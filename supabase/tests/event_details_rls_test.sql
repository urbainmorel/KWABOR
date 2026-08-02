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

create or replace function tests.statement_succeeds_as(db_role text, uid uuid, sql text)
returns boolean
language plpgsql
as $$
begin
  perform tests.use_auth_context(db_role, uid);
  execute sql;
  reset role;
  return true;
exception
  when others then
    reset role;
    return false;
end;
$$;

create or replace function tests.statement_fails_as(db_role text, uid uuid, sql text)
returns boolean
language plpgsql
as $$
begin
  perform tests.use_auth_context(db_role, uid);
  execute sql;
  reset role;
  return false;
exception
  when others then
    reset role;
    return true;
end;
$$;

select plan(57);

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
    'ed000000-0000-4000-8000-000000000001',
    'authenticated',
    'authenticated',
    'event-owner@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    'ed000000-0000-4000-8000-000000000002',
    'authenticated',
    'authenticated',
    'event-other@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    'ed000000-0000-4000-8000-000000000003',
    'authenticated',
    'authenticated',
    'event-user@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    'ed000000-0000-4000-8000-000000000004',
    'authenticated',
    'authenticated',
    'event-incomplete@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    'ed000000-0000-4000-8000-000000000005',
    'authenticated',
    'authenticated',
    'event-admin@kwabor.test',
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
  ('ed000000-0000-4000-8000-000000000001', 'Owner', 'Event', 'cotonou', now()),
  ('ed000000-0000-4000-8000-000000000002', 'Other', 'Promoter', 'cotonou', now()),
  ('ed000000-0000-4000-8000-000000000003', 'Regular', 'User', 'cotonou', now()),
  ('ed000000-0000-4000-8000-000000000004', 'Incomplete', 'Promoter', 'cotonou', null),
  ('ed000000-0000-4000-8000-000000000005', 'Admin', 'Event', 'cotonou', now());

