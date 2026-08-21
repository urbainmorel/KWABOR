do $local_only$
begin
  if current_setting('kwabor.local_concurrency_harness', true)
      is distinct from 'explicit-local-wrapper'
    or current_setting('ssl') <> 'off'
  then
    raise exception 'notification_inbox_concurrency_test.sql requires the explicit local-only runner';
  end if;
end;
$local_only$;

create temporary table notification_concurrency_credentials (
  password text not null
) on commit preserve rows;

insert into notification_concurrency_credentials (password)
values (encode(extensions.gen_random_bytes(32), 'hex'));

do $role_setup$
declare
  test_password text;
begin
  if exists (
    select 1 from pg_roles where rolname = 'kwabor_notification_concurrency_test'
  ) then
    execute 'revoke service_role from kwabor_notification_concurrency_test';
    execute 'drop role kwabor_notification_concurrency_test';
  end if;

  select credentials.password
  into strict test_password
  from notification_concurrency_credentials as credentials;

  execute format(
    'create role kwabor_notification_concurrency_test login noinherit bypassrls connection limit 3 password %L valid until %L',
    test_password,
    clock_timestamp() + interval '5 minutes'
  );
end;
$role_setup$;

grant service_role to kwabor_notification_concurrency_test;

delete from public.account_deletion_requests
where user_id in (
  'bc100000-0000-4000-8000-000000000001',
  'bc100000-0000-4000-8000-000000000002',
  'bc100000-0000-4000-8000-000000000003'
);

delete from auth.users
where id in (
  'bc100000-0000-4000-8000-000000000001',
  'bc100000-0000-4000-8000-000000000002',
  'bc100000-0000-4000-8000-000000000003'
);

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
    'bc100000-0000-4000-8000-000000000001',
    'authenticated',
    'authenticated',
    'notification-concurrency-sequence@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    'bc100000-0000-4000-8000-000000000002',
    'authenticated',
    'authenticated',
    'notification-concurrency-mutation-first@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    'bc100000-0000-4000-8000-000000000003',
    'authenticated',
    'authenticated',
    'notification-concurrency-deletion-first@kwabor.test',
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
    'bc100000-0000-4000-8000-000000000001',
    'Notif',
    'Sequence',
    'ouidah',
    now()
  ),
  (
    'bc100000-0000-4000-8000-000000000002',
    'Notif',
    'Mutation',
    'ouidah',
    now()
  ),
  (
    'bc100000-0000-4000-8000-000000000003',
    'Notif',
    'Deletion',
    'ouidah',
    now()
  );

insert into public.notification_preferences_v1 (user_id, family, enabled)
values
  (
    'bc100000-0000-4000-8000-000000000001',
    'suggestion',
    true
  ),
  (
    'bc100000-0000-4000-8000-000000000002',
    'suggestion',
    true
  ),
  (
    'bc100000-0000-4000-8000-000000000003',
    'suggestion',
    true
  );

begin;

create extension if not exists dblink with schema extensions;

select plan(12);

create temporary table notification_concurrency_observations (
  observation_key text primary key,
  uuid_value uuid,
  bigint_value bigint,
  text_value text
) on commit preserve rows;

do $connection_setup$
declare
  connection_info text := format(
    'hostaddr=%s port=%s dbname=postgres user=kwabor_notification_concurrency_test password=%s',
    inet_server_addr(),
    inet_server_port(),
    (select credentials.password from notification_concurrency_credentials as credentials)
  );
begin
  perform extensions.dblink_connect('notification_first', connection_info);
  perform extensions.dblink_connect('notification_second', connection_info);
  perform extensions.dblink_connect('notification_check', connection_info);

  perform extensions.dblink_exec(
    'notification_second',
    'create temporary table notification_transition_result (sqlstate text, message text) on commit preserve rows'
  );
end;
$connection_setup$;

do $serialized_enqueues_setup$
declare
  first_result record;
