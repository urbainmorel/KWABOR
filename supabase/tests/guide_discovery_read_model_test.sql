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

create or replace function tests.command_as(db_role text, uid uuid, sql text)
returns bigint
language plpgsql
as $$
declare
  affected_rows bigint;
begin
  perform tests.use_auth_context(db_role, uid);
  execute sql;
  get diagnostics affected_rows = row_count;
  reset role;
  return affected_rows;
exception
  when others then
    reset role;
    raise;
end;
$$;

create or replace function tests.force_guide_language_divergence(
  target_listing_id uuid,
  target_language_id text
)
returns void
language plpgsql
as $$
begin
  perform tests.use_auth_context('service_role', null);

  delete from public.guide_service_languages
  where listing_id = target_listing_id
    and language_id = target_language_id;

  reset role;
  set constraints guide_service_languages_match_legacy immediate;
  set constraints guide_service_languages_match_legacy deferred;

  raise exception 'Expected the deferred guide discovery invariant to reject divergence';
exception
  when others then
    reset role;
    raise;
end;
$$;

create or replace function tests.force_guide_language_reference_ambiguity()
returns void
language plpgsql
as $$
begin
  perform tests.use_auth_context('service_role', null);

  insert into public.guide_languages (id, label, display_order)
  values ('forbidden-francais-alias', 'francais', 1301);

  reset role;
  set constraints guide_languages_preserve_discovery_mapping immediate;
  set constraints guide_languages_preserve_discovery_mapping deferred;

  raise exception 'Expected the deferred guide reference invariant to reject ambiguity';
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

create or replace function tests.sorted_jsonb_keys(value jsonb)
returns text[]
language sql
immutable
set search_path = ''
as $$
  select coalesce(array_agg(key order by key), '{}'::text[])
  from jsonb_object_keys(value) key;
$$;

create or replace function tests.jsonb_has_any_key(value jsonb, forbidden_keys text[])
returns boolean
language sql
immutable
set search_path = ''
as $$
  with recursive nodes(node) as (
    select value
    union all
    select child.node
    from nodes parent
    cross join lateral (
      select object_child.value as node
      from jsonb_each(
        case when jsonb_typeof(parent.node) = 'object' then parent.node else '{}'::jsonb end
      ) object_child
      union all
      select array_child.value as node
      from jsonb_array_elements(
        case when jsonb_typeof(parent.node) = 'array' then parent.node else '[]'::jsonb end
      ) array_child
    ) child
  )
  select exists (
    select 1
    from nodes
    cross join lateral jsonb_object_keys(
      case when jsonb_typeof(nodes.node) = 'object' then nodes.node else '{}'::jsonb end
    ) key
    where key = any(forbidden_keys)
  );
$$;

create or replace function tests.valid_opening_hours()
returns jsonb
language sql
immutable
set search_path = ''
as $$
  select $json$
    {
      "monday":{"status":"periods","periods":[{"opens_minute":480,"closes_minute":1080,"closes_next_day":false}]},
      "tuesday":{"status":"periods","periods":[{"opens_minute":480,"closes_minute":1080,"closes_next_day":false}]},
      "wednesday":{"status":"periods","periods":[{"opens_minute":480,"closes_minute":1080,"closes_next_day":false}]},
      "thursday":{"status":"periods","periods":[{"opens_minute":480,"closes_minute":1080,"closes_next_day":false}]},
      "friday":{"status":"periods","periods":[{"opens_minute":480,"closes_minute":1200,"closes_next_day":false}]},
      "saturday":{"status":"open_24_hours","periods":[]},
      "sunday":{"status":"closed","periods":[]}
    }
  $json$::jsonb;
$$;

select plan(77);

select results_eq(
  $$
    select table_name::text
    from information_schema.tables
    where table_schema = 'public'
      and table_name in (
        'guide_languages',
        'guide_specialties',
        'guide_service_cities',
        'guide_service_languages',
        'guide_service_specialties'
      )
    order by table_name
  $$,
  $$
    values
      ('guide_languages'),
      ('guide_service_cities'),
      ('guide_service_languages'),
      ('guide_service_specialties'),
      ('guide_specialties')
  $$,
  'guide discovery creates only the five normalized public tables'
);

select ok(
  (
    select bool_and(class.relrowsecurity)
    from pg_catalog.pg_class class
    where class.oid in (
      'public.guide_languages'::regclass,
      'public.guide_specialties'::regclass,
      'public.guide_service_cities'::regclass,
      'public.guide_service_languages'::regclass,
      'public.guide_service_specialties'::regclass
    )
  ),
  'RLS is enabled on every exposed guide discovery table'
);

select ok(
  (
    select count(*) = 3
      and bool_and(
        pg_get_constraintdef(constraint_record.oid) like '%display_order >= 0%'
        and pg_get_constraintdef(constraint_record.oid) like '%display_order <= 19%'
      )
    from pg_catalog.pg_constraint constraint_record
    where constraint_record.conname in (
      'guide_service_cities_display_order_range',
      'guide_service_languages_display_order_range',
      'guide_service_specialties_display_order_range'
    )
  ),
  'every normalized service relation is bounded to twenty ordered values'
);

