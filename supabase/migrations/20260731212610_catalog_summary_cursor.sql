create index listing_media_catalog_cover_idx
on public.listing_media (
  listing_id,
  is_cover desc,
  display_order asc,
  id asc
)
include (url);

create or replace function public.list_catalog_summaries(
  p_city_id text default null,
  p_category_id text default null,
  p_listing_type text default null,
  p_listing_class text default null,
  p_search_query text default null,
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
  price_from_xof integer,
  rating_avg numeric,
  likes_count integer,
  verified boolean,
  sponsored_until timestamptz,
  is_sponsored_placement boolean,
  row_cursor text
)
language plpgsql
stable
security invoker
set search_path = ''
as $$
declare
  v_city_id text;
  v_category_id text;
  v_listing_type public.listing_type;
  v_listing_class public.listing_class;
  v_search_query text;
  v_search_pattern text;
  v_fingerprint text;
  v_cursor_payload jsonb;
  v_as_of timestamptz := statement_timestamp();
  v_cursor_sponsored_until timestamptz;
  v_cursor_rating numeric;
  v_cursor_likes integer;
  v_cursor_published_at timestamptz;
  v_cursor_id uuid;
begin
  if p_limit is null or p_limit < 1 or p_limit > 50 then
    raise exception using
      errcode = '22023',
      message = 'p_limit must be between 1 and 50';
  end if;

  if p_city_id is not null then
    v_city_id := btrim(p_city_id);

    if v_city_id = '' or char_length(v_city_id) > 100 then
      raise exception using
        errcode = '22023',
        message = 'p_city_id is invalid';
    end if;

    if not exists (
      select 1
      from public.cities city
      where city.id = v_city_id
    ) then
      raise exception using
        errcode = '22023',
        message = 'p_city_id is unknown';
    end if;
  end if;

  if p_category_id is not null then
    v_category_id := btrim(p_category_id);

    if v_category_id = '' or char_length(v_category_id) > 100 then
      raise exception using
        errcode = '22023',
        message = 'p_category_id is invalid';
    end if;

    if not exists (
      select 1
      from public.categories category
      where category.id = v_category_id
    ) then
      raise exception using
        errcode = '22023',
        message = 'p_category_id is unknown';
    end if;
  end if;

  if p_listing_type is not null then
    begin
      v_listing_type := lower(btrim(p_listing_type))::public.listing_type;
    exception
      when invalid_text_representation then
        raise exception using
          errcode = '22023',
          message = 'p_listing_type is invalid';
    end;
  end if;

  if p_listing_class is not null then
    begin
      v_listing_class := lower(btrim(p_listing_class))::public.listing_class;
    exception
      when invalid_text_representation then
        raise exception using
          errcode = '22023',
          message = 'p_listing_class is invalid';
    end;
  end if;

  if p_search_query is not null then
    v_search_query := lower(btrim(p_search_query));

    if v_search_query = ''
      or char_length(v_search_query) > 120
      or v_search_query ~ '[[:cntrl:]]'
    then
      raise exception using
        errcode = '22023',
        message = 'p_search_query is invalid';
    end if;

    v_search_pattern := replace(v_search_query, E'\\', E'\\\\');
    v_search_pattern := replace(v_search_pattern, '%', E'\\%');
    v_search_pattern := replace(v_search_pattern, '_', E'\\_');
  end if;

  v_fingerprint := md5(
    jsonb_build_object(
      'category_id', v_category_id,
      'city_id', v_city_id,
      'listing_class', v_listing_class::text,
      'listing_type', v_listing_type::text,
      'search_query', v_search_query
    )::text
  );

  if p_cursor is not null then
    if btrim(p_cursor) = ''
      or char_length(p_cursor) > 4096
      or p_cursor ~ '[[:space:]]'
    then
      raise exception using
        errcode = '22023',
        message = 'p_cursor is invalid';
    end if;

    begin
      v_cursor_payload := convert_from(decode(p_cursor, 'base64'), 'UTF8')::jsonb;
    exception
      when others then
        raise exception using
          errcode = '22023',
          message = 'p_cursor is malformed';
    end;

    if jsonb_typeof(v_cursor_payload) <> 'object'
      or jsonb_typeof(v_cursor_payload -> 'v') <> 'number'
      or v_cursor_payload ->> 'v' <> '1'
    then
      raise exception using
        errcode = '22023',
        message = 'p_cursor version is unsupported';
    end if;

    if jsonb_typeof(v_cursor_payload -> 'as_of') <> 'string'
      or jsonb_typeof(v_cursor_payload -> 'fingerprint') <> 'string'
      or not (v_cursor_payload ? 'sponsored_until')
      or jsonb_typeof(v_cursor_payload -> 'sponsored_until') not in ('null', 'string')
      or jsonb_typeof(v_cursor_payload -> 'rating') <> 'number'
      or jsonb_typeof(v_cursor_payload -> 'likes') <> 'number'
      or jsonb_typeof(v_cursor_payload -> 'published_at') <> 'string'
      or jsonb_typeof(v_cursor_payload -> 'id') <> 'string'
    then
      raise exception using
        errcode = '22023',
        message = 'p_cursor fields are malformed';
    end if;

    if v_cursor_payload ->> 'fingerprint' <> v_fingerprint then
      raise exception using
        errcode = '22023',
        message = 'p_cursor does not match catalog filters';
    end if;

    begin
      v_as_of := (v_cursor_payload ->> 'as_of')::timestamptz;
      v_cursor_sponsored_until := case
        when jsonb_typeof(v_cursor_payload -> 'sponsored_until') = 'null' then null
        else (v_cursor_payload ->> 'sponsored_until')::timestamptz
      end;
      v_cursor_rating := (v_cursor_payload ->> 'rating')::numeric;
      v_cursor_likes := (v_cursor_payload ->> 'likes')::integer;
      v_cursor_published_at := (v_cursor_payload ->> 'published_at')::timestamptz;
      v_cursor_id := (v_cursor_payload ->> 'id')::uuid;
    exception
      when others then
        raise exception using
          errcode = '22023',
          message = 'p_cursor fields are malformed';
    end;

    if not isfinite(v_as_of)
      or not isfinite(v_cursor_published_at)
      or (
        v_cursor_sponsored_until is not null
        and (
          not isfinite(v_cursor_sponsored_until)
          or v_cursor_sponsored_until <= v_as_of
        )
      )
      or v_cursor_rating < -1
      or v_cursor_rating > 5
      or v_cursor_likes < 0
    then
      raise exception using
        errcode = '22023',
        message = 'p_cursor fields are invalid';
    end if;
  end if;

  return query
  with ranked_catalog as (
    select
      listing.id,
      listing.type,
      listing.listing_class,
      listing.status,
      listing.name,
      listing.city_id,
      listing.category_id,
      cover.url as cover_image_url,
      listing.price_from_xof,
      listing.rating_avg,
      listing.likes_count,
      listing.verified,
      listing.sponsored_until,
      case
        when listing.sponsored_until > v_as_of then listing.sponsored_until
        else null
      end as sort_sponsored_until,
      coalesce(listing.rating_avg, -1::numeric) as sort_rating,
      coalesce(listing.published_at, listing.created_at) as sort_published_at
    from public.listings listing
    left join lateral (
      select media.url
      from public.listing_media media
      where media.listing_id = listing.id
      order by
        media.is_cover desc,
        media.display_order asc,
        media.id asc
      limit 1
    ) cover on true
    where listing.status = 'publie'::public.listing_status
      and (v_city_id is null or listing.city_id = v_city_id)
      and (v_category_id is null or listing.category_id = v_category_id)
      and (v_listing_type is null or listing.type = v_listing_type)
      and (v_listing_class is null or listing.listing_class = v_listing_class)
      and (
        v_search_pattern is null
        or listing.name ilike '%' || v_search_pattern || '%' escape E'\\'
      )
  ),
  catalog_page as (
    select ranked.*
    from ranked_catalog ranked
    where p_cursor is null
      or (
        coalesce(ranked.sort_sponsored_until, '-infinity'::timestamptz),
        ranked.sort_rating,
        ranked.likes_count,
        ranked.sort_published_at,
        ranked.id
      ) < (
        coalesce(v_cursor_sponsored_until, '-infinity'::timestamptz),
        v_cursor_rating,
        v_cursor_likes,
        v_cursor_published_at,
        v_cursor_id
      )
    order by
      ranked.sort_sponsored_until desc nulls last,
      ranked.sort_rating desc,
      ranked.likes_count desc,
      ranked.sort_published_at desc,
      ranked.id desc
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
    page.price_from_xof,
    page.rating_avg,
    page.likes_count,
    page.verified,
    page.sponsored_until,
    page.sort_sponsored_until is not null as is_sponsored_placement,
    replace(
      replace(
        encode(
          convert_to(
            jsonb_build_object(
              'v', 1,
              'as_of', v_as_of,
              'fingerprint', v_fingerprint,
              'sponsored_until', page.sort_sponsored_until,
              'rating', page.sort_rating,
              'likes', page.likes_count,
              'published_at', page.sort_published_at,
              'id', page.id
            )::text,
            'UTF8'
          ),
          'base64'
        ),
        chr(10),
        ''
      ),
      chr(13),
      ''
    ) as row_cursor
  from catalog_page page
  order by
    page.sort_sponsored_until desc nulls last,
    page.sort_rating desc,
    page.likes_count desc,
    page.sort_published_at desc,
    page.id desc;
end;
$$;

revoke execute on function public.list_catalog_summaries(
  text,
  text,
  text,
  text,
  text,
  text,
  integer
)
from public, anon, authenticated, service_role;

grant execute on function public.list_catalog_summaries(
  text,
  text,
  text,
  text,
  text,
  text,
  integer
)
to anon, authenticated;
