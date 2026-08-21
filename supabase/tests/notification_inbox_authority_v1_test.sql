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

create or replace function tests.jsonb_as(db_role text, uid uuid, sql text)
returns jsonb
language plpgsql
as $$
declare
  result jsonb;
begin
  perform tests.use_auth_context(db_role, uid);
  execute format(
    'select coalesce(jsonb_agg(to_jsonb(scoped)), ''[]''::jsonb) from (%s) as scoped',
    sql
  ) into result;
  reset role;
  return result;
exception
  when others then
    reset role;
    raise;
end;
$$;

create or replace function tests.exec_as(db_role text, uid uuid, sql text)
returns void
language plpgsql
as $$
begin
  perform tests.use_auth_context(db_role, uid);
  execute sql;
  reset role;
exception
  when others then
    reset role;
    raise;
end;
$$;

select plan(94);

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
    'b9100000-0000-4000-8000-000000000001',
    'authenticated',
    'authenticated',
    'notification-owner@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    'b9100000-0000-4000-8000-000000000002',
    'authenticated',
    'authenticated',
    'notification-other@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    'b9100000-0000-4000-8000-000000000003',
    'authenticated',
    'authenticated',
    'notification-incomplete@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    'b9100000-0000-4000-8000-000000000004',
    'authenticated',
    'authenticated',
    'notification-deletion@kwabor.test',
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
    'b9100000-0000-4000-8000-000000000001',
    'Inbox',
    'Owner',
    'ouidah',
    now()
  ),
  (
    'b9100000-0000-4000-8000-000000000002',
    'Inbox',
    'Other',
    'cotonou',
    now()
  ),
  (
    'b9100000-0000-4000-8000-000000000003',
    'Inbox',
    'Incomplete',
    'cotonou',
    null
  ),
  (
    'b9100000-0000-4000-8000-000000000004',
    'Inbox',
    'Deletion',
    'ouidah',
    now()
  );

insert into public.notifications (
  id,
  user_id,
  type,
  title_key,
  body_key
)
values (
  'b9100000-0000-4000-8000-000000000101',
  'b9100000-0000-4000-8000-000000000001',
  'system',
  'notification.legacy.title',
  'notification.legacy.body'
);

create temporary table notification_test_observations (
  observation_key text primary key,
  payload jsonb not null
) on commit drop;

select ok(
  to_regclass('public.notification_inbox_states_v1') is not null,
  'notification inbox state table exists'
);

select ok(
  to_regclass('public.notification_preferences_v1') is not null,
  'notification preference table exists'
);

select ok(
  to_regclass('public.notification_enqueue_receipts_v1') is not null,
  'notification enqueue receipt table exists'
);

select results_eq(
  $$
    select enum_value::text
    from pg_catalog.unnest(
      pg_catalog.enum_range(null::public.notification_family_v1)
    ) as enum_value
  $$,
  $$
    values
      ('suggestion'::text),
      ('sponsored'::text),
      ('new_listing'::text),
      ('event_alert'::text)
  $$,
  'notification families are the four V1 proactive families in contract order'
);

select ok(
  (
    select relation.relrowsecurity and relation.relforcerowsecurity
    from pg_catalog.pg_class as relation
    where relation.oid = 'public.notifications'::regclass
  ),
  'notifications force RLS'
);

select ok(
  (
    select relation.relrowsecurity and relation.relforcerowsecurity
    from pg_catalog.pg_class as relation
    where relation.oid = 'public.notification_inbox_states_v1'::regclass
  ),
  'notification inbox state forces RLS'
);

select ok(
  (
    select relation.relrowsecurity and relation.relforcerowsecurity
    from pg_catalog.pg_class as relation
    where relation.oid = 'public.notification_preferences_v1'::regclass
  ),
  'notification preferences force RLS'
);

select ok(
  (
    select relation.relrowsecurity and relation.relforcerowsecurity
    from pg_catalog.pg_class as relation
    where relation.oid = 'public.notification_enqueue_receipts_v1'::regclass
  ),
  'notification enqueue receipts force RLS'
);

select is(
  (
    select constraint_definition.confdeltype
    from pg_catalog.pg_constraint as constraint_definition
    where constraint_definition.conrelid = 'public.notifications'::regclass
      and constraint_definition.conname = 'notifications_related_listing_id_fkey'
  ),
  'n'::"char",
  'listing deletion nulls the target without deleting historical notification text'
);

select ok(
  to_regclass('public.notifications_owner_sequence_v1_key') is not null,
  'notification owner sequence uniqueness is indexed'
);

select ok(
  to_regclass('public.notifications_owner_source_event_v1_key') is not null,
  'notification owner source-event uniqueness is indexed'
);

select ok(
  to_regclass('public.notifications_related_listing_id_idx') is not null,
  'notification listing foreign key is indexed for target deletion'
);

select ok(
  not has_table_privilege(
    'authenticated',
    'public.notification_inbox_states_v1',
    'SELECT'
  ),
  'authenticated cannot read inbox state directly'
);

select ok(
  not has_table_privilege(
    'authenticated',
    'public.notification_preferences_v1',
    'SELECT'
  ),
  'authenticated cannot read notification preferences directly'
);

select ok(
  not has_table_privilege(
    'service_role',
    'public.notifications',
    'INSERT'
  ),
  'service role cannot bypass enqueue with a direct notification insert'
);

select ok(
  has_function_privilege(
    'authenticated',
    'public.list_notification_inbox_v1(uuid,text,integer)',
    'EXECUTE'
  ),
  'authenticated can execute the owner-fenced inbox list RPC'
);

select ok(
  has_function_privilege(
    'authenticated',
    'public.get_notification_inbox_status_v1(uuid)',
    'EXECUTE'
  ),
  'authenticated can execute the owner-fenced inbox status RPC'
);

select ok(
  has_function_privilege(
    'authenticated',
    'public.set_notification_preference_v1(uuid,public.notification_family_v1,boolean)',
    'EXECUTE'
  ),
  'authenticated can execute the preference setter'
);

