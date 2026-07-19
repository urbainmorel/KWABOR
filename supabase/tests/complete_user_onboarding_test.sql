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

CREATE OR REPLACE FUNCTION tests.affected_rows_as(db_role text, uid uuid, sql text)
RETURNS bigint
LANGUAGE plpgsql
AS $$
DECLARE
  affected_rows bigint;
BEGIN
  PERFORM tests.use_auth_context(db_role, uid);
  EXECUTE sql;
  GET DIAGNOSTICS affected_rows = ROW_COUNT;
  RESET ROLE;
  RETURN affected_rows;
EXCEPTION
  WHEN OTHERS THEN
    RESET ROLE;
    RAISE;
END;
$$;

SELECT plan(101);

INSERT INTO auth.users (id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
VALUES
  (
    'aaaaaaaa-0000-4000-8000-000000000001',
    'authenticated',
    'authenticated',
    'onboarding-one@kwabor.test',
    extensions.crypt('Test-password-123', extensions.gen_salt('bf')),
    now(),
    now(),
    now()
  ),
  (
    'bbbbbbbb-0000-4000-8000-000000000002',
    'authenticated',
    'authenticated',
    'onboarding-two@kwabor.test',
    extensions.crypt('Test-password-123', extensions.gen_salt('bf')),
    now(),
    now(),
    now()
  ),
  (
    'cccccccc-0000-4000-8000-000000000003',
    'authenticated',
    'authenticated',
    'onboarding-rollback@kwabor.test',
    extensions.crypt('Test-password-123', extensions.gen_salt('bf')),
    now(),
    now(),
    now()
  ),
  (
    'dddddddd-0000-4000-8000-000000000004',
    'authenticated',
    'authenticated',
    'onboarding-incomplete@kwabor.test',
    '',
    now(),
    now(),
    now()
  );

INSERT INTO public.cities (id, name, slug, country_code, enabled)
VALUES ('disabled-test-city', 'Ville desactivee test', 'ville-desactivee-test', 'BJ', false);

INSERT INTO public.legal_documents (
  id,
  document_type,
  version,
  locale,
  content_url,
  content_sha256,
  effective_at,
  active
)
VALUES
  (
    '10000000-0000-4000-8000-000000000001',
    'terms',
    'test-1',
    'fr',
    'https://legal.kwabor.test/terms/test-1',
    repeat('a', 64),
    now() - interval '1 day',
    true
  ),
  (
    '10000000-0000-4000-8000-000000000002',
    'privacy_policy',
    'test-1',
    'fr',
    'https://legal.kwabor.test/privacy/test-1',
    repeat('b', 64),
    now() - interval '1 day',
    true
  ),
  (
    '10000000-0000-4000-8000-000000000003',
    'ugc_license',
    'test-1',
    'fr',
    'https://legal.kwabor.test/ugc/test-1',
    repeat('c', 64),
    now() - interval '1 day',
    true
  ),
  (
    '10000000-0000-4000-8000-000000000004',
    'terms',
    'test-retired',
    'fr',
    'https://legal.kwabor.test/terms/test-retired',
    repeat('d', 64),
    now() - interval '2 days',
    false
  );

INSERT INTO public.profiles (
  user_id,
  first_name,
  last_name,
  city_id,
  preferred_locale,
  preferred_currency
)
VALUES (
  'dddddddd-0000-4000-8000-000000000004',
  'Profil',
  'Incomplet',
  'cotonou',
  'fr',
  'XOF'
);

INSERT INTO public.user_roles (user_id, role, verification_status)
VALUES
  ('dddddddd-0000-4000-8000-000000000004', 'admin', 'verified'),
  ('dddddddd-0000-4000-8000-000000000004', 'promoteur', 'verified');

INSERT INTO public.organizations (
  id,
  type,
  name,
  slug,
  verification_status,
  primary_owner_id,
  created_by
)
VALUES (
  '40000000-0000-4000-8000-000000000001',
  'promoteur',
  'Organisation onboarding incomplet',
  'organisation-onboarding-incomplet',
  'verified',
  'dddddddd-0000-4000-8000-000000000004',
  'dddddddd-0000-4000-8000-000000000004'
);

INSERT INTO public.organization_members (
  id,
  organization_id,
  user_id,
  role,
  status,
  accepted_at
)
VALUES
  (
    '41000000-0000-4000-8000-000000000001',
    '40000000-0000-4000-8000-000000000001',
    'dddddddd-0000-4000-8000-000000000004',
    'proprietaire',
    'active',
    now()
  ),
  (
    '41000000-0000-4000-8000-000000000002',
    '40000000-0000-4000-8000-000000000001',
    'bbbbbbbb-0000-4000-8000-000000000002',
    'editeur',
    'active',
    now()
  );

INSERT INTO public.organization_invites (
  id,
  organization_id,
  email,
  token_hash,
  proposed_role,
  invited_by_member_id,
  expires_at
)
VALUES
  (
    '42000000-0000-4000-8000-000000000001',
    '40000000-0000-4000-8000-000000000001',
    'revoke-incomplete@kwabor.test',
    encode(extensions.digest('revoke-incomplete-token', 'sha256'), 'hex'),
    'editeur',
    '41000000-0000-4000-8000-000000000001',
    now() + interval '7 days'
  ),
  (
    '42000000-0000-4000-8000-000000000002',
    '40000000-0000-4000-8000-000000000001',
    'onboarding-incomplete@kwabor.test',
    encode(extensions.digest('accept-incomplete-token', 'sha256'), 'hex'),
    'editeur',
    '41000000-0000-4000-8000-000000000001',
    now() + interval '7 days'
  );

INSERT INTO public.listing_media (
  id,
  listing_id,
  url,
  alt,
  display_order
)
VALUES (
  '43000000-0000-4000-8000-000000000001',
  '00000000-0000-4000-8000-000000000101',
  'https://media.kwabor.test/incomplete/listing.jpg',
  'Media de test appartenant a une session incomplete',
  99
);

INSERT INTO public.social_posts (
  id,
  author_id,
  social_media_type,
  listing_id,
  caption,
  content_lang
)
VALUES (
  '44000000-0000-4000-8000-000000000001',
  'dddddddd-0000-4000-8000-000000000004',
  'photo',
  '00000000-0000-4000-8000-000000000101',
  'Publication existante de test pour session incomplete.',
  'fr'
);

INSERT INTO public.social_media (
  id,
  post_id,
  url,
  alt,
  display_order
)
VALUES (
  '45000000-0000-4000-8000-000000000001',
  '44000000-0000-4000-8000-000000000001',
  'https://media.kwabor.test/incomplete/social.jpg',
  'Media social de test appartenant a une session incomplete',
  0
);

INSERT INTO public.favorites (user_id, listing_id)
VALUES (
  'dddddddd-0000-4000-8000-000000000004',
  '00000000-0000-4000-8000-000000000101'
);

INSERT INTO public.likes (user_id, listing_id)
VALUES (
  'dddddddd-0000-4000-8000-000000000004',
  '00000000-0000-4000-8000-000000000101'
);

INSERT INTO public.notifications (
  id,
  user_id,
  type,
  title_key,
  body_key
)
VALUES (
  '46000000-0000-4000-8000-000000000001',
  'dddddddd-0000-4000-8000-000000000004',
  'system',
  'notification.onboarding.test.title',
  'notification.onboarding.test.body'
);

INSERT INTO public.claims (
  id,
  listing_id,
  claimant_id,
  contact_phone
)
VALUES (
  '47000000-0000-4000-8000-000000000001',
  '00000000-0000-4000-8000-000000000102',
  'dddddddd-0000-4000-8000-000000000004',
  '+2290100000000'
);

INSERT INTO public.missing_place_reports (
  id,
  reporter_id,
  name,
  presumed_type,
  city_id,
  note
)
VALUES (
  '48000000-0000-4000-8000-000000000001',
  'dddddddd-0000-4000-8000-000000000004',
  'Signalement existant session incomplete',
  'lieu',
  'cotonou',
  'Signalement preexistant utilise pour verifier le verrouillage des mises a jour.'
);

CREATE OR REPLACE FUNCTION tests.fail_rollback_user_role()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NEW.user_id = 'cccccccc-0000-4000-8000-000000000003'::uuid THEN
    RAISE EXCEPTION 'Injected role failure for transaction test';
  END IF;

  RETURN NEW;
END;
$$;

CREATE TRIGGER fail_rollback_user_role
BEFORE INSERT ON public.user_roles
FOR EACH ROW EXECUTE FUNCTION tests.fail_rollback_user_role();

SELECT ok(
  (SELECT relrowsecurity FROM pg_class WHERE oid = 'public.legal_documents'::regclass),
  'legal documents have RLS enabled'
);

SELECT ok(
  (SELECT relrowsecurity FROM pg_class WHERE oid = 'public.user_legal_acceptances'::regclass),
  'legal acceptances have RLS enabled'
);

SELECT ok(
  to_regprocedure(
    'public.complete_user_onboarding(text, text, text, text, text, uuid, uuid, uuid)'
  ) IS NOT NULL,
  'complete onboarding RPC exists'
);

SELECT ok(
  has_function_privilege(
    'authenticated',
    'public.complete_user_onboarding(text, text, text, text, text, uuid, uuid, uuid)',
    'EXECUTE'
  ),
  'authenticated users can execute the public onboarding RPC'
);

SELECT ok(
  NOT has_function_privilege(
    'anon',
    'public.complete_user_onboarding(text, text, text, text, text, uuid, uuid, uuid)',
    'EXECUTE'
  ),
  'anonymous users cannot execute the onboarding RPC'
);

SELECT ok(
  NOT has_function_privilege(
    'authenticated',
    'app_private.complete_user_onboarding_internal(text, text, text, text, text, uuid, uuid, uuid)',
    'EXECUTE'
  ),
  'authenticated users cannot execute the private onboarding implementation'
);

SELECT is(
  tests.count_as('anon', NULL, 'SELECT id FROM public.legal_documents'),
  3::bigint,
  'anonymous clients read only active effective legal revisions'
);

SELECT is(
  tests.count_as(
    'anon',
    NULL,
    'SELECT id FROM public.legal_documents WHERE id = ''10000000-0000-4000-8000-000000000004'''
  ),
  0::bigint,
  'inactive legal revisions are not exposed'
);

SELECT ok(
  tests.statement_fails_as(
    'anon',
    NULL,
    'SELECT * FROM public.user_legal_acceptances'
  ),
  'anonymous clients cannot read legal acceptances'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'aaaaaaaa-0000-4000-8000-000000000001',
    $sql$
      INSERT INTO public.legal_documents (
        document_type,
        version,
        locale,
        content_url,
        content_sha256,
        effective_at,
        active
      ) VALUES (
        'terms',
        'forbidden',
        'fr',
        'https://legal.kwabor.test/forbidden',
        repeat('e', 64),
        now(),
        false
      )
    $sql$
  ),
  'clients cannot create legal revisions'
);

