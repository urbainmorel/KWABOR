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

create or replace function tests.encode_catalog_v2_cursor(payload jsonb)
returns text
language sql
immutable
set search_path = ''
as $$
  select pg_catalog.replace(
    pg_catalog.replace(
      pg_catalog.encode(
        pg_catalog.convert_to(payload::text, 'UTF8'),
        'base64'
      ),
      pg_catalog.chr(10),
      ''
    ),
    pg_catalog.chr(13),
    ''
  );
$$;

select plan(92);

create temporary table explore_v2_clock (
  anchor_at timestamptz primary key
) on commit drop;

insert into explore_v2_clock (anchor_at)
values (date_trunc('second', statement_timestamp()));

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
  'e1000000-0000-4000-8000-000000000001',
  'authenticated',
  'authenticated',
  'explore-v2-owner@kwabor.test',
  '',
  statement_timestamp(),
  statement_timestamp(),
  statement_timestamp()
);

insert into public.cities (
  id,
  name,
  slug,
  country_code,
  latitude,
  longitude,
  enabled
)
values (
  'explore-v2-city',
  'Ville Explore V2',
  'explore-v2-city',
  'BJ',
  6.370300,
  2.391200,
  true
);

insert into public.categories (
  id,
  listing_type,
  subtype,
  name_key,
  default_listing_class,
  sort_order,
  detail_variant
)
values
  (
    'explore-v2-place',
    'lieu',
    'explore-v2-place',
    'category.explore_v2_place',
    'commercial',
    901,
    'place'
  ),
  (
    'explore-v2-establishment',
    'etablissement',
    'explore-v2-establishment',
    'category.explore_v2_establishment',
    'commercial',
    902,
    'food'
  ),
  (
    'explore-v2-event-rank',
    'evenement',
    'explore-v2-event-rank',
    'category.explore_v2_event_rank',
    'evenementiel',
    903,
    'event'
  ),
  (
    'explore-v2-event-window',
    'evenement',
    'explore-v2-event-window',
    'category.explore_v2_event_window',
    'evenementiel',
    904,
    'event'
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
  views_count,
  likes_count,
  sponsored_until,
  created_at,
  published_at
)
values
  (
    'e2000000-0000-4000-8000-000000000101',
    'lieu',
    'explore-v2-place',
    'commercial',
    'explore-v2-place',
    null,
    null,
    'publie',
    'Place popularity views',
    'explore-v2-place-views',
    'Fixture transactionnelle Explore V2 pour vérifier le classement public par popularité.',
    'explore-v2-city',
    'aucune',
    100,
    0,
    (select anchor_at + interval '20 days' from explore_v2_clock),
    '2026-07-01 00:00:00+00',
    '2026-07-01 00:00:00+00'
  ),
  (
    'e2000000-0000-4000-8000-000000000102',
    'lieu',
    'explore-v2-place',
    'commercial',
    'explore-v2-place',
    null,
    null,
    'publie',
    'Place popularity older tie',
    'explore-v2-place-older-tie',
    'Fixture transactionnelle Explore V2 pour vérifier le classement public par popularité.',
    'explore-v2-city',
    'aucune',
    50,
    10,
    null,
    '2026-07-02 00:00:00+00',
    '2026-07-02 00:00:00+00'
  ),
  (
    'e2000000-0000-4000-8000-000000000103',
    'lieu',
    'explore-v2-place',
    'commercial',
    'explore-v2-place',
    null,
    null,
    'publie',
    'Place popularity likes tie',
    'explore-v2-place-likes-tie',
    'Fixture transactionnelle Explore V2 pour vérifier le classement public par popularité.',
    'explore-v2-city',
    'aucune',
    90,
    2,
    null,
    '2026-07-02 00:00:00+00',
    '2026-07-02 00:00:00+00'
  ),
  (
    'e2000000-0000-4000-8000-000000000104',
    'lieu',
    'explore-v2-place',
    'commercial',
    'explore-v2-place',
    null,
    null,
    'publie',
    'Place popularity bigint',
    'explore-v2-place-bigint',
    'Fixture transactionnelle Explore V2 pour vérifier le calcul bigint sans débordement.',
    'explore-v2-city',
    'aucune',
    2147483647,
    2147483647,
    null,
    '2026-07-01 00:00:00+00',
    '2026-07-01 00:00:00+00'
  ),
  (
    'e2000000-0000-4000-8000-000000000105',
    'lieu',
    'explore-v2-place',
    'commercial',
    'explore-v2-place',
    null,
    null,
    'publie',
    'Place popularity below tie',
    'explore-v2-place-below-tie',
    'Fixture transactionnelle Explore V2 pour vérifier le classement public par popularité.',
    'explore-v2-city',
    'aucune',
    99,
    0,
    null,
    '2026-07-04 00:00:00+00',
    '2026-07-04 00:00:00+00'
  ),
  (
    'e2000000-0000-4000-8000-000000000106',
    'lieu',
    'explore-v2-place',
    'commercial',
    'explore-v2-place',
    null,
    null,
    'publie',
    'Place popularity recent tie one',
    'explore-v2-place-recent-tie-one',
    'Fixture transactionnelle Explore V2 pour vérifier le départage par publication et UUID.',
    'explore-v2-city',
    'aucune',
    50,
    10,
    null,
    '2026-07-03 00:00:00+00',
    '2026-07-03 00:00:00+00'
  ),
  (
    'e2000000-0000-4000-8000-000000000107',
    'lieu',
    'explore-v2-place',
    'commercial',
    'explore-v2-place',
    null,
    null,
    'publie',
    'Place popularity recent tie two',
    'explore-v2-place-recent-tie-two',
    'Fixture transactionnelle Explore V2 pour vérifier le départage par publication et UUID.',
    'explore-v2-city',
    'aucune',
    50,
    10,
    null,
    '2026-07-03 00:00:00+00',
    '2026-07-03 00:00:00+00'
  ),
  (
    'e2000000-0000-4000-8000-000000000199',
    'lieu',
    'explore-v2-place',
    'commercial',
    'explore-v2-place',
    'e1000000-0000-4000-8000-000000000001',
    'e1000000-0000-4000-8000-000000000001',
    'brouillon',
    'Place private draft',
    'explore-v2-place-private-draft',
    'Fixture transactionnelle privée qui ne doit jamais sortir du contrat catalogue public.',
    'explore-v2-city',
    'aucune',
    2147483647,
    2147483647,
    null,
    '2026-07-05 00:00:00+00',
    null
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
  city_id,
  price_from_xof,
  price_unit,
  views_count,
  likes_count,
  sponsored_until,
  created_at,
  published_at
)
values
  (
    'e2100000-0000-4000-8000-000000000001',
    'etablissement',
    'explore-v2-establishment',
    'commercial',
    'explore-v2-establishment',
    'publie',
    'Establishment active sponsor one',
    'explore-v2-establishment-sponsor-one',
    'Fixture transactionnelle Explore V2 pour vérifier le plafond sponsorisé serveur global.',
    'explore-v2-city',
    1000,
    'consommation',
    900,
    0,
    (select anchor_at + interval '1 day' from explore_v2_clock),
    '2026-07-01 00:00:00+00',
    '2026-07-01 00:00:00+00'
  ),
  (
    'e2100000-0000-4000-8000-000000000002',
    'etablissement',
    'explore-v2-establishment',
    'commercial',
    'explore-v2-establishment',
    'publie',
    'Establishment active sponsor two',
    'explore-v2-establishment-sponsor-two',
    'Fixture transactionnelle Explore V2 pour vérifier le plafond sponsorisé serveur global.',
    'explore-v2-city',
    2000,
    'consommation',
    800,
    0,
    (select anchor_at + interval '20 days' from explore_v2_clock),
    '2026-07-02 00:00:00+00',
    '2026-07-02 00:00:00+00'
  ),
  (
    'e2100000-0000-4000-8000-000000000003',
    'etablissement',
    'explore-v2-establishment',
    'commercial',
    'explore-v2-establishment',
    'publie',
    'Establishment capped sponsor three',
    'explore-v2-establishment-sponsor-three',
    'Fixture transactionnelle Explore V2 pour vérifier le plafonnement après deux placements.',
    'explore-v2-city',
    3000,
    'consommation',
    700,
    0,
    (select anchor_at + interval '30 days' from explore_v2_clock),
    '2026-07-03 00:00:00+00',
    '2026-07-03 00:00:00+00'
  ),
  (
    'e2100000-0000-4000-8000-000000000004',
    'etablissement',
    'explore-v2-establishment',
    'commercial',
    'explore-v2-establishment',
    'publie',
    'Establishment capped sponsor four',
    'explore-v2-establishment-sponsor-four',
    'Fixture transactionnelle Explore V2 pour vérifier le plafonnement après deux placements.',
    'explore-v2-city',
    4000,
    'consommation',
    600,
    0,
    (select anchor_at + interval '40 days' from explore_v2_clock),
    '2026-07-04 00:00:00+00',
    '2026-07-04 00:00:00+00'
  ),
  (
    'e2100000-0000-4000-8000-000000000005',
    'etablissement',
    'explore-v2-establishment',
    'commercial',
    'explore-v2-establishment',
    'publie',
    'Establishment expired sponsor',
    'explore-v2-establishment-expired-sponsor',
    'Fixture transactionnelle Explore V2 pour vérifier qu’un sponsoring expiré reste organique.',
    'explore-v2-city',
    5000,
    'consommation',
    1000,
    0,
    (select anchor_at - interval '1 day' from explore_v2_clock),
    '2026-07-05 00:00:00+00',
    '2026-07-05 00:00:00+00'
  ),
  (
    'e2100000-0000-4000-8000-000000000006',
    'etablissement',
    'explore-v2-establishment',
    'commercial',
    'explore-v2-establishment',
    'publie',
    'Establishment organic six',
    'explore-v2-establishment-organic-six',
    'Fixture transactionnelle Explore V2 pour vérifier l’ordre organique après les sponsors.',
    'explore-v2-city',
    6000,
    'consommation',
    650,
    0,
    null,
    '2026-07-06 00:00:00+00',
    '2026-07-06 00:00:00+00'
  ),
  (
    'e2100000-0000-4000-8000-000000000007',
    'etablissement',
    'explore-v2-establishment',
    'commercial',
    'explore-v2-establishment',
    'publie',
    'Establishment unknown price',
    'explore-v2-establishment-unknown-price',
    'Fixture transactionnelle Explore V2 pour vérifier la sémantique d’un prix XOF inconnu.',
    'explore-v2-city',
    null,
    'aucune',
    550,
    0,
    null,
    '2026-07-07 00:00:00+00',
    '2026-07-07 00:00:00+00'
  ),
  (
    'e2100000-0000-4000-8000-000000000008',
    'etablissement',
    'explore-v2-establishment',
    'commercial',
    'explore-v2-establishment',
    'publie',
    'Establishment organic eight',
    'explore-v2-establishment-organic-eight',
    'Fixture transactionnelle Explore V2 pour vérifier les bornes de prix XOF inclusives.',
    'explore-v2-city',
    2000,
    'consommation',
    450,
    0,
    null,
    '2026-07-08 00:00:00+00',
    '2026-07-08 00:00:00+00'
  ),
  (
    'e2100000-0000-4000-8000-000000000009',
    'etablissement',
    'explore-v2-establishment',
    'commercial',
    'explore-v2-establishment',
    'publie',
    'Establishment organic nine',
    'explore-v2-establishment-organic-nine',
    'Fixture transactionnelle Explore V2 pour vérifier les bornes de prix XOF inclusives.',
    'explore-v2-city',
    7000,
    'consommation',
    350,
    0,
    null,
    '2026-07-09 00:00:00+00',
    '2026-07-09 00:00:00+00'
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
  address,
  lat,
  lng,
  price_unit,
  views_count,
  likes_count,
  sponsored_until,
  created_at,
  published_at
)
values
  (
    'e2200000-0000-4000-8000-000000000001',
    'evenement',
    'explore-v2-event-rank',
    'evenementiel',
    'explore-v2-event-rank',
    null,
    null,
    'brouillon',
    'Event ongoing closest',
    'explore-v2-event-ongoing-closest',
    'Fixture transactionnelle Explore V2 pour vérifier les phases temporelles demi-ouvertes.',
    'explore-v2-city',
    'Cotonou',
    6.370300,
    2.391200,
    'aucune',
    10,
    0,
    (select anchor_at + interval '10 days' from explore_v2_clock),
    '2026-07-01 00:00:00+00',
    null
  ),
  (
    'e2200000-0000-4000-8000-000000000002',
    'evenement',
    'explore-v2-event-rank',
    'evenementiel',
    'explore-v2-event-rank',
    null,
    null,
    'brouillon',
    'Event ongoing farther',
    'explore-v2-event-ongoing-farther',
    'Fixture transactionnelle Explore V2 pour vérifier les phases temporelles demi-ouvertes.',
    'explore-v2-city',
    'Cotonou',
    6.370300,
    2.391200,
    'aucune',
    20,
    0,
    null,
    '2026-07-02 00:00:00+00',
    null
  ),
  (
    'e2200000-0000-4000-8000-000000000003',
    'evenement',
    'explore-v2-event-rank',
    'evenementiel',
    'explore-v2-event-rank',
    null,
    null,
    'brouillon',
    'Event upcoming point closest',
    'explore-v2-event-upcoming-closest',
    'Fixture transactionnelle Explore V2 pour vérifier un événement ponctuel à venir.',
    'explore-v2-city',
    'Cotonou',
    6.370300,
    2.391200,
    'aucune',
    30,
    0,
    null,
    '2026-07-03 00:00:00+00',
    null
  ),
  (
    'e2200000-0000-4000-8000-000000000004',
    'evenement',
    'explore-v2-event-rank',
    'evenementiel',
    'explore-v2-event-rank',
    null,
    null,
    'brouillon',
    'Event upcoming farther',
    'explore-v2-event-upcoming-farther',
    'Fixture transactionnelle Explore V2 pour vérifier les phases temporelles demi-ouvertes.',
    'explore-v2-city',
    'Cotonou',
    6.370300,
    2.391200,
    'aucune',
    40,
    0,
    null,
    '2026-07-04 00:00:00+00',
    null
  ),
  (
    'e2200000-0000-4000-8000-000000000005',
    'evenement',
    'explore-v2-event-rank',
    'evenementiel',
    'explore-v2-event-rank',
    null,
    null,
    'brouillon',
    'Event ended point closest',
    'explore-v2-event-ended-closest',
    'Fixture transactionnelle Explore V2 pour vérifier un événement ponctuel terminé.',
    'explore-v2-city',
    'Cotonou',
    6.370300,
    2.391200,
    'aucune',
    50,
    0,
    null,
    '2026-07-05 00:00:00+00',
    null
  ),
  (
    'e2200000-0000-4000-8000-000000000006',
    'evenement',
    'explore-v2-event-rank',
    'evenementiel',
    'explore-v2-event-rank',
    null,
    null,
    'brouillon',
    'Event ended farther',
    'explore-v2-event-ended-farther',
    'Fixture transactionnelle Explore V2 pour vérifier les phases temporelles demi-ouvertes.',
    'explore-v2-city',
    'Cotonou',
    6.370300,
    2.391200,
    'aucune',
    60,
    0,
    null,
    '2026-07-06 00:00:00+00',
    null
  ),
  (
    'e2200000-0000-4000-8000-000000000099',
    'evenement',
    'explore-v2-event-rank',
    'evenementiel',
    'explore-v2-event-rank',
    'e1000000-0000-4000-8000-000000000001',
    'e1000000-0000-4000-8000-000000000001',
    'brouillon',
    'Event private draft',
    'explore-v2-event-private-draft',
    'Fixture transactionnelle privée qui ne doit jamais sortir du contrat catalogue public.',
    'explore-v2-city',
    'Cotonou',
    6.370300,
    2.391200,
    'aucune',
    2147483647,
    2147483647,
    null,
    '2026-07-07 00:00:00+00',
    null
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
  city_id,
  address,
  lat,
  lng,
  price_unit,
  views_count,
  likes_count,
  created_at,
  published_at
)
select
  fixture.id,
  'evenement'::public.listing_type,
  'explore-v2-event-window',
  'evenementiel'::public.listing_class,
  'explore-v2-event-window',
  'brouillon'::public.listing_status,
  fixture.name,
  fixture.slug,
  'Fixture transactionnelle Explore V2 pour vérifier les bornes exactes de fenêtre événementielle.',
  'explore-v2-city',
  'Cotonou',
  6.370300,
  2.391200,
  'aucune'::public.price_unit,
  fixture.views_count,
  0,
  fixture.created_at,
  null::timestamptz
