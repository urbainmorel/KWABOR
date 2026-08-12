begin;

create schema if not exists tests;

create or replace function tests.closed_beta_storage_context(
  db_role text,
  uid uuid
)
returns void
language plpgsql
as $$
begin
  execute format('set local role %I', db_role);
  perform set_config('request.jwt.claim.role', db_role, true);
  perform set_config('request.jwt.claim.sub', coalesce(uid::text, ''), true);
  perform set_config(
    'request.jwt.claims',
    jsonb_build_object(
      'role', db_role,
      'sub', coalesce(uid::text, '')
    )::text,
    true
  );
end;
$$;

create or replace function tests.closed_beta_statement_succeeds_as(
  db_role text,
  uid uuid,
  sql text
)
returns boolean
language plpgsql
as $$
begin
  perform tests.closed_beta_storage_context(db_role, uid);
  execute sql;
  reset role;
  return true;
exception
  when others then
    reset role;
    raise notice 'statement_succeeds_as failed: %', sqlerrm;
    return false;
end;
$$;

create or replace function tests.closed_beta_statement_sqlstate_as(
  db_role text,
  uid uuid,
  sql text
)
returns text
language plpgsql
as $$
declare
  failure_sqlstate text;
begin
  perform tests.closed_beta_storage_context(db_role, uid);
  execute sql;
  reset role;
  return null;
exception
  when others then
    get stacked diagnostics failure_sqlstate = returned_sqlstate;
    reset role;
    return failure_sqlstate;
end;
$$;

create or replace function tests.closed_beta_affected_rows_as(
  db_role text,
  uid uuid,
  sql text
)
returns bigint
language plpgsql
as $$
declare
  affected_rows bigint;
begin
  perform tests.closed_beta_storage_context(db_role, uid);
  execute sql;
  get diagnostics affected_rows = row_count;
  reset role;
  return affected_rows;
exception
  when others then
    reset role;
    raise;
end;
$$;

create or replace function tests.closed_beta_policy_role_applies_to(
  policy_role name,
  client_role name
)
returns boolean
language sql
stable
as $$
  select case
    when policy_role = 'public'::name then true
    else pg_catalog.pg_has_role(client_role::text, policy_role::text, 'USAGE')
  end;
$$;

select plan(15);

select ok(
  (
    select relation.relrowsecurity
    from pg_catalog.pg_class relation
    where relation.oid = 'storage.objects'::regclass
  ),
  'storage.objects has row-level security enabled'
);

select is(
  (
    select count(*)::integer
    from pg_catalog.pg_policies policy_record
    cross join lateral unnest(policy_record.roles) as policy_role(role_name)
    where policy_record.schemaname = 'storage'
      and policy_record.tablename = 'objects'
      and policy_record.permissive = 'PERMISSIVE'
      and policy_record.cmd in ('ALL', 'INSERT', 'UPDATE', 'DELETE')
      and (
        tests.closed_beta_policy_role_applies_to(policy_role.role_name, 'anon')
        or tests.closed_beta_policy_role_applies_to(policy_role.role_name, 'authenticated')
      )
  ),
  0,
  'no permissive client policy can grant Storage writes during the closed beta'
);

select is(
  (
    select count(*)::integer
    from pg_catalog.pg_policies policy_record
    cross join lateral unnest(policy_record.roles) as policy_role(role_name)
    where policy_record.schemaname = 'storage'
      and policy_record.tablename = 'objects'
      and policy_record.cmd in ('ALL', 'SELECT')
      and (
        tests.closed_beta_policy_role_applies_to(policy_role.role_name, 'anon')
        or tests.closed_beta_policy_role_applies_to(policy_role.role_name, 'authenticated')
      )
  ),
  0,
  'public delivery exposes no storage.objects metadata policy to clients'
);

select ok(
  not exists (
    select 1
    from storage.buckets bucket
    where bucket.id = 'kwabor-catalog-demo'
  ),
  'database migrations never create the staging-only demo bucket'
);

insert into storage.buckets (
  id,
  name,
  public,
  file_size_limit,
  allowed_mime_types
)
values (
  'kwabor-catalog-demo',
  'kwabor-catalog-demo',
  true,
  524288,
  array['image/jpeg']::text[]
);