SELECT throws_ok(
  $sql$
    UPDATE public.legal_documents
    SET content_url = 'https://legal.kwabor.test/terms/tampered'
    WHERE id = '10000000-0000-4000-8000-000000000001'
  $sql$,
  '22023',
  'Legal document revisions are immutable',
  'accepted legal revision evidence cannot be modified'
);

SELECT throws_ok(
  $sql$
    DELETE FROM public.legal_documents
    WHERE id = '10000000-0000-4000-8000-000000000004'
  $sql$,
  '22023',
  'Legal document revisions cannot be deleted',
  'legal revision evidence cannot be deleted'
);

SELECT lives_ok(
  $sql$
    UPDATE public.legal_documents
    SET active = false
    WHERE id = '10000000-0000-4000-8000-000000000001';

    UPDATE public.legal_documents
    SET active = true
    WHERE id = '10000000-0000-4000-8000-000000000004';

    UPDATE public.legal_documents
    SET active = false
    WHERE id = '10000000-0000-4000-8000-000000000004';

    UPDATE public.legal_documents
    SET active = true
    WHERE id = '10000000-0000-4000-8000-000000000001';
  $sql$,
  'legal revision activation can change without mutating its evidence'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'aaaaaaaa-0000-4000-8000-000000000001',
    $sql$
      INSERT INTO public.profiles (
        user_id,
        first_name,
        last_name,
        city_id
      ) VALUES (
        'aaaaaaaa-0000-4000-8000-000000000001',
        'Direct',
        'Interdit',
        'cotonou'
      )
    $sql$
  ),
  'clients cannot directly create profiles'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'aaaaaaaa-0000-4000-8000-000000000001',
    $sql$
      INSERT INTO public.user_roles (user_id, role, verification_status)
      VALUES ('aaaaaaaa-0000-4000-8000-000000000001', 'user', 'unverified')
    $sql$
  ),
  'clients cannot directly create their base role'
);

