begin;

set local lock_timeout = '5s';

create type public.notification_family_v1 as enum (
  'suggestion',
  'sponsored',
  'new_listing',
  'event_alert'
);

create or replace function app_private.notification_rfc3339_timestamp_valid_v1(
  p_value text
)
returns boolean
language plpgsql
immutable
strict
parallel safe
set search_path = ''
as $$
begin
  if p_value !~ '^[0-9]{4}-(0[1-9]|1[0-2])-([0-2][0-9]|3[01])T([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]([.][0-9]{1,6})?Z$'
  then
    return false;
  end if;

  begin
    perform p_value::timestamptz;
  exception
    when others then
      return false;
  end;

  return true;
end;
$$;

revoke all
on function app_private.notification_rfc3339_timestamp_valid_v1(text)
from public, anon, authenticated, service_role;

create or replace function app_private.notification_template_valid_v1(
  p_family public.notification_family_v1,
  p_title_key text,
  p_title_args jsonb,
  p_body_key text,
  p_body_args jsonb
)
returns boolean
language plpgsql
immutable
parallel safe
set search_path = ''
as $$
declare
  listing_name text;
  city_name text;
  event_start_at text;
begin
  if p_family is null
    or p_title_key is null
    or p_title_args is null
    or p_body_key is null
    or p_body_args is null
    or pg_catalog.jsonb_typeof(p_title_args) is distinct from 'object'
    or pg_catalog.jsonb_typeof(p_body_args) is distinct from 'object'
    or p_title_args <> '{}'::jsonb
  then
    return false;
  end if;

  listing_name := p_body_args ->> 'listing_name';
  city_name := p_body_args ->> 'city_name';
  event_start_at := p_body_args ->> 'event_start_at';

  if listing_name is null
    or pg_catalog.char_length(listing_name) not between 1 and 120
    or listing_name <> pg_catalog.btrim(listing_name)
    or listing_name ~ '[[:cntrl:]]'
    or pg_catalog.jsonb_typeof(p_body_args -> 'listing_name') is distinct from 'string'
  then
    return false;
  end if;

  case p_family
    when 'suggestion'::public.notification_family_v1 then
      return p_title_key = 'notification.suggestion.title'
        and p_body_key = 'notification.suggestion.body'
        and p_body_args - 'listing_name' = '{}'::jsonb;
    when 'sponsored'::public.notification_family_v1 then
      return p_title_key = 'notification.sponsored.title'
        and p_body_key = 'notification.sponsored.body'
        and p_body_args - 'listing_name' = '{}'::jsonb;
    when 'new_listing'::public.notification_family_v1 then
      return p_title_key = 'notification.new_listing.title'
        and p_body_key = 'notification.new_listing.body'
        and p_body_args - array['listing_name', 'city_name']::text[] = '{}'::jsonb
        and pg_catalog.jsonb_typeof(p_body_args -> 'city_name') = 'string'
        and city_name is not null
        and pg_catalog.char_length(city_name) between 1 and 120
        and city_name = pg_catalog.btrim(city_name)
        and city_name !~ '[[:cntrl:]]';
    when 'event_alert'::public.notification_family_v1 then
      return p_title_key = 'notification.event_alert.title'
        and p_body_key = 'notification.event_alert.body'
        and p_body_args - array['listing_name', 'event_start_at']::text[] = '{}'::jsonb
        and pg_catalog.jsonb_typeof(p_body_args -> 'event_start_at') = 'string'
        and event_start_at is not null
        and app_private.notification_rfc3339_timestamp_valid_v1(event_start_at);
  end case;
end;
$$;

revoke all
on function app_private.notification_template_valid_v1(
  public.notification_family_v1,
  text,
  jsonb,
  text,
  jsonb
)
from public, anon, authenticated, service_role;

alter table public.notifications
  drop constraint notifications_related_listing_id_fkey,
  add constraint notifications_related_listing_id_fkey
    foreign key (related_listing_id)
    references public.listings (id)
    on delete set null,
  add column family_v1 public.notification_family_v1,
  add column title_args jsonb not null default '{}'::jsonb,
  add column body_args jsonb not null default '{}'::jsonb,
  add column source_event_id uuid,
  add column inbox_sequence bigint,
  add column seen_at timestamptz,
  add column read_at timestamptz,
  add column hidden_at timestamptz,
  add constraint notifications_v1_shape check (
    (
      family_v1 is null
      and source_event_id is null
      and inbox_sequence is null
    )
    or (
      family_v1 is not null
      and source_event_id is not null
      and inbox_sequence > 0
      and sponsored = (family_v1 = 'sponsored'::public.notification_family_v1)
      and read = (read_at is not null)
    )
  ),
  add constraint notifications_v1_template check (
    family_v1 is null
    or app_private.notification_template_valid_v1(
      family_v1,
      title_key,
      title_args,
      body_key,
      body_args
    )
  ),
  add constraint notifications_v1_monotone_timestamps check (
    (seen_at is null or seen_at >= created_at)
    and (
      read_at is null
      or (
        seen_at is not null
        and read_at >= created_at
        and seen_at <= read_at
      )
    )
    and (
      hidden_at is null
      or (
        seen_at is not null
        and hidden_at >= created_at
        and seen_at <= hidden_at
      )
    )
  );

create unique index notifications_owner_sequence_v1_key
on public.notifications (user_id, inbox_sequence)
where family_v1 is not null;

