do $local_only$
begin
  if current_setting('kwabor.local_concurrency_harness', true)
      is distinct from 'explicit-local-wrapper'
    or current_setting('ssl') <> 'off'
  then
    raise exception 'event_details_concurrency_test.sql requires the explicit local-only runner';
  end if;
end;
$local_only$;

create temporary table event_concurrency_credentials (
  password text not null
) on commit preserve rows;

insert into event_concurrency_credentials (password)
values (encode(extensions.gen_random_bytes(32), 'hex'));

do $role_setup$
declare
  test_password text;
begin
  if exists (select 1 from pg_roles where rolname = 'kwabor_event_concurrency_test') then
    execute 'revoke all privileges on table public.categories, public.listings, public.event_details from kwabor_event_concurrency_test';
    execute 'revoke usage on schema public from kwabor_event_concurrency_test';
    execute 'drop role kwabor_event_concurrency_test';
  end if;

  select credentials.password
  into strict test_password
  from event_concurrency_credentials credentials;

  execute format(
    'create role kwabor_event_concurrency_test login noinherit bypassrls connection limit 3 password %L valid until %L',
    test_password,
    clock_timestamp() + interval '5 minutes'
  );
end;
$role_setup$;

grant usage on schema public to kwabor_event_concurrency_test;
grant select on table public.categories to kwabor_event_concurrency_test;
grant select, insert, update, delete
on table public.listings, public.event_details
to kwabor_event_concurrency_test;

begin;

create extension if not exists dblink with schema extensions;

select plan(12);

do $test_setup$
declare
  connection_info text := format(
    'hostaddr=%s port=%s dbname=postgres user=kwabor_event_concurrency_test password=%s',
    inet_server_addr(),
    inet_server_port(),
    (select credentials.password from event_concurrency_credentials credentials)
  );
begin
  perform extensions.dblink_connect('event_setup', connection_info);
  perform extensions.dblink_connect('event_delete', connection_info);
  perform extensions.dblink_connect('event_submit', connection_info);

  perform extensions.dblink_exec(
    'event_setup',
    $setup$
      delete from public.listings
      where id = 'ec100000-0000-4000-8000-000000000001';

      insert into public.listings (
        id,
        type,
        subtype,
        listing_class,
        category_id,
        status,
        name,
        slug,
        description,
        city_id,
        address,
        lat,
        lng,
        price_unit
      ) values (
        'ec100000-0000-4000-8000-000000000001',
        'evenement',
        'culture',
        'evenementiel',
        'event-culture',
        'brouillon',
        'Événement concurrence détails',
        'event-details-concurrency',
        'Événement temporaire utilisé pour vérifier la sérialisation parent enfant.',
        'cotonou',
        'Cotonou',
        6.3703,
        2.3912,
        'aucune'
      );

      insert into public.event_details (
        listing_id,
        category,
        start_at,
        organizer_name,
        organizer_contact,
        ticket_type
      ) values (
        'ec100000-0000-4000-8000-000000000001',
        'culture',
        '2030-02-01 18:00:00+01',
        'Organisation concurrence',
        'concurrency@kwabor.test',
        'gratuit'
      );
    $setup$
  );

  perform extensions.dblink_exec(
    'event_submit',
    'create temporary table event_transition_result (sqlstate text, message text) on commit preserve rows'
  );
  perform extensions.dblink_exec(
    'event_delete',
    'create temporary table event_transition_result (sqlstate text, message text) on commit preserve rows'
  );

  perform extensions.dblink_exec('event_delete', 'begin');
  perform extensions.dblink_exec(
    'event_delete',
    $delete$
      delete from public.event_details
      where listing_id = 'ec100000-0000-4000-8000-000000000001'
    $delete$
  );

  if extensions.dblink_send_query(
    'event_submit',
    $submit$
      do $remote$
      begin
        begin
          update public.listings
          set status = 'en_attente'
          where id = 'ec100000-0000-4000-8000-000000000001';

          insert into event_transition_result (sqlstate, message)
          values ('00000', 'unexpected success');
        exception
          when others then
            insert into event_transition_result (sqlstate, message)
            values (sqlstate, sqlerrm);
        end;
      end;
      $remote$
    $submit$
  ) <> 1 then
    raise exception 'Unable to start the concurrent event transition';
  end if;
end;
$test_setup$;

do $$
begin
  perform pg_sleep(0.25);
end;
$$;

select is(
  extensions.dblink_is_busy('event_submit'),
  1,
  'event submission waits while detail removal holds the parent lock'
);

