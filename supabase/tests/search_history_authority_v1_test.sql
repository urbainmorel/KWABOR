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

select plan(75);

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
    'b8100000-0000-4000-8000-000000000001',
    'authenticated',
    'authenticated',
    'history-owner@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    'b8100000-0000-4000-8000-000000000002',
    'authenticated',
    'authenticated',
    'history-other@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    'b8100000-0000-4000-8000-000000000003',
    'authenticated',
    'authenticated',
    'history-deletion@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    'b8100000-0000-4000-8000-000000000004',
    'authenticated',
    'authenticated',
    'history-cascade@kwabor.test',
    '',
    now(),
    now(),
    now()
  );

select ok(
  to_regclass('public.search_history_entries') is not null,
  'search history authority table exists'
);

select ok(
  to_regclass('public.search_history_preferences') is not null,
  'search history preference table exists'
);

select ok(
  (
    select relation.relrowsecurity and relation.relforcerowsecurity
    from pg_catalog.pg_class as relation
    where relation.oid = 'public.search_history_entries'::regclass
  ),
  'search history entries enforce RLS'
);

select ok(
  (
    select relation.relrowsecurity and relation.relforcerowsecurity
    from pg_catalog.pg_class as relation
    where relation.oid = 'public.search_history_preferences'::regclass
  ),
  'search history preferences enforce RLS'
);

select is(
  (
    select constraint_definition.confdeltype
    from pg_catalog.pg_constraint as constraint_definition
    where constraint_definition.conrelid = 'public.search_history_entries'::regclass
      and constraint_definition.contype = 'f'
      and constraint_definition.confrelid = 'auth.users'::regclass
  ),
  'c'::"char",
  'Auth deletion cascades to search history entries'
);

select is(
  (
    select constraint_definition.confdeltype
    from pg_catalog.pg_constraint as constraint_definition
    where constraint_definition.conrelid = 'public.search_history_preferences'::regclass
      and constraint_definition.contype = 'f'
      and constraint_definition.confrelid = 'auth.users'::regclass
  ),
  'c'::"char",
  'Auth deletion cascades to search history preferences'
);

select ok(
  exists (
    select 1
    from pg_catalog.pg_constraint as constraint_definition
    where constraint_definition.conrelid = 'public.search_history_entries'::regclass
      and constraint_definition.conname = 'search_history_entries_user_query_unique'
      and constraint_definition.contype = 'u'
  ),
  'canonical queries are unique per account'
);

select ok(
  to_regclass('public.search_history_entries_user_recent_idx') is not null,
  'owner recency index exists'
);

select is(
  (
    select collation_definition.collname
    from pg_catalog.pg_attribute as attribute
    join pg_catalog.pg_collation as collation_definition
      on collation_definition.oid = attribute.attcollation
    where attribute.attrelid = 'public.search_history_entries'::regclass
      and attribute.attname = 'canonical_query'
  ),
  'C',
  'canonical query equality is deterministic and case-sensitive'
);

select is(
  (
    select pg_catalog.pg_get_expr(attribute_default.adbin, attribute_default.adrelid)
    from pg_catalog.pg_attrdef as attribute_default
    join pg_catalog.pg_attribute as attribute
      on attribute.attrelid = attribute_default.adrelid
      and attribute.attnum = attribute_default.adnum
    where attribute_default.adrelid = 'public.search_history_preferences'::regclass
      and attribute.attname = 'activity_personalization_enabled'
  ),
  'false',
  'activity personalization defaults to disabled'
);

select is(
  (
    select count(*)
    from pg_catalog.pg_policy as policy
    where policy.polrelid = 'public.search_history_entries'::regclass
  ),
  1::bigint,
  'search history entries expose only the owner-read RLS policy'
);

select is(
  (
    select count(*)
    from pg_catalog.pg_policy as policy
    where policy.polrelid = 'public.search_history_preferences'::regclass
  ),
  1::bigint,
  'search history preferences expose only the owner-read RLS policy'
);