create unique index notifications_owner_source_event_v1_key
on public.notifications (user_id, source_event_id)
where family_v1 is not null;

create unique index notifications_owner_id_v1_key
on public.notifications (user_id, id);

create index notifications_related_listing_id_idx
on public.notifications (related_listing_id)
where related_listing_id is not null;

create index notifications_owner_visible_sequence_v1_idx
on public.notifications (user_id, inbox_sequence desc)
include (
  family_v1,
  related_listing_id,
  sponsored,
  seen_at,
  read_at,
  created_at
)
where family_v1 is not null and hidden_at is null;

create table public.notification_inbox_states_v1 (
  user_id uuid primary key references auth.users (id) on delete cascade,
  latest_sequence bigint not null default 0,
  seen_through_sequence bigint not null default 0,
  seen_through_at timestamptz,
  updated_at timestamptz not null default pg_catalog.statement_timestamp(),
  constraint notification_inbox_states_v1_sequences_valid check (
    latest_sequence >= 0
    and seen_through_sequence >= 0
    and seen_through_sequence <= latest_sequence
  ),
  constraint notification_inbox_states_v1_seen_time_valid check (
    (seen_through_sequence = 0 and seen_through_at is null)
    or (seen_through_sequence > 0 and seen_through_at is not null)
  )
);

create table public.notification_preferences_v1 (
  user_id uuid not null references auth.users (id) on delete cascade,
  family public.notification_family_v1 not null,
  enabled boolean not null default false,
  updated_at timestamptz not null default pg_catalog.statement_timestamp(),
  primary key (user_id, family)
);

create table public.notification_enqueue_receipts_v1 (
  user_id uuid not null references auth.users (id) on delete cascade,
  source_event_id uuid not null,
  family public.notification_family_v1 not null,
  payload_fingerprint text not null,
  outcome text not null,
  notification_id uuid,
  sequence_number bigint,
  created_at timestamptz not null default pg_catalog.statement_timestamp(),
  primary key (user_id, source_event_id),
  constraint notification_enqueue_receipts_v1_outcome_valid check (
    (outcome = 'disabled' and notification_id is null and sequence_number is null)
    or (outcome = 'enqueued' and notification_id is not null and sequence_number > 0)
  ),
  constraint notification_enqueue_receipts_v1_notification_fkey
    foreign key (user_id, notification_id)
    references public.notifications (user_id, id)
    on delete cascade
);

create index notification_enqueue_receipts_notification_v1_idx
on public.notification_enqueue_receipts_v1 (user_id, notification_id)
where notification_id is not null;

alter table public.notifications force row level security;
alter table public.notification_inbox_states_v1 enable row level security;
alter table public.notification_inbox_states_v1 force row level security;
alter table public.notification_preferences_v1 enable row level security;
alter table public.notification_preferences_v1 force row level security;
alter table public.notification_enqueue_receipts_v1 enable row level security;
alter table public.notification_enqueue_receipts_v1 force row level security;

drop policy if exists "users read their notifications"
on public.notifications;

create policy "users read their legacy notifications"
on public.notifications
for select
to authenticated
using (
  family_v1 is null
  and (select auth.uid()) is not null
  and (select auth.uid()) = user_id
);

drop policy if exists "users mark their notifications read"
on public.notifications;

create policy "users mark their legacy notifications read"
on public.notifications
for update
to authenticated
using (
  family_v1 is null
  and (select app_private.current_user_has_completed_onboarding())
  and (select auth.uid()) = user_id
)
with check (
  family_v1 is null
  and (select app_private.current_user_has_completed_onboarding())
  and (select auth.uid()) = user_id
);

revoke all on table public.notifications
from public, anon, authenticated, service_role;
grant select on table public.notifications to authenticated;
grant update (read) on table public.notifications to authenticated;

revoke all on table public.notification_inbox_states_v1
from public, anon, authenticated, service_role;
revoke all on table public.notification_preferences_v1
from public, anon, authenticated, service_role;
revoke all on table public.notification_enqueue_receipts_v1
from public, anon, authenticated, service_role;

create or replace function app_private.require_notification_owner_v1(
  p_expected_account_id uuid
)
returns uuid
language plpgsql
stable
set search_path = ''
as $$
declare
  current_user_id uuid := (select auth.uid());
begin
  if current_user_id is null
    or p_expected_account_id is null
    or current_user_id is distinct from p_expected_account_id
  then
    raise insufficient_privilege
      using message = 'Authentication required';
  end if;

  return current_user_id;
end;
$$;

revoke all
on function app_private.require_notification_owner_v1(uuid)
from public, anon, authenticated, service_role;

create or replace function app_private.require_notification_account_active_v1(
  p_user_id uuid
)
returns void
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  if exists (
    select 1
    from public.account_deletion_requests as deletion_request
    where deletion_request.user_id = p_user_id
  ) then
    raise insufficient_privilege
      using message = 'Account deletion in progress';
  end if;
end;
$$;

revoke all
on function app_private.require_notification_account_active_v1(uuid)
from public, anon, authenticated, service_role;