do $$
begin
  perform extensions.dblink_exec('event_delete', 'commit');
  perform result.status
  from extensions.dblink_get_result('event_submit', false) as result(status text);
  -- libpq exposes a final empty result after the command result; consume it
  -- before reusing the asynchronous connection.
  perform result.status
  from extensions.dblink_get_result('event_submit', false) as result(status text);
end;
$$;

select is(
  (
    select transition.sqlstate || '|' || transition.message
    from extensions.dblink(
      'event_submit',
      'select sqlstate, message from event_transition_result'
    ) as transition(sqlstate text, message text)
  ),
  '23514|An event must have details before review or publication',
  'the waiting submission rechecks details after the concurrent removal commits'
);

select is(
  (
    select state.value
    from extensions.dblink(
      'event_setup',
      $state$
        select listing.status::text || '|' || count(detail.listing_id)::text
        from public.listings listing
        left join public.event_details detail on detail.listing_id = listing.id
        where listing.id = 'ec100000-0000-4000-8000-000000000001'
        group by listing.status
      $state$
    ) as state(value text)
  ),
  'brouillon|0',
  'the concurrent outcome remains a valid draft without details'
);

do $inverse_setup$
begin
  perform extensions.dblink_exec(
    'event_setup',
    $restore_details$
      insert into public.event_details (
        listing_id,
        category,
        start_at,
        organizer_name,
        organizer_contact,
        ticket_type
      ) values (
        'ec100000-0000-4000-8000-000000000001',
        'culture',
        '2030-02-01 18:00:00+01',
        'Organisation concurrence',
        'concurrency@kwabor.test',
        'gratuit'
      )
    $restore_details$
  );
  perform extensions.dblink_exec('event_delete', 'truncate event_transition_result');
  perform extensions.dblink_exec('event_submit', 'begin');
  perform extensions.dblink_exec(
    'event_submit',
    $submit$
      update public.listings
      set status = 'en_attente'
      where id = 'ec100000-0000-4000-8000-000000000001'
    $submit$
  );

  if extensions.dblink_send_query(
    'event_delete',
    $delete$
      do $remote$
      begin
        begin
          delete from public.event_details
          where listing_id = 'ec100000-0000-4000-8000-000000000001';

          insert into event_transition_result (sqlstate, message)
          values ('00000', 'unexpected success');
        exception
          when others then
            insert into event_transition_result (sqlstate, message)
            values (sqlstate, sqlerrm);
        end;
      end;
      $remote$
    $delete$
  ) <> 1 then
    raise exception 'Unable to start the inverse concurrent detail removal';
  end if;
end;
$inverse_setup$;

do $$
begin
  perform pg_sleep(0.25);
end;
$$;

select is(
  extensions.dblink_is_busy('event_delete'),
  1,
  'detail removal waits while event submission holds the parent lock'
);

do $$
begin
  perform extensions.dblink_exec('event_submit', 'commit');
  perform result.status
  from extensions.dblink_get_result('event_delete', false) as result(status text);
  perform result.status
  from extensions.dblink_get_result('event_delete', false) as result(status text);
end;
$$;

select is(
  (
    select transition.sqlstate || '|' || transition.message
    from extensions.dblink(
      'event_delete',
      'select sqlstate, message from event_transition_result'
    ) as transition(sqlstate text, message text)
  ),
  '23514|An event under review or published must keep its event details',
  'the waiting removal rechecks the committed event status'
);

select is(
  (
    select state.value
    from extensions.dblink(
      'event_setup',
      $state$
        select listing.status::text || '|' || count(detail.listing_id)::text
        from public.listings listing
        left join public.event_details detail on detail.listing_id = listing.id
        where listing.id = 'ec100000-0000-4000-8000-000000000001'
        group by listing.status
      $state$
    ) as state(value text)
  ),
  'en_attente|1',
  'the inverse concurrent outcome keeps required details on the submitted event'
);

do $$
begin
  perform extensions.dblink_exec(
    'event_setup',
    'update public.listings set status = ''brouillon'' where id = ''ec100000-0000-4000-8000-000000000001'''
  );
end;
$$;

