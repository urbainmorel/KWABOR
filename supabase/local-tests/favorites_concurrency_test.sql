do $local_only$
begin
  if current_setting('kwabor.local_concurrency_harness', true)
      is distinct from 'explicit-local-wrapper'
    or current_setting('ssl') <> 'off'
  then
    raise exception 'favorites_concurrency_test.sql requires the explicit local-only runner';
  end if;
end;
$local_only$;

create temporary table favorites_concurrency_credentials (
  password text not null
) on commit preserve rows;

insert into favorites_concurrency_credentials (password)
values (encode(extensions.gen_random_bytes(32), 'hex'));

do $role_setup$
declare
  test_password text;
begin
  if exists (
    select 1
    from pg_catalog.pg_roles
    where rolname = 'kwabor_favorites_concurrency_test'
  ) then
    execute 'revoke authenticated from kwabor_favorites_concurrency_test';
    execute 'revoke service_role from kwabor_favorites_concurrency_test';
    execute 'drop role kwabor_favorites_concurrency_test';
  end if;

  select credentials.password
  into strict test_password
  from favorites_concurrency_credentials as credentials;

  execute format(
    'create role kwabor_favorites_concurrency_test login noinherit bypassrls connection limit 3 password %L valid until %L',
    test_password,
    pg_catalog.clock_timestamp() + interval '5 minutes'
  );
end;
$role_setup$;

grant authenticated to kwabor_favorites_concurrency_test;
grant service_role to kwabor_favorites_concurrency_test;

delete from public.account_deletion_requests
where user_id in (
  'fc100000-0000-4000-8000-000000000001',
  'fc100000-0000-4000-8000-000000000002',
  'fc100000-0000-4000-8000-000000000003',
  'fc100000-0000-4000-8000-000000000004',
  'fc100000-0000-4000-8000-000000000005',
  'fc100000-0000-4000-8000-000000000006',
  'fc100000-0000-4000-8000-000000000007'
);

delete from auth.users
where id in (
  'fc100000-0000-4000-8000-000000000001',
  'fc100000-0000-4000-8000-000000000002',
  'fc100000-0000-4000-8000-000000000003',
  'fc100000-0000-4000-8000-000000000004',
  'fc100000-0000-4000-8000-000000000005',
  'fc100000-0000-4000-8000-000000000006',
  'fc100000-0000-4000-8000-000000000007'
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
    'fc100000-0000-4000-8000-000000000001',
    'authenticated',
    'authenticated',
    'favorites-concurrency-set-first@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    'fc100000-0000-4000-8000-000000000002',
    'authenticated',
    'authenticated',
    'favorites-concurrency-delete-first@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    'fc100000-0000-4000-8000-000000000003',
    'authenticated',
    'authenticated',
    'favorites-concurrency-setters@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    'fc100000-0000-4000-8000-000000000004',
    'authenticated',
    'authenticated',
    'favorites-concurrency-read@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    'fc100000-0000-4000-8000-000000000005',
    'authenticated',
    'authenticated',
    'favorites-concurrency-direct-write@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    'fc100000-0000-4000-8000-000000000006',
    'authenticated',
    'authenticated',
    'likes-concurrency-direct-first@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    'fc100000-0000-4000-8000-000000000007',
    'authenticated',
    'authenticated',
    'likes-concurrency-deletion-first@kwabor.test',
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
    'fc100000-0000-4000-8000-000000000001',
    'Concurrence',
    'MutationAvantSuppression',
    'cotonou',
    now()
  ),
  (
    'fc100000-0000-4000-8000-000000000002',
    'Concurrence',
    'SuppressionAvantMutation',
    'cotonou',
    now()
  ),
  (
    'fc100000-0000-4000-8000-000000000003',
    'Concurrence',
    'Mutations',
    'cotonou',
    now()
  ),
  (
    'fc100000-0000-4000-8000-000000000004',
    'Concurrence',
    'Lecture',
    'cotonou',
    now()
  ),
  (
    'fc100000-0000-4000-8000-000000000005',
    'Concurrence',
    'EcritureDirecte',
    'cotonou',
    now()
  ),
  (
    'fc100000-0000-4000-8000-000000000006',
    'Concurrence',
    'LikeAvantSuppression',
    'cotonou',
    now()
  ),
  (
    'fc100000-0000-4000-8000-000000000007',
    'Concurrence',
    'SuppressionAvantLike',
    'cotonou',
    now()
  );