insert into public.user_roles (user_id, role, verification_status)
values
  ('ed000000-0000-4000-8000-000000000001', 'promoteur', 'verified'),
  ('ed000000-0000-4000-8000-000000000002', 'promoteur', 'verified'),
  ('ed000000-0000-4000-8000-000000000003', 'user', 'unverified'),
  ('ed000000-0000-4000-8000-000000000004', 'promoteur', 'verified'),
  ('ed000000-0000-4000-8000-000000000005', 'admin', 'verified');

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
  address,
  lat,
  lng,
  price_unit
)
values
  (
    'ed100000-0000-4000-8000-000000000010',
    'evenement',
    'culture',
    'evenementiel',
    'event-culture',
    'ed000000-0000-4000-8000-000000000001',
    'ed000000-0000-4000-8000-000000000001',
    'brouillon',
    'Événement public test',
    'event-details-public-test',
    'Événement public utilisé pour vérifier la lecture anonyme des détails événementiels.',
    'ouidah',
    'Ouidah',
    6.3631,
    2.0851,
    'aucune'
  ),
  (
    'ed100000-0000-4000-8000-000000000011',
    'evenement',
    'culture',
    'evenementiel',
    'event-culture',
    'ed000000-0000-4000-8000-000000000001',
    'ed000000-0000-4000-8000-000000000001',
    'brouillon',
    'Événement privé propriétaire',
    'event-details-owner-draft',
    'Brouillon événementiel utilisé pour vérifier la lecture privée du propriétaire autorisé.',
    'cotonou',
    'Cotonou',
    6.3703,
    2.3912,
    'aucune'
  ),
  (
    'ed100000-0000-4000-8000-000000000012',
    'evenement',
    'culture',
    'evenementiel',
    'event-culture',
    'ed000000-0000-4000-8000-000000000002',
    'ed000000-0000-4000-8000-000000000002',
    'brouillon',
    'Événement privé autre propriétaire',
    'event-details-other-draft',
    'Brouillon événementiel utilisé pour vérifier l isolation stricte entre deux promoteurs.',
    'cotonou',
    'Cotonou',
    6.3703,
    2.3912,
    'aucune'
  ),
  (
    'ed100000-0000-4000-8000-000000000013',
    'evenement',
    'culture',
    'evenementiel',
    'event-culture',
    'ed000000-0000-4000-8000-000000000001',
    'ed000000-0000-4000-8000-000000000001',
    'brouillon',
    'Événement candidat détails',
    'event-details-candidate',
    'Brouillon événementiel utilisé pour valider les créations et modifications autorisées.',
    'cotonou',
    'Cotonou',
    6.3703,
    2.3912,
    'aucune'
  ),
  (
    'ed100000-0000-4000-8000-000000000014',
    'lieu',
    'marche',
    'commercial',
    'commercial-marche',
    'ed000000-0000-4000-8000-000000000001',
    'ed000000-0000-4000-8000-000000000001',
    'brouillon',
    'Lieu candidat invalide',
    'event-details-place-candidate',
    'Lieu commercial utilisé pour vérifier le refus des détails événementiels sur une autre famille.',
    'cotonou',
    'Cotonou',
    6.3703,
    2.3912,
    'aucune'
  ),
  (
    'ed100000-0000-4000-8000-000000000015',
    'evenement',
    'culture',
    'evenementiel',
    'event-culture',
    'ed000000-0000-4000-8000-000000000004',
    'ed000000-0000-4000-8000-000000000004',
    'brouillon',
    'Événement onboarding incomplet',
    'event-details-incomplete-onboarding',
    'Brouillon utilisé pour vérifier que le rôle vérifié ne contourne pas la fin de l onboarding.',
    'cotonou',
    'Cotonou',
    6.3703,
    2.3912,
    'aucune'
  ),
  (
    'ed100000-0000-4000-8000-000000000016',
    'evenement',
    'culture',
    'evenementiel',
    'event-culture',
    'ed000000-0000-4000-8000-000000000001',
    'ed000000-0000-4000-8000-000000000001',
    'brouillon',
    'Événement sans localisation',
    'event-details-without-location',
    'Brouillon utilisé pour vérifier que les détails exigent un lieu ou des coordonnées complètes.',
    'cotonou',
    null,
    null,
    null,
    'aucune'
  ),
  (
    'ed100000-0000-4000-8000-000000000017',
    'evenement',
    'culture',
    'evenementiel',
    'event-culture',
    'ed000000-0000-4000-8000-000000000001',
    'ed000000-0000-4000-8000-000000000001',
    'brouillon',
    'Événement avec lieu privé géré',
    'event-details-managed-draft-venue-event',
    'Brouillon utilisé pour vérifier la publication coordonnée du lieu et de l événement.',
    'cotonou',
    'Cotonou',
    6.3703,
    2.3912,
    'aucune'
  ),
  (
    'ed100000-0000-4000-8000-000000000020',
    'lieu',
    'nature',
    'patrimonial',
    'heritage-nature',
    null,
    null,
    'publie',
    'Lieu événementiel public',
    'event-details-public-venue',
    'Lieu public utilisé comme rattachement valide pour un événement du catalogue Kwabor.',
    'cotonou',
    'Cotonou',
    6.3703,
    2.3912,
    'aucune'
  ),
  (
    'ed100000-0000-4000-8000-000000000021',
    'etablissement',
    'restaurant',
    'commercial',
    'commercial-restaurant',
    'ed000000-0000-4000-8000-000000000002',
    'ed000000-0000-4000-8000-000000000002',
    'brouillon',
    'Restaurant privé autre promoteur',
    'event-details-private-venue',
    'Brouillon privé utilisé pour vérifier la confidentialité des lieux rattachés.',
    'cotonou',
    'Cotonou',
    6.3703,
    2.3912,
    'aucune'
  ),
  (
    'ed100000-0000-4000-8000-000000000022',
    'etablissement',
    'restaurant',
    'commercial',
    'commercial-restaurant',
    'ed000000-0000-4000-8000-000000000001',
    'ed000000-0000-4000-8000-000000000001',
    'brouillon',
    'Restaurant privé géré',
    'event-details-managed-draft-venue',
    'Brouillon privé géré utilisé pour vérifier la publication coordonnée avec l événement.',
    'cotonou',
    'Cotonou',
    6.3703,
    2.3912,
    'aucune'
  );

