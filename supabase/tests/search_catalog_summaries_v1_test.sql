begin;

create schema if not exists tests;

create or replace function tests.use_auth_context(db_role text, uid uuid)
returns void
language plpgsql
as $$
begin
  execute format('set local role %I', db_role);
  perform set_config('request.jwt.claim.role', db_role, true);
  perform set_config('request.jwt.claim.sub', coalesce(uid::text, ''), true);
end;
$$;

create or replace function tests.count_as(db_role text, uid uuid, sql text)
returns bigint
language plpgsql
as $$
declare
  result bigint;
begin
  perform tests.use_auth_context(db_role, uid);
  execute format('select count(*) from (%s) as scoped_query', sql) into result;
  reset role;
  return result;
exception
  when others then
    reset role;
    raise;
end;
$$;

select plan(61);

select ok(
  to_regprocedure(
    'public.search_catalog_summaries_v1(text,text,text,text,text,text,integer)'
  ) is not null,
  'versioned catalog search RPC exists'
);

select is(
  (
    select procedure.proargnames[1:7]
    from pg_catalog.pg_proc procedure
    where procedure.oid =
      'public.search_catalog_summaries_v1(text,text,text,text,text,text,integer)'::regprocedure
  ),
  array[
    'p_search_query',
    'p_city_id',
    'p_category_id',
    'p_listing_type',
    'p_listing_class',
    'p_cursor',
    'p_limit'
  ]::text[],
  'catalog search input parameter names are stable'
);

select is(
  (
    select procedure.proargnames[8:22]
    from pg_catalog.pg_proc procedure
    where procedure.oid =
      'public.search_catalog_summaries_v1(text,text,text,text,text,text,integer)'::regprocedure
  ),
  array[
    'id',
    'type',
    'listing_class',
    'status',
    'name',
    'city_id',
    'category_id',
    'cover_image_url',
    'price_from_xof',
    'rating_avg',
    'likes_count',
    'verified',
    'sponsored_until',
    'is_sponsored_placement',
    'row_cursor'
  ]::text[],
  'catalog search exposes the flat card contract only'
);

select is(
  (
    select procedure.pronargdefaults
    from pg_catalog.pg_proc procedure
    where procedure.oid =
      'public.search_catalog_summaries_v1(text,text,text,text,text,text,integer)'::regprocedure
  ),
  6::smallint,
  'only the required search query has no default'
);

select ok(
  (
    select procedure.proretset
    from pg_catalog.pg_proc procedure
    where procedure.oid =
      'public.search_catalog_summaries_v1(text,text,text,text,text,text,integer)'::regprocedure
  ),
  'catalog search returns a row set'
);

select is(
  (
    select procedure.provolatile
    from pg_catalog.pg_proc procedure
    where procedure.oid = 'app_private.catalog_search_normalize(text)'::regprocedure
  ),
  'i'::"char",
  'catalog search normalization is immutable'
);

select ok(
  to_regprocedure('app_private.catalog_search_normalize(text)') is not null,
  'catalog search normalization helper exists'
);

select is(
  app_private.catalog_search_normalize(U&'M\00e9moire marche\0301 C\0153ur \00c6ther'),
  'memoire marche coeur aether',
  'catalog search normalization folds composed, decomposed, and ligature forms'
);

select ok(
  not has_function_privilege(
    'anon',
    'app_private.catalog_search_normalize(text)',
    'execute'
  ),
  'anonymous callers cannot execute internal catalog normalization'
);

select ok(
  has_function_privilege(
    'authenticated',
    'app_private.catalog_search_normalize(text)',
    'execute'
  ),
  'authenticated search and listing writes can execute catalog normalization'
);

select ok(
  has_function_privilege(
    'service_role',
    'app_private.catalog_search_normalize(text)',
    'execute'
  ),
  'service-role listing writes can execute catalog normalization'
);

select is(
  (
    select procedure.provolatile
    from pg_catalog.pg_proc procedure
    where procedure.oid =
      'public.search_catalog_summaries_v1(text,text,text,text,text,text,integer)'::regprocedure
  ),
  's'::"char",
  'catalog search is STABLE'
);

select ok(
  not (
    select procedure.prosecdef
    from pg_catalog.pg_proc procedure
    where procedure.oid =
      'public.search_catalog_summaries_v1(text,text,text,text,text,text,integer)'::regprocedure
  ),
  'catalog search is SECURITY INVOKER'
);

select is(
  (
    select array_to_string(procedure.proconfig, ',')
    from pg_catalog.pg_proc procedure
    where procedure.oid =
      'public.search_catalog_summaries_v1(text,text,text,text,text,text,integer)'::regprocedure
  ),
  'search_path=""',
  'catalog search has an empty fixed search_path'
);