SELECT ok(
  to_regprocedure('app_private.current_user_has_completed_onboarding()') IS NOT NULL,
  'the private completed-onboarding authorization helper exists'
);

SELECT ok(
  (
    SELECT procedure.prosecdef
    FROM pg_proc procedure
    WHERE procedure.oid = 'app_private.current_user_has_completed_onboarding()'::regprocedure
  ),
  'the authorization helper performs its indexed profile lookup as security definer'
);

SELECT ok(
  has_function_privilege(
    'authenticated',
    'app_private.current_user_has_completed_onboarding()',
    'EXECUTE'
  ),
  'authenticated RLS evaluation can execute the authorization helper'
);

SELECT ok(
  NOT has_function_privilege(
    'anon',
    'app_private.current_user_has_completed_onboarding()',
    'EXECUTE'
  ),
  'anonymous clients cannot execute the completed-onboarding helper'
);

SELECT tests.use_auth_context(
  'authenticated',
  'dddddddd-0000-4000-8000-000000000004'
);
SELECT is(
  app_private.current_user_has_completed_onboarding(),
  false,
  'a privileged OTP-only session is still classified as onboarding incomplete'
);
RESET ROLE;

SELECT is(
  (
    SELECT count(*)::integer
    FROM pg_policies policy
    WHERE policy.schemaname = 'public'
      AND 'authenticated' = ANY (policy.roles)
      AND policy.cmd IN ('INSERT', 'UPDATE', 'DELETE', 'ALL')
  ),
  28,
  'the audited authenticated product mutation policy set is complete'
);

SELECT is(
  (
    SELECT count(*)::integer
    FROM pg_policies policy
    WHERE policy.schemaname = 'public'
      AND 'authenticated' = ANY (policy.roles)
      AND policy.cmd IN ('INSERT', 'UPDATE', 'DELETE', 'ALL')
      AND position(
        'current_user_has_completed_onboarding'
        in coalesce(policy.qual, '') || ' ' || coalesce(policy.with_check, '')
      ) > 0
  ),
  28,
  'every authenticated product mutation policy requires completed onboarding'
);

SELECT is(
  tests.affected_rows_as(
    'authenticated',
    'dddddddd-0000-4000-8000-000000000004',
    $sql$
      UPDATE public.profiles
      SET first_name = 'Mutation interdite'
      WHERE user_id = 'dddddddd-0000-4000-8000-000000000004'
    $sql$
  ),
  0::bigint,
  'an incomplete session cannot update its profile outside onboarding completion'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'dddddddd-0000-4000-8000-000000000004',
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
      ) VALUES (
        '49000000-0000-4000-8000-000000000001',
        'etablissement',
        'restaurant',
        'commercial',
        'commercial-restaurant',
        'dddddddd-0000-4000-8000-000000000004',
        'dddddddd-0000-4000-8000-000000000004',
        'brouillon',
        'Fiche interdite onboarding incomplet',
        'fiche-interdite-onboarding-incomplet',
        'Cette fiche ne doit pas etre creee meme si la session incomplete possede un ancien role admin verifie.',
        'cotonou',
        'Cotonou',
        6.37,
        2.39,
        5000,
        'consommation'
      )
    $sql$
  ),
  'an incomplete privileged session cannot create listings'
);

SELECT is(
  tests.affected_rows_as(
    'authenticated',
    'dddddddd-0000-4000-8000-000000000004',
    $sql$
      UPDATE public.listings
      SET name = 'Mutation catalogue interdite'
      WHERE id = '00000000-0000-4000-8000-000000000101'
    $sql$
  ),
  0::bigint,
  'an incomplete privileged session cannot update listings'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'dddddddd-0000-4000-8000-000000000004',
    $sql$
      INSERT INTO public.listing_media (listing_id, url, alt, display_order)
      VALUES (
        '00000000-0000-4000-8000-000000000101',
        'https://media.kwabor.test/incomplete/new-listing.jpg',
        'Insertion media interdite pendant onboarding',
        100
      )
    $sql$
  ),
  'an incomplete privileged session cannot create listing media'
);