select ok(
  has_function_privilege(
    'service_role',
    'public.enqueue_notification_v1(uuid,uuid,public.notification_family_v1,text,jsonb,text,jsonb,uuid)',
    'EXECUTE'
  ),
  'service role can execute the trusted enqueue RPC'
);

select ok(
  not has_function_privilege(
    'authenticated',
    'public.enqueue_notification_v1(uuid,uuid,public.notification_family_v1,text,jsonb,text,jsonb,uuid)',
    'EXECUTE'
  ),
  'authenticated cannot enqueue notifications'
);

select ok(
  not has_function_privilege(
    'anon',
    'public.list_notification_inbox_v1(uuid,text,integer)',
    'EXECUTE'
  ),
  'anonymous callers cannot execute inbox RPCs'
);

insert into notification_test_observations (observation_key, payload)
select
  'initial-preferences',
  tests.jsonb_as(
    'authenticated',
    'b9100000-0000-4000-8000-000000000001',
    $$
      select *
      from public.list_notification_preferences_v1(
        'b9100000-0000-4000-8000-000000000001'
      )
    $$
  );

select is(
  jsonb_array_length(
    (select payload from notification_test_observations where observation_key = 'initial-preferences')
  ),
  4,
  'preference list synthesizes all four families'
);

select ok(
  not exists (
    select 1
    from jsonb_array_elements(
      (select payload from notification_test_observations where observation_key = 'initial-preferences')
    ) as preference
    where (preference ->> 'enabled')::boolean
      or preference -> 'updated_at' <> 'null'::jsonb
  ),
  'absent preferences are disabled with a null updated_at'
);

select throws_ok(
  $$
    select tests.exec_as(
      'authenticated',
      'b9100000-0000-4000-8000-000000000001',
      'select * from public.get_notification_inbox_status_v1(''b9100000-0000-4000-8000-000000000002'')'
    )
  $$,
  '42501',
  'Authentication required',
  'owner RPC rejects an expected-account mismatch'
);

select throws_ok(
  $$
    select tests.exec_as(
      'authenticated',
      null,
      'select * from public.get_notification_inbox_status_v1(''b9100000-0000-4000-8000-000000000001'')'
    )
  $$,
  '42501',
  'Authentication required',
  'owner RPC rejects a missing authenticated account'
);

select throws_ok(
  $$
    select tests.exec_as(
      'authenticated',
      'b9100000-0000-4000-8000-000000000003',
      'select * from public.get_notification_inbox_status_v1(''b9100000-0000-4000-8000-000000000003'')'
    )
  $$,
  '42501',
  'Onboarding completion required',
  'owner RPC rejects incomplete onboarding'
);

insert into notification_test_observations (observation_key, payload)
select
  'disabled-enqueue',
  tests.jsonb_as(
    'service_role',
    null,
    $$
      select *
      from public.enqueue_notification_v1(
        'b9100000-0000-4000-8000-000000000001',
        'd9100000-0000-4000-8000-000000000001',
        'suggestion',
        'notification.suggestion.title',
        '{}'::jsonb,
        'notification.suggestion.body',
        '{"listing_name":"Porte du Non-Retour"}'::jsonb,
        '00000000-0000-4000-8000-000000000101'
      )
    $$
  );

select is(
  (
    select (payload -> 0 ->> 'enqueued')::boolean
    from notification_test_observations
    where observation_key = 'disabled-enqueue'
  ),
  false,
  'missing preference suppresses enqueue'
);

select is(
  (
    select outcome
    from public.notification_enqueue_receipts_v1
    where user_id = 'b9100000-0000-4000-8000-000000000001'
      and source_event_id = 'd9100000-0000-4000-8000-000000000001'
  ),
  'disabled'::text,
  'preference-disabled enqueue records an idempotency receipt'
);

select ok(
  not exists (
    select 1
    from public.notification_inbox_states_v1
    where user_id = 'b9100000-0000-4000-8000-000000000001'
  ),
  'suppressed enqueue does not consume an inbox sequence'
);

insert into notification_test_observations (observation_key, payload)
select
  'suggestion-preference',
  tests.jsonb_as(
    'authenticated',
    'b9100000-0000-4000-8000-000000000001',
    $$
      select *
      from public.set_notification_preference_v1(
        'b9100000-0000-4000-8000-000000000001',
        'suggestion',
        true
      )
    $$
  );

select is(
  (
    select (payload -> 0 ->> 'enabled')::boolean
    from notification_test_observations
    where observation_key = 'suggestion-preference'
  ),
  true,
  'owner can enable one notification family'
);

select ok(
  (
    select payload -> 0 -> 'updated_at' <> 'null'::jsonb
    from notification_test_observations
    where observation_key = 'suggestion-preference'
  ),
  'stored preference exposes a non-null update time'
);

insert into notification_test_observations (observation_key, payload)
select
  'disabled-retry',
  tests.jsonb_as(
    'service_role',
    null,
    $$
      select *
      from public.enqueue_notification_v1(
        'b9100000-0000-4000-8000-000000000001',
        'd9100000-0000-4000-8000-000000000001',
        'suggestion',
        'notification.suggestion.title',
        '{}'::jsonb,
        'notification.suggestion.body',
        '{"listing_name":"Porte du Non-Retour"}'::jsonb,
        '00000000-0000-4000-8000-000000000101'
      )
    $$
  );

select is(
  (
    select (payload -> 0 ->> 'enqueued')::boolean
    from notification_test_observations
    where observation_key = 'disabled-retry'
  ),
  false,
  'source event suppressed before opt-in stays suppressed on retry'
);

select throws_ok(
  $$
    select tests.exec_as(
      'service_role',
      null,
      $sql$
        select * from public.enqueue_notification_v1(
          'b9100000-0000-4000-8000-000000000001',
          'd9100000-0000-4000-8000-000000000011',
          'suggestion',
          'notification.sponsored.title',
          '{}'::jsonb,
          'notification.suggestion.body',
          '{"listing_name":"Porte du Non-Retour"}'::jsonb,
          '00000000-0000-4000-8000-000000000101'
        )
      $sql$
    )
  $$,
  '22023',
  'Notification payload is invalid',
  'enqueue rejects a title key crossed from another family'
);

