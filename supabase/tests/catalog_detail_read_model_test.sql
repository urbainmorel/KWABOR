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

create or replace function tests.catalog_payload_as(
  db_role text,
  uid uuid,
  target_listing_id uuid
)
returns jsonb
language plpgsql
as $$
declare
  result jsonb;
begin
  perform tests.use_auth_context(db_role, uid);
  select detail.payload
  into result
  from public.get_catalog_detail_v1(target_listing_id) detail;
  reset role;
  return result;
exception
  when others then
    reset role;
    raise;
end;
$$;

create or replace function tests.affected_rows_as(db_role text, uid uuid, sql text)
returns bigint
language plpgsql
as $$
declare
  result bigint;
begin
  perform tests.use_auth_context(db_role, uid);
  execute sql;
  get diagnostics result = row_count;
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

select plan(211);

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
values
  (
    'da000000-0000-4000-8000-000000000001',
    'authenticated',
    'authenticated',
    'detail-owner@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    'da000000-0000-4000-8000-000000000002',
    'authenticated',
    'authenticated',
    'detail-other@kwabor.test',
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
values
  ('da000000-0000-4000-8000-000000000001', 'Owner', 'Detail', 'cotonou', now()),
  ('da000000-0000-4000-8000-000000000002', 'Other', 'Detail', 'cotonou', now());

insert into public.user_roles (user_id, role, verification_status)
values
  ('da000000-0000-4000-8000-000000000001', 'promoteur', 'verified'),
  ('da000000-0000-4000-8000-000000000002', 'promoteur', 'verified');

insert into public.organizations (
  id,
  type,
  name,
  slug,
  verification_status,
  primary_owner_id,
  created_by
)
values (
  'da000000-0000-4000-8000-000000000010',
  'promoteur',
  'Organisation Detail',
  'organisation-detail-test',
  'verified',
  'da000000-0000-4000-8000-000000000001',
  'da000000-0000-4000-8000-000000000001'
);

insert into public.categories (
  id,
  listing_type,
  subtype,
  name_key,
  default_listing_class,
  detail_variant,
  sort_order
)
values (
  'commercial-club',
  'etablissement',
  'club',
  'category.commercial.club',
  'commercial',
  'nightlife',
  80
);

insert into public.amenities (id, name_key, allowed_variants, sort_order)
values (
  'local-expertise',
  'amenity.local_expertise',
  array['guide']::public.catalog_detail_variant[],
  40
);

insert into public.listings (
  id,
  type,
  subtype,
  listing_class,
  category_id,
  owner_id,
  organization_id,
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
  external_url,
  socials,
  tags
)
values
  (
    'da110000-0000-4000-8000-000000000001',
    'etablissement',
    'hotel',
    'commercial',
    'commercial-hotel',
    'da000000-0000-4000-8000-000000000001',
    null,
    'da000000-0000-4000-8000-000000000001',
    'brouillon',
    'Hotel Detail Cotonou',
    'hotel-detail-cotonou-test',
    'Hotel de test complet utilise pour verifier le contrat de lecture des hebergements Kwabor.',
    'cotonou',
    'Haie Vive',
    'Rue de la Paix, Cotonou',
    6.3586,
    2.4041,
    25000,
    'par_nuit',
    tests.valid_opening_hours(),
    '+2290100000001',
    'https://hotel.example.invalid/reservation',
    '{"instagram":"https://instagram.com/kwabor.hotel"}'::jsonb,
    array['hotel', 'test']
  ),
  (
    'da120000-0000-4000-8000-000000000001',
    'etablissement',
    'guide',
    'commercial',
    'guide-touristique',
    null,
    'da000000-0000-4000-8000-000000000010',
    'da000000-0000-4000-8000-000000000001',
    'brouillon',
    'Guide Detail Ouidah',
    'guide-detail-ouidah-test',
    'Guide touristique de test avec langues zones et specialites pour le contrat de lecture detaille.',
    'ouidah',
    'Centre ville',
    'Ouidah',
    6.3631,
    2.0851,
    12000,
    'par_personne',
    tests.valid_opening_hours(),
    '+2290100000002',
    null,
    '{"linkedin":"https://linkedin.com/in/kwabor-guide"}'::jsonb,
    array['guide', 'culture']
  ),
  (
    'da130000-0000-4000-8000-000000000001',
    'etablissement',
    'club',
    'commercial',
    'commercial-club',
    'da000000-0000-4000-8000-000000000001',
    null,
    'da000000-0000-4000-8000-000000000001',
    'brouillon',
    'Club Detail Cotonou',
    'club-detail-cotonou-test',
    'Club de test complet utilise pour verifier le contrat de lecture des etablissements nocturnes.',
    'cotonou',
    'Ganhi',
    'Boulevard de la Marina, Cotonou',
    6.3550,
    2.4250,
    5000,
    'par_entree',
    tests.valid_opening_hours(),
    '+2290100000003',
    null,
    '{}'::jsonb,
    array['club', 'test']
  ),
  (
    'da140000-0000-4000-8000-000000000001',
    'etablissement',
    'restaurant',
    'commercial',
    'commercial-restaurant',
    'da000000-0000-4000-8000-000000000001',
    null,
    'da000000-0000-4000-8000-000000000001',
    'brouillon',
    'Restaurant Detail Brouillon',
    'restaurant-detail-brouillon-test',
    'Restaurant brouillon utilise pour verifier que le RPC public reste strictement publication only.',
    'cotonou',
    'Cadjehoun',
    'Rue 10.101, Cotonou',
    6.3610,
    2.4010,
    4000,
    'par_personne',
    tests.valid_opening_hours(),
    '+2290100000004',
    null,
    '{}'::jsonb,
    array['restaurant', 'draft']
  );

insert into public.lodging_details (
  listing_id,
  star_rating,
  room_count,
  checkin_time,
  checkout_time
)
values (
  'da110000-0000-4000-8000-000000000001',
  4,
  24,
  '14:00:00',
  '11:00:00'
);

insert into public.room_types (listing_id, name, price_xof, display_order)
values
  ('da110000-0000-4000-8000-000000000001', 'Standard', 25000, 0),
  ('da110000-0000-4000-8000-000000000001', 'Suite', 40000, 1);

insert into public.guide_details (
  listing_id,
  languages,
  zones,
  specialties,
  indicative_price_xof,
  accreditation,
  experience_years
)
values (
  'da120000-0000-4000-8000-000000000001',
  array['francais', 'anglais'],
  array['Ouidah', 'Cotonou'],
  array['histoire', 'patrimoine'],
  12000,
  'Guide national',
  7
);

insert into public.nightlife_details (listing_id, venue_kind, min_age)
values ('da130000-0000-4000-8000-000000000001', 'club', 18);

insert into public.food_details (listing_id, cuisines, meals, reservation, menu_url)
values (
  'da140000-0000-4000-8000-000000000001',
  array['beninoise'],
  array['dejeuner'],
  false,
  'https://restaurant.example.invalid/menu'
);

insert into public.listing_amenities (listing_id, amenity_id, display_order)
values
  ('da110000-0000-4000-8000-000000000001', 'accessible-pmr', 0),
  ('da120000-0000-4000-8000-000000000001', 'local-expertise', 0),
  ('da130000-0000-4000-8000-000000000001', 'parking', 0);

insert into public.listing_media (
  listing_id,
  url,
  alt,
  display_order,
  is_cover,
  kind
)
values
  (
    'da110000-0000-4000-8000-000000000001',
    'https://media.example.invalid/hotel-cover.jpg',
    'Facade de hotel',
    0,
    true,
    'image'
  ),
  (
    'da110000-0000-4000-8000-000000000001',
    'https://media.example.invalid/hotel-tour.mp4',
    'Visite video de hotel',
    1,
    false,
    'video'
  ),
  (
    'da120000-0000-4000-8000-000000000001',
    'https://media.example.invalid/guide-cover.jpg',
    'Portrait du guide',
    0,
    true,
    'image'
  ),
  (
    'da130000-0000-4000-8000-000000000001',
    'https://media.example.invalid/club-cover.jpg',
    'Salle du club',
    0,
    true,
    'image'
  ),
  (
    'da140000-0000-4000-8000-000000000001',
    'https://media.example.invalid/restaurant-draft.jpg',
    'Salle du restaurant en brouillon',
    0,
    false,
    'image'
  );

update public.listings
set status = 'publie',
    published_at = '2026-08-02 00:00:00+00'
where id in (
  'da110000-0000-4000-8000-000000000001',
  'da120000-0000-4000-8000-000000000001',
  'da130000-0000-4000-8000-000000000001'
);

create temporary table catalog_detail_payloads (
  variant public.catalog_detail_variant primary key,
  payload jsonb not null
);

insert into catalog_detail_payloads (variant, payload)
values
  (
    'place',
    tests.catalog_payload_as('anon', null, '00000000-0000-4000-8000-000000000101')
  ),
  (
    'food',
    tests.catalog_payload_as('anon', null, '00000000-0000-4000-8000-000000000103')
  ),
  (
    'event',
    tests.catalog_payload_as('anon', null, '00000000-0000-4000-8000-000000000104')
  ),
  (
    'lodging',
    tests.catalog_payload_as('anon', null, 'da110000-0000-4000-8000-000000000001')
  ),
  (
    'guide',
    tests.catalog_payload_as('anon', null, 'da120000-0000-4000-8000-000000000001')
  ),
  (
    'nightlife',
    tests.catalog_payload_as('anon', null, 'da130000-0000-4000-8000-000000000001')
  );

select ok(
  to_regprocedure('public.get_catalog_detail_v1(uuid)') is not null,
  'catalog detail RPC exists'
);

select is(
  (
    select attribute.attgenerated
    from pg_catalog.pg_attribute attribute
    where attribute.attrelid = 'public.listings'::regclass
      and attribute.attname = 'is_claimable'
      and not attribute.attisdropped
  ),
  's'::"char",
  'listing claimability is a stored generated column'
);

select ok(
  (
    select procedure.proretset
    from pg_catalog.pg_proc procedure
    where procedure.oid = 'public.get_catalog_detail_v1(uuid)'::regprocedure
  ),
  'catalog detail RPC returns a row set'
);

select is(
  (
    select procedure.provolatile
    from pg_catalog.pg_proc procedure
    where procedure.oid = 'public.get_catalog_detail_v1(uuid)'::regprocedure
  ),
  's'::"char",
  'catalog detail RPC is STABLE'
);

select ok(
  not (
    select procedure.prosecdef
    from pg_catalog.pg_proc procedure
    where procedure.oid = 'public.get_catalog_detail_v1(uuid)'::regprocedure
  ),
  'catalog detail RPC is SECURITY INVOKER'
);

select is(
  (
    select array_to_string(procedure.proconfig, ',')
    from pg_catalog.pg_proc procedure
    where procedure.oid = 'public.get_catalog_detail_v1(uuid)'::regprocedure
  ),
  'search_path=""',
  'catalog detail RPC has an empty fixed search_path'
);

select ok(
  has_function_privilege('anon', 'public.get_catalog_detail_v1(uuid)', 'execute'),
  'anonymous callers can execute the catalog detail RPC'
);

select ok(
  has_function_privilege('authenticated', 'public.get_catalog_detail_v1(uuid)', 'execute'),
  'authenticated callers can execute the catalog detail RPC'
);

select ok(
  not has_function_privilege('service_role', 'public.get_catalog_detail_v1(uuid)', 'execute'),
  'service role has no direct execute grant on the public catalog detail RPC'
);

select ok(
  has_schema_privilege('authenticated', 'app_private', 'usage')
  and has_schema_privilege('service_role', 'app_private', 'usage')
  and not has_schema_privilege('anon', 'app_private', 'usage'),
  'only authenticated and trusted backend writers can resolve the private validator schema'
);

select ok(
  (
    select bool_and(
      has_function_privilege('authenticated', validator.signature, 'execute')
      and has_function_privilege('service_role', validator.signature, 'execute')
      and not has_function_privilege('anon', validator.signature, 'execute')
    )
    from unnest(array[
      'app_private.catalog_opening_hours_is_valid(jsonb)',
      'app_private.catalog_https_url_is_valid(text)',
      'app_private.catalog_socials_are_valid(jsonb)',
      'app_private.catalog_text_has_mobile_whitespace(text)',
      'app_private.catalog_text_has_canonical_edges(text)',
      'app_private.catalog_timestamp_is_mobile_safe(timestamptz)',
      'app_private.catalog_tags_are_valid(text[])',
      'app_private.catalog_text_array_is_valid(text[],boolean)',
      'app_private.catalog_point_is_within_benin(numeric,numeric)'
    ]) validator(signature)
  ),
  'all catalog validators have the intended role-scoped EXECUTE matrix'
);

select ok(
  (
    select bool_and(
      not procedure.prosecdef
      and coalesce('search_path=""' = any(procedure.proconfig), false)
    )
    from unnest(array[
      'app_private.catalog_opening_hours_is_valid(jsonb)'::regprocedure,
      'app_private.catalog_https_url_is_valid(text)'::regprocedure,
      'app_private.catalog_socials_are_valid(jsonb)'::regprocedure,
      'app_private.catalog_text_has_mobile_whitespace(text)'::regprocedure,
      'app_private.catalog_text_has_canonical_edges(text)'::regprocedure,
      'app_private.catalog_timestamp_is_mobile_safe(timestamptz)'::regprocedure,
      'app_private.catalog_tags_are_valid(text[])'::regprocedure,
      'app_private.catalog_text_array_is_valid(text[],boolean)'::regprocedure,
      'app_private.catalog_point_is_within_benin(numeric,numeric)'::regprocedure
    ]) validator(function_oid)
    join pg_catalog.pg_proc procedure on procedure.oid = validator.function_oid
  ),
  'catalog validators remain SECURITY INVOKER with an empty fixed search_path'
);

select is(
  tests.affected_rows_as(
    'service_role',
    null,
    $$
      update public.listings
      set name = name
      where id = 'da140000-0000-4000-8000-000000000001'
    $$
  ),
  1::bigint,
  'the trusted backend can execute catalog CHECK validators during a listing write'
);

select ok(
  has_function_privilege(
    'anon',
    'public.list_catalog_summaries(text,text,text,text,text,text,integer)',
    'execute'
  )
  and has_function_privilege(
    'authenticated',
    'public.list_catalog_summaries(text,text,text,text,text,text,integer)',
    'execute'
  )
  and not has_function_privilege(
    'service_role',
    'public.list_catalog_summaries(text,text,text,text,text,text,integer)',
    'execute'
  ),
  'catalog summary RPC execution remains limited to public client roles'
);

select is(
  (
    select count(*)::integer
    from pg_catalog.pg_class relation
    join pg_catalog.pg_namespace namespace on namespace.oid = relation.relnamespace
    where namespace.nspname = 'public'
      and relation.relname in (
        'amenities',
        'place_details',
        'lodging_details',
        'room_types',
        'food_details',
        'nightlife_details',
        'guide_details',
        'ticket_tiers',
        'listing_amenities'
      )
      and relation.relrowsecurity
  ),
  9,
  'all catalog detail tables have RLS enabled'
);

select ok(
  not has_table_privilege('anon', 'public.listings', 'select'),
  'anonymous callers have no table-wide SELECT on listings'
);

select ok(
  not has_table_privilege('authenticated', 'public.listings', 'select'),
  'authenticated callers have no table-wide SELECT on listings'
);

select ok(
  not has_table_privilege('anon', 'public.listing_media', 'select'),
  'anonymous callers have no table-wide SELECT on listing media'
);

select ok(
  not has_table_privilege('authenticated', 'public.listing_media', 'select'),
  'authenticated callers have no table-wide SELECT on listing media'
);

select ok(
  not exists (
    select 1
    from unnest(array[
      'public.event_details',
      'public.amenities',
      'public.place_details',
      'public.lodging_details',
      'public.room_types',
      'public.food_details',
      'public.nightlife_details',
      'public.guide_details',
      'public.ticket_tiers',
      'public.listing_amenities'
    ]) as relation(relation_name)
    where has_table_privilege('anon', relation_name, 'select')
  ),
  'anonymous callers have no table-wide SELECT on detail relations'
);

select ok(
  not exists (
    select 1
    from unnest(array[
      'public.event_details',
      'public.amenities',
      'public.place_details',
      'public.lodging_details',
      'public.room_types',
      'public.food_details',
      'public.nightlife_details',
      'public.guide_details',
      'public.ticket_tiers',
      'public.listing_amenities'
    ]) as relation(relation_name)
    where has_table_privilege('authenticated', relation_name, 'select')
  ),
  'authenticated callers have no table-wide SELECT on detail relations'
);

select ok(
  not has_column_privilege('anon', 'public.listings', 'owner_id', 'select')
  and not has_column_privilege('anon', 'public.listings', 'steward_id', 'select')
  and not has_column_privilege('anon', 'public.listings', 'submitted_by', 'select')
  and not has_column_privilege('anon', 'public.listings', 'organization_id', 'select'),
  'anonymous callers cannot read listing authority UUIDs'
);

select ok(
  not has_column_privilege('authenticated', 'public.listings', 'owner_id', 'select')
  and not has_column_privilege('authenticated', 'public.listings', 'steward_id', 'select')
  and not has_column_privilege('authenticated', 'public.listings', 'submitted_by', 'select')
  and not has_column_privilege('authenticated', 'public.listings', 'organization_id', 'select'),
  'authenticated callers cannot read listing authority UUIDs'
);

select ok(
  has_column_privilege('anon', 'public.listings', 'id', 'select')
  and has_column_privilege('anon', 'public.listings', 'name', 'select')
  and has_column_privilege('anon', 'public.listings', 'published_at', 'select')
  and has_column_privilege('anon', 'public.listings', 'is_claimable', 'select')
  and not has_column_privilege('anon', 'public.listings', 'created_at', 'select')
  and not has_column_privilege('anon', 'public.listings', 'updated_at', 'select')
  and not has_column_privilege('anon', 'public.listings', 'geog', 'select')
  and not has_column_privilege('anon', 'public.listings', 'google_place_id', 'select')
  and not has_column_privilege('anon', 'public.listings', 'editorial_pin_until', 'select'),
  'anonymous listing grants expose public fields and hide technical timestamps'
);

select ok(
  has_column_privilege('authenticated', 'public.listings', 'id', 'select')
  and has_column_privilege('authenticated', 'public.listings', 'name', 'select')
  and has_column_privilege('authenticated', 'public.listings', 'published_at', 'select')
  and has_column_privilege('authenticated', 'public.listings', 'is_claimable', 'select')
  and not has_column_privilege('authenticated', 'public.listings', 'created_at', 'select')
  and not has_column_privilege('authenticated', 'public.listings', 'updated_at', 'select')
  and not has_column_privilege('authenticated', 'public.listings', 'geog', 'select')
  and not has_column_privilege('authenticated', 'public.listings', 'google_place_id', 'select')
  and not has_column_privilege('authenticated', 'public.listings', 'editorial_pin_until', 'select'),
  'authenticated listing grants expose public fields and hide technical timestamps'
);

select ok(
  not has_column_privilege('anon', 'public.listing_media', 'id', 'select')
  and not has_column_privilege('anon', 'public.listing_media', 'storage_path', 'select')
  and not has_column_privilege('anon', 'public.listing_media', 'created_at', 'select'),
  'anonymous callers cannot read media storage or technical columns'
);

select ok(
  not has_column_privilege('authenticated', 'public.listing_media', 'id', 'select')
  and not has_column_privilege('authenticated', 'public.listing_media', 'storage_path', 'select')
  and not has_column_privilege('authenticated', 'public.listing_media', 'created_at', 'select'),
  'authenticated callers cannot read media storage or technical columns'
);

select ok(
  has_column_privilege('anon', 'public.listing_media', 'listing_id', 'select')
  and has_column_privilege('anon', 'public.listing_media', 'url', 'select')
  and has_column_privilege('anon', 'public.listing_media', 'alt', 'select')
  and has_column_privilege('anon', 'public.listing_media', 'display_order', 'select')
  and has_column_privilege('anon', 'public.listing_media', 'is_cover', 'select')
  and has_column_privilege('anon', 'public.listing_media', 'kind', 'select'),
  'anonymous callers retain the safe media projection columns'
);

select ok(
  has_column_privilege('authenticated', 'public.listing_media', 'listing_id', 'select')
  and has_column_privilege('authenticated', 'public.listing_media', 'url', 'select')
  and has_column_privilege('authenticated', 'public.listing_media', 'alt', 'select')
  and has_column_privilege('authenticated', 'public.listing_media', 'display_order', 'select')
  and has_column_privilege('authenticated', 'public.listing_media', 'is_cover', 'select')
  and has_column_privilege('authenticated', 'public.listing_media', 'kind', 'select'),
  'authenticated callers retain the safe media projection columns'
);

select ok(
  not has_column_privilege('anon', 'public.event_details', 'created_at', 'select')
  and not has_column_privilege('anon', 'public.event_details', 'updated_at', 'select')
  and not has_column_privilege('anon', 'public.amenities', 'created_at', 'select')
  and not has_column_privilege('anon', 'public.place_details', 'created_at', 'select')
  and not has_column_privilege('anon', 'public.place_details', 'updated_at', 'select')
  and not has_column_privilege('anon', 'public.lodging_details', 'created_at', 'select')
  and not has_column_privilege('anon', 'public.lodging_details', 'updated_at', 'select')
  and not has_column_privilege('anon', 'public.room_types', 'id', 'select')
  and not has_column_privilege('anon', 'public.room_types', 'created_at', 'select')
  and not has_column_privilege('anon', 'public.room_types', 'updated_at', 'select')
  and not has_column_privilege('anon', 'public.food_details', 'created_at', 'select')
  and not has_column_privilege('anon', 'public.food_details', 'updated_at', 'select')
  and not has_column_privilege('anon', 'public.nightlife_details', 'created_at', 'select')
  and not has_column_privilege('anon', 'public.nightlife_details', 'updated_at', 'select')
  and not has_column_privilege('anon', 'public.guide_details', 'created_at', 'select')
  and not has_column_privilege('anon', 'public.guide_details', 'updated_at', 'select')
  and not has_column_privilege('anon', 'public.ticket_tiers', 'id', 'select')
  and not has_column_privilege('anon', 'public.ticket_tiers', 'created_at', 'select')
  and not has_column_privilege('anon', 'public.ticket_tiers', 'updated_at', 'select')
  and not has_column_privilege('anon', 'public.listing_amenities', 'created_at', 'select'),
  'anonymous callers cannot read detail technical IDs or timestamps'
);

select ok(
  not has_column_privilege('authenticated', 'public.event_details', 'created_at', 'select')
  and not has_column_privilege('authenticated', 'public.event_details', 'updated_at', 'select')
  and not has_column_privilege('authenticated', 'public.amenities', 'created_at', 'select')
  and not has_column_privilege('authenticated', 'public.place_details', 'created_at', 'select')
  and not has_column_privilege('authenticated', 'public.place_details', 'updated_at', 'select')
  and not has_column_privilege('authenticated', 'public.lodging_details', 'created_at', 'select')
  and not has_column_privilege('authenticated', 'public.lodging_details', 'updated_at', 'select')
  and not has_column_privilege('authenticated', 'public.room_types', 'id', 'select')
  and not has_column_privilege('authenticated', 'public.room_types', 'created_at', 'select')
  and not has_column_privilege('authenticated', 'public.room_types', 'updated_at', 'select')
  and not has_column_privilege('authenticated', 'public.food_details', 'created_at', 'select')
  and not has_column_privilege('authenticated', 'public.food_details', 'updated_at', 'select')
  and not has_column_privilege('authenticated', 'public.nightlife_details', 'created_at', 'select')
  and not has_column_privilege('authenticated', 'public.nightlife_details', 'updated_at', 'select')
  and not has_column_privilege('authenticated', 'public.guide_details', 'created_at', 'select')
  and not has_column_privilege('authenticated', 'public.guide_details', 'updated_at', 'select')
  and not has_column_privilege('authenticated', 'public.ticket_tiers', 'id', 'select')
  and not has_column_privilege('authenticated', 'public.ticket_tiers', 'created_at', 'select')
  and not has_column_privilege('authenticated', 'public.ticket_tiers', 'updated_at', 'select')
  and not has_column_privilege('authenticated', 'public.listing_amenities', 'created_at', 'select'),
  'authenticated callers cannot read detail technical IDs or timestamps'
);

select ok(
  has_table_privilege('service_role', 'public.listings', 'select')
  and has_table_privilege('service_role', 'public.listings', 'insert')
  and has_table_privilege('service_role', 'public.listings', 'update')
  and has_table_privilege('service_role', 'public.listings', 'delete')
  and has_table_privilege('service_role', 'public.listing_media', 'select')
  and has_table_privilege('service_role', 'public.listing_media', 'insert')
  and has_table_privilege('service_role', 'public.listing_media', 'update')
  and has_table_privilege('service_role', 'public.listing_media', 'delete')
  and has_column_privilege('service_role', 'public.room_types', 'id', 'select')
  and has_column_privilege('service_role', 'public.ticket_tiers', 'created_at', 'select'),
  'service role retains required catalog DML and internal detail reads'
);

select is(
  tests.count_as(
    'authenticated',
    'da000000-0000-4000-8000-000000000002',
    $$select * from public.get_catalog_detail_v1('00000000-0000-4000-8000-000000000101')$$
  ),
  1::bigint,
  'authenticated callers still execute the published detail RPC through reduced grants'
);

select ok(
  tests.count_as(
    'anon',
    null,
    $$select * from public.list_catalog_summaries(p_limit => 1)$$
  ) >= 1,
  'anonymous callers still execute the summary RPC through reduced grants'
);

select ok(
  tests.count_as(
    'authenticated',
    'da000000-0000-4000-8000-000000000002',
    $$select * from public.list_catalog_summaries(p_limit => 1)$$
  ) >= 1,
  'authenticated callers still execute the summary RPC through reduced grants'
);

select is(
  tests.count_as(
    'anon',
    null,
    $$select * from public.get_catalog_detail_v1('00000000-0000-4000-8000-000000000101')$$
  ),
  1::bigint,
  'anonymous callers read a published place detail'
);

select is(
  tests.count_as(
    'anon',
    null,
    $$select * from public.get_catalog_detail_v1('00000000-0000-4000-8000-000000000103')$$
  ),
  1::bigint,
  'anonymous callers read a published food detail'
);

select is(
  tests.count_as(
    'anon',
    null,
    $$select * from public.get_catalog_detail_v1('00000000-0000-4000-8000-000000000104')$$
  ),
  1::bigint,
  'anonymous callers read a published event detail'
);

select is(
  tests.count_as(
    'anon',
    null,
    $$select * from public.get_catalog_detail_v1('da110000-0000-4000-8000-000000000001')$$
  ),
  1::bigint,
  'anonymous callers read a published lodging detail'
);

select is(
  tests.count_as(
    'anon',
    null,
    $$select * from public.get_catalog_detail_v1('da120000-0000-4000-8000-000000000001')$$
  ),
  1::bigint,
  'anonymous callers read a published guide detail'
);

select is(
  tests.count_as(
    'anon',
    null,
    $$select * from public.get_catalog_detail_v1('da130000-0000-4000-8000-000000000001')$$
  ),
  1::bigint,
  'anonymous callers read a published nightlife detail'
);

select is(
  tests.count_as(
    'anon',
    null,
    $$select * from public.get_catalog_detail_v1('da990000-0000-4000-8000-000000000001')$$
  ),
  0::bigint,
  'a missing listing returns zero rows'
);

select is(
  tests.count_as(
    'anon',
    null,
    $$select * from public.get_catalog_detail_v1('da140000-0000-4000-8000-000000000001')$$
  ),
  0::bigint,
  'a draft listing returns zero rows to anonymous callers'
);

select is(
  tests.count_as(
    'authenticated',
    'da000000-0000-4000-8000-000000000001',
    $$select * from public.get_catalog_detail_v1('da140000-0000-4000-8000-000000000001')$$
  ),
  0::bigint,
  'a draft listing returns zero rows even to its authenticated owner'
);

select is(
  tests.count_as(
    'authenticated',
    'da000000-0000-4000-8000-000000000002',
    $$select * from public.get_catalog_detail_v1('da140000-0000-4000-8000-000000000001')$$
  ),
  0::bigint,
  'a draft listing returns zero rows to another authenticated caller'
);

insert into public.cities (id, name, slug, enabled)
values (
  'detail-disabled-venue-city',
  'Disabled Venue City',
  'detail-disabled-venue-city',
  false
);

update public.listings
set city_id = 'detail-disabled-venue-city'
where id = '00000000-0000-4000-8000-000000000101';

select is(
  tests.count_as(
    'anon',
    null,
    $$select * from public.get_catalog_detail_v1('00000000-0000-4000-8000-000000000104')$$
  ),
  0::bigint,
  'the RPC fails closed when an event venue city is not publicly readable'
);

update public.listings
set city_id = 'ouidah'
where id = '00000000-0000-4000-8000-000000000101';

select results_eq(
  'select variant::text from catalog_detail_payloads order by variant::text',
  $$values ('event'), ('food'), ('guide'), ('lodging'), ('nightlife'), ('place')$$,
  'the public projection covers exactly the six detail variants'
);

select ok(
  (select bool_and((payload ->> 'schema_version')::integer = 1) from catalog_detail_payloads),
  'every payload carries schema version one'
);

select ok(
  (
    select bool_and(
      tests.sorted_jsonb_keys(payload) = array[
        'amenities',
        'category',
        'city',
        'contact',
        'content_lang',
        'description',
        'detail',
        'id',
        'is_claimable',
        'listing_class',
        'location',
        'media',
        'metrics',
        'name',
        'opening_hours',
        'price',
        'published_at',
        'schema_version',
        'slug',
        'socials',
        'subtype',
        'tags',
        'type',
        'verified'
      ]::text[]
    )
    from catalog_detail_payloads
  ),
  'every payload exposes the exact version-one root keys'
);

select ok(
  (select bool_and(tests.sorted_jsonb_keys(payload -> 'city') = array['id', 'name']) from catalog_detail_payloads),
  'city objects expose only their public identity'
);

select ok(
  (select bool_and(tests.sorted_jsonb_keys(payload -> 'category') = array['id', 'label_key']) from catalog_detail_payloads),
  'category objects expose only id and localization key'
);

select ok(
  (
    select bool_and(
      tests.sorted_jsonb_keys(payload -> 'location') = array['address', 'district', 'latitude', 'longitude']
    )
    from catalog_detail_payloads
  ),
  'location objects follow the exact public contract'
);

select ok(
  (select bool_and(tests.sorted_jsonb_keys(payload -> 'price') = array['from_xof', 'tier', 'unit']) from catalog_detail_payloads),
  'price objects follow the exact XOF contract'
);

select ok(
  (
    select bool_and(
      tests.sorted_jsonb_keys(payload -> 'contact') = array['email', 'external_url', 'phone', 'whatsapp']
    )
    from catalog_detail_payloads
  ),
  'contact objects follow the exact public contract'
);

select ok(
  (
    select bool_and(
      tests.sorted_jsonb_keys(payload -> 'metrics') = array[
        'likes_count', 'rating_average', 'rating_count', 'views_count'
      ]
    )
    from catalog_detail_payloads
  ),
  'metrics expose only aggregate public counters'
);

select ok(
  not exists (
    select 1
    from catalog_detail_payloads payload_row
    cross join lateral jsonb_array_elements(payload_row.payload -> 'media') media
    where tests.sorted_jsonb_keys(media) <> array['alt', 'display_order', 'is_cover', 'kind', 'url']
  ),
  'media objects never expose storage paths or child identifiers'
);

select ok(
  not exists (
    select 1
    from catalog_detail_payloads payload_row
    cross join lateral jsonb_array_elements(payload_row.payload -> 'amenities') amenity
    where tests.sorted_jsonb_keys(amenity) <> array['display_order', 'id', 'label_key']
  ),
  'amenity objects expose only reference data and display order'
);

select ok(
  not exists (
    select 1
    from catalog_detail_payloads
    where tests.jsonb_has_any_key(
      payload,
      array[
        'owner_id',
        'steward_id',
        'submitted_by',
        'organization_id',
        'created_at',
        'updated_at',
        'storage_path',
        'google_place_id',
        'sponsored_until',
        'editorial_pin_until',
        'social_posts',
        'social_media',
        'reviews',
        'review_replies',
        'author_id',
        'moderation_status',
        'caption',
        'ugc'
      ]::text[]
    )
  ),
  'authority and UGC keys are absent recursively from every payload'
);

select ok(
  (select bool_and(not (payload ? 'status')) from catalog_detail_payloads),
  'listing workflow status is absent from every payload root'
);

select is(
  (select payload ->> 'is_claimable' from catalog_detail_payloads where variant = 'place'),
  'false',
  'a patrimonial listing is never claimable'
);

select is(
  tests.catalog_payload_as(
    'anon',
    null,
    '00000000-0000-4000-8000-000000000102'
  ) ->> 'is_claimable',
  'true',
  'an unowned commercial listing is claimable'
);

select is(
  (select payload ->> 'is_claimable' from catalog_detail_payloads where variant = 'lodging'),
  'false',
  'a listing with a private owner is not claimable'
);

select is(
  (select payload ->> 'is_claimable' from catalog_detail_payloads where variant = 'guide'),
  'false',
  'a listing assigned to an organization is not claimable without exposing its identifier'
);

select is(
  tests.sorted_jsonb_keys((select payload -> 'detail' from catalog_detail_payloads where variant = 'place')),
  array['entry_fee_xof', 'fee_note', 'is_free', 'place_category', 'variant']::text[],
  'place detail uses its exact discriminated shape'
);

select is(
  tests.sorted_jsonb_keys((select payload -> 'detail' from catalog_detail_payloads where variant = 'lodging')),
  array['checkin_time', 'checkout_time', 'room_count', 'room_types', 'star_rating', 'variant']::text[],
  'lodging detail uses its exact discriminated shape'
);

select is(
  tests.sorted_jsonb_keys((select payload -> 'detail' from catalog_detail_payloads where variant = 'food')),
  array['cuisines', 'meals', 'menu_url', 'reservation', 'variant']::text[],
  'food detail uses its exact discriminated shape'
);

select is(
  tests.sorted_jsonb_keys((select payload -> 'detail' from catalog_detail_payloads where variant = 'nightlife')),
  array['min_age', 'variant', 'venue_kind']::text[],
  'nightlife detail uses its exact discriminated shape'
);

select is(
  tests.sorted_jsonb_keys((select payload -> 'detail' from catalog_detail_payloads where variant = 'guide')),
  array[
    'accreditation',
    'experience_years',
    'indicative_price_xof',
    'languages',
    'specialties',
    'variant',
    'zones'
  ]::text[],
  'guide detail uses its exact discriminated shape'
);

select is(
  tests.sorted_jsonb_keys((select payload -> 'detail' from catalog_detail_payloads where variant = 'event')),
  array['capacity', 'category', 'end_at', 'organizer', 'start_at', 'ticketing', 'variant', 'venue_listing']::text[],
  'event detail uses its exact discriminated shape'
);

select ok(
  not exists (
    select 1
    from jsonb_array_elements(
      (select payload #> '{detail,room_types}' from catalog_detail_payloads where variant = 'lodging')
    ) room
    where tests.sorted_jsonb_keys(room) <> array['display_order', 'name', 'price_xof']
  ),
  'room types omit child identifiers and timestamps'
);

select ok(
  not exists (
    select 1
    from jsonb_array_elements(
      (select payload #> '{detail,ticketing,tiers}' from catalog_detail_payloads where variant = 'event')
    ) tier
    where tests.sorted_jsonb_keys(tier) <> array['display_order', 'label', 'price_xof']
  ),
  'ticket tiers omit child identifiers and timestamps'
);

select is(
  tests.sorted_jsonb_keys(
    (select payload #> '{detail,ticketing}' from catalog_detail_payloads where variant = 'event')
  ),
  array['tiers', 'type', 'url']::text[],
  'event ticketing uses its exact nested shape'
);

select is(
  tests.sorted_jsonb_keys(
    (select payload #> '{detail,organizer}' from catalog_detail_payloads where variant = 'event')
  ),
  array['contact', 'name']::text[],
  'event organizer uses its exact nested shape'
);

select is(
  tests.sorted_jsonb_keys(
    (select payload #> '{detail,venue_listing}' from catalog_detail_payloads where variant = 'event')
  ),
  array['address', 'city', 'id', 'latitude', 'longitude', 'name', 'subtype', 'type']::text[],
  'event venue listing uses its exact public summary shape'
);

select is(
  tests.sorted_jsonb_keys(
    (select payload #> '{detail,venue_listing,city}' from catalog_detail_payloads where variant = 'event')
  ),
  array['id', 'name']::text[],
  'event venue city uses its exact public identity shape'
);

select is(
  (select payload #>> '{detail,venue_listing,id}' from catalog_detail_payloads where variant = 'event'),
  '00000000-0000-4000-8000-000000000101',
  'event venue references the intended published place without authority data'
);

select is(
  (select payload #>> '{media,0,kind}' from catalog_detail_payloads where variant = 'lodging'),
  'image',
  'official media are ordered with the cover first'
);

select is(
  (select payload #>> '{media,1,kind}' from catalog_detail_payloads where variant = 'lodging'),
  'video',
  'official non-cover video metadata follows display order'
);

select is(
  (select payload #>> '{amenities,0,id}' from catalog_detail_payloads where variant = 'food'),
  'wifi',
  'amenities are ordered by display order'
);

select is(
  (select payload #>> '{detail,room_types,0,name}' from catalog_detail_payloads where variant = 'lodging'),
  'Standard',
  'room types are ordered by display order'
);

select is(
  (select payload #>> '{detail,ticketing,tiers,0,label}' from catalog_detail_payloads where variant = 'event'),
  'Standard',
  'ticket tiers are ordered by display order'
);

select ok(
  not app_private.catalog_tags_are_valid(array['culture', 'culture']),
  'listing tags reject case-sensitive duplicates'
);

select ok(
  not app_private.catalog_text_array_is_valid(array['francais', 'francais'], false),
  'typed catalog arrays reject case-sensitive duplicates'
);

select ok(
  app_private.catalog_text_array_is_valid(
    array(select 'valeur-' || item::text from generate_series(1, 20) item),
    false
  )
  and app_private.catalog_text_array_is_valid(array[repeat('🐕', 80)], false)
  and not app_private.catalog_text_array_is_valid(
    array(select 'valeur-' || item::text from generate_series(1, 21) item),
    false
  )
  and not app_private.catalog_text_array_is_valid(array[repeat('🐕', 81)], false)
  and not app_private.catalog_text_array_is_valid(array[E'fran\nçais'], false),
  'typed catalog arrays enforce 20 values, 80 Unicode characters and no controls'
);

select ok(
  app_private.catalog_text_has_canonical_edges('Texte interieur valide')
  and not app_private.catalog_text_has_canonical_edges(E'\tTexte invalide')
  and not app_private.catalog_text_has_canonical_edges(E'Texte invalide\n'),
  'catalog text canonicalization rejects non-space edge whitespace recognized by Kotlin trim'
);

select ok(
  not app_private.catalog_text_has_canonical_edges(U&'\00A0Texte invalide')
  and not app_private.catalog_text_has_canonical_edges(U&'\2003Texte invalide')
  and not app_private.catalog_text_has_canonical_edges(U&'Texte invalide\202F')
  and app_private.catalog_text_has_canonical_edges(U&'Texte\00A0interieur'),
  'catalog text canonicalization matches Kotlin Unicode whitespace at edges only'
);

select ok(
  not app_private.catalog_tags_are_valid(array[['culture', 'patrimoine']]::text[]),
  'listing tags reject multidimensional arrays'
);

select ok(
  not app_private.catalog_text_array_is_valid(array[['francais', 'anglais']]::text[], false),
  'typed catalog text values reject multidimensional arrays'
);

select throws_ok(
  $sql$
    update public.listings
    set name = E'\tRestaurant Detail Brouillon'
    where id = 'da140000-0000-4000-8000-000000000001'
  $sql$,
  '23514',
  'new row for relation "listings" violates check constraint "listings_v1_text_canonical"',
  'projected listing names reject leading tabulation'
);

select throws_ok(
  $sql$
    update public.listings
    set name = ' Restaurant Detail Brouillon'
    where id = 'da140000-0000-4000-8000-000000000001'
  $sql$,
  '23514',
  'new row for relation "listings" violates check constraint "listings_v1_text_canonical"',
  'projected listing names must be canonically trimmed'
);

select throws_ok(
  $sql$
    update public.listings
    set slug = 'restaurant-detail-brouillon-test '
    where id = 'da140000-0000-4000-8000-000000000001'
  $sql$,
  '23514',
  'new row for relation "listings" violates check constraint "listings_v1_text_canonical"',
  'projected listing slugs must be canonically trimmed'
);

select throws_ok(
  $sql$
    update public.listings
    set description = description || ' '
    where id = 'da140000-0000-4000-8000-000000000001'
  $sql$,
  '23514',
  'new row for relation "listings" violates check constraint "listings_v1_text_canonical"',
  'projected listing descriptions must be canonically trimmed'
);

select throws_ok(
  $sql$
    update public.listings
    set slug = 'Invalid_Slug'
    where id = 'da140000-0000-4000-8000-000000000001'
  $sql$,
  '23514',
  'new row for relation "listings" violates check constraint "listings_v1_text_canonical"',
  'projected listing slugs must use the canonical ASCII slug format'
);

select throws_ok(
  $sql$
    update public.listings
    set district = ' Cadjehoun'
    where id = 'da140000-0000-4000-8000-000000000001'
  $sql$,
  '23514',
  'new row for relation "listings" violates check constraint "listings_v1_text_canonical"',
  'projected listing districts must be canonically trimmed'
);

select throws_ok(
  $sql$
    update public.listings
    set address = 'Rue 10.101, Cotonou '
    where id = 'da140000-0000-4000-8000-000000000001'
  $sql$,
  '23514',
  'new row for relation "listings" violates check constraint "listings_v1_text_canonical"',
  'projected listing addresses must be canonically trimmed'
);

select throws_ok(
  $sql$
    update public.cities
    set name = ' Cotonou'
    where id = 'cotonou'
  $sql$,
  '23514',
  'new row for relation "cities" violates check constraint "cities_v1_name_canonical"',
  'projected city names must be canonically trimmed'
);

select throws_ok(
  $sql$
    update public.categories
    set name_key = ' category.commercial.restaurant'
    where id = 'commercial-restaurant'
  $sql$,
  '23514',
  'new row for relation "categories" violates check constraint "categories_v1_name_key_canonical"',
  'projected category label keys must be canonically trimmed'
);

select throws_ok(
  $sql$
    insert into public.cities (id, name, slug)
    values ('detail-invalid-city ', 'Invalid City', 'detail-invalid-city')
  $sql$,
  '23514',
  'new row for relation "cities" violates check constraint "cities_v1_id_canonical"',
  'projected city identifiers must use the canonical ASCII slug format'
);

select throws_ok(
  $sql$
    insert into public.categories (
      id,
      listing_type,
      subtype,
      name_key,
      default_listing_class,
      detail_variant,
      sort_order
    ) values (
      'detail-invalid-category ',
      'etablissement',
      'detail-invalid-category-id',
      'category.detail.invalid_id',
      'commercial',
      'food',
      901
    )
  $sql$,
  '23514',
  'new row for relation "categories" violates check constraint "categories_v1_id_canonical"',
  'projected category identifiers must use the canonical ASCII slug format'
);

select throws_ok(
  $sql$
    insert into public.categories (
      id,
      listing_type,
      subtype,
      name_key,
      default_listing_class,
      detail_variant,
      sort_order
    ) values (
      'detail-invalid-subtype',
      'etablissement',
      'invalid subtype',
      'category.detail.invalid_subtype',
      'commercial',
      'food',
      902
    )
  $sql$,
  '23514',
  'new row for relation "categories" violates check constraint "categories_v1_subtype_canonical"',
  'projected category subtypes must use the canonical ASCII slug format'
);

select throws_ok(
  $sql$
    insert into public.categories (
      id,
      listing_type,
      subtype,
      name_key,
      default_listing_class,
      detail_variant,
      sort_order
    ) values (
      'detail-invalid-event-class',
      'evenement',
      'invalid-event-class',
      'category.detail.invalid_event_class',
      'commercial',
      'event',
      903
    )
  $sql$,
  '23514',
  'new row for relation "categories" violates check constraint "categories_detail_variant_matches_type"',
  'category listing classes must remain compatible with their detail family'
);

select throws_ok(
  $sql$
    insert into public.amenities (id, name_key, allowed_variants)
    values (
      'detail-invalid-variant',
      'amenity.detail.invalid_variant',
      array[null]::public.catalog_detail_variant[]
    )
  $sql$,
  '23514',
  'new row for relation "amenities" violates check constraint "amenities_variants_not_empty"',
  'amenity variant vocabularies reject null entries'
);

select throws_ok(
  $sql$
    update public.amenities
    set allowed_variants = array['place']::public.catalog_detail_variant[]
    where id = 'wifi'
  $sql$,
  '23514',
  'Amenity variants cannot exclude an existing listing link',
  'amenity variant changes preserve existing compatible links'
);

select throws_ok(
  $sql$
    update public.listing_media
    set alt = ' Facade de hotel'
    where listing_id = 'da110000-0000-4000-8000-000000000001'
      and display_order = 0
  $sql$,
  '23514',
  'new row for relation "listing_media" violates check constraint "listing_media_alt_canonical"',
  'projected media alternative text must be canonically trimmed'
);

select throws_ok(
  $sql$
    update public.amenities
    set name_key = E'\tamenity.wifi'
    where id = 'wifi'
  $sql$,
  '23514',
  'new row for relation "amenities" violates check constraint "amenities_name_key_valid"',
  'projected amenity labels reject leading tabulation'
);

select throws_ok(
  $sql$
    update public.place_details
    set place_category = E'patrimoine\n'
    where listing_id = '00000000-0000-4000-8000-000000000101'
  $sql$,
  '23514',
  'new row for relation "place_details" violates check constraint "place_details_category_not_blank"',
  'projected place categories reject trailing line breaks'
);

select throws_ok(
  $sql$
    update public.place_details
    set fee_note = E'\tTarif adulte'
    where listing_id = '00000000-0000-4000-8000-000000000101'
  $sql$,
  '23514',
  'new row for relation "place_details" violates check constraint "place_details_fee_note_valid"',
  'projected place fee notes reject leading tabulation'
);

select throws_ok(
  $sql$
    update public.room_types
    set name = E'\tStandard'
    where listing_id = 'da110000-0000-4000-8000-000000000001'
      and display_order = 0
  $sql$,
  '23514',
  'new row for relation "room_types" violates check constraint "room_types_name_valid"',
  'projected room names reject leading tabulation'
);

select lives_ok(
  $sql$
    update public.room_types
    set name = repeat('🐕', 80)
    where listing_id = 'da110000-0000-4000-8000-000000000001'
      and display_order = 0
  $sql$,
  'projected room names accept exactly 80 Unicode characters'
);

update public.room_types
set name = 'Standard'
where listing_id = 'da110000-0000-4000-8000-000000000001'
  and display_order = 0;

select throws_ok(
  $sql$
    update public.room_types
    set name = repeat('🐕', 81)
    where listing_id = 'da110000-0000-4000-8000-000000000001'
      and display_order = 0
  $sql$,
  '23514',
  'new row for relation "room_types" violates check constraint "room_types_name_valid"',
  'projected room names reject more than 80 Unicode characters'
);

select throws_ok(
  $sql$
    update public.room_types
    set name = E'Suite\nVIP'
    where listing_id = 'da110000-0000-4000-8000-000000000001'
      and display_order = 0
  $sql$,
  '23514',
  'new row for relation "room_types" violates check constraint "room_types_name_valid"',
  'projected room names reject internal controls'
);

select throws_ok(
  $sql$
    update public.room_types
    set display_order = 20
    where listing_id = 'da110000-0000-4000-8000-000000000001'
      and display_order = 0
  $sql$,
  '23514',
  'new row for relation "room_types" violates check constraint "room_types_display_order_range"',
  'room types are limited to twenty distinct display positions'
);

select throws_ok(
  $sql$
    update public.nightlife_details
    set venue_kind = E'club\n'
    where listing_id = 'da130000-0000-4000-8000-000000000001'
  $sql$,
  '23514',
  'new row for relation "nightlife_details" violates check constraint "nightlife_details_venue_kind_valid"',
  'projected nightlife kinds reject trailing line breaks'
);

select throws_ok(
  $sql$
    update public.guide_details
    set accreditation = E'\tGuide national'
    where listing_id = 'da120000-0000-4000-8000-000000000001'
  $sql$,
  '23514',
  'new row for relation "guide_details" violates check constraint "guide_details_accreditation_valid"',
  'projected guide accreditation rejects leading tabulation'
);

select throws_ok(
  $sql$
    update public.ticket_tiers
    set label = E'Standard\n'
    where listing_id = '00000000-0000-4000-8000-000000000104'
      and display_order = 0
  $sql$,
  '23514',
  'new row for relation "ticket_tiers" violates check constraint "ticket_tiers_label_valid"',
  'projected ticket labels reject trailing line breaks'
);

select lives_ok(
  $sql$
    update public.ticket_tiers
    set label = repeat('🐕', 80)
    where listing_id = '00000000-0000-4000-8000-000000000104'
      and display_order = 0
  $sql$,
  'projected ticket labels accept exactly 80 Unicode characters'
);

update public.ticket_tiers
set label = 'Standard'
where listing_id = '00000000-0000-4000-8000-000000000104'
  and display_order = 0;

select throws_ok(
  $sql$
    update public.ticket_tiers
    set label = repeat('🐕', 81)
    where listing_id = '00000000-0000-4000-8000-000000000104'
      and display_order = 0
  $sql$,
  '23514',
  'new row for relation "ticket_tiers" violates check constraint "ticket_tiers_label_valid"',
  'projected ticket labels reject more than 80 Unicode characters'
);

select throws_ok(
  $sql$
    update public.ticket_tiers
    set label = E'Standard\nVIP'
    where listing_id = '00000000-0000-4000-8000-000000000104'
      and display_order = 0
  $sql$,
  '23514',
  'new row for relation "ticket_tiers" violates check constraint "ticket_tiers_label_valid"',
  'projected ticket labels reject internal controls'
);

select throws_ok(
  $sql$
    update public.ticket_tiers
    set display_order = 20
    where listing_id = '00000000-0000-4000-8000-000000000104'
      and display_order = 0
  $sql$,
  '23514',
  'new row for relation "ticket_tiers" violates check constraint "ticket_tiers_display_order_range"',
  'ticket tiers are limited to twenty distinct display positions'
);

select throws_ok(
  $sql$
    update public.event_details
    set organizer_name = E'\tCollectif Kwabor'
    where listing_id = '00000000-0000-4000-8000-000000000104'
  $sql$,
  '23514',
  'new row for relation "event_details" violates check constraint "event_details_v1_text_canonical"',
  'projected event organizer names reject leading tabulation'
);

select throws_ok(
  $sql$
    update public.listings
    set tags = array['restaurant', 'restaurant']
    where id = 'da140000-0000-4000-8000-000000000001'
  $sql$,
  '23514',
  'new row for relation "listings" violates check constraint "listings_tags_contract"',
  'listing storage rejects duplicate projected tags'
);

select throws_ok(
  $sql$
    update public.listings
    set tags = array[['restaurant', 'terrasse']]::text[]
    where id = 'da140000-0000-4000-8000-000000000001'
  $sql$,
  '23514',
  'new row for relation "listings" violates check constraint "listings_tags_contract"',
  'listing storage rejects multidimensional projected tags'
);

select throws_ok(
  $sql$
    update public.food_details
    set cuisines = array['beninoise', 'beninoise']
    where listing_id = 'da140000-0000-4000-8000-000000000001'
  $sql$,
  '23514',
  'new row for relation "food_details" violates check constraint "food_details_cuisines_valid"',
  'typed detail storage rejects duplicate projected text values'
);

select throws_ok(
  $sql$
    update public.food_details
    set cuisines = array[['beninoise', 'africaine']]::text[]
    where listing_id = 'da140000-0000-4000-8000-000000000001'
  $sql$,
  '23514',
  'new row for relation "food_details" violates check constraint "food_details_cuisines_valid"',
  'typed detail storage rejects multidimensional projected text values'
);

select ok(
  app_private.catalog_opening_hours_is_valid('{}'::jsonb),
  'empty opening hours remain valid for drafts and optional listing families'
);

select ok(
  app_private.catalog_opening_hours_is_valid(tests.valid_opening_hours()),
  'the canonical seven-day opening-hours object is valid'
);

select ok(
  not app_private.catalog_opening_hours_is_valid(tests.valid_opening_hours() - 'sunday'),
  'a seven-day schedule rejects a missing day'
);

select ok(
  not app_private.catalog_opening_hours_is_valid(
    tests.valid_opening_hours() || '{"holiday":{"status":"closed","periods":[]}}'::jsonb
  ),
  'a seven-day schedule rejects an extra day'
);

select ok(
  not app_private.catalog_opening_hours_is_valid(
    jsonb_set(
      tests.valid_opening_hours(),
      '{monday}',
      '{"status":"periods","periods":[]}'::jsonb
    )
  ),
  'period status requires at least one period'
);

select ok(
  not app_private.catalog_opening_hours_is_valid(
    jsonb_set(
      tests.valid_opening_hours(),
      '{monday}',
      '{"status":"periods","periods":[{"opens_minute":600,"closes_minute":800,"closes_next_day":false},{"opens_minute":700,"closes_minute":900,"closes_next_day":false}]}'::jsonb
    )
  ),
  'opening periods cannot overlap'
);

select ok(
  not app_private.catalog_opening_hours_is_valid(
    jsonb_set(
      tests.valid_opening_hours(),
      '{monday}',
      '{"status":"periods","periods":[{"opens_minute":900,"closes_minute":1000,"closes_next_day":false},{"opens_minute":600,"closes_minute":700,"closes_next_day":false}]}'::jsonb
    )
  ),
  'opening periods must be ordered'
);

select ok(
  not app_private.catalog_opening_hours_is_valid(
    jsonb_set(
      tests.valid_opening_hours(),
      '{monday}',
      '{"status":"periods","periods":[{"opens_minute":1200,"closes_minute":60,"closes_next_day":true},{"opens_minute":120,"closes_minute":200,"closes_next_day":false}]}'::jsonb
    )
  ),
  'an overnight period must be the final period of its day'
);

select ok(
  not app_private.catalog_opening_hours_is_valid(
    jsonb_set(
      tests.valid_opening_hours(),
      '{sunday}',
      '{"status":"closed","periods":[{"opens_minute":600,"closes_minute":700,"closes_next_day":false}]}'::jsonb
    )
  ),
  'a closed day cannot contain periods'
);

select ok(
  not app_private.catalog_opening_hours_is_valid(
    jsonb_set(
      tests.valid_opening_hours(),
      '{monday}',
      '{"status":"periods","periods":[{"opens_minute":900,"closes_minute":800,"closes_next_day":false}]}'::jsonb
    )
  ),
  'a same-day period must close after it opens'
);

select ok(
  app_private.catalog_socials_are_valid('{}'::jsonb),
  'an empty social-link object is valid'
);

select ok(
  app_private.catalog_socials_are_valid(
    '{"instagram":"https://social.example.invalid/path?campaign=kwabor"}'::jsonb
  ),
  'an allowlisted social platform accepts a normalized HTTPS URL'
);

select ok(
  not app_private.catalog_socials_are_valid(
    '{"snapchat":"https://social.example.invalid/kwabor"}'::jsonb
  ),
  'unsupported social platforms are rejected'
);

select ok(
  not app_private.catalog_socials_are_valid(
    '{"instagram":"https://social.example.invalid/kwabor#fragment"}'::jsonb
  ),
  'social URLs with fragments are rejected'
);

select ok(
  not app_private.catalog_socials_are_valid(
    '{"instagram":"https://user@social.example.invalid/kwabor"}'::jsonb
  ),
  'social URLs with authority userinfo are rejected'
);

select ok(
  not app_private.catalog_socials_are_valid('{"instagram":"https:///kwabor"}'::jsonb),
  'social URLs with an empty host are rejected'
);

select ok(
  not app_private.catalog_socials_are_valid(
    jsonb_build_object('instagram', 'https://social.example.invalid/' || repeat('a', 2020))
  ),
  'social URLs longer than 2048 UTF-8 bytes are rejected'
);

select ok(
  app_private.catalog_https_url_is_valid('https://example.invalid/path/@editor?source=kwabor'),
  'an at-sign outside the URL authority remains valid'
);

select ok(
  not app_private.catalog_https_url_is_valid('https://example.invalid/path#fragment'),
  'the shared HTTPS validator rejects fragments'
);

select ok(
  not app_private.catalog_https_url_is_valid('https://example.invalid/path#'),
  'the shared HTTPS validator rejects an explicit empty fragment'
);

select ok(
  not app_private.catalog_https_url_is_valid(U&'https://example.invalid/a\00A0b'),
  'the shared HTTPS validator rejects mobile Unicode whitespace in paths'
);

select ok(
  not app_private.catalog_https_url_is_valid('https://example.invalid/path%'),
  'the shared HTTPS validator rejects a truncated percent escape in a path'
);

select ok(
  not app_private.catalog_https_url_is_valid('https://example.invalid/search?q=%2'),
  'the shared HTTPS validator rejects a short percent escape in a query'
);

select ok(
  not app_private.catalog_https_url_is_valid('https://example.invalid/path%GG'),
  'the shared HTTPS validator rejects a non-hexadecimal percent escape in a path'
);

select ok(
  app_private.catalog_https_url_is_valid('https://example.invalid/path%25'),
  'the shared HTTPS validator accepts an encoded percent sign in a path'
);

select ok(
  app_private.catalog_https_url_is_valid(
    'https://example.invalid/search?q=%F0%9F%90%95'
  ),
  'the shared HTTPS validator accepts a valid UTF-8 percent sequence in a query'
);

select ok(
  not app_private.catalog_https_url_is_valid('https://user@example.invalid/path'),
  'the shared HTTPS validator rejects authority userinfo'
);

select ok(
  not app_private.catalog_https_url_is_valid('https:///path'),
  'the shared HTTPS validator rejects an empty host'
);

select ok(
  not app_private.catalog_https_url_is_valid('https://example.invalid/' || repeat('a', 2028)),
  'the shared HTTPS validator rejects URLs longer than 2048 UTF-8 bytes'
);

select ok(
  app_private.catalog_https_url_is_valid(
    'https://example.invalid/' || repeat(U&'\+01F600', 506)
  ),
  'the shared HTTPS validator accepts an exact 2048-byte non-BMP URL'
);

select ok(
  not app_private.catalog_https_url_is_valid(
    'https://example.invalid/' || repeat(U&'\+01F600', 507)
  ),
  'the shared HTTPS validator rejects a 2052-byte non-BMP URL'
);

select ok(
  app_private.catalog_https_url_is_valid('https://example.invalid:443/path'),
  'the shared HTTPS validator accepts the explicit effective HTTPS port'
);

select ok(
  not app_private.catalog_https_url_is_valid('https://example.invalid:0443/path'),
  'the shared HTTPS validator rejects a zero-padded four-digit HTTPS port'
);

select ok(
  not app_private.catalog_https_url_is_valid('https://example.invalid:00443/path'),
  'the shared HTTPS validator rejects a zero-padded five-digit HTTPS port'
);

select ok(
  not app_private.catalog_https_url_is_valid('https://example.invalid:abc/path'),
  'the shared HTTPS validator rejects a non-numeric port'
);

select ok(
  not app_private.catalog_https_url_is_valid('https://example.invalid:99999/path'),
  'the shared HTTPS validator rejects an out-of-range port'
);

select ok(
  not app_private.catalog_https_url_is_valid('https://example.invalid:8443/path'),
  'the shared HTTPS validator rejects a non-443 effective port'
);

select ok(
  not app_private.catalog_https_url_is_valid(
    'https://example.invalid/' || chr(92) || 'path'
  ),
  'the shared HTTPS validator rejects backslashes'
);

select ok(
  not app_private.catalog_https_url_is_valid('https://[::::]/path'),
  'the shared HTTPS validator rejects malformed IPv6 authorities'
);

select ok(
  not app_private.catalog_https_url_is_valid('https://127.0.0.1/path'),
  'the shared HTTPS validator rejects IP-literal hosts'
);

select ok(
  not app_private.catalog_https_url_is_valid('https://service.localhost/path')
  and not app_private.catalog_https_url_is_valid('https://service.local/path')
  and not app_private.catalog_https_url_is_valid('https://service.internal/path')
  and not app_private.catalog_https_url_is_valid('https://service.lan/path')
  and not app_private.catalog_https_url_is_valid('https://service.home.arpa/path'),
  'the shared HTTPS validator rejects every private DNS suffix from the mobile policy'
);

select throws_ok(
  $sql$
    update public.listings
    set external_url = 'https://user@example.invalid/path'
    where id = 'da140000-0000-4000-8000-000000000001'
  $sql$,
  '23514',
  'new row for relation "listings" violates check constraint "listings_external_url_https"',
  'listing external URLs enforce the shared HTTPS policy'
);

select throws_ok(
  $sql$
    update public.listings
    set socials = '{"instagram":"https://example.invalid/path#fragment"}'::jsonb
    where id = 'da140000-0000-4000-8000-000000000001'
  $sql$,
  '23514',
  'new row for relation "listings" violates check constraint "listings_socials_contract"',
  'listing social links enforce the shared HTTPS policy'
);

select throws_ok(
  $sql$
    insert into public.listing_media (
      listing_id,
      url,
      alt,
      display_order,
      is_cover,
      kind
    ) values (
      'da140000-0000-4000-8000-000000000001',
      'https:///invalid.jpg',
      'Media invalide',
      0,
      false,
      'image'
    )
  $sql$,
  '23514',
  'new row for relation "listing_media" violates check constraint "listing_media_url_https"',
  'official media URLs enforce the shared HTTPS policy'
);

select throws_ok(
  $sql$
    insert into public.listing_media (
      listing_id,
      url,
      alt,
      display_order,
      is_cover,
      kind
    ) values (
      'da110000-0000-4000-8000-000000000001',
      'https://media.example.invalid/duplicate-order.jpg',
      'Media au rang duplique',
      1,
      false,
      'image'
    )
  $sql$,
  '23505',
  'duplicate key value violates unique constraint "listing_media_listing_order_unique"',
  'official media display order is unique within a listing'
);

select throws_ok(
  $sql$
    update public.food_details
    set menu_url = 'https://restaurant.example.invalid/menu#fragment'
    where listing_id = 'da140000-0000-4000-8000-000000000001'
  $sql$,
  '23514',
  'new row for relation "food_details" violates check constraint "food_details_menu_url_https"',
  'menu URLs enforce the shared HTTPS policy'
);

select throws_ok(
  $sql$
    update public.event_details
    set ticket_url = 'https://buyer@tickets.example.invalid/event'
    where listing_id = '00000000-0000-4000-8000-000000000104'
  $sql$,
  '23514',
  'new row for relation "event_details" violates check constraint "event_details_ticket_url_valid"',
  'ticket URLs enforce the shared HTTPS policy'
);

select lives_ok(
  $$select app_private.assert_catalog_listing_detail_complete('da110000-0000-4000-8000-000000000001')$$,
  'a complete published lodging detail satisfies final-state validation'
);

select lives_ok(
  $$select app_private.assert_catalog_listing_detail_complete('da120000-0000-4000-8000-000000000001')$$,
  'a complete published guide detail satisfies final-state validation'
);

select lives_ok(
  $$select app_private.assert_catalog_listing_detail_complete('da130000-0000-4000-8000-000000000001')$$,
  'a complete published nightlife detail satisfies final-state validation'
);

update public.listings
set published_at = null
where id = 'da110000-0000-4000-8000-000000000001';

select throws_ok(
  $$select app_private.assert_catalog_listing_detail_complete('da110000-0000-4000-8000-000000000001')$$,
  '23514',
  'A published catalog listing requires a publication timestamp',
  'a published detail requires a non-null publication timestamp'
);

select is(
  tests.count_as(
    'anon',
    null,
    $$select * from public.get_catalog_detail_v1('da110000-0000-4000-8000-000000000001')$$
  ),
  0::bigint,
  'the RPC fails closed while a published row has no publication timestamp'
);

update public.listings
set published_at = '2026-08-02 00:00:00+00'
where id = 'da110000-0000-4000-8000-000000000001';

select throws_ok(
  $sql$
    update public.listings
    set published_at = 'infinity'::timestamptz
    where id = 'da110000-0000-4000-8000-000000000001'
  $sql$,
  '23514',
  'new row for relation "listings" violates check constraint "listings_published_at_finite"',
  'published catalog timestamps reject positive infinity'
);

select throws_ok(
  $sql$
    update public.listings
    set published_at = '-infinity'::timestamptz
    where id = 'da110000-0000-4000-8000-000000000001'
  $sql$,
  '23514',
  'new row for relation "listings" violates check constraint "listings_published_at_finite"',
  'published catalog timestamps reject negative infinity'
);

select throws_ok(
  $sql$
    update public.listings
    set published_at = '0001-01-01 BC'::timestamptz
    where id = 'da110000-0000-4000-8000-000000000001'
  $sql$,
  '23514',
  'new row for relation "listings" violates check constraint "listings_published_at_finite"',
  'published catalog timestamps reject years before the mobile RFC3339 range'
);

select throws_ok(
  $sql$
    update public.listings
    set published_at = '10000-01-01 00:00:00+00'::timestamptz
    where id = 'da110000-0000-4000-8000-000000000001'
  $sql$,
  '23514',
  'new row for relation "listings" violates check constraint "listings_published_at_finite"',
  'published catalog timestamps reject years after the mobile RFC3339 range'
);

select throws_ok(
  $sql$
    update public.listings
    set sponsored_until = '0001-01-01 BC'::timestamptz
    where id = 'da140000-0000-4000-8000-000000000001'
  $sql$,
  '23514',
  'new row for relation "listings" violates check constraint "listings_sponsored_until_mobile_safe"',
  'summary sponsorship timestamps reject years before the mobile RFC3339 range'
);

select throws_ok(
  $sql$
    update public.listings
    set sponsored_until = '10000-01-01 00:00:00+00'::timestamptz
    where id = 'da140000-0000-4000-8000-000000000001'
  $sql$,
  '23514',
  'new row for relation "listings" violates check constraint "listings_sponsored_until_mobile_safe"',
  'summary sponsorship timestamps reject years after the mobile RFC3339 range'
);

select throws_ok(
  $sql$
    update public.event_details
    set start_at = '0001-01-01 BC'::timestamptz,
        end_at = null
    where listing_id = '00000000-0000-4000-8000-000000000104'
  $sql$,
  '23514',
  'new row for relation "event_details" violates check constraint "event_details_start_at_finite"',
  'event start timestamps reject years before the mobile RFC3339 range'
);

select throws_ok(
  $sql$
    update public.event_details
    set start_at = '10000-01-01 00:00:00+00'::timestamptz,
        end_at = null
    where listing_id = '00000000-0000-4000-8000-000000000104'
  $sql$,
  '23514',
  'new row for relation "event_details" violates check constraint "event_details_start_at_finite"',
  'event start timestamps reject years after the mobile RFC3339 range'
);

select throws_ok(
  $sql$
    update public.event_details
    set end_at = '10000-01-01 00:00:00+00'::timestamptz
    where listing_id = '00000000-0000-4000-8000-000000000104'
  $sql$,
  '23514',
  'new row for relation "event_details" violates check constraint "event_details_end_at_valid"',
  'event end timestamps reject years after the mobile RFC3339 range'
);

select throws_ok(
  $sql$
    update public.lodging_details
    set checkin_time = '14:00:01'
    where listing_id = 'da110000-0000-4000-8000-000000000001'
  $sql$,
  '23514',
  'new row for relation "lodging_details" violates check constraint "lodging_details_checkin_minute_precision"',
  'lodging check-in times reject non-zero seconds'
);

select throws_ok(
  $sql$
    update public.lodging_details
    set checkout_time = '11:00:01'
    where listing_id = 'da110000-0000-4000-8000-000000000001'
  $sql$,
  '23514',
  'new row for relation "lodging_details" violates check constraint "lodging_details_checkout_minute_precision"',
  'lodging check-out times reject non-zero seconds'
);

select throws_ok(
  $sql$
    update public.lodging_details
    set checkin_time = '24:00:00'::time
    where listing_id = 'da110000-0000-4000-8000-000000000001'
  $sql$,
  '23514',
  'new row for relation "lodging_details" violates check constraint "lodging_details_checkin_minute_precision"',
  'lodging check-in times reject PostgreSQL 24:00 outside the mapper range'
);

select throws_ok(
  $sql$
    update public.lodging_details
    set checkout_time = '24:00:00'::time
    where listing_id = 'da110000-0000-4000-8000-000000000001'
  $sql$,
  '23514',
  'new row for relation "lodging_details" violates check constraint "lodging_details_checkout_minute_precision"',
  'lodging check-out times reject PostgreSQL 24:00 outside the mapper range'
);

select throws_ok(
  $sql$
    update public.nightlife_details
    set min_age = 15
    where listing_id = 'da130000-0000-4000-8000-000000000001'
  $sql$,
  '23514',
  'new row for relation "nightlife_details" violates check constraint "nightlife_details_min_age_range"',
  'nightlife minimum age rejects values below the mapper range'
);

select throws_ok(
  $sql$
    update public.nightlife_details
    set min_age = 26
    where listing_id = 'da130000-0000-4000-8000-000000000001'
  $sql$,
  '23514',
  'new row for relation "nightlife_details" violates check constraint "nightlife_details_min_age_range"',
  'nightlife minimum age rejects values above the mapper range'
);

select lives_ok(
  $sql$
    do $body$
    begin
      update public.nightlife_details
      set min_age = 16
      where listing_id = 'da130000-0000-4000-8000-000000000001';
      update public.nightlife_details
      set min_age = 25
      where listing_id = 'da130000-0000-4000-8000-000000000001';
      update public.nightlife_details
      set min_age = 18
      where listing_id = 'da130000-0000-4000-8000-000000000001';
    end;
    $body$
  $sql$,
  'nightlife minimum age accepts both mapper boundaries'
);

select throws_ok(
  $sql$
    update public.guide_details
    set experience_years = -1
    where listing_id = 'da120000-0000-4000-8000-000000000001'
  $sql$,
  '23514',
  'new row for relation "guide_details" violates check constraint "guide_details_experience_range"',
  'guide experience rejects negative years'
);

select throws_ok(
  $sql$
    update public.guide_details
    set experience_years = 81
    where listing_id = 'da120000-0000-4000-8000-000000000001'
  $sql$,
  '23514',
  'new row for relation "guide_details" violates check constraint "guide_details_experience_range"',
  'guide experience rejects values above the mapper range'
);

select lives_ok(
  $sql$
    do $body$
    begin
      update public.guide_details
      set experience_years = 0
      where listing_id = 'da120000-0000-4000-8000-000000000001';
      update public.guide_details
      set experience_years = 80
      where listing_id = 'da120000-0000-4000-8000-000000000001';
      update public.guide_details
      set experience_years = 7
      where listing_id = 'da120000-0000-4000-8000-000000000001';
    end;
    $body$
  $sql$,
  'guide experience accepts both mapper boundaries'
);

update public.event_details
set ticket_url = null
where listing_id = '00000000-0000-4000-8000-000000000104';

select throws_ok(
  $$select app_private.assert_catalog_listing_detail_complete('00000000-0000-4000-8000-000000000104')$$,
  '23514',
  'A paid event requires a ticket URL and positive tiers matching its starting XOF price',
  'an active paid event requires a ticket URL'
);

update public.event_details
set ticket_url = 'https://example.invalid/kwabor/tickets/festival-ouidah'
where listing_id = '00000000-0000-4000-8000-000000000104';

update public.ticket_tiers
set price_xof = 0
where listing_id = '00000000-0000-4000-8000-000000000104'
  and label = 'Standard';

select throws_ok(
  $$select app_private.assert_catalog_listing_detail_complete('00000000-0000-4000-8000-000000000104')$$,
  '23514',
  'A paid event requires a ticket URL and positive tiers matching its starting XOF price',
  'an active paid event rejects a zero-priced ticket tier'
);

update public.ticket_tiers
set price_xof = 2000
where listing_id = '00000000-0000-4000-8000-000000000104'
  and label = 'Standard';

select throws_ok(
  $sql$
    update public.place_details
    set is_free = false,
        entry_fee_xof = 0
    where listing_id = '00000000-0000-4000-8000-000000000101'
  $sql$,
  '23514',
  'new row for relation "place_details" violates check constraint "place_details_fee_consistent"',
  'a paid place requires a strictly positive entry fee'
);

update public.listings
set status = 'publie',
    published_at = now()
where id = 'da140000-0000-4000-8000-000000000001';

select throws_ok(
  $$select app_private.assert_catalog_listing_detail_complete('da140000-0000-4000-8000-000000000001')$$,
  '23514',
  'An active catalog listing requires exactly one official image cover',
  'an active incomplete detail fails closed before publication'
);

update public.listings
set status = 'brouillon',
    published_at = null
where id = 'da140000-0000-4000-8000-000000000001';

select is(
  tests.count_as(
    'anon',
    null,
    $$select listing_id from public.food_details where listing_id = '00000000-0000-4000-8000-000000000103'$$
  ),
  1::bigint,
  'anonymous callers read typed details only for published listings'
);

select is(
  tests.count_as(
    'anon',
    null,
    $$select listing_id from public.food_details where listing_id = 'da140000-0000-4000-8000-000000000001'$$
  ),
  0::bigint,
  'anonymous callers cannot read typed details for drafts'
);

select is(
  tests.count_as(
    'authenticated',
    'da000000-0000-4000-8000-000000000001',
    $$select listing_id from public.food_details where listing_id = 'da140000-0000-4000-8000-000000000001'$$
  ),
  1::bigint,
  'an authenticated manager reads its draft typed details'
);

select is(
  tests.count_as(
    'authenticated',
    'da000000-0000-4000-8000-000000000002',
    $$select listing_id from public.food_details where listing_id = 'da140000-0000-4000-8000-000000000001'$$
  ),
  0::bigint,
  'another authenticated caller cannot read a private typed detail'
);

select is(
  tests.affected_rows_as(
    'authenticated',
    'da000000-0000-4000-8000-000000000001',
    $$update public.food_details set reservation = true where listing_id = 'da140000-0000-4000-8000-000000000001'$$
  ),
  1::bigint,
  'a completed manager can update its draft typed detail'
);

select is(
  tests.affected_rows_as(
    'authenticated',
    'da000000-0000-4000-8000-000000000002',
    $$update public.food_details set reservation = false where listing_id = 'da140000-0000-4000-8000-000000000001'$$
  ),
  0::bigint,
  'another authenticated caller cannot update a private typed detail'
);

select is(
  tests.affected_rows_as(
    'authenticated',
    'da000000-0000-4000-8000-000000000001',
    $$update public.room_types set price_xof = 26000 where listing_id = 'da110000-0000-4000-8000-000000000001' and name = 'Standard'$$
  ),
  0::bigint,
  'a manager cannot bypass re-moderation by editing a published room type'
);

select is(
  tests.affected_rows_as(
    'authenticated',
    'da000000-0000-4000-8000-000000000001',
    $$
      update public.listing_media
      set alt = 'Salle du restaurant relue'
      where listing_id = 'da140000-0000-4000-8000-000000000001'
        and display_order = 0
    $$
  ),
  1::bigint,
  'a completed manager can update official media on its draft listing'
);

select is(
  tests.affected_rows_as(
    'authenticated',
    'da000000-0000-4000-8000-000000000001',
    $$
      delete from public.listing_media
      where listing_id = 'da140000-0000-4000-8000-000000000001'
        and display_order = 0
    $$
  ),
  1::bigint,
  'a completed manager can delete official media from its draft listing'
);

select throws_ok(
  $sql$
    select tests.affected_rows_as(
      'authenticated',
      'da000000-0000-4000-8000-000000000001',
      $dml$
        insert into public.listing_media (
          listing_id,
          url,
          alt,
          display_order,
          is_cover,
          kind
        ) values (
          'da110000-0000-4000-8000-000000000001',
          'https://media.example.invalid/published-addition.jpg',
          'Ajout apres publication',
          2,
          false,
          'image'
        )
      $dml$
    )
  $sql$,
  '42501',
  'Official media can only change before publication',
  'a manager cannot add official media to a published listing without re-moderation'
);

select is(
  tests.affected_rows_as(
    'authenticated',
    'da000000-0000-4000-8000-000000000001',
    $$
      update public.listing_media
      set alt = 'Facade modifiee apres publication'
      where listing_id = 'da110000-0000-4000-8000-000000000001'
        and display_order = 0
    $$
  ),
  0::bigint,
  'a manager cannot update official media on a published listing without re-moderation'
);

select is(
  tests.affected_rows_as(
    'authenticated',
    'da000000-0000-4000-8000-000000000001',
    $$
      delete from public.listing_media
      where listing_id = 'da110000-0000-4000-8000-000000000001'
        and display_order = 1
    $$
  ),
  0::bigint,
  'a manager cannot delete official media from a published listing without re-moderation'
);

select * from finish();
rollback;