do $location_setup$
begin
  perform extensions.dblink_exec(
    'event_setup',
    $setup$
      delete from public.listings
      where id = 'ec100000-0000-4000-8000-000000000001';

      delete from public.listings
      where id in (
        'ec100000-0000-4000-8000-000000000002',
        'ec100000-0000-4000-8000-000000000003'
      );

      insert into public.listings (
        id,
        type,
        subtype,
        listing_class,
        category_id,
        status,
        name,
        slug,
        description,
        city_id,
        address,
        lat,
        lng,
        price_unit
      ) values
        (
          'ec100000-0000-4000-8000-000000000002',
          'lieu',
          'nature',
          'patrimonial',
          'heritage-nature',
          'publie',
          'Lieu concurrence localisation',
          'event-location-concurrency-venue',
          'Lieu temporaire utilisé pour vérifier la sérialisation de la localisation.',
          'cotonou',
          'Cotonou',
          6.3703,
          2.3912,
          'aucune'
        ),
        (
          'ec100000-0000-4000-8000-000000000003',
          'evenement',
          'culture',
          'evenementiel',
          'event-culture',
          'brouillon',
          'Événement concurrence localisation',
          'event-location-concurrency',
          'Événement temporaire utilisé pour vérifier la sérialisation de la localisation.',
          'cotonou',
          'Cotonou',
          6.3703,
          2.3912,
          'aucune'
        );

      insert into public.event_details (
        listing_id,
        category,
        start_at,
        venue_listing_id,
        organizer_name,
        organizer_contact,
        ticket_type
      ) values (
        'ec100000-0000-4000-8000-000000000003',
        'culture',
        '2030-02-02 18:00:00+01',
        'ec100000-0000-4000-8000-000000000002',
        'Organisation concurrence localisation',
        'location-concurrency@kwabor.test',
        'gratuit'
      );
    $setup$
  );

  perform extensions.dblink_exec('event_submit', 'truncate event_transition_result');
  perform extensions.dblink_exec('event_delete', 'begin');
  perform extensions.dblink_exec(
    'event_delete',
    $remove_venue$
      update public.event_details
      set venue_listing_id = null
      where listing_id = 'ec100000-0000-4000-8000-000000000003'
    $remove_venue$
  );

  if extensions.dblink_send_query(
    'event_submit',
    $remove_location$
      do $remote$
      begin
        begin
          update public.listings
          set address = null,
              lat = null,
              lng = null
          where id = 'ec100000-0000-4000-8000-000000000003';

          insert into event_transition_result (sqlstate, message)
          values ('00000', 'unexpected success');
        exception
          when others then
            insert into event_transition_result (sqlstate, message)
            values (sqlstate, sqlerrm);
        end;
      end;
      $remote$
    $remove_location$
  ) <> 1 then
    raise exception 'Unable to start the concurrent event location update';
  end if;
end;
$location_setup$;

do $$
begin
  perform pg_sleep(0.25);
end;
$$;

select is(
  extensions.dblink_is_busy('event_submit'),
  1,
  'parent location removal waits while event details drop their venue'
);

do $$
begin
  perform extensions.dblink_exec('event_delete', 'commit');
  perform result.status
  from extensions.dblink_get_result('event_submit', false) as result(status text);
  perform result.status
  from extensions.dblink_get_result('event_submit', false) as result(status text);
end;
$$;

select is(
  (
    select transition.sqlstate || '|' || transition.message
    from extensions.dblink(
      'event_submit',
      'select sqlstate, message from event_transition_result'
    ) as transition(sqlstate text, message text)
  ),
  '23514|An event without a venue listing must keep an address and coordinates',
  'the waiting parent update rechecks the committed direct-location requirement'
);

select is(
  (
    select state.value
    from extensions.dblink(
      'event_setup',
      $state$
        select listing.status::text
          || '|' || (listing.address is not null and listing.lat is not null and listing.lng is not null)::text
          || '|' || (detail.venue_listing_id is null)::text
        from public.listings listing
        join public.event_details detail on detail.listing_id = listing.id
        where listing.id = 'ec100000-0000-4000-8000-000000000003'
      $state$
    ) as state(value text)
  ),
  'brouillon|true|true',
  'the concurrent outcome keeps a valid direct location after venue removal'
);