create or replace function app_private.notification_inbox_status_v1(
  p_user_id uuid
)
returns table (
  latest_sequence bigint,
  seen_through_sequence bigint,
  unseen_count bigint,
  unread_count bigint
)
language sql
stable
security definer
set search_path = ''
as $$
  with inbox_state as (
    select
      coalesce(state.latest_sequence, 0::bigint) as latest_sequence,
      coalesce(state.seen_through_sequence, 0::bigint) as seen_through_sequence
    from (select 1) as singleton
    left join public.notification_inbox_states_v1 as state
      on state.user_id = p_user_id
  )
  select
    inbox_state.latest_sequence,
    inbox_state.seen_through_sequence,
    count(notification.id) filter (
      where notification.hidden_at is null
        and notification.seen_at is null
        and notification.inbox_sequence > inbox_state.seen_through_sequence
    )::bigint as unseen_count,
    count(notification.id) filter (
      where notification.hidden_at is null
        and notification.read_at is null
    )::bigint as unread_count
  from inbox_state
  left join public.notifications as notification
    on notification.user_id = p_user_id
    and notification.family_v1 is not null
  group by inbox_state.latest_sequence, inbox_state.seen_through_sequence;
$$;

revoke all
on function app_private.notification_inbox_status_v1(uuid)
from public, anon, authenticated, service_role;

create or replace function public.get_notification_inbox_status_v1(
  p_expected_account_id uuid
)
returns table (
  latest_sequence bigint,
  seen_through_sequence bigint,
  unseen_count bigint,
  unread_count bigint
)
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  current_user_id uuid :=
    app_private.require_notification_owner_v1(p_expected_account_id);
begin
  perform pg_catalog.pg_advisory_xact_lock_shared(
    pg_catalog.hashtextextended(current_user_id::text, 0)
  );
  perform app_private.require_notification_account_active_v1(current_user_id);
  perform app_private.require_completed_onboarding();

  return query
  select status.*
  from app_private.notification_inbox_status_v1(current_user_id) as status;
end;
$$;

create or replace function public.list_notification_inbox_v1(
  p_expected_account_id uuid,
  p_cursor text default null,
  p_limit integer default 20
)
returns table (
  notification_id uuid,
  sequence_number bigint,
  snapshot_sequence bigint,
  family public.notification_family_v1,
  title_key text,
  title_args jsonb,
  body_key text,
  body_args jsonb,
  target_available boolean,
  target_listing_id uuid,
  target_listing_type public.listing_type,
  target_listing_name text,
  target_city_id text,
  target_city_name text,
  target_cover_image_url text,
  target_cover_image_alt text,
  target_event_start_at timestamptz,
  sponsored boolean,
  seen_at timestamptz,
  read_at timestamptz,
  hidden_at timestamptz,
  created_at timestamptz,
  row_cursor text
)
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  current_user_id uuid :=
    app_private.require_notification_owner_v1(p_expected_account_id);
  v_latest_sequence bigint := 0;
  v_seen_through_sequence bigint := 0;
  v_seen_through_at timestamptz;
  v_snapshot_sequence bigint := 0;
  v_last_sequence bigint;
  v_fingerprint text;
  v_cursor_payload jsonb;
