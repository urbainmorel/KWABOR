insert into public.cities (id, name, slug, latitude, longitude) values
  ('cotonou', 'Cotonou', 'cotonou', 6.3703, 2.3912),
  ('porto-novo', 'Porto-Novo', 'porto-novo', 6.4969, 2.6289),
  ('ouidah', 'Ouidah', 'ouidah', 6.3631, 2.0851),
  ('abomey', 'Abomey', 'abomey', 7.1853, 1.9912),
  ('parakou', 'Parakou', 'parakou', 9.3372, 2.6303)
on conflict (id) do update set
  name = excluded.name,
  slug = excluded.slug,
  latitude = excluded.latitude,
  longitude = excluded.longitude,
  enabled = true;

insert into public.categories (
  id,
  listing_type,
  subtype,
  name_key,
  default_listing_class,
  detail_variant,
  sort_order
) values
  ('heritage-historique', 'lieu', 'historique', 'category.heritage.historique', 'patrimonial', 'place', 10),
  ('heritage-nature', 'lieu', 'nature', 'category.heritage.nature', 'patrimonial', 'place', 20),
  ('commercial-marche', 'lieu', 'marche', 'category.commercial.marche', 'commercial', 'place', 30),
  ('commercial-restaurant', 'etablissement', 'restaurant', 'category.commercial.restaurant', 'commercial', 'food', 40),
  ('commercial-hotel', 'etablissement', 'hotel', 'category.commercial.hotel', 'commercial', 'lodging', 50),
  ('guide-touristique', 'etablissement', 'guide', 'category.commercial.guide', 'commercial', 'guide', 60),
  ('event-culture', 'evenement', 'culture', 'category.event.culture', 'evenementiel', 'event', 70)
on conflict (id) do update set
  listing_type = excluded.listing_type,
  subtype = excluded.subtype,
  name_key = excluded.name_key,
  default_listing_class = excluded.default_listing_class,
  detail_variant = excluded.detail_variant,
  sort_order = excluded.sort_order;

update public.listings
set status = 'brouillon',
    published_at = null
where id = '00000000-0000-4000-8000-000000000104';

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
  district,
  address,
  lat,
  lng,
  price_from_xof,
  price_unit,
  tags,
  verified,
  rating_avg,
  rating_count,
  views_count,
  likes_count,
  published_at
) values
  (
    '00000000-0000-4000-8000-000000000101',
    'lieu',
    'historique',
    'patrimonial',
    'heritage-historique',
    'brouillon',
    'Porte du Non-Retour',
    'porte-du-non-retour-ouidah',
    'Monument patrimonial majeur de Ouidah, point de memoire ouvert aux visiteurs et parcours de decouverte historique.',
    'fr',
    'ouidah',
    'Plage de Ouidah',
    'Route des Esclaves, Ouidah',
    6.3422,
    2.0800,
    null,
    'aucune',
    array['memoire', 'patrimoine', 'histoire'],
    true,
    4.8,
    42,
    320,
    86,
    null
  ),
  (
    '00000000-0000-4000-8000-000000000102',
    'lieu',
    'marche',
    'commercial',
    'commercial-marche',
    'brouillon',
    'Marche Dantokpa',
    'marche-dantokpa-cotonou',
    'Grand marche populaire de Cotonou, connu pour ses allees denses, ses produits locaux et son energie commerciale.',
    'fr',
    'cotonou',
    'Dantokpa',
    'Boulevard Saint Michel, Cotonou',
    6.3707,
    2.4310,
    null,
    'aucune',
    array['marche', 'shopping', 'local'],
    false,
    4.4,
    31,
    280,
    63,
    null
  ),
  (
    '00000000-0000-4000-8000-000000000103',
    'etablissement',
    'restaurant',
    'commercial',
    'commercial-restaurant',
    'brouillon',
    'Table Locale Cotonou',
    'table-locale-cotonou',
    'Restaurant de test dedie a la validation catalogue Kwabor, avec cuisine beninoise, prix indicatif et contact fictif.',
    'fr',
    'cotonou',
    'Haie Vive',
    'Rue 12.001, Cotonou',
    6.3586,
    2.4041,
    5000,
    'par_personne',
    array['restaurant', 'beninois', 'test'],
    false,
    4.2,
    12,
    110,
    24,
    null
  ),
  (
    '00000000-0000-4000-8000-000000000104',
    'evenement',
    'culture',
    'evenementiel',
    'event-culture',
    'brouillon',
    'Festival culturel de Ouidah',
    'festival-culturel-ouidah-test',
    'Evenement culturel de test pour valider les fiches evenementielles, la recherche par ville et les campagnes futures.',
    'fr',
    'ouidah',
    'Centre ville',
    'Ouidah',
    6.3631,
    2.0851,
    2000,
    'par_entree',
    array['festival', 'culture', 'test'],
    false,
    4.1,
    8,
    75,
    15,
    null
  )