from (
  values
    (
      'e2300000-0000-4000-8000-000000000001'::uuid,
      'Event window overlap left',
      'explore-v2-event-window-overlap-left',
      81,
      '2026-07-01 00:00:00+00'::timestamptz
    ),
    (
      'e2300000-0000-4000-8000-000000000002'::uuid,
      'Event window ending at start',
      'explore-v2-event-window-ending-at-start',
      82,
      '2026-07-02 00:00:00+00'::timestamptz
    ),
    (
      'e2300000-0000-4000-8000-000000000003'::uuid,
      'Event window starting at end',
      'explore-v2-event-window-starting-at-end',
      83,
      '2026-07-03 00:00:00+00'::timestamptz
    ),
    (
      'e2300000-0000-4000-8000-000000000004'::uuid,
      'Event point at window start',
      'explore-v2-event-point-at-window-start',
      84,
      '2026-07-04 00:00:00+00'::timestamptz
    ),
    (
      'e2300000-0000-4000-8000-000000000005'::uuid,
      'Event point at window end',
      'explore-v2-event-point-at-window-end',
      85,
      '2026-07-05 00:00:00+00'::timestamptz
    ),
    (
      'e2300000-0000-4000-8000-000000000006'::uuid,
      'Event point inside window',
      'explore-v2-event-point-inside-window',
      86,
      '2026-07-06 00:00:00+00'::timestamptz
    ),
    (
      'e2300000-0000-4000-8000-000000000007'::uuid,
      'Event spanning window',
      'explore-v2-event-spanning-window',
      87,
      '2026-07-07 00:00:00+00'::timestamptz
    ),
    (
      'e2300000-0000-4000-8000-000000000008'::uuid,
      'Event duration inside window',
      'explore-v2-event-duration-inside-window',
      88,
      '2026-07-08 00:00:00+00'::timestamptz
    )
) as fixture(id, name, slug, views_count, created_at);

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
    'e2200000-0000-4000-8000-000000000001',
    'explore-v2-event-rank',
    (select anchor_at - interval '10 days' from explore_v2_clock),
    (select anchor_at + interval '1 day' from explore_v2_clock),
    'Kwabor Test',
    'events@kwabor.test',
    'gratuit'
  ),
  (
    'e2200000-0000-4000-8000-000000000002',
    'explore-v2-event-rank',
    (select anchor_at - interval '5 days' from explore_v2_clock),
    (select anchor_at + interval '2 days' from explore_v2_clock),
    'Kwabor Test',
    'events@kwabor.test',
    'gratuit'
  ),
  (
    'e2200000-0000-4000-8000-000000000003',
    'explore-v2-event-rank',
    (select anchor_at + interval '1 day' from explore_v2_clock),
    null,
    'Kwabor Test',
    'events@kwabor.test',
    'gratuit'
  ),
  (
    'e2200000-0000-4000-8000-000000000004',
    'explore-v2-event-rank',
    (select anchor_at + interval '2 days' from explore_v2_clock),
    (select anchor_at + interval '3 days' from explore_v2_clock),
    'Kwabor Test',
    'events@kwabor.test',
    'gratuit'
  ),
  (
    'e2200000-0000-4000-8000-000000000005',
    'explore-v2-event-rank',
    (select anchor_at - interval '1 day' from explore_v2_clock),
    null,
    'Kwabor Test',
    'events@kwabor.test',
    'gratuit'
  ),
  (
    'e2200000-0000-4000-8000-000000000006',
    'explore-v2-event-rank',
    (select anchor_at - interval '3 days' from explore_v2_clock),
    (select anchor_at - interval '2 days' from explore_v2_clock),
    'Kwabor Test',
    'events@kwabor.test',
    'gratuit'
  ),
  (
    'e2200000-0000-4000-8000-000000000099',
    'explore-v2-event-rank',
    (select anchor_at + interval '1 day' from explore_v2_clock),
    null,
    'Kwabor Test',
    'events@kwabor.test',
    'gratuit'
  ),
  (
    'e2300000-0000-4000-8000-000000000001',
    'explore-v2-event-window',
    (select anchor_at + interval '29 days 23 hours' from explore_v2_clock),
    (select anchor_at + interval '30 days 1 hour' from explore_v2_clock),
    'Kwabor Test',
    'events@kwabor.test',
    'gratuit'
  ),
  (
    'e2300000-0000-4000-8000-000000000002',
    'explore-v2-event-window',
    (select anchor_at + interval '29 days' from explore_v2_clock),
    (select anchor_at + interval '30 days' from explore_v2_clock),
    'Kwabor Test',
    'events@kwabor.test',
    'gratuit'
  ),
  (
    'e2300000-0000-4000-8000-000000000003',
    'explore-v2-event-window',
    (select anchor_at + interval '31 days' from explore_v2_clock),
    (select anchor_at + interval '31 days 1 hour' from explore_v2_clock),
    'Kwabor Test',
    'events@kwabor.test',
    'gratuit'
  ),
  (
    'e2300000-0000-4000-8000-000000000004',
    'explore-v2-event-window',
    (select anchor_at + interval '30 days' from explore_v2_clock),
    null,
    'Kwabor Test',
    'events@kwabor.test',
    'gratuit'
  ),
  (
    'e2300000-0000-4000-8000-000000000005',
    'explore-v2-event-window',
    (select anchor_at + interval '31 days' from explore_v2_clock),
    null,
    'Kwabor Test',
    'events@kwabor.test',
    'gratuit'
  ),
  (
    'e2300000-0000-4000-8000-000000000006',
    'explore-v2-event-window',
    (select anchor_at + interval '30 days 12 hours' from explore_v2_clock),
    (select anchor_at + interval '30 days 12 hours' from explore_v2_clock),
    'Kwabor Test',
    'events@kwabor.test',
    'gratuit'
  ),
  (
    'e2300000-0000-4000-8000-000000000007',
    'explore-v2-event-window',
    (select anchor_at + interval '20 days' from explore_v2_clock),
    (select anchor_at + interval '40 days' from explore_v2_clock),
    'Kwabor Test',
    'events@kwabor.test',
    'gratuit'
  ),
  (
    'e2300000-0000-4000-8000-000000000008',
    'explore-v2-event-window',
    (select anchor_at + interval '30 days 2 hours' from explore_v2_clock),
    (select anchor_at + interval '30 days 4 hours' from explore_v2_clock),
    'Kwabor Test',
    'events@kwabor.test',
    'gratuit'
  );