select throws_ok(
  $$
    select tests.exec_as(
      'service_role',
      null,
      $sql$
        select * from public.enqueue_notification_v1(
          'b9100000-0000-4000-8000-000000000001',
          'd9100000-0000-4000-8000-000000000012',
          'suggestion',
          'notification.suggestion.title',
          '{}'::jsonb,
          'notification.suggestion.body',
          '{"listing_name":"Porte du Non-Retour","extra":"forbidden"}'::jsonb,
          '00000000-0000-4000-8000-000000000101'
        )
      $sql$
    )
  $$,
  '22023',
  'Notification payload is invalid',
  'enqueue rejects an extra template argument'
);

select throws_ok(
  $$
    select tests.exec_as(
      'service_role',
      null,
      $sql$
        select * from public.enqueue_notification_v1(
          'b9100000-0000-4000-8000-000000000001',
          'd9100000-0000-4000-8000-000000000013',
          'suggestion',
          'notification.suggestion.title',
          '{}'::jsonb,
          'notification.suggestion.body',
          '{"listing_name":42}'::jsonb,
          '00000000-0000-4000-8000-000000000101'
        )
      $sql$
    )
  $$,
  '22023',
  'Notification payload is invalid',
  'enqueue rejects non-string template arguments'
);

select throws_ok(
  $$
    select tests.exec_as(
      'service_role',
      null,
      $sql$
        select * from public.enqueue_notification_v1(
          'b9100000-0000-4000-8000-000000000001',
          'd9100000-0000-4000-8000-000000000014',
          'suggestion',
          'notification.suggestion.title',
          '{}'::jsonb,
          'notification.suggestion.body',
          '{"listing_name":"Fiche absente"}'::jsonb,
          'aaaaaaaa-0000-4000-8000-000000000099'
        )
      $sql$
    )
  $$,
  'P0002',
  'listing not found',
  'enqueue rejects an absent listing target'
);

select throws_ok(
  $$
    select tests.exec_as(
      'service_role',
      null,
      $sql$
        select * from public.enqueue_notification_v1(
          'b9100000-0000-4000-8000-000000000001',
          'd9100000-0000-4000-8000-000000000015',
          'event_alert',
          'notification.event_alert.title',
          '{}'::jsonb,
          'notification.event_alert.body',
          '{"listing_name":"Porte du Non-Retour","event_start_at":"2030-01-10T17:00:00Z"}'::jsonb,
          '00000000-0000-4000-8000-000000000101'
        )
      $sql$
    )
  $$,
  '22023',
  'Event notification target is invalid',
  'event alert requires an event listing with event details'
);

select throws_ok(
  $$
    select tests.exec_as(
      'service_role',
      null,
      $sql$
        select * from public.enqueue_notification_v1(
          'b9100000-0000-4000-8000-000000000001',
          'd9100000-0000-4000-8000-000000000016',
          'event_alert',
          'notification.event_alert.title',
          '{}'::jsonb,
          'notification.event_alert.body',
          '{"listing_name":"Festival culturel de Ouidah","event_start_at":"2030-01-11T17:00:00Z"}'::jsonb,
          '00000000-0000-4000-8000-000000000104'
        )
      $sql$
    )
  $$,
  '22023',
  'Event notification payload does not match listing',
  'event alert start time must equal the authoritative event detail'
);

select throws_ok(
  $$
    select tests.exec_as(
      'service_role',
      null,
      $sql$
        select * from public.enqueue_notification_v1(
          'b9100000-0000-4000-8000-000000000001',
          'd9100000-0000-4000-8000-000000000018',
          'event_alert',
          'notification.event_alert.title',
          '{}'::jsonb,
          'notification.event_alert.body',
          '{"listing_name":"Festival culturel de Ouidah","event_start_at":"2030-01-10 17:00:00+00"}'::jsonb,
          '00000000-0000-4000-8000-000000000104'
        )
      $sql$
    )
  $$,
  '22023',
  'Notification payload is invalid',
  'enqueue rejects a PostgreSQL timestamp that is not canonical RFC3339 UTC'
);

select throws_ok(
  $$
    select tests.exec_as(
      'service_role',
      null,
      $sql$
        select * from public.enqueue_notification_v1(
          'b9100000-0000-4000-8000-000000000001',
          'd9100000-0000-4000-8000-000000000017',
          'new_listing',
          'notification.new_listing.title',
          '{}'::jsonb,
          'notification.new_listing.body',
          '{"listing_name":"Porte du Non-Retour","city_name":"Cotonou"}'::jsonb,
          '00000000-0000-4000-8000-000000000101'
        )
      $sql$
    )
  $$,
  '22023',
  'Notification payload does not match listing',
  'new listing city argument must equal the authoritative city'
);

do $enable_remaining_preferences$
begin
  perform tests.jsonb_as(
    'authenticated',
    'b9100000-0000-4000-8000-000000000001',
    $sql$
      select *
      from public.set_notification_preference_v1(
        'b9100000-0000-4000-8000-000000000001',
        'sponsored',
        true
      )
    $sql$
  );
  perform tests.jsonb_as(
    'authenticated',
    'b9100000-0000-4000-8000-000000000001',
    $sql$
      select *
      from public.set_notification_preference_v1(
        'b9100000-0000-4000-8000-000000000001',
        'new_listing',
        true
      )
    $sql$
  );
  perform tests.jsonb_as(
    'authenticated',
    'b9100000-0000-4000-8000-000000000001',
    $sql$
      select *
      from public.set_notification_preference_v1(
        'b9100000-0000-4000-8000-000000000001',
        'event_alert',
        true
      )
    $sql$
  );
end;
$enable_remaining_preferences$;

insert into notification_test_observations (observation_key, payload)
select
  'enqueue-sequence-1',
  tests.jsonb_as(
    'service_role',
    null,
    $$
      select * from public.enqueue_notification_v1(
        'b9100000-0000-4000-8000-000000000001',
        'd9100000-0000-4000-8000-000000000101',
        'suggestion',
        'notification.suggestion.title',
        '{}'::jsonb,
        'notification.suggestion.body',
        '{"listing_name":"Porte du Non-Retour"}'::jsonb,
        '00000000-0000-4000-8000-000000000101'
      )
    $$
  );