select ok(
  has_function_privilege(
    'anon',
    'public.search_catalog_summaries_v1(text,text,text,text,text,text,integer)',
    'execute'
  ),
  'anonymous callers can execute catalog search'
);

select ok(
  has_function_privilege(
    'authenticated',
    'public.search_catalog_summaries_v1(text,text,text,text,text,text,integer)',
    'execute'
  ),
  'authenticated callers can execute catalog search'
);

select is(
  (
    select string_agg(
      coalesce(grantee.rolname, 'PUBLIC'),
      ','
      order by coalesce(grantee.rolname, 'PUBLIC')
    )
    from pg_catalog.pg_proc procedure
    cross join lateral aclexplode(procedure.proacl) privilege
    left join pg_catalog.pg_roles grantee on grantee.oid = privilege.grantee
    where procedure.oid =
      'public.search_catalog_summaries_v1(text,text,text,text,text,text,integer)'::regprocedure
      and privilege.privilege_type = 'EXECUTE'
      and privilege.grantee <> procedure.proowner
  ),
  'anon,authenticated',
  'only mobile client roles have direct catalog-search EXECUTE grants'
);

select is(
  (
    select procedure.provolatile
    from pg_catalog.pg_proc procedure
    where procedure.oid = 'app_private.catalog_search_document(text,text[])'::regprocedure
  ),
  'i'::"char",
  'listing search document helper is immutable'
);

select ok(
  not has_function_privilege(
    'anon',
    'app_private.catalog_search_document(text,text[])',
    'execute'
  ),
  'anonymous callers cannot execute the internal search document helper'
);

select ok(
  has_function_privilege(
    'authenticated',
    'app_private.catalog_search_document(text,text[])',
    'execute'
  ),
  'authenticated listing writes can derive the generated search document'
);

select ok(
  has_function_privilege(
    'service_role',
    'app_private.catalog_search_document(text,text[])',
    'execute'
  ),
  'service-role listing writes can derive the generated search document'
);

select ok(
  exists (
    select 1
    from information_schema.columns column_definition
    where column_definition.table_schema = 'public'
      and column_definition.table_name = 'listings'
      and column_definition.column_name = 'catalog_search_document'
      and column_definition.is_generated = 'ALWAYS'
  ),
  'listing search document is a stored generated column'
);

select ok(
  has_column_privilege(
    'anon',
    'public.listings',
    'catalog_search_document',
    'select'
  ),
  'anonymous SECURITY INVOKER search can read the derived public document'
);

select ok(
  not has_column_privilege(
    'authenticated',
    'public.listings',
    'catalog_search_document',
    'update'
  ),
  'authenticated callers cannot overwrite the generated search document'
);

select ok(
  to_regclass('public.listings_catalog_search_document_published_idx') is not null,
  'published listing search GIN index exists'
);

select ok(
  pg_get_indexdef('public.listings_catalog_search_document_published_idx'::regclass)
    like '%USING gin (catalog_search_document)%'
  and pg_get_indexdef('public.listings_catalog_search_document_published_idx'::regclass)
    like '%published_at IS NOT NULL%',
  'listing search index targets the generated document and published subset'
);

select ok(
  to_regclass('public.cities_catalog_search_name_idx') is not null,
  'city-name search GIN index exists'
);

select ok(
  pg_get_indexdef('public.cities_catalog_search_name_idx'::regclass)
    like '%to_tsvector(''simple''::regconfig, regexp_replace(%'
  and position(
    'lower(name)'
    in pg_get_indexdef('public.cities_catalog_search_name_idx'::regclass)
  ) > 0,
  'city search index matches the RPC expression'
);

select ok(
  to_regclass('public.categories_catalog_search_terms_idx') is not null,
  'category-term search GIN index exists'
);

select ok(
  pg_get_indexdef('public.categories_catalog_search_terms_idx'::regclass)
    like '%to_tsvector(''simple''::regconfig, regexp_replace(%'
  and position(
    'id || '' ''::text'
    in pg_get_indexdef('public.categories_catalog_search_terms_idx'::regclass)
  ) > 0
  and position(
    'subtype'
    in pg_get_indexdef('public.categories_catalog_search_terms_idx'::regclass)
  ) > 0
  and position(
    'name_key'
    in pg_get_indexdef('public.categories_catalog_search_terms_idx'::regclass)
  ) > 0,
  'category search index covers id, subtype, and name key'
);

select ok(
  (
    select listing.catalog_search_document
      @@ plainto_tsquery('simple'::regconfig, 'memoire')
    from public.listings listing
    where listing.id = '00000000-0000-4000-8000-000000000101'
  ),
  'existing rows were backfilled into the generated search document'
);

select is(
  (
    select count(*)
    from public.search_catalog_summaries_v1('mémoire', p_limit => 50)
    where id = '00000000-0000-4000-8000-000000000101'
  ),
  1::bigint,
  'catalog search folds composed French diacritics'
);

