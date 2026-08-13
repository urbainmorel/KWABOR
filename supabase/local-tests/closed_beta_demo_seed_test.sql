do $guard$
begin
  if current_setting('kwabor.local_demo_catalog_harness', true)
    is distinct from 'explicit-local-wrapper'
  then
    raise exception 'Closed-beta demo seed proof is destructive and local-wrapper only';
  end if;
end;
$guard$;

create temporary table closed_beta_demo_state (
  label text not null,
  relation_name text not null,
  row_key text not null,
  row_value jsonb not null,
  primary key (label, relation_name, row_key)
) on commit preserve rows;

create temporary table closed_beta_outside_state (
  label text not null,
  relation_name text not null,
  row_key text not null,
  row_value jsonb not null,
  primary key (label, relation_name, row_key)
) on commit preserve rows;

create or replace function pg_temp.capture_closed_beta_outside_state(p_label text)
returns void
language plpgsql
set search_path = ''
as $$
begin
  delete from pg_temp.closed_beta_outside_state where label = p_label;

  insert into pg_temp.closed_beta_outside_state (label, relation_name, row_key, row_value)
  select p_label, 'listings', listing.id::text, to_jsonb(listing)
  from public.listings listing
  where coalesce(not ('demo-kwabor' = any(listing.tags)), true);

  insert into pg_temp.closed_beta_outside_state (label, relation_name, row_key, row_value)
  select p_label, 'favorites', favorite.user_id::text || ':' || favorite.listing_id::text, to_jsonb(favorite)
  from public.favorites favorite;

  insert into pg_temp.closed_beta_outside_state (label, relation_name, row_key, row_value)
  select p_label, 'likes', like_row.user_id::text || ':' || like_row.listing_id::text, to_jsonb(like_row)
  from public.likes like_row;

  insert into pg_temp.closed_beta_outside_state (label, relation_name, row_key, row_value)
  select p_label, 'claims', claim.id::text, to_jsonb(claim)
  from public.claims claim;

  insert into pg_temp.closed_beta_outside_state (label, relation_name, row_key, row_value)
  select p_label, 'campaigns', campaign.id::text, to_jsonb(campaign)
  from public.campaigns campaign;

  insert into pg_temp.closed_beta_outside_state (label, relation_name, row_key, row_value)
  select p_label, 'notifications', notification.id::text, to_jsonb(notification)
  from public.notifications notification;

  insert into pg_temp.closed_beta_outside_state (label, relation_name, row_key, row_value)
  select p_label, 'social_posts', post.id::text, to_jsonb(post)
  from public.social_posts post;

  insert into pg_temp.closed_beta_outside_state (label, relation_name, row_key, row_value)
  select p_label, 'promoter_invites', invite.id::text, to_jsonb(invite)
  from public.promoter_invites invite;
end;
$$;

create or replace function pg_temp.assert_closed_beta_outside_state_unchanged(p_label text)
returns void
language plpgsql
set search_path = ''
as $$
declare
  expected jsonb;
  actual jsonb;
begin
  select coalesce(jsonb_object_agg(
    state.relation_name || ':' || state.row_key,
    state.row_value
  ), '{}'::jsonb)
  into expected
  from pg_temp.closed_beta_outside_state state
  where state.label = p_label;

  perform pg_temp.capture_closed_beta_outside_state('__outside_after__');
  select coalesce(jsonb_object_agg(
    state.relation_name || ':' || state.row_key,
    state.row_value
  ), '{}'::jsonb)
  into actual
  from pg_temp.closed_beta_outside_state state
  where state.label = '__outside_after__';

  if actual is distinct from expected then
    raise exception 'Demo seed or rollback changed data outside the demo corpus';
  end if;
end;
$$;

create or replace function pg_temp.seed_closed_beta_user_relations()
returns void
language plpgsql
set search_path = ''
as $$
begin
  insert into auth.users (
    id,
    aud,
    role,
    email,
    encrypted_password,
    email_confirmed_at,
    created_at,
    updated_at
  ) values (
    'cb000000-0000-4000-8000-000000000001'::uuid,
    'authenticated',
    'authenticated',
    'closed-beta-seed-proof@kwabor.test',
    '',
    statement_timestamp(),
    statement_timestamp(),
    statement_timestamp()
  ) on conflict (id) do nothing;

  insert into public.favorites (user_id, listing_id)
  values (
    'cb000000-0000-4000-8000-000000000001'::uuid,
    '00000000-0000-4000-8000-000000000201'::uuid
  ) on conflict do nothing;

  insert into public.likes (user_id, listing_id)
  values (
    'cb000000-0000-4000-8000-000000000001'::uuid,
    '00000000-0000-4000-8000-000000000401'::uuid
  ) on conflict do nothing;
end;
$$;

create or replace function pg_temp.demo_summary_count_as(
  p_role text,
  p_user_id uuid,
  p_listing_type text
)
returns bigint
language plpgsql
set search_path = ''
as $$
declare
  result bigint;