begin
  if p_limit is null or p_limit < 1 or p_limit > 50 then
    raise invalid_parameter_value
      using message = 'p_limit must be between 1 and 50';
  end if;

  v_fingerprint := pg_catalog.md5(
    pg_catalog.jsonb_build_object(
      'contract', 'notification-inbox-v1',
      'limit', p_limit,
      'user_id', current_user_id
    )::text
  );

  perform pg_catalog.pg_advisory_xact_lock_shared(
    pg_catalog.hashtextextended(current_user_id::text, 0)
  );
  perform app_private.require_notification_account_active_v1(current_user_id);
  perform app_private.require_completed_onboarding();

  select
    state.latest_sequence,
    state.seen_through_sequence,
    state.seen_through_at
  into
    v_latest_sequence,
    v_seen_through_sequence,
    v_seen_through_at
  from public.notification_inbox_states_v1 as state
  where state.user_id = current_user_id;

  if not found then
    v_latest_sequence := 0;
    v_seen_through_sequence := 0;
    v_seen_through_at := null;
  end if;

  v_snapshot_sequence := v_latest_sequence;

  if p_cursor is not null then
    if pg_catalog.btrim(p_cursor) = ''
      or pg_catalog.char_length(p_cursor) > 4096
      or p_cursor ~ '[[:space:]]'
    then
      raise invalid_parameter_value
        using message = 'p_cursor is invalid';
    end if;

    begin
      v_cursor_payload := pg_catalog.convert_from(
        pg_catalog.decode(p_cursor, 'base64'),
        'UTF8'
      )::jsonb;
    exception
      when others then
        raise invalid_parameter_value
          using message = 'p_cursor is malformed';
    end;

    if pg_catalog.jsonb_typeof(v_cursor_payload) is distinct from 'object'
      or v_cursor_payload - array[
        'v',
        'snapshot_sequence',
        'last_sequence',
        'fingerprint'
      ]::text[] <> '{}'::jsonb
      or pg_catalog.jsonb_typeof(v_cursor_payload -> 'v') is distinct from 'number'
      or v_cursor_payload ->> 'v' is distinct from '1'
      or pg_catalog.jsonb_typeof(v_cursor_payload -> 'snapshot_sequence')
        is distinct from 'number'
      or pg_catalog.jsonb_typeof(v_cursor_payload -> 'last_sequence')
        is distinct from 'number'
      or pg_catalog.jsonb_typeof(v_cursor_payload -> 'fingerprint')
        is distinct from 'string'
    then
      raise invalid_parameter_value
        using message = 'p_cursor fields are malformed';
    end if;

    if v_cursor_payload ->> 'fingerprint' is distinct from v_fingerprint then
      raise invalid_parameter_value
        using message = 'p_cursor does not match inbox owner or limit';
    end if;

    begin
      v_snapshot_sequence :=
        (v_cursor_payload ->> 'snapshot_sequence')::bigint;
      v_last_sequence := (v_cursor_payload ->> 'last_sequence')::bigint;
    exception
      when others then
        raise invalid_parameter_value
          using message = 'p_cursor fields are malformed';
    end;

    if v_snapshot_sequence <= 0
      or v_snapshot_sequence > v_latest_sequence
      or v_last_sequence <= 0
      or v_last_sequence > v_snapshot_sequence
    then
      raise invalid_parameter_value
        using message = 'p_cursor fields are invalid';
    end if;
  end if;

  return query
  select
    notification.id,
    notification.inbox_sequence,
    v_snapshot_sequence,
    notification.family_v1,
    notification.title_key,
    notification.title_args,
    notification.body_key,
    notification.body_args,
    target.id is not null,
    target.id,
    target.type,
    target.name,
    city.id,
    city.name,
    cover.url,
    cover.alt,
    event_detail.start_at,
    notification.sponsored,
    coalesce(
      notification.seen_at,
      case
        when notification.inbox_sequence <= v_seen_through_sequence
          then v_seen_through_at
      end
    ),
    notification.read_at,
    notification.hidden_at,
    notification.created_at,
    pg_catalog.replace(
      pg_catalog.replace(
        pg_catalog.encode(
          pg_catalog.convert_to(
            pg_catalog.jsonb_build_object(
              'v', 1,
              'snapshot_sequence', v_snapshot_sequence,
              'last_sequence', notification.inbox_sequence,
              'fingerprint', v_fingerprint
            )::text,
            'UTF8'
          ),
          'base64'
        ),
        pg_catalog.chr(10),
        ''
      ),
      pg_catalog.chr(13),
      ''
    )
  from public.notifications as notification
  left join public.listings as target
    on target.id = notification.related_listing_id
    and target.status = 'publie'::public.listing_status
    and target.published_at is not null
    and pg_catalog.char_length(target.name) between 1 and 120
    and target.name = pg_catalog.btrim(target.name)
    and target.name !~ '[[:cntrl:]]'
  left join lateral (
    select target_city.id, target_city.name
    from public.cities as target_city
    where target_city.id = target.city_id
      and pg_catalog.char_length(target_city.id) between 1 and 100
      and target_city.id = pg_catalog.btrim(target_city.id)
      and target_city.id !~ '[[:cntrl:]]'
      and pg_catalog.char_length(target_city.name) between 1 and 120
      and target_city.name = pg_catalog.btrim(target_city.name)
      and target_city.name !~ '[[:cntrl:]]'
  ) as city on true
  left join public.event_details as event_detail
    on event_detail.listing_id = target.id
  left join lateral (
    select media.url, media.alt
    from public.listing_media as media
    where media.listing_id = target.id
      and media.kind = 'image'::public.listing_media_kind
      and pg_catalog.char_length(media.alt) between 1 and 240
      and media.alt = pg_catalog.btrim(media.alt)
      and media.alt !~ '[[:cntrl:]]'
    order by media.is_cover desc, media.display_order asc, media.id asc
    limit 1
  ) as cover on true
  where notification.user_id = current_user_id
    and notification.family_v1 is not null
    and notification.hidden_at is null
    and notification.inbox_sequence <= v_snapshot_sequence
    and (v_last_sequence is null or notification.inbox_sequence < v_last_sequence)
  order by notification.inbox_sequence desc
  -- The extra row is a transport sentinel. Mobile retains `p_limit` rows and
  -- derives the next cursor from the last retained row only when it is present.
  limit (p_limit + 1);
end;
$$;

create or replace function public.mark_notification_inbox_seen_v1(
  p_expected_account_id uuid,
  p_seen_through_sequence bigint
)
returns table (
  latest_sequence bigint,
  seen_through_sequence bigint,
  unseen_count bigint,
  unread_count bigint
)
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  current_user_id uuid :=
    app_private.require_notification_owner_v1(p_expected_account_id);
  current_latest_sequence bigint;
  mutation_at timestamptz;
begin
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(current_user_id::text, 0)
  );
  perform app_private.require_notification_account_active_v1(current_user_id);
  perform app_private.require_completed_onboarding();

  select state.latest_sequence
  into current_latest_sequence
  from public.notification_inbox_states_v1 as state
  where state.user_id = current_user_id
  for update;

  if not found
    or p_seen_through_sequence is null
    or p_seen_through_sequence <= 0
    or p_seen_through_sequence > current_latest_sequence
  then
    raise invalid_parameter_value
      using message = 'Seen-through sequence is invalid';
  end if;

  mutation_at := pg_catalog.clock_timestamp();

  update public.notifications as notification
  set seen_at = coalesce(
        notification.seen_at,
        greatest(notification.created_at, mutation_at)
      )
  where notification.user_id = current_user_id
    and notification.family_v1 is not null
    and notification.inbox_sequence <= p_seen_through_sequence
    and notification.seen_at is null;

  update public.notification_inbox_states_v1 as state
  set seen_through_sequence = greatest(
        state.seen_through_sequence,
        p_seen_through_sequence
      ),
      seen_through_at = case
        when p_seen_through_sequence > state.seen_through_sequence
          then greatest(
            mutation_at,
            coalesce(state.seen_through_at, mutation_at)
          )
        else state.seen_through_at
      end,
      updated_at = case
        when p_seen_through_sequence > state.seen_through_sequence
          then mutation_at
        else state.updated_at
      end
  where state.user_id = current_user_id;

  return query
  select status.*
  from app_private.notification_inbox_status_v1(current_user_id) as status;
