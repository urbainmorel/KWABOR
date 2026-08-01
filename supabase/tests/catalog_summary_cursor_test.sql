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

create or replace function tests.json_as(db_role text, uid uuid, sql text)
returns jsonb
language plpgsql
as $$
declare
  result jsonb;
begin
  perform tests.use_auth_context(db_role, uid);
  execute format(
    'select coalesce(jsonb_agg(to_jsonb(scoped_row)), ''[]''::jsonb) from (%s) as scoped_row',
    sql
  ) into result;
  reset role;
  return result;
exception
  when others then
    reset role;
    raise;
end;
$$;

select plan(55);

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
  'catalog-owner@kwabor.test',
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
  owner_id,
  submitted_by,
  status,
  name,
  slug,
  description,
  city_id,
  price_unit,
  rating_avg,
  likes_count,
  created_at,
  published_at
)
values
  (
    'ca000000-0000-4000-8000-000000000010',
    'etablissement',
    'restaurant',
    'commercial',
    'commercial-restaurant',
    'ca000000-0000-4000-8000-000000000001',
    'ca000000-0000-4000-8000-000000000001',
    'brouillon',
    'Brouillon prive catalogue',
    'catalogue-brouillon-prive',
    'Cette fiche privee verifie que le resume public ne divulgue jamais les brouillons geres.',
    'cotonou',
    'aucune',
    5.00,
    999,
    '2026-07-01 00:00:00+00',
    null
  ),
  (
    'ca100000-0000-4000-8000-000000000001',
    'lieu',
    'nature',
    'patrimonial',
    'heritage-nature',
    null,
    null,
    'publie',
    'Visite 100% Nature',
    'catalogue-recherche-pourcent',
    'Cette fiche publique sert a verifier la recherche litterale du caractere pourcentage.',
    'parakou',
    'aucune',
    2.00,
    2,
    '2026-07-01 00:00:00+00',
    '2026-07-01 00:00:00+00'
  ),
  (
    'ca100000-0000-4000-8000-000000000002',
    'lieu',
    'nature',
    'patrimonial',
    'heritage-nature',
    null,
    null,
    'publie',
    'Maison_test catalogue',
    'catalogue-recherche-soulignement',
    'Cette fiche publique sert a verifier la recherche litterale du caractere de soulignement.',
    'parakou',
    'aucune',
    2.00,
    2,
    '2026-07-01 00:00:00+00',
    '2026-07-01 00:00:00+00'
  ),
  (
    'ca100000-0000-4000-8000-000000000003',
    'lieu',
    'nature',
    'patrimonial',
    'heritage-nature',
    null,
    null,
    'publie',
    'Chemin \ Ouidah',
    'catalogue-recherche-antislash',
    'Cette fiche publique sert a verifier la recherche litterale du caractere antislash.',
    'parakou',
    'aucune',
    2.00,
    2,
    '2026-07-01 00:00:00+00',
    '2026-07-01 00:00:00+00'
  ),
  (
    'ca200000-0000-4000-8000-000000000001',
    'lieu',
    'nature',
    'patrimonial',
    'heritage-nature',
    null,
    null,
    'publie',
    'Pagination tie 1',
    'catalogue-pagination-tie-1',
    'Cette fiche publique constitue une egalite complete pour tester la pagination par curseur.',
    'abomey',
    'aucune',
    3.00,
    7,
    '2026-07-02 00:00:00+00',
    '2026-07-02 00:00:00+00'
  ),
  (
    'ca200000-0000-4000-8000-000000000002',
    'lieu',
    'nature',
    'patrimonial',
    'heritage-nature',
    null,
    null,
    'publie',
    'Pagination tie 2',
    'catalogue-pagination-tie-2',
    'Cette fiche publique constitue une egalite complete pour tester la pagination par curseur.',
    'abomey',
    'aucune',
    3.00,
    7,
    '2026-07-02 00:00:00+00',
    '2026-07-02 00:00:00+00'
  ),
  (
    'ca200000-0000-4000-8000-000000000003',
    'lieu',
    'nature',
    'patrimonial',
    'heritage-nature',
    null,
    null,
    'publie',
    'Pagination tie 3',
    'catalogue-pagination-tie-3',
    'Cette fiche publique constitue une egalite complete pour tester la pagination par curseur.',
    'abomey',
    'aucune',
    3.00,
    7,
    '2026-07-02 00:00:00+00',
    '2026-07-02 00:00:00+00'
  ),
  (
    'ca200000-0000-4000-8000-000000000004',
    'lieu',
    'nature',
    'patrimonial',
    'heritage-nature',
    null,
    null,
    'publie',
    'Pagination tie 4',
    'catalogue-pagination-tie-4',
    'Cette fiche publique constitue une egalite complete pour tester la pagination par curseur.',
    'abomey',
    'aucune',
    3.00,
    7,
    '2026-07-02 00:00:00+00',
    '2026-07-02 00:00:00+00'
  ),
  (
    'ca200000-0000-4000-8000-000000000005',
    'lieu',
    'nature',
    'patrimonial',
    'heritage-nature',
    null,
    null,
    'publie',
    'Pagination tie 5',
    'catalogue-pagination-tie-5',
    'Cette fiche publique constitue une egalite complete pour tester la pagination par curseur.',
    'abomey',
    'aucune',
    3.00,
    7,
    '2026-07-02 00:00:00+00',
    '2026-07-02 00:00:00+00'
  ),
  (
    'ca300000-0000-4000-8000-000000000001',
    'lieu',
    'nature',
    'patrimonial',
    'heritage-nature',
    null,
    null,
    'publie',
    'Sponsor ranking long',
    'catalogue-sponsor-long',
    'Cette fiche publique verifie la priorite du sponsoring actif de plus longue duree.',
    'porto-novo',
    'aucune',
    1.00,
    1,
    '2026-07-03 00:00:00+00',
    '2026-07-03 00:00:00+00'
  ),
  (
    'ca300000-0000-4000-8000-000000000002',
    'lieu',
    'nature',
    'patrimonial',
    'heritage-nature',
    null,
    null,
    'publie',
    'Sponsor ranking short',
    'catalogue-sponsor-short',
    'Cette fiche publique verifie la priorite du sponsoring actif de plus courte duree.',
    'porto-novo',
    'aucune',
    1.00,
    1,
    '2026-07-03 00:00:00+00',
    '2026-07-03 00:00:00+00'
  ),
  (
    'ca300000-0000-4000-8000-000000000003',
    'lieu',
    'nature',
    'patrimonial',
    'heritage-nature',
    null,
    null,
    'publie',
    'Sponsor ranking expired',
    'catalogue-sponsor-expired',
    'Cette fiche publique verifie qu un sponsoring expire redevient une fiche catalogue ordinaire.',
    'porto-novo',
    'aucune',
    1.00,
    1,
    '2026-07-03 00:00:00+00',
    '2026-07-03 00:00:00+00'
  ),
  (
    'ca300000-0000-4000-8000-000000000004',
    'lieu',
    'nature',
    'patrimonial',
    'heritage-nature',
    null,
    null,
    'publie',
    'Sponsor ranking none',
    'catalogue-sponsor-none',
    'Cette fiche publique verifie le classement ordinaire en absence de sponsoring actif.',
    'porto-novo',
    'aucune',
    1.00,
    1,
    '2026-07-03 00:00:00+00',
    '2026-07-03 00:00:00+00'
  );

