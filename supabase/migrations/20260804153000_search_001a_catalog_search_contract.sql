begin;

set local lock_timeout = '5s';

create or replace function app_private.catalog_search_normalize(search_text text)
returns text
language sql
immutable
parallel safe
set search_path = ''
as $$
  select pg_catalog.regexp_replace(
    pg_catalog.regexp_replace(
      pg_catalog.replace(
        pg_catalog.replace(
          pg_catalog.translate(
            pg_catalog.lower(coalesce(search_text, '')),
            'àáâãäåçèéêëìíîïñòóôõöøùúûüýÿ',
            'aaaaaaceeeeiiiinoooooouuuuyy'
          ),
          'œ',
          'oe'
        ),
        'æ',
        'ae'
      ),
      U&'[\0300-\036f]',
      '',
      'g'
    ),
    '[^[:alnum:]]+',
    ' ',
    'g'
  );
$$;

comment on function app_private.catalog_search_normalize(text) is
  'Immutable lowercase and diacritic folding shared by indexed catalog search expressions.';

revoke all on function app_private.catalog_search_normalize(text)
from public, anon, authenticated, service_role;
grant execute on function app_private.catalog_search_normalize(text)
to authenticated, service_role;

create or replace function app_private.catalog_search_document(
  listing_name text,
  listing_tags text[]
)
returns tsvector
language sql
immutable
parallel safe
set search_path = ''
as $$
  select pg_catalog.to_tsvector(
    'simple'::regconfig,
    app_private.catalog_search_normalize(
      coalesce(listing_name, '')
      || ' '
      || coalesce(pg_catalog.array_to_string(listing_tags, ' '), '')
    )
  );
$$;

comment on function app_private.catalog_search_document(text, text[]) is
  'Immutable listing-name and text-tag projection used only by the stored catalog search column.';

revoke all on function app_private.catalog_search_document(text, text[])
from public, anon, authenticated, service_role;
grant execute on function app_private.catalog_search_document(text, text[])
to authenticated, service_role;

alter table public.listings
add column catalog_search_document tsvector generated always as (
  app_private.catalog_search_document(name, tags)
) stored;

create index listings_catalog_search_document_published_idx
on public.listings using gin (catalog_search_document)
where status = 'publie'::public.listing_status
  and published_at is not null;

create index cities_catalog_search_name_idx
on public.cities using gin (
  pg_catalog.to_tsvector(
    'simple'::regconfig,
    pg_catalog.regexp_replace(
      pg_catalog.regexp_replace(
        pg_catalog.replace(
          pg_catalog.replace(
            pg_catalog.translate(
              pg_catalog.lower(name),
              'àáâãäåçèéêëìíîïñòóôõöøùúûüýÿ',
              'aaaaaaceeeeiiiinoooooouuuuyy'
            ),
            'œ',
            'oe'
          ),
          'æ',
          'ae'
        ),
        U&'[\0300-\036f]',
        '',
        'g'
      ),
      '[^[:alnum:]]+',
      ' ',
      'g'
    )
  )
);

create index categories_catalog_search_terms_idx
on public.categories using gin (
  pg_catalog.to_tsvector(
    'simple'::regconfig,
    pg_catalog.regexp_replace(
      pg_catalog.regexp_replace(
        pg_catalog.replace(
          pg_catalog.replace(
            pg_catalog.translate(
              pg_catalog.lower(id || ' ' || subtype || ' ' || name_key),
              'àáâãäåçèéêëìíîïñòóôõöøùúûüýÿ',
              'aaaaaaceeeeiiiinoooooouuuuyy'
            ),
            'œ',
            'oe'
          ),
          'æ',
          'ae'
        ),
        U&'[\0300-\036f]',
        '',
        'g'
      ),
      '[^[:alnum:]]+',
      ' ',
      'g'
    )
  )
);

comment on index public.listings_catalog_search_document_published_idx is
  'Bounds published catalog name/tag keyword lookup before card projection and media selection.';
comment on index public.cities_catalog_search_name_idx is
  'Resolves matching city identifiers before joining published catalog rows.';
comment on index public.categories_catalog_search_terms_idx is
  'Resolves matching category id, subtype, and name-key terms before joining published catalog rows.';

-- The versioned SECURITY INVOKER RPC reads this derived column under the caller's
-- RLS context. Its lexemes contain only the already-public listing name and tags.
grant select (catalog_search_document) on table public.listings to anon, authenticated;