insert into public.event_details (
  listing_id,
  category,
  start_at,
  end_at,
  organizer_name,
  organizer_contact,
  ticket_type
)
values
  (
    'ed100000-0000-4000-8000-000000000010',
    'culture',
    '2026-09-10 18:00:00+01',
    '2026-09-10 22:00:00+01',
    'Organisation publique',
    'public@kwabor.test',
    'gratuit'
  ),
  (
    'ed100000-0000-4000-8000-000000000011',
    'culture',
    '2026-09-11 18:00:00+01',
    null,
    'Organisation propriétaire',
    '+22997000001',
    'gratuit'
  ),
  (
    'ed100000-0000-4000-8000-000000000012',
    'culture',
    '2026-09-12 18:00:00+01',
    null,
    'Organisation autre',
    '+22997000002',
    'gratuit'
  );

update public.listings
set status = 'publie',
    published_at = now()
where id = 'ed100000-0000-4000-8000-000000000010';

select ok(to_regclass('public.event_details') is not null, 'event details table exists');

select is(
  (
    select array_agg(enum_value.enumlabel order by enum_value.enumsortorder)
    from pg_enum enum_value
    join pg_type enum_type on enum_type.oid = enum_value.enumtypid
    join pg_namespace namespace on namespace.oid = enum_type.typnamespace
    where namespace.nspname = 'public'
      and enum_type.typname = 'ticket_type'
  ),
  array['gratuit', 'payant']::name[],
  'ticket type exposes only the approved values'
);

select is(
  (
    select category
    from public.event_details
    where listing_id = '00000000-0000-4000-8000-000000000104'
  ),
  'culture'::text,
  'the seed event has its typed event details'
);

select ok(
  (
    select end_at > start_at and venue_listing_id is null
    from public.event_details
    where listing_id = '00000000-0000-4000-8000-000000000104'
  ),
  'the seed event keeps a coherent schedule and canonical direct location'
);

select ok(
  (select relation.relrowsecurity from pg_class relation where relation.oid = 'public.event_details'::regclass),
  'event details has row level security enabled'
);

select ok(to_regclass('public.event_details_start_listing_idx') is not null, 'event date index exists');
select ok(has_table_privilege('anon', 'public.event_details', 'select'), 'anon has event detail select');
select ok(not has_table_privilege('anon', 'public.event_details', 'insert'), 'anon cannot insert event details');
select ok(has_table_privilege('authenticated', 'public.event_details', 'select'), 'authenticated can select details');
select ok(
  has_column_privilege('authenticated', 'public.event_details', 'listing_id', 'insert'),
  'authenticated can insert the managed event identifier'
);
select ok(
  not has_column_privilege('authenticated', 'public.event_details', 'created_at', 'insert'),
  'authenticated cannot forge event detail creation time'
);
select ok(
  has_column_privilege('authenticated', 'public.event_details', 'start_at', 'update'),
  'authenticated can update managed event dates'
);
select ok(
  not has_column_privilege('authenticated', 'public.event_details', 'listing_id', 'update'),
  'authenticated cannot move details to another listing'
);
select ok(
  has_table_privilege('service_role', 'public.event_details', 'select,insert,update,delete'),
  'service role has explicit server-side event detail privileges'
);
select ok(
  not has_function_privilege(
    'authenticated',
    'app_private.enforce_event_detail_references()',
    'execute'
  ),
  'authenticated users cannot invoke the privileged reference guard directly'
);

select is(
  tests.count_as(
    'anon',
    null,
    'select listing_id from public.event_details where listing_id = ''ed100000-0000-4000-8000-000000000010'''
  ),
  1::bigint,
  'anon reads details of a published event'
);

select is(
  tests.count_as(
    'anon',
    null,
    'select listing_id from public.event_details where listing_id = ''ed100000-0000-4000-8000-000000000011'''
  ),
  0::bigint,
  'anon cannot read draft event details'
);

