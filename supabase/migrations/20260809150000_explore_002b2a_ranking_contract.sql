set lock_timeout = '5s';

create or replace function public.list_catalog_summaries_v2(
  p_listing_type text,
  p_city_id text default null,
  p_category_id text default null,
  p_listing_class text default null,
  p_sort text default 'default',
  p_price_min_xof integer default null,
  p_price_max_xof integer default null,
  p_event_window_start timestamptz default null,
  p_event_window_end timestamptz default null,
  p_cursor text default null,
  p_limit integer default 20
)
returns table (
  id uuid,
  type public.listing_type,
  listing_class public.listing_class,
  status public.listing_status,
  name text,
  city_id text,
  category_id text,
  cover_image_url text,
  cover_image_alt text,
  price_from_xof integer,
  rating_avg numeric,
  views_count integer,
  likes_count integer,
  verified boolean,
  sponsored_until timestamptz,
  event_start_at timestamptz,
  event_end_at timestamptz,
  is_event_ended boolean,
  is_sponsored_placement boolean,
  snapshot_at timestamptz,
  row_cursor text
)
language plpgsql
stable
security invoker
set search_path = ''
as $$
declare
  v_request_as_of timestamptz := pg_catalog.statement_timestamp();
  v_as_of timestamptz := v_request_as_of;
  v_listing_type public.listing_type;
  v_city_id text;
  v_category_id text;
  v_listing_class public.listing_class;
  v_requested_sort text;
  v_resolved_sort text;
  v_fingerprint text;
  v_cursor_payload jsonb;
  v_cursor_sponsored boolean;
  v_cursor_phase integer;
  v_cursor_distance bigint;
  v_cursor_popularity bigint;
  v_cursor_likes integer;
  v_cursor_views integer;
  v_cursor_published_at timestamptz;
  v_cursor_id uuid;
