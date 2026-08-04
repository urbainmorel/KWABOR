begin;

select plan(35);

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
    'a7000000-0000-4000-8000-000000000001',
    'authenticated',
    'authenticated',
    'sec001f-live@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    'a7000000-0000-4000-8000-000000000002',
    'authenticated',
    'authenticated',
    'sec001f-future@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    'a7000000-0000-4000-8000-000000000003',
    'authenticated',
    'authenticated',
    'sec001f-rejected@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    'a7000000-0000-4000-8000-000000000004',
    'authenticated',
    'authenticated',
    'sec001f-foreign@kwabor.test',
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
  (
    'a7000000-0000-4000-8000-000000000001',
    'Session',
    'Active',
    'cotonou',
    now()
  ),
  (
    'a7000000-0000-4000-8000-000000000002',
    'Session',
    'Future',
    'cotonou',
    now()
  ),
  (
    'a7000000-0000-4000-8000-000000000003',
    'Session',
    'Rejected',
    'cotonou',
    now()
  ),
  (
    'a7000000-0000-4000-8000-000000000004',
    'Session',
    'Foreign',
    'cotonou',
    now()
  );

insert into auth.sessions (
  id,
  user_id,
  created_at,
  updated_at,
  not_after
)
values
  (
    'e7000000-0000-4000-8000-000000000001',
    'a7000000-0000-4000-8000-000000000001',
    statement_timestamp(),
    statement_timestamp(),
    null
  ),
  (
    'e7000000-0000-4000-8000-000000000002',
    'a7000000-0000-4000-8000-000000000002',
    statement_timestamp(),
    statement_timestamp(),
    statement_timestamp() + interval '1 hour'
  ),
  (
    'e7000000-0000-4000-8000-000000000003',
    'a7000000-0000-4000-8000-000000000003',
    statement_timestamp(),
    statement_timestamp(),
    statement_timestamp() - interval '1 minute'
  ),
  (
    'e7000000-0000-4000-8000-000000000004',
    'a7000000-0000-4000-8000-000000000004',
    statement_timestamp(),
    statement_timestamp(),
    null
  );

select ok(
  to_regprocedure(
    'public.prepare_account_deletion_with_session(uuid,uuid,uuid)'
  ) is not null,
  'the session-bound account deletion preparation exists'
);

select ok(
  (
    select
      procedure.prosecdef
      and procedure.provolatile = 'v'::"char"
      and array_to_string(procedure.proconfig, ',') = 'search_path=""'
    from pg_catalog.pg_proc as procedure
    where procedure.oid =
      'public.prepare_account_deletion_with_session(uuid,uuid,uuid)'::regprocedure
  ),
  'the session-bound preparation is a hardened volatile SECURITY DEFINER function'
);

select ok(
  position(
    'for key share of auth_session' in lower(
      pg_get_functiondef(
        'public.prepare_account_deletion_with_session(uuid,uuid,uuid)'::regprocedure
      )
    )
  ) > 0,
  'the live session row is locked through the first account mutation'
);

select ok(
  has_function_privilege(
    'service_role',
    'public.prepare_account_deletion_with_session(uuid,uuid,uuid)',
    'execute'
  ),
  'service_role can execute the session-bound preparation'
);
select ok(
  not has_function_privilege(
    'anon',
    'public.prepare_account_deletion_with_session(uuid,uuid,uuid)',
    'execute'
  ),
  'anonymous clients cannot execute the session-bound preparation'
);
select ok(
  not has_function_privilege(
    'authenticated',
    'public.prepare_account_deletion_with_session(uuid,uuid,uuid)',
    'execute'
  ),
  'authenticated clients cannot execute the session-bound preparation'
);
select ok(
  not exists (
    select 1
    from pg_catalog.pg_proc as procedure
    cross join lateral pg_catalog.aclexplode(procedure.proacl) as privilege
    where procedure.oid =
      'public.prepare_account_deletion_with_session(uuid,uuid,uuid)'::regprocedure
      and privilege.grantee = 0
      and privilege.privilege_type = 'EXECUTE'
  ),
  'PUBLIC has no implicit execution grant on the session-bound preparation'
);