begin
  perform extensions.dblink_exec('notification_first', 'begin');
  perform extensions.dblink_exec(
    'notification_first',
    'set local statement_timeout = ''5s'''
  );
  perform extensions.dblink_exec('notification_first', 'set local role service_role');

  select result.*
  into strict first_result
  from extensions.dblink(
    'notification_first',
    $query$
      select notification_id, sequence_number, enqueued
      from public.enqueue_notification_v1(
        'bc100000-0000-4000-8000-000000000001',
        'dc100000-0000-4000-8000-000000000001',
        'suggestion',
        'notification.suggestion.title',
        '{}'::jsonb,
        'notification.suggestion.body',
        '{"listing_name":"Porte du Non-Retour"}'::jsonb,
        '00000000-0000-4000-8000-000000000101'
      )
    $query$
  ) as result(notification_id uuid, sequence_number bigint, enqueued boolean);

  insert into notification_concurrency_observations (
    observation_key,
    uuid_value,
    bigint_value
  ) values (
    'serialized-first',
    first_result.notification_id,
    first_result.sequence_number
  );

  perform extensions.dblink_exec('notification_second', 'begin');
  perform extensions.dblink_exec(
    'notification_second',
    'set local statement_timeout = ''5s'''
  );
  perform extensions.dblink_exec('notification_second', 'set local role service_role');

  if extensions.dblink_send_query(
    'notification_second',
    $query$
      select notification_id, sequence_number, enqueued
      from public.enqueue_notification_v1(
        'bc100000-0000-4000-8000-000000000001',
        'dc100000-0000-4000-8000-000000000002',
        'suggestion',
        'notification.suggestion.title',
        '{}'::jsonb,
        'notification.suggestion.body',
        '{"listing_name":"Porte du Non-Retour"}'::jsonb,
        '00000000-0000-4000-8000-000000000101'
      )
    $query$
  ) <> 1 then
    raise exception 'Unable to start the second serialized enqueue';
  end if;
end;
$serialized_enqueues_setup$;

do $$
begin
  perform pg_catalog.pg_sleep(0.25);
end;
$$;

select is(
  extensions.dblink_is_busy('notification_second'),
  1,
  'concurrent enqueues for one account serialize on the account lock'
);

do $finish_serialized_enqueues$
declare
  second_result record;
begin
  perform extensions.dblink_exec('notification_first', 'commit');

  select result.*
  into strict second_result
  from extensions.dblink_get_result('notification_second', false) as result(
    notification_id uuid,
    sequence_number bigint,
    enqueued boolean
  );
  perform result.command_status
  from extensions.dblink_get_result('notification_second', false) as result(
    command_status text
  );
  perform extensions.dblink_exec('notification_second', 'commit');

  insert into notification_concurrency_observations (
    observation_key,
    uuid_value,
    bigint_value
  ) values (
    'serialized-second',
    second_result.notification_id,
    second_result.sequence_number
  );
end;
$finish_serialized_enqueues$;

select is(
  (
    select bigint_value
    from notification_concurrency_observations
    where observation_key = 'serialized-first'
  ),
  1::bigint,
  'the first serialized enqueue receives sequence one'
);

select is(
  (
    select bigint_value
    from notification_concurrency_observations
    where observation_key = 'serialized-second'
  ),
  2::bigint,
  'the later serialized enqueue receives sequence two'
);

select results_eq(
  $$
    select inbox_sequence
    from public.notifications
    where user_id = 'bc100000-0000-4000-8000-000000000001'
    order by inbox_sequence
  $$,
  $$ values (1::bigint), (2::bigint) $$,
  'serialized commits persist a strict sequence without duplicates or gaps'
);

do $same_source_setup$
declare
  first_result record;
begin
  perform extensions.dblink_exec('notification_first', 'begin');
  perform extensions.dblink_exec('notification_first', 'set local role service_role');

  select result.*
  into strict first_result
  from extensions.dblink(
    'notification_first',
    $query$
      select notification_id, sequence_number, enqueued
      from public.enqueue_notification_v1(
        'bc100000-0000-4000-8000-000000000001',
        'dc100000-0000-4000-8000-000000000003',
        'suggestion',
        'notification.suggestion.title',
        '{}'::jsonb,
        'notification.suggestion.body',
        '{"listing_name":"Porte du Non-Retour"}'::jsonb,
        '00000000-0000-4000-8000-000000000101'
      )
    $query$
  ) as result(notification_id uuid, sequence_number bigint, enqueued boolean);

  insert into notification_concurrency_observations (
    observation_key,
    uuid_value,
    bigint_value
  ) values (
    'same-source-first',
    first_result.notification_id,
    first_result.sequence_number
  );

  perform extensions.dblink_exec('notification_second', 'begin');
  perform extensions.dblink_exec('notification_second', 'set local role service_role');

  if extensions.dblink_send_query(
    'notification_second',
    $query$
      select notification_id, sequence_number, enqueued
      from public.enqueue_notification_v1(
        'bc100000-0000-4000-8000-000000000001',
        'dc100000-0000-4000-8000-000000000003',
        'suggestion',
        'notification.suggestion.title',
        '{}'::jsonb,
        'notification.suggestion.body',
        '{"listing_name":"Porte du Non-Retour"}'::jsonb,
        '00000000-0000-4000-8000-000000000101'
      )
    $query$
  ) <> 1 then
    raise exception 'Unable to start the same-source enqueue retry';
  end if;