do $venue_setup$
begin
  perform extensions.dblink_exec(
    'event_setup',
    $setup$
      delete from public.listings
      where id = 'ec100000-0000-4000-8000-000000000003';

      delete from public.listings
      where id = 'ec100000-0000-4000-8000-000000000002';

      delete from public.listings
      where id in (
        'ec100000-0000-4000-8000-000000000004',
        'ec100000-0000-4000-8000-000000000005'
      );

      insert into public.listings (
        id,
        type,
        subtype,
        listing_class,
        category_id,
        status,
        name,
        slug,
        description,
        city_id,
        address,
        lat,
        lng,
        price_unit
      ) values
        (
          'ec100000-0000-4000-8000-000000000004',
          'lieu',
          'nature',
          'patrimonial',
          'heritage-nature',
          'publie',
          'Lieu concurrence typologie',
          'event-venue-type-concurrency',
          'Lieu temporaire utilisé pour vérifier la sérialisation de sa typologie.',
          'cotonou',
          'Cotonou',
          6.3703,
          2.3912,
          'aucune'
        ),
        (
          'ec100000-0000-4000-8000-000000000005',
          'evenement',
          'culture',
          'evenementiel',
          'event-culture',
          'brouillon',
          'Événement concurrence typologie lieu',
          'event-venue-type-reference-concurrency',
          'Événement temporaire utilisé pour vérifier la sérialisation du lieu référencé.',
          'cotonou',
          'Cotonou',
          6.3703,
          2.3912,
          'aucune'
        );
    $setup$
  );

  perform extensions.dblink_exec('event_submit', 'truncate event_transition_result');
  perform extensions.dblink_exec('event_delete', 'begin');
  perform extensions.dblink_exec(
    'event_delete',
    $insert_details$
      insert into public.event_details (
        listing_id,
        category,
        start_at,
        venue_listing_id,
        organizer_name,
        organizer_contact,
        ticket_type
      ) values (
        'ec100000-0000-4000-8000-000000000005',
        'culture',
        '2030-02-03 18:00:00+01',
        'ec100000-0000-4000-8000-000000000004',
        'Organisation concurrence typologie',
        'venue-concurrency@kwabor.test',
        'gratuit'
      )
    $insert_details$
  );

  if extensions.dblink_send_query(
    'event_submit',
    $convert_venue$
      do $remote$
      begin
        begin
          update public.listings
          set type = 'evenement',
              subtype = 'culture',
              listing_class = 'evenementiel',
              category_id = 'event-culture'
          where id = 'ec100000-0000-4000-8000-000000000004';

          insert into event_transition_result (sqlstate, message)
          values ('00000', 'unexpected success');
        exception
          when others then
            insert into event_transition_result (sqlstate, message)
            values (sqlstate, sqlerrm);
        end;
      end;
      $remote$
    $convert_venue$
  ) <> 1 then
    raise exception 'Unable to start the concurrent venue conversion';
  end if;
end;
$venue_setup$;

do $$
begin
  perform pg_sleep(0.25);
end;
$$;

select is(
  extensions.dblink_is_busy('event_submit'),
  1,
  'venue conversion waits while new event details hold the venue lock'
);

do $$
begin
  perform extensions.dblink_exec('event_delete', 'commit');
  perform result.status
  from extensions.dblink_get_result('event_submit', false) as result(status text);
  perform result.status
  from extensions.dblink_get_result('event_submit', false) as result(status text);
end;
$$;

select is(
  (
    select transition.sqlstate || '|' || transition.message
    from extensions.dblink(
      'event_submit',
      'select sqlstate, message from event_transition_result'
    ) as transition(sqlstate text, message text)
  ),
  '23514|A listing used as an event venue cannot become an event',
  'the waiting venue conversion rechecks references created concurrently'
);

select is(
  (
    select state.value
    from extensions.dblink(
      'event_setup',
      $state$
        select venue.type::text || '|' || count(detail.listing_id)::text
        from public.listings venue
        left join public.event_details detail on detail.venue_listing_id = venue.id
        where venue.id = 'ec100000-0000-4000-8000-000000000004'
        group by venue.type
      $state$
    ) as state(value text)
  ),
  'lieu|1',
  'the concurrent outcome keeps a valid venue reference'
);

do $$
begin
  perform extensions.dblink_exec(
    'event_setup',
    $cleanup$
      delete from public.listings
      where id = 'ec100000-0000-4000-8000-000000000005';

      delete from public.listings
      where id = 'ec100000-0000-4000-8000-000000000004';
    $cleanup$
  );
  perform extensions.dblink_disconnect('event_submit');
  perform extensions.dblink_disconnect('event_delete');
  perform extensions.dblink_disconnect('event_setup');
end;
$$;

select * from finish();
rollback;

revoke all privileges on table public.categories, public.listings, public.event_details
from kwabor_event_concurrency_test;
revoke usage on schema public from kwabor_event_concurrency_test;
drop role kwabor_event_concurrency_test;