update public.listings
set status = 'publie',
    published_at = created_at
where category_id in ('explore-v2-event-rank', 'explore-v2-event-window')
  and id <> 'e2200000-0000-4000-8000-000000000099';

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
    'e2400000-0000-4000-8000-000000000001',
    'e2000000-0000-4000-8000-000000000104',
    'https://media.kwabor.test/explore-v2-fallback.jpg',
    'Image secondaire Explore V2',
    0,
    false
  ),
  (
    'e2400000-0000-4000-8000-000000000002',
    'e2000000-0000-4000-8000-000000000104',
    'https://media.kwabor.test/explore-v2-cover.jpg',
    'Image de couverture Explore V2',
    5,
    true
  );

select ok(
  to_regprocedure(
    'public.list_catalog_summaries_v2(text,text,text,text,text,integer,integer,timestamptz,timestamptz,text,integer)'
  ) is not null,
  'Explore V2 RPC exists with its unambiguous versioned identity'
);

select is(
  (
    select procedure_definition.proargnames[1:11]
    from pg_catalog.pg_proc as procedure_definition
    where procedure_definition.oid =
      'public.list_catalog_summaries_v2(text,text,text,text,text,integer,integer,timestamptz,timestamptz,text,integer)'::regprocedure
  ),
  array[
    'p_listing_type',
    'p_city_id',
    'p_category_id',
    'p_listing_class',
    'p_sort',
    'p_price_min_xof',
    'p_price_max_xof',
    'p_event_window_start',
    'p_event_window_end',
    'p_cursor',
    'p_limit'
  ]::text[],
  'Explore V2 input names and order are stable'
);

select is(
  (
    select procedure_definition.proargnames[12:32]
    from pg_catalog.pg_proc as procedure_definition
    where procedure_definition.oid =
      'public.list_catalog_summaries_v2(text,text,text,text,text,integer,integer,timestamptz,timestamptz,text,integer)'::regprocedure
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
    'cover_image_alt',
    'price_from_xof',
    'rating_avg',
    'views_count',
    'likes_count',
    'verified',
    'sponsored_until',
    'event_start_at',
    'event_end_at',
    'is_event_ended',
    'is_sponsored_placement',
    'snapshot_at',
    'row_cursor'
  ]::text[],
  'Explore V2 exposes the exact flat mobile card projection'
);

select is(
  (
    select procedure_definition.pronargdefaults
    from pg_catalog.pg_proc as procedure_definition
    where procedure_definition.oid =
      'public.list_catalog_summaries_v2(text,text,text,text,text,integer,integer,timestamptz,timestamptz,text,integer)'::regprocedure
  ),
  10::smallint,
  'listing type is the only required input'
);

select ok(
  (
    select procedure_definition.proretset
    from pg_catalog.pg_proc as procedure_definition
    where procedure_definition.oid =
      'public.list_catalog_summaries_v2(text,text,text,text,text,integer,integer,timestamptz,timestamptz,text,integer)'::regprocedure
  ),
  'Explore V2 returns a row set'
);

select is(
  (
    select procedure_definition.provolatile
    from pg_catalog.pg_proc as procedure_definition
    where procedure_definition.oid =
      'public.list_catalog_summaries_v2(text,text,text,text,text,integer,integer,timestamptz,timestamptz,text,integer)'::regprocedure
  ),
  's'::"char",
  'Explore V2 is STABLE for one statement snapshot'
);

select ok(
  not (
    select procedure_definition.prosecdef
    from pg_catalog.pg_proc as procedure_definition
    where procedure_definition.oid =
      'public.list_catalog_summaries_v2(text,text,text,text,text,integer,integer,timestamptz,timestamptz,text,integer)'::regprocedure
  ),
  'Explore V2 is SECURITY INVOKER and keeps RLS authoritative'
);

select is(
  (
    select procedure_definition.proconfig
    from pg_catalog.pg_proc as procedure_definition
    where procedure_definition.oid =
      'public.list_catalog_summaries_v2(text,text,text,text,text,integer,integer,timestamptz,timestamptz,text,integer)'::regprocedure
  ),
  array['search_path=""']::text[],
  'Explore V2 has an empty fixed search_path'
);

select ok(
  has_function_privilege(
    'anon',
    'public.list_catalog_summaries_v2(text,text,text,text,text,integer,integer,timestamptz,timestamptz,text,integer)',
    'EXECUTE'
  )
  and has_function_privilege(
    'authenticated',
    'public.list_catalog_summaries_v2(text,text,text,text,text,integer,integer,timestamptz,timestamptz,text,integer)',
    'EXECUTE'
  ),
  'anonymous and authenticated clients can execute Explore V2'
);

select ok(
  not has_function_privilege(
    'service_role',
    'public.list_catalog_summaries_v2(text,text,text,text,text,integer,integer,timestamptz,timestamptz,text,integer)',
    'EXECUTE'
  ),
  'service_role receives no unnecessary direct Explore V2 grant'
);