insert into public.favorites (user_id, listing_id)
values (
  'fc100000-0000-4000-8000-000000000004',
  '00000000-0000-4000-8000-000000000101'
);

begin;

create extension if not exists dblink with schema extensions;

select plan(23);

create temporary table favorites_concurrency_observations (
  observation_key text primary key,
  boolean_value boolean,
  bigint_value bigint,
  text_value text
);

do $connect$
declare
  connection_info text := format(
    'hostaddr=%s port=%s dbname=postgres user=kwabor_favorites_concurrency_test password=%s',
    inet_server_addr(),
    inet_server_port(),
    (
      select credentials.password
      from favorites_concurrency_credentials as credentials
    )
  );
begin
  perform extensions.dblink_connect('favorites_first', connection_info);
  perform extensions.dblink_connect('favorites_second', connection_info);
  perform extensions.dblink_connect('favorites_check', connection_info);
  perform extensions.dblink_exec(
    'favorites_first',
    'set statement_timeout = ''30s'''
  );
  perform extensions.dblink_exec(
    'favorites_second',
    'set statement_timeout = ''30s'''
  );
  perform extensions.dblink_exec(
    'favorites_check',
    'set statement_timeout = ''30s'''
  );
  perform extensions.dblink_exec(
    'favorites_second',
    'create temporary table like_transition_result (sqlstate text, message text) on commit preserve rows'
  );
  perform extensions.dblink_exec(
    'favorites_second',
    'grant select, insert on table pg_temp.like_transition_result to authenticated'
  );
end;
$connect$;

do $setter_first_setup$
declare
  setter_result record;
begin
  perform extensions.dblink_exec('favorites_first', 'begin');
  perform extensions.dblink_exec('favorites_first', 'set local role authenticated');
  perform extensions.dblink_exec(
    'favorites_first',
    'set local request.jwt.claim.role = ''authenticated'''
  );
  perform extensions.dblink_exec(
    'favorites_first',
    'set local request.jwt.claim.sub = ''fc100000-0000-4000-8000-000000000001'''
  );

  select result.*
  into strict setter_result
  from extensions.dblink(
    'favorites_first',
    $query$
      select listing_id, favorited_by_current_user, favorited_at
      from public.set_listing_favorite_v1(
        '00000000-0000-4000-8000-000000000101',
        true
      )
    $query$
  ) as result(
    listing_id uuid,
    favorited_by_current_user boolean,
    favorited_at timestamptz
  );

  insert into favorites_concurrency_observations (
    observation_key,
    boolean_value
  ) values (
    'setter-first-result',
    setter_result.favorited_by_current_user
  );

  perform extensions.dblink_exec('favorites_second', 'begin');
  perform extensions.dblink_exec('favorites_second', 'set local role service_role');

  if extensions.dblink_send_query(
    'favorites_second',
    $query$
      select status, effective_idempotency_key
      from public.prepare_account_deletion(
        'fc100000-0000-4000-8000-000000000001',
        'fc1d0000-0000-4000-8000-000000000001'
      )
    $query$
  ) <> 1 then
    raise exception 'Unable to start deletion after the favorite mutation';
  end if;
end;
$setter_first_setup$;

select ok(
  (select boolean_value from favorites_concurrency_observations
    where observation_key = 'setter-first-result'),
  'the first transaction creates the favorite before holding its lock'
);

do $$
begin
  perform pg_catalog.pg_sleep(0.25);
end;
$$;

select is(
  extensions.dblink_is_busy('favorites_second'),
  1,
  'account deletion waits for an earlier favorite mutation'
);