SELECT is(
  tests.affected_rows_as(
    'authenticated',
    'dddddddd-0000-4000-8000-000000000004',
    $sql$
      UPDATE public.listing_media
      SET alt = 'Mutation media interdite'
      WHERE id = '43000000-0000-4000-8000-000000000001'
    $sql$
  ),
  0::bigint,
  'an incomplete privileged session cannot update listing media'
);

SELECT is(
  tests.affected_rows_as(
    'authenticated',
    'dddddddd-0000-4000-8000-000000000004',
    $sql$
      DELETE FROM public.listing_media
      WHERE id = '43000000-0000-4000-8000-000000000001'
    $sql$
  ),
  0::bigint,
  'an incomplete privileged session cannot delete listing media'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'dddddddd-0000-4000-8000-000000000004',
    $sql$
      INSERT INTO public.social_posts (
        author_id,
        social_media_type,
        listing_id,
        caption,
        content_lang
      ) VALUES (
        'dddddddd-0000-4000-8000-000000000004',
        'photo',
        '00000000-0000-4000-8000-000000000101',
        'Publication interdite pendant onboarding.',
        'fr'
      )
    $sql$
  ),
  'an incomplete session cannot create social posts'
);

SELECT is(
  tests.affected_rows_as(
    'authenticated',
    'dddddddd-0000-4000-8000-000000000004',
    $sql$
      UPDATE public.social_posts
      SET caption = 'Mutation sociale interdite'
      WHERE id = '44000000-0000-4000-8000-000000000001'
    $sql$
  ),
  0::bigint,
  'an incomplete session cannot update social posts'
);

SELECT is(
  tests.affected_rows_as(
    'authenticated',
    'dddddddd-0000-4000-8000-000000000004',
    $sql$
      DELETE FROM public.social_posts
      WHERE id = '44000000-0000-4000-8000-000000000001'
    $sql$
  ),
  0::bigint,
  'an incomplete session cannot delete social posts'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'dddddddd-0000-4000-8000-000000000004',
    $sql$
      INSERT INTO public.social_media (post_id, url, alt, display_order)
      VALUES (
        '44000000-0000-4000-8000-000000000001',
        'https://media.kwabor.test/incomplete/new-social.jpg',
        'Insertion media social interdite pendant onboarding',
        1
      )
    $sql$
  ),
  'an incomplete session cannot create social media'
);

SELECT is(
  tests.affected_rows_as(
    'authenticated',
    'dddddddd-0000-4000-8000-000000000004',
    $sql$
      UPDATE public.social_media
      SET alt = 'Mutation media social interdite'
      WHERE id = '45000000-0000-4000-8000-000000000001'
    $sql$
  ),
  0::bigint,
  'an incomplete session cannot update social media'
);

SELECT is(
  tests.affected_rows_as(
    'authenticated',
    'dddddddd-0000-4000-8000-000000000004',
    $sql$
      DELETE FROM public.social_media
      WHERE id = '45000000-0000-4000-8000-000000000001'
    $sql$
  ),
  0::bigint,
  'an incomplete session cannot delete social media'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'dddddddd-0000-4000-8000-000000000004',
    $sql$
      INSERT INTO public.favorites (user_id, listing_id)
      VALUES (
        'dddddddd-0000-4000-8000-000000000004',
        '00000000-0000-4000-8000-000000000102'
      )
    $sql$
  ),
  'an incomplete session cannot add favorites directly'
);

SELECT is(
  tests.affected_rows_as(
    'authenticated',
    'dddddddd-0000-4000-8000-000000000004',
    $sql$
      DELETE FROM public.favorites
      WHERE user_id = 'dddddddd-0000-4000-8000-000000000004'
        AND listing_id = '00000000-0000-4000-8000-000000000101'
    $sql$
  ),
  0::bigint,
  'an incomplete session cannot delete favorites directly'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'dddddddd-0000-4000-8000-000000000004',
    $sql$
      INSERT INTO public.likes (user_id, listing_id)
      VALUES (
        'dddddddd-0000-4000-8000-000000000004',
        '00000000-0000-4000-8000-000000000102'
      )
    $sql$
  ),
  'an incomplete session cannot add likes directly'
);

SELECT is(
  tests.affected_rows_as(
    'authenticated',
    'dddddddd-0000-4000-8000-000000000004',
    $sql$
      DELETE FROM public.likes
      WHERE user_id = 'dddddddd-0000-4000-8000-000000000004'
        AND listing_id = '00000000-0000-4000-8000-000000000101'
    $sql$
  ),
  0::bigint,
  'an incomplete session cannot delete likes directly'
);

SELECT is(
  tests.affected_rows_as(
    'authenticated',
    'dddddddd-0000-4000-8000-000000000004',
    $sql$
      UPDATE public.notifications
      SET read = true
      WHERE id = '46000000-0000-4000-8000-000000000001'
    $sql$
  ),
  0::bigint,
  'an incomplete session cannot mutate notification state'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'dddddddd-0000-4000-8000-000000000004',
    $sql$
      INSERT INTO public.claims (listing_id, claimant_id, contact_phone)
      VALUES (
        '00000000-0000-4000-8000-000000000103',
        'dddddddd-0000-4000-8000-000000000004',
        '+2290199999999'
      )
    $sql$
  ),
  'an incomplete verified promoter cannot submit claims'
);