select is(
  (
    select count(*)
    from public.search_catalog_summaries_v1(U&'marche\0301', p_limit => 50)
    where id = '00000000-0000-4000-8000-000000000102'
  ),
  1::bigint,
  'catalog search folds decomposed French diacritics'
);

select is(
  (
    select count(*)
    from public.search_catalog_summaries_v1('porte-retour', p_limit => 50)
    where id = '00000000-0000-4000-8000-000000000101'
  ),
  1::bigint,
  'catalog search treats punctuation as a token separator like the mobile fallback'
);

select is(
  (
    select count(*)
    from public.search_catalog_summaries_v1('rant', p_limit => 50)
  ),
  0::bigint,
  'catalog search does not turn a partial token into a substring match'
);

insert into auth.users (
  id,
  aud,
  role,
  email,
  encrypted_password,
  email_confirmed_at,
  created_at,
  updated_at
)
values (
  'ca000000-0000-4000-8000-000000000001',
  'authenticated',
  'authenticated',
  'search-owner@kwabor.test',
  '',
  now(),
  now(),
  now()
);

insert into public.listings (
  id,
  type,
  subtype,
  listing_class,
  category_id,
  status,
  name,
  slug,
  description,
  content_lang,
  city_id,
  tags,
  submitted_by
)
values (
  '5ea2c001-0000-4000-8000-000000000001',
  'lieu',
  'historique',
  'patrimonial',
  'heritage-historique',
  'brouillon',
  'Hidden Search Draft',
  'hidden-search-draft',
  'Draft description long enough to satisfy the catalog listing contract safely.',
  'fr',
  'ouidah',
  array['hidden-search-keyword'],
  'ca000000-0000-4000-8000-000000000001'
);

select is(
  (
    select count(*)
    from public.search_catalog_summaries_v1('  PORTE RETOUR  ', p_limit => 50)
  ),
  1::bigint,
  'catalog search trims and case-folds a submitted name query'
);

update public.cities
set name = 'Ville Atlantique Unique'
where id = 'ouidah';

select is(
  (
    select count(*)
    from public.search_catalog_summaries_v1('Atlantique Unique', p_limit => 50)
  ),
  2::bigint,
  'catalog search matches the current city name'
);

select is(
  (
    select count(*)
    from public.search_catalog_summaries_v1('retour atlantique', p_limit => 50)
    where id = '00000000-0000-4000-8000-000000000101'
  ),
  1::bigint,
  'catalog search combines submitted keywords across listing and city fields'
);

select is(
  (
    select count(*)
    from public.search_catalog_summaries_v1('commercial marche', p_limit => 50)
  ),
  1::bigint,
  'catalog search matches category identifiers'
);

insert into public.categories (
  id,
  listing_type,
  subtype,
  name_key,
  default_listing_class,
  detail_variant
)
values (
  'search-category-id-unique',
  'lieu',
  'searchsubtypeunique',
  'category.searchnamekeyunique',
  'patrimonial',
  'place'
);

insert into public.listings (
  id,
  type,
  subtype,
  listing_class,
  category_id,
  status,
  name,
  slug,
  description,
  content_lang,
  city_id,
  tags,
  published_at
)
values (
  '5ea2c001-0000-4000-8000-000000000002',
  'lieu',
  'searchsubtypeunique',
  'patrimonial',
  'search-category-id-unique',
  'publie',
  'Neutral Category Fixture',
  'neutral-category-fixture',
  'Published fixture used to verify category search terms without changing production taxonomy rows.',
  'fr',
  'porto-novo',
  array['neutral-category-fixture'],
  '2026-08-04 00:00:00+00'
);

select is(
  (
    select count(*)
    from public.search_catalog_summaries_v1('searchsubtypeunique', p_limit => 50)
  ),
  1::bigint,
  'catalog search matches category subtypes'
);

select is(
  (
    select count(*)
    from public.search_catalog_summaries_v1('category.searchnamekeyunique', p_limit => 50)
  ),
  1::bigint,
  'catalog search matches category name keys'
);

update public.listings
set tags = tags || 'searchtagunique'::text
where id = '00000000-0000-4000-8000-000000000101';

select is(
  (
    select count(*)
    from public.search_catalog_summaries_v1('searchtagunique', p_limit => 50)
  ),
  1::bigint,
  'catalog search matches listing tags'
);

select ok(
  (
    select listing.catalog_search_document
      @@ plainto_tsquery('simple'::regconfig, 'searchtagunique')
    from public.listings listing
    where listing.id = '00000000-0000-4000-8000-000000000101'
  ),
  'generated search document follows listing tag updates'
);

select is(
  (
    select count(*)
    from public.search_catalog_summaries_v1(
      'test',
      p_listing_type => 'etablissement',
      p_limit => 50
    )
  ),
  1::bigint,
  'catalog search applies the active listing-type scope'
);