select is(
  tests.count_as(
    'authenticated',
    'ed000000-0000-4000-8000-000000000003',
    'select listing_id from public.event_details where listing_id = ''ed100000-0000-4000-8000-000000000010'''
  ),
  1::bigint,
  'an authenticated user reads published event details'
);

select is(
  tests.count_as(
    'authenticated',
    'ed000000-0000-4000-8000-000000000001',
    'select listing_id from public.event_details where listing_id = ''ed100000-0000-4000-8000-000000000011'''
  ),
  1::bigint,
  'an event manager reads their draft details'
);

select is(
  tests.count_as(
    'authenticated',
    'ed000000-0000-4000-8000-000000000003',
    'select listing_id from public.event_details where listing_id = ''ed100000-0000-4000-8000-000000000011'''
  ),
  0::bigint,
  'an ordinary user cannot read another user draft details'
);

select is(
  tests.count_as(
    'authenticated',
    'ed000000-0000-4000-8000-000000000002',
    'select listing_id from public.event_details where listing_id = ''ed100000-0000-4000-8000-000000000011'''
  ),
  0::bigint,
  'another promoter cannot read another promoter draft details'
);

select ok(
  tests.statement_succeeds_as(
    'authenticated',
    'ed000000-0000-4000-8000-000000000001',
    $sql$
      update public.event_details
      set capacity = 777
      where listing_id = 'ed100000-0000-4000-8000-000000000010'
    $sql$
  ),
  'a manager update of published details is safely masked by row level security'
);

select is(
  (
    select capacity
    from public.event_details
    where listing_id = 'ed100000-0000-4000-8000-000000000010'
  ),
  null::integer,
  'a manager cannot modify published event details without re-moderation'
);

select ok(
  tests.statement_succeeds_as(
    'authenticated',
    'ed000000-0000-4000-8000-000000000005',
    $sql$
      delete from public.event_details
      where listing_id = 'ed100000-0000-4000-8000-000000000010'
    $sql$
  ),
  'an admin delete of published details is safely masked by row level security'
);

select is(
  (
    select count(*)::integer
    from public.event_details
    where listing_id = 'ed100000-0000-4000-8000-000000000010'
  ),
  1,
  'an admin cannot directly remove required published event details'
);

select ok(
  tests.statement_succeeds_as(
    'authenticated',
    'ed000000-0000-4000-8000-000000000005',
    $sql$
      update public.event_details
      set capacity = 888
      where listing_id = 'ed100000-0000-4000-8000-000000000010'
    $sql$
  ),
  'a verified admin can update published event details'
);

select is(
  (
    select capacity
    from public.event_details
    where listing_id = 'ed100000-0000-4000-8000-000000000010'
  ),
  888,
  'the verified admin update is persisted'
);

select throws_ok(
  $sql$
    update public.event_details
    set venue_listing_id = 'ed100000-0000-4000-8000-000000000021'
    where listing_id = 'ed100000-0000-4000-8000-000000000010'
  $sql$,
  '23514',
  'An event under review or published requires a published venue',
  'published event details cannot switch to a private draft venue'
);

select throws_ok(
  $sql$
    delete from public.event_details
    where listing_id = 'ed100000-0000-4000-8000-000000000010'
  $sql$,
  '23514',
  'An event under review or published must keep its event details',
  'database integrity rejects privileged removal of published event details'
);

select ok(
  tests.statement_fails_as(
    'authenticated',
    'ed000000-0000-4000-8000-000000000001',
    $sql$
      update public.listings
      set address = null,
          lat = null,
          lng = null
      where id = 'ed100000-0000-4000-8000-000000000011'
    $sql$
  ),
  'an event without a venue listing cannot lose its address and coordinates'
);

select throws_ok(
  $sql$
    update public.listings
    set type = 'etablissement',
        subtype = 'restaurant',
        listing_class = 'commercial',
        category_id = 'commercial-restaurant'
    where id = 'ed100000-0000-4000-8000-000000000011'
  $sql$,
  '23514',
  'A listing with event details must remain an event with the same category',
  'a parent listing cannot change taxonomy while event details exist'
);