begin
  execute format('set local role %I', p_role);
  perform set_config('request.jwt.claim.role', p_role, true);
  perform set_config('request.jwt.claim.sub', coalesce(p_user_id::text, ''), true);
  execute format(
    $query$
      select count(*)
      from public.list_catalog_summaries_v2(%L, p_limit => 50) summary
      join public.listings listing on listing.id = summary.id
      where 'demo-kwabor' = any(listing.tags)
    $query$,
    p_listing_type
  ) into result;
  reset role;
  return result;
exception
  when others then
    reset role;
    raise;
end;
$$;

create or replace function pg_temp.demo_detail_count_as(
  p_role text,
  p_user_id uuid,
  p_listing_id uuid
)
returns bigint
language plpgsql
set search_path = ''
as $$
declare
  result bigint;
begin
  execute format('set local role %I', p_role);
  perform set_config('request.jwt.claim.role', p_role, true);
  perform set_config('request.jwt.claim.sub', coalesce(p_user_id::text, ''), true);
  select count(*) into result
  from public.get_catalog_detail_v1(p_listing_id);
  reset role;
  return result;
exception
  when others then
    reset role;
    raise;
end;
$$;

create or replace function pg_temp.assert_closed_beta_rpc_surface()
returns void
language plpgsql
set search_path = ''
as $$
declare
  db_role text;
  db_user_id uuid;
  place_count bigint;
  event_count bigint;
  establishment_count bigint;
  detail_count bigint;
begin
  for db_role, db_user_id in
    values
      ('anon'::text, null::uuid),
      ('authenticated'::text, 'cb000000-0000-4000-8000-000000000001'::uuid)
  loop
    place_count := pg_temp.demo_summary_count_as(db_role, db_user_id, 'lieu');
    event_count := pg_temp.demo_summary_count_as(db_role, db_user_id, 'evenement');
    establishment_count := pg_temp.demo_summary_count_as(db_role, db_user_id, 'etablissement');
    detail_count := pg_temp.demo_detail_count_as(
      db_role,
      db_user_id,
      '00000000-0000-4000-8000-000000000201'::uuid
    );
    if place_count <> 15 or event_count <> 15 or establishment_count <> 30 then
      raise exception
        'Demo Explore RPC mismatch for role %: places %, events %, establishments %',
        db_role,
        place_count,
        event_count,
        establishment_count;
    end if;
    if detail_count <> 1 then
      raise exception 'Demo detail RPC mismatch for role %: % rows', db_role, detail_count;
    end if;
  end loop;
end;
$$;

create or replace function pg_temp.capture_closed_beta_demo_state(p_label text)
returns void
language plpgsql
set search_path = ''
as $$
begin
  delete from pg_temp.closed_beta_demo_state where label = p_label;

  insert into pg_temp.closed_beta_demo_state (label, relation_name, row_key, row_value)
  select p_label, 'listings', listing.id::text, to_jsonb(listing)
  from public.listings listing
  where 'demo-kwabor' = any(listing.tags);

  insert into pg_temp.closed_beta_demo_state (label, relation_name, row_key, row_value)
  select p_label, 'listing_media', media.id::text, to_jsonb(media)
  from public.listing_media media
  join public.listings listing on listing.id = media.listing_id
  where 'demo-kwabor' = any(listing.tags);

  insert into pg_temp.closed_beta_demo_state (label, relation_name, row_key, row_value)
  select p_label, 'place_details', detail.listing_id::text, to_jsonb(detail)
  from public.place_details detail
  join public.listings listing on listing.id = detail.listing_id
  where 'demo-kwabor' = any(listing.tags);

  insert into pg_temp.closed_beta_demo_state (label, relation_name, row_key, row_value)
  select p_label, 'lodging_details', detail.listing_id::text, to_jsonb(detail)
  from public.lodging_details detail
  join public.listings listing on listing.id = detail.listing_id
  where 'demo-kwabor' = any(listing.tags);

  insert into pg_temp.closed_beta_demo_state (label, relation_name, row_key, row_value)
  select p_label, 'food_details', detail.listing_id::text, to_jsonb(detail)
  from public.food_details detail
  join public.listings listing on listing.id = detail.listing_id
  where 'demo-kwabor' = any(listing.tags);

  insert into pg_temp.closed_beta_demo_state (label, relation_name, row_key, row_value)
  select p_label, 'event_details', detail.listing_id::text, to_jsonb(detail)
  from public.event_details detail
  join public.listings listing on listing.id = detail.listing_id
  where 'demo-kwabor' = any(listing.tags);

  insert into pg_temp.closed_beta_demo_state (label, relation_name, row_key, row_value)
  select p_label, 'room_types', room.id::text, to_jsonb(room)
  from public.room_types room
  join public.listings listing on listing.id = room.listing_id
  where 'demo-kwabor' = any(listing.tags);

  insert into pg_temp.closed_beta_demo_state (label, relation_name, row_key, row_value)
  select p_label, 'ticket_tiers', tier.id::text, to_jsonb(tier)
  from public.ticket_tiers tier
  join public.listings listing on listing.id = tier.listing_id
  where 'demo-kwabor' = any(listing.tags);

  insert into pg_temp.closed_beta_demo_state (label, relation_name, row_key, row_value)
  select
    p_label,
    'listing_amenities',
    link.listing_id::text || ':' || link.amenity_id,
    to_jsonb(link)
  from public.listing_amenities link
  join public.listings listing on listing.id = link.listing_id
  where 'demo-kwabor' = any(listing.tags);