SELECT is(
  tests.affected_rows_as(
    'authenticated',
    'dddddddd-0000-4000-8000-000000000004',
    $sql$
      UPDATE public.claims
      SET status = 'approuve'
      WHERE id = '47000000-0000-4000-8000-000000000001'
    $sql$
  ),
  0::bigint,
  'an incomplete privileged session cannot moderate claims'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'dddddddd-0000-4000-8000-000000000004',
    $sql$
      INSERT INTO public.missing_place_reports (
        reporter_id,
        name,
        presumed_type,
        city_id,
        note
      ) VALUES (
        'dddddddd-0000-4000-8000-000000000004',
        'Signalement interdit onboarding incomplet',
        'lieu',
        'cotonou',
        'Ce signalement ne doit pas etre cree avant la fin du parcours.'
      )
    $sql$
  ),
  'an incomplete session cannot report missing places'
);

SELECT is(
  tests.affected_rows_as(
    'authenticated',
    'dddddddd-0000-4000-8000-000000000004',
    $sql$
      UPDATE public.missing_place_reports
      SET status = 'en_revue'
      WHERE id = '48000000-0000-4000-8000-000000000001'
    $sql$
  ),
  0::bigint,
  'an incomplete privileged session cannot moderate missing-place reports'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'dddddddd-0000-4000-8000-000000000004',
    $sql$
      INSERT INTO public.organizations (
        type,
        name,
        slug,
        verification_status,
        primary_owner_id,
        created_by
      ) VALUES (
        'institution',
        'Organisation interdite onboarding incomplet',
        'organisation-interdite-onboarding-incomplet',
        'verified',
        'dddddddd-0000-4000-8000-000000000004',
        'dddddddd-0000-4000-8000-000000000004'
      )
    $sql$
  ),
  'an incomplete verified admin cannot create organizations'
);

SELECT is(
  tests.affected_rows_as(
    'authenticated',
    'dddddddd-0000-4000-8000-000000000004',
    $sql$
      UPDATE public.organizations
      SET name = 'Mutation organisation interdite'
      WHERE id = '40000000-0000-4000-8000-000000000001'
    $sql$
  ),
  0::bigint,
  'an incomplete verified admin cannot update organizations'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'dddddddd-0000-4000-8000-000000000004',
    $sql$
      INSERT INTO public.organization_members (
        organization_id,
        user_id,
        role,
        status,
        accepted_at
      ) VALUES (
        '40000000-0000-4000-8000-000000000001',
        'cccccccc-0000-4000-8000-000000000003',
        'moderateur',
        'active',
        now()
      )
    $sql$
  ),
  'an incomplete verified admin cannot create organization members'
);

SELECT is(
  tests.affected_rows_as(
    'authenticated',
    'dddddddd-0000-4000-8000-000000000004',
    $sql$
      UPDATE public.organization_members
      SET status = 'suspended', suspended_at = now()
      WHERE id = '41000000-0000-4000-8000-000000000002'
    $sql$
  ),
  0::bigint,
  'an incomplete verified admin cannot update organization members'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'dddddddd-0000-4000-8000-000000000004',
    $sql$
      INSERT INTO public.organization_invites (
        organization_id,
        email,
        token_hash,
        proposed_role,
        invited_by_member_id,
        expires_at
      ) VALUES (
        '40000000-0000-4000-8000-000000000001',
        'new-incomplete-invite@kwabor.test',
        'incomplete-cannot-create-invite-token-hash-0001',
        'editeur',
        '41000000-0000-4000-8000-000000000001',
        now() + interval '7 days'
      )
    $sql$
  ),
  'an incomplete organization owner cannot create invites'
);

SELECT is(
  tests.affected_rows_as(
    'authenticated',
    'dddddddd-0000-4000-8000-000000000004',
    $sql$
      UPDATE public.organization_invites
      SET status = 'revoked', revoked_at = now()
      WHERE id = '42000000-0000-4000-8000-000000000001'
    $sql$
  ),
  0::bigint,
  'an incomplete organization owner cannot update invites'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'dddddddd-0000-4000-8000-000000000004',
    $sql$
      INSERT INTO public.member_ad_budgets (
        organization_id,
        member_id,
        allocated_by_member_id,
        period_start,
        period_end,
        allocated_xof
      ) VALUES (
        '40000000-0000-4000-8000-000000000001',
        '41000000-0000-4000-8000-000000000002',
        '41000000-0000-4000-8000-000000000001',
        DATE '2026-07-01',
        DATE '2026-07-31',
        50000
      )
    $sql$
  ),
  'an incomplete organization owner cannot allocate budgets'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'dddddddd-0000-4000-8000-000000000004',
    'SELECT * FROM public.like_listing(''00000000-0000-4000-8000-000000000102'')'
  ),
  'the like RPC rejects incomplete sessions before mutation'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'dddddddd-0000-4000-8000-000000000004',
    'SELECT * FROM public.unlike_listing(''00000000-0000-4000-8000-000000000101'')'
  ),
  'the unlike RPC rejects incomplete sessions before mutation'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'dddddddd-0000-4000-8000-000000000004',
    'SELECT * FROM public.add_listing_to_favorites(''00000000-0000-4000-8000-000000000102'')'
  ),
  'the favorite RPC rejects incomplete sessions before mutation'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'dddddddd-0000-4000-8000-000000000004',
    'SELECT * FROM public.remove_listing_from_favorites(''00000000-0000-4000-8000-000000000101'')'
  ),
  'the unfavorite RPC rejects incomplete sessions before mutation'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'dddddddd-0000-4000-8000-000000000004',
    $sql$
      SELECT * FROM public.create_organization_invite(
        '40000000-0000-4000-8000-000000000001',
        '41000000-0000-4000-8000-000000000001',
        'rpc-incomplete@kwabor.test',
        'editeur',
        now() + interval '7 days'
      )
    $sql$
  ),
  'the organization invite creation RPC rejects incomplete sessions'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'dddddddd-0000-4000-8000-000000000004',
    $sql$
      SELECT * FROM public.revoke_organization_invite(
        '42000000-0000-4000-8000-000000000001'
      )
    $sql$
  ),
  'the organization invite revocation RPC rejects incomplete sessions'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'dddddddd-0000-4000-8000-000000000004',
    $sql$
      SELECT * FROM public.suspend_organization_member(
        '40000000-0000-4000-8000-000000000001',
        '41000000-0000-4000-8000-000000000002'
      )
    $sql$
  ),
  'the organization suspension RPC rejects incomplete sessions'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'dddddddd-0000-4000-8000-000000000004',
    'SELECT * FROM public.accept_organization_invite(''accept-incomplete-token'')'
  ),
  'the security-definer invitation acceptance RPC rejects incomplete sessions'
);