update public.listings
set sponsored_until = statement_timestamp() + interval '30 days'
where id = 'ca300000-0000-4000-8000-000000000001';

update public.listings
set sponsored_until = statement_timestamp() + interval '10 days'
where id = 'ca300000-0000-4000-8000-000000000002';

update public.listings
set sponsored_until = statement_timestamp() - interval '1 day'
where id = 'ca300000-0000-4000-8000-000000000003';

delete from public.listing_media
where listing_id in (
  '00000000-0000-4000-8000-000000000101',
  '00000000-0000-4000-8000-000000000102',
  '00000000-0000-4000-8000-000000000103'
);

insert into public.listing_media (
  id,
  listing_id,
  url,
  alt,
  display_order,
  is_cover
)
values
  (
    'cb000000-0000-4000-8000-000000000001',
    '00000000-0000-4000-8000-000000000101',
    'https://media.kwabor.test/non-cover-first.jpg',
    'Image non couverture de test',
    0,
    false
  ),
  (
    'cb000000-0000-4000-8000-000000000002',
    '00000000-0000-4000-8000-000000000101',
    'https://media.kwabor.test/cover-preferred.jpg',
    'Image de couverture preferee',
    10,
    true
  ),
  (
    'cb000000-0000-4000-8000-000000000011',
    '00000000-0000-4000-8000-000000000102',
    'https://media.kwabor.test/fallback-low-id.jpg',
    'Image secondaire avec identifiant bas',
    5,
    false
  ),
  (
    'cb000000-0000-4000-8000-000000000012',
    '00000000-0000-4000-8000-000000000102',
    'https://media.kwabor.test/fallback-high-id.jpg',
    'Image secondaire avec identifiant haut',
    5,
    false
  ),
  (
    'cb000000-0000-4000-8000-000000000020',
    'ca000000-0000-4000-8000-000000000010',
    'https://media.kwabor.test/private-draft.jpg',
    'Image privee rattachee au brouillon',
    0,
    true
  );

