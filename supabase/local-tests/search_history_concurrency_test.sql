do $local_only$
begin
  if current_setting('kwabor.local_concurrency_harness', true)
      is distinct from 'explicit-local-wrapper'
    or current_setting('ssl') <> 'off'
  then
    raise exception 'search_history_concurrency_test.sql requires the explicit local-only runner';
  end if;
end;
$local_only$;

create temporary table search_history_concurrency_credentials (
  password text not null
) on commit preserve rows;

insert into search_history_concurrency_credentials (password)
values (encode(extensions.gen_random_bytes(32), 'hex'));

do $role_setup$
declare
  test_password text;
begin
  if exists (
    select 1
    from pg_catalog.pg_roles
    where rolname = 'kwabor_search_history_concurrency_test'
  ) then
    execute 'revoke authenticated from kwabor_search_history_concurrency_test';
    execute 'revoke all privileges on table public.search_history_entries, public.search_history_preferences from kwabor_search_history_concurrency_test';
    execute 'revoke usage on schema public from kwabor_search_history_concurrency_test';
    execute 'drop role kwabor_search_history_concurrency_test';
  end if;

  select credentials.password
  into strict test_password
  from search_history_concurrency_credentials as credentials;

  execute format(
    'create role kwabor_search_history_concurrency_test login noinherit bypassrls connection limit 3 password %L valid until %L',
    test_password,
    pg_catalog.clock_timestamp() + interval '5 minutes'
  );
end;
$role_setup$;

grant authenticated to kwabor_search_history_concurrency_test;
grant usage on schema public to kwabor_search_history_concurrency_test;
grant select, insert, delete on table
  public.search_history_entries,
  public.search_history_preferences
to kwabor_search_history_concurrency_test;

delete from auth.users
where id = 'bc100000-0000-4000-8000-000000000001';

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
values (
  'bc100000-0000-4000-8000-000000000001',
  'authenticated',
  'authenticated',
  'history-concurrency@kwabor.test',
  '',
  now(),
  now(),
  now()
);

begin;

create extension if not exists dblink with schema extensions;

select plan(11);

do $test_setup$
declare
  connection_info text := format(
    'hostaddr=%s port=%s dbname=postgres user=kwabor_search_history_concurrency_test password=%s',
    inet_server_addr(),
    inet_server_port(),
    (
      select credentials.password
      from search_history_concurrency_credentials as credentials
    )
  );
begin
  perform extensions.dblink_connect('history_setup', connection_info);
  perform extensions.dblink_connect('history_first', connection_info);
  perform extensions.dblink_connect('history_second', connection_info);

  perform extensions.dblink_exec(
    'history_setup',
    $setup$
      insert into public.search_history_preferences (user_id)
      values ('bc100000-0000-4000-8000-000000000001');

      insert into public.search_history_entries (
        id,
        user_id,
        canonical_query,
        created_at,
        last_submitted_at
      )
      select
        md5(sequence_number::text)::uuid,
        'bc100000-0000-4000-8000-000000000001',
        'seed-' || lpad(sequence_number::text, 3, '0'),
        '2020-01-01 00:00:00+00'::timestamptz
          + sequence_number * interval '1 millisecond',
        '2020-01-01 00:00:00+00'::timestamptz
          + sequence_number * interval '1 millisecond'
      from generate_series(1, 199) as generated(sequence_number);
    $setup$
  );

  perform extensions.dblink_exec('history_first', 'begin');
  perform extensions.dblink_exec('history_first', 'set local role authenticated');
  perform extensions.dblink_exec(
    'history_first',
    'set local request.jwt.claim.role = ''authenticated'''
  );
  perform extensions.dblink_exec(
    'history_first',
    'set local request.jwt.claim.sub = ''bc100000-0000-4000-8000-000000000001'''
  );
  perform result.entry_id
  from extensions.dblink(
    'history_first',
    'select entry_id from public.record_search_history_v1(''concurrent-a'')'
  ) as result(entry_id uuid);

  perform extensions.dblink_exec('history_second', 'begin');
  perform extensions.dblink_exec('history_second', 'set local role authenticated');
  perform extensions.dblink_exec(
    'history_second',
    'set local request.jwt.claim.role = ''authenticated'''
  );
  perform extensions.dblink_exec(
    'history_second',
    'set local request.jwt.claim.sub = ''bc100000-0000-4000-8000-000000000001'''
  );

  if extensions.dblink_send_query(
    'history_second',
    'select entry_id from public.record_search_history_v1(''concurrent-b'')'
  ) <> 1 then
    raise exception 'Unable to start the concurrent capped history write';
  end if;