do $$
begin
  perform extensions.dblink_exec('favorites_first', 'commit');
  perform result.status
  from extensions.dblink_get_result('favorites_second', false) as result(
    status text,
    effective_idempotency_key uuid
  );
  perform result.command_status
  from extensions.dblink_get_result('favorites_second', false) as result(
    command_status text
  );
  perform extensions.dblink_exec('favorites_second', 'commit');
end;
$$;

select ok(
  not exists (
    select 1
    from public.favorites
    where user_id = 'fc100000-0000-4000-8000-000000000001'
  ),
  'deletion cleans the favorite committed before it'
);

select ok(
  exists (
    select 1
    from public.account_deletion_requests
    where user_id = 'fc100000-0000-4000-8000-000000000001'
  ),
  'the serialized deletion leaves its account tombstone'
);

do $deletion_first_setup$
begin
  perform extensions.dblink_exec('favorites_first', 'begin');
  perform extensions.dblink_exec('favorites_first', 'set local role service_role');
  perform result.status
  from extensions.dblink(
    'favorites_first',
    $query$
      select status, effective_idempotency_key
      from public.prepare_account_deletion(
        'fc100000-0000-4000-8000-000000000002',
        'fc1d0000-0000-4000-8000-000000000002'
      )
    $query$
  ) as result(status text, effective_idempotency_key uuid);

  perform extensions.dblink_exec('favorites_second', 'begin');
  perform extensions.dblink_exec('favorites_second', 'set local role authenticated');
  perform extensions.dblink_exec(
    'favorites_second',
    'set local request.jwt.claim.role = ''authenticated'''
  );
  perform extensions.dblink_exec(
    'favorites_second',
    'set local request.jwt.claim.sub = ''fc100000-0000-4000-8000-000000000002'''
  );

  if extensions.dblink_send_query(
    'favorites_second',
    $query$
      select listing_id, favorited_by_current_user, favorited_at
      from public.set_listing_favorite_v1(
        '00000000-0000-4000-8000-000000000101',
        true
      )
    $query$
  ) <> 1 then
    raise exception 'Unable to start mutation after account deletion';
  end if;
end;
$deletion_first_setup$;

do $$
begin
  perform pg_catalog.pg_sleep(0.25);
end;
$$;

select is(
  extensions.dblink_is_busy('favorites_second'),
  1,
  'a favorite mutation waits for an earlier account deletion'
);

do $$
begin
  perform extensions.dblink_exec('favorites_first', 'commit');
  perform result.listing_id
  from extensions.dblink_get_result('favorites_second', false) as result(
    listing_id uuid,
    favorited_by_current_user boolean,
    favorited_at timestamptz
  );

  insert into favorites_concurrency_observations (
    observation_key,
    text_value
  ) values (
    'deletion-first-error',
    extensions.dblink_error_message('favorites_second')
  );

  -- libpq exposes a final empty result after the failed command result.
  -- Consume it before issuing the rollback on the asynchronous connection.
  perform result.command_status
  from extensions.dblink_get_result('favorites_second', false) as result(
    command_status text
  );

  perform extensions.dblink_exec('favorites_second', 'rollback');
end;
$$;

select ok(
  (
    select text_value
    from favorites_concurrency_observations
    where observation_key = 'deletion-first-error'
  ) like '%Onboarding completion required%',
  'the waiting mutation observes the tombstone and fails closed'
);

select ok(
  not exists (
    select 1
    from public.favorites
    where user_id = 'fc100000-0000-4000-8000-000000000002'
  ),
  'the failed late mutation cannot recreate deleted account data'
);