insert into notification_test_observations (observation_key, payload)
select
  'enqueue-sequence-2',
  tests.jsonb_as(
    'service_role',
    null,
    $$
      select * from public.enqueue_notification_v1(
        'b9100000-0000-4000-8000-000000000001',
        'd9100000-0000-4000-8000-000000000102',
        'sponsored',
        'notification.sponsored.title',
        '{}'::jsonb,
        'notification.sponsored.body',
        '{"listing_name":"Porte du Non-Retour"}'::jsonb,
        '00000000-0000-4000-8000-000000000101'
      )
    $$
  );

insert into notification_test_observations (observation_key, payload)
select
  'enqueue-sequence-3',
  tests.jsonb_as(
    'service_role',
    null,
    $$
      select * from public.enqueue_notification_v1(
        'b9100000-0000-4000-8000-000000000001',
        'd9100000-0000-4000-8000-000000000103',
        'new_listing',
        'notification.new_listing.title',
        '{}'::jsonb,
        'notification.new_listing.body',
        '{"listing_name":"Porte du Non-Retour","city_name":"Ouidah"}'::jsonb,
        '00000000-0000-4000-8000-000000000101'
      )
    $$
  );

insert into notification_test_observations (observation_key, payload)
select
  'enqueue-sequence-4',
  tests.jsonb_as(
    'service_role',
    null,
    $$
      select * from public.enqueue_notification_v1(
        'b9100000-0000-4000-8000-000000000001',
        'd9100000-0000-4000-8000-000000000104',
        'event_alert',
        'notification.event_alert.title',
        '{}'::jsonb,
        'notification.event_alert.body',
        '{"listing_name":"Festival culturel de Ouidah","event_start_at":"2030-01-10T17:00:00Z"}'::jsonb,
        '00000000-0000-4000-8000-000000000104'
      )
    $$
  );

insert into notification_test_observations (observation_key, payload)
select
  'enqueue-sequence-5',
  tests.jsonb_as(
    'service_role',
    null,
    $$
      select * from public.enqueue_notification_v1(
        'b9100000-0000-4000-8000-000000000001',
        'd9100000-0000-4000-8000-000000000105',
        'suggestion',
        'notification.suggestion.title',
        '{}'::jsonb,
        'notification.suggestion.body',
        '{"listing_name":"Porte du Non-Retour"}'::jsonb,
        '00000000-0000-4000-8000-000000000101'
      )
    $$
  );

select results_eq(
  $$
    select inbox_sequence
    from public.notifications
    where user_id = 'b9100000-0000-4000-8000-000000000001'
      and family_v1 is not null
    order by inbox_sequence
  $$,
  $$ values (1::bigint), (2::bigint), (3::bigint), (4::bigint), (5::bigint) $$,
  'successful enqueues allocate a strict gap-free account sequence'
);

select ok(
  (
    select bool_and((payload -> 0 ->> 'enqueued')::boolean)
    from notification_test_observations
    where observation_key like 'enqueue-sequence-%'
  ),
  'all enabled-family enqueues report success'
);

select is(
  (
    select inbox_sequence
    from public.notifications
    where user_id = 'b9100000-0000-4000-8000-000000000001'
      and family_v1 = 'sponsored'
  ),
  2::bigint,
  'sponsored notification receives its account sequence'
);

select ok(
  (
    select sponsored and type = 'promotion'::public.notification_type
    from public.notifications
    where user_id = 'b9100000-0000-4000-8000-000000000001'
      and family_v1 = 'sponsored'
  ),
  'sponsored family maps to legacy promotion type and sponsored flag'
);

insert into notification_test_observations (observation_key, payload)
select
  'enqueue-idempotent-retry',
  tests.jsonb_as(
    'service_role',
    null,
    $$
      select * from public.enqueue_notification_v1(
        'b9100000-0000-4000-8000-000000000001',
        'd9100000-0000-4000-8000-000000000101',
        'suggestion',
        'notification.suggestion.title',
        '{}'::jsonb,
        'notification.suggestion.body',
        '{"listing_name":"Porte du Non-Retour"}'::jsonb,
        '00000000-0000-4000-8000-000000000101'
      )
    $$
  );

select is(
  (
    select payload -> 0 ->> 'notification_id'
    from notification_test_observations
    where observation_key = 'enqueue-idempotent-retry'
  ),
  (
    select payload -> 0 ->> 'notification_id'
    from notification_test_observations
    where observation_key = 'enqueue-sequence-1'
  ),
  'same source-event retry returns the original notification identifier'
);

select is(
  (
    select count(*)
    from public.notifications
    where user_id = 'b9100000-0000-4000-8000-000000000001'
      and family_v1 is not null
  ),
  5::bigint,
  'same source-event retry does not duplicate a notification'
);

select throws_ok(
  $$
    select tests.exec_as(
      'service_role',
      null,
      $sql$
        select * from public.enqueue_notification_v1(
          'b9100000-0000-4000-8000-000000000001',
          'd9100000-0000-4000-8000-000000000101',
          'suggestion',
          'notification.suggestion.title',
          '{}'::jsonb,
          'notification.suggestion.body',
          '{"listing_name":"Autre nom"}'::jsonb,
          '00000000-0000-4000-8000-000000000101'
        )
      $sql$
    )
  $$,
  '22023',
  'Source event payload is inconsistent',
  'same source event with a changed payload is rejected'
);

insert into notification_test_observations (observation_key, payload)
select
  'status-five',
  tests.jsonb_as(
    'authenticated',
    'b9100000-0000-4000-8000-000000000001',
    $$
      select * from public.get_notification_inbox_status_v1(
        'b9100000-0000-4000-8000-000000000001'
      )
    $$
  );

select is(
  (
    select (payload -> 0 ->> 'latest_sequence')::bigint
    from notification_test_observations
    where observation_key = 'status-five'
  ),
  5::bigint,
  'status exposes latest account sequence'
);

select is(
  (
    select (payload -> 0 ->> 'unseen_count')::bigint
    from notification_test_observations
    where observation_key = 'status-five'
  ),
  5::bigint,
  'status initially counts all visible V1 notifications unseen'
);

