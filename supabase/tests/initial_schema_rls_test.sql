BEGIN;

CREATE SCHEMA IF NOT EXISTS tests;

CREATE OR REPLACE FUNCTION tests.use_auth_context(db_role text, uid uuid)
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
  EXECUTE format('SET LOCAL ROLE %I', db_role);
  PERFORM set_config('request.jwt.claim.role', db_role, true);
  PERFORM set_config('request.jwt.claim.sub', coalesce(uid::text, ''), true);
END;
$$;

CREATE OR REPLACE FUNCTION tests.count_as(db_role text, uid uuid, sql text)
RETURNS bigint
LANGUAGE plpgsql
AS $$
DECLARE
  result bigint;
BEGIN
  PERFORM tests.use_auth_context(db_role, uid);
  EXECUTE format('SELECT count(*) FROM (%s) AS scoped_query', sql) INTO result;
  RESET ROLE;
  RETURN result;
EXCEPTION
  WHEN OTHERS THEN
    RESET ROLE;
    RAISE;
END;
$$;

CREATE OR REPLACE FUNCTION tests.statement_succeeds_as(db_role text, uid uuid, sql text)
RETURNS boolean
LANGUAGE plpgsql
AS $$
BEGIN
  PERFORM tests.use_auth_context(db_role, uid);
  EXECUTE sql;
  RESET ROLE;
  RETURN true;
EXCEPTION
  WHEN OTHERS THEN
    RESET ROLE;
    RETURN false;
END;
$$;

CREATE OR REPLACE FUNCTION tests.statement_fails_as(db_role text, uid uuid, sql text)
RETURNS boolean
LANGUAGE plpgsql
AS $$
BEGIN
  PERFORM tests.use_auth_context(db_role, uid);
  EXECUTE sql;
  RESET ROLE;
  RETURN false;
EXCEPTION
  WHEN OTHERS THEN
    RESET ROLE;
    RETURN true;
END;
$$;

SELECT plan(19);

INSERT INTO auth.users (id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
VALUES
  (
    '11111111-1111-4111-8111-111111111111',
    'authenticated',
    'authenticated',
    'user@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    '22222222-2222-4222-8222-222222222222',
    'authenticated',
    'authenticated',
    'promoter@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    '33333333-3333-4333-8333-333333333333',
    'authenticated',
    'authenticated',
    'admin@kwabor.test',
    '',
    now(),
    now(),
    now()
  );

INSERT INTO public.user_roles (user_id, role, verification_status)
VALUES
  ('11111111-1111-4111-8111-111111111111', 'user', 'unverified'),
  ('22222222-2222-4222-8222-222222222222', 'promoteur', 'verified'),
  ('33333333-3333-4333-8333-333333333333', 'admin', 'verified');

INSERT INTO public.listings (
  id,
  type,
  subtype,
  listing_class,
  category_id,
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
VALUES (
  '00000000-0000-4000-8000-000000000199',
  'lieu',
  'historique',
  'patrimonial',
  'heritage-historique',
  '33333333-3333-4333-8333-333333333333',
  'brouillon',
  'Brouillon patrimonial cache',
  'brouillon-patrimonial-cache',
  'Fiche de test non publiee pour verifier que la lecture publique reste limitee aux contenus publies.',
  'ouidah',
  'Ouidah',
  6.3600,
  2.0800,
  'aucune'
);

INSERT INTO public.notifications (user_id, type, title_key, body_key)
VALUES (
  '11111111-1111-4111-8111-111111111111',
  'system',
  'notification.test.title',
  'notification.test.body'
);

SELECT ok(to_regclass('public.listings') IS NOT NULL, 'listings table exists');
SELECT ok(to_regclass('public.campaigns') IS NOT NULL, 'campaigns table exists');

SELECT is(
  (
    SELECT count(*)::integer
    FROM pg_class relation
    JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
    WHERE namespace.nspname = 'public'
      AND relation.relname IN (
        'cities',
        'categories',
        'profiles',
        'user_roles',
        'listings',
        'listing_media',
        'social_posts',
        'favorites',
        'likes',
        'notifications',
        'claims',
        'missing_place_reports',
        'campaigns',
        'payments'
      )
      AND relation.relrowsecurity
  ),
  14,
  'all required public tables have RLS enabled'
);

SELECT is((SELECT count(*)::integer FROM public.cities), 5, 'Benin seed cities are loaded');
SELECT is((SELECT count(*)::integer FROM public.categories), 7, 'catalog seed categories are loaded');
SELECT is(
  tests.count_as('anon', NULL, 'SELECT id FROM public.listings'),
  4::bigint,
  'anonymous users read only published seed listings'
);
SELECT is(
  tests.count_as(
    'anon',
    NULL,
    'SELECT id FROM public.listings WHERE id = ''00000000-0000-4000-8000-000000000199'''
  ),
  0::bigint,
  'anonymous users cannot read draft listings'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    '11111111-1111-4111-8111-111111111111',
    $sql$
      INSERT INTO public.listings (
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
      VALUES (
        'lieu',
        'marche',
        'commercial',
        'commercial-marche',
        '11111111-1111-4111-8111-111111111111',
        '11111111-1111-4111-8111-111111111111',
        'en_attente',
        'Creation interdite simple utilisateur',
        'creation-interdite-simple-utilisateur',
        'Cette insertion doit echouer car un utilisateur simple ne peut jamais creer de fiche catalogue.',
        'cotonou',
        'Cotonou',
        6.37,
        2.39,
        'aucune'
      )
    $sql$
  ),
  'plain users cannot create listings'
);

SELECT ok(
  tests.statement_succeeds_as(
    'authenticated',
    '22222222-2222-4222-8222-222222222222',
    $sql$
      INSERT INTO public.listings (
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
        price_from_xof,
        price_unit
      )
      VALUES (
        '00000000-0000-4000-8000-000000000201',
        'etablissement',
        'restaurant',
        'commercial',
        'commercial-restaurant',
        '22222222-2222-4222-8222-222222222222',
        '22222222-2222-4222-8222-222222222222',
        'en_attente',
        'Restaurant promoteur test',
        'restaurant-promoteur-test',
        'Fiche commerciale creee par un promoteur verifie pour valider la politique RLS de creation.',
        'cotonou',
        'Cotonou',
        6.37,
        2.39,
        5000,
        'consommation'
      )
    $sql$
  ),
  'verified promoters can create commercial listings in review'
);

SELECT ok(
  tests.statement_succeeds_as(
    'authenticated',
    '11111111-1111-4111-8111-111111111111',
    $sql$
      INSERT INTO public.social_posts (
        author_id,
        social_media_type,
        listing_id,
        caption,
        content_lang
      )
      VALUES (
        '11111111-1111-4111-8111-111111111111',
        'photo',
        '00000000-0000-4000-8000-000000000101',
        'Photo rattachee a une fiche catalogue.',
        'fr'
      )
    $sql$
  ),
  'authenticated users can create social posts attached to a listing'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    '11111111-1111-4111-8111-111111111111',
    $sql$
      INSERT INTO public.social_posts (
        author_id,
        social_media_type,
        caption,
        content_lang
      )
      VALUES (
        '11111111-1111-4111-8111-111111111111',
        'photo',
        'Post sans fiche rattachee.',
        'fr'
      )
    $sql$
  ),
  'social posts require a listing_id'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    '22222222-2222-4222-8222-222222222222',
    $sql$
      INSERT INTO public.claims (
        listing_id,
        claimant_id,
        contact_phone
      )
      VALUES (
        '00000000-0000-4000-8000-000000000101',
        '22222222-2222-4222-8222-222222222222',
        '+2290100000000'
      )
    $sql$
  ),
  'patrimonial listings cannot be claimed'
);