select ok(
  to_regclass('public.guide_service_cities_city_listing_idx') is not null
  and to_regclass('public.guide_service_languages_language_listing_idx') is not null
  and to_regclass('public.guide_service_specialties_specialty_listing_idx') is not null,
  'all reverse facet lookup indexes exist'
);

select results_eq(
  $$select id from public.guide_languages where is_active order by display_order$$,
  $$
    values
      ('francais'), ('fon'), ('yoruba'), ('mina'), ('adja'),
      ('bariba'), ('dendi'), ('anglais'), ('portugais')
  $$,
  'the V1 language authority is seeded deterministically'
);

select results_eq(
  $$select id from public.guide_specialties where is_active order by display_order$$,
  $$
    values
      ('histoire'), ('patrimoine'), ('culture'), ('art-artisanat'),
      ('gastronomie'), ('nature-ecotourisme'), ('architecture'), ('vie-locale')
  $$,
  'the V1 specialty authority is seeded deterministically'
);

select ok(
  to_regprocedure('public.list_guide_facets_v1()') is not null,
  'the V1 guide facets RPC exists'
);

select ok(
  to_regprocedure('public.list_guide_services_v1(text,text,text,text,integer)') is not null,
  'the V1 guide services RPC exists with the expected identity arguments'
);

select is(
  (
    select procedure.proargnames[1:5]
    from pg_catalog.pg_proc procedure
    where procedure.oid =
      'public.list_guide_services_v1(text,text,text,text,integer)'::regprocedure
  ),
  array[
    'p_city_id',
    'p_language_id',
    'p_specialty_id',
    'p_cursor',
    'p_limit'
  ]::text[],
  'guide service input names are stable'
);

select is(
  (
    select procedure.proargnames[6:20]
    from pg_catalog.pg_proc procedure
    where procedure.oid =
      'public.list_guide_services_v1(text,text,text,text,integer)'::regprocedure
  ),
  array[
    'schema_version',
    'id',
    'name',
    'base_city_id',
    'base_city_name',
    'cover_image_url',
    'cover_image_alt',
    'languages',
    'coverage_cities',
    'specialties',
    'indicative_price_xof',
    'rating_avg',
    'rating_count',
    'verified',
    'row_cursor'
  ]::text[],
  'guide service output names expose only the dedicated card contract'
);

select is(
  (
    select procedure.proargnames
    from pg_catalog.pg_proc procedure
    where procedure.oid = 'public.list_guide_facets_v1()'::regprocedure
  ),
  array['schema_version', 'facet_type', 'facet_id', 'label']::text[],
  'guide facet output names are stable'
);

select ok(
  (
    select bool_and(procedure.provolatile = 's'::"char")
    from pg_catalog.pg_proc procedure
    where procedure.oid in (
      'public.list_guide_facets_v1()'::regprocedure,
      'public.list_guide_services_v1(text,text,text,text,integer)'::regprocedure
    )
  ),
  'both guide discovery RPCs are STABLE'
);

select ok(
  not exists (
    select 1
    from pg_catalog.pg_proc procedure
    where procedure.oid in (
      'public.list_guide_facets_v1()'::regprocedure,
      'public.list_guide_services_v1(text,text,text,text,integer)'::regprocedure
    )
      and procedure.prosecdef
  ),
  'both guide discovery RPCs are SECURITY INVOKER'
);

select ok(
  (
    select bool_and(array_to_string(procedure.proconfig, ',') = 'search_path=""')
    from pg_catalog.pg_proc procedure
    where procedure.oid in (
      'public.list_guide_facets_v1()'::regprocedure,
      'public.list_guide_services_v1(text,text,text,text,integer)'::regprocedure
    )
  ),
  'both guide discovery RPCs have an empty fixed search path'
);

select ok(
  has_function_privilege('anon', 'public.list_guide_facets_v1()', 'execute')
  and has_function_privilege(
    'anon',
    'public.list_guide_services_v1(text,text,text,text,integer)',
    'execute'
  )
  and has_function_privilege('authenticated', 'public.list_guide_facets_v1()', 'execute')
  and has_function_privilege(
    'authenticated',
    'public.list_guide_services_v1(text,text,text,text,integer)',
    'execute'
  ),
  'anon and authenticated can execute both public guide discovery RPCs'
);

select ok(
  not exists (
    select 1
    from pg_catalog.pg_proc procedure
    cross join lateral aclexplode(procedure.proacl) privilege
    where procedure.oid in (
      'public.list_guide_facets_v1()'::regprocedure,
      'public.list_guide_services_v1(text,text,text,text,integer)'::regprocedure
    )
      and privilege.grantee = 0
      and privilege.privilege_type = 'EXECUTE'
  ),
  'PUBLIC has no direct execute access to guide discovery RPCs'
);

select ok(
  has_column_privilege('anon', 'public.guide_languages', 'id', 'select')
  and not has_column_privilege('anon', 'public.guide_languages', 'created_at', 'select')
  and has_column_privilege('anon', 'public.guide_service_cities', 'city_id', 'select')
  and not has_column_privilege('anon', 'public.guide_service_cities', 'created_at', 'select'),
  'anonymous column grants expose identifiers and display data but not audit timestamps'
);