do $serialized_setters_setup$
begin
  perform extensions.dblink_exec('favorites_first', 'begin');
  perform extensions.dblink_exec('favorites_first', 'set local role authenticated');
  perform extensions.dblink_exec(
    'favorites_first',
    'set local request.jwt.claim.role = ''authenticated'''
  );
  perform extensions.dblink_exec(
    'favorites_first',
    'set local request.jwt.claim.sub = ''fc100000-0000-4000-8000-000000000003'''
  );
  perform result.listing_id
  from extensions.dblink(
    'favorites_first',
    $query$
      select listing_id, favorited_by_current_user, favorited_at
      from public.set_listing_favorite_v1(
        '00000000-0000-4000-8000-000000000101',
        true
      )
    $query$
  ) as result(
    listing_id uuid,
    favorited_by_current_user boolean,
    favorited_at timestamptz
  );

  perform extensions.dblink_exec('favorites_second', 'begin');
  perform extensions.dblink_exec('favorites_second', 'set local role authenticated');
  perform extensions.dblink_exec(
    'favorites_second',
    'set local request.jwt.claim.role = ''authenticated'''
  );
  perform extensions.dblink_exec(
    'favorites_second',
    'set local request.jwt.claim.sub = ''fc100000-0000-4000-8000-000000000003'''
  );

  if extensions.dblink_send_query(
    'favorites_second',
    $query$
      select listing_id, favorited_by_current_user, favorited_at
      from public.set_listing_favorite_v1(
        '00000000-0000-4000-8000-000000000101',
        false
      )
    $query$
  ) <> 1 then
    raise exception 'Unable to start the second serialized setter';
  end if;
end;
$serialized_setters_setup$;

do $$
begin
  perform pg_catalog.pg_sleep(0.25);
end;
$$;

select is(
  extensions.dblink_is_busy('favorites_second'),
  1,
  'concurrent setters for one account are serialized'
);

do $$
declare
  remove_result record;
begin
  perform extensions.dblink_exec('favorites_first', 'commit');
  select result.*
  into strict remove_result
  from extensions.dblink_get_result('favorites_second', false) as result(
    listing_id uuid,
    favorited_by_current_user boolean,
    favorited_at timestamptz
  );

  insert into favorites_concurrency_observations (
    observation_key,
    boolean_value
  ) values (
    'serialized-remove-result',
    remove_result.favorited_by_current_user
  );

  perform result.command_status
  from extensions.dblink_get_result('favorites_second', false) as result(
    command_status text
  );
  perform extensions.dblink_exec('favorites_second', 'commit');
end;
$$;

select ok(
  not (
    select boolean_value
    from favorites_concurrency_observations
    where observation_key = 'serialized-remove-result'
  ),
  'the later serialized setter returns the requested absent state'
);

select ok(
  not exists (
    select 1
    from public.favorites
    where user_id = 'fc100000-0000-4000-8000-000000000003'
  ),
  'the later serialized setter determines the final state'
);

do $reader_first_setup$
declare
  favorite_count bigint;
begin
  perform extensions.dblink_exec('favorites_first', 'begin');
  perform extensions.dblink_exec('favorites_first', 'set local role authenticated');
  perform extensions.dblink_exec(
    'favorites_first',
    'set local request.jwt.claim.role = ''authenticated'''
  );
  perform extensions.dblink_exec(
    'favorites_first',
    'set local request.jwt.claim.sub = ''fc100000-0000-4000-8000-000000000004'''
  );

  select result.row_count
  into strict favorite_count
  from extensions.dblink(
    'favorites_first',
    'select count(*) from public.list_favorite_listing_summaries_v1()'
  ) as result(row_count bigint);

  insert into favorites_concurrency_observations (
    observation_key,
    bigint_value
  ) values (
    'reader-count',
    favorite_count
  );

  perform extensions.dblink_exec('favorites_second', 'begin');
  perform extensions.dblink_exec('favorites_second', 'set local role service_role');

  if extensions.dblink_send_query(
    'favorites_second',
    $query$
      select status, effective_idempotency_key
      from public.prepare_account_deletion(
        'fc100000-0000-4000-8000-000000000004',
        'fc1d0000-0000-4000-8000-000000000004'
      )
    $query$
  ) <> 1 then
    raise exception 'Unable to start deletion after the favorite snapshot';
  end if;
end;
$reader_first_setup$;

select is(
  (
    select bigint_value
    from favorites_concurrency_observations
    where observation_key = 'reader-count'
  ),
  1::bigint,
  'the reader completes its owner snapshot before deletion'
);