SELECT ok(
  tests.statement_fails_as(
    'anon',
    NULL,
    $sql$
      SELECT * FROM public.complete_user_onboarding(
        'Anon',
        'Interdit',
        'cotonou',
        'fr',
        'XOF',
        '10000000-0000-4000-8000-000000000001',
        '10000000-0000-4000-8000-000000000002',
        '10000000-0000-4000-8000-000000000003'
      )
    $sql$
  ),
  'anonymous clients cannot complete onboarding'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'aaaaaaaa-0000-4000-8000-000000000001',
    $sql$
      SELECT * FROM public.complete_user_onboarding(
        '   ', 'Utilisateur', 'cotonou', 'fr', 'XOF',
        '10000000-0000-4000-8000-000000000001',
        '10000000-0000-4000-8000-000000000002',
        '10000000-0000-4000-8000-000000000003'
      )
    $sql$
  ),
  'blank identity is rejected'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'aaaaaaaa-0000-4000-8000-000000000001',
    $sql$
      SELECT * FROM public.complete_user_onboarding(
        'Ada', 'Utilisateur', 'disabled-test-city', 'fr', 'XOF',
        '10000000-0000-4000-8000-000000000001',
        '10000000-0000-4000-8000-000000000002',
        '10000000-0000-4000-8000-000000000003'
      )
    $sql$
  ),
  'disabled cities are rejected'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'aaaaaaaa-0000-4000-8000-000000000001',
    $sql$
      SELECT * FROM public.complete_user_onboarding(
        'Ada', 'Utilisateur', 'cotonou', 'xx', 'XOF',
        '10000000-0000-4000-8000-000000000001',
        '10000000-0000-4000-8000-000000000002',
        '10000000-0000-4000-8000-000000000003'
      )
    $sql$
  ),
  'unsupported locales are rejected'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'aaaaaaaa-0000-4000-8000-000000000001',
    $sql$
      SELECT * FROM public.complete_user_onboarding(
        'Ada', 'Utilisateur', 'cotonou', 'fr', 'GBP',
        '10000000-0000-4000-8000-000000000001',
        '10000000-0000-4000-8000-000000000002',
        '10000000-0000-4000-8000-000000000003'
      )
    $sql$
  ),
  'unsupported currencies are rejected'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'aaaaaaaa-0000-4000-8000-000000000001',
    $sql$
      SELECT * FROM public.complete_user_onboarding(
        'Ada', 'Utilisateur', 'cotonou', 'fr', 'XOF',
        '10000000-0000-4000-8000-000000000001',
        '10000000-0000-4000-8000-000000000001',
        '10000000-0000-4000-8000-000000000003'
      )
    $sql$
  ),
  'a legal revision cannot satisfy the wrong document type'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'aaaaaaaa-0000-4000-8000-000000000001',
    $sql$
      SELECT * FROM public.complete_user_onboarding(
        'Ada', 'Utilisateur', 'cotonou', 'fr', 'XOF',
        '10000000-0000-4000-8000-000000000004',
        '10000000-0000-4000-8000-000000000002',
        '10000000-0000-4000-8000-000000000003'
      )
    $sql$
  ),
  'inactive legal revisions are rejected'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'aaaaaaaa-0000-4000-8000-000000000001',
    $sql$
      SELECT * FROM public.complete_user_onboarding(
        'Ada', 'Utilisateur', 'cotonou', 'fr', 'XOF',
        NULL,
        '10000000-0000-4000-8000-000000000002',
        '10000000-0000-4000-8000-000000000003'
      )
    $sql$
  ),
  'missing legal revisions are rejected'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'dddddddd-0000-4000-8000-000000000004',
    $sql$
      SELECT * FROM public.complete_user_onboarding(
        'Profil', 'Incomplet', 'cotonou', 'fr', 'XOF',
        '10000000-0000-4000-8000-000000000001',
        '10000000-0000-4000-8000-000000000002',
        '10000000-0000-4000-8000-000000000003'
      )
    $sql$
  ),
  'an OTP-only session cannot bypass the required initial password'
);