select throws_ok(
  $sql$
    update public.listings
    set status = 'en_attente'
    where id = 'ed100000-0000-4000-8000-000000000013'
  $sql$,
  '23514',
  'An event must have details before review or publication',
  'an event cannot enter review without its required details'
);

select ok(
  tests.statement_fails_as(
    'authenticated',
    'ed000000-0000-4000-8000-000000000003',
    $sql$
      insert into public.event_details (
        listing_id,
        category,
        start_at,
        organizer_name,
        organizer_contact,
        ticket_type
      ) values (
        'ed100000-0000-4000-8000-000000000013',
        'culture',
        '2026-10-01 18:00:00+01',
        'Organisation refusée',
        'denied@kwabor.test',
        'gratuit'
      )
    $sql$
  ),
  'an ordinary user cannot create event details'
);

select ok(
  tests.statement_fails_as(
    'authenticated',
    'ed000000-0000-4000-8000-000000000004',
    $sql$
      insert into public.event_details (
        listing_id,
        category,
        start_at,
        organizer_name,
        organizer_contact,
        ticket_type
      ) values (
        'ed100000-0000-4000-8000-000000000015',
        'culture',
        '2026-10-01 18:00:00+01',
        'Organisation incomplète',
        'incomplete@kwabor.test',
        'gratuit'
      )
    $sql$
  ),
  'a verified promoter cannot mutate event details before completing onboarding'
);

select ok(
  tests.statement_fails_as(
    'authenticated',
    'ed000000-0000-4000-8000-000000000001',
    $sql$
      insert into public.event_details (
        listing_id,
        category,
        start_at,
        organizer_name,
        organizer_contact,
        ticket_type
      ) values (
        'ed100000-0000-4000-8000-000000000014',
        'marche',
        '2026-10-01 18:00:00+01',
        'Organisation invalide',
        'invalid@kwabor.test',
        'gratuit'
      )
    $sql$
  ),
  'event details reject a non-event parent listing'
);

select ok(
  tests.statement_fails_as(
    'authenticated',
    'ed000000-0000-4000-8000-000000000001',
    $sql$
      insert into public.event_details (
        listing_id,
        category,
        start_at,
        organizer_name,
        organizer_contact,
        ticket_type
      ) values (
        'ed100000-0000-4000-8000-000000000013',
        'festival',
        '2026-10-01 18:00:00+01',
        'Organisation invalide',
        'invalid@kwabor.test',
        'gratuit'
      )
    $sql$
  ),
  'event detail category must match the listing subtype'
);

select ok(
  tests.statement_fails_as(
    'authenticated',
    'ed000000-0000-4000-8000-000000000001',
    $sql$
      insert into public.event_details (
        listing_id,
        category,
        start_at,
        end_at,
        organizer_name,
        organizer_contact,
        ticket_type
      ) values (
        'ed100000-0000-4000-8000-000000000013',
        'culture',
        '2026-10-02 18:00:00+01',
        '2026-10-01 18:00:00+01',
        'Organisation invalide',
        'invalid@kwabor.test',
        'gratuit'
      )
    $sql$
  ),
  'event detail end cannot precede its start'
);

select ok(
  tests.statement_fails_as(
    'authenticated',
    'ed000000-0000-4000-8000-000000000001',
    $sql$
      insert into public.event_details (
        listing_id,
        category,
        start_at,
        venue_listing_id,
        organizer_name,
        organizer_contact,
        ticket_type
      ) values (
        'ed100000-0000-4000-8000-000000000013',
        'culture',
        '2026-10-01 18:00:00+01',
        'ed100000-0000-4000-8000-000000000010',
        'Organisation invalide',
        'invalid@kwabor.test',
        'gratuit'
      )
    $sql$
  ),
  'event venue rejects another event listing'
);