select is(
  (
    select count(*)
    from public.search_catalog_summaries_v1(
      'test',
      p_listing_type => 'lieu',
      p_limit => 50
    )
  ),
  0::bigint,
  'catalog search rejects non-matching listing-type scope'
);

select is(
  tests.count_as(
    'anon',
    null,
    $$select * from public.search_catalog_summaries_v1('hidden search keyword', p_limit => 50)$$
  ),
  0::bigint,
  'anonymous catalog search never returns drafts'
);

select is(
  tests.count_as(
    'authenticated',
    'ca000000-0000-4000-8000-000000000001',
    $$select * from public.search_catalog_summaries_v1('hidden search keyword', p_limit => 50)$$
  ),
  0::bigint,
  'authenticated catalog search remains published-only'
);

select lives_ok(
  $$select * from public.search_catalog_summaries_v1('%', p_limit => 50)$$,
  'percent is parsed as safe search text'
);

select lives_ok(
  $$select * from public.search_catalog_summaries_v1('_', p_limit => 50)$$,
  'underscore is parsed as safe search text'
);

select lives_ok(
  $$select * from public.search_catalog_summaries_v1(E'\\', p_limit => 50)$$,
  'backslash is parsed as safe search text'
);

select throws_ok(
  'select * from public.search_catalog_summaries_v1(null)',
  '22023',
  'p_search_query is invalid',
  'null search query is rejected'
);

select throws_ok(
  $$select * from public.search_catalog_summaries_v1('   ')$$,
  '22023',
  'p_search_query is invalid',
  'blank search query is rejected'
);

select throws_ok(
  $$select * from public.search_catalog_summaries_v1(E'unsafe\nquery')$$,
  '22023',
  'p_search_query is invalid',
  'control characters are rejected'
);

select throws_ok(
  format(
    'select * from public.search_catalog_summaries_v1(%L)',
    repeat('x', 121)
  ),
  '22023',
  'p_search_query is invalid',
  'overlong search query is rejected'
);

select throws_ok(
  $$select * from public.search_catalog_summaries_v1('test', p_limit => 0)$$,
  '22023',
  'p_limit must be between 1 and 50',
  'invalid page limit is rejected'
);

select throws_ok(
  format(
    'select * from public.search_catalog_summaries_v1(%L, p_cursor => %L)',
    'test',
    encode(
      convert_to('{"v":1,"sponsored_until":null}'::jsonb::text, 'UTF8'),
      'base64'
    )
  ),
  '22023',
  'p_cursor fields are malformed',
  'cursor objects with absent required fields are rejected'
);

create temporary table catalog_search_page_one as
select result.*
from public.search_catalog_summaries_v1('test', p_limit => 1) with ordinality result;

select throws_ok(
  format(
    'select * from public.search_catalog_summaries_v1(%L, p_cursor => %L)',
    'test',
    (
      select pg_catalog.replace(
        pg_catalog.replace(
          pg_catalog.encode(
            pg_catalog.convert_to(
              pg_catalog.jsonb_set(
                pg_catalog.convert_from(
                  pg_catalog.decode(row_cursor, 'base64'),
                  'UTF8'
                )::jsonb,
                '{as_of}',
                pg_catalog.to_jsonb((statement_timestamp() + interval '1 day')::text)
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
      )
      from catalog_search_page_one
      where ordinality = 1
    )
  ),
  '22023',
  'p_cursor fields are invalid',
  'self-contained cursors cannot move their search snapshot into the future'
);

select is(
  (select count(*) from catalog_search_page_one),
  2::bigint,
  'first search page includes one bounded sentinel row'
);

select is(
  (
    select count(*)
    from public.search_catalog_summaries_v1(
      'test',
      p_cursor => (
        select row_cursor
        from catalog_search_page_one
        where ordinality = 1
      ),
      p_limit => 1
    )
  ),
  1::bigint,
  'search cursor continues after the retained row without omission'
);

select throws_ok(
  format(
    'select * from public.search_catalog_summaries_v1(%L, p_cursor => %L, p_limit => 1)',
    'culture',
    (
      select row_cursor
      from catalog_search_page_one
      where ordinality = 1
    )
  ),
  '22023',
  'p_cursor does not match catalog search parameters',
  'search cursor cannot be reused with another query'
);

select throws_ok(
  format(
    'select * from public.search_catalog_summaries_v1(%L, p_city_id => %L, p_cursor => %L, p_limit => 1)',
    'test',
    'cotonou',
    (
      select row_cursor
      from catalog_search_page_one
      where ordinality = 1
    )
  ),
  '22023',
  'p_cursor does not match catalog search parameters',
  'search cursor cannot be reused with another filter scope'
);

select * from finish();

rollback;