select ok(
  (
    select count(*) = 5
      and bool_and(procedure.prosecdef)
      and bool_and(array_to_string(procedure.proconfig, ',') = 'search_path=""')
    from pg_catalog.pg_proc procedure
    where procedure.oid in (
      'app_private.sync_guide_discovery_relations(uuid)'::regprocedure,
      'app_private.sync_guide_discovery_relations_from_legacy()'::regprocedure,
      'app_private.assert_guide_discovery_relations_match(uuid)'::regprocedure,
      'app_private.assert_all_guide_discovery_relations_trigger()'::regprocedure,
      'app_private.assert_guide_discovery_relations_trigger()'::regprocedure
    )
  ),
  'all private guide relation helpers are SECURITY DEFINER with an empty search path'
);

select ok(
  not exists (
    select 1
    from (
      values ('anon'), ('authenticated'), ('service_role')
    ) api_role(role_name)
    cross join (
      values
        ('app_private.sync_guide_discovery_relations(uuid)'),
        ('app_private.sync_guide_discovery_relations_from_legacy()'),
        ('app_private.assert_guide_discovery_relations_match(uuid)'),
        ('app_private.assert_all_guide_discovery_relations_trigger()'),
        ('app_private.assert_guide_discovery_relations_trigger()')
    ) private_function(signature)
    where has_function_privilege(
      api_role.role_name,
      private_function.signature,
      'execute'
    )
  ),
  'private synchronization and assertion helpers are not directly callable by API roles'
);

select ok(
  exists (
    select 1
    from pg_catalog.pg_trigger trigger_record
    where trigger_record.tgrelid = 'public.guide_details'::regclass
      and trigger_record.tgname = 'guide_details_sync_discovery_relations'
      and not trigger_record.tgisinternal
  )
  and (
    select count(*) = 6
      and bool_and(trigger_record.tgdeferrable)
      and bool_and(trigger_record.tginitdeferred)
    from pg_catalog.pg_trigger trigger_record
    where trigger_record.tgname in (
      'guide_service_cities_match_legacy',
      'guide_service_languages_match_legacy',
      'guide_service_specialties_match_legacy',
      'guide_languages_preserve_discovery_mapping',
      'guide_specialties_preserve_discovery_mapping',
      'cities_preserve_guide_discovery_mapping'
    )
  ),
  'legacy, relation and reference mutations are all covered by synchronization invariants'
);

select ok(
  not has_table_privilege('authenticated', 'public.guide_service_cities', 'insert')
  and not has_any_column_privilege('authenticated', 'public.guide_service_cities', 'insert')
  and not has_table_privilege('authenticated', 'public.guide_service_cities', 'update')
  and not has_any_column_privilege('authenticated', 'public.guide_service_cities', 'update')
  and not has_table_privilege('authenticated', 'public.guide_service_cities', 'delete')
  and not has_table_privilege('authenticated', 'public.guide_service_languages', 'insert')
  and not has_any_column_privilege('authenticated', 'public.guide_service_languages', 'insert')
  and not has_table_privilege('authenticated', 'public.guide_service_languages', 'update')
  and not has_any_column_privilege('authenticated', 'public.guide_service_languages', 'update')
  and not has_table_privilege('authenticated', 'public.guide_service_languages', 'delete')
  and not has_table_privilege('authenticated', 'public.guide_service_specialties', 'insert')
  and not has_any_column_privilege('authenticated', 'public.guide_service_specialties', 'insert')
  and not has_table_privilege('authenticated', 'public.guide_service_specialties', 'update')
  and not has_any_column_privilege('authenticated', 'public.guide_service_specialties', 'update')
  and not has_table_privilege('authenticated', 'public.guide_service_specialties', 'delete'),
  'authenticated has no direct DML grant on normalized guide relations'
);

select ok(
  not exists (
    select 1
    from pg_catalog.pg_policies policy_record
    where policy_record.schemaname = 'public'
      and policy_record.tablename in (
        'guide_service_cities',
        'guide_service_languages',
        'guide_service_specialties'
      )
      and policy_record.cmd in ('ALL', 'INSERT', 'UPDATE', 'DELETE')
      and 'authenticated' = any(policy_record.roles)
  ),
  'normalized guide relations expose no authenticated write policy'
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
  'b0000000-0000-4000-8000-000000000001',
  'authenticated',
  'authenticated',
  'guide-discovery-owner@kwabor.test',
  '',
  now(),
  now(),
  now()
);

insert into public.profiles (
  user_id,
  first_name,
  last_name,
  city_id,
  onboarding_completed_at
)
values (
  'b0000000-0000-4000-8000-000000000001',
  'Guide',
  'Discovery',
  'cotonou',
  now()
);

insert into public.user_roles (user_id, role, verification_status)
values (
  'b0000000-0000-4000-8000-000000000001',
  'guide',
  'verified'
);

insert into public.amenities (id, name_key, allowed_variants, sort_order)
values (
  'guide-discovery-expertise',
  'amenity.guide_discovery_expertise',
  array['guide']::public.catalog_detail_variant[],
  990
);

insert into public.guide_languages (id, label, is_active, display_order)
values ('guide-hidden-language', 'Langue masquée', false, 990);

insert into public.guide_specialties (id, label, is_active, display_order)
values ('guide-hidden-specialty', 'Spécialité masquée', false, 990);