end;
$same_source_setup$;

do $$
begin
  perform pg_catalog.pg_sleep(0.25);
end;
$$;

select is(
  extensions.dblink_is_busy('notification_second'),
  1,
  'same-source concurrent retry waits for the original receipt transaction'
);

do $finish_same_source$
declare
  second_result record;
begin
  perform extensions.dblink_exec('notification_first', 'commit');

  select result.*
  into strict second_result
  from extensions.dblink_get_result('notification_second', false) as result(
    notification_id uuid,
    sequence_number bigint,
    enqueued boolean
  );
  perform result.command_status
  from extensions.dblink_get_result('notification_second', false) as result(
    command_status text
  );
  perform extensions.dblink_exec('notification_second', 'commit');

  insert into notification_concurrency_observations (
    observation_key,
    uuid_value,
    bigint_value
  ) values (
    'same-source-second',
    second_result.notification_id,
    second_result.sequence_number
  );
end;
$finish_same_source$;

select ok(
  (
    select first.uuid_value = second.uuid_value
      and first.bigint_value = second.bigint_value
    from notification_concurrency_observations as first
    join notification_concurrency_observations as second
      on second.observation_key = 'same-source-second'
    where first.observation_key = 'same-source-first'
  ),
  'same-source retry returns the original notification and sequence'
);

select ok(
  (
    select state.latest_sequence = 3
    from public.notification_inbox_states_v1 as state
    where state.user_id = 'bc100000-0000-4000-8000-000000000001'
  )
  and (
    select count(*) = 3
    from public.notifications
    where user_id = 'bc100000-0000-4000-8000-000000000001'
  ),
  'same-source retry consumes no additional sequence or row'
);

do $enqueue_before_deletion_setup$
begin
  perform extensions.dblink_exec('notification_first', 'begin');
  perform extensions.dblink_exec('notification_first', 'set local role service_role');
  perform result.notification_id
  from extensions.dblink(
    'notification_first',
    $query$
      select notification_id, sequence_number, enqueued
      from public.enqueue_notification_v1(
        'bc100000-0000-4000-8000-000000000002',
        'dc100000-0000-4000-8000-000000000101',
        'suggestion',
        'notification.suggestion.title',
        '{}'::jsonb,
        'notification.suggestion.body',
        '{"listing_name":"Porte du Non-Retour"}'::jsonb,
        '00000000-0000-4000-8000-000000000101'
      )
    $query$
  ) as result(notification_id uuid, sequence_number bigint, enqueued boolean);

  perform extensions.dblink_exec('notification_second', 'begin');
  perform extensions.dblink_exec('notification_second', 'set local role service_role');
  if extensions.dblink_send_query(
    'notification_second',
    $query$
      select status, effective_idempotency_key
      from public.prepare_account_deletion(
        'bc100000-0000-4000-8000-000000000002',
        'dc100000-0000-4000-8000-000000000102'
      )
    $query$
  ) <> 1 then
    raise exception 'Unable to start account deletion after enqueue';
  end if;
end;
$enqueue_before_deletion_setup$;

do $$
begin
  perform pg_catalog.pg_sleep(0.25);
end;
$$;

select is(
  extensions.dblink_is_busy('notification_second'),
  1,
  'account deletion waits for an earlier enqueue transaction'
);

do $finish_enqueue_before_deletion$
begin
  perform extensions.dblink_exec('notification_first', 'commit');
  perform result.status
  from extensions.dblink_get_result('notification_second', false) as result(
    status text,
    effective_idempotency_key uuid
  );
  perform result.command_status
  from extensions.dblink_get_result('notification_second', false) as result(
    command_status text
  );
  perform extensions.dblink_exec('notification_second', 'commit');
end;
$finish_enqueue_before_deletion$;

select ok(
  not exists (
    select 1 from public.notifications
    where user_id = 'bc100000-0000-4000-8000-000000000002'
  )
  and not exists (
    select 1 from public.notification_inbox_states_v1
    where user_id = 'bc100000-0000-4000-8000-000000000002'
  )
  and not exists (
    select 1 from public.notification_preferences_v1
    where user_id = 'bc100000-0000-4000-8000-000000000002'
  )
  and not exists (
    select 1 from public.notification_enqueue_receipts_v1
    where user_id = 'bc100000-0000-4000-8000-000000000002'
  )
  and exists (
    select 1 from public.account_deletion_requests
    where user_id = 'bc100000-0000-4000-8000-000000000002'
  ),
  'deletion purges the committed earlier enqueue and leaves only its tombstone'
);