on conflict (id) do update set
  type = excluded.type,
  subtype = excluded.subtype,
  listing_class = excluded.listing_class,
  category_id = excluded.category_id,
  status = excluded.status,
  name = excluded.name,
  slug = excluded.slug,
  description = excluded.description,
  city_id = excluded.city_id,
  district = excluded.district,
  address = excluded.address,
  lat = excluded.lat,
  lng = excluded.lng,
  price_from_xof = excluded.price_from_xof,
  price_unit = excluded.price_unit,
  tags = excluded.tags,
  verified = excluded.verified,
  rating_avg = excluded.rating_avg,
  rating_count = excluded.rating_count,
  views_count = excluded.views_count,
  likes_count = excluded.likes_count,
  published_at = excluded.published_at;

update public.listings
set opening_hours = $$
  {
    "monday":{"status":"periods","periods":[{"opens_minute":660,"closes_minute":1320,"closes_next_day":false}]},
    "tuesday":{"status":"periods","periods":[{"opens_minute":660,"closes_minute":1320,"closes_next_day":false}]},
    "wednesday":{"status":"periods","periods":[{"opens_minute":660,"closes_minute":1320,"closes_next_day":false}]},
    "thursday":{"status":"periods","periods":[{"opens_minute":660,"closes_minute":1320,"closes_next_day":false}]},
    "friday":{"status":"periods","periods":[{"opens_minute":660,"closes_minute":1380,"closes_next_day":false}]},
    "saturday":{"status":"periods","periods":[{"opens_minute":660,"closes_minute":1380,"closes_next_day":false}]},
    "sunday":{"status":"closed","periods":[]}
  }
$$::jsonb,
    contact_phone = '+2290100000000',
    socials = '{"instagram":"https://instagram.com/kwabor.test"}'::jsonb
where id = '00000000-0000-4000-8000-000000000103';

insert into public.amenities (id, name_key, allowed_variants, sort_order) values
  (
    'parking',
    'amenity.parking',
    array['place', 'lodging', 'food', 'nightlife']::public.catalog_detail_variant[],
    10
  ),
  (
    'wifi',
    'amenity.wifi',
    array['lodging', 'food']::public.catalog_detail_variant[],
    20
  ),
  (
    'accessible-pmr',
    'amenity.accessible_pmr',
    array['place', 'lodging', 'food', 'nightlife']::public.catalog_detail_variant[],
    30
  )
on conflict (id) do update set
  name_key = excluded.name_key,
  allowed_variants = excluded.allowed_variants,
  sort_order = excluded.sort_order;

insert into public.place_details (
  listing_id,
  place_category,
  is_free,
  entry_fee_xof,
  fee_note
) values
  (
    '00000000-0000-4000-8000-000000000101',
    'historique',
    true,
    null,
    'Acces libre au monument.'
  ),
  (
    '00000000-0000-4000-8000-000000000102',
    'marche',
    true,
    null,
    'Acces libre au marche.'
  )