insert into public.cities (id, name, slug, enabled)
values ('guide-hidden-city', 'Ville masquée', 'guide-hidden-city', false);

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
  district,
  address,
  lat,
  lng,
  price_from_xof,
  price_unit,
  opening_hours,
  contact_phone,
  rating_avg,
  rating_count,
  verified,
  published_at
)
values
  (
    'b1000000-0000-4000-8000-000000000101',
    'etablissement', 'guide', 'commercial', 'guide-touristique',
    'b0000000-0000-4000-8000-000000000001',
    'b0000000-0000-4000-8000-000000000001',
    'brouillon',
    'Guide A Cotonou Ouidah',
    'guide-discovery-a',
    'Service guide complet couvrant Cotonou et Ouidah pour valider les facettes publiques.',
    'cotonou', 'Centre', 'Cotonou', 6.3703, 2.3912,
    12000, 'par_personne', tests.valid_opening_hours(), '+2290100000101',
    5.00, 10, true, null
  ),
  (
    'b1000000-0000-4000-8000-000000000102',
    'etablissement', 'guide', 'commercial', 'guide-touristique',
    'b0000000-0000-4000-8000-000000000001',
    'b0000000-0000-4000-8000-000000000001',
    'brouillon',
    'Guide B Cotonou',
    'guide-discovery-b',
    'Service guide anglophone couvrant Cotonou pour valider les filtres de découverte publique.',
    'cotonou', 'Centre', 'Cotonou', 6.3704, 2.3913,
    9000, 'par_personne', tests.valid_opening_hours(), '+2290100000102',
    null, 0, false, null
  ),
  (
    'b1000000-0000-4000-8000-000000000103',
    'etablissement', 'guide', 'commercial', 'guide-touristique',
    'b0000000-0000-4000-8000-000000000001',
    'b0000000-0000-4000-8000-000000000001',
    'brouillon',
    'Guide D Ouidah',
    'guide-discovery-d',
    'Service guide lusophone couvrant Ouidah pour valider le classement totalement déterministe.',
    'ouidah', 'Centre', 'Ouidah', 6.3631, 2.0851,
    15000, 'par_personne', tests.valid_opening_hours(), '+2290100000103',
    5.00, 10, true, null
  ),
  (
    'b1000000-0000-4000-8000-000000000104',
    'etablissement', 'guide', 'commercial', 'guide-touristique',
    'b0000000-0000-4000-8000-000000000001',
    'b0000000-0000-4000-8000-000000000001',
    'brouillon',
    'Guide Privé Porto Novo',
    'guide-discovery-private',
    'Service guide encore en brouillon qui ne doit alimenter aucun résultat ni aucune facette.',
    'porto-novo', 'Centre', 'Porto-Novo', 6.4969, 2.6289,
    10000, 'par_personne', tests.valid_opening_hours(), '+2290100000104',
    5.00, 999, true, null
  ),
  (
    'b1000000-0000-4000-8000-000000000105',
    'etablissement', 'guide', 'commercial', 'guide-touristique',
    'b0000000-0000-4000-8000-000000000001',
    'b0000000-0000-4000-8000-000000000001',
    'brouillon',
    'Guide Incomplet Parakou',
    'guide-discovery-incomplete',
    'Service guide publié sans relations normalisées et exclu de manière fail closed du contrat.',
    'parakou', 'Centre', 'Parakou', 9.3372, 2.6303,
    11000, 'par_personne', tests.valid_opening_hours(), '+2290100000105',
    5.00, 999, true, null
  );

insert into public.guide_details (
  listing_id,
  languages,
  zones,
  specialties,
  indicative_price_xof,
  accreditation,
  experience_years
)
values
  (
    'b1000000-0000-4000-8000-000000000101',
    array['francais', 'portugais'], array['Cotonou', 'Ouidah'],
    array['histoire', 'patrimoine'], 12000, 'Guide national', 8
  ),
  (
    'b1000000-0000-4000-8000-000000000102',
    array['anglais'], array['Cotonou'], array['culture'], 9000, null, 4
  ),
  (
    'b1000000-0000-4000-8000-000000000103',
    array['portugais'], array['Ouidah'], array['culture'], 15000, null, 6
  ),
  (
    'b1000000-0000-4000-8000-000000000104',
    array['portugais'], array['Porto-Novo'], array['histoire'], 10000, null, 3
  ),
  (
    'b1000000-0000-4000-8000-000000000105',
    array['francais'], array['Parakou'], array['histoire'], 11000, null, 2
  );

insert into public.listing_amenities (listing_id, amenity_id, display_order)
select listing.id, 'guide-discovery-expertise', 0
from public.listings listing
where listing.id between
  'b1000000-0000-4000-8000-000000000101'::uuid
  and 'b1000000-0000-4000-8000-000000000105'::uuid;

insert into public.listing_media (
  listing_id,
  url,
  alt,
  display_order,
  is_cover,
  kind
)
select
  listing.id,
  'https://media.example.invalid/' || listing.slug || '.jpg',
  'Portrait public ' || listing.name,
  0,
  true,
  'image'
from public.listings listing
where listing.id between
  'b1000000-0000-4000-8000-000000000101'::uuid
  and 'b1000000-0000-4000-8000-000000000105'::uuid;

update public.listings
set
  status = 'publie',
  published_at = case id
    when 'b1000000-0000-4000-8000-000000000102'::uuid then '2026-08-01 08:00:00+00'
    else '2026-08-03 08:00:00+00'
  end