SELECT ok(
  tests.statement_succeeds_as(
    'authenticated',
    'aaaaaaaa-0000-4000-8000-000000000001',
    $sql$
      SELECT * FROM public.complete_user_onboarding(
        '  Ada  ', '  Utilisateur  ', 'cotonou', 'fr', 'EUR',
        '10000000-0000-4000-8000-000000000001',
        '10000000-0000-4000-8000-000000000002',
        '10000000-0000-4000-8000-000000000003'
      )
    $sql$
  ),
  'an authenticated user completes onboarding through the RPC'
);

SELECT tests.use_auth_context(
  'authenticated',
  'aaaaaaaa-0000-4000-8000-000000000001'
);
SELECT is(
  app_private.current_user_has_completed_onboarding(),
  true,
  'the authorization helper recognizes a completed onboarding profile'
);
RESET ROLE;

SELECT ok(
  tests.statement_succeeds_as(
    'authenticated',
    'aaaaaaaa-0000-4000-8000-000000000001',
    $sql$
      DO $do$
      BEGIN
        PERFORM public.like_listing('00000000-0000-4000-8000-000000000104');
        PERFORM public.unlike_listing('00000000-0000-4000-8000-000000000104');
      END
      $do$
    $sql$
  ),
  'product mutation RPCs become available after onboarding completion'
);

SELECT ok(
  tests.statement_succeeds_as(
    'authenticated',
    'aaaaaaaa-0000-4000-8000-000000000001',
    $sql$
      INSERT INTO public.favorites (user_id, listing_id)
      VALUES (
        'aaaaaaaa-0000-4000-8000-000000000001',
        '00000000-0000-4000-8000-000000000104'
      )
    $sql$
  ),
  'direct RLS-authorized product writes become available after onboarding completion'
);

SELECT is(
  tests.affected_rows_as(
    'authenticated',
    'aaaaaaaa-0000-4000-8000-000000000001',
    $sql$
      DELETE FROM public.favorites
      WHERE user_id = 'aaaaaaaa-0000-4000-8000-000000000001'
        AND listing_id = '00000000-0000-4000-8000-000000000104'
    $sql$
  ),
  1::bigint,
  'completed users can remove their own product interaction rows'
);

SELECT is(
  tests.count_as(
    'authenticated',
    'aaaaaaaa-0000-4000-8000-000000000001',
    $sql$
      SELECT * FROM public.complete_user_onboarding(
        'Ada', 'Utilisateur', 'cotonou', 'fr', 'EUR',
        '10000000-0000-4000-8000-000000000001',
        '10000000-0000-4000-8000-000000000002',
        '10000000-0000-4000-8000-000000000003'
      )
    $sql$
  ),
  1::bigint,
  'the RPC returns exactly one profile on idempotent success'
);

SELECT is(
  (
    SELECT count(*)
    FROM public.profiles
    WHERE user_id = 'aaaaaaaa-0000-4000-8000-000000000001'
      AND onboarding_completed_at IS NOT NULL
  ),
  1::bigint,
  'completion creates one completed profile'
);

SELECT is(
  (
    SELECT count(*)
    FROM public.user_roles
    WHERE user_id = 'aaaaaaaa-0000-4000-8000-000000000001'
      AND role = 'user'
      AND verification_status = 'unverified'
  ),
  1::bigint,
  'completion creates one unverified base user role'
);

SELECT is(
  (
    SELECT count(*)
    FROM public.user_legal_acceptances
    WHERE user_id = 'aaaaaaaa-0000-4000-8000-000000000001'
  ),
  3::bigint,
  'completion records the three legal acceptances'
);

SELECT is(
  (
    SELECT first_name || '|' || last_name || '|' || city_id || '|' || preferred_currency
    FROM public.profiles
    WHERE user_id = 'aaaaaaaa-0000-4000-8000-000000000001'
  ),
  'Ada|Utilisateur|cotonou|EUR',
  'profile values are normalized and persisted'
);

SELECT is(
  tests.count_as(
    'authenticated',
    'aaaaaaaa-0000-4000-8000-000000000001',
    $sql$
      SELECT * FROM public.complete_user_onboarding(
        'Tentative', 'Ecrasement', 'ouidah', 'fr', 'USD',
        '10000000-0000-4000-8000-000000000001',
        '10000000-0000-4000-8000-000000000002',
        '10000000-0000-4000-8000-000000000003'
      )
    $sql$
  ),
  1::bigint,
  'retry returns exactly one existing profile'
);

SELECT is(
  (
    SELECT first_name || '|' || city_id || '|' || preferred_currency
    FROM public.profiles
    WHERE user_id = 'aaaaaaaa-0000-4000-8000-000000000001'
  ),
  'Ada|cotonou|EUR',
  'retry cannot overwrite a completed profile'
);

SELECT is(
  (
    SELECT count(*)
    FROM public.user_roles
    WHERE user_id = 'aaaaaaaa-0000-4000-8000-000000000001'
      AND role = 'user'
  ),
  1::bigint,
  'retry does not duplicate the base role'
);

SELECT is(
  (
    SELECT count(*)
    FROM public.user_legal_acceptances
    WHERE user_id = 'aaaaaaaa-0000-4000-8000-000000000001'
  ),
  3::bigint,
  'retry does not duplicate legal acceptances'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'aaaaaaaa-0000-4000-8000-000000000001',
    $sql$
      UPDATE public.profiles
      SET onboarding_completed_at = NULL
      WHERE user_id = 'aaaaaaaa-0000-4000-8000-000000000001'
    $sql$
  ),
  'clients cannot directly mutate onboarding completion authority'
);