end;
$test_setup$;

do $$
begin
  perform pg_catalog.pg_sleep(0.25);
end;
$$;

select is(
  extensions.dblink_is_busy('history_second'),
  1,
  'a concurrent record waits on the account-scoped mutation lock'
);

do $$
begin
  perform extensions.dblink_exec('history_first', 'commit');
  perform result.entry_id
  from extensions.dblink_get_result('history_second', false) as result(entry_id uuid);
  perform result.status
  from extensions.dblink_get_result('history_second', false) as result(status text);
  perform extensions.dblink_exec('history_second', 'commit');
end;
$$;

select is(
  (
    select result.row_count
    from extensions.dblink(
      'history_setup',
      $count$
        select count(*)
        from public.search_history_entries
        where user_id = 'bc100000-0000-4000-8000-000000000001'
      $count$
    ) as result(row_count bigint)
  ),
  200::bigint,
  'two concurrent submissions keep the owner at the 200-row cap'
);

select ok(
  (
    select result.present
    from extensions.dblink(
      'history_setup',
      $query$
        select exists (
          select 1
          from public.search_history_entries
          where user_id = 'bc100000-0000-4000-8000-000000000001'
            and canonical_query = 'concurrent-a'
        )
      $query$
    ) as result(present boolean)
  ),
  'the first concurrent submission remains active'
);

select ok(
  (
    select result.present
    from extensions.dblink(
      'history_setup',
      $query$
        select exists (
          select 1
          from public.search_history_entries
          where user_id = 'bc100000-0000-4000-8000-000000000001'
            and canonical_query = 'concurrent-b'
        )
      $query$
    ) as result(present boolean)
  ),
  'the waiting concurrent submission remains active'
);

select ok(
  not (
    select result.present
    from extensions.dblink(
      'history_setup',
      $query$
        select exists (
          select 1
          from public.search_history_entries
          where user_id = 'bc100000-0000-4000-8000-000000000001'
            and canonical_query = 'seed-001'
        )
      $query$
    ) as result(present boolean)
  ),
  'concurrent capacity enforcement evicts only the oldest seed'
);

select is(
  (
    select result.enabled
    from extensions.dblink(
      'history_setup',
      $preference$
        select activity_personalization_enabled
        from public.search_history_preferences
        where user_id = 'bc100000-0000-4000-8000-000000000001'
      $preference$
    ) as result(enabled boolean)
  ),
  false,
  'concurrent records keep personalization disabled'
);