where id in (
  'b1000000-0000-4000-8000-000000000101',
  'b1000000-0000-4000-8000-000000000102',
  'b1000000-0000-4000-8000-000000000103'
);

set constraints all immediate;
set constraints all deferred;

select is(
  tests.count_as(
    'anon',
    null,
    'select id, label, is_active, display_order from public.guide_languages'
  ),
  9::bigint,
  'anonymous callers see only active language references'
);

select is(
  tests.count_as(
    'anon',
    null,
    'select id, label, is_active, display_order from public.guide_specialties'
  ),
  8::bigint,
  'anonymous callers see only active specialty references'
);

select is(
  tests.count_as(
    'anon',
    null,
    'select listing_id, city_id, display_order from public.guide_service_cities'
  ),
  4::bigint,
  'service-city RLS exposes only links belonging to published guides'
);

select is(
  tests.count_as(
    'anon',
    null,
    'select listing_id, language_id, display_order from public.guide_service_languages'
  ),
  4::bigint,
  'service-language RLS exposes only links belonging to published guides'
);

select is(
  tests.count_as(
    'anon',
    null,
    'select listing_id, specialty_id, display_order from public.guide_service_specialties'
  ),
  4::bigint,
  'service-specialty RLS exposes only links belonging to published guides'
);

select is(
  tests.count_as(
    'authenticated',
    'b0000000-0000-4000-8000-000000000001',
    $$
      select listing_id, city_id, display_order
      from public.guide_service_cities
      where listing_id = 'b1000000-0000-4000-8000-000000000104'
    $$
  ),
  1::bigint,
  'an authorized manager can read normalized relations for its own draft'
);

select is(
  tests.count_as('anon', null, 'select * from public.list_guide_facets_v1()'),
  8::bigint,
  'facets contain only values offered by complete published guides'
);

select is(
  tests.count_as(
    'anon',
    null,
    $$
      select * from public.list_guide_facets_v1()
      where facet_type not in ('city', 'language', 'specialty')
    $$
  ),
  0::bigint,
  'facet_type is restricted to the three contract values'
);

select is(
  tests.count_as(
    'anon',
    null,
    $$
      select * from public.list_guide_facets_v1()
      where (facet_type, facet_id) in (
        ('city', 'cotonou'),
        ('city', 'ouidah'),
        ('language', 'francais'),
        ('language', 'anglais'),
        ('language', 'portugais'),
        ('specialty', 'histoire'),
        ('specialty', 'patrimoine'),
        ('specialty', 'culture')
      )
    $$
  ),
  8::bigint,
  'facets expose the expected available city, language and specialty identifiers'
);

select is(
  tests.count_as(
    'anon',
    null,
    $$
      select * from public.list_guide_facets_v1()
      where facet_id in (
        'porto-novo', 'parakou', 'guide-hidden-city',
        'guide-hidden-language', 'guide-hidden-specialty'
      )
    $$
  ),
  0::bigint,
  'draft and inactive values never contribute to facets'
);

select is(
  tests.count_as('anon', null, 'select * from public.list_guide_services_v1()'),
  3::bigint,
  'anonymous discovery returns every and only complete published guides'
);

select is(
  tests.count_as(
    'authenticated',
    'b0000000-0000-4000-8000-000000000001',
    $$
      select * from public.list_guide_services_v1()
      where id = 'b1000000-0000-4000-8000-000000000104'
    $$
  ),
  0::bigint,
  'even an owner cannot leak its draft through the public RPC'
);

select is(
  tests.count_as(
    'anon',
    null,
    $$
      select * from public.list_guide_services_v1()
      where id = 'b1000000-0000-4000-8000-000000000105'
    $$
  ),
  0::bigint,
  'an unpublished guide never enters public discovery'
);

select is(
  tests.count_as(
    'anon',
    null,
    $$
      select *
      from public.list_guide_services_v1(
        p_city_id => 'ouidah',
        p_language_id => 'portugais',
        p_specialty_id => 'histoire'
      )
    $$
  ),
  1::bigint,
  'city, language and specialty filters combine with AND'
);

select is(
  tests.count_as(
    'anon',
    null,
    $$
      select *
      from public.list_guide_services_v1(
        p_city_id => 'ouidah',
        p_language_id => 'anglais'
      )
    $$
  ),
  0::bigint,
  'a guide must satisfy every supplied dimension'
);

select is(
  tests.count_as(
    'anon',
    null,
    $$
      select *
      from public.list_guide_services_v1(p_city_id => 'ouidah')
      where id = 'b1000000-0000-4000-8000-000000000101'
        and base_city_id = 'cotonou'
    $$
  ),
  1::bigint,
  'city filtering uses covered destinations rather than the base city approximation'
);

select is(
  (
    tests.json_as(
      'anon',
      null,
      $$
        select * from public.list_guide_services_v1()
        where id = 'b1000000-0000-4000-8000-000000000101'
      $$
    ) -> 0 ->> 'schema_version'
  )::integer,
  1,
  'every guide card carries schema version one'
);