select ok(
  not has_table_privilege(
    'anon',
    'public.search_history_entries',
    'select,insert,update,delete'
  )
  and not has_table_privilege(
    'authenticated',
    'public.search_history_entries',
    'select,insert,update,delete'
  )
  and not has_table_privilege(
    'service_role',
    'public.search_history_entries',
    'select,insert,update,delete'
  ),
  'no API role can bypass the entry RPCs through the table'
);

select ok(
  not has_table_privilege(
    'anon',
    'public.search_history_preferences',
    'select,insert,update,delete'
  )
  and not has_table_privilege(
    'authenticated',
    'public.search_history_preferences',
    'select,insert,update,delete'
  )
  and not has_table_privilege(
    'service_role',
    'public.search_history_preferences',
    'select,insert,update,delete'
  ),
  'no API role can bypass the preference boundary through the table'
);

select ok(
  to_regprocedure('public.record_search_history_v1(text)') is not null,
  'versioned record RPC exists'
);

select ok(
  to_regprocedure('public.list_search_history_v1()') is not null,
  'versioned list RPC exists'
);

select ok(
  to_regprocedure('public.delete_search_history_entry_v1(uuid)') is not null,
  'versioned delete RPC exists'
);

select ok(
  to_regprocedure('public.clear_search_history_v1()') is not null,
  'versioned clear RPC exists'
);

select ok(
  to_regprocedure('app_private.search_history_canonicalize_v1(text)') is not null,
  'private canonicalization helper exists'
);

select ok(
  (
    select
      procedure.prosecdef
      and procedure.provolatile = 'v'::"char"
      and array_to_string(procedure.proconfig, ',') = 'search_path=""'
    from pg_catalog.pg_proc as procedure
    where procedure.oid = 'public.record_search_history_v1(text)'::regprocedure
  ),
  'record RPC is a hardened volatile SECURITY DEFINER function'
);

select ok(
  (
    select
      procedure.prosecdef
      and procedure.provolatile = 'v'::"char"
      and array_to_string(procedure.proconfig, ',') = 'search_path=""'
    from pg_catalog.pg_proc as procedure
    where procedure.oid = 'public.list_search_history_v1()'::regprocedure
  ),
  'list RPC is a hardened volatile SECURITY DEFINER function'
);

select ok(
  (
    select
      procedure.prosecdef
      and procedure.provolatile = 'v'::"char"
      and array_to_string(procedure.proconfig, ',') = 'search_path=""'
    from pg_catalog.pg_proc as procedure
    where procedure.oid = 'public.delete_search_history_entry_v1(uuid)'::regprocedure
  ),
  'delete RPC is a hardened volatile SECURITY DEFINER function'
);

select ok(
  (
    select
      procedure.prosecdef
      and procedure.provolatile = 'v'::"char"
      and array_to_string(procedure.proconfig, ',') = 'search_path=""'
    from pg_catalog.pg_proc as procedure
    where procedure.oid = 'public.clear_search_history_v1()'::regprocedure
  ),
  'clear RPC is a hardened volatile SECURITY DEFINER function'
);

select ok(
  (
    select
      not procedure.prosecdef
      and procedure.provolatile = 'i'::"char"
      and procedure.proisstrict
      and array_to_string(procedure.proconfig, ',') = 'search_path=""'
    from pg_catalog.pg_proc as procedure
    where procedure.oid = 'app_private.search_history_canonicalize_v1(text)'::regprocedure
  ),
  'canonicalization helper is immutable, strict, invoker-safe, and path-hardened'
);

select is(
  (
    select string_agg(
      coalesce(grantee.rolname, 'PUBLIC'),
      ','
      order by coalesce(grantee.rolname, 'PUBLIC')
    )
    from pg_catalog.pg_proc as procedure
    cross join lateral aclexplode(procedure.proacl) as privilege
    left join pg_catalog.pg_roles as grantee on grantee.oid = privilege.grantee
    where procedure.oid = 'public.record_search_history_v1(text)'::regprocedure
      and privilege.privilege_type = 'EXECUTE'
      and privilege.grantee <> procedure.proowner
  ),
  'authenticated',
  'only authenticated clients can execute record'
);