set local role service_role;
select is(
  (
    select status
    from public.prepare_account_deletion_with_session(
      'a7000000-0000-4000-8000-000000000001',
      'e7000000-0000-4000-8000-000000000001',
      'd7000000-0000-4000-8000-000000000001'
    )
  ),
  'prepared',
  'a matching live session without not_after starts account deletion'
);
reset role;

select ok(
  exists (
    select 1
    from public.profiles
    where user_id = 'a7000000-0000-4000-8000-000000000001'
      and onboarding_completed_at is not null
  ),
  'preparation retains the completed profile needed for a safe retry'
);
select ok(
  exists (
    select 1
    from public.profiles
    where user_id = 'a7000000-0000-4000-8000-000000000001'
      and first_name = 'Compte'
      and last_name = 'Suppression'
      and avatar_url is null
      and cover_url is null
      and bio is null
      and city_id is null
      and preferred_locale = 'fr'
      and preferred_currency = 'XOF'
      and onboarding_completed_at is not null
  ),
  'the retained retry profile contains no user-provided profile data'
);
select is(
  (
    select idempotency_key
    from public.account_deletion_requests
    where user_id = 'a7000000-0000-4000-8000-000000000001'
  ),
  'd7000000-0000-4000-8000-000000000001'::uuid,
  'the session-bound preparation records the requested operation key'
);
select ok(
  exists (
    select 1
    from auth.sessions
    where id = 'e7000000-0000-4000-8000-000000000001'
  ),
  'preparation leaves session revocation to the following server step'
);
select set_config(
  'request.jwt.claims',
  '{"role":"anon"}',
  true
);
set local role anon;
select is(
  (
    select count(*)
    from public.profiles
    where user_id = 'a7000000-0000-4000-8000-000000000001'
  ),
  0::bigint,
  'a retained retry profile is hidden from public readers'
);
reset role;
select set_config(
  'request.jwt.claims',
  '{"role":"authenticated","sub":"a7000000-0000-4000-8000-000000000001"}',
  true
);
set local role authenticated;
select is(
  (
    select count(*)
    from public.profiles
    where user_id = 'a7000000-0000-4000-8000-000000000001'
  ),
  1::bigint,
  'the account owner can still read the retained profile needed for routing'
);
select ok(
  not app_private.current_user_has_completed_onboarding(),
  'the tombstone keeps all completed-onboarding mutation guards closed'
);
select is_empty(
  $sql$
    update public.profiles
    set first_name = 'Mutation interdite'
    where user_id = 'a7000000-0000-4000-8000-000000000001'
    returning 1
  $sql$,
  'the retained retry profile cannot be modified'
);
reset role;

set local role service_role;
select is(
  (
    select status
    from public.prepare_account_deletion_with_session(
      'a7000000-0000-4000-8000-000000000002',
      'e7000000-0000-4000-8000-000000000002',
      'd7000000-0000-4000-8000-000000000002'
    )
  ),
  'prepared',
  'a matching session with a future not_after starts account deletion'
);
reset role;
select ok(
  exists (
    select 1
    from public.profiles
    where user_id = 'a7000000-0000-4000-8000-000000000002'
      and onboarding_completed_at is not null
  ),
  'a future not_after prepares deletion without hiding the retry route'
);

set local role service_role;
select throws_ok(
  $sql$
    select *
    from public.prepare_account_deletion_with_session(
      'a7000000-0000-4000-8000-000000000003',
      'e7000000-0000-4000-8000-000000000004',
      'd7000000-0000-4000-8000-000000000003'
    )
  $sql$,
  '42501',
  'Live authentication session required',
  'a session owned by another user is rejected'
);
reset role;
select ok(
  exists (
    select 1
    from public.profiles
    where user_id = 'a7000000-0000-4000-8000-000000000003'
  ),
  'a foreign session cannot clean the target profile'
);
select is(
  (
    select count(*)::integer
    from public.account_deletion_requests
    where user_id = 'a7000000-0000-4000-8000-000000000003'
  ),
  0,
  'a foreign session cannot create a deletion tombstone'
);