select is(
  tests.sorted_jsonb_keys(
    tests.json_as(
      'anon',
      null,
      $$
        select * from public.list_guide_services_v1()
        where id = 'b1000000-0000-4000-8000-000000000101'
      $$
    ) -> 0
  ),
  array[
    'base_city_id', 'base_city_name', 'cover_image_alt', 'cover_image_url',
    'coverage_cities', 'id', 'indicative_price_xof', 'languages', 'name',
    'rating_avg', 'rating_count', 'row_cursor', 'schema_version', 'specialties', 'verified'
  ]::text[],
  'the guide card contains exactly the dedicated public fields'
);

select is(
  tests.json_as(
    'anon',
    null,
    $$
      select cover_image_url, cover_image_alt
      from public.list_guide_services_v1()
      where id = 'b1000000-0000-4000-8000-000000000101'
    $$
  ) -> 0,
  '{"cover_image_url":"https://media.example.invalid/guide-discovery-a.jpg","cover_image_alt":"Portrait public Guide A Cotonou Ouidah"}'::jsonb,
  'cover projection contains the public URL and alt text only'
);

select is(
  tests.json_as(
    'anon',
    null,
    $$
      select base_city_id, base_city_name
      from public.list_guide_services_v1()
      where id = 'b1000000-0000-4000-8000-000000000101'
    $$
  ) -> 0,
  '{"base_city_id":"cotonou","base_city_name":"Cotonou"}'::jsonb,
  'base city projection contains a stable identifier and label only'
);

select ok(
  not exists (
    select 1
    from jsonb_array_elements(
      tests.json_as(
        'anon',
        null,
        $$
          select * from public.list_guide_services_v1()
          where id = 'b1000000-0000-4000-8000-000000000101'
        $$
      ) -> 0 -> 'coverage_cities'
    ) item
    where tests.sorted_jsonb_keys(item) <> array['id', 'label']::text[]
  ),
  'every covered city is projected as an id-label object'
);

select ok(
  not exists (
    select 1
    from jsonb_array_elements(
      tests.json_as(
        'anon',
        null,
        $$
          select * from public.list_guide_services_v1()
          where id = 'b1000000-0000-4000-8000-000000000101'
        $$
      ) -> 0 -> 'languages'
    ) item
    where tests.sorted_jsonb_keys(item) <> array['id', 'label']::text[]
  ),
  'every language is projected as an id-label object'
);

select ok(
  not exists (
    select 1
    from jsonb_array_elements(
      tests.json_as(
        'anon',
        null,
        $$
          select * from public.list_guide_services_v1()
          where id = 'b1000000-0000-4000-8000-000000000101'
        $$
      ) -> 0 -> 'specialties'
    ) item
    where tests.sorted_jsonb_keys(item) <> array['id', 'label']::text[]
  ),
  'every specialty is projected as an id-label object'
);

select ok(
  not tests.jsonb_has_any_key(
    tests.json_as(
      'anon',
      null,
      $$
        select * from public.list_guide_services_v1()
        where id = 'b1000000-0000-4000-8000-000000000101'
      $$
    ) -> 0,
    array[
      'owner_id', 'organization_id', 'submitted_by', 'storage_path',
      'contact_phone', 'contact_whatsapp', 'email', 'external_url',
      'accreditation', 'experience_years', 'sponsored_until',
      'is_sponsored_placement', 'editorial_pin_until', 'likes_count', 'views_count',
      'published_at'
    ]
  ),
  'guide cards expose no authority, contact, moderation or sponsorship data'
);

select is(
  tests.json_as(
    'anon',
    null,
    $$
      select
        indicative_price_xof,
        rating_avg,
        rating_count,
        verified
      from public.list_guide_services_v1()
      where id = 'b1000000-0000-4000-8000-000000000101'
    $$
  ) -> 0,
  '{"indicative_price_xof":12000,"rating_avg":5.00,"rating_count":10,"verified":true}'::jsonb,
  'price, rating, count and verification status are projected from authoritative columns'
);

create temporary table guide_first_page as
select * from public.list_guide_services_v1(p_limit => 1);

select is(
  (select count(*) from guide_first_page),
  2::bigint,
  'the RPC returns limit plus one rows for continuation detection'
);

select is(
  (
    select (
      convert_from(decode(row_cursor, 'base64'), 'UTF8')::jsonb ->> 'rating'
    )::numeric
    from public.list_guide_services_v1()
    where id = 'b1000000-0000-4000-8000-000000000102'
  ),
  (-1)::numeric,
  'an unrated guide has the deterministic cursor rank minus one'
);

select results_eq(
  $$
    select id
    from guide_first_page
    order by
      (convert_from(decode(row_cursor, 'base64'), 'UTF8')::jsonb ->> 'rating')::numeric desc,
      (convert_from(decode(row_cursor, 'base64'), 'UTF8')::jsonb ->> 'rating_count')::integer desc,
      (convert_from(decode(row_cursor, 'base64'), 'UTF8')::jsonb ->> 'published_at')::timestamptz desc,
      id desc
  $$,
  $$
    values
      ('b1000000-0000-4000-8000-000000000103'::uuid),
      ('b1000000-0000-4000-8000-000000000101'::uuid)
  $$,
  'rating, count, publication time and id provide a total deterministic order'
);

select is(
  (
    select tests.sorted_jsonb_keys(
      convert_from(decode(row_cursor, 'base64'), 'UTF8')::jsonb
    )
    from guide_first_page
    where id = 'b1000000-0000-4000-8000-000000000103'
  ),
  array['fingerprint', 'id', 'published_at', 'rating', 'rating_count', 'v']::text[],
  'the opaque cursor contains only version, filter fingerprint and ranking tuple'
);

