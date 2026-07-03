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

SELECT plan(12);

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
    '11111111-1111-4111-8111-111111111111',
    'authenticated',
    'authenticated',
    'invited-editor@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    '22222222-2222-4222-8222-222222222222',
    'authenticated',
    'authenticated',
    'wrong-invite-user@kwabor.test',
    '',
    now(),
    now(),
    now()
  );

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
  'organisation-promoteur-test-rpc',
  'verified',
  'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1',
  'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1'
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

SELECT ok(
  to_regprocedure(
    'public.create_organization_invite(uuid, uuid, text, public.organization_role, timestamp with time zone)'
  ) IS NOT NULL,
  'create organization invite RPC exists'
);

SELECT ok(
  to_regprocedure('public.accept_organization_invite(text)') IS NOT NULL,
  'accept organization invite RPC exists'
);

SELECT ok(
  tests.statement_succeeds_as(
    'authenticated',
    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1',
    $sql$
      SELECT * FROM public.create_organization_invite(
        '10000000-0000-4000-8000-000000000001',
        '20000000-0000-4000-8000-000000000001',
        'New-Manager@Kwabor.Test',
        'gestionnaire',
        now() + interval '7 days'
      )
    $sql$
  ),
  'owner can create manager invitation through RPC'
);

SELECT is(
  tests.count_as(
    'authenticated',
    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1',
    'SELECT id FROM public.organization_invites WHERE email = ''new-manager@kwabor.test'''
  ),
  1::bigint,
  'create invitation RPC normalizes email'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2',
    $sql$
      SELECT * FROM public.create_organization_invite(
        '10000000-0000-4000-8000-000000000001',
        '20000000-0000-4000-8000-000000000002',
        'forbidden-manager@kwabor.test',
        'gestionnaire',
        now() + interval '7 days'
      )
    $sql$
  ),
  'manager cannot create manager invitation through RPC'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'cccccccc-cccc-4ccc-8ccc-ccccccccccc3',
    $sql$
      SELECT * FROM public.create_organization_invite(
        '10000000-0000-4000-8000-000000000001',
        '20000000-0000-4000-8000-000000000003',
        'forbidden-moderator@kwabor.test',
        'moderateur',
        now() + interval '7 days'
      )
    $sql$
  ),
  'editor cannot create team invitation through RPC'
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
    '30000000-0000-4000-8000-000000000001',
    '10000000-0000-4000-8000-000000000001',
    'revoked-editor@kwabor.test',
    encode(extensions.digest('revoked-editor-token', 'sha256'), 'hex'),
    'editeur',
    '20000000-0000-4000-8000-000000000001',
    now() + interval '7 days'
  ),
  (
    '30000000-0000-4000-8000-000000000002',
    '10000000-0000-4000-8000-000000000001',
    'invited-editor@kwabor.test',
    encode(extensions.digest('valid-editor-token', 'sha256'), 'hex'),
    'editeur',
    '20000000-0000-4000-8000-000000000001',
    now() + interval '7 days'
  ),
  (
    '30000000-0000-4000-8000-000000000003',
    '10000000-0000-4000-8000-000000000001',
    'invited-editor@kwabor.test',
    encode(extensions.digest('wrong-user-token', 'sha256'), 'hex'),
    'editeur',
    '20000000-0000-4000-8000-000000000001',
    now() + interval '7 days'
  );

SELECT ok(
  tests.statement_succeeds_as(
    'authenticated',
    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1',
    $sql$
      SELECT * FROM public.revoke_organization_invite(
        '30000000-0000-4000-8000-000000000001'
      )
    $sql$
  ),
  'owner can revoke pending invitation through RPC'
);

SELECT ok(
  tests.statement_succeeds_as(
    'authenticated',
    '11111111-1111-4111-8111-111111111111',
    $sql$
      SELECT * FROM public.accept_organization_invite('valid-editor-token')
    $sql$
  ),
  'invited user can accept matching token through RPC'
);

SELECT is(
  tests.count_as(
    'authenticated',
    'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2',
    'SELECT id FROM public.organization_members WHERE user_id = ''11111111-1111-4111-8111-111111111111'' AND status = ''active'''
  ),
  1::bigint,
  'accept invitation RPC activates organization member'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    '22222222-2222-4222-8222-222222222222',
    $sql$
      SELECT * FROM public.accept_organization_invite('wrong-user-token')
    $sql$
  ),
  'wrong authenticated user cannot accept another email invitation'
);

SELECT ok(
  tests.statement_succeeds_as(
    'authenticated',
    'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2',
    $sql$
      SELECT * FROM public.suspend_organization_member(
        '10000000-0000-4000-8000-000000000001',
        '20000000-0000-4000-8000-000000000003'
      )
    $sql$
  ),
  'manager can suspend editor through RPC'
);

SELECT ok(
  tests.statement_fails_as(
    'authenticated',
    'dddddddd-dddd-4ddd-8ddd-ddddddddddd4',
    $sql$
      SELECT * FROM public.suspend_organization_member(
        '10000000-0000-4000-8000-000000000001',
        '20000000-0000-4000-8000-000000000002'
      )
    $sql$
  ),
  'moderator cannot suspend manager through RPC'
);

SELECT * FROM finish();
ROLLBACK;
