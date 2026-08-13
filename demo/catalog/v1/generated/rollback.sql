-- GENERATED FILE. DO NOT EDIT.
-- Kind: rollback
-- Catalog version: v1
-- Catalog anchor: 2026-08-12 (Africa/Porto-Novo)
-- Source SHA-256: 2d0affd1fe5dacf9175b9c2a47750c62d65d3daf51684c4cd7a2949715615bc8
-- Catalog marker: demo-catalog:2d0affd1fe5
-- Operational guard only: these GUC checks are not a security boundary.
-- Execution is permitted only through the protected local/staging Environment workflow.
-- Required session settings:
--   set app.kwabor_environment = 'local' | 'staging';
--   set app.kwabor_demo_catalog_enabled = 'true';
--   set app.kwabor_demo_media_base_url = 'https://.../';

begin;
set local lock_timeout = '5s';
set local statement_timeout = '120s';
set local idle_in_transaction_session_timeout = '60s';
set constraints all deferred;

do $catalog_lock$
begin
  if not pg_try_advisory_xact_lock(hashtextextended('kwabor-demo-catalog-v1', 0)) then
    raise exception 'Another demo catalog operation already holds the advisory lock';
  end if;
end;
$catalog_lock$;

do $guard$
declare
  target_environment text := current_setting('app.kwabor_environment', true);
  catalog_enabled text := current_setting('app.kwabor_demo_catalog_enabled', true);
  media_base_url text := current_setting('app.kwabor_demo_media_base_url', true);
begin
  if target_environment is null or target_environment not in ('local', 'staging') then
    raise exception 'Demo catalog execution is allowed only for explicit local/staging operation';
  end if;
  if catalog_enabled is distinct from 'true' then
    raise exception 'Demo catalog execution requires app.kwabor_demo_catalog_enabled=true';
  end if;
  if media_base_url is null
    or media_base_url !~ '^https://[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+(:443)?/([^[:space:]#\\]*/)?$'
    or media_base_url ~ 'https://[^/]*@'
    or media_base_url ~ 'https://[^/]*(localhost|\.localhost|\.local|\.internal)(:443)?/'
  then
    raise exception 'Demo catalog execution requires a canonical public HTTPS app.kwabor_demo_media_base_url ending in /';
  end if;
end;
$guard$;

-- Identity is proven before neutralization; missing, drifted or foreign rows fail closed.
create temporary table kwabor_demo_expected_listings (
  id uuid primary key,
  slug text not null unique,
  category_id text not null,
  detail_variant text not null
) on commit drop;