SELECT ok(
  tests.statement_succeeds_as(
    'authenticated',
    '22222222-2222-4222-8222-222222222222',
    $sql$
      INSERT INTO public.claims (
        listing_id,
        claimant_id,
        contact_phone
      )
      VALUES (
        '00000000-0000-4000-8000-000000000102',
        '22222222-2222-4222-8222-222222222222',
        '+2290100000000'
      )
    $sql$
  ),
  'verified promoters can claim unowned commercial listings'
);

SELECT ok(
  tests.statement_succeeds_as(
    'authenticated',
    '11111111-1111-4111-8111-111111111111',
    $sql$
      INSERT INTO public.missing_place_reports (
        reporter_id,
        name,
        presumed_type,
        city_id,
        note
      )
      VALUES (
        '11111111-1111-4111-8111-111111111111',
        'Lieu signale par utilisateur',
        'lieu',
        'cotonou',
        'Signalement de test cree par un utilisateur authentifie.'
      )
    $sql$
  ),
  'authenticated users can report missing places'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    '22222222-2222-4222-8222-222222222222',
    $sql$
      INSERT INTO public.campaigns (
        listing_id,
        owner_id,
        city_ids,
        cost_xof,
        starts_at,
        ends_at
      )
      VALUES (
        '00000000-0000-4000-8000-000000000102',
        '22222222-2222-4222-8222-222222222222',
        ARRAY['cotonou'],
        30000,
        now(),
        now() + interval '7 days'
      )
    $sql$
  ),
  'campaigns are not directly inserted by clients'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    '22222222-2222-4222-8222-222222222222',
    $sql$
      INSERT INTO public.payments (
        campaign_id,
        payer_id,
        amount_xof,
        provider
      )
      VALUES (
        gen_random_uuid(),
        '22222222-2222-4222-8222-222222222222',
        30000,
        'cinetpay'
      )
    $sql$
  ),
  'payments are not directly inserted by clients'
);

SELECT is(
  tests.count_as(
    'authenticated',
    '22222222-2222-4222-8222-222222222222',
    'SELECT id FROM public.notifications'
  ),
  0::bigint,
  'users cannot read notifications owned by another user'
);

SELECT ok(
  NOT has_table_privilege('anon', 'public.payments', 'INSERT'),
  'anonymous role has no payment insert grant'
);
SELECT ok(
  NOT has_table_privilege('authenticated', 'public.payments', 'INSERT'),
  'authenticated role has no payment insert grant'
);

SELECT * FROM finish();
ROLLBACK;
