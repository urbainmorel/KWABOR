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

SELECT plan(21);

INSERT INTO auth.users (id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
VALUES
  (
    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1',
    'authenticated',
    'authenticated',
    'owner@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2',
    'authenticated',
    'authenticated',
    'manager@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    'cccccccc-cccc-4ccc-8ccc-ccccccccccc3',
    'authenticated',
    'authenticated',
    'editor@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    'dddddddd-dddd-4ddd-8ddd-ddddddddddd4',
    'authenticated',
    'authenticated',
    'moderator@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeee5',
    'authenticated',
    'authenticated',
    'outsider@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    'ffffffff-ffff-4fff-8fff-fffffffffff6',
    'authenticated',
    'authenticated',
    'admin-team@kwabor.test',
    '',
    now(),
    now(),
    now()
  );

INSERT INTO public.profiles (
  user_id,
  first_name,
  last_name,
  city_id,
  onboarding_completed_at
)
VALUES
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1', 'Proprietaire', 'Test', 'cotonou', now()),
  ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2', 'Gestionnaire', 'Test', 'cotonou', now()),
  ('cccccccc-cccc-4ccc-8ccc-ccccccccccc3', 'Editeur', 'Test', 'cotonou', now()),
  ('dddddddd-dddd-4ddd-8ddd-ddddddddddd4', 'Moderateur', 'Test', 'cotonou', now()),
  ('eeeeeeee-eeee-4eee-8eee-eeeeeeeeeee5', 'Externe', 'Test', 'cotonou', now()),
  ('ffffffff-ffff-4fff-8fff-fffffffffff6', 'Admin', 'Equipe', 'cotonou', now());

INSERT INTO public.user_roles (user_id, role, verification_status)
VALUES
  ('ffffffff-ffff-4fff-8fff-fffffffffff6', 'admin', 'verified');

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
  '10000000-0000-4000-8000-000000000001',
  'promoteur',
  'Organisation Promoteur Test',
  'organisation-promoteur-test',
  'verified',
  'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1',
  'ffffffff-ffff-4fff-8fff-fffffffffff6'
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
    '20000000-0000-4000-8000-000000000001',
    '10000000-0000-4000-8000-000000000001',
    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1',
    'proprietaire',
    'active',
    now()
  ),
  (
    '20000000-0000-4000-8000-000000000002',
    '10000000-0000-4000-8000-000000000001',
    'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2',
    'gestionnaire',
    'active',
    now()
  ),
  (
    '20000000-0000-4000-8000-000000000003',
    '10000000-0000-4000-8000-000000000001',
    'cccccccc-cccc-4ccc-8ccc-ccccccccccc3',
    'editeur',
    'active',
    now()
  ),
  (
    '20000000-0000-4000-8000-000000000004',
    '10000000-0000-4000-8000-000000000001',
    'dddddddd-dddd-4ddd-8ddd-ddddddddddd4',
    'moderateur',
    'active',
    now()
  );

SELECT ok(to_regclass('public.organizations') IS NOT NULL, 'organizations table exists');
SELECT ok(to_regclass('public.organization_members') IS NOT NULL, 'organization_members table exists');
SELECT ok(to_regclass('public.organization_invites') IS NOT NULL, 'organization_invites table exists');
SELECT ok(to_regclass('public.member_ad_budgets') IS NOT NULL, 'member_ad_budgets table exists');

SELECT is(
  (
    SELECT count(*)::integer
    FROM pg_class relation
    JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
    WHERE namespace.nspname = 'public'
      AND relation.relname IN (
        'organizations',
        'organization_members',
        'organization_invites',
        'member_ad_budgets'
      )
      AND relation.relrowsecurity
  ),
  4,
  'team access public tables have RLS enabled'
);

SELECT is(
  tests.count_as(
    'authenticated',
    'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeee5',
    'SELECT id FROM public.organizations WHERE id = ''10000000-0000-4000-8000-000000000001'''
  ),
  0::bigint,
  'outsiders cannot read verified organizations'
);

SELECT is(
  tests.count_as(
    'authenticated',
    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1',
    'SELECT id FROM public.organizations WHERE id = ''10000000-0000-4000-8000-000000000001'''
  ),
  1::bigint,
  'owners can read their organization'
);

SELECT is(
  tests.count_as(
    'authenticated',
    'dddddddd-dddd-4ddd-8ddd-ddddddddddd4',
    'SELECT id FROM public.organizations WHERE id = ''10000000-0000-4000-8000-000000000001'''
  ),
  1::bigint,
  'moderators can read their organization context'
);

SELECT ok(
  tests.statement_succeeds_as(
    'authenticated',
    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1',
    $sql$
      INSERT INTO public.organization_invites (
        organization_id,
        email,
        token_hash,
        proposed_role,
        invited_by_member_id,
        expires_at
      )
      VALUES (
        '10000000-0000-4000-8000-000000000001',
        'new-manager@kwabor.test',
        'owner-can-invite-manager-token-hash-000001',
        'gestionnaire',
        '20000000-0000-4000-8000-000000000001',
        now() + interval '7 days'
      )
    $sql$
  ),
  'owners can invite managers'
);