select is(
  (
    select string_agg(
      coalesce(grantee.rolname, 'PUBLIC'),
      ','
      order by coalesce(grantee.rolname, 'PUBLIC')
    )
    from pg_catalog.pg_proc as procedure
    cross join lateral aclexplode(procedure.proacl) as privilege
    left join pg_catalog.pg_roles as grantee on grantee.oid = privilege.grantee
    where procedure.oid = 'public.list_search_history_v1()'::regprocedure
      and privilege.privilege_type = 'EXECUTE'
      and privilege.grantee <> procedure.proowner
  ),
  'authenticated',
  'only authenticated clients can execute list'
);

select is(
  (
    select string_agg(
      coalesce(grantee.rolname, 'PUBLIC'),
      ','
      order by coalesce(grantee.rolname, 'PUBLIC')
    )
    from pg_catalog.pg_proc as procedure
    cross join lateral aclexplode(procedure.proacl) as privilege
    left join pg_catalog.pg_roles as grantee on grantee.oid = privilege.grantee
    where procedure.oid = 'public.delete_search_history_entry_v1(uuid)'::regprocedure
      and privilege.privilege_type = 'EXECUTE'
      and privilege.grantee <> procedure.proowner
  ),
  'authenticated',
  'only authenticated clients can execute delete'
);

select is(
  (
    select string_agg(
      coalesce(grantee.rolname, 'PUBLIC'),
      ','
      order by coalesce(grantee.rolname, 'PUBLIC')
    )
    from pg_catalog.pg_proc as procedure
    cross join lateral aclexplode(procedure.proacl) as privilege
    left join pg_catalog.pg_roles as grantee on grantee.oid = privilege.grantee
    where procedure.oid = 'public.clear_search_history_v1()'::regprocedure
      and privilege.privilege_type = 'EXECUTE'
      and privilege.grantee <> procedure.proowner
  ),
  'authenticated',
  'only authenticated clients can execute clear'
);

select ok(
  not has_function_privilege(
    'anon',
    'app_private.search_history_canonicalize_v1(text)',
    'execute'
  )
  and not has_function_privilege(
    'authenticated',
    'app_private.search_history_canonicalize_v1(text)',
    'execute'
  )
  and not has_function_privilege(
    'service_role',
    'app_private.search_history_canonicalize_v1(text)',
    'execute'
  ),
  'no API role can execute private canonicalization directly'
);

select is(
  app_private.search_history_canonicalize_v1('  Recherche Cotonou  '),
  'Recherche Cotonou',
  'canonicalization trims submitted text without case folding'
);

select throws_ok(
  $sql$
    select app_private.search_history_canonicalize_v1(E'query\nvalue')
  $sql$,
  '22023',
  'Submitted search query is invalid',
  'canonicalization rejects control characters with a redacted message'
);

select tests.use_auth_context(
  'authenticated',
  null
);
select throws_ok(
  $sql$select * from public.record_search_history_v1('valid query')$sql$,
  '42501',
  'Authentication required',
  'record rejects a missing authenticated subject'
);
reset role;

select tests.use_auth_context(
  'authenticated',
  'b8100000-0000-4000-8000-000000000001'
);
select is(
  (
    select query_text
    from public.record_search_history_v1('  Restaurant calme  ')
  ),
  'Restaurant calme',
  'record stores the canonical submitted query'
);
reset role;

create temporary table first_submission as
select
  history_entry.id,
  history_entry.created_at,
  history_entry.last_submitted_at
from public.search_history_entries as history_entry
where history_entry.user_id = 'b8100000-0000-4000-8000-000000000001'
  and history_entry.canonical_query = 'Restaurant calme';

select is(
  (
    select history_preference.activity_personalization_enabled
    from public.search_history_preferences as history_preference
    where history_preference.user_id = 'b8100000-0000-4000-8000-000000000001'
  ),
  false,
  'the first submission initializes personalization as disabled'
);