end;
$$;

create or replace function public.mark_all_notifications_read_v1(
  p_expected_account_id uuid,
  p_through_sequence bigint
)
returns table (
  latest_sequence bigint,
  seen_through_sequence bigint,
  unseen_count bigint,
  unread_count bigint,
  mutation_at timestamptz
)
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  current_user_id uuid :=
    app_private.require_notification_owner_v1(p_expected_account_id);
  current_latest_sequence bigint;
  confirmed_mutation_at timestamptz;
begin
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(current_user_id::text, 0)
  );
  perform app_private.require_notification_account_active_v1(current_user_id);
  perform app_private.require_completed_onboarding();

  select state.latest_sequence
  into current_latest_sequence
  from public.notification_inbox_states_v1 as state
  where state.user_id = current_user_id
  for update;

  if not found
    or p_through_sequence is null
    or p_through_sequence <= 0
    or p_through_sequence > current_latest_sequence
  then
    raise invalid_parameter_value
      using message = 'Read-through sequence is invalid';
  end if;

  confirmed_mutation_at := pg_catalog.clock_timestamp();

  update public.notifications as notification
  set seen_at = coalesce(
        notification.seen_at,
        greatest(notification.created_at, confirmed_mutation_at)
      ),
      read_at = coalesce(
        notification.read_at,
        greatest(
          notification.created_at,
          confirmed_mutation_at,
          coalesce(notification.seen_at, notification.created_at)
        )
      ),
      read = true
  where notification.user_id = current_user_id
    and notification.family_v1 is not null
    and notification.hidden_at is null
    and notification.inbox_sequence <= p_through_sequence
    and (notification.seen_at is null or notification.read_at is null);

  update public.notification_inbox_states_v1 as state
  set seen_through_sequence = greatest(
        state.seen_through_sequence,
        p_through_sequence
      ),
      seen_through_at = case
        when p_through_sequence > state.seen_through_sequence
          then greatest(
            confirmed_mutation_at,
            coalesce(state.seen_through_at, confirmed_mutation_at)
          )
        else state.seen_through_at
      end,
      updated_at = case
        when p_through_sequence > state.seen_through_sequence
          then confirmed_mutation_at
        else state.updated_at
      end
  where state.user_id = current_user_id;

  return query
  select
    status.latest_sequence,
    status.seen_through_sequence,
    status.unseen_count,
    status.unread_count,
    confirmed_mutation_at
  from app_private.notification_inbox_status_v1(current_user_id) as status;
end;
$$;

create or replace function public.mark_notification_read_v1(
  p_expected_account_id uuid,
  p_notification_id uuid
)
returns table (
  notification_id uuid,
  sequence_number bigint,
  seen_at timestamptz,
  read_at timestamptz,
  hidden_at timestamptz
)
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  current_user_id uuid :=
    app_private.require_notification_owner_v1(p_expected_account_id);
  mutation_at timestamptz;
begin
  if p_notification_id is null then
    raise invalid_parameter_value
      using message = 'Notification identifier is invalid';
  end if;

  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(current_user_id::text, 0)
  );
  perform app_private.require_notification_account_active_v1(current_user_id);
  perform app_private.require_completed_onboarding();

  mutation_at := pg_catalog.clock_timestamp();

  return query
  update public.notifications as notification
  set seen_at = coalesce(
        notification.seen_at,
        greatest(notification.created_at, mutation_at)
      ),
      read_at = coalesce(
        notification.read_at,
        greatest(
          notification.created_at,
          mutation_at,
          coalesce(notification.seen_at, notification.created_at)
        )
      ),
      read = true
  where notification.id = p_notification_id
    and notification.user_id = current_user_id
    and notification.family_v1 is not null
  returning
    notification.id,
    notification.inbox_sequence,
    notification.seen_at,
    notification.read_at,
    notification.hidden_at;

  if not found then
    raise no_data_found
      using message = 'notification not found';
  end if;
end;
$$;

create or replace function public.hide_notification_v1(
  p_expected_account_id uuid,
  p_notification_id uuid
)
returns table (
  notification_id uuid,
  sequence_number bigint,
  seen_at timestamptz,
  read_at timestamptz,
  hidden_at timestamptz
)
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  current_user_id uuid :=
    app_private.require_notification_owner_v1(p_expected_account_id);
  mutation_at timestamptz;
begin
  if p_notification_id is null then
    raise invalid_parameter_value
      using message = 'Notification identifier is invalid';
  end if;

  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(current_user_id::text, 0)
  );
  perform app_private.require_notification_account_active_v1(current_user_id);
  perform app_private.require_completed_onboarding();

  mutation_at := pg_catalog.clock_timestamp();

  return query
  update public.notifications as notification
  set seen_at = coalesce(
        notification.seen_at,
        greatest(notification.created_at, mutation_at)
      ),
      hidden_at = coalesce(
        notification.hidden_at,
        greatest(
          notification.created_at,
          mutation_at,
          coalesce(notification.seen_at, notification.created_at)
        )
      )
  where notification.id = p_notification_id
    and notification.user_id = current_user_id
    and notification.family_v1 is not null
  returning
    notification.id,
    notification.inbox_sequence,
    notification.seen_at,
    notification.read_at,
    notification.hidden_at;

  if not found then
    raise no_data_found
      using message = 'notification not found';
  end if;