insert into kwabor_demo_expected_listings (id, slug, category_id, detail_variant) values
  ('00000000-0000-4000-8000-000000000201'::uuid, 'jardin-brises-littoral-cotonou-demo', 'heritage-nature', 'place'),
  ('00000000-0000-4000-8000-000000000202'::uuid, 'cour-memoires-ganhi-cotonou-demo', 'heritage-historique', 'place'),
  ('00000000-0000-4000-8000-000000000203'::uuid, 'mangrove-urbaine-agla-cotonou-demo', 'heritage-nature', 'place'),
  ('00000000-0000-4000-8000-000000000204'::uuid, 'marche-paniers-lac-cotonou-demo', 'commercial-marche', 'place'),
  ('00000000-0000-4000-8000-000000000205'::uuid, 'sentier-paletuviers-rouges-ouidah-demo', 'heritage-nature', 'place'),
  ('00000000-0000-4000-8000-000000000206'::uuid, 'cour-tambours-zomai-ouidah-demo', 'heritage-historique', 'place'),
  ('00000000-0000-4000-8000-000000000207'::uuid, 'jardin-dunes-djegbadji-ouidah-demo', 'heritage-nature', 'place'),
  ('00000000-0000-4000-8000-000000000208'::uuid, 'marche-teinturieres-avlekete-ouidah-demo', 'commercial-marche', 'place'),
  ('00000000-0000-4000-8000-000000000209'::uuid, 'maison-voutes-adjina-porto-novo-demo', 'heritage-historique', 'place'),
  ('00000000-0000-4000-8000-000000000210'::uuid, 'escale-verte-lagune-porto-novo-demo', 'heritage-nature', 'place'),
  ('00000000-0000-4000-8000-000000000211'::uuid, 'passage-masques-tokpota-porto-novo-demo', 'heritage-historique', 'place'),
  ('00000000-0000-4000-8000-000000000212'::uuid, 'halle-potieres-akpassa-porto-novo-demo', 'commercial-marche', 'place'),
  ('00000000-0000-4000-8000-000000000213'::uuid, 'marche-etoffes-indigo-porto-novo-demo', 'commercial-marche', 'place'),
  ('00000000-0000-4000-8000-000000000214'::uuid, 'porte-du-non-retour', 'heritage-historique', 'place'),
  ('00000000-0000-4000-8000-000000000215'::uuid, 'marche-dantokpa', 'commercial-marche', 'place'),
  ('00000000-0000-4000-8000-000000000301'::uuid, 'escales-sonores-littoral-cotonou-demo', 'event-culture', 'event'),
  ('00000000-0000-4000-8000-000000000302'::uuid, 'atelier-couleurs-cotonou-demo', 'event-culture', 'event'),
  ('00000000-0000-4000-8000-000000000303'::uuid, 'scene-contes-urbains-cotonou-demo', 'event-culture', 'event'),
  ('00000000-0000-4000-8000-000000000304'::uuid, 'balade-photo-lumieres-port-cotonou-demo', 'event-culture', 'event'),
  ('00000000-0000-4000-8000-000000000305'::uuid, 'rencontres-danse-quartiers-cotonou-demo', 'event-culture', 'event'),
  ('00000000-0000-4000-8000-000000000306'::uuid, 'cinema-dunes-ouidah-demo', 'event-culture', 'event'),
  ('00000000-0000-4000-8000-000000000307'::uuid, 'laboratoire-rythmes-cote-ouidah-demo', 'event-culture', 'event'),
  ('00000000-0000-4000-8000-000000000308'::uuid, 'parade-lanternes-argile-ouidah-demo', 'event-culture', 'event'),
  ('00000000-0000-4000-8000-000000000309'::uuid, 'journee-saveurs-littoral-ouidah-demo', 'event-culture', 'event'),
  ('00000000-0000-4000-8000-000000000310'::uuid, 'matinee-masques-papier-porto-novo-demo', 'event-culture', 'event'),
  ('00000000-0000-4000-8000-000000000311'::uuid, 'salon-livre-lagunes-porto-novo-demo', 'event-culture', 'event'),
  ('00000000-0000-4000-8000-000000000312'::uuid, 'concert-cours-illuminees-porto-novo-demo', 'event-culture', 'event'),
  ('00000000-0000-4000-8000-000000000313'::uuid, 'atelier-indigo-papier-porto-novo-demo', 'event-culture', 'event'),
  ('00000000-0000-4000-8000-000000000314'::uuid, 'semaine-histoires-lagune-porto-novo-demo', 'event-culture', 'event'),
  ('00000000-0000-4000-8000-000000000315'::uuid, 'festival-culturel-ouidah-demo', 'event-culture', 'event'),
  ('00000000-0000-4000-8000-000000000401'::uuid, 'azur-des-cocotiers-demo-cotonou', 'commercial-hotel', 'lodging'),
  ('00000000-0000-4000-8000-000000000402'::uuid, 'patio-indigo-demo-cotonou', 'commercial-hotel', 'lodging'),
  ('00000000-0000-4000-8000-000000000403'::uuid, 'lagune-sereine-demo-cotonou', 'commercial-hotel', 'lodging'),
  ('00000000-0000-4000-8000-000000000404'::uuid, 'jardin-corail-demo-cotonou', 'commercial-hotel', 'lodging'),
  ('00000000-0000-4000-8000-000000000405'::uuid, 'horizon-des-alizes-demo-cotonou', 'commercial-hotel', 'lodging'),
  ('00000000-0000-4000-8000-000000000406'::uuid, 'escale-des-palmes-demo-ouidah', 'commercial-hotel', 'lodging'),
  ('00000000-0000-4000-8000-000000000407'::uuid, 'cour-rouge-demo-ouidah', 'commercial-hotel', 'lodging'),
  ('00000000-0000-4000-8000-000000000408'::uuid, 'refuge-de-la-lagune-demo-ouidah', 'commercial-hotel', 'lodging'),
  ('00000000-0000-4000-8000-000000000409'::uuid, 'maison-des-embruns-demo-ouidah', 'commercial-hotel', 'lodging'),
  ('00000000-0000-4000-8000-000000000410'::uuid, 'jardin-de-sable-demo-ouidah', 'commercial-hotel', 'lodging'),
  ('00000000-0000-4000-8000-000000000411'::uuid, 'patio-des-masques-demo-porto-novo', 'commercial-hotel', 'lodging'),
  ('00000000-0000-4000-8000-000000000412'::uuid, 'rives-de-l-oueme-demo-porto-novo', 'commercial-hotel', 'lodging'),
  ('00000000-0000-4000-8000-000000000413'::uuid, 'maison-ocre-demo-porto-novo', 'commercial-hotel', 'lodging'),
  ('00000000-0000-4000-8000-000000000414'::uuid, 'jardin-des-arcades-demo-porto-novo', 'commercial-hotel', 'lodging'),
  ('00000000-0000-4000-8000-000000000415'::uuid, 'etoile-de-la-lagune-demo-porto-novo', 'commercial-hotel', 'lodging'),
  ('00000000-0000-4000-8000-000000000501'::uuid, 'jardin-des-saveurs-demo-cotonou', 'commercial-restaurant', 'food'),
  ('00000000-0000-4000-8000-000000000502'::uuid, 'marmite-du-littoral-demo-cotonou', 'commercial-restaurant', 'food'),
  ('00000000-0000-4000-8000-000000000503'::uuid, 'patio-des-epices-demo-cotonou', 'commercial-restaurant', 'food'),
  ('00000000-0000-4000-8000-000000000504'::uuid, 'bol-de-la-lagune-demo-cotonou', 'commercial-restaurant', 'food'),
  ('00000000-0000-4000-8000-000000000505'::uuid, 'table-des-palmes-demo-ouidah', 'commercial-restaurant', 'food'),
  ('00000000-0000-4000-8000-000000000506'::uuid, 'marmite-de-sable-demo-ouidah', 'commercial-restaurant', 'food'),
  ('00000000-0000-4000-8000-000000000507'::uuid, 'cour-des-aromates-demo-ouidah', 'commercial-restaurant', 'food'),
  ('00000000-0000-4000-8000-000000000508'::uuid, 'assiette-de-la-lagune-demo-ouidah', 'commercial-restaurant', 'food'),
  ('00000000-0000-4000-8000-000000000509'::uuid, 'cuisine-des-embruns-demo-ouidah', 'commercial-restaurant', 'food'),
  ('00000000-0000-4000-8000-000000000510'::uuid, 'table-des-arcades-demo-porto-novo', 'commercial-restaurant', 'food'),
  ('00000000-0000-4000-8000-000000000511'::uuid, 'marmite-de-l-oueme-demo-porto-novo', 'commercial-restaurant', 'food'),
  ('00000000-0000-4000-8000-000000000512'::uuid, 'patio-des-saveurs-demo-porto-novo', 'commercial-restaurant', 'food'),
  ('00000000-0000-4000-8000-000000000513'::uuid, 'bol-ocre-demo-porto-novo', 'commercial-restaurant', 'food'),
  ('00000000-0000-4000-8000-000000000514'::uuid, 'cuisine-des-jardins-demo-porto-novo', 'commercial-restaurant', 'food'),
  ('00000000-0000-4000-8000-000000000515'::uuid, 'table-locale-cotonou-demo', 'commercial-restaurant', 'food');