select pg_catalog.pg_sleep(0.01);
select tests.use_auth_context(
  'authenticated',
  'b8100000-0000-4000-8000-000000000001'
);
select lives_ok(
  $sql$select * from public.record_search_history_v1('Restaurant calme')$sql$,
  'the same canonical query can be resubmitted'
);
reset role;

select is(
  (
    select history_entry.id
    from public.search_history_entries as history_entry
    where history_entry.user_id = 'b8100000-0000-4000-8000-000000000001'
      and history_entry.canonical_query = 'Restaurant calme'
  ),
  (select first_submission.id from first_submission),
  'resubmission preserves entry identity'
);

select is(
  (
    select history_entry.created_at
    from public.search_history_entries as history_entry
    where history_entry.user_id = 'b8100000-0000-4000-8000-000000000001'
      and history_entry.canonical_query = 'Restaurant calme'
  ),
  (select first_submission.created_at from first_submission),
  'resubmission preserves creation time'
);

select cmp_ok(
  (
    select history_entry.last_submitted_at
    from public.search_history_entries as history_entry
    where history_entry.user_id = 'b8100000-0000-4000-8000-000000000001'
      and history_entry.canonical_query = 'Restaurant calme'
  ),
  '>',
  (select first_submission.last_submitted_at from first_submission),
  'resubmission advances the server submission time'
);

select is(
  (
    select count(*)
    from public.search_history_entries as history_entry
    where history_entry.user_id = 'b8100000-0000-4000-8000-000000000001'
  ),
  1::bigint,
  'resubmission does not create a duplicate'
);

select tests.use_auth_context(
  'authenticated',
  'b8100000-0000-4000-8000-000000000001'
);
select lives_ok(
  $sql$select * from public.record_search_history_v1('Marché artisanal')$sql$,
  'a second canonical query can be recorded'
);
select is(
  array(
    select query_text
    from public.list_search_history_v1()
  ),
  array['Marché artisanal', 'Restaurant calme']::text[],
  'list returns the complete owner snapshot newest first'
);
reset role;

select tests.use_auth_context(
  'authenticated',
  'b8100000-0000-4000-8000-000000000002'
);
select lives_ok(
  $sql$select * from public.record_search_history_v1('Autre compte')$sql$,
  'another account can keep an isolated history'
);
reset role;

update public.search_history_entries as history_entry
set last_submitted_at = pg_catalog.statement_timestamp() + interval '1 hour'
where history_entry.user_id = 'b8100000-0000-4000-8000-000000000002';

create temporary table future_submission as
select history_entry.last_submitted_at
from public.search_history_entries as history_entry
where history_entry.user_id = 'b8100000-0000-4000-8000-000000000002';

select tests.use_auth_context(
  'authenticated',
  'b8100000-0000-4000-8000-000000000002'
);
select lives_ok(
  $sql$select * from public.record_search_history_v1('Autre compte')$sql$,
  'resubmission tolerates a backward server clock adjustment'
);
reset role;

select is(
  (
    select history_entry.last_submitted_at
    from public.search_history_entries as history_entry
    where history_entry.user_id = 'b8100000-0000-4000-8000-000000000002'
  ),
  (select future_submission.last_submitted_at from future_submission),
  'resubmission never moves the authoritative submission time backward'
);

create temporary table foreign_entry as
select history_entry.id
from public.search_history_entries as history_entry
where history_entry.user_id = 'b8100000-0000-4000-8000-000000000002';
grant select on table foreign_entry to authenticated;

select tests.use_auth_context(
  'authenticated',
  'b8100000-0000-4000-8000-000000000001'
);
select is(
  public.delete_search_history_entry_v1(
    (select foreign_entry.id from foreign_entry)
  ),
  false,
  'delete does not reveal a foreign owner entry'
);
reset role;

select ok(
  exists (
    select 1
    from public.search_history_entries as history_entry
    where history_entry.user_id = 'b8100000-0000-4000-8000-000000000002'
  ),
  'a foreign delete attempt leaves the other owner entry intact'
);

create temporary table owner_entry as
select history_entry.id
from public.search_history_entries as history_entry
where history_entry.user_id = 'b8100000-0000-4000-8000-000000000001'
  and history_entry.canonical_query = 'Restaurant calme';