select is(
  jsonb_array_length(
    tests.jsonb_as(
      'authenticated',
      'b9100000-0000-4000-8000-000000000001',
      'select id from public.notifications'
    )
  ),
  1,
  'direct table compatibility exposes only the owner legacy notification'
);

select is(
  jsonb_array_length(
    tests.jsonb_as(
      'authenticated',
      'b9100000-0000-4000-8000-000000000002',
      'select id from public.notifications'
    )
  ),
  0,
  'direct notification policy remains account isolated'
);

insert into notification_test_observations (observation_key, payload)
select
  'page-one',
  tests.jsonb_as(
    'authenticated',
    'b9100000-0000-4000-8000-000000000001',
    $$
      select * from public.list_notification_inbox_v1(
        'b9100000-0000-4000-8000-000000000001',
        null,
        2
      )
    $$
  );

select is(
  jsonb_array_length(
    (select payload from notification_test_observations where observation_key = 'page-one')
  ),
  3,
  'full inbox page returns one sentinel row beyond its requested limit'
);

select is(
  (
    select (payload -> 0 ->> 'sequence_number')::bigint
    from notification_test_observations
    where observation_key = 'page-one'
  ),
  5::bigint,
  'first inbox page is ordered newest sequence first'
);

select is(
  (
    select (payload -> 1 ->> 'sequence_number')::bigint
    from notification_test_observations
    where observation_key = 'page-one'
  ),
  4::bigint,
  'last retained row has the deterministic next-page cursor'
);

select is(
  (
    select (payload -> 2 ->> 'sequence_number')::bigint
    from notification_test_observations
    where observation_key = 'page-one'
  ),
  3::bigint,
  'first inbox page sentinel is the next sequence after retained rows'
);

select ok(
  (
    select bool_and((row_value ->> 'snapshot_sequence')::bigint = 5)
    from notification_test_observations,
      lateral jsonb_array_elements(payload) as row_value
    where observation_key = 'page-one'
  ),
  'every row cursor page exposes the same snapshot sequence'
);

select ok(
  (
    select bool_and((row_value ->> 'target_available')::boolean)
    from notification_test_observations,
      lateral jsonb_array_elements(payload) as row_value
    where observation_key = 'page-one'
  ),
  'published targets are available in inbox rows'
);

update public.listing_media
set alt = E'Festival\nOuidah'
where listing_id = '00000000-0000-4000-8000-000000000104'
  and is_cover;

insert into public.listing_media (
  id,
  listing_id,
  url,
  alt,
  display_order,
  is_cover,
  kind
)
values (
  'b9100000-0000-4000-8000-000000000201',
  '00000000-0000-4000-8000-000000000104',
  'https://example.invalid/kwabor/tests/festival-ouidah.mp4',
  'Video du festival culturel de Ouidah',
  1,
  false,
  'video'
);

insert into notification_test_observations (observation_key, payload)
select
  'invalid-cover-omitted',
  tests.jsonb_as(
    'authenticated',
    'b9100000-0000-4000-8000-000000000001',
    $$
      select * from public.list_notification_inbox_v1(
        'b9100000-0000-4000-8000-000000000001',
        null,
        50
      )
    $$
  );

select ok(
  exists (
    select 1
    from notification_test_observations,
      lateral jsonb_array_elements(payload) as row_value
    where observation_key = 'invalid-cover-omitted'
      and row_value ->> 'family' = 'event_alert'
      and (row_value ->> 'target_available')::boolean
      and row_value -> 'target_cover_image_url' = 'null'::jsonb
      and row_value -> 'target_cover_image_alt' = 'null'::jsonb
  ),
  'invalid image alt omits the cover pair and a video is never projected as its fallback'
);

update public.listing_media
set alt = 'Scene culturelle de festival a Ouidah'
where listing_id = '00000000-0000-4000-8000-000000000104'
  and is_cover;

update public.cities
set name = E'Oui\ndah'
where id = 'ouidah';

insert into notification_test_observations (observation_key, payload)
select
  'invalid-city-omitted',
  tests.jsonb_as(
    'authenticated',
    'b9100000-0000-4000-8000-000000000001',
    $$
      select * from public.list_notification_inbox_v1(
        'b9100000-0000-4000-8000-000000000001',
        null,
        50
      )
    $$
  );

select ok(
  exists (
    select 1
    from notification_test_observations,
      lateral jsonb_array_elements(payload) as row_value
    where observation_key = 'invalid-city-omitted'
      and (row_value ->> 'target_available')::boolean
      and row_value -> 'target_city_id' = 'null'::jsonb
      and row_value -> 'target_city_name' = 'null'::jsonb
  ),
  'invalid city projection omits the city pair without hiding its target'
);

update public.cities
set name = 'Ouidah'
where id = 'ouidah';

update public.listings
set name = E'Porte du Non-\nRetour'
where id = '00000000-0000-4000-8000-000000000101';

insert into notification_test_observations (observation_key, payload)
select
  'invalid-target-name-page',
  tests.jsonb_as(
    'authenticated',
    'b9100000-0000-4000-8000-000000000001',
    $$
      select * from public.list_notification_inbox_v1(
        'b9100000-0000-4000-8000-000000000001',
        null,
        50
      )
    $$
  );

select ok(
  exists (
    select 1
    from notification_test_observations,
      lateral jsonb_array_elements(payload) as row_value
    where observation_key = 'invalid-target-name-page'
      and row_value ->> 'family' = 'suggestion'
      and not (row_value ->> 'target_available')::boolean
      and row_value -> 'target_listing_id' = 'null'::jsonb
      and row_value -> 'target_listing_name' = 'null'::jsonb
      and row_value -> 'body_args' ->> 'listing_name' = 'Porte du Non-Retour'
  ),
  'a renamed target with control characters is unavailable without poisoning historical content'
);

update public.listings
set name = 'Porte du Non-Retour'
where id = '00000000-0000-4000-8000-000000000101';