insert into storage.objects (bucket_id, name, owner_id)
values ('kwabor-catalog-demo', 'v1/existing.jpg', null);

select is(
  tests.closed_beta_statement_sqlstate_as(
    'anon',
    null,
    $sql$
      insert into storage.objects (bucket_id, name, owner_id)
      values ('kwabor-catalog-demo', 'v1/anon-insert.jpg', null)
    $sql$
  ),
  '42501',
  'anon insert is rejected by Storage RLS'
);

select is(
  tests.closed_beta_statement_sqlstate_as(
    'authenticated',
    'b2000000-0000-4000-8000-000000000001',
    $sql$
      insert into storage.objects (bucket_id, name, owner_id)
      values (
        'kwabor-catalog-demo',
        'v1/authenticated-insert.jpg',
        'b2000000-0000-4000-8000-000000000001'
      )
    $sql$
  ),
  '42501',
  'authenticated insert is rejected by Storage RLS'
);

select is(
  tests.closed_beta_affected_rows_as(
    'anon',
    null,
    $sql$
      update storage.objects
      set name = 'v1/anon-update.jpg'
      where bucket_id = 'kwabor-catalog-demo'
        and name = 'v1/existing.jpg'
    $sql$
  ),
  0::bigint,
  'anon update cannot see or mutate the demo object'
);

select is(
  tests.closed_beta_affected_rows_as(
    'authenticated',
    'b2000000-0000-4000-8000-000000000001',
    $sql$
      update storage.objects
      set name = 'v1/authenticated-update.jpg'
      where bucket_id = 'kwabor-catalog-demo'
        and name = 'v1/existing.jpg'
    $sql$
  ),
  0::bigint,
  'authenticated update cannot see or mutate the demo object'
);

select is(
  (
    select name
    from storage.objects
    where bucket_id = 'kwabor-catalog-demo'
      and name = 'v1/existing.jpg'
  ),
  'v1/existing.jpg',
  'the demo object name survives both client update attempts'
);

select is(
  (
    select count(*)::integer
    from pg_catalog.pg_policies policy_record
    cross join lateral unnest(policy_record.roles) as policy_role(role_name)
    where policy_record.schemaname = 'storage'
      and policy_record.tablename = 'objects'
      and policy_record.permissive = 'PERMISSIVE'
      and policy_record.cmd in ('ALL', 'DELETE')
      and tests.closed_beta_policy_role_applies_to(policy_role.role_name, 'anon')
  ),
  0,
  'anon has no permissive policy that can delete demo objects'
);

select is(
  (
    select count(*)::integer
    from pg_catalog.pg_policies policy_record
    cross join lateral unnest(policy_record.roles) as policy_role(role_name)
    where policy_record.schemaname = 'storage'
      and policy_record.tablename = 'objects'
      and policy_record.permissive = 'PERMISSIVE'
      and policy_record.cmd in ('ALL', 'DELETE')
      and tests.closed_beta_policy_role_applies_to(policy_role.role_name, 'authenticated')
  ),
  0,
  'authenticated has no permissive policy that can delete demo objects'
);

select is(
  (
    select count(*)::integer
    from storage.objects
    where bucket_id = 'kwabor-catalog-demo'
      and name = 'v1/existing.jpg'
  ),
  1,
  'the demo object remains present while clients have no delete policy'
);

select ok(
  tests.closed_beta_statement_succeeds_as(
    'service_role',
    null,
    $sql$
      insert into storage.objects (bucket_id, name, owner_id)
      values ('kwabor-catalog-demo', 'v1/workflow-only.jpg', null)
    $sql$
  ),
  'service_role can create a workflow-owned demo object'
);

select ok(
  tests.closed_beta_statement_succeeds_as(
    'service_role',
    null,
    $sql$
      update storage.objects
      set metadata = '{"cacheControl":"31536000"}'::jsonb
      where bucket_id = 'kwabor-catalog-demo'
        and name = 'v1/workflow-only.jpg'
    $sql$
  ),
  'service_role remains the demo update authority'
);

select ok(
  coalesce(
    (
      select role_record.rolbypassrls
      from pg_catalog.pg_roles role_record
      where role_record.rolname = 'service_role'
    ),
    false
  ),
  'service_role remains the RLS-bypass authority used by Storage API rollback'
);

select * from finish();
rollback;