set local role service_role;
select throws_ok(
  $sql$
    select *
    from public.prepare_account_deletion_with_session(
      'a7000000-0000-4000-8000-000000000003',
      'e7000000-0000-4000-8000-000000000003',
      'd7000000-0000-4000-8000-000000000003'
    )
  $sql$,
  '42501',
  'Live authentication session required',
  'an expired session is rejected'
);
reset role;
select ok(
  exists (
    select 1
    from public.profiles
    where user_id = 'a7000000-0000-4000-8000-000000000003'
  ),
  'an expired session cannot clean the target profile'
);
select is(
  (
    select count(*)::integer
    from public.account_deletion_requests
    where user_id = 'a7000000-0000-4000-8000-000000000003'
  ),
  0,
  'an expired session cannot create a deletion tombstone'
);

set local role service_role;
select throws_ok(
  $sql$
    select *
    from public.prepare_account_deletion_with_session(
      'a7000000-0000-4000-8000-000000000003',
      'e7000000-0000-4000-8000-000000000005',
      'd7000000-0000-4000-8000-000000000003'
    )
  $sql$,
  '42501',
  'Live authentication session required',
  'an absent session is rejected'
);
reset role;
select ok(
  exists (
    select 1
    from public.profiles
    where user_id = 'a7000000-0000-4000-8000-000000000003'
  ),
  'an absent session cannot clean the target profile'
);
select is(
  (
    select count(*)::integer
    from public.account_deletion_requests
    where user_id = 'a7000000-0000-4000-8000-000000000003'
  ),
  0,
  'an absent session cannot create a deletion tombstone'
);

set local role service_role;
select throws_ok(
  $sql$
    select *
    from public.prepare_account_deletion_with_session(
      'a7000000-0000-4000-8000-000000000004',
      'e7000000-0000-4000-8000-000000000004',
      null
    )
  $sql$,
  '22023',
  'Invalid account deletion request',
  'a null operation key is rejected before any account mutation'
);
reset role;
select ok(
  exists (
    select 1
    from public.profiles
    where user_id = 'a7000000-0000-4000-8000-000000000004'
  )
  and not exists (
    select 1
    from public.account_deletion_requests
    where user_id = 'a7000000-0000-4000-8000-000000000004'
  ),
  'an invalid operation key leaves the account untouched'
);

delete from auth.sessions
where id = 'e7000000-0000-4000-8000-000000000001';

insert into auth.sessions (
  id,
  user_id,
  created_at,
  updated_at,
  not_after
)
values (
  'e7000000-0000-4000-8000-000000000009',
  'a7000000-0000-4000-8000-000000000001',
  statement_timestamp(),
  statement_timestamp(),
  statement_timestamp() + interval '1 hour'
);

set local role service_role;
create temporary table sec001f_restarted_deletion as
select *
from public.prepare_account_deletion_with_session(
  'a7000000-0000-4000-8000-000000000001',
  'e7000000-0000-4000-8000-000000000009',
  'd7000000-0000-4000-8000-000000000009'
);
reset role;

select is(
  (select status from sec001f_restarted_deletion),
  'prepared',
  'the existing preparation remains replayable after session revocation'
);
select is(
  (select effective_idempotency_key from sec001f_restarted_deletion),
  'd7000000-0000-4000-8000-000000000001'::uuid,
  'the post-revocation replay keeps the original effective operation key'
);

set local role service_role;
select throws_ok(
  $sql$
    select *
    from public.mark_account_deletion_completed(
      'a7000000-0000-4000-8000-000000000001',
      'd7000000-0000-4000-8000-000000000001'
    )
  $sql$,
  '55000',
  'Auth user still present',
  'completion is rejected while the Auth user still exists'
);
reset role;
select is(
  (
    select status
    from public.account_deletion_requests
    where user_id = 'a7000000-0000-4000-8000-000000000001'
  ),
  'prepared'::public.account_deletion_status,
  'a rejected early completion keeps the tombstone prepared'
);

delete from auth.users
where id = 'a7000000-0000-4000-8000-000000000001';

set local role service_role;
select is(
  (
    select status
    from public.mark_account_deletion_completed(
      'a7000000-0000-4000-8000-000000000001',
      'd7000000-0000-4000-8000-000000000001'
    )
  ),
  'completed',
  'completion succeeds after the Auth user is absent'
);
reset role;
select ok(
  not exists (
    select 1
    from public.profiles
    where user_id = 'a7000000-0000-4000-8000-000000000001'
  ),
  'Auth deletion and final cleanup remove the retained profile'
);

select * from finish();
rollback;