select results_eq(
  format(
    $$
      select id
      from public.list_guide_services_v1(p_cursor => %L, p_limit => 1)
      order by
        (convert_from(decode(row_cursor, 'base64'), 'UTF8')::jsonb ->> 'rating')::numeric desc,
        (convert_from(decode(row_cursor, 'base64'), 'UTF8')::jsonb ->> 'rating_count')::integer desc,
        (convert_from(decode(row_cursor, 'base64'), 'UTF8')::jsonb ->> 'published_at')::timestamptz desc,
        id desc
    $$,
    (
      select row_cursor from guide_first_page
      where id = 'b1000000-0000-4000-8000-000000000103'
    )
  ),
  $$
    values
      ('b1000000-0000-4000-8000-000000000101'::uuid),
      ('b1000000-0000-4000-8000-000000000102'::uuid)
  $$,
  'keyset continuation resumes strictly after the supplied ranking tuple'
);

select throws_ok(
  format(
    $$
      select *
      from public.list_guide_services_v1(
        p_city_id => 'ouidah',
        p_cursor => %L
      )
    $$,
    (
      select row_cursor from guide_first_page
      where id = 'b1000000-0000-4000-8000-000000000103'
    )
  ),
  '22023',
  'p_cursor does not match guide filters',
  'a cursor cannot be reused with another filter fingerprint'
);

select throws_ok(
  $$select * from public.list_guide_services_v1(p_city_id => ' ouidah ')$$,
  '22023',
  'p_city_id is invalid',
  'city identifiers with non-canonical edges are rejected'
);

select throws_ok(
  $$select * from public.list_guide_services_v1(p_language_id => repeat('a', 81))$$,
  '22023',
  'p_language_id is invalid',
  'language identifiers longer than eighty characters are rejected'
);

select throws_ok(
  $$select * from public.list_guide_services_v1(p_specialty_id => 'Histoire')$$,
  '22023',
  'p_specialty_id is invalid',
  'non-canonical specialty identifiers are rejected'
);

select throws_ok(
  $$select * from public.list_guide_services_v1(p_cursor => 'not-base64!')$$,
  '22023',
  'p_cursor is malformed',
  'malformed cursors are rejected'
);

select throws_ok(
  format(
    'select * from public.list_guide_services_v1(p_cursor => %L)',
    replace(
      encode(convert_to('{"v":2}'::text, 'UTF8'), 'base64'),
      chr(10),
      ''
    )
  ),
  '22023',
  'p_cursor version is unsupported',
  'unsupported cursor versions are rejected'
);

select throws_ok(
  $$select * from public.list_guide_services_v1(p_city_id => 'unknown-city')$$,
  '22023',
  'p_city_id is unknown',
  'unknown city identifiers are rejected'
);

select throws_ok(
  $$select * from public.list_guide_services_v1(p_language_id => 'unknown-language')$$,
  '22023',
  'p_language_id is unknown',
  'unknown language identifiers are rejected'
);

select throws_ok(
  $$select * from public.list_guide_services_v1(p_specialty_id => 'unknown-specialty')$$,
  '22023',
  'p_specialty_id is unknown',
  'unknown specialty identifiers are rejected'
);

select throws_ok(
  $$select * from public.list_guide_services_v1(p_language_id => 'guide-hidden-language')$$,
  '22023',
  'p_language_id is unknown',
  'inactive language identifiers are rejected as unavailable'
);

select throws_ok(
  $$select * from public.list_guide_services_v1(p_limit => 0)$$,
  '22023',
  'p_limit must be between 1 and 50',
  'zero limits are rejected'
);

select throws_ok(
  $$select * from public.list_guide_services_v1(p_limit => 51)$$,
  '22023',
  'p_limit must be between 1 and 50',
  'oversized limits are rejected'
);

select throws_ok(
  $$select * from public.list_guide_services_v1(p_limit => null)$$,
  '22023',
  'p_limit must be between 1 and 50',
  'null limits are rejected'
);

select is(
  tests.command_as(
    'authenticated',
    'b0000000-0000-4000-8000-000000000001',
    $command$
      update public.guide_details
      set
        languages = array['anglais', 'francais'],
        zones = array['Ouidah', 'Cotonou'],
        specialties = array['culture', 'histoire']
      where listing_id = 'b1000000-0000-4000-8000-000000000104'
    $command$
  ),
  1::bigint,
  'an authorized legacy guide-details update succeeds without relation DML rights'
);

select results_eq(
  $$
    select language_id, display_order
    from public.guide_service_languages
    where listing_id = 'b1000000-0000-4000-8000-000000000104'
    order by display_order
  $$,
  $$values ('anglais', 0), ('francais', 1)$$,
  'legacy language order is synchronized to canonical relation rows'
);

select results_eq(
  $$
    select city_id, display_order
    from public.guide_service_cities
    where listing_id = 'b1000000-0000-4000-8000-000000000104'
    order by display_order
  $$,
  $$values ('ouidah', 0), ('cotonou', 1)$$,
  'legacy zone order is synchronized to canonical city relation rows'
);