on conflict (listing_id) do update set
  place_category = excluded.place_category,
  is_free = excluded.is_free,
  entry_fee_xof = excluded.entry_fee_xof,
  fee_note = excluded.fee_note;

insert into public.food_details (
  listing_id,
  cuisines,
  meals,
  reservation,
  menu_url
) values (
  '00000000-0000-4000-8000-000000000103',
  array['beninoise'],
  array['dejeuner', 'diner'],
  true,
  'https://example.invalid/kwabor/menus/table-locale'
)
on conflict (listing_id) do update set
  cuisines = excluded.cuisines,
  meals = excluded.meals,
  reservation = excluded.reservation,
  menu_url = excluded.menu_url;

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
  '00000000-0000-4000-8000-000000000104',
  'culture',
  '2030-01-10 18:00:00+01',
  '2030-01-10 22:00:00+01',
  '00000000-0000-4000-8000-000000000101',
  'Kwabor Démonstration',
  'events@kwabor.test',
  'payant',
  'https://example.invalid/kwabor/tickets/festival-ouidah',
  500
)
on conflict (listing_id) do update set
  category = excluded.category,
  start_at = excluded.start_at,
  end_at = excluded.end_at,
  venue_listing_id = excluded.venue_listing_id,
  organizer_name = excluded.organizer_name,
  organizer_contact = excluded.organizer_contact,
  ticket_type = excluded.ticket_type,
  ticket_url = excluded.ticket_url,
  capacity = excluded.capacity;

insert into public.ticket_tiers (
  listing_id,
  label,
  price_xof,
  display_order
) values
  (
    '00000000-0000-4000-8000-000000000104',
    'Standard',
    2000,
    0
  ),
  (
    '00000000-0000-4000-8000-000000000104',
    'VIP',
    5000,
    1
  )
on conflict (listing_id, label) do update set
  price_xof = excluded.price_xof,
  display_order = excluded.display_order;

insert into public.listing_amenities (
  listing_id,
  amenity_id,
  display_order
) values
  ('00000000-0000-4000-8000-000000000101', 'accessible-pmr', 0),
  ('00000000-0000-4000-8000-000000000102', 'parking', 0),
  ('00000000-0000-4000-8000-000000000103', 'wifi', 0),
  ('00000000-0000-4000-8000-000000000103', 'accessible-pmr', 1)
on conflict (listing_id, amenity_id) do update set
  display_order = excluded.display_order;

insert into public.listing_media (
  listing_id,
  url,
  alt,
  display_order,
  is_cover
) values
  (
    '00000000-0000-4000-8000-000000000101',
    'https://example.invalid/kwabor/seeds/porte-non-retour.jpg',
    'Porte du Non-Retour a Ouidah',
    0,
    true
  ),
  (
    '00000000-0000-4000-8000-000000000102',
    'https://example.invalid/kwabor/seeds/marche-dantokpa.jpg',
    'Vue du Marche Dantokpa a Cotonou',
    0,
    true
  ),
  (
    '00000000-0000-4000-8000-000000000103',
    'https://example.invalid/kwabor/seeds/table-locale.jpg',
    'Plat local beninois servi dans un restaurant de test',
    0,
    true
  ),
  (
    '00000000-0000-4000-8000-000000000104',
    'https://example.invalid/kwabor/seeds/festival-ouidah.jpg',
    'Scene culturelle de festival a Ouidah',
    0,
    true
  )
on conflict do nothing;

update public.listings
set status = 'publie',
    published_at = '2026-07-01 00:00:00+00'
where id in (
  '00000000-0000-4000-8000-000000000101',
  '00000000-0000-4000-8000-000000000102',
  '00000000-0000-4000-8000-000000000103'
);

update public.listings
set status = 'publie',
    published_at = '2026-07-01 00:00:00+00'
where id = '00000000-0000-4000-8000-000000000104';