lock table public.listings, public.event_details in share row exclusive mode;

do $identity$
declare
  canonical_ids constant uuid[] := array[
    '00000000-0000-4000-8000-000000000101'::uuid,
    '00000000-0000-4000-8000-000000000102'::uuid,
    '00000000-0000-4000-8000-000000000103'::uuid,
    '00000000-0000-4000-8000-000000000104'::uuid
  ]::uuid[];
  matched integer;
begin
  if exists (
    select 1 from kwabor_demo_expected_listings demo where demo.id = any(canonical_ids)
  ) then
    raise exception 'Rollback target overlaps canonical fixtures';
  end if;
  if (select count(*) from kwabor_demo_expected_listings) <> 60 then
    raise exception 'Rollback requires exactly 60 distinct demo UUIDs';
  end if;
  select count(*) into matched
  from public.listings listing
  join kwabor_demo_expected_listings expected on expected.id = listing.id
  where listing.tags @> array['demo-catalog:2d0affd1fe5', 'demo-anchor:20260812']::text[];
  if matched <> 60 then
    raise exception 'Rollback catalog identity mismatch: %/60', matched;
  end if;
  if exists (
    select 1
    from public.event_details detail
    join kwabor_demo_expected_listings venue on venue.id = detail.venue_listing_id
    join public.listings event_listing on event_listing.id = detail.listing_id
    left join kwabor_demo_expected_listings demo_event on demo_event.id = event_listing.id
    where demo_event.id is null
      and event_listing.status in ('en_attente', 'publie')
  ) then
    raise exception 'Rollback refuses to archive a venue used by an external active event';
  end if;
end;
$identity$;