SELECT ok(
  tests.statement_succeeds_as(
    'authenticated',
    'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2',
    $sql$
      INSERT INTO public.organization_invites (
        organization_id,
        email,
        token_hash,
        proposed_role,
        invited_by_member_id,
        expires_at
      )
      VALUES (
        '10000000-0000-4000-8000-000000000001',
        'new-editor@kwabor.test',
        'manager-can-invite-editor-token-hash-0001',
        'editeur',
        '20000000-0000-4000-8000-000000000002',
        now() + interval '7 days'
      )
    $sql$
  ),
  'managers can invite editors'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2',
    $sql$
      INSERT INTO public.organization_invites (
        organization_id,
        email,
        token_hash,
        proposed_role,
        invited_by_member_id,
        expires_at
      )
      VALUES (
        '10000000-0000-4000-8000-000000000001',
        'forbidden-manager@kwabor.test',
        'manager-cannot-invite-manager-token-0001',
        'gestionnaire',
        '20000000-0000-4000-8000-000000000002',
        now() + interval '7 days'
      )
    $sql$
  ),
  'managers cannot invite managers'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'cccccccc-cccc-4ccc-8ccc-ccccccccccc3',
    $sql$
      INSERT INTO public.organization_invites (
        organization_id,
        email,
        token_hash,
        proposed_role,
        invited_by_member_id,
        expires_at
      )
      VALUES (
        '10000000-0000-4000-8000-000000000001',
        'forbidden-moderator@kwabor.test',
        'editor-cannot-invite-moderator-token-01',
        'moderateur',
        '20000000-0000-4000-8000-000000000003',
        now() + interval '7 days'
      )
    $sql$
  ),
  'editors cannot invite team members'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2',
    $sql$
      INSERT INTO public.organization_members (
        organization_id,
        user_id,
        role,
        status,
        accepted_at
      )
      VALUES (
        '10000000-0000-4000-8000-000000000001',
        'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeee5',
        'editeur',
        'active',
        now()
      )
    $sql$
  ),
  'managers cannot activate members directly'
);

SELECT ok(
  tests.statement_succeeds_as(
    'authenticated',
    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1',
    $sql$
      INSERT INTO public.member_ad_budgets (
        organization_id,
        member_id,
        allocated_by_member_id,
        period_start,
        period_end,
        allocated_xof
      )
      VALUES (
        '10000000-0000-4000-8000-000000000001',
        '20000000-0000-4000-8000-000000000002',
        '20000000-0000-4000-8000-000000000001',
        DATE '2026-07-01',
        DATE '2026-07-31',
        50000
      )
    $sql$
  ),
  'owners can allocate ad budget to managers'
);

SELECT ok(
  tests.statement_succeeds_as(
    'authenticated',
    'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2',
    $sql$
      INSERT INTO public.member_ad_budgets (
        organization_id,
        member_id,
        allocated_by_member_id,
        period_start,
        period_end,
        allocated_xof
      )
      VALUES (
        '10000000-0000-4000-8000-000000000001',
        '20000000-0000-4000-8000-000000000003',
        '20000000-0000-4000-8000-000000000002',
        DATE '2026-07-01',
        DATE '2026-07-31',
        40000
      )
    $sql$
  ),
  'managers can allocate budget to editors within their own allocation'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2',
    $sql$
      INSERT INTO public.member_ad_budgets (
        organization_id,
        member_id,
        allocated_by_member_id,
        period_start,
        period_end,
        allocated_xof
      )
      VALUES (
        '10000000-0000-4000-8000-000000000001',
        '20000000-0000-4000-8000-000000000003',
        '20000000-0000-4000-8000-000000000002',
        DATE '2026-08-01',
        DATE '2026-08-31',
        60000
      )
    $sql$
  ),
  'managers cannot allocate more budget than authorized'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2',
    $sql$
      INSERT INTO public.member_ad_budgets (
        organization_id,
        member_id,
        allocated_by_member_id,
        period_start,
        period_end,
        allocated_xof
      )
      VALUES (
        '10000000-0000-4000-8000-000000000001',
        '20000000-0000-4000-8000-000000000004',
        '20000000-0000-4000-8000-000000000002',
        DATE '2026-07-01',
        DATE '2026-07-31',
        5000
      )
    $sql$
  ),
  'managers cannot allocate ad budget to moderators'
);

SELECT is(
  tests.count_as(
    'authenticated',
    'cccccccc-cccc-4ccc-8ccc-ccccccccccc3',
    'SELECT id FROM public.member_ad_budgets WHERE member_id = ''20000000-0000-4000-8000-000000000003'''
  ),
  1::bigint,
  'editors can read their own ad budget'
);

SELECT is(
  tests.count_as(
    'authenticated',
    'dddddddd-dddd-4ddd-8ddd-ddddddddddd4',
    'SELECT id FROM public.member_ad_budgets WHERE member_id = ''20000000-0000-4000-8000-000000000003'''
  ),
  0::bigint,
  'moderators cannot read editor ad budgets'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeee5',
    $sql$
      INSERT INTO public.organizations (
        type,
        name,
        slug,
        verification_status,
        primary_owner_id,
        created_by
      )
      VALUES (
        'promoteur',
        'Organisation interdite utilisateur',
        'organisation-interdite-utilisateur',
        'verified',
        'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeee5',
        'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeee5'
      )
    $sql$
  ),
  'plain authenticated users cannot create organizations'
);

SELECT ok(
  tests.statement_succeeds_as(
    'authenticated',
    'ffffffff-ffff-4fff-8fff-fffffffffff6',
    $sql$
      INSERT INTO public.organizations (
        type,
        name,
        slug,
        verification_status,
        primary_owner_id,
        created_by
      )
      VALUES (
        'institution',
        'Organisation creee par admin',
        'organisation-creee-par-admin',
        'verified',
        'ffffffff-ffff-4fff-8fff-fffffffffff6',
        'ffffffff-ffff-4fff-8fff-fffffffffff6'
      )
    $sql$
  ),
  'verified Kwabor admins can create organizations'
);

SELECT * FROM finish();
ROLLBACK;