end;
$$;

create or replace function public.list_notification_preferences_v1(
  p_expected_account_id uuid
)
returns table (
  family public.notification_family_v1,
  enabled boolean,
  updated_at timestamptz
)
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  current_user_id uuid :=
    app_private.require_notification_owner_v1(p_expected_account_id);
begin
  perform pg_catalog.pg_advisory_xact_lock_shared(
    pg_catalog.hashtextextended(current_user_id::text, 0)
  );
  perform app_private.require_notification_account_active_v1(current_user_id);
  perform app_private.require_completed_onboarding();

  return query
  select
    family_value.family,
    coalesce(preference.enabled, false),
    preference.updated_at
  from pg_catalog.unnest(
    pg_catalog.enum_range(null::public.notification_family_v1)
  ) as family_value(family)
  left join public.notification_preferences_v1 as preference
    on preference.user_id = current_user_id
    and preference.family = family_value.family
  order by family_value.family;
end;
$$;

create or replace function public.set_notification_preference_v1(
  p_expected_account_id uuid,
  p_family public.notification_family_v1,
  p_enabled boolean
)
returns table (
  family public.notification_family_v1,
  enabled boolean,
  updated_at timestamptz
)
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  current_user_id uuid :=
    app_private.require_notification_owner_v1(p_expected_account_id);
begin
  if p_family is null or p_enabled is null then
    raise invalid_parameter_value
      using message = 'Notification preference is invalid';
  end if;

  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(current_user_id::text, 0)
  );
  perform app_private.require_notification_account_active_v1(current_user_id);
  perform app_private.require_completed_onboarding();

  return query
  insert into public.notification_preferences_v1 as preference (
    user_id,
    family,
    enabled,
    updated_at
  ) values (
    current_user_id,
    p_family,
    p_enabled,
    pg_catalog.clock_timestamp()
  )
  on conflict (user_id, family) do update
  set enabled = excluded.enabled,
      updated_at = case
        when preference.enabled is distinct from excluded.enabled
          then excluded.updated_at
        else preference.updated_at
      end
  returning preference.family, preference.enabled, preference.updated_at;
end;
$$;

create or replace function public.enqueue_notification_v1(
  p_user_id uuid,
  p_source_event_id uuid,
  p_family public.notification_family_v1,
  p_title_key text,
  p_title_args jsonb,
  p_body_key text,
  p_body_args jsonb,
  p_related_listing_id uuid
)
returns table (
  notification_id uuid,
  sequence_number bigint,
  enqueued boolean
)
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  existing_receipt public.notification_enqueue_receipts_v1%rowtype;
  target_name text;
  target_city_name text;
  target_listing_type public.listing_type;
  target_event_start_at timestamptz;
  supplied_event_start_at timestamptz;
  payload_fingerprint text;
  next_sequence bigint;
  new_notification_id uuid;