-- Logical rollback: archive events before their venues, retaining every parent,
-- typed child, media row, interaction and foreign-key reference.
update public.listings as target
set status = 'archive', published_at = null
where id in (
    '00000000-0000-4000-8000-000000000301'::uuid,
    '00000000-0000-4000-8000-000000000302'::uuid,
    '00000000-0000-4000-8000-000000000303'::uuid,
    '00000000-0000-4000-8000-000000000304'::uuid,
    '00000000-0000-4000-8000-000000000305'::uuid,
    '00000000-0000-4000-8000-000000000306'::uuid,
    '00000000-0000-4000-8000-000000000307'::uuid,
    '00000000-0000-4000-8000-000000000308'::uuid,
    '00000000-0000-4000-8000-000000000309'::uuid,
    '00000000-0000-4000-8000-000000000310'::uuid,
    '00000000-0000-4000-8000-000000000311'::uuid,
    '00000000-0000-4000-8000-000000000312'::uuid,
    '00000000-0000-4000-8000-000000000313'::uuid,
    '00000000-0000-4000-8000-000000000314'::uuid,
    '00000000-0000-4000-8000-000000000315'::uuid
)
  and row(target.status, target.published_at)
    is distinct from row('archive'::public.listing_status, null::timestamptz);

update public.listings as target
set status = 'archive', published_at = null
where id in (
    '00000000-0000-4000-8000-000000000201'::uuid,
    '00000000-0000-4000-8000-000000000202'::uuid,
    '00000000-0000-4000-8000-000000000203'::uuid,
    '00000000-0000-4000-8000-000000000204'::uuid,
    '00000000-0000-4000-8000-000000000205'::uuid,
    '00000000-0000-4000-8000-000000000206'::uuid,
    '00000000-0000-4000-8000-000000000207'::uuid,
    '00000000-0000-4000-8000-000000000208'::uuid,
    '00000000-0000-4000-8000-000000000209'::uuid,
    '00000000-0000-4000-8000-000000000210'::uuid,
    '00000000-0000-4000-8000-000000000211'::uuid,
    '00000000-0000-4000-8000-000000000212'::uuid,
    '00000000-0000-4000-8000-000000000213'::uuid,
    '00000000-0000-4000-8000-000000000214'::uuid,
    '00000000-0000-4000-8000-000000000215'::uuid,
    '00000000-0000-4000-8000-000000000401'::uuid,
    '00000000-0000-4000-8000-000000000402'::uuid,
    '00000000-0000-4000-8000-000000000403'::uuid,
    '00000000-0000-4000-8000-000000000404'::uuid,
    '00000000-0000-4000-8000-000000000405'::uuid,
    '00000000-0000-4000-8000-000000000406'::uuid,
    '00000000-0000-4000-8000-000000000407'::uuid,
    '00000000-0000-4000-8000-000000000408'::uuid,
    '00000000-0000-4000-8000-000000000409'::uuid,
    '00000000-0000-4000-8000-000000000410'::uuid,
    '00000000-0000-4000-8000-000000000411'::uuid,
    '00000000-0000-4000-8000-000000000412'::uuid,
    '00000000-0000-4000-8000-000000000413'::uuid,
    '00000000-0000-4000-8000-000000000414'::uuid,
    '00000000-0000-4000-8000-000000000415'::uuid,
    '00000000-0000-4000-8000-000000000501'::uuid,
    '00000000-0000-4000-8000-000000000502'::uuid,
    '00000000-0000-4000-8000-000000000503'::uuid,
    '00000000-0000-4000-8000-000000000504'::uuid,
    '00000000-0000-4000-8000-000000000505'::uuid,
    '00000000-0000-4000-8000-000000000506'::uuid,
    '00000000-0000-4000-8000-000000000507'::uuid,
    '00000000-0000-4000-8000-000000000508'::uuid,
    '00000000-0000-4000-8000-000000000509'::uuid,
    '00000000-0000-4000-8000-000000000510'::uuid,
    '00000000-0000-4000-8000-000000000511'::uuid,
    '00000000-0000-4000-8000-000000000512'::uuid,
    '00000000-0000-4000-8000-000000000513'::uuid,
    '00000000-0000-4000-8000-000000000514'::uuid,
    '00000000-0000-4000-8000-000000000515'::uuid
)
  and row(target.status, target.published_at)
    is distinct from row('archive'::public.listing_status, null::timestamptz);

do $proof$
declare
  neutralized integer;
begin
  select count(*) into neutralized
  from public.listings listing
  join kwabor_demo_expected_listings expected on expected.id = listing.id
  where listing.status = 'archive'
    and listing.published_at is null
    and listing.tags @> array['demo-catalog:2d0affd1fe5', 'demo-anchor:20260812']::text[];
  if neutralized <> 60 then
    raise exception 'Rollback neutralization proof failed: %/60', neutralized;
  end if;
end;
$proof$;

commit;