do $$
begin
  perform pg_catalog.pg_sleep(0.25);
end;
$$;

select is(
  extensions.dblink_is_busy('favorites_second'),
  1,
  'account deletion waits for an earlier shared snapshot lock'
);

do $$
begin
  perform extensions.dblink_exec('favorites_first', 'commit');
  perform result.status
  from extensions.dblink_get_result('favorites_second', false) as result(
    status text,
    effective_idempotency_key uuid
  );
  perform result.command_status
  from extensions.dblink_get_result('favorites_second', false) as result(
    command_status text
  );
  perform extensions.dblink_exec('favorites_second', 'commit');
end;
$$;

select ok(
  not exists (
    select 1
    from public.favorites
    where user_id = 'fc100000-0000-4000-8000-000000000004'
  ),
  'deletion purges the relation after the earlier snapshot commits'
);

do $direct_deletion_first_setup$
begin
  perform extensions.dblink_exec('favorites_first', 'begin');
  perform extensions.dblink_exec('favorites_first', 'set local role service_role');
  perform result.status
  from extensions.dblink(
    'favorites_first',
    $query$
      select status, effective_idempotency_key
      from public.prepare_account_deletion(
        'fc100000-0000-4000-8000-000000000005',
        'fc1d0000-0000-4000-8000-000000000005'
      )
    $query$
  ) as result(status text, effective_idempotency_key uuid);

  perform extensions.dblink_exec('favorites_second', 'begin');
  perform extensions.dblink_exec('favorites_second', 'set local role authenticated');
  perform extensions.dblink_exec(
    'favorites_second',
    'set local request.jwt.claim.role = ''authenticated'''
  );
  perform extensions.dblink_exec(
    'favorites_second',
    'set local request.jwt.claim.sub = ''fc100000-0000-4000-8000-000000000005'''
  );

  if extensions.dblink_send_query(
    'favorites_second',
    $query$
      insert into public.favorites (user_id, listing_id)
      values (
        'fc100000-0000-4000-8000-000000000005',
        '00000000-0000-4000-8000-000000000101'
      )
      returning listing_id
    $query$
  ) <> 1 then
    raise exception 'Unable to start direct favorite write after account deletion';
  end if;
end;
$direct_deletion_first_setup$;

do $direct_write_wait$
begin
  perform pg_catalog.pg_sleep(0.25);
end;
$direct_write_wait$;

select is(
  extensions.dblink_is_busy('favorites_second'),
  1,
  'a direct legacy table write waits for account deletion'
);

do $finish_direct_write$
begin
  perform extensions.dblink_exec('favorites_first', 'commit');
  perform result.listing_id
  from extensions.dblink_get_result('favorites_second', false) as result(
    listing_id uuid
  );

  insert into favorites_concurrency_observations (
    observation_key,
    text_value
  ) values (
    'direct-deletion-first-error',
    extensions.dblink_error_message('favorites_second')
  );

  perform result.command_status
  from extensions.dblink_get_result('favorites_second', false) as result(
    command_status text
  );
  perform extensions.dblink_exec('favorites_second', 'rollback');
end;
$finish_direct_write$;

select ok(
  (
    select text_value
    from favorites_concurrency_observations
    where observation_key = 'direct-deletion-first-error'
  ) like '%row-level security policy%',
  'the waiting direct write rechecks the committed deletion tombstone'
);

select ok(
  not exists (
    select 1
    from public.favorites
    where user_id = 'fc100000-0000-4000-8000-000000000005'
  ),
  'a legacy direct write cannot resurrect data after account cleanup'
);

do $like_direct_first_setup$
declare
  inserted_listing_id uuid;