begin
  if p_user_id is null
    or p_source_event_id is null
    or p_family is null
    or p_related_listing_id is null
    or not app_private.notification_template_valid_v1(
      p_family,
      p_title_key,
      p_title_args,
      p_body_key,
      p_body_args
    )
  then
    raise invalid_parameter_value
      using message = 'Notification payload is invalid';
  end if;

  payload_fingerprint := pg_catalog.md5(
    pg_catalog.jsonb_build_object(
      'contract', 'enqueue-notification-v1',
      'family', p_family,
      'title_key', p_title_key,
      'title_args', p_title_args,
      'body_key', p_body_key,
      'body_args', p_body_args,
      'related_listing_id', p_related_listing_id
    )::text
  );

  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(p_user_id::text, 0)
  );

  if not exists (
    select 1
    from auth.users as account
    join public.profiles as profile
      on profile.user_id = account.id
      and profile.onboarding_completed_at is not null
    where account.id = p_user_id
  ) or exists (
    select 1
    from public.account_deletion_requests as deletion_request
    where deletion_request.user_id = p_user_id
  ) then
    raise insufficient_privilege
      using message = 'Notification recipient is unavailable';
  end if;

  select receipt.*
  into existing_receipt
  from public.notification_enqueue_receipts_v1 as receipt
  where receipt.user_id = p_user_id
    and receipt.source_event_id = p_source_event_id;

  if found then
    if existing_receipt.payload_fingerprint is distinct from payload_fingerprint then
      raise invalid_parameter_value
        using message = 'Source event payload is inconsistent';
    end if;

    return query
    select
      existing_receipt.notification_id,
      existing_receipt.sequence_number,
      existing_receipt.outcome = 'enqueued';
    return;
  end if;

  -- Validate the current catalogue only for a new source event. A retry must
  -- keep returning its original receipt after a rename, depublish, or delete.
  select
    listing.name,
    city.name,
    listing.type,
    event_detail.start_at
  into
    target_name,
    target_city_name,
    target_listing_type,
    target_event_start_at
  from public.listings as listing
  join public.cities as city
    on city.id = listing.city_id
  left join public.event_details as event_detail
    on event_detail.listing_id = listing.id
  where listing.id = p_related_listing_id
    and listing.status = 'publie'::public.listing_status
    and listing.published_at is not null;

  if not found then
    raise no_data_found
      using message = 'listing not found';
  end if;

  if p_body_args ->> 'listing_name' is distinct from target_name
    or (
      p_family = 'new_listing'::public.notification_family_v1
      and p_body_args ->> 'city_name' is distinct from target_city_name
    )
  then
    raise invalid_parameter_value
      using message = 'Notification payload does not match listing';
  end if;

  if p_family = 'event_alert'::public.notification_family_v1 then
    if target_listing_type is distinct from 'evenement'::public.listing_type
      or target_event_start_at is null
    then
      raise invalid_parameter_value
        using message = 'Event notification target is invalid';
    end if;

    begin
      supplied_event_start_at :=
        (p_body_args ->> 'event_start_at')::timestamptz;
    exception
      when others then
        raise invalid_parameter_value
          using message = 'Event notification payload is invalid';
    end;

    if supplied_event_start_at is distinct from target_event_start_at then
      raise invalid_parameter_value
        using message = 'Event notification payload does not match listing';
    end if;
  end if;

  if not coalesce(
    (
      select preference.enabled
      from public.notification_preferences_v1 as preference
      where preference.user_id = p_user_id
        and preference.family = p_family
    ),
    false
  ) then
    insert into public.notification_enqueue_receipts_v1 (
      user_id,
      source_event_id,
      family,
      payload_fingerprint,
      outcome
    ) values (
      p_user_id,
      p_source_event_id,
      p_family,
      payload_fingerprint,
      'disabled'
    );

    return query select null::uuid, null::bigint, false;
    return;
  end if;

  insert into public.notification_inbox_states_v1 as state (
    user_id,
    latest_sequence,
    seen_through_sequence,
    updated_at
  ) values (
    p_user_id,
    1,
    0,
    pg_catalog.clock_timestamp()
  )
  on conflict (user_id) do update
  set latest_sequence = state.latest_sequence + 1,
      updated_at = pg_catalog.clock_timestamp()
  returning state.latest_sequence into next_sequence;

  insert into public.notifications (
    user_id,
    type,
    title_key,
    title_args,
    body_key,
    body_args,
    related_listing_id,
    sponsored,
    read,
    family_v1,
    source_event_id,
    inbox_sequence,
    created_at
  ) values (
    p_user_id,
    case p_family
      when 'suggestion'::public.notification_family_v1
        then 'listing'::public.notification_type
      when 'sponsored'::public.notification_family_v1
        then 'promotion'::public.notification_type
      when 'new_listing'::public.notification_family_v1
        then 'listing'::public.notification_type
      when 'event_alert'::public.notification_family_v1
        then 'listing'::public.notification_type
    end,
    p_title_key,
    p_title_args,
    p_body_key,
    p_body_args,
    p_related_listing_id,
    p_family = 'sponsored'::public.notification_family_v1,
    false,
    p_family,
    p_source_event_id,
    next_sequence,
    pg_catalog.clock_timestamp()
  )
  returning id into new_notification_id;

  insert into public.notification_enqueue_receipts_v1 (
    user_id,
    source_event_id,
    family,
    payload_fingerprint,
    outcome,
    notification_id,
    sequence_number
  ) values (
    p_user_id,
    p_source_event_id,
    p_family,
    payload_fingerprint,
    'enqueued',
    new_notification_id,
    next_sequence
  );

  return query select new_notification_id, next_sequence, true;
end;
$$;

create or replace function app_private.purge_notification_account_data_v1(
  p_user_id uuid
)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
begin
  delete from public.notifications
  where user_id = p_user_id;

  delete from public.notification_enqueue_receipts_v1
  where user_id = p_user_id;

  delete from public.notification_preferences_v1
  where user_id = p_user_id;

  delete from public.notification_inbox_states_v1
  where user_id = p_user_id;
end;
$$;

revoke all
on function app_private.purge_notification_account_data_v1(uuid)
from public, anon, authenticated, service_role;