begin
  if p_limit is null or p_limit < 1 or p_limit > 50 then
    raise invalid_parameter_value
      using message = 'p_limit must be between 1 and 50';
  end if;

  if p_listing_type is null or pg_catalog.btrim(p_listing_type) = '' then
    raise invalid_parameter_value
      using message = 'p_listing_type is invalid';
  end if;

  begin
    v_listing_type :=
      pg_catalog.lower(pg_catalog.btrim(p_listing_type))::public.listing_type;
  exception
    when invalid_text_representation then
      raise invalid_parameter_value
        using message = 'p_listing_type is invalid';
  end;

  if p_city_id is not null then
    v_city_id := pg_catalog.btrim(p_city_id);

    if v_city_id = '' or pg_catalog.char_length(v_city_id) > 100 then
      raise invalid_parameter_value
        using message = 'p_city_id is invalid';
    end if;

    if not exists (
      select 1
      from public.cities as city
      where city.id = v_city_id
    ) then
      raise invalid_parameter_value
        using message = 'p_city_id is unknown';
    end if;
  end if;

  if p_category_id is not null then
    v_category_id := pg_catalog.btrim(p_category_id);

    if v_category_id = '' or pg_catalog.char_length(v_category_id) > 100 then
      raise invalid_parameter_value
        using message = 'p_category_id is invalid';
    end if;

    if not exists (
      select 1
      from public.categories as category
      where category.id = v_category_id
        and category.listing_type = v_listing_type
    ) then
      raise invalid_parameter_value
        using message = 'p_category_id is unknown or does not match p_listing_type';
    end if;
  end if;

  if p_listing_class is not null then
    if pg_catalog.btrim(p_listing_class) = '' then
      raise invalid_parameter_value
        using message = 'p_listing_class is invalid';
    end if;

    begin
      v_listing_class :=
        pg_catalog.lower(pg_catalog.btrim(p_listing_class))::public.listing_class;
    exception
      when invalid_text_representation then
        raise invalid_parameter_value
          using message = 'p_listing_class is invalid';
    end;
  end if;

  if p_sort is null or pg_catalog.btrim(p_sort) = '' then
    raise invalid_parameter_value
      using message = 'p_sort is invalid';
  end if;

  v_requested_sort := pg_catalog.lower(pg_catalog.btrim(p_sort));
  if v_requested_sort not in ('default', 'popularity', 'temporal_proximity') then
    raise invalid_parameter_value
      using message = 'p_sort is invalid';
  end if;

  v_resolved_sort := case
    when v_requested_sort = 'default'
      and v_listing_type = 'evenement'::public.listing_type
    then 'temporal_proximity'
    when v_requested_sort = 'default' then 'popularity'
    else v_requested_sort
  end;

  if v_resolved_sort = 'temporal_proximity'
    and v_listing_type <> 'evenement'::public.listing_type
  then
    raise invalid_parameter_value
      using message = 'p_sort is not supported for p_listing_type';
  end if;

  if p_price_min_xof < 0
    or p_price_max_xof < 0
    or p_price_min_xof > p_price_max_xof
  then
    raise invalid_parameter_value
      using message = 'price filters are invalid';
  end if;

  if (p_price_min_xof is not null or p_price_max_xof is not null)
    and v_listing_type <> 'etablissement'::public.listing_type
  then
    raise invalid_parameter_value
      using message = 'price filters require establishment listings';
  end if;

  if (p_event_window_start is null) <> (p_event_window_end is null) then
    raise invalid_parameter_value
      using message = 'event window bounds must be provided together';
  end if;

  if p_event_window_start is not null
    and v_listing_type <> 'evenement'::public.listing_type
  then
    raise invalid_parameter_value
      using message = 'event window requires event listings';
  end if;

  if p_event_window_start is not null
    and (
      p_event_window_start < '0001-01-01 00:00:00+00'::timestamptz
      or p_event_window_start >= '10000-01-01 00:00:00+00'::timestamptz
      or p_event_window_end < '0001-01-01 00:00:00+00'::timestamptz
      or p_event_window_end >= '10000-01-01 00:00:00+00'::timestamptz
      or p_event_window_start >= p_event_window_end
    )
  then
    raise invalid_parameter_value
      using message = 'event window is invalid';
  end if;

  v_fingerprint := pg_catalog.md5(
    pg_catalog.jsonb_build_object(
      'contract', 'catalog-summaries-v2',
      'listing_type', v_listing_type::text,
      'city_id', v_city_id,
      'category_id', v_category_id,
      'listing_class', v_listing_class::text,
      'sort', v_resolved_sort,
      'price_min_xof', p_price_min_xof,
      'price_max_xof', p_price_max_xof,
      'event_window_start_epoch_us', case
        when p_event_window_start is null then null
        else (
          extract(epoch from p_event_window_start) * 1000000
        )::bigint
      end,
      'event_window_end_epoch_us', case
        when p_event_window_end is null then null
        else (
          extract(epoch from p_event_window_end) * 1000000
        )::bigint
      end,
      'limit', p_limit,
      'popularity_like_weight', 5,
      'sponsor_policy', 'top-2-establishment-commercial'
    )::text
  );

  if p_cursor is not null then
    if pg_catalog.btrim(p_cursor) = ''
      or pg_catalog.char_length(p_cursor) > 4096
      or p_cursor ~ '[[:space:]]'
    then
      raise invalid_parameter_value
        using message = 'p_cursor is invalid';
    end if;

    begin
      v_cursor_payload := pg_catalog.convert_from(
        pg_catalog.decode(p_cursor, 'base64'),
        'UTF8'
      )::jsonb;
    exception
      when others then
        raise invalid_parameter_value
          using message = 'p_cursor is malformed';
    end;

    if pg_catalog.jsonb_typeof(v_cursor_payload) is distinct from 'object'
      or pg_catalog.jsonb_typeof(v_cursor_payload -> 'v') is distinct from 'number'
      or v_cursor_payload ->> 'v' is distinct from '2'
    then
      raise invalid_parameter_value
        using message = 'p_cursor version is unsupported';
    end if;

    if pg_catalog.jsonb_typeof(v_cursor_payload -> 'contract') is distinct from 'string'
      or pg_catalog.jsonb_typeof(v_cursor_payload -> 'as_of') is distinct from 'string'
      or pg_catalog.jsonb_typeof(v_cursor_payload -> 'fingerprint') is distinct from 'string'
      or pg_catalog.jsonb_typeof(v_cursor_payload -> 'sponsored') is distinct from 'boolean'
      or pg_catalog.jsonb_typeof(v_cursor_payload -> 'phase') is distinct from 'number'
      or pg_catalog.jsonb_typeof(v_cursor_payload -> 'distance') is distinct from 'number'
      or pg_catalog.jsonb_typeof(v_cursor_payload -> 'popularity') is distinct from 'number'
      or pg_catalog.jsonb_typeof(v_cursor_payload -> 'likes') is distinct from 'number'
      or pg_catalog.jsonb_typeof(v_cursor_payload -> 'views') is distinct from 'number'
      or pg_catalog.jsonb_typeof(v_cursor_payload -> 'published_at') is distinct from 'string'
      or pg_catalog.jsonb_typeof(v_cursor_payload -> 'id') is distinct from 'string'
      or (v_cursor_payload ->> 'phase') !~ '^[0-9]+$'
      or (v_cursor_payload ->> 'distance') !~ '^-?[0-9]+$'
      or (v_cursor_payload ->> 'popularity') !~ '^[0-9]+$'
      or (v_cursor_payload ->> 'likes') !~ '^[0-9]+$'
      or (v_cursor_payload ->> 'views') !~ '^[0-9]+$'
      or exists (
        select 1
        from pg_catalog.jsonb_object_keys(v_cursor_payload)
          as cursor_key(key_name)
        where cursor_key.key_name not in (
          'v',
          'contract',
          'as_of',
          'fingerprint',
          'sponsored',
          'phase',
          'distance',
          'popularity',
          'likes',
          'views',
          'published_at',
          'id'
        )
      )
    then
      raise invalid_parameter_value
        using message = 'p_cursor fields are malformed';
    end if;

    if v_cursor_payload ->> 'contract' is distinct from 'catalog-summaries-v2' then
      raise invalid_parameter_value
        using message = 'p_cursor version is unsupported';
    end if;

    if v_cursor_payload ->> 'fingerprint' is distinct from v_fingerprint then
      raise invalid_parameter_value
        using message = 'p_cursor does not match catalog filters';
    end if;

    begin
      v_as_of := (v_cursor_payload ->> 'as_of')::timestamptz;
      v_cursor_sponsored := (v_cursor_payload ->> 'sponsored')::boolean;
      v_cursor_phase := (v_cursor_payload ->> 'phase')::integer;
      v_cursor_distance := (v_cursor_payload ->> 'distance')::bigint;
      v_cursor_popularity := (v_cursor_payload ->> 'popularity')::bigint;
      v_cursor_likes := (v_cursor_payload ->> 'likes')::integer;
      v_cursor_views := (v_cursor_payload ->> 'views')::integer;
      v_cursor_published_at :=
        (v_cursor_payload ->> 'published_at')::timestamptz;
      v_cursor_id := (v_cursor_payload ->> 'id')::uuid;
    exception
      when others then
        raise invalid_parameter_value
          using message = 'p_cursor fields are malformed';
    end;

    if v_as_of < '0001-01-01 00:00:00+00'::timestamptz
      or v_as_of >= '10000-01-01 00:00:00+00'::timestamptz
      or v_as_of > v_request_as_of
      or v_cursor_published_at < '0001-01-01 00:00:00+00'::timestamptz
      or v_cursor_published_at >= '10000-01-01 00:00:00+00'::timestamptz
      or v_cursor_published_at > v_as_of
      or v_cursor_phase not between 0 and 2
      or v_cursor_distance > 0
      or v_cursor_distance <= -315537897600000000::bigint
      or v_cursor_popularity < 0
      or v_cursor_likes < 0
      or v_cursor_views < 0
      or v_cursor_popularity
        <> v_cursor_views::bigint + (5 * v_cursor_likes::bigint)
      or (
        v_resolved_sort = 'popularity'
        and (v_cursor_phase <> 0 or v_cursor_distance <> 0)
      )
      or (
        v_resolved_sort = 'temporal_proximity'
        and v_cursor_phase in (1, 2)
        and v_cursor_distance = 0
      )
      or (
        v_cursor_sponsored
        and (
          v_listing_type <> 'etablissement'::public.listing_type
          or (
            v_listing_class is not null
            and v_listing_class <> 'commercial'::public.listing_class
          )
        )
      )
    then
      raise invalid_parameter_value
        using message = 'p_cursor fields are invalid';
    end if;
  end if;

  return query
  with base_candidates as materialized (
    select
      listing.id,
      listing.type,
      listing.listing_class,
      listing.status,
      listing.name,
      listing.city_id,
      listing.category_id,
      cover.url as cover_image_url,
      cover.alt as cover_image_alt,
      listing.price_from_xof,
      listing.rating_avg,
      listing.views_count,
      listing.likes_count,
      listing.verified,
      listing.sponsored_until,
      event_detail.start_at as event_start_at,
      event_detail.end_at as event_end_at,
      case
        when listing.type = 'evenement'::public.listing_type
          and event_detail.start_at is not null
        then v_as_of >= coalesce(
          event_detail.end_at,
          event_detail.start_at
        )
        else false
      end as is_event_ended,
      listing.views_count::bigint
        + (5 * listing.likes_count::bigint) as sort_popularity,
      listing.published_at as sort_published_at,
      listing.type = 'etablissement'::public.listing_type
        and listing.listing_class = 'commercial'::public.listing_class
        and listing.sponsored_until > v_as_of as sponsor_eligible
    from public.listings as listing
    left join public.event_details as event_detail
      on event_detail.listing_id = listing.id
    left join lateral (
      select media.url, media.alt
      from public.listing_media as media
      where media.listing_id = listing.id
      order by media.is_cover desc, media.display_order asc
      limit 1
    ) as cover on true
    where listing.type = v_listing_type
      and listing.status = 'publie'::public.listing_status
      and listing.published_at is not null
      and listing.published_at <= v_as_of
      and (v_city_id is null or listing.city_id = v_city_id)
      and (v_category_id is null or listing.category_id = v_category_id)
      and (
        v_listing_class is null
        or listing.listing_class = v_listing_class
      )
      and (
        p_price_min_xof is null
        or listing.price_from_xof >= p_price_min_xof
      )
      and (
        p_price_max_xof is null
        or listing.price_from_xof <= p_price_max_xof
      )
      and (
        listing.type <> 'evenement'::public.listing_type
        or event_detail.listing_id is not null
      )
      and (
        p_event_window_start is null
        or (
          coalesce(event_detail.end_at, event_detail.start_at)
            = event_detail.start_at
          and event_detail.start_at >= p_event_window_start
          and event_detail.start_at < p_event_window_end
        )
        or (
          event_detail.end_at > event_detail.start_at
          and event_detail.start_at < p_event_window_end
          and event_detail.end_at > p_event_window_start
        )
      )
  ),
  scored_candidates as materialized (
    select
      candidate.*,
      case
        when v_resolved_sort <> 'temporal_proximity' then 0
        when candidate.event_start_at <= v_as_of
          and v_as_of < coalesce(
            candidate.event_end_at,
            candidate.event_start_at
          )
        then 2
        when candidate.event_start_at > v_as_of then 1
        else 0
      end as sort_phase,
      case
        when v_resolved_sort <> 'temporal_proximity' then 0::bigint
        when candidate.event_start_at <= v_as_of
          and v_as_of < coalesce(
            candidate.event_end_at,
            candidate.event_start_at
          )
        then -(
          extract(
            epoch from (
              coalesce(
                candidate.event_end_at,
                candidate.event_start_at
              ) - v_as_of
            )
          ) * 1000000
        )::bigint
        when candidate.event_start_at > v_as_of then -(
          extract(epoch from (candidate.event_start_at - v_as_of)) * 1000000
        )::bigint
        else -(
          extract(
            epoch from (
              v_as_of - coalesce(
                candidate.event_end_at,
                candidate.event_start_at
              )
            )
          ) * 1000000
        )::bigint
      end as sort_distance
    from base_candidates as candidate
  ),
  selected_sponsors as materialized (
    select candidate.id
    from scored_candidates as candidate
    where candidate.sponsor_eligible
    order by
      candidate.sort_phase desc,
      candidate.sort_distance desc,
      candidate.sort_popularity desc,
      candidate.likes_count desc,
      candidate.views_count desc,
      candidate.sort_published_at desc,
      candidate.id desc
    limit 2
  ),
  ordered_candidates as materialized (
    select
      candidate.*,
      selected_sponsor.id is not null as sort_sponsored
    from scored_candidates as candidate
    left join selected_sponsors as selected_sponsor
      on selected_sponsor.id = candidate.id
  ),
  catalog_page as (
    select candidate.*
    from ordered_candidates as candidate
    where p_cursor is null
      or (
        candidate.sort_sponsored,
        candidate.sort_phase,
        candidate.sort_distance,
        candidate.sort_popularity,
        candidate.likes_count,
        candidate.views_count,
        candidate.sort_published_at,
        candidate.id
      ) < (
        v_cursor_sponsored,
        v_cursor_phase,
        v_cursor_distance,
        v_cursor_popularity,
        v_cursor_likes,
        v_cursor_views,
        v_cursor_published_at,
        v_cursor_id
      )
    order by
      candidate.sort_sponsored desc,
      candidate.sort_phase desc,
      candidate.sort_distance desc,
      candidate.sort_popularity desc,
      candidate.likes_count desc,
      candidate.views_count desc,
      candidate.sort_published_at desc,
      candidate.id desc
    limit (p_limit + 1)
  )
  select
    page.id,
    page.type,
    page.listing_class,
    page.status,
    page.name,
    page.city_id,
    page.category_id,
    page.cover_image_url,
    page.cover_image_alt,
    page.price_from_xof,
    page.rating_avg,
    page.views_count,
    page.likes_count,
    page.verified,
    page.sponsored_until,
    page.event_start_at,
    page.event_end_at,
    page.is_event_ended,
    page.sort_sponsored as is_sponsored_placement,
    v_as_of as snapshot_at,
    pg_catalog.replace(
      pg_catalog.replace(
        pg_catalog.encode(
          pg_catalog.convert_to(
            pg_catalog.jsonb_build_object(
              'v', 2,
              'contract', 'catalog-summaries-v2',
              'as_of', v_as_of,
              'fingerprint', v_fingerprint,
              'sponsored', page.sort_sponsored,
              'phase', page.sort_phase,
              'distance', page.sort_distance,
              'popularity', page.sort_popularity,
              'likes', page.likes_count,
              'views', page.views_count,
              'published_at', page.sort_published_at,
              'id', page.id
            )::text,
            'UTF8'
          ),
          'base64'
        ),
        pg_catalog.chr(10),
        ''
      ),
      pg_catalog.chr(13),
      ''
    ) as row_cursor
  from catalog_page as page
  order by
    page.sort_sponsored desc,
    page.sort_phase desc,
    page.sort_distance desc,
    page.sort_popularity desc,
    page.likes_count desc,
    page.views_count desc,
    page.sort_published_at desc,
    page.id desc;
end;
$$;

comment on function public.list_catalog_summaries_v2(
  text,
  text,
  text,
  text,
  text,
  integer,
  integer,
  timestamptz,
  timestamptz,
  text,
  integer
) is
  'Versioned published-catalog ranking contract. Output order is v1 card fields enriched with cover alt, views, event timing/end state and snapshot_at before row_cursor. Cursor v2 is an exact catalog-summaries-v2 JSON object ordered DESC by sponsored, phase, negative microsecond distance, popularity, likes, views, published_at and id. At most the first two eligible establishment listings are sponsored placements; remaining sponsored listings are organic.';

revoke execute on function public.list_catalog_summaries_v2(
  text,
  text,
  text,
  text,
  text,
  integer,
  integer,
  timestamptz,
  timestamptz,
  text,
  integer
)
from public, anon, authenticated, service_role;

grant execute on function public.list_catalog_summaries_v2(
  text,
  text,
  text,
  text,
  text,
  integer,
  integer,
  timestamptz,
  timestamptz,
  text,
  integer
)
to anon, authenticated;

reset lock_timeout;