select results_eq(
  $$
    select specialty_id, display_order
    from public.guide_service_specialties
    where listing_id = 'b1000000-0000-4000-8000-000000000104'
    order by display_order
  $$,
  $$values ('culture', 0), ('histoire', 1)$$,
  'legacy specialty order is synchronized to canonical relation rows'
);

select throws_ok(
  $test$
    select tests.command_as(
      'authenticated',
      'b0000000-0000-4000-8000-000000000001',
      $command$
        delete from public.guide_service_languages
        where listing_id = 'b1000000-0000-4000-8000-000000000104'
          and language_id = 'anglais'
      $command$
    )
  $test$,
  '42501',
  'permission denied for table guide_service_languages',
  'an authenticated manager cannot mutate normalized guide relations directly'
);

insert into public.guide_languages (id, label, display_order)
values
  ('ambiguity-by-label', 'shared-alias', 1001),
  ('shared-alias', 'Distinct alias', 1002);

insert into public.guide_languages (id, label, display_order)
select
  'test-language-' || pg_catalog.lpad(series.value::text, 2, '0'),
  'Test language ' || pg_catalog.lpad(series.value::text, 2, '0'),
  1100 + series.value
from pg_catalog.generate_series(1, 10) as series(value);

select throws_ok(
  $test$
    select tests.command_as(
      'authenticated',
      'b0000000-0000-4000-8000-000000000001',
      $command$
        update public.guide_details
        set languages = array['unknown-language']
        where listing_id = 'b1000000-0000-4000-8000-000000000104'
      $command$
    )
  $test$,
  '23514',
  'Guide b1000000-0000-4000-8000-000000000104 has an unknown or ambiguous legacy language: unknown-language',
  'an unknown legacy reference aborts the entire guide-details update'
);

select throws_ok(
  $test$
    select tests.command_as(
      'authenticated',
      'b0000000-0000-4000-8000-000000000001',
      $command$
        update public.guide_details
        set languages = array['shared-alias']
        where listing_id = 'b1000000-0000-4000-8000-000000000104'
      $command$
    )
  $test$,
  '23514',
  'Guide b1000000-0000-4000-8000-000000000104 has an unknown or ambiguous legacy language: shared-alias',
  'an ambiguous legacy reference aborts the entire guide-details update'
);

select throws_ok(
  $test$
    select tests.command_as(
      'authenticated',
      'b0000000-0000-4000-8000-000000000001',
      $command$
        update public.guide_details
        set languages = array['francais', 'Français']
        where listing_id = 'b1000000-0000-4000-8000-000000000104'
      $command$
    )
  $test$,
  '23514',
  'Guide b1000000-0000-4000-8000-000000000104 maps more than one legacy language to canonical language francais',
  'two distinct aliases mapping to one canonical value abort the update'
);

select throws_ok(
  $test$
    select tests.command_as(
      'authenticated',
      'b0000000-0000-4000-8000-000000000001',
      $command$
        update public.guide_details
        set languages = array[
          'francais', 'fon', 'yoruba', 'mina', 'adja', 'bariba', 'dendi',
          'anglais', 'portugais', 'ambiguity-by-label', 'shared-alias',
          'test-language-01', 'test-language-02', 'test-language-03',
          'test-language-04', 'test-language-05', 'test-language-06',
          'test-language-07', 'test-language-08', 'test-language-09',
          'test-language-10'
        ]
        where listing_id = 'b1000000-0000-4000-8000-000000000104'
      $command$
    )
  $test$,
  '23514',
  'new row for relation "guide_details" violates check constraint "guide_details_languages_valid"',
  'more than twenty legacy values abort the entire guide-details update'
);

select throws_ok(
  $$
    select tests.force_guide_language_divergence(
      'b1000000-0000-4000-8000-000000000104',
      'anglais'
    )
  $$,
  '23514',
  'Guide discovery relations diverge from legacy arrays for guide b1000000-0000-4000-8000-000000000104',
  'the deferred invariant rejects even privileged direct relation divergence'
);

select throws_ok(
  $$select tests.force_guide_language_reference_ambiguity()$$,
  '23514',
  'Guide discovery relations diverge from legacy arrays for guide b1000000-0000-4000-8000-000000000101',
  'the deferred invariant rejects a privileged reference mutation that makes legacy mapping ambiguous'
);

select ok(
  (
    select detail.languages = array['anglais', 'francais']
      and detail.zones = array['Ouidah', 'Cotonou']
      and detail.specialties = array['culture', 'histoire']
    from public.guide_details detail
    where detail.listing_id = 'b1000000-0000-4000-8000-000000000104'
  )
  and (
    select pg_catalog.array_agg(link.language_id order by link.display_order)
      = array['anglais', 'francais']
    from public.guide_service_languages link
    where link.listing_id = 'b1000000-0000-4000-8000-000000000104'
  )
  and (
    select pg_catalog.array_agg(link.city_id order by link.display_order)
      = array['ouidah', 'cotonou']
    from public.guide_service_cities link
    where link.listing_id = 'b1000000-0000-4000-8000-000000000104'
  )
  and (
    select pg_catalog.array_agg(link.specialty_id order by link.display_order)
      = array['culture', 'histoire']
    from public.guide_service_specialties link
    where link.listing_id = 'b1000000-0000-4000-8000-000000000104'
  ),
  'all rejected writes roll back both legacy arrays and normalized relations atomically'
);

select * from finish();
rollback;