select is(
  (
    select pg_catalog.string_agg(
      pg_catalog.coalesce(grantee.rolname, 'PUBLIC'),
      ','
      order by pg_catalog.coalesce(grantee.rolname, 'PUBLIC')
    )
    from pg_catalog.pg_proc as procedure_definition
    cross join lateral pg_catalog.aclexplode(procedure_definition.proacl) as privilege_definition
    left join pg_catalog.pg_roles as grantee
      on grantee.oid = privilege_definition.grantee
    where procedure_definition.oid =
      'public.list_catalog_summaries_v2(text,text,text,text,text,integer,integer,timestamptz,timestamptz,text,integer)'::regprocedure
      and privilege_definition.privilege_type = 'EXECUTE'
      and privilege_definition.grantee <> procedure_definition.proowner
  ),
  'anon,authenticated',
  'only anon and authenticated have direct client EXECUTE grants'
);

select ok(
  to_regprocedure(
    'public.list_catalog_summaries(text,text,text,text,text,text,integer)'
  ) is not null,
  'the Store-compatible V1 RPC identity remains available'
);

select is(
  (
    select procedure_definition.proargnames[1:7]
    from pg_catalog.pg_proc as procedure_definition
    where procedure_definition.oid =
      'public.list_catalog_summaries(text,text,text,text,text,text,integer)'::regprocedure
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
  'the V1 input contract remains unchanged'
);

select is(
  (
    select (
      pg_catalog.convert_from(
        pg_catalog.decode(row_cursor, 'base64'),
        'UTF8'
      )::jsonb ->> 'v'
    )
    from public.list_catalog_summaries(
      p_listing_type => 'lieu',
      p_limit => 1
    )
    limit 1
  ),
  '1',
  'the preserved V1 still emits V1 cursors'
);

select is(
  tests.count_as(
    'anon',
    null,
    $sql$
      select *
      from public.list_catalog_summaries_v2(
        'lieu',
        p_city_id => 'explore-v2-city',
        p_category_id => 'explore-v2-place',
        p_limit => 50
      )
    $sql$
  ),
  7::bigint,
  'anonymous callers receive every and only published place fixture'
);

select is(
  tests.count_as(
    'authenticated',
    'e1000000-0000-4000-8000-000000000001',
    $sql$
      select id
      from public.listings
      where id = 'e2000000-0000-4000-8000-000000000199'
    $sql$
  ),
  1::bigint,
  'the authenticated owner can directly read their draft under table RLS'
);

select is(
  tests.count_as(
    'authenticated',
    'e1000000-0000-4000-8000-000000000001',
    $sql$
      select *
      from public.list_catalog_summaries_v2(
        'lieu',
        p_city_id => 'explore-v2-city',
        p_category_id => 'explore-v2-place',
        p_limit => 50
      )
      where id = 'e2000000-0000-4000-8000-000000000199'
    $sql$
  ),
  0::bigint,
  'the public RPC never leaks a managed draft despite broader owner RLS'
);

select is(
  tests.count_as(
    'anon',
    null,
    $sql$
      select *
      from public.list_catalog_summaries_v2(
        'evenement',
        p_city_id => 'explore-v2-city',
        p_category_id => 'explore-v2-event-rank',
        p_limit => 50
      )
    $sql$
  ),
  6::bigint,
  'anonymous event reads obey published listing and event-detail RLS'
);

select is(
  (
    select cover_image_url || '|' || cover_image_alt
    from public.list_catalog_summaries_v2(
      'lieu',
      p_city_id => 'explore-v2-city',
      p_category_id => 'explore-v2-place',
      p_limit => 50
    )
    where id = 'e2000000-0000-4000-8000-000000000104'
  ),
  'https://media.kwabor.test/explore-v2-cover.jpg|Image de couverture Explore V2',
  'the deterministic cover projection keeps URL and accessibility alt together'
);

select ok(
  (
    select
      event_start_at is null
      and event_end_at is null
      and not is_event_ended
      and not is_sponsored_placement
    from public.list_catalog_summaries_v2(
      'lieu',
      p_city_id => 'explore-v2-city',
      p_category_id => 'explore-v2-place',
      p_limit => 50
    )
    where id = 'e2000000-0000-4000-8000-000000000101'
  ),
  'a commercially classified place is neither an event nor a sponsored placement'
);

select is(
  (
    select pg_catalog.string_agg(result.id::text, ',' order by result.ordinality)
    from public.list_catalog_summaries_v2(
      'lieu',
      p_city_id => 'explore-v2-city',
      p_category_id => 'explore-v2-place',
      p_limit => 50
    ) with ordinality as result
  ),
  pg_catalog.concat_ws(
    ',',
    'e2000000-0000-4000-8000-000000000104',
    'e2000000-0000-4000-8000-000000000107',
    'e2000000-0000-4000-8000-000000000106',
    'e2000000-0000-4000-8000-000000000102',
    'e2000000-0000-4000-8000-000000000103',
    'e2000000-0000-4000-8000-000000000101',
    'e2000000-0000-4000-8000-000000000105'
  ),
  'default place ranking uses bigint popularity then likes, publication and UUID ties'
);

select is(
  (
    select pg_catalog.string_agg(result.id::text, ',' order by result.ordinality)
    from public.list_catalog_summaries_v2(
      'lieu',
      p_city_id => 'explore-v2-city',
      p_category_id => 'explore-v2-place',
      p_sort => 'popularity',
      p_limit => 50
    ) with ordinality as result
  ),
  (
    select pg_catalog.string_agg(result.id::text, ',' order by result.ordinality)
    from public.list_catalog_summaries_v2(
      'lieu',
      p_city_id => 'explore-v2-city',
      p_category_id => 'explore-v2-place',
      p_sort => 'default',
      p_limit => 50
    ) with ordinality as result
  ),
  'default and explicit popularity are canonically equivalent for places'
);

create temporary table explore_v2_place_page
on commit drop
as
select result.*
from public.list_catalog_summaries_v2(
  'lieu',
  p_city_id => 'explore-v2-city',
  p_category_id => 'explore-v2-place',
  p_limit => 2
) with ordinality as result;

create temporary table explore_v2_cursor_seed
on commit drop
as
select
  row_cursor,
  pg_catalog.convert_from(
    pg_catalog.decode(row_cursor, 'base64'),
    'UTF8'
  )::jsonb as payload
from explore_v2_place_page
where ordinality = 1;

select is(
  (
    select (payload ->> 'popularity')::bigint
    from explore_v2_cursor_seed
  ),
  12884901882::bigint,
  'popularity widens both operands to bigint before applying the like weight'
);

select is(
  (
    select pg_catalog.array_agg(cursor_field.key_name order by cursor_field.key_name)
    from explore_v2_cursor_seed as cursor_seed
    cross join lateral pg_catalog.jsonb_object_keys(cursor_seed.payload)
      as cursor_field(key_name)
  ),
  array[
    'as_of',
    'contract',
    'distance',
    'fingerprint',
    'id',
    'likes',
    'phase',
    'popularity',
    'published_at',
    'sponsored',
    'v',
    'views'
  ]::text[],
  'the V2 cursor contains exactly its versioned keyset contract'
);

select ok(
  (
    select
      payload ->> 'v' = '2'
      and payload ->> 'contract' = 'catalog-summaries-v2'
      and payload ->> 'phase' = '0'
      and payload ->> 'distance' = '0'
      and not (payload ->> 'sponsored')::boolean
    from explore_v2_cursor_seed
  ),
  'a place cursor carries the canonical V2 popularity sort keys'
);

select ok(
  (
    select pg_catalog.bool_and(
      result.snapshot_at = (
        pg_catalog.convert_from(
          pg_catalog.decode(result.row_cursor, 'base64'),
          'UTF8'
        )::jsonb ->> 'as_of'
      )::timestamptz
    )
    from explore_v2_place_page as result
  ),
  'each row snapshot matches the snapshot embedded in its cursor'
);

select is(
  (
    select pg_catalog.string_agg(result.id::text, ',' order by result.ordinality)
    from public.list_catalog_summaries_v2(
      'evenement',
      p_city_id => 'explore-v2-city',
      p_category_id => 'explore-v2-event-rank',
      p_limit => 50
    ) with ordinality as result
  ),
  pg_catalog.concat_ws(
    ',',
    'e2200000-0000-4000-8000-000000000001',
    'e2200000-0000-4000-8000-000000000002',
    'e2200000-0000-4000-8000-000000000003',
    'e2200000-0000-4000-8000-000000000004',
    'e2200000-0000-4000-8000-000000000005',
    'e2200000-0000-4000-8000-000000000006'
  ),
  'default event ranking is ongoing, upcoming, ended, then nearest phase boundary'
);

select is(
  (
    select pg_catalog.string_agg(result.id::text, ',' order by result.ordinality)
    from public.list_catalog_summaries_v2(
      'evenement',
      p_city_id => 'explore-v2-city',
      p_category_id => 'explore-v2-event-rank',
      p_sort => 'temporal_proximity',
      p_limit => 50
    ) with ordinality as result
  ),
  (
    select pg_catalog.string_agg(result.id::text, ',' order by result.ordinality)
    from public.list_catalog_summaries_v2(
      'evenement',
      p_city_id => 'explore-v2-city',
      p_category_id => 'explore-v2-event-rank',
      p_sort => 'default',
      p_limit => 50
    ) with ordinality as result
  ),
  'default and explicit temporal proximity are canonically equivalent for events'
);

select is(
  (
    select pg_catalog.string_agg(result.id::text, ',' order by result.ordinality)
    from public.list_catalog_summaries_v2(
      'evenement',
      p_city_id => 'explore-v2-city',
      p_category_id => 'explore-v2-event-rank',
      p_sort => 'popularity',
      p_limit => 50
    ) with ordinality as result
  ),
  pg_catalog.concat_ws(
    ',',
    'e2200000-0000-4000-8000-000000000006',
    'e2200000-0000-4000-8000-000000000005',
    'e2200000-0000-4000-8000-000000000004',
    'e2200000-0000-4000-8000-000000000003',
    'e2200000-0000-4000-8000-000000000002',
    'e2200000-0000-4000-8000-000000000001'
  ),
  'events explicitly support the popularity strategy independent of temporal phase'
);

select is(
  (
    select pg_catalog.array_agg(is_event_ended order by id)
    from public.list_catalog_summaries_v2(
      'evenement',
      p_city_id => 'explore-v2-city',
      p_category_id => 'explore-v2-event-rank',
      p_limit => 50
    )
  ),
  array[false, false, false, false, true, true]::boolean[],
  'point events are upcoming before start and ended at or after their start'
);

select ok(
  not exists (
    select 1
    from public.list_catalog_summaries_v2(
      'evenement',
      p_city_id => 'explore-v2-city',
      p_category_id => 'explore-v2-event-rank',
      p_limit => 50
    )
    where is_sponsored_placement
  ),
  'an active sponsored_until never turns an event into a sponsored placement'
);

select is(
  (
    select pg_catalog.string_agg(result.id::text, ',' order by result.id)
    from public.list_catalog_summaries_v2(
      'evenement',
      p_city_id => 'explore-v2-city',
      p_category_id => 'explore-v2-event-window',
      p_event_window_start => (
        select anchor_at + interval '30 days'
        from explore_v2_clock
      ),
      p_event_window_end => (
        select anchor_at + interval '31 days'
        from explore_v2_clock
      ),
      p_limit => 50
    ) as result
  ),
  pg_catalog.concat_ws(
    ',',
    'e2300000-0000-4000-8000-000000000001',
    'e2300000-0000-4000-8000-000000000004',
    'e2300000-0000-4000-8000-000000000006',
    'e2300000-0000-4000-8000-000000000007',
    'e2300000-0000-4000-8000-000000000008'
  ),
  'event filtering uses raw half-open overlap and includes points only inside [start,end)'
);

select is(
  (
    select count(*)
    from public.list_catalog_summaries_v2(
      'etablissement',
      p_city_id => 'explore-v2-city',
      p_category_id => 'explore-v2-establishment',
      p_limit => 50
    )
    where price_from_xof is null
  ),
  1::bigint,
  'a listing with unknown price remains visible when no price filter is active'
);

select is(
  (
    select pg_catalog.string_agg(result.id::text, ',' order by result.ordinality)
    from public.list_catalog_summaries_v2(
      'etablissement',
      p_city_id => 'explore-v2-city',
      p_category_id => 'explore-v2-establishment',
      p_price_min_xof => 2000,
      p_price_max_xof => 2000,
      p_limit => 50
    ) with ordinality as result
  ),
  pg_catalog.concat_ws(
    ',',
    'e2100000-0000-4000-8000-000000000002',
    'e2100000-0000-4000-8000-000000000008'
  ),
  'equal inclusive price bounds retain every exact XOF match'
);

select is(
  (
    select count(*)
    from public.list_catalog_summaries_v2(
      'etablissement',
      p_city_id => 'explore-v2-city',
      p_category_id => 'explore-v2-establishment',
      p_price_min_xof => 6000,
      p_limit => 50
    )
  ),
  2::bigint,
  'a minimum-only price bound is inclusive and excludes unknown prices'
);

select is(
  (
    select count(*)
    from public.list_catalog_summaries_v2(
      'etablissement',
      p_city_id => 'explore-v2-city',
      p_category_id => 'explore-v2-establishment',
      p_price_max_xof => 1000,
      p_limit => 50
    )
  ),
  1::bigint,
  'a maximum-only price bound is inclusive and excludes unknown prices'
);

select is(
  (
    select pg_catalog.string_agg(result.id::text, ',' order by result.ordinality)
    from public.list_catalog_summaries_v2(
      'etablissement',
      p_city_id => 'explore-v2-city',
      p_category_id => 'explore-v2-establishment',
      p_limit => 50
    ) with ordinality as result
  ),
  pg_catalog.concat_ws(
    ',',
    'e2100000-0000-4000-8000-000000000001',
    'e2100000-0000-4000-8000-000000000002',
    'e2100000-0000-4000-8000-000000000005',
    'e2100000-0000-4000-8000-000000000003',
    'e2100000-0000-4000-8000-000000000006',
    'e2100000-0000-4000-8000-000000000004',
    'e2100000-0000-4000-8000-000000000007',
    'e2100000-0000-4000-8000-000000000008',
    'e2100000-0000-4000-8000-000000000009'
  ),
  'two highest-popularity eligible establishments lead, then every remaining row is organic'
);

select is(
  (
    select pg_catalog.array_agg(result.id order by result.ordinality)
      filter (where result.is_sponsored_placement)
    from public.list_catalog_summaries_v2(
      'etablissement',
      p_city_id => 'explore-v2-city',
      p_category_id => 'explore-v2-establishment',
      p_limit => 50
    ) with ordinality as result
  ),
  array[
    'e2100000-0000-4000-8000-000000000001'::uuid,
    'e2100000-0000-4000-8000-000000000002'::uuid
  ],
  'sponsor selection follows organic popularity rather than later expiration'
);

select ok(
  not exists (
    select 1
    from public.list_catalog_summaries_v2(
      'etablissement',
      p_city_id => 'explore-v2-city',
      p_category_id => 'explore-v2-establishment',
      p_limit => 50
    )
    where id in (
      'e2100000-0000-4000-8000-000000000003',
      'e2100000-0000-4000-8000-000000000004',
      'e2100000-0000-4000-8000-000000000005'
    )
      and is_sponsored_placement
  ),
  'capped active and expired sponsorships remain visible without a sponsored badge'
);

select ok(
  not exists (
    select 1
    from public.categories as category
    where category.listing_type = 'etablissement'
      and category.default_listing_class <> 'commercial'
  )
  and not exists (
    select 1
    from public.list_catalog_summaries_v2(
      'etablissement',
      p_city_id => 'explore-v2-city',
      p_category_id => 'explore-v2-establishment',
      p_listing_class => 'patrimonial',
      p_limit => 50
    )
    where is_sponsored_placement
  ),
  'establishment taxonomy and the scoped RPC admit no non-commercial sponsored placement'
);

select is(
  (
    select pg_catalog.array_agg(result.id order by result.ordinality)
      filter (where result.is_sponsored_placement)
    from public.list_catalog_summaries_v2(
      'etablissement',
      p_city_id => 'explore-v2-city',
      p_category_id => 'explore-v2-establishment',
      p_price_min_xof => 2000,
      p_price_max_xof => 7000,
      p_limit => 50
    ) with ordinality as result
  ),
  array[
    'e2100000-0000-4000-8000-000000000002'::uuid,
    'e2100000-0000-4000-8000-000000000003'::uuid
  ],
  'price filtering happens before the two sponsored candidates are selected'
);

select is(
  (
    select count(*)
    from public.list_catalog_summaries_v2(
      'etablissement',
      p_city_id => 'explore-v2-city',
      p_category_id => 'explore-v2-establishment',
      p_price_min_xof => 2000,
      p_price_max_xof => 7000,
      p_limit => 50
    )
  ),
  7::bigint,
  'bounded price filtering excludes both the out-of-range sponsor and null price'
);

create temporary table explore_v2_raw_page (
  id uuid not null,
  is_sponsored_placement boolean not null,
  snapshot_at timestamptz not null,
  row_cursor text not null,
  page_ordinal integer not null
) on commit drop;

create temporary table explore_v2_pagination (
  page_limit integer not null,
  global_ordinal integer not null,
  id uuid not null,
  is_sponsored_placement boolean not null,
  snapshot_at timestamptz not null,
  primary key (page_limit, global_ordinal)
) on commit drop;

do $$
declare
  v_page_size integer;
  v_cursor text;
  v_raw_count integer;
  v_kept_before integer;
  v_page_guard integer;
begin
  foreach v_page_size in array array[1, 2, 5, 7]
  loop
    v_cursor := null;
    v_kept_before := 0;
    v_page_guard := 0;

    loop
      truncate table explore_v2_raw_page;

      insert into explore_v2_raw_page (
        id,
        is_sponsored_placement,
        snapshot_at,
        row_cursor,
        page_ordinal
      )
      select
        page.id,
        page.is_sponsored_placement,
        page.snapshot_at,
        page.row_cursor,
        page.ordinality::integer
      from public.list_catalog_summaries_v2(
        'etablissement',
        p_city_id => 'explore-v2-city',
        p_category_id => 'explore-v2-establishment',
        p_cursor => v_cursor,
        p_limit => v_page_size
      ) with ordinality as page;

      get diagnostics v_raw_count = row_count;
      exit when v_raw_count = 0;

      insert into explore_v2_pagination (
        page_limit,
        global_ordinal,
        id,
        is_sponsored_placement,
        snapshot_at
      )
      select
        v_page_size,
        v_kept_before + raw_page.page_ordinal,
        raw_page.id,
        raw_page.is_sponsored_placement,
        raw_page.snapshot_at
      from explore_v2_raw_page as raw_page
      where raw_page.page_ordinal <= v_page_size
      order by raw_page.page_ordinal;

      exit when v_raw_count <= v_page_size;

      select raw_page.row_cursor
      into strict v_cursor
      from explore_v2_raw_page as raw_page
      where raw_page.page_ordinal = v_page_size;

      v_kept_before := v_kept_before + v_page_size;
      v_page_guard := v_page_guard + 1;

      if v_page_guard > 20 then
        raise exception 'Explore V2 pagination did not converge';
      end if;
    end loop;
  end loop;
end;
$$;

select results_eq(
  $sql$
    select
      page_limit,
      count(*)::bigint,
      count(distinct id)::bigint
    from explore_v2_pagination
    group by page_limit
    order by page_limit
  $sql$,
  $sql$
    values
      (1, 9::bigint, 9::bigint),
      (2, 9::bigint, 9::bigint),
      (5, 9::bigint, 9::bigint),
      (7, 9::bigint, 9::bigint)
  $sql$,
  'limits 1, 2, 5 and 7 paginate all establishments without duplicates or omissions'
);

select ok(
  (
    select pg_catalog.bool_and(
      paged.actual_order = pg_catalog.concat_ws(
        ',',
        'e2100000-0000-4000-8000-000000000001',
        'e2100000-0000-4000-8000-000000000002',
        'e2100000-0000-4000-8000-000000000005',
        'e2100000-0000-4000-8000-000000000003',
        'e2100000-0000-4000-8000-000000000006',
        'e2100000-0000-4000-8000-000000000004',
        'e2100000-0000-4000-8000-000000000007',
        'e2100000-0000-4000-8000-000000000008',
        'e2100000-0000-4000-8000-000000000009'
      )
    )
    from (
      select
        page_limit,
        pg_catalog.string_agg(id::text, ',' order by global_ordinal) as actual_order
      from explore_v2_pagination
      group by page_limit
    ) as paged
  ),
  'every tested page size reconstructs the same strict server order'
);

select results_eq(
  $sql$
    select
      page_limit,
      count(*) filter (where is_sponsored_placement)::bigint,
      array_agg(global_ordinal order by global_ordinal)
        filter (where is_sponsored_placement)
    from explore_v2_pagination
    group by page_limit
    order by page_limit
  $sql$,
  $sql$
    values
      (1, 2::bigint, array[1, 2]::integer[]),
      (2, 2::bigint, array[1, 2]::integer[]),
      (5, 2::bigint, array[1, 2]::integer[]),
      (7, 2::bigint, array[1, 2]::integer[])
  $sql$,
  'the global two-sponsor cap does not restart at a page boundary'
);

select ok(
  (
    select pg_catalog.bool_and(snapshot_chain.is_stable)
    from (
      select min(snapshot_at) = max(snapshot_at) as is_stable
      from explore_v2_pagination
      group by page_limit
    ) as snapshot_chain
  ),
  'every cursor chain preserves one server snapshot across all pages'
);

select results_eq(
  $sql$
    select requested.page_limit, count(page.id)::bigint
    from (values (1), (2), (5), (7)) as requested(page_limit)
    cross join lateral public.list_catalog_summaries_v2(
      'etablissement',
      p_city_id => 'explore-v2-city',
      p_category_id => 'explore-v2-establishment',
      p_limit => requested.page_limit
    ) as page
    group by requested.page_limit
    order by requested.page_limit
  $sql$,
  $sql$
    values
      (1, 2::bigint),
      (2, 3::bigint),
      (5, 6::bigint),
      (7, 8::bigint)
  $sql$,
  'each non-terminal first page returns exactly limit plus one sentinel row'
);

select throws_ok(
  'select * from public.list_catalog_summaries_v2(null)',
  '22023',
  'p_listing_type is invalid',
  'a null required listing type is rejected'
);

select throws_ok(
  'select * from public.list_catalog_summaries_v2(''unknown'')',
  '22023',
  'p_listing_type is invalid',
  'an unknown listing type is rejected'
);

select throws_ok(
  'select * from public.list_catalog_summaries_v2(''lieu'', p_city_id => ''   '')',
  '22023',
  'p_city_id is invalid',
  'a blank city filter is rejected'
);

select throws_ok(
  'select * from public.list_catalog_summaries_v2(''lieu'', p_city_id => ''unknown-city'')',
  '22023',
  'p_city_id is unknown',
  'an unknown city filter is rejected'
);

select throws_ok(
  $sql$
    select *
    from public.list_catalog_summaries_v2(
      'lieu',
      p_category_id => 'explore-v2-event-rank'
    )
  $sql$,
  '22023',
  'p_category_id is unknown or does not match p_listing_type',
  'a category from another listing type is rejected'
);

select throws_ok(
  'select * from public.list_catalog_summaries_v2(''lieu'', p_listing_class => ''unknown'')',
  '22023',
  'p_listing_class is invalid',
  'an unknown listing class is rejected'
);

select throws_ok(
  'select * from public.list_catalog_summaries_v2(''lieu'', p_sort => null)',
  '22023',
  'p_sort is invalid',
  'an explicit null sort is rejected'
);

select throws_ok(
  'select * from public.list_catalog_summaries_v2(''lieu'', p_sort => ''recent'')',
  '22023',
  'p_sort is invalid',
  'an unapproved sort is rejected'
);

select throws_ok(
  $sql$
    select *
    from public.list_catalog_summaries_v2(
      'lieu',
      p_sort => 'temporal_proximity'
    )
  $sql$,
  '22023',
  'p_sort is not supported for p_listing_type',
  'temporal proximity is reserved for events'
);

select throws_ok(
  $sql$
    select *
    from public.list_catalog_summaries_v2(
      'etablissement',
      p_price_min_xof => -1
    )
  $sql$,
  '22023',
  'price filters are invalid',
  'a negative XOF price bound is rejected'
);

select throws_ok(
  $sql$
    select *
    from public.list_catalog_summaries_v2(
      'etablissement',
      p_price_min_xof => 2000,
      p_price_max_xof => 1000
    )
  $sql$,
  '22023',
  'price filters are invalid',
  'reversed XOF price bounds are rejected'
);

select throws_ok(
  'select * from public.list_catalog_summaries_v2(''lieu'', p_price_min_xof => 0)',
  '22023',
  'price filters require establishment listings',
  'price filtering is reserved for establishments'
);

select throws_ok(
  $sql$
    select *
    from public.list_catalog_summaries_v2(
      'evenement',
      p_event_window_start => '2026-08-09 00:00:00+00'
    )
  $sql$,
  '22023',
  'event window bounds must be provided together',
  'a partial event window is rejected'
);

select throws_ok(
  $sql$
    select *
    from public.list_catalog_summaries_v2(
      'lieu',
      p_event_window_start => '2026-08-09 00:00:00+00',
      p_event_window_end => '2026-08-10 00:00:00+00'
    )
  $sql$,
  '22023',
  'event window requires event listings',
  'event windows are reserved for event listings'
);

select throws_ok(
  $sql$
    select *
    from public.list_catalog_summaries_v2(
      'evenement',
      p_event_window_start => '2026-08-09 00:00:00+00',
      p_event_window_end => '2026-08-09 00:00:00+00'
    )
  $sql$,
  '22023',
  'event window is invalid',
  'an empty half-open event window is rejected'
);

select throws_ok(
  $sql$
    select *
    from public.list_catalog_summaries_v2(
      'evenement',
      p_event_window_start => '-infinity'::timestamptz,
      p_event_window_end => '2026-08-09 00:00:00+00'::timestamptz
    )
  $sql$,
  '22023',
  'event window is invalid',
  'event windows outside the mobile-safe timestamp range are rejected'
);

select throws_ok(
  'select * from public.list_catalog_summaries_v2(''lieu'', p_limit => null)',
  '22023',
  'p_limit must be between 1 and 50',
  'a null page limit is rejected'
);

select throws_ok(
  'select * from public.list_catalog_summaries_v2(''lieu'', p_limit => 0)',
  '22023',
  'p_limit must be between 1 and 50',
  'a zero page limit is rejected'
);

select throws_ok(
  'select * from public.list_catalog_summaries_v2(''lieu'', p_limit => 51)',
  '22023',
  'p_limit must be between 1 and 50',
  'a page limit above fifty is rejected'
);

select lives_ok(
  'select * from public.list_catalog_summaries_v2(''  LIEU  '', p_sort => ''  POPULARITY  '', p_limit => 1)',
  'listing type and approved sort are canonicalized before execution'
);

create temporary table explore_v2_event_page
on commit drop
as
select
  result.*,
  pg_catalog.convert_from(
    pg_catalog.decode(result.row_cursor, 'base64'),
    'UTF8'
  )::jsonb as payload
from public.list_catalog_summaries_v2(
  'evenement',
  p_city_id => 'explore-v2-city',
  p_category_id => 'explore-v2-event-rank',
  p_limit => 2
) with ordinality as result;

create temporary table explore_v2_event_page_2
on commit drop
as
select result.*
from public.list_catalog_summaries_v2(
  'evenement',
  p_city_id => 'explore-v2-city',
  p_category_id => 'explore-v2-event-rank',
  p_cursor => (
    select row_cursor
    from explore_v2_event_page
    where ordinality = 2
  ),
  p_limit => 2
) with ordinality as result;

create temporary table explore_v2_event_page_3
on commit drop
as
select result.*
from public.list_catalog_summaries_v2(
  'evenement',
  p_city_id => 'explore-v2-city',
  p_category_id => 'explore-v2-event-rank',
  p_cursor => (
    select row_cursor
    from explore_v2_event_page_2
    where ordinality = 2
  ),
  p_limit => 2
) with ordinality as result;

select results_eq(
  $sql$
    select paged.id
    from (
      select ordinality::integer as global_ordinal, id
      from explore_v2_event_page
      where ordinality <= 2
      union all
      select 2 + ordinality::integer, id
      from explore_v2_event_page_2
      where ordinality <= 2
      union all
      select 4 + ordinality::integer, id
      from explore_v2_event_page_3
      where ordinality <= 2
    ) as paged
    order by paged.global_ordinal
  $sql$,
  $sql$
    values
      ('e2200000-0000-4000-8000-000000000001'::uuid),
      ('e2200000-0000-4000-8000-000000000002'::uuid),
      ('e2200000-0000-4000-8000-000000000003'::uuid),
      ('e2200000-0000-4000-8000-000000000004'::uuid),
      ('e2200000-0000-4000-8000-000000000005'::uuid),
      ('e2200000-0000-4000-8000-000000000006'::uuid)
  $sql$,
  'event keyset crosses ongoing, upcoming and ended phases without gaps or overlap'
);

create temporary table explore_v2_establishment_page
on commit drop
as
select result.*
from public.list_catalog_summaries_v2(
  'etablissement',
  p_city_id => 'explore-v2-city',
  p_category_id => 'explore-v2-establishment',
  p_limit => 2
) with ordinality as result;

create temporary table explore_v2_window_page
on commit drop
as
select
  result.*,
  window_bound.window_start,
  window_bound.window_end
from (
  select
    anchor_at + interval '30 days' as window_start,
    anchor_at + interval '31 days' as window_end
  from explore_v2_clock
) as window_bound
cross join lateral public.list_catalog_summaries_v2(
  'evenement',
  p_city_id => 'explore-v2-city',
  p_category_id => 'explore-v2-event-window',
  p_event_window_start => window_bound.window_start,
  p_event_window_end => window_bound.window_end,
  p_limit => 2
) with ordinality as result;

create temporary table explore_v2_v1_cursor
on commit drop
as
select row_cursor
from public.list_catalog_summaries(
  p_listing_type => 'lieu',
  p_limit => 1
)
limit 1;

select results_eq(
  format(
    $sql$
      select continued.id
      from public.list_catalog_summaries_v2(
        'lieu',
        p_city_id => 'explore-v2-city',
        p_category_id => 'explore-v2-place',
        p_sort => 'popularity',
        p_cursor => %L,
        p_limit => 2
      ) with ordinality as continued
      order by continued.ordinality
    $sql$,
    (
      select row_cursor
      from explore_v2_place_page
      where ordinality = 2
    )
  ),
  $sql$
    values
      ('e2000000-0000-4000-8000-000000000106'::uuid),
      ('e2000000-0000-4000-8000-000000000102'::uuid),
      ('e2000000-0000-4000-8000-000000000103'::uuid)
  $sql$,
  'place keyset crosses UUID and publication ties under the equivalent popularity sort'
);

select throws_ok(
  format(
    $sql$
      select *
      from public.list_catalog_summaries_v2(
        'lieu',
        p_category_id => 'explore-v2-place',
        p_cursor => %L,
        p_limit => 2
      )
    $sql$,
    (
      select row_cursor
      from explore_v2_place_page
      where ordinality = 2
    )
  ),
  '22023',
  'p_cursor does not match catalog filters',
  'a cursor cannot be reused after removing its city scope'
);

select throws_ok(
  format(
    $sql$
      select *
      from public.list_catalog_summaries_v2(
        'lieu',
        p_city_id => 'explore-v2-city',
        p_category_id => 'explore-v2-place',
        p_cursor => %L,
        p_limit => 1
      )
    $sql$,
    (
      select row_cursor
      from explore_v2_place_page
      where ordinality = 2
    )
  ),
  '22023',
  'p_cursor does not match catalog filters',
  'a cursor is strictly bound to its page limit'
);

select throws_ok(
  format(
    $sql$
      select *
      from public.list_catalog_summaries_v2(
        'evenement',
        p_city_id => 'explore-v2-city',
        p_category_id => 'explore-v2-event-rank',
        p_sort => 'popularity',
        p_cursor => %L,
        p_limit => 2
      )
    $sql$,
    (
      select row_cursor
      from explore_v2_event_page
      where ordinality = 2
    )
  ),
  '22023',
  'p_cursor does not match catalog filters',
  'an event cursor is bound to its resolved sort'
);

select throws_ok(
  format(
    $sql$
      select *
      from public.list_catalog_summaries_v2(
        'etablissement',
        p_city_id => 'explore-v2-city',
        p_category_id => 'explore-v2-establishment',
        p_price_min_xof => 1000,
        p_cursor => %L,
        p_limit => 2
      )
    $sql$,
    (
      select row_cursor
      from explore_v2_establishment_page
      where ordinality = 2
    )
  ),
  '22023',
  'p_cursor does not match catalog filters',
  'an establishment cursor is bound to its price scope'
);

select throws_ok(
  format(
    $sql$
      select *
      from public.list_catalog_summaries_v2(
        'evenement',
        p_city_id => 'explore-v2-city',
        p_category_id => 'explore-v2-event-window',
        p_event_window_start => %L::timestamptz,
        p_event_window_end => %L::timestamptz,
        p_cursor => %L,
        p_limit => 2
      )
    $sql$,
    (select window_start from explore_v2_window_page limit 1),
    (select window_end + interval '1 day' from explore_v2_window_page limit 1),
    (select row_cursor from explore_v2_window_page where ordinality = 2)
  ),
  '22023',
  'p_cursor does not match catalog filters',
  'an event cursor is bound to its exact half-open window'
);

select throws_ok(
  'select * from public.list_catalog_summaries_v2(''lieu'', p_cursor => ''not-base64!'')',
  '22023',
  'p_cursor is malformed',
  'malformed cursor base64 is rejected'
);

select throws_ok(
  'select * from public.list_catalog_summaries_v2(''lieu'', p_cursor => ''   '')',
  '22023',
  'p_cursor is invalid',
  'a blank cursor is rejected before decoding'
);

select throws_ok(
  format(
    'select * from public.list_catalog_summaries_v2(''lieu'', p_cursor => %L)',
    (select row_cursor from explore_v2_v1_cursor)
  ),
  '22023',
  'p_cursor version is unsupported',
  'a live V1 cursor is rejected by the V2 contract'
);

select throws_ok(
  format(
    $sql$
      select *
      from public.list_catalog_summaries_v2(
        'lieu',
        p_city_id => 'explore-v2-city',
        p_category_id => 'explore-v2-place',
        p_cursor => %L,
        p_limit => 2
      )
    $sql$,
    (
      select tests.encode_catalog_v2_cursor(payload - 'likes')
      from explore_v2_cursor_seed
    )
  ),
  '22023',
  'p_cursor fields are malformed',
  'a cursor missing one exact keyset field is rejected'
);

select throws_ok(
  format(
    $sql$
      select *
      from public.list_catalog_summaries_v2(
        'lieu',
        p_city_id => 'explore-v2-city',
        p_category_id => 'explore-v2-place',
        p_cursor => %L,
        p_limit => 2
      )
    $sql$,
    (
      select tests.encode_catalog_v2_cursor(payload || '{"extra":true}'::jsonb)
      from explore_v2_cursor_seed
    )
  ),
  '22023',
  'p_cursor fields are malformed',
  'a cursor with an unrecognized extra field is rejected'
);

select throws_ok(
  format(
    $sql$
      select *
      from public.list_catalog_summaries_v2(
        'lieu',
        p_city_id => 'explore-v2-city',
        p_category_id => 'explore-v2-place',
        p_cursor => %L,
        p_limit => 2
      )
    $sql$,
    (
      select tests.encode_catalog_v2_cursor(
        pg_catalog.jsonb_set(
          payload,
          '{contract}',
          '"catalog-summaries-v1"'::jsonb
        )
      )
      from explore_v2_cursor_seed
    )
  ),
  '22023',
  'p_cursor version is unsupported',
  'a cursor naming another contract version is rejected'
);

select throws_ok(
  format(
    $sql$
      select *
      from public.list_catalog_summaries_v2(
        'lieu',
        p_city_id => 'explore-v2-city',
        p_category_id => 'explore-v2-place',
        p_cursor => %L,
        p_limit => 2
      )
    $sql$,
    (
      select tests.encode_catalog_v2_cursor(
        pg_catalog.jsonb_set(
          payload,
          '{fingerprint}',
          '"00000000000000000000000000000000"'::jsonb
        )
      )
      from explore_v2_cursor_seed
    )
  ),
  '22023',
  'p_cursor does not match catalog filters',
  'a forged filter fingerprint is rejected'
);

select throws_ok(
  format(
    $sql$
      select *
      from public.list_catalog_summaries_v2(
        'lieu',
        p_city_id => 'explore-v2-city',
        p_category_id => 'explore-v2-place',
        p_cursor => %L,
        p_limit => 2
      )
    $sql$,
    (
      select tests.encode_catalog_v2_cursor(
        pg_catalog.jsonb_set(
          payload,
          '{as_of}',
          pg_catalog.to_jsonb((statement_timestamp() + interval '1 day')::text)
        )
      )
      from explore_v2_cursor_seed
    )
  ),
  '22023',
  'p_cursor fields are invalid',
  'a cursor cannot move its server snapshot into the future'
);

select throws_ok(
  format(
    $sql$
      select *
      from public.list_catalog_summaries_v2(
        'lieu',
        p_city_id => 'explore-v2-city',
        p_category_id => 'explore-v2-place',
        p_cursor => %L,
        p_limit => 2
      )
    $sql$,
    (
      select tests.encode_catalog_v2_cursor(
        pg_catalog.jsonb_set(
          payload,
          '{popularity}',
          pg_catalog.to_jsonb((payload ->> 'popularity')::bigint + 1)
        )
      )
      from explore_v2_cursor_seed
    )
  ),
  '22023',
  'p_cursor fields are invalid',
  'a popularity key inconsistent with views plus weighted likes is rejected'
);

select throws_ok(
  format(
    $sql$
      select *
      from public.list_catalog_summaries_v2(
        'evenement',
        p_city_id => 'explore-v2-city',
        p_category_id => 'explore-v2-event-rank',
        p_cursor => %L,
        p_limit => 2
      )
    $sql$,
    (
      select tests.encode_catalog_v2_cursor(
        pg_catalog.jsonb_set(
          payload,
          '{distance}',
          pg_catalog.to_jsonb(-315537897600000000::bigint)
        )
      )
      from explore_v2_event_page
      where ordinality = 1
    )
  ),
  '22023',
  'p_cursor fields are invalid',
  'a temporal cursor distance outside the mobile-safe range is rejected'
);

select throws_ok(
  format(
    $sql$
      select *
      from public.list_catalog_summaries_v2(
        'evenement',
        p_city_id => 'explore-v2-city',
        p_category_id => 'explore-v2-event-rank',
        p_cursor => %L,
        p_limit => 2
      )
    $sql$,
    (
      select tests.encode_catalog_v2_cursor(
        pg_catalog.jsonb_set(payload, '{distance}', '0'::jsonb)
      )
      from explore_v2_event_page
      where ordinality = 1
    )
  ),
  '22023',
  'p_cursor fields are invalid',
  'an ongoing temporal cursor cannot claim zero remaining distance'
);

select throws_ok(
  format(
    $sql$
      select *
      from public.list_catalog_summaries_v2(
        'evenement',
        p_city_id => 'explore-v2-city',
        p_category_id => 'explore-v2-event-rank',
        p_cursor => %L,
        p_limit => 2
      )
    $sql$,
    (
      select tests.encode_catalog_v2_cursor(
        pg_catalog.jsonb_set(payload, '{distance}', '0'::jsonb)
      )
      from explore_v2_event_page
      where ordinality = 3
    )
  ),
  '22023',
  'p_cursor fields are invalid',
  'an upcoming temporal cursor cannot claim zero distance before its start'
);

select lives_ok(
  format(
    $sql$
      select *
      from public.list_catalog_summaries_v2(
        'evenement',
        p_city_id => 'explore-v2-city',
        p_category_id => 'explore-v2-event-window',
        p_event_window_start => %L::timestamptz,
        p_event_window_end => %L::timestamptz,
        p_cursor => %L,
        p_limit => 2
      )
    $sql$,
    (
      select
        pg_catalog.to_char(
          window_start at time zone 'UTC' + interval '5 hours',
          'YYYY-MM-DD"T"HH24:MI:SS.US'
        ) || '+05:00'
      from explore_v2_window_page
      limit 1
    ),
    (
      select
        pg_catalog.to_char(
          window_end at time zone 'UTC' + interval '5 hours',
          'YYYY-MM-DD"T"HH24:MI:SS.US'
        ) || '+05:00'
      from explore_v2_window_page
      limit 1
    ),
    (
      select row_cursor
      from explore_v2_window_page
      where ordinality = 2
    )
  ),
  'event window cursors are invariant when the same instants use another UTC offset'
);

create temporary table explore_v2_exact_snapshot_boundaries (
  test_case text primary key,
  phase integer,
  is_event_ended boolean,
  is_sponsored_placement boolean,
  matching_rows bigint,
  expected_snapshot timestamptz,
  actual_snapshot timestamptz
) on commit drop;

do $$
declare
  v_snapshot timestamptz := statement_timestamp();
begin
  update public.event_details
  set start_at = v_snapshot,
      end_at = v_snapshot + interval '1 day'
  where listing_id = 'e2300000-0000-4000-8000-000000000003';

  update public.event_details
  set start_at = v_snapshot - interval '1 day',
      end_at = v_snapshot
  where listing_id = 'e2300000-0000-4000-8000-000000000002';

  update public.event_details
  set start_at = v_snapshot,
      end_at = v_snapshot
  where listing_id = 'e2300000-0000-4000-8000-000000000005';

  update public.listings
  set sponsored_until = v_snapshot
  where id = 'e2100000-0000-4000-8000-000000000001';

  update public.listings
  set status = 'publie',
      published_at = v_snapshot + interval '1 day'
  where id = 'e2000000-0000-4000-8000-000000000199';

  insert into explore_v2_exact_snapshot_boundaries (
    test_case,
    phase,
    is_event_ended,
    expected_snapshot,
    actual_snapshot
  )
  select
    case event_result.id
      when 'e2300000-0000-4000-8000-000000000003'::uuid then 'start-equals-snapshot'
      when 'e2300000-0000-4000-8000-000000000002'::uuid then 'end-equals-snapshot'
      when 'e2300000-0000-4000-8000-000000000005'::uuid then 'point-equals-snapshot'
    end,
    (
      pg_catalog.convert_from(
        pg_catalog.decode(event_result.row_cursor, 'base64'),
        'UTF8'
      )::jsonb ->> 'phase'
    )::integer,
    event_result.is_event_ended,
    v_snapshot,
    event_result.snapshot_at
  from public.list_catalog_summaries_v2(
    'evenement',
    p_city_id => 'explore-v2-city',
    p_category_id => 'explore-v2-event-window',
    p_limit => 50
  ) as event_result
  where event_result.id in (
    'e2300000-0000-4000-8000-000000000002',
    'e2300000-0000-4000-8000-000000000003',
    'e2300000-0000-4000-8000-000000000005'
  );

  insert into explore_v2_exact_snapshot_boundaries (
    test_case,
    is_sponsored_placement,
    expected_snapshot,
    actual_snapshot
  )
  select
    'sponsor-equals-snapshot',
    establishment_result.is_sponsored_placement,
    v_snapshot,
    establishment_result.snapshot_at
  from public.list_catalog_summaries_v2(
    'etablissement',
    p_city_id => 'explore-v2-city',
    p_category_id => 'explore-v2-establishment',
    p_limit => 50
  ) as establishment_result
  where establishment_result.id = 'e2100000-0000-4000-8000-000000000001';

  insert into explore_v2_exact_snapshot_boundaries (
    test_case,
    matching_rows
  )
  select
    'publication-after-snapshot',
    count(*)
  from public.list_catalog_summaries_v2(
    'lieu',
    p_city_id => 'explore-v2-city',
    p_category_id => 'explore-v2-place',
    p_limit => 50
  ) as place_result
  where place_result.id = 'e2000000-0000-4000-8000-000000000199';
end;
$$;

select ok(
  (
    select
      phase = 2
      and not is_event_ended
      and actual_snapshot = expected_snapshot
    from explore_v2_exact_snapshot_boundaries
    where test_case = 'start-equals-snapshot'
  ),
  'an event starting exactly at the snapshot is ongoing while its end is later'
);

select ok(
  (
    select
      phase = 0
      and is_event_ended
      and actual_snapshot = expected_snapshot
    from explore_v2_exact_snapshot_boundaries
    where test_case = 'end-equals-snapshot'
  ),
  'an event ending exactly at the snapshot is ended under [start,end) semantics'
);

select ok(
  (
    select
      phase = 0
      and is_event_ended
      and actual_snapshot = expected_snapshot
    from explore_v2_exact_snapshot_boundaries
    where test_case = 'point-equals-snapshot'
  ),
  'a point event is ended exactly at its start snapshot'
);

select ok(
  not (
    select
      is_sponsored_placement
      or actual_snapshot <> expected_snapshot
    from explore_v2_exact_snapshot_boundaries
    where test_case = 'sponsor-equals-snapshot'
  ),
  'sponsored_until equal to the snapshot is organic because eligibility is strictly later'
);

select is(
  (
    select matching_rows
    from explore_v2_exact_snapshot_boundaries
    where test_case = 'publication-after-snapshot'
  ),
  0::bigint,
  'a published listing newer than the server snapshot is excluded'
);

select * from finish();
rollback;