do $duplicate_setup$
begin
  perform extensions.dblink_exec(
    'history_setup',
    $setup$
      delete from public.search_history_entries
      where user_id = 'bc100000-0000-4000-8000-000000000001';

      insert into public.search_history_entries (
        id,
        user_id,
        canonical_query,
        created_at,
        last_submitted_at
      ) values (
        'bc1d0000-0000-4000-8000-000000000001',
        'bc100000-0000-4000-8000-000000000001',
        'same-query',
        '2020-01-01 00:00:00+00',
        '2020-01-01 00:00:00+00'
      );
    $setup$
  );

  perform extensions.dblink_exec('history_first', 'begin');
  perform extensions.dblink_exec('history_first', 'set local role authenticated');
  perform extensions.dblink_exec(
    'history_first',
    'set local request.jwt.claim.role = ''authenticated'''
  );
  perform extensions.dblink_exec(
    'history_first',
    'set local request.jwt.claim.sub = ''bc100000-0000-4000-8000-000000000001'''
  );
  perform result.entry_id
  from extensions.dblink(
    'history_first',
    'select entry_id from public.record_search_history_v1(''same-query'')'
  ) as result(entry_id uuid);

  perform extensions.dblink_exec('history_second', 'begin');
  perform extensions.dblink_exec('history_second', 'set local role authenticated');
  perform extensions.dblink_exec(
    'history_second',
    'set local request.jwt.claim.role = ''authenticated'''
  );
  perform extensions.dblink_exec(
    'history_second',
    'set local request.jwt.claim.sub = ''bc100000-0000-4000-8000-000000000001'''
  );

  if extensions.dblink_send_query(
    'history_second',
    'select entry_id from public.record_search_history_v1(''same-query'')'
  ) <> 1 then
    raise exception 'Unable to start the concurrent canonical upsert';
  end if;
end;
$duplicate_setup$;

do $$
begin
  perform pg_catalog.pg_sleep(0.25);
end;
$$;

select is(
  extensions.dblink_is_busy('history_second'),
  1,
  'a concurrent resubmission waits on the same account lock'
);

do $$
begin
  perform extensions.dblink_exec('history_first', 'commit');
  perform result.entry_id
  from extensions.dblink_get_result('history_second', false) as result(entry_id uuid);
  perform result.status
  from extensions.dblink_get_result('history_second', false) as result(status text);
  perform extensions.dblink_exec('history_second', 'commit');
end;
$$;

select is(
  (
    select result.row_count
    from extensions.dblink(
      'history_setup',
      $count$
        select count(*)
        from public.search_history_entries
        where user_id = 'bc100000-0000-4000-8000-000000000001'
          and canonical_query = 'same-query'
      $count$
    ) as result(row_count bigint)
  ),
  1::bigint,
  'concurrent canonical resubmission creates no duplicate'
);

select is(
  (
    select result.entry_id
    from extensions.dblink(
      'history_setup',
      $entry$
        select id
        from public.search_history_entries
        where user_id = 'bc100000-0000-4000-8000-000000000001'
          and canonical_query = 'same-query'
      $entry$
    ) as result(entry_id uuid)
  ),
  'bc1d0000-0000-4000-8000-000000000001'::uuid,
  'concurrent canonical resubmission preserves identity'
);

select is(
  (
    select result.created_at
    from extensions.dblink(
      'history_setup',
      $entry$
        select created_at
        from public.search_history_entries
        where user_id = 'bc100000-0000-4000-8000-000000000001'
          and canonical_query = 'same-query'
      $entry$
    ) as result(created_at timestamptz)
  ),
  '2020-01-01 00:00:00+00'::timestamptz,
  'concurrent canonical resubmission preserves creation time'
);

select cmp_ok(
  (
    select result.last_submitted_at
    from extensions.dblink(
      'history_setup',
      $entry$
        select last_submitted_at
        from public.search_history_entries
        where user_id = 'bc100000-0000-4000-8000-000000000001'
          and canonical_query = 'same-query'
      $entry$
    ) as result(last_submitted_at timestamptz)
  ),
  '>',
  '2020-01-01 00:00:00+00'::timestamptz,
  'concurrent canonical resubmission uses a server timestamp'
);

do $$
begin
  perform extensions.dblink_disconnect('history_second');
  perform extensions.dblink_disconnect('history_first');
  perform extensions.dblink_disconnect('history_setup');
end;
$$;

select * from finish();
rollback;

delete from auth.users
where id = 'bc100000-0000-4000-8000-000000000001';

revoke authenticated from kwabor_search_history_concurrency_test;
revoke all privileges on table
  public.search_history_entries,
  public.search_history_preferences
from kwabor_search_history_concurrency_test;
revoke usage on schema public from kwabor_search_history_concurrency_test;
drop role kwabor_search_history_concurrency_test;