end;
$$;

create or replace function pg_temp.assert_closed_beta_demo_state_unchanged(p_label text)
returns void
language plpgsql
set search_path = ''
as $$
declare
  expected jsonb;
  actual jsonb;
begin
  select coalesce(jsonb_object_agg(
    state.relation_name || ':' || state.row_key,
    state.row_value
  ), '{}'::jsonb)
  into expected
  from pg_temp.closed_beta_demo_state state
  where state.label = p_label;

  perform pg_temp.capture_closed_beta_demo_state('__second__');
  select coalesce(jsonb_object_agg(
    state.relation_name || ':' || state.row_key,
    state.row_value
  ), '{}'::jsonb)
  into actual
  from pg_temp.closed_beta_demo_state state
  where state.label = '__second__';

  if actual is distinct from expected then
    raise exception 'Second demo import changed durable rows or timestamps';
  end if;
end;
$$;

create or replace function pg_temp.assert_closed_beta_demo_logically_rolled_back()
returns void
language plpgsql
set search_path = ''
as $$
declare
  visible_count integer;
  parent_count integer;
  media_count integer;
  expected_children jsonb;
  actual_children jsonb;
  expected_parents jsonb;
  actual_parents jsonb;
begin
  select count(*) into parent_count
  from public.listings listing
  where 'demo-kwabor' = any(listing.tags);
  if parent_count <> 60 then
    raise exception 'Logical rollback deleted demo parents: %/60 remain', parent_count;
  end if;

  select count(*) into visible_count
  from public.listings listing
  where 'demo-kwabor' = any(listing.tags)
    and listing.status = 'publie';
  if visible_count <> 0 then
    raise exception 'Logical rollback left % demo listings published', visible_count;
  end if;

  select count(*) into media_count
  from public.listing_media media
  join public.listings listing on listing.id = media.listing_id
  where 'demo-kwabor' = any(listing.tags);
  if media_count <> 180 then
    raise exception 'Logical rollback deleted demo media rows: %/180 remain', media_count;
  end if;

  perform pg_temp.capture_closed_beta_demo_state('__rollback__');
  select coalesce(jsonb_object_agg(
    state.relation_name || ':' || state.row_key,
    state.row_value
  ), '{}'::jsonb)
  into expected_children
  from pg_temp.closed_beta_demo_state state
  where state.label = 'first'
    and state.relation_name <> 'listings';
  select coalesce(jsonb_object_agg(
    state.relation_name || ':' || state.row_key,
    state.row_value
  ), '{}'::jsonb)
  into actual_children
  from pg_temp.closed_beta_demo_state state
  where state.label = '__rollback__'
    and state.relation_name <> 'listings';
  if actual_children is distinct from expected_children then
    raise exception 'Logical rollback changed or deleted typed demo children';
  end if;

  select coalesce(jsonb_object_agg(
    state.row_key,
    ((state.row_value - 'status') - 'published_at') - 'updated_at'
  ), '{}'::jsonb)
  into expected_parents
  from pg_temp.closed_beta_demo_state state
  where state.label = 'first'
    and state.relation_name = 'listings';
  select coalesce(jsonb_object_agg(
    state.row_key,
    ((state.row_value - 'status') - 'published_at') - 'updated_at'
  ), '{}'::jsonb)
  into actual_parents
  from pg_temp.closed_beta_demo_state state
  where state.label = '__rollback__'
    and state.relation_name = 'listings';
  if actual_parents is distinct from expected_parents then
    raise exception 'Logical rollback changed demo parent data beyond archival fields';
  end if;

  if not exists (
    select 1
    from public.favorites favorite
    where favorite.user_id = 'cb000000-0000-4000-8000-000000000001'::uuid
      and favorite.listing_id = '00000000-0000-4000-8000-000000000201'::uuid
  ) or not exists (
    select 1
    from public.likes like_row
    where like_row.user_id = 'cb000000-0000-4000-8000-000000000001'::uuid
      and like_row.listing_id = '00000000-0000-4000-8000-000000000401'::uuid
  ) then
    raise exception 'Logical rollback deleted a user relation targeting the demo corpus';
  end if;
end;
$$;