select ok(
  (
    select bool_and(
      coalesce(row_value ->> 'row_cursor', '') <> ''
      and row_value ->> 'row_cursor' !~ '[[:space:]]'
    )
    from notification_test_observations,
      lateral jsonb_array_elements(payload) as row_value
    where observation_key = 'page-one'
  ),
  'row cursors are non-empty and whitespace-free'
);

select throws_ok(
  format(
    $$
      select tests.exec_as(
        'authenticated',
        'b9100000-0000-4000-8000-000000000001',
        %L
      )
    $$,
    format(
      'select * from public.list_notification_inbox_v1(%L, %L, 3)',
      'b9100000-0000-4000-8000-000000000001',
      (
        select payload -> 1 ->> 'row_cursor'
        from notification_test_observations
        where observation_key = 'page-one'
      )
    )
  ),
  '22023',
  'p_cursor does not match inbox owner or limit',
  'cursor cannot be reused with another page limit'
);

select throws_ok(
  $$
    select tests.exec_as(
      'authenticated',
      'b9100000-0000-4000-8000-000000000001',
      'select * from public.list_notification_inbox_v1(''b9100000-0000-4000-8000-000000000001'', ''not-base64!'', 2)'
    )
  $$,
  '22023',
  'p_cursor is malformed',
  'malformed cursor is rejected'
);

do $enqueue_after_snapshot$
begin
  perform tests.jsonb_as(
    'service_role',
    null,
    $sql$
      select * from public.enqueue_notification_v1(
        'b9100000-0000-4000-8000-000000000001',
        'd9100000-0000-4000-8000-000000000106',
        'suggestion',
        'notification.suggestion.title',
        '{}'::jsonb,
        'notification.suggestion.body',
        '{"listing_name":"Porte du Non-Retour"}'::jsonb,
        '00000000-0000-4000-8000-000000000101'
      )
    $sql$
  );
end;
$enqueue_after_snapshot$;

insert into notification_test_observations (observation_key, payload)
select
  'page-two-old-snapshot',
  tests.jsonb_as(
    'authenticated',
    'b9100000-0000-4000-8000-000000000001',
    format(
      'select * from public.list_notification_inbox_v1(%L, %L, 2)',
      'b9100000-0000-4000-8000-000000000001',
      (
        select payload -> 1 ->> 'row_cursor'
        from notification_test_observations
        where observation_key = 'page-one'
      )
    )
  );

select results_eq(
  $$
    select (row_value ->> 'sequence_number')::bigint
    from notification_test_observations,
      lateral jsonb_array_elements(payload) as row_value
    where observation_key = 'page-two-old-snapshot'
    order by (row_value ->> 'sequence_number')::bigint desc
  $$,
  $$ values (3::bigint), (2::bigint), (1::bigint) $$,
  'cursor continues the old snapshot and excludes a later enqueue'
);

select ok(
  (
    select bool_and((row_value ->> 'snapshot_sequence')::bigint = 5)
    from notification_test_observations,
      lateral jsonb_array_elements(payload) as row_value
    where observation_key = 'page-two-old-snapshot'
  ),
  'continued page preserves its original snapshot boundary'
);

select ok(
  not exists (
    select 1
    from jsonb_array_elements(
      (select payload from notification_test_observations where observation_key = 'page-one')
    ) with ordinality as first_page(row_value, position)
    join jsonb_array_elements(
      (select payload from notification_test_observations where observation_key = 'page-two-old-snapshot')
    ) as second_page(row_value)
      on second_page.row_value ->> 'notification_id'
        = first_page.row_value ->> 'notification_id'
    where first_page.position <= 2
  ),
  'continued page never duplicates either retained row from the previous page'
);

insert into notification_test_observations (observation_key, payload)
select
  'page-three-old-snapshot',
  tests.jsonb_as(
    'authenticated',
    'b9100000-0000-4000-8000-000000000001',
    format(
      'select * from public.list_notification_inbox_v1(%L, %L, 2)',
      'b9100000-0000-4000-8000-000000000001',
      (
        select payload -> 1 ->> 'row_cursor'
        from notification_test_observations
        where observation_key = 'page-two-old-snapshot'
      )
    )
  );

select results_eq(
  $$
    select (row_value ->> 'sequence_number')::bigint
    from notification_test_observations,
      lateral jsonb_array_elements(payload) as row_value
    where observation_key = 'page-three-old-snapshot'
  $$,
  $$ values (1::bigint) $$,
  'terminal page returns the remaining row without a sentinel'
);

select throws_ok(
  $$
    update public.notifications
    set seen_at = created_at - interval '1 second'
    where user_id = 'b9100000-0000-4000-8000-000000000001'
      and inbox_sequence = 5
  $$,
  '23514',
  'new row for relation "notifications" violates check constraint "notifications_v1_monotone_timestamps"',
  'V1 notification state cannot precede its creation timestamp'
);

update public.notifications
set created_at = pg_catalog.clock_timestamp() + interval '1 day'
where user_id = 'b9100000-0000-4000-8000-000000000001'
  and inbox_sequence in (5, 6);

insert into notification_test_observations (observation_key, payload)
select
  'hide-sequence-five',
  tests.jsonb_as(
    'authenticated',
    'b9100000-0000-4000-8000-000000000001',
    format(
      'select * from public.hide_notification_v1(%L, %L)',
      'b9100000-0000-4000-8000-000000000001',
      (
        select payload -> 0 ->> 'notification_id'
        from notification_test_observations
        where observation_key = 'page-one'
      )
    )
  );

select ok(
  (
    select payload -> 0 -> 'seen_at' <> 'null'::jsonb
      and payload -> 0 -> 'hidden_at' <> 'null'::jsonb
    from notification_test_observations
    where observation_key = 'hide-sequence-five'
  ),
  'hide marks the row seen and hidden atomically'
);

select ok(
  (
    select seen_at >= created_at and hidden_at >= created_at
    from public.notifications
    where user_id = 'b9100000-0000-4000-8000-000000000001'
      and inbox_sequence = 5
  ),
  'hide bounds seen and hidden timestamps by creation time despite clock rollback'
);

select is(
  (
    select seen_through_sequence
    from public.notification_inbox_states_v1
    where user_id = 'b9100000-0000-4000-8000-000000000001'
  ),
  0::bigint,
  'hiding one row does not advance the global seen-through watermark'
);