select ok(
  tests.statement_fails_as(
    'authenticated',
    'ed000000-0000-4000-8000-000000000001',
    $sql$
      insert into public.event_details (
        listing_id,
        category,
        start_at,
        venue_listing_id,
        organizer_name,
        organizer_contact,
        ticket_type
      ) values (
        'ed100000-0000-4000-8000-000000000013',
        'culture',
        '2026-10-01 18:00:00+01',
        'ed100000-0000-4000-8000-000000000021',
        'Organisation sans accès au lieu',
        'private-venue@kwabor.test',
        'gratuit'
      )
    $sql$
  ),
  'an event manager cannot reference another promoter private draft venue'
);

select ok(
  tests.statement_succeeds_as(
    'authenticated',
    'ed000000-0000-4000-8000-000000000001',
    $sql$
      insert into public.event_details (
        listing_id,
        category,
        start_at,
        venue_listing_id,
        organizer_name,
        organizer_contact,
        ticket_type
      ) values (
        'ed100000-0000-4000-8000-000000000017',
        'culture',
        '2026-10-01 18:00:00+01',
        'ed100000-0000-4000-8000-000000000022',
        'Organisation avec lieu géré',
        'managed-venue@kwabor.test',
        'gratuit'
      )
    $sql$
  ),
  'an event manager can prepare a draft event with their managed draft venue'
);

select throws_ok(
  $sql$
    update public.listings
    set status = 'en_attente'
    where id = 'ed100000-0000-4000-8000-000000000017'
  $sql$,
  '23514',
  'An event under review or published requires a published venue',
  'an event cannot enter review until its managed venue is published'
);

select ok(
  tests.statement_fails_as(
    'authenticated',
    'ed000000-0000-4000-8000-000000000001',
    $sql$
      insert into public.event_details (
        listing_id,
        category,
        start_at,
        organizer_name,
        organizer_contact,
        ticket_type
      ) values (
        'ed100000-0000-4000-8000-000000000013',
        'culture',
        '2026-10-01 18:00:00+01',
        'Organisation invalide',
        'contact libre invalide',
        'gratuit'
      )
    $sql$
  ),
  'event organizer contact must be an email or E.164 number'
);

select throws_ok(
  $sql$
    insert into public.event_details (
      listing_id,
      category,
      start_at,
      organizer_name,
      organizer_contact,
      ticket_type,
      ticket_url
    ) values (
      'ed100000-0000-4000-8000-000000000013',
      'culture',
      '2026-10-01 18:00:00+01',
      'Organisation invalide',
      'invalid@kwabor.test',
      'payant',
      'http://tickets.kwabor.test/event'
    )
  $sql$,
  '23514',
  'new row for relation "event_details" violates check constraint "event_details_ticket_url_valid"',
  'event ticket URL must use HTTPS'
);

select throws_ok(
  $sql$
    insert into public.event_details (
      listing_id,
      category,
      start_at,
      organizer_name,
      organizer_contact,
      ticket_type,
      capacity
    ) values (
      'ed100000-0000-4000-8000-000000000013',
      'culture',
      '2026-10-01 18:00:00+01',
      'Organisation invalide',
      'invalid@kwabor.test',
      'gratuit',
      0
    )
  $sql$,
  '23514',
  'new row for relation "event_details" violates check constraint "event_details_capacity_positive"',
  'event capacity must be strictly positive when present'
);

select throws_ok(
  $sql$
    insert into public.event_details (
      listing_id,
      category,
      start_at,
      organizer_name,
      organizer_contact,
      ticket_type
    ) values (
      'ed100000-0000-4000-8000-000000000016',
      'culture',
      '2026-10-01 18:00:00+01',
      'Organisation sans lieu',
      'location@kwabor.test',
      'gratuit'
    )
  $sql$,
  '23514',
  'An event requires a venue listing or an address with coordinates',
  'event details require a venue or complete direct location'
);

select ok(
  tests.statement_fails_as(
    'authenticated',
    'ed000000-0000-4000-8000-000000000001',
    $sql$
      insert into public.event_details (
        listing_id,
        category,
        start_at,
        organizer_name,
        organizer_contact,
        ticket_type,
        created_at
      ) values (
        'ed100000-0000-4000-8000-000000000013',
        'culture',
        '2026-10-01 18:00:00+01',
        'Organisation invalide',
        'invalid@kwabor.test',
        'gratuit',
        '2000-01-01 00:00:00+00'
      )
    $sql$
  ),
  'event managers cannot forge creation timestamps'
);