begin
  perform extensions.dblink_exec('favorites_first', 'begin');
  perform extensions.dblink_exec(
    'favorites_first',
    'set local statement_timeout = ''5s'''
  );
  perform extensions.dblink_exec('favorites_first', 'set local role authenticated');
  perform extensions.dblink_exec(
    'favorites_first',
    'set local request.jwt.claim.role = ''authenticated'''
  );
  perform extensions.dblink_exec(
    'favorites_first',
    'set local request.jwt.claim.sub = ''fc100000-0000-4000-8000-000000000006'''
  );

  select result.listing_id
  into strict inserted_listing_id
  from extensions.dblink(
    'favorites_first',
    $query$
      insert into public.likes (user_id, listing_id)
      values (
        'fc100000-0000-4000-8000-000000000006',
        '00000000-0000-4000-8000-000000000101'
      )
      returning listing_id
    $query$
  ) as result(listing_id uuid);

  insert into favorites_concurrency_observations (
    observation_key,
    boolean_value
  ) values (
    'like-direct-first-inserted',
    inserted_listing_id = '00000000-0000-4000-8000-000000000101'::uuid
  );

  perform extensions.dblink_exec('favorites_second', 'begin');
  perform extensions.dblink_exec(
    'favorites_second',
    'set local statement_timeout = ''5s'''
  );
  perform extensions.dblink_exec('favorites_second', 'set local role service_role');

  if extensions.dblink_send_query(
    'favorites_second',
    $query$
      select status, effective_idempotency_key
      from public.prepare_account_deletion(
        'fc100000-0000-4000-8000-000000000006',
        'fc1d0000-0000-4000-8000-000000000006'
      )
    $query$
  ) <> 1 then
    raise exception 'Unable to start deletion after the direct Like write';
  end if;
end;
$like_direct_first_setup$;

select ok(
  (
    select boolean_value
    from favorites_concurrency_observations
    where observation_key = 'like-direct-first-inserted'
  ),
  'the direct Like INSERT completes before its transaction retains the account lock'
);

do $like_direct_first_wait$
begin
  perform pg_catalog.pg_sleep(0.25);
end;
$like_direct_first_wait$;

select is(
  extensions.dblink_is_busy('favorites_second'),
  1,
  'account deletion waits for an earlier direct Like INSERT transaction'
);

do $finish_like_direct_first$
begin
  perform extensions.dblink_exec('favorites_first', 'commit');
  perform result.status
  from extensions.dblink_get_result('favorites_second', false) as result(
    status text,
    effective_idempotency_key uuid
  );
  perform result.command_status
  from extensions.dblink_get_result('favorites_second', false) as result(
    command_status text
  );
  perform extensions.dblink_exec('favorites_second', 'commit');
end;
$finish_like_direct_first$;

select ok(
  not exists (
    select 1
    from public.likes
    where user_id = 'fc100000-0000-4000-8000-000000000006'
  )
  and (
    select listing.likes_count = (
      select count(*)
      from public.likes as counted_like
      where counted_like.listing_id = listing.id
    )
    from public.listings as listing
    where listing.id = '00000000-0000-4000-8000-000000000101'
  ),
  'deletion purges the earlier direct Like and restores its aggregate counter'
);

select ok(
  exists (
    select 1
    from public.account_deletion_requests
    where user_id = 'fc100000-0000-4000-8000-000000000006'
  ),
  'the mutation-first Like ordering finishes with a deletion tombstone'
);

do $like_deletion_first_setup$
begin
  perform extensions.dblink_exec(
    'favorites_second',
    'truncate table pg_temp.like_transition_result'
  );

  perform extensions.dblink_exec('favorites_first', 'begin');
  perform extensions.dblink_exec(
    'favorites_first',
    'set local statement_timeout = ''5s'''
  );
  perform extensions.dblink_exec('favorites_first', 'set local role service_role');
  perform result.status
  from extensions.dblink(
    'favorites_first',
    $query$
      select status, effective_idempotency_key
      from public.prepare_account_deletion(
        'fc100000-0000-4000-8000-000000000007',
        'fc1d0000-0000-4000-8000-000000000007'
      )
    $query$
  ) as result(status text, effective_idempotency_key uuid);

  perform extensions.dblink_exec('favorites_second', 'begin');
  perform extensions.dblink_exec(
    'favorites_second',
    'set local statement_timeout = ''5s'''
  );
  perform extensions.dblink_exec('favorites_second', 'set local role authenticated');
  perform extensions.dblink_exec(
    'favorites_second',
    'set local request.jwt.claim.role = ''authenticated'''
  );
  perform extensions.dblink_exec(
    'favorites_second',
    'set local request.jwt.claim.sub = ''fc100000-0000-4000-8000-000000000007'''
  );

  if extensions.dblink_send_query(
    'favorites_second',
    $query$
      do $remote$
      begin
        begin
          insert into public.likes (user_id, listing_id)
          values (
            'fc100000-0000-4000-8000-000000000007',
            '00000000-0000-4000-8000-000000000101'
          );

          insert into pg_temp.like_transition_result (sqlstate, message)
          values ('00000', 'unexpected success');
        exception
          when others then
            insert into pg_temp.like_transition_result (sqlstate, message)
            values (sqlstate, sqlerrm);
        end;
      end;
      $remote$
    $query$
  ) <> 1 then
    raise exception 'Unable to start the direct Like INSERT after account deletion';
  end if;