select is(
  (
    select (payload -> 0 ->> 'hidden_at')::timestamptz
    from notification_test_observations
    where observation_key = 'hide-sequence-five'
  ),
  (
    select (
      tests.jsonb_as(
        'authenticated',
        'b9100000-0000-4000-8000-000000000001',
        format(
          'select * from public.hide_notification_v1(%L, %L)',
          'b9100000-0000-4000-8000-000000000001',
          (
            select payload -> 0 ->> 'notification_id'
            from notification_test_observations
            where observation_key = 'page-one'
          )
        )
      ) -> 0 ->> 'hidden_at'
    )::timestamptz
  ),
  'hide retry preserves the original hidden timestamp'
);

insert into notification_test_observations (observation_key, payload)
select
  'mark-seen-five',
  tests.jsonb_as(
    'authenticated',
    'b9100000-0000-4000-8000-000000000001',
    $$
      select * from public.mark_notification_inbox_seen_v1(
        'b9100000-0000-4000-8000-000000000001',
        5
      )
    $$
  );

select is(
  (
    select (payload -> 0 ->> 'seen_through_sequence')::bigint
    from notification_test_observations
    where observation_key = 'mark-seen-five'
  ),
  5::bigint,
  'mark seen advances exactly to the requested opening boundary'
);

select is(
  (
    select (payload -> 0 ->> 'unseen_count')::bigint
    from notification_test_observations
    where observation_key = 'mark-seen-five'
  ),
  1::bigint,
  'arrival after the opening boundary remains unseen'
);

select throws_ok(
  $$
    select tests.exec_as(
      'authenticated',
      'b9100000-0000-4000-8000-000000000001',
      'select * from public.mark_notification_inbox_seen_v1(''b9100000-0000-4000-8000-000000000001'', 0)'
    )
  $$,
  '22023',
  'Seen-through sequence is invalid',
  'mark seen rejects a non-positive boundary'
);

select throws_ok(
  $$
    select tests.exec_as(
      'authenticated',
      'b9100000-0000-4000-8000-000000000001',
      'select * from public.mark_notification_inbox_seen_v1(''b9100000-0000-4000-8000-000000000001'', 7)'
    )
  $$,
  '22023',
  'Seen-through sequence is invalid',
  'mark seen rejects a boundary beyond latest sequence'
);

insert into notification_test_observations (observation_key, payload)
select
  'read-sequence-six',
  tests.jsonb_as(
    'authenticated',
    'b9100000-0000-4000-8000-000000000001',
    format(
      'select * from public.mark_notification_read_v1(%L, %L)',
      'b9100000-0000-4000-8000-000000000001',
      (
        select notification_id
        from public.notifications
        where user_id = 'b9100000-0000-4000-8000-000000000001'
          and inbox_sequence = 6
      )
    )
  );

select ok(
  (
    select payload -> 0 -> 'seen_at' <> 'null'::jsonb
      and payload -> 0 -> 'read_at' <> 'null'::jsonb
    from notification_test_observations
    where observation_key = 'read-sequence-six'
  ),
  'mark read sets seen and read timestamps'
);

select ok(
  (
    select seen_at >= created_at and read_at >= created_at
    from public.notifications
    where user_id = 'b9100000-0000-4000-8000-000000000001'
      and inbox_sequence = 6
  ),
  'mark read bounds seen and read timestamps by creation time despite clock rollback'
);

select is(
  (
    select (payload -> 0 ->> 'read_at')::timestamptz
    from notification_test_observations
    where observation_key = 'read-sequence-six'
  ),
  (
    select (
      tests.jsonb_as(
        'authenticated',
        'b9100000-0000-4000-8000-000000000001',
        format(
          'select * from public.mark_notification_read_v1(%L, %L)',
          'b9100000-0000-4000-8000-000000000001',
          (
            select notification_id
            from public.notifications
            where user_id = 'b9100000-0000-4000-8000-000000000001'
              and inbox_sequence = 6
          )
        )
      ) -> 0 ->> 'read_at'
    )::timestamptz
  ),
  'mark read retry preserves the original read timestamp'
);

insert into notification_test_observations (observation_key, payload)
select
  'read-all-five',
  tests.jsonb_as(
    'authenticated',
    'b9100000-0000-4000-8000-000000000001',
    $$
      select * from public.mark_all_notifications_read_v1(
        'b9100000-0000-4000-8000-000000000001',
        5
      )
    $$
  );

select is(
  (
    select (payload -> 0 ->> 'unread_count')::bigint
    from notification_test_observations
    where observation_key = 'read-all-five'
  ),
  0::bigint,
  'read-all plus the separately read later arrival clears visible unread count'
);

select ok(
  (
    select (payload -> 0 ->> 'mutation_at')::timestamptz is not null
    from notification_test_observations
    where observation_key = 'read-all-five'
  ),
  'read-all returns the authoritative server mutation timestamp'
);

select ok(
  (
    select hidden_at is not null and read_at is null
    from public.notifications
    where user_id = 'b9100000-0000-4000-8000-000000000001'
      and inbox_sequence = 5
  ),
  'read-all does not change the independent read state of a hidden row'
);

select throws_ok(
  $$
    select tests.exec_as(
      'authenticated',
      'b9100000-0000-4000-8000-000000000001',
      'select * from public.mark_all_notifications_read_v1(''b9100000-0000-4000-8000-000000000001'', 0)'
    )
  $$,
  '22023',
  'Read-through sequence is invalid',
  'read-all rejects a non-positive boundary'
);

select throws_ok(
  $$
    select tests.exec_as(
      'authenticated',
      'b9100000-0000-4000-8000-000000000001',
      'select * from public.mark_all_notifications_read_v1(''b9100000-0000-4000-8000-000000000001'', 7)'
    )
  $$,
  '22023',
  'Read-through sequence is invalid',
  'read-all rejects a boundary beyond latest sequence'
);

update public.listings
set name = 'Porte du Non-Retour renommée',
    status = 'archive'
where id = '00000000-0000-4000-8000-000000000101';

