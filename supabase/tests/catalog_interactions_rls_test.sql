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

CREATE OR REPLACE FUNCTION tests.scalar_int_as(db_role text, uid uuid, sql text)
RETURNS integer
LANGUAGE plpgsql
AS $$
DECLARE
  result integer;
BEGIN
  PERFORM tests.use_auth_context(db_role, uid);
  EXECUTE sql INTO result;
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

SELECT plan(14);

INSERT INTO auth.users (id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
VALUES
  (
    '44444444-4444-4444-8444-444444444444',
    'authenticated',
    'authenticated',
    'viewer-one@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    '55555555-5555-4555-8555-555555555555',
    'authenticated',
    'authenticated',
    'viewer-two@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    '66666666-6666-4666-8666-666666666666',
    'authenticated',
    'authenticated',
    'admin-catalog@kwabor.test',
    '',
    now(),
    now(),
    now()
  );

INSERT INTO public.user_roles (user_id, role, verification_status)
VALUES
  ('44444444-4444-4444-8444-444444444444', 'user', 'unverified'),
  ('55555555-5555-4555-8555-555555555555', 'user', 'unverified'),
  ('66666666-6666-4666-8666-666666666666', 'admin', 'verified');

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
  '00000000-0000-4000-8000-000000000299',
  'lieu',
  'historique',
  'patrimonial',
  'heritage-historique',
  '66666666-6666-4666-8666-666666666666',
  'brouillon',
  'Brouillon interaction cache',
  'brouillon-interaction-cache',
  'Fiche de test non publiee pour verifier que les interactions catalogue restent bloquees.',
  'ouidah',
  'Ouidah',
  6.3600,
  2.0800,
  'aucune'
);

SELECT ok(
  tests.statement_fails_as(
    'anon',
    NULL,
    'SELECT * FROM public.get_listing_viewer_interaction(''00000000-0000-4000-8000-000000000101'')'
  ),
  'anonymous users cannot read interaction state'
);

SELECT ok(
  tests.statement_succeeds_as(
    'authenticated',
    '44444444-4444-4444-8444-444444444444',
    $sql$
      INSERT INTO public.favorites (user_id, listing_id)
      VALUES (
        '44444444-4444-4444-8444-444444444444',
        '00000000-0000-4000-8000-000000000101'
      )
    $sql$
  ),
  'authenticated users can favorite a published listing'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    '44444444-4444-4444-8444-444444444444',
    $sql$
      INSERT INTO public.favorites (user_id, listing_id)
      VALUES (
        '44444444-4444-4444-8444-444444444444',
        '00000000-0000-4000-8000-000000000299'
      )
    $sql$
  ),
  'users cannot favorite an unpublished listing'
);

SELECT is(
  tests.count_as(
    'authenticated',
    '55555555-5555-4555-8555-555555555555',
    'SELECT listing_id FROM public.favorites'
  ),
  0::bigint,
  'users cannot read another user favorite rows'
);

SELECT ok(
  tests.statement_succeeds_as(
    'authenticated',
    '44444444-4444-4444-8444-444444444444',
    $sql$
      DELETE FROM public.favorites
      WHERE user_id = '44444444-4444-4444-8444-444444444444'
        AND listing_id = '00000000-0000-4000-8000-000000000101'
    $sql$
  ),
  'users can remove their own favorite rows'
);

SELECT tests.use_auth_context('authenticated', '44444444-4444-4444-8444-444444444444');
SELECT results_eq(
  $sql$
    SELECT liked_by_current_user, favorited_by_current_user
    FROM public.get_listing_viewer_interaction('00000000-0000-4000-8000-000000000101')
  $sql$,
  $$VALUES (false, false)$$,
  'published listing starts without viewer interaction'
);
RESET ROLE;

SELECT ok(
  tests.statement_succeeds_as(
    'authenticated',
    '44444444-4444-4444-8444-444444444444',
    $sql$
      DO $do$
      BEGIN
        PERFORM public.add_listing_to_favorites('00000000-0000-4000-8000-000000000101');
        PERFORM public.add_listing_to_favorites('00000000-0000-4000-8000-000000000101');
      END
      $do$
    $sql$
  ),
  'favorite RPC is idempotent'
);

SELECT is(
  tests.count_as(
    'authenticated',
    '44444444-4444-4444-8444-444444444444',
    'SELECT listing_id FROM public.favorites WHERE listing_id = ''00000000-0000-4000-8000-000000000101'''
  ),
  1::bigint,
  'favorite RPC stores one favorite row'
);

SELECT is(
  tests.scalar_int_as(
    'authenticated',
    '44444444-4444-4444-8444-444444444444',
    'SELECT likes_count FROM public.listings WHERE id = ''00000000-0000-4000-8000-000000000101'''
  ),
  86,
  'favorite does not change likes count'
);

SELECT ok(
  tests.statement_succeeds_as(
    'authenticated',
    '44444444-4444-4444-8444-444444444444',
    $sql$
      DO $do$
      BEGIN
        PERFORM public.like_listing('00000000-0000-4000-8000-000000000101');
        PERFORM public.like_listing('00000000-0000-4000-8000-000000000101');
      END
      $do$
    $sql$
  ),
  'like RPC is idempotent'
);

SELECT is(
  tests.scalar_int_as(
    'authenticated',
    '44444444-4444-4444-8444-444444444444',
    'SELECT likes_count FROM public.listings WHERE id = ''00000000-0000-4000-8000-000000000101'''
  ),
  87,
  'like increments count once'
);

SELECT ok(
  tests.statement_succeeds_as(
    'authenticated',
    '44444444-4444-4444-8444-444444444444',
    $sql$
      DO $do$
      BEGIN
        PERFORM public.unlike_listing('00000000-0000-4000-8000-000000000101');
        PERFORM public.unlike_listing('00000000-0000-4000-8000-000000000101');
      END
      $do$
    $sql$
  ),
  'unlike RPC is idempotent'
);

SELECT is(
  tests.scalar_int_as(
    'authenticated',
    '44444444-4444-4444-8444-444444444444',
    'SELECT likes_count FROM public.listings WHERE id = ''00000000-0000-4000-8000-000000000101'''
  ),
  86,
  'unlike decrements count once without going below original count'
);

SELECT tests.use_auth_context('authenticated', '44444444-4444-4444-8444-444444444444');
SELECT results_eq(
  $sql$
    SELECT listing_id, liked_by_current_user, favorited_by_current_user
    FROM public.list_listing_viewer_interactions(ARRAY[
      '00000000-0000-4000-8000-000000000101'::uuid,
      '00000000-0000-4000-8000-000000000102'::uuid,
      '00000000-0000-4000-8000-000000000299'::uuid
    ])
  $sql$,
  $$
    VALUES
      ('00000000-0000-4000-8000-000000000101'::uuid, false, true),
      ('00000000-0000-4000-8000-000000000102'::uuid, false, false)
  $$,
  'batch interaction RPC returns published listings only'
);
RESET ROLE;

SELECT * FROM finish();
ROLLBACK;