select ok(
  tests.statement_succeeds_as(
    'authenticated',
    'ed000000-0000-4000-8000-000000000001',
    $sql$
      insert into public.event_details (
        listing_id,
        category,
        start_at,
        end_at,
        venue_listing_id,
        organizer_name,
        organizer_contact,
        ticket_type,
        ticket_url,
        capacity
      ) values (
        'ed100000-0000-4000-8000-000000000013',
        '  culture  ',
        '2026-10-01 18:00:00+01',
        '2026-10-01 22:00:00+01',
        'ed100000-0000-4000-8000-000000000020',
        '  Organisation autorisée  ',
        '  +22997000001  ',
        'payant',
        '  https://tickets.kwabor.test/event  ',
        200
      )
    $sql$
  ),
  'an event manager creates valid details for their event'
);

select is(
  (
    select concat_ws('|', category, organizer_name, organizer_contact, ticket_url)
    from public.event_details
    where listing_id = 'ed100000-0000-4000-8000-000000000013'
  ),
  'culture|Organisation autorisée|+22997000001|https://tickets.kwabor.test/event',
  'event detail text fields are stored in canonical trimmed form'
);

select lives_ok(
  $sql$
    update public.listings
    set status = 'en_attente'
    where id = 'ed100000-0000-4000-8000-000000000013'
  $sql$,
  'server authority can move an event with complete details into review'
);

select throws_ok(
  $sql$
    update public.listings
    set status = 'brouillon'
    where id = 'ed100000-0000-4000-8000-000000000020'
  $sql$,
  '23514',
  'A venue used by an event under review or published must remain published',
  'a venue cannot be unpublished while an active event depends on it'
);

update public.listings
set status = 'brouillon'
where id = 'ed100000-0000-4000-8000-000000000013';

select throws_ok(
  $sql$
    update public.listings
    set type = 'evenement',
        subtype = 'culture',
        listing_class = 'evenementiel',
        category_id = 'event-culture'
    where id = 'ed100000-0000-4000-8000-000000000020'
  $sql$,
  '23514',
  'A listing used as an event venue cannot become an event',
  'a referenced venue cannot be converted into an event listing'
);

select ok(
  tests.statement_succeeds_as(
    'authenticated',
    'ed000000-0000-4000-8000-000000000001',
    $sql$
      update public.event_details
      set capacity = 250
      where listing_id = 'ed100000-0000-4000-8000-000000000013'
    $sql$
  ),
  'an event manager updates their event details'
);

select ok(
  tests.statement_succeeds_as(
    'authenticated',
    'ed000000-0000-4000-8000-000000000002',
    $sql$
      update public.event_details
      set capacity = 999
      where listing_id = 'ed100000-0000-4000-8000-000000000013'
    $sql$
  ),
  'an unauthorized update is safely masked by row level security'
);

select is(
  (
    select capacity
    from public.event_details
    where listing_id = 'ed100000-0000-4000-8000-000000000013'
  ),
  250,
  'another promoter cannot change event details they do not manage'
);

select ok(
  tests.statement_succeeds_as(
    'authenticated',
    'ed000000-0000-4000-8000-000000000001',
    $sql$
      delete from public.event_details
      where listing_id = 'ed100000-0000-4000-8000-000000000013'
    $sql$
  ),
  'an event manager deletes their event details'
);

select is(
  (
    select count(*)::integer
    from public.listings listing
    where listing.type = 'evenement'
      and listing.status in ('en_attente', 'publie')
      and not exists (
        select 1
        from public.event_details detail
        where detail.listing_id = listing.id
      )
  ),
  0,
  'every event in review or publication has required event details'
);

select lives_ok(
  $sql$
    delete from public.listings
    where id = 'ed100000-0000-4000-8000-000000000010'
  $sql$,
  'deleting the parent listing still cascades its required event details'
);

select * from finish();
rollback;