grant select on table owner_entry to authenticated;

select tests.use_auth_context(
  'authenticated',
  'b8100000-0000-4000-8000-000000000001'
);
select is(
  public.delete_search_history_entry_v1(
    (select owner_entry.id from owner_entry)
  ),
  true,
  'delete removes an owner entry'
);
select is(
  public.clear_search_history_v1(),
  1,
  'clear reports the number of remaining owner entries removed'
);
reset role;

select is(
  (
    select count(*)
    from public.search_history_entries as history_entry
    where history_entry.user_id = 'b8100000-0000-4000-8000-000000000001'
  ),
  0::bigint,
  'clear physically removes the owner text'
);

select is(
  (
    select history_preference.activity_personalization_enabled
    from public.search_history_preferences as history_preference
    where history_preference.user_id = 'b8100000-0000-4000-8000-000000000001'
  ),
  false,
  'clear preserves the separate disabled personalization preference'
);

select is(
  (
    select count(*)
    from public.search_history_entries as history_entry
    where history_entry.user_id = 'b8100000-0000-4000-8000-000000000002'
  ),
  1::bigint,
  'clear does not affect another account'
);

select tests.use_auth_context(
  'authenticated',
  'b8100000-0000-4000-8000-000000000001'
);
select throws_ok(
  $sql$select * from public.record_search_history_v1('   ')$sql$,
  '22023',
  'Submitted search query is invalid',
  'record rejects a blank query without echoing it'
);
select throws_ok(
  $sql$select * from public.record_search_history_v1(E'unsafe\tquery')$sql$,
  '22023',
  'Submitted search query is invalid',
  'record rejects a control character without echoing it'
);
select throws_ok(
  $sql$select * from public.record_search_history_v1(repeat('x', 121))$sql$,
  '22023',
  'Submitted search query is invalid',
  'record rejects text beyond the 120-character bound'
);
select throws_ok(
  $sql$select * from public.record_search_history_v1(null)$sql$,
  '22023',
  'Submitted search query is invalid',
  'record rejects null with the same redacted validation message'
);
select throws_ok(
  $sql$select public.delete_search_history_entry_v1(null)$sql$,
  '22023',
  'Search history entry identifier is invalid',
  'delete rejects a null identifier'
);
reset role;

insert into public.search_history_entries (
  user_id,
  canonical_query,
  created_at,
  last_submitted_at
)
select
  'b8100000-0000-4000-8000-000000000001',
  'cap-' || pg_catalog.lpad(sequence_number::text, 3, '0'),
  pg_catalog.statement_timestamp() - interval '2 days' + sequence_number * interval '1 millisecond',
  pg_catalog.statement_timestamp() - interval '2 days' + sequence_number * interval '1 millisecond'
from pg_catalog.generate_series(1, 200) as generated(sequence_number);

select tests.use_auth_context(
  'authenticated',
  'b8100000-0000-4000-8000-000000000001'
);
select lives_ok(
  $sql$select * from public.record_search_history_v1('cap-201')$sql$,
  'record accepts a new query when the account is at capacity'
);
reset role;

select is(
  (
    select count(*)
    from public.search_history_entries as history_entry
    where history_entry.user_id = 'b8100000-0000-4000-8000-000000000001'
  ),
  200::bigint,
  'record enforces the server cap of 200 active canonical queries'
);

select ok(
  not exists (
    select 1
    from public.search_history_entries as history_entry
    where history_entry.user_id = 'b8100000-0000-4000-8000-000000000001'
      and history_entry.canonical_query = 'cap-001'
  ),
  'record evicts the oldest active query at capacity'
);

select ok(
  exists (
    select 1
    from public.search_history_entries as history_entry
    where history_entry.user_id = 'b8100000-0000-4000-8000-000000000001'
      and history_entry.canonical_query = 'cap-201'
  ),
  'record keeps the newly submitted query at capacity'
);

grant select on table public.search_history_entries to authenticated;
grant select on table public.search_history_preferences to authenticated;