end;
$like_deletion_first_setup$;

do $like_deletion_first_wait$
begin
  perform pg_catalog.pg_sleep(0.25);
end;
$like_deletion_first_wait$;

select is(
  extensions.dblink_is_busy('favorites_second'),
  1,
  'a direct Like INSERT already in flight waits for the deletion transaction'
);

do $finish_like_deletion_first$
declare
  transition_result text;
begin
  perform extensions.dblink_exec('favorites_first', 'commit');
  perform result.command_status
  from extensions.dblink_get_result('favorites_second', false) as result(
    command_status text
  );
  perform result.command_status
  from extensions.dblink_get_result('favorites_second', false) as result(
    command_status text
  );

  select transition.sqlstate || '|' || transition.message
  into strict transition_result
  from extensions.dblink(
    'favorites_second',
    'select sqlstate, message from pg_temp.like_transition_result'
  ) as transition(sqlstate text, message text);

  insert into favorites_concurrency_observations (
    observation_key,
    text_value
  ) values (
    'like-deletion-first-result',
    transition_result
  );

  perform extensions.dblink_exec('favorites_second', 'commit');
end;
$finish_like_deletion_first$;

select ok(
  (
    select text_value
    from favorites_concurrency_observations
    where observation_key = 'like-deletion-first-result'
  ) like '42501|%row-level security policy%',
  'the waiting direct Like INSERT rechecks the tombstone and fails with SQLSTATE 42501'
);

select ok(
  not exists (
    select 1
    from public.likes
    where user_id = 'fc100000-0000-4000-8000-000000000007'
  )
  and exists (
    select 1
    from public.account_deletion_requests
    where user_id = 'fc100000-0000-4000-8000-000000000007'
  ),
  'the committed tombstone and failed late direct Like INSERT leave no resurrected account data'
);

do $$
begin
  perform extensions.dblink_disconnect('favorites_check');
  perform extensions.dblink_disconnect('favorites_second');
  perform extensions.dblink_disconnect('favorites_first');
end;
$$;

select * from finish();
rollback;

delete from public.account_deletion_requests
where user_id in (
  'fc100000-0000-4000-8000-000000000001',
  'fc100000-0000-4000-8000-000000000002',
  'fc100000-0000-4000-8000-000000000003',
  'fc100000-0000-4000-8000-000000000004',
  'fc100000-0000-4000-8000-000000000005',
  'fc100000-0000-4000-8000-000000000006',
  'fc100000-0000-4000-8000-000000000007'
);

delete from auth.users
where id in (
  'fc100000-0000-4000-8000-000000000001',
  'fc100000-0000-4000-8000-000000000002',
  'fc100000-0000-4000-8000-000000000003',
  'fc100000-0000-4000-8000-000000000004',
  'fc100000-0000-4000-8000-000000000005',
  'fc100000-0000-4000-8000-000000000006',
  'fc100000-0000-4000-8000-000000000007'
);

revoke service_role from kwabor_favorites_concurrency_test;
revoke authenticated from kwabor_favorites_concurrency_test;
drop role kwabor_favorites_concurrency_test;