SELECT ok(
  tests.statement_succeeds_as(
    'authenticated',
    'aaaaaaaa-0000-4000-8000-000000000001',
    $sql$
      UPDATE public.profiles
      SET first_name = 'Ada-Mae'
      WHERE user_id = 'aaaaaaaa-0000-4000-8000-000000000001'
    $sql$
  ),
  'completed users retain scoped profile editing rights'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'aaaaaaaa-0000-4000-8000-000000000001',
    $sql$
      UPDATE public.profiles
      SET first_name = repeat('A', 81)
      WHERE user_id = 'aaaaaaaa-0000-4000-8000-000000000001'
    $sql$
  ),
  'profile edits cannot bypass the server identity length limit'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'aaaaaaaa-0000-4000-8000-000000000001',
    $sql$
      UPDATE public.user_legal_acceptances
      SET accepted_at = now() + interval '1 day'
      WHERE user_id = 'aaaaaaaa-0000-4000-8000-000000000001'
    $sql$
  ),
  'clients cannot alter legal acceptance evidence'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'aaaaaaaa-0000-4000-8000-000000000001',
    $sql$
      DELETE FROM public.user_legal_acceptances
      WHERE user_id = 'aaaaaaaa-0000-4000-8000-000000000001'
    $sql$
  ),
  'clients cannot delete legal acceptance evidence'
);

SELECT is(
  tests.count_as(
    'authenticated',
    'aaaaaaaa-0000-4000-8000-000000000001',
    'SELECT legal_document_id FROM public.user_legal_acceptances'
  ),
  3::bigint,
  'a user reads their own legal acceptance evidence'
);

SELECT is(
  tests.count_as(
    'authenticated',
    'bbbbbbbb-0000-4000-8000-000000000002',
    'SELECT legal_document_id FROM public.user_legal_acceptances'
  ),
  0::bigint,
  'another user cannot read foreign legal acceptance evidence'
);

SELECT is(
  tests.count_as(
    'anon',
    NULL,
    $sql$
      SELECT user_id FROM public.profiles
      WHERE user_id = 'aaaaaaaa-0000-4000-8000-000000000001'
    $sql$
  ),
  1::bigint,
  'completed profiles remain publicly readable'
);

SELECT is(
  tests.count_as(
    'anon',
    NULL,
    $sql$
      SELECT user_id FROM public.profiles
      WHERE user_id = 'dddddddd-0000-4000-8000-000000000004'
    $sql$
  ),
  0::bigint,
  'incomplete profiles are not publicly exposed'
);

SELECT is(
  tests.count_as(
    'authenticated',
    'dddddddd-0000-4000-8000-000000000004',
    $sql$
      SELECT user_id FROM public.profiles
      WHERE user_id = 'dddddddd-0000-4000-8000-000000000004'
    $sql$
  ),
  1::bigint,
  'an incomplete user can restore their own onboarding status'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'cccccccc-0000-4000-8000-000000000003',
    $sql$
      SELECT * FROM public.complete_user_onboarding(
        'Rollback', 'Utilisateur', 'cotonou', 'fr', 'XOF',
        '10000000-0000-4000-8000-000000000001',
        '10000000-0000-4000-8000-000000000002',
        '10000000-0000-4000-8000-000000000003'
      )
    $sql$
  ),
  'a downstream persistence failure aborts onboarding'
);

SELECT is(
  (SELECT count(*) FROM public.profiles WHERE user_id = 'cccccccc-0000-4000-8000-000000000003'),
  0::bigint,
  'transaction rollback removes the partial profile'
);

SELECT is(
  (SELECT count(*) FROM public.user_roles WHERE user_id = 'cccccccc-0000-4000-8000-000000000003'),
  0::bigint,
  'transaction rollback leaves no base role'
);

SELECT is(
  (
    SELECT count(*)
    FROM public.user_legal_acceptances
    WHERE user_id = 'cccccccc-0000-4000-8000-000000000003'
  ),
  0::bigint,
  'transaction rollback leaves no legal acceptance evidence'
);

SELECT ok(
  tests.statement_succeeds_as(
    'authenticated',
    'bbbbbbbb-0000-4000-8000-000000000002',
    $sql$
      SELECT * FROM public.complete_user_onboarding(
        'Grace', 'Utilisateur', 'ouidah', 'fr', 'XOF',
        '10000000-0000-4000-8000-000000000001',
        '10000000-0000-4000-8000-000000000002',
        '10000000-0000-4000-8000-000000000003'
      )
    $sql$
  ),
  'a second user can complete onboarding independently'
);

SELECT is(
  (
    SELECT count(*)
    FROM public.profiles
    WHERE user_id = 'bbbbbbbb-0000-4000-8000-000000000002'
      AND onboarding_completed_at IS NOT NULL
  ),
  1::bigint,
  'the second user receives one completed profile'
);

SELECT is(
  tests.count_as(
    'authenticated',
    'bbbbbbbb-0000-4000-8000-000000000002',
    'SELECT legal_document_id FROM public.user_legal_acceptances'
  ),
  3::bigint,
  'the second user reads only their three acceptance rows'
);

SELECT ok(
  NOT has_table_privilege('authenticated', 'public.profiles', 'INSERT'),
  'authenticated clients have no profile insert grant'
);

SELECT ok(
  NOT has_table_privilege('authenticated', 'public.user_roles', 'INSERT'),
  'authenticated clients have no user role insert grant'
);

SELECT ok(
  NOT has_column_privilege(
    'authenticated',
    'public.profiles',
    'onboarding_completed_at',
    'UPDATE'
  ),
  'onboarding completion timestamp is not client-writable'
);

SELECT * FROM finish();
ROLLBACK;