select ok(
  to_regprocedure(
    'public.list_catalog_summaries(text,text,text,text,text,text,integer)'
  ) is not null,
  'catalog summary RPC exists with the expected identity arguments'
);

select is(
  (
    select procedure.proargnames[1:7]
    from pg_catalog.pg_proc procedure
    where procedure.oid = 'public.list_catalog_summaries(text,text,text,text,text,text,integer)'::regprocedure
  ),
  array[
    'p_city_id',
    'p_category_id',
    'p_listing_type',
    'p_listing_class',
    'p_search_query',
    'p_cursor',
    'p_limit'
  ]::text[],
  'catalog summary RPC input parameter names are stable'
);

select is(
  (
    select procedure.proargnames[8:22]
    from pg_catalog.pg_proc procedure
    where procedure.oid = 'public.list_catalog_summaries(text,text,text,text,text,text,integer)'::regprocedure
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
  'catalog summary RPC exposes only the flat card contract'
);

select is(
  (
    select procedure.pronargdefaults
    from pg_catalog.pg_proc procedure
    where procedure.oid = 'public.list_catalog_summaries(text,text,text,text,text,text,integer)'::regprocedure
  ),
  7::smallint,
  'all catalog summary RPC inputs have defaults'
);

select ok(
  (
    select procedure.proretset
    from pg_catalog.pg_proc procedure
    where procedure.oid = 'public.list_catalog_summaries(text,text,text,text,text,text,integer)'::regprocedure
  ),
  'catalog summary RPC returns a row set'
);

select is(
  (
    select procedure.provolatile
    from pg_catalog.pg_proc procedure
    where procedure.oid = 'public.list_catalog_summaries(text,text,text,text,text,text,integer)'::regprocedure
  ),
  's'::"char",
  'catalog summary RPC is STABLE'
);

select ok(
  not (
    select procedure.prosecdef
    from pg_catalog.pg_proc procedure
    where procedure.oid = 'public.list_catalog_summaries(text,text,text,text,text,text,integer)'::regprocedure
  ),
  'catalog summary RPC is SECURITY INVOKER'
);

select is(
  (
    select array_to_string(procedure.proconfig, ',')
    from pg_catalog.pg_proc procedure
    where procedure.oid = 'public.list_catalog_summaries(text,text,text,text,text,text,integer)'::regprocedure
  ),
  'search_path=""',
  'catalog summary RPC has an empty fixed search_path'
);

select ok(
  has_function_privilege(
    'anon',
    'public.list_catalog_summaries(text,text,text,text,text,text,integer)',
    'execute'
  ),
  'anonymous callers can execute the public catalog RPC'
);

select ok(
  has_function_privilege(
    'authenticated',
    'public.list_catalog_summaries(text,text,text,text,text,text,integer)',
    'execute'
  ),
  'authenticated callers can execute the public catalog RPC'
);

select is(
  (
    select string_agg(coalesce(grantee.rolname, 'PUBLIC'), ',' order by coalesce(grantee.rolname, 'PUBLIC'))
    from pg_catalog.pg_proc procedure
    cross join lateral aclexplode(procedure.proacl) privilege
    left join pg_catalog.pg_roles grantee on grantee.oid = privilege.grantee
    where procedure.oid = 'public.list_catalog_summaries(text,text,text,text,text,text,integer)'::regprocedure
      and privilege.privilege_type = 'EXECUTE'
      and privilege.grantee <> procedure.proowner
  ),
  'anon,authenticated',
  'only anon and authenticated have direct client EXECUTE grants'
);

select ok(
  to_regclass('public.listing_media_catalog_cover_idx') is not null,
  'catalog cover lookup index exists'
);

select ok(
  pg_get_indexdef('public.listing_media_catalog_cover_idx'::regclass)
    like '%(listing_id, is_cover DESC, display_order, id) INCLUDE (url)%',
  'catalog cover lookup index matches the lateral ordering and covers the URL'
);

select is(
  tests.count_as(
    'anon',
    null,
    'select * from public.list_catalog_summaries(p_limit => 50)'
  ),
  (select count(*) from public.listings where status = 'publie'),
  'anonymous callers receive every and only published listing'
);

select is(
  tests.count_as(
    'authenticated',
    'ca000000-0000-4000-8000-000000000001',
    $sql$
      select *
      from public.list_catalog_summaries(p_limit => 50)
      where id = 'ca000000-0000-4000-8000-000000000010'
    $sql$
  ),
  0::bigint,
  'authenticated owners cannot receive their draft through the public catalog RPC'
);

select is(
  tests.count_as(
    'authenticated',
    'ca000000-0000-4000-8000-000000000001',
    $sql$
      select *
      from public.list_catalog_summaries(p_limit => 50)
      where status <> 'publie'
    $sql$
  ),
  0::bigint,
  'authenticated callers still receive published status only'
);

select ok(
  strpos(
    tests.json_as(
      'authenticated',
      'ca000000-0000-4000-8000-000000000001',
      'select * from public.list_catalog_summaries(p_limit => 50)'
    )::text,
    'private-draft.jpg'
  ) = 0,
  'draft media never leaks through the authenticated public summary path'
);

select is(
  (
    select cover_image_url
    from public.list_catalog_summaries(p_limit => 50)
    where id = '00000000-0000-4000-8000-000000000101'
  ),
  'https://media.kwabor.test/cover-preferred.jpg',
  'is_cover wins before display order'
);

select is(
  (
    select cover_image_url
    from public.list_catalog_summaries(p_limit => 50)
    where id = '00000000-0000-4000-8000-000000000102'
  ),
  'https://media.kwabor.test/fallback-low-id.jpg',
  'media fallback is deterministic by display order then id'
);

select is(
  (
    select cover_image_url
    from public.list_catalog_summaries(p_limit => 50)
    where id = '00000000-0000-4000-8000-000000000103'
  ),
  null,
  'listing without media returns a null cover URL'
);

select is(
  (select count(*) from public.list_catalog_summaries(p_city_id => 'cotonou', p_limit => 50)),
  2::bigint,
  'city filter is applied'
);

select is(
  (
    select count(*)
    from public.list_catalog_summaries(p_category_id => 'event-culture', p_limit => 50)
  ),
  1::bigint,
  'category filter is applied'
);

select is(
  (
    select count(*)
    from public.list_catalog_summaries(p_listing_type => 'evenement', p_limit => 50)
  ),
  1::bigint,
  'listing type filter is applied'
);

select is(
  (
    select count(*)
    from public.list_catalog_summaries(
      p_listing_class => 'commercial',
      p_limit => 50
    )
  ),
  2::bigint,
  'listing class filter is applied'
);

select is(
  (select count(*) from public.list_catalog_summaries(p_search_query => '%', p_limit => 50)),
  1::bigint,
  'percent is searched as a literal character'
);

select is(
  (select count(*) from public.list_catalog_summaries(p_search_query => '_', p_limit => 50)),
  1::bigint,
  'underscore is searched as a literal character'
);

select is(
  (
    select count(*)
    from public.list_catalog_summaries(p_search_query => E'\\', p_limit => 50)
  ),
  1::bigint,
  'backslash is searched as a literal character'
);

select is(
  (
    select name
    from public.list_catalog_summaries(
      p_search_query => '  VISITE 100% NATURE  ',
      p_limit => 50
    )
  ),
  'Visite 100% Nature',
  'search is canonically trimmed and case-insensitive'
);

select ok(
  (
    select bool_and(
      row_cursor is not null
      and (convert_from(decode(row_cursor, 'base64'), 'UTF8')::jsonb ->> 'v') = '1'
    )
    from public.list_catalog_summaries(p_limit => 50)
  ),
  'every returned summary carries a valid versioned base64 cursor'
);

select throws_ok(
  'select * from public.list_catalog_summaries(p_limit => null)',
  '22023',
  'p_limit must be between 1 and 50',
  'null limit is rejected'
);

select throws_ok(
  'select * from public.list_catalog_summaries(p_limit => 0)',
  '22023',
  'p_limit must be between 1 and 50',
  'zero limit is rejected'
);

select throws_ok(
  'select * from public.list_catalog_summaries(p_limit => 51)',
  '22023',
  'p_limit must be between 1 and 50',
  'limit above the maximum is rejected'
);

select throws_ok(
  'select * from public.list_catalog_summaries(p_city_id => ''   '')',
  '22023',
  'p_city_id is invalid',
  'blank city filter is rejected'
);

select throws_ok(
  'select * from public.list_catalog_summaries(p_city_id => ''ville-inconnue'')',
  '22023',
  'p_city_id is unknown',
  'unknown city filter is rejected'
);

select throws_ok(
  'select * from public.list_catalog_summaries(p_category_id => ''categorie-inconnue'')',
  '22023',
  'p_category_id is unknown',
  'unknown category filter is rejected'
);

select throws_ok(
  'select * from public.list_catalog_summaries(p_listing_type => ''inconnu'')',
  '22023',
  'p_listing_type is invalid',
  'invalid listing type is rejected'
);

select throws_ok(
  'select * from public.list_catalog_summaries(p_listing_class => ''inconnue'')',
  '22023',
  'p_listing_class is invalid',
  'invalid listing class is rejected'
);

select throws_ok(
  'select * from public.list_catalog_summaries(p_search_query => ''   '')',
  '22023',
  'p_search_query is invalid',
  'blank search text is rejected'
);

select throws_ok(
  format(
    'select * from public.list_catalog_summaries(p_search_query => %L)',
    repeat('x', 121)
  ),
  '22023',
  'p_search_query is invalid',
  'overlong search text is rejected'
);

select throws_ok(
  'select * from public.list_catalog_summaries(p_cursor => ''not-base64!'')',
  '22023',
  'p_cursor is malformed',
  'malformed base64 cursor is rejected'
);

create temporary table catalog_bad_cursors (
  kind text primary key,
  cursor_value text not null
);

insert into catalog_bad_cursors (kind, cursor_value)
values
  (
    'wrong-version',
    replace(
      replace(encode(convert_to('{"v":2}'::text, 'UTF8'), 'base64'), chr(10), ''),
      chr(13),
      ''
    )
  ),
  (
    'missing-fields',
    replace(
      replace(encode(convert_to('{"v":1}'::text, 'UTF8'), 'base64'), chr(10), ''),
      chr(13),
      ''
    )
  );

select throws_ok(
  format(
    'select * from public.list_catalog_summaries(p_cursor => %L)',
    (select cursor_value from catalog_bad_cursors where kind = 'wrong-version')
  ),
  '22023',
  'p_cursor version is unsupported',
  'unsupported cursor version is rejected'
);

select throws_ok(
  format(
    'select * from public.list_catalog_summaries(p_cursor => %L)',
    (select cursor_value from catalog_bad_cursors where kind = 'missing-fields')
  ),
  '22023',
  'p_cursor fields are malformed',
  'cursor missing sort fields is rejected'
);

create temporary table catalog_city_cursor as
select row_cursor
from public.list_catalog_summaries(p_city_id => 'cotonou', p_limit => 1)
limit 1;

select throws_ok(
  format(
    'select * from public.list_catalog_summaries(p_cursor => %L)',
    (select row_cursor from catalog_city_cursor)
  ),
  '22023',
  'p_cursor does not match catalog filters',
  'cursor reuse with different filters is rejected'
);

select throws_ok(
  format(
    'select * from public.list_catalog_summaries(p_cursor => %L)',
    repeat('A', 4097)
  ),
  '22023',
  'p_cursor is invalid',
  'overlong cursor is rejected before decoding'
);

create temporary table catalog_tie_page_1 as
select *
from public.list_catalog_summaries(p_search_query => 'Pagination tie', p_limit => 2);

create temporary table catalog_tie_kept_1 as
select *
from catalog_tie_page_1
order by id desc
limit 2;

create temporary table catalog_tie_page_2 as
select *
from public.list_catalog_summaries(
  p_search_query => 'Pagination tie',
  p_cursor => (
    select row_cursor
    from catalog_tie_kept_1
    order by id asc
    limit 1
  ),
  p_limit => 2
);

create temporary table catalog_tie_kept_2 as
select *
from catalog_tie_page_2
order by id desc
limit 2;

create temporary table catalog_tie_page_3 as
select *
from public.list_catalog_summaries(
  p_search_query => 'Pagination tie',
  p_cursor => (
    select row_cursor
    from catalog_tie_kept_2
    order by id asc
    limit 1
  ),
  p_limit => 2
);

select is(
  (select count(*) from catalog_tie_page_1),
  3::bigint,
  'first cursor page returns limit plus one sentinel row'
);

select is(
  (select count(*) from catalog_tie_page_2),
  3::bigint,
  'second cursor page returns limit plus one sentinel row'
);

select is(
  (select count(*) from catalog_tie_page_3),
  1::bigint,
  'terminal cursor page returns only the remaining row'
);

select is(
  (
    select count(*)
    from (
      select id from catalog_tie_kept_1
      union all
      select id from catalog_tie_kept_2
      union all
      select id from catalog_tie_page_3
    ) paged
  ),
  5::bigint,
  'client-kept cursor pages contain five rows total'
);

select is(
  (
    select count(distinct id)
    from (
      select id from catalog_tie_kept_1
      union all
      select id from catalog_tie_kept_2
      union all
      select id from catalog_tie_page_3
    ) paged
  ),
  5::bigint,
  'cursor pages have no overlap despite tied sort values'
);

select is(
  (
    select string_agg(id::text, ',' order by id desc)
    from (
      select id from catalog_tie_kept_1
      union all
      select id from catalog_tie_kept_2
      union all
      select id from catalog_tie_page_3
    ) paged
  ),
  concat_ws(
    ',',
    'ca200000-0000-4000-8000-000000000005',
    'ca200000-0000-4000-8000-000000000004',
    'ca200000-0000-4000-8000-000000000003',
    'ca200000-0000-4000-8000-000000000002',
    'ca200000-0000-4000-8000-000000000001'
  ),
  'cursor pages omit none of the tied listings'
);

select is(
  (
    select count(*)
    from public.list_catalog_summaries(p_search_query => 'Pagination tie', p_limit => 5)
  ),
  5::bigint,
  'an exact full terminal page has no sentinel row'
);

select ok(
  not (
    (
      select count(*)
      from public.list_catalog_summaries(p_search_query => 'Pagination tie', p_limit => 5)
    ) > 5
  ),
  'limit-plus-one detection reports no false next page at exact exhaustion'
);

select is(
  (
    select string_agg(result.id::text, ',' order by result.ordinality)
    from public.list_catalog_summaries(
      p_search_query => 'Sponsor ranking',
      p_limit => 10
    ) with ordinality result
  ),
  concat_ws(
    ',',
    'ca300000-0000-4000-8000-000000000001',
    'ca300000-0000-4000-8000-000000000002',
    'ca300000-0000-4000-8000-000000000004',
    'ca300000-0000-4000-8000-000000000003'
  ),
  'active sponsorship sorts first while expired and null sponsorship normalize equally'
);

select ok(
  (
    select is_sponsored_placement
    from public.list_catalog_summaries(
      p_search_query => 'Sponsor ranking long',
      p_limit => 10
    )
  ),
  'active sponsorship is exposed from the server pagination snapshot'
);

select ok(
  not exists (
    select 1
    from public.list_catalog_summaries(
      p_search_query => 'Sponsor ranking',
      p_limit => 10
    )
    where id in (
      'ca300000-0000-4000-8000-000000000003',
      'ca300000-0000-4000-8000-000000000004'
    )
      and is_sponsored_placement
  ),
  'expired and null sponsorship are both exposed as non-sponsored placements'
);

select * from finish();
rollback;