create or replace function app_private.cleanup_account_data(p_user_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  account_email text;
  anonymized_email text :=
    'deleted+'
    || replace(extensions.gen_random_uuid()::text, '-', '')
    || '@kwabor.invalid';
begin
  select lower(account.email)
  into account_email
  from auth.users account
  where account.id = p_user_id;

  update public.organization_invites invite
  set email = anonymized_email,
      accepted_by = case when invite.accepted_by = p_user_id then null else invite.accepted_by end
  where invite.accepted_by = p_user_id
    or (account_email is not null and invite.email = account_email);

  update public.promoter_invites invite
  set email = anonymized_email,
      accepted_by = case when invite.accepted_by = p_user_id then null else invite.accepted_by end
  where invite.accepted_by = p_user_id
    or (account_email is not null and invite.email = account_email);

  delete from public.social_posts where author_id = p_user_id;
  delete from public.favorites where user_id = p_user_id;
  delete from public.likes where user_id = p_user_id;
  perform app_private.purge_notification_account_data_v1(p_user_id);
  delete from public.claims where claimant_id = p_user_id;
  delete from public.missing_place_reports where reporter_id = p_user_id;
  delete from public.organization_members where user_id = p_user_id;
  delete from public.user_legal_acceptances where user_id = p_user_id;
  delete from public.user_roles where user_id = p_user_id;
  perform app_private.purge_search_history_account_data(p_user_id);
  delete from public.profiles where user_id = p_user_id;

  update public.listings
  set owner_id = null
  where owner_id = p_user_id;

  update public.listings
  set steward_id = null
  where steward_id = p_user_id;

  update public.listings
  set submitted_by = null
  where submitted_by = p_user_id;
end;
$$;

revoke all on function app_private.cleanup_account_data(uuid)
from public, anon, authenticated, service_role;

create or replace function app_private.prepare_account_data_for_deletion(
  p_user_id uuid
)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  account_email text;
  anonymized_email text :=
    'deleted+'
    || replace(extensions.gen_random_uuid()::text, '-', '')
    || '@kwabor.invalid';
begin
  select lower(account.email)
  into account_email
  from auth.users as account
  where account.id = p_user_id;

  update public.organization_invites as invite
  set email = anonymized_email,
      accepted_by = case when invite.accepted_by = p_user_id then null else invite.accepted_by end
  where invite.accepted_by = p_user_id
    or (account_email is not null and invite.email = account_email);

  update public.promoter_invites as invite
  set email = anonymized_email,
      accepted_by = case when invite.accepted_by = p_user_id then null else invite.accepted_by end
  where invite.accepted_by = p_user_id
    or (account_email is not null and invite.email = account_email);

  delete from public.social_posts where author_id = p_user_id;
  delete from public.favorites where user_id = p_user_id;
  delete from public.likes where user_id = p_user_id;
  perform app_private.purge_notification_account_data_v1(p_user_id);
  delete from public.claims where claimant_id = p_user_id;
  delete from public.missing_place_reports where reporter_id = p_user_id;
  delete from public.organization_members where user_id = p_user_id;
  delete from public.user_legal_acceptances where user_id = p_user_id;
  delete from public.user_roles where user_id = p_user_id;
  perform app_private.purge_search_history_account_data(p_user_id);

  update public.listings
  set owner_id = null
  where owner_id = p_user_id;

  update public.listings
  set steward_id = null
  where steward_id = p_user_id;

  update public.listings
  set submitted_by = null
  where submitted_by = p_user_id;

  update public.profiles as profile
  set first_name = 'Compte',
      last_name = 'Suppression',
      avatar_url = null,
      cover_url = null,
      bio = null,
      city_id = null,
      preferred_locale = 'fr',
      preferred_currency = 'XOF',
      created_at = pg_catalog.statement_timestamp(),
      updated_at = pg_catalog.statement_timestamp(),
      onboarding_completed_at = pg_catalog.statement_timestamp()
  where profile.user_id = p_user_id;
end;
$$;

revoke all
on function app_private.prepare_account_data_for_deletion(uuid)
from public, anon, authenticated, service_role;

revoke all
on function public.get_notification_inbox_status_v1(uuid)
from public, anon, authenticated, service_role;
revoke all
on function public.list_notification_inbox_v1(uuid, text, integer)
from public, anon, authenticated, service_role;
revoke all
on function public.mark_notification_inbox_seen_v1(uuid, bigint)
from public, anon, authenticated, service_role;
revoke all
on function public.mark_all_notifications_read_v1(uuid, bigint)
from public, anon, authenticated, service_role;
revoke all
on function public.mark_notification_read_v1(uuid, uuid)
from public, anon, authenticated, service_role;
revoke all
on function public.hide_notification_v1(uuid, uuid)
from public, anon, authenticated, service_role;
revoke all
on function public.list_notification_preferences_v1(uuid)
from public, anon, authenticated, service_role;
revoke all
on function public.set_notification_preference_v1(
  uuid,
  public.notification_family_v1,
  boolean
)
from public, anon, authenticated, service_role;
revoke all
on function public.enqueue_notification_v1(
  uuid,
  uuid,
  public.notification_family_v1,
  text,
  jsonb,
  text,
  jsonb,
  uuid
)
from public, anon, authenticated, service_role;

grant execute
on function public.get_notification_inbox_status_v1(uuid)
to authenticated;
grant execute
on function public.list_notification_inbox_v1(uuid, text, integer)
to authenticated;
grant execute
on function public.mark_notification_inbox_seen_v1(uuid, bigint)
to authenticated;
grant execute
on function public.mark_all_notifications_read_v1(uuid, bigint)
to authenticated;
grant execute
on function public.mark_notification_read_v1(uuid, uuid)
to authenticated;
grant execute
on function public.hide_notification_v1(uuid, uuid)
to authenticated;
grant execute
on function public.list_notification_preferences_v1(uuid)
to authenticated;
grant execute
on function public.set_notification_preference_v1(
  uuid,
  public.notification_family_v1,
  boolean
)
to authenticated;
grant execute
on function public.enqueue_notification_v1(
  uuid,
  uuid,
  public.notification_family_v1,
  text,
  jsonb,
  text,
  jsonb,
  uuid
)
to service_role;

comment on table public.notification_inbox_states_v1 is
  'Per-account notification sequence authority and navbar seen-through watermark.';
comment on table public.notification_preferences_v1 is
  'Owner notification family opt-ins. An absent row and enabled=false both mean disabled.';
comment on table public.notification_enqueue_receipts_v1 is
  'Idempotency receipts for trusted notification enqueue attempts, including preference-disabled outcomes.';
comment on function public.list_notification_inbox_v1(uuid, text, integer) is
  'Returns one owner-fenced, sequence-snapshot page of visible V1 notifications. Cursors are bound to owner and limit.';
comment on function public.enqueue_notification_v1(
  uuid,
  uuid,
  public.notification_family_v1,
  text,
  jsonb,
  text,
  jsonb,
  uuid
) is
  'Trusted idempotent enqueue boundary. It validates published targets and exact localization templates; no producer is installed by NOTIF-001A.';

commit;