select is(
  tests.count_as(
    'authenticated',
    'b8100000-0000-4000-8000-000000000001',
    'select * from public.search_history_entries'
  ),
  200::bigint,
  'entry RLS exposes only the current owner snapshot'
);

select is(
  tests.count_as(
    'authenticated',
    'b8100000-0000-4000-8000-000000000002',
    'select * from public.search_history_entries'
  ),
  1::bigint,
  'entry RLS isolates another owner snapshot'
);

select is(
  tests.count_as(
    'authenticated',
    'b8100000-0000-4000-8000-000000000002',
    'select * from public.search_history_preferences'
  ),
  1::bigint,
  'preference RLS exposes only the current owner row'
);

revoke select on table public.search_history_entries from authenticated;
revoke select on table public.search_history_preferences from authenticated;

select tests.use_auth_context(
  'authenticated',
  'b8100000-0000-4000-8000-000000000003'
);
select lives_ok(
  $sql$select * from public.record_search_history_v1('Compte à supprimer')$sql$,
  'an active account can record history before deletion preparation'
);
reset role;

set local role service_role;
select is(
  (
    select status
    from public.prepare_account_deletion(
      'b8100000-0000-4000-8000-000000000003',
      'b81d0000-0000-4000-8000-000000000003'
    )
  ),
  'prepared',
  'account deletion preparation succeeds for the history fixture'
);
reset role;

select ok(
  not exists (
    select 1
    from public.search_history_entries as history_entry
    where history_entry.user_id = 'b8100000-0000-4000-8000-000000000003'
  ),
  'account deletion preparation purges submitted query text'
);

select ok(
  not exists (
    select 1
    from public.search_history_preferences as history_preference
    where history_preference.user_id = 'b8100000-0000-4000-8000-000000000003'
  ),
  'account deletion preparation purges the personalization preference'
);

select tests.use_auth_context(
  'authenticated',
  'b8100000-0000-4000-8000-000000000003'
);
select throws_ok(
  $sql$select * from public.record_search_history_v1('late mutation')$sql$,
  '42501',
  'Account deletion in progress',
  'the account deletion tombstone fences later history writes'
);
select throws_ok(
  $sql$select * from public.list_search_history_v1()$sql$,
  '42501',
  'Account deletion in progress',
  'the account deletion tombstone fences later history reads'
);
reset role;

select tests.use_auth_context(
  'authenticated',
  'b8100000-0000-4000-8000-000000000004'
);
select lives_ok(
  $sql$select * from public.record_search_history_v1('Cascade finale')$sql$,
  'the cascade fixture records history'
);
reset role;

delete from auth.users
where id = 'b8100000-0000-4000-8000-000000000004';

select ok(
  not exists (
    select 1
    from public.search_history_entries as history_entry
    where history_entry.user_id = 'b8100000-0000-4000-8000-000000000004'
  ),
  'final Auth deletion cascades to history entries'
);

select ok(
  not exists (
    select 1
    from public.search_history_preferences as history_preference
    where history_preference.user_id = 'b8100000-0000-4000-8000-000000000004'
  ),
  'final Auth deletion cascades to the preference row'
);

select ok(
  position(
    'purge_search_history_account_data' in pg_catalog.pg_get_functiondef(
      'app_private.prepare_account_data_for_deletion(uuid)'::regprocedure
    )
  ) > 0,
  'account deletion preparation is wired to the replayable history purge'
);

select ok(
  position(
    'purge_search_history_account_data' in pg_catalog.pg_get_functiondef(
      'app_private.cleanup_account_data(uuid)'::regprocedure
    )
  ) > 0,
  'final account cleanup is wired to the replayable history purge'
);

select ok(
  position(
    'raise log' in pg_catalog.lower(
      pg_catalog.pg_get_functiondef('public.record_search_history_v1(text)'::regprocedure)
    )
  ) = 0
  and position(
    'raise notice' in pg_catalog.lower(
      pg_catalog.pg_get_functiondef('public.record_search_history_v1(text)'::regprocedure)
    )
  ) = 0,
  'record contains no diagnostic path that logs submitted text'
);

select * from finish();
rollback;
