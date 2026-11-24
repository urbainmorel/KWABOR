begin;

set local lock_timeout = '5s';

create function public.suggest_catalog_listings_v1(
  p_query text,
  p_city_id text default null,
  p_category_id text default null,
  p_listing_type public.listing_type default null,
  p_listing_class public.listing_class default null
)
returns table (id uuid, name text, city_name text)
language sql
stable
security invoker
set search_path = ''
begin atomic
  with sources(kind, source_id, source_text) as (
    select 'query', null::text, p_query
    where pg_catalog.char_length(p_query) between 2 and 120
    union all
    select 'city', city.id, city.name from public.cities city
    union all
    select 'category', category.id, category.id || ' ' || category.subtype || ' ' || category.name_key
    from public.categories category
  ),
  -- Match the stored search document's folding while leaving its private helpers private.
  documents as materialized (
    select sources.kind, sources.source_id, pg_catalog.regexp_replace(
      pg_catalog.regexp_replace(
        pg_catalog.replace(pg_catalog.replace(pg_catalog.translate(
          pg_catalog.lower(sources.source_text),
          'àáâãäåçèéêëìíîïñòóôõöøùúûüýÿ',
          'aaaaaaceeeeiiiinoooooouuuuyy'
        ), 'œ', 'oe'), 'æ', 'ae'),
        U&'[\0300-\036f]', '', 'g'
      ),
      '[^[:alnum:]]+', ' ', 'g'
    ) as normalized
    from sources
  ),
  terms as materialized (
    select word,
      pg_catalog.to_tsquery('simple'::regconfig, pg_catalog.quote_literal(word) || ':*') as prefix
    from documents
    cross join lateral pg_catalog.unnest(
      pg_catalog.tsvector_to_array(pg_catalog.to_tsvector('simple'::regconfig, documents.normalized))
    ) as words(word)
    where documents.kind = 'query'
      and pg_catalog.char_length(pg_catalog.btrim(documents.normalized)) >= 2
  ),
  published as not materialized (
    select listing.id, listing.name, listing.city_id, listing.category_id, listing.catalog_search_document
    from public.listings listing
    where listing.status = 'publie'::public.listing_status
      and listing.published_at is not null
      and (p_city_id is null or listing.city_id = p_city_id)
      and (p_category_id is null or listing.category_id = p_category_id)
      and (p_listing_type is null or listing.type = p_listing_type)
      and (p_listing_class is null or listing.listing_class = p_listing_class)
  ),
  matches as (
    select published.id, terms.word, 2 as weight
    from terms
    join published on published.catalog_search_document @@ terms.prefix
    union all
    select published.id, terms.word, 1
    from terms
    join documents on documents.kind in ('city', 'category')
      and pg_catalog.to_tsvector('simple'::regconfig, documents.normalized) @@ terms.prefix
    join published on (documents.kind = 'city' and documents.source_id = published.city_id)
      or (documents.kind = 'category' and documents.source_id = published.category_id)
  ),
  ranked as (
    select matches.id, pg_catalog.sum(matches.weight) as score
    from matches
    group by matches.id
    having pg_catalog.count(distinct matches.word) = (select pg_catalog.count(*) from terms)
  )
  select published.id, published.name, city.name
  from ranked
  join published on published.id = ranked.id
  join public.cities city on city.id = published.city_id
  order by ranked.score desc, published.name, published.id
  limit 6;
end;

comment on function public.suggest_catalog_listings_v1(text, text, text, public.listing_type, public.listing_class) is
  'Returns up to six published listing suggestions by prefix under the caller RLS, without writing query history.';

revoke all on function public.suggest_catalog_listings_v1(text, text, text, public.listing_type, public.listing_class)
from public, anon, authenticated, service_role;
grant execute on function public.suggest_catalog_listings_v1(text, text, text, public.listing_type, public.listing_class)
to anon, authenticated;

commit;