insert into notification_test_observations (observation_key, payload)
select
  'unavailable-target-page',
  tests.jsonb_as(
    'authenticated',
    'b9100000-0000-4000-8000-000000000001',
    $$
      select * from public.list_notification_inbox_v1(
        'b9100000-0000-4000-8000-000000000001',
        null,
        50
      )
    $$
  );

select ok(
  exists (
    select 1
    from notification_test_observations,
      lateral jsonb_array_elements(payload) as row_value
    where observation_key = 'unavailable-target-page'
      and row_value ->> 'family' = 'suggestion'
      and not (row_value ->> 'target_available')::boolean
      and row_value -> 'target_listing_id' = 'null'::jsonb
      and row_value -> 'body_args' ->> 'listing_name' = 'Porte du Non-Retour'
  ),
  'depublished target becomes unavailable while historical args remain unchanged'
);

select is(
  (
    select (
      tests.jsonb_as(
        'service_role',
        null,
        $$
          select * from public.enqueue_notification_v1(
            'b9100000-0000-4000-8000-000000000001',
            'd9100000-0000-4000-8000-000000000101',
            'suggestion',
            'notification.suggestion.title',
            '{}'::jsonb,
            'notification.suggestion.body',
            '{"listing_name":"Porte du Non-Retour"}'::jsonb,
            '00000000-0000-4000-8000-000000000101'
          )
        $$
      ) -> 0 ->> 'notification_id'
    )
  ),
  (
    select payload -> 0 ->> 'notification_id'
    from notification_test_observations
    where observation_key = 'enqueue-sequence-1'
  ),
  'idempotent retry survives target rename and depublish'
);

select throws_ok(
  $$
    select tests.exec_as(
      'service_role',
      null,
      $sql$
        select * from public.enqueue_notification_v1(
          'b9100000-0000-4000-8000-000000000001',
          'd9100000-0000-4000-8000-000000000107',
          'suggestion',
          'notification.suggestion.title',
          '{}'::jsonb,
          'notification.suggestion.body',
          '{"listing_name":"Porte du Non-Retour renommée"}'::jsonb,
          '00000000-0000-4000-8000-000000000101'
        )
      $sql$
    )
  $$,
  'P0002',
  'listing not found',
  'a new source event cannot target a depublished listing'
);

select is(
  (
    select latest_sequence
    from public.notification_inbox_states_v1
    where user_id = 'b9100000-0000-4000-8000-000000000001'
  ),
  6::bigint,
  'failed enqueue does not consume a sequence'
);

do $deletion_fixture$
begin
  perform tests.jsonb_as(
    'authenticated',
    'b9100000-0000-4000-8000-000000000004',
    $sql$
      select * from public.set_notification_preference_v1(
        'b9100000-0000-4000-8000-000000000004',
        'event_alert',
        true
      )
    $sql$
  );

  perform tests.jsonb_as(
    'service_role',
    null,
    $sql$
      select * from public.enqueue_notification_v1(
        'b9100000-0000-4000-8000-000000000004',
        'd9100000-0000-4000-8000-000000000201',
        'event_alert',
        'notification.event_alert.title',
        '{}'::jsonb,
        'notification.event_alert.body',
        '{"listing_name":"Festival culturel de Ouidah","event_start_at":"2030-01-10T17:00:00Z"}'::jsonb,
        '00000000-0000-4000-8000-000000000104'
      )
    $sql$
  );
end;
$deletion_fixture$;

select lives_ok(
  $$
    select tests.exec_as(
      'service_role',
      null,
      'select * from public.prepare_account_deletion(''b9100000-0000-4000-8000-000000000004'', ''d9100000-0000-4000-8000-000000000204'')'
    )
  $$,
  'account deletion preparation accepts an account with notification data'
);

select ok(
  not exists (
    select 1 from public.notifications
    where user_id = 'b9100000-0000-4000-8000-000000000004'
  )
  and not exists (
    select 1 from public.notification_inbox_states_v1
    where user_id = 'b9100000-0000-4000-8000-000000000004'
  )
  and not exists (
    select 1 from public.notification_preferences_v1
    where user_id = 'b9100000-0000-4000-8000-000000000004'
  )
  and not exists (
    select 1 from public.notification_enqueue_receipts_v1
    where user_id = 'b9100000-0000-4000-8000-000000000004'
  ),
  'account deletion preparation purges every notification authority table'
);

select throws_ok(
  $$
    select tests.exec_as(
      'service_role',
      null,
      $sql$
        select * from public.enqueue_notification_v1(
          'b9100000-0000-4000-8000-000000000004',
          'd9100000-0000-4000-8000-000000000202',
          'event_alert',
          'notification.event_alert.title',
          '{}'::jsonb,
          'notification.event_alert.body',
          '{"listing_name":"Festival culturel de Ouidah","event_start_at":"2030-01-10T17:00:00Z"}'::jsonb,
          '00000000-0000-4000-8000-000000000104'
        )
      $sql$
    )
  $$,
  '42501',
  'Notification recipient is unavailable',
  'enqueue cannot resurrect data after a deletion tombstone'
);

select throws_ok(
  $$
    select tests.exec_as(
      'authenticated',
      'b9100000-0000-4000-8000-000000000004',
      $sql$
        select * from public.set_notification_preference_v1(
          'b9100000-0000-4000-8000-000000000004',
          'suggestion',
          true
        )
      $sql$
    )
  $$,
  '42501',
  'Account deletion in progress',
  'preference mutation rejects the owner after the deletion tombstone'
);

select throws_ok(
  $$
    select tests.exec_as(
      'authenticated',
      'b9100000-0000-4000-8000-000000000004',
      $sql$
        select * from public.list_notification_inbox_v1(
          'b9100000-0000-4000-8000-000000000004',
          null,
          20
        )
      $sql$
    )
  $$,
  '42501',
  'Account deletion in progress',
  'inbox reads reject the owner after the deletion tombstone'
);

select ok(
  not exists (
    select 1
    from public.notification_preferences_v1
    where user_id = 'b9100000-0000-4000-8000-000000000004'
  ),
  'rejected owner calls cannot recreate notification data after account purge'
);

select * from finish();
rollback;