do $deletion_before_enqueue_setup$
begin
  perform extensions.dblink_exec('notification_first', 'begin');
  perform extensions.dblink_exec('notification_first', 'set local role service_role');
  perform result.status
  from extensions.dblink(
    'notification_first',
    $query$
      select status, effective_idempotency_key
      from public.prepare_account_deletion(
        'bc100000-0000-4000-8000-000000000003',
        'dc100000-0000-4000-8000-000000000202'
      )
    $query$
  ) as result(status text, effective_idempotency_key uuid);

  perform extensions.dblink_exec(
    'notification_second',
    'truncate table pg_temp.notification_transition_result'
  );
  perform extensions.dblink_exec('notification_second', 'begin');
  perform extensions.dblink_exec('notification_second', 'set local role service_role');

  if extensions.dblink_send_query(
    'notification_second',
    $query$
      do $remote$
      begin
        begin
          perform * from public.enqueue_notification_v1(
            'bc100000-0000-4000-8000-000000000003',
            'dc100000-0000-4000-8000-000000000201',
            'suggestion',
            'notification.suggestion.title',
            '{}'::jsonb,
            'notification.suggestion.body',
            '{"listing_name":"Porte du Non-Retour"}'::jsonb,
            '00000000-0000-4000-8000-000000000101'
          );
          insert into pg_temp.notification_transition_result (sqlstate, message)
          values ('00000', 'unexpected success');
        exception
          when others then
            insert into pg_temp.notification_transition_result (sqlstate, message)
            values (sqlstate, sqlerrm);
        end;
      end;
      $remote$
    $query$
  ) <> 1 then
    raise exception 'Unable to start enqueue after account deletion';
  end if;
end;
$deletion_before_enqueue_setup$;

do $$
begin
  perform pg_catalog.pg_sleep(0.25);
end;
$$;

select is(
  extensions.dblink_is_busy('notification_second'),
  1,
  'enqueue waits for an earlier account deletion transaction'
);

do $finish_deletion_before_enqueue$
declare
  transition_result text;
begin
  perform extensions.dblink_exec('notification_first', 'commit');
  perform result.command_status
  from extensions.dblink_get_result('notification_second', false) as result(
    command_status text
  );
  perform result.command_status
  from extensions.dblink_get_result('notification_second', false) as result(
    command_status text
  );

  select transition.sqlstate || '|' || transition.message
  into strict transition_result
  from extensions.dblink(
    'notification_second',
    'select sqlstate, message from pg_temp.notification_transition_result'
  ) as transition(sqlstate text, message text);

  insert into notification_concurrency_observations (
    observation_key,
    text_value
  ) values (
    'deletion-first-result',
    transition_result
  );

  perform extensions.dblink_exec('notification_second', 'commit');
end;
$finish_deletion_before_enqueue$;

select is(
  (
    select text_value
    from notification_concurrency_observations
    where observation_key = 'deletion-first-result'
  ),
  '42501|Notification recipient is unavailable'::text,
  'enqueue rechecks and rejects the committed deletion tombstone'
);

select ok(
  not exists (
    select 1 from public.notifications
    where user_id = 'bc100000-0000-4000-8000-000000000003'
  )
  and not exists (
    select 1 from public.notification_inbox_states_v1
    where user_id = 'bc100000-0000-4000-8000-000000000003'
  )
  and not exists (
    select 1 from public.notification_preferences_v1
    where user_id = 'bc100000-0000-4000-8000-000000000003'
  )
  and not exists (
    select 1 from public.notification_enqueue_receipts_v1
    where user_id = 'bc100000-0000-4000-8000-000000000003'
  )
  and exists (
    select 1 from public.account_deletion_requests
    where user_id = 'bc100000-0000-4000-8000-000000000003'
  ),
  'deletion-first ordering leaves no resurrected notification data'
);

do $disconnect$
begin
  perform extensions.dblink_disconnect('notification_check');
  perform extensions.dblink_disconnect('notification_second');
  perform extensions.dblink_disconnect('notification_first');
end;
$disconnect$;

select * from finish();
rollback;

delete from public.account_deletion_requests
where user_id in (
  'bc100000-0000-4000-8000-000000000001',
  'bc100000-0000-4000-8000-000000000002',
  'bc100000-0000-4000-8000-000000000003'
);

delete from auth.users
where id in (
  'bc100000-0000-4000-8000-000000000001',
  'bc100000-0000-4000-8000-000000000002',
  'bc100000-0000-4000-8000-000000000003'
);

revoke service_role from kwabor_notification_concurrency_test;
drop role kwabor_notification_concurrency_test;