create or replace function public.search_catalog_summaries_v1(
  p_search_query text,
  p_city_id text default null,
  p_category_id text default null,
  p_listing_type text default null,
  p_listing_class text default null,
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

  if p_search_query is null or p_search_query ~ '[[:cntrl:]]' then
    raise exception using
      errcode = '22023',
      message = 'p_search_query is invalid';
  end if;

  v_search_query := pg_catalog.btrim(p_search_query);
  if pg_catalog.char_length(v_search_query) < 1
    or pg_catalog.char_length(v_search_query) > 120
  then
    raise exception using
      errcode = '22023',
      message = 'p_search_query is invalid';
  end if;
  v_search_query := pg_catalog.regexp_replace(
    pg_catalog.regexp_replace(
      pg_catalog.replace(
        pg_catalog.replace(
          pg_catalog.translate(
            pg_catalog.lower(v_search_query),
            'àáâãäåçèéêëìíîïñòóôõöøùúûüýÿ',
            'aaaaaaceeeeiiiinoooooouuuuyy'
          ),
          'œ',
          'oe'
        ),
        'æ',
        'ae'
      ),
      U&'[\0300-\036f]',
      '',
      'g'
    ),
    '[^[:alnum:]]+',
    ' ',
    'g'
  );
  if p_city_id is not null then
    v_city_id := pg_catalog.btrim(p_city_id);
    if v_city_id = '' or pg_catalog.char_length(v_city_id) > 100 then
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
    v_category_id := pg_catalog.btrim(p_category_id);
    if v_category_id = '' or pg_catalog.char_length(v_category_id) > 100 then
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
      v_listing_type := pg_catalog.lower(pg_catalog.btrim(p_listing_type))::public.listing_type;
    exception
      when invalid_text_representation then
        raise exception using
          errcode = '22023',
          message = 'p_listing_type is invalid';
    end;
  end if;

  if p_listing_class is not null then
    begin
      v_listing_class := pg_catalog.lower(pg_catalog.btrim(p_listing_class))::public.listing_class;
    exception
      when invalid_text_representation then
        raise exception using
          errcode = '22023',
          message = 'p_listing_class is invalid';
    end;
  end if;

  v_fingerprint := pg_catalog.md5(
    pg_catalog.jsonb_build_object(
      'category_id', v_category_id,
      'city_id', v_city_id,
      'listing_class', v_listing_class::text,
      'listing_type', v_listing_type::text,
      'search_query', v_search_query
    )::text
  );

  if p_cursor is not null then
    if pg_catalog.btrim(p_cursor) = ''
      or pg_catalog.char_length(p_cursor) > 4096
      or p_cursor ~ '[[:space:]]'
    then
      raise exception using
        errcode = '22023',
        message = 'p_cursor is invalid';
    end if;

    begin
      v_cursor_payload := pg_catalog.convert_from(
        pg_catalog.decode(p_cursor, 'base64'),
        'UTF8'
      )::jsonb;
    exception
      when others then
        raise exception using
          errcode = '22023',
          message = 'p_cursor is malformed';
    end;

    if pg_catalog.jsonb_typeof(v_cursor_payload) is distinct from 'object'
      or pg_catalog.jsonb_typeof(v_cursor_payload -> 'v') is distinct from 'number'
      or v_cursor_payload ->> 'v' is distinct from '1'
    then
      raise exception using
        errcode = '22023',
        message = 'p_cursor version is unsupported';
    end if;

    if pg_catalog.jsonb_typeof(v_cursor_payload -> 'as_of') is distinct from 'string'
      or pg_catalog.jsonb_typeof(v_cursor_payload -> 'fingerprint') is distinct from 'string'
      or not (v_cursor_payload ? 'sponsored_until')
      or (
        pg_catalog.jsonb_typeof(v_cursor_payload -> 'sponsored_until') is distinct from 'null'
        and pg_catalog.jsonb_typeof(v_cursor_payload -> 'sponsored_until') is distinct from 'string'
      )
      or pg_catalog.jsonb_typeof(v_cursor_payload -> 'rating') is distinct from 'number'
      or pg_catalog.jsonb_typeof(v_cursor_payload -> 'likes') is distinct from 'number'
      or pg_catalog.jsonb_typeof(v_cursor_payload -> 'published_at') is distinct from 'string'
      or pg_catalog.jsonb_typeof(v_cursor_payload -> 'id') is distinct from 'string'
    then
      raise exception using
        errcode = '22023',
        message = 'p_cursor fields are malformed';
    end if;

    if v_cursor_payload ->> 'fingerprint' <> v_fingerprint then
      raise exception using
        errcode = '22023',
        message = 'p_cursor does not match catalog search parameters';
    end if;

    begin
      v_as_of := (v_cursor_payload ->> 'as_of')::timestamptz;
      v_cursor_sponsored_until := case
        when pg_catalog.jsonb_typeof(v_cursor_payload -> 'sponsored_until') = 'null' then null
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

    if v_as_of < '0001-01-01 00:00:00+00'::timestamptz
      or v_as_of > statement_timestamp()
      or v_as_of >= '10000-01-01 00:00:00+00'::timestamptz
      or v_cursor_published_at < '0001-01-01 00:00:00+00'::timestamptz
      or v_cursor_published_at >= '10000-01-01 00:00:00+00'::timestamptz
      or (
        v_cursor_sponsored_until is not null
        and (
          v_cursor_sponsored_until < '0001-01-01 00:00:00+00'::timestamptz
          or v_cursor_sponsored_until >= '10000-01-01 00:00:00+00'::timestamptz
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
  with query_lexemes as materialized (
    select query_lexeme.lexeme
    from pg_catalog.unnest(
      pg_catalog.tsvector_to_array(
        pg_catalog.to_tsvector('simple'::regconfig, v_search_query)
      )
    ) as query_lexeme(lexeme)
  ),
  search_lexeme_matches as materialized (
    select listing.id, query_lexeme.lexeme
    from query_lexemes query_lexeme
    join public.listings listing
      on listing.catalog_search_document
        @@ pg_catalog.plainto_tsquery('simple'::regconfig, query_lexeme.lexeme)
    where listing.status = 'publie'::public.listing_status
      and listing.published_at is not null

    union

    select listing.id, query_lexeme.lexeme
    from query_lexemes query_lexeme
    join public.cities city
      on pg_catalog.to_tsvector(
        'simple'::regconfig,
        pg_catalog.regexp_replace(
          pg_catalog.regexp_replace(
            pg_catalog.replace(
              pg_catalog.replace(
                pg_catalog.translate(
                  pg_catalog.lower(city.name),
                  'àáâãäåçèéêëìíîïñòóôõöøùúûüýÿ',
                  'aaaaaaceeeeiiiinoooooouuuuyy'
                ),
                'œ',
                'oe'
              ),
              'æ',
              'ae'
            ),
            U&'[\0300-\036f]',
            '',
            'g'
          ),
          '[^[:alnum:]]+',
          ' ',
          'g'
        )
      ) @@ pg_catalog.plainto_tsquery('simple'::regconfig, query_lexeme.lexeme)
    join public.listings listing on listing.city_id = city.id
    where listing.status = 'publie'::public.listing_status
      and listing.published_at is not null

    union

    select listing.id, query_lexeme.lexeme
    from query_lexemes query_lexeme
    join public.categories category
      on pg_catalog.to_tsvector(
        'simple'::regconfig,
        pg_catalog.regexp_replace(
          pg_catalog.regexp_replace(
            pg_catalog.replace(
              pg_catalog.replace(
                pg_catalog.translate(
                  pg_catalog.lower(category.id || ' ' || category.subtype || ' ' || category.name_key),
                  'àáâãäåçèéêëìíîïñòóôõöøùúûüýÿ',
                  'aaaaaaceeeeiiiinoooooouuuuyy'
                ),
                'œ',
                'oe'
              ),
              'æ',
              'ae'
            ),
            U&'[\0300-\036f]',
            '',
            'g'
          ),
          '[^[:alnum:]]+',
          ' ',
          'g'
        )
      ) @@ pg_catalog.plainto_tsquery('simple'::regconfig, query_lexeme.lexeme)
    join public.listings listing on listing.category_id = category.id
    where listing.status = 'publie'::public.listing_status
      and listing.published_at is not null
  ),
  search_candidates as materialized (
    select matched_listing.id
    from search_lexeme_matches matched_listing
    group by matched_listing.id
    having pg_catalog.count(*) = (select pg_catalog.count(*) from query_lexemes)
  ),
  ranked_catalog as (
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
      listing.published_at as sort_published_at
    from search_candidates candidate
    join public.listings listing on listing.id = candidate.id
    left join lateral (
      select media.url
      from public.listing_media media
      where media.listing_id = listing.id
      order by media.is_cover desc, media.display_order asc
      limit 1
    ) cover on true
    where listing.status = 'publie'::public.listing_status
      and listing.published_at is not null
      and (v_city_id is null or listing.city_id = v_city_id)
      and (v_category_id is null or listing.category_id = v_category_id)
      and (v_listing_type is null or listing.type = v_listing_type)
      and (v_listing_class is null or listing.listing_class = v_listing_class)
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
    pg_catalog.replace(
      pg_catalog.replace(
        pg_catalog.encode(
          pg_catalog.convert_to(
            pg_catalog.jsonb_build_object(
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
        pg_catalog.chr(10),
        ''
      ),
      pg_catalog.chr(13),
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

revoke execute on function public.search_catalog_summaries_v1(
  text,
  text,
  text,
  text,
  text,
  text,
  integer
)
from public, anon, authenticated, service_role;

grant execute on function public.search_catalog_summaries_v1(
  text,
  text,
  text,
  text,
  text,
  text,
  integer
)
to anon, authenticated;

reset lock_timeout;

commit;
