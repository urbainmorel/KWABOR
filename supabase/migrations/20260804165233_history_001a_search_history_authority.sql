begin;

set local lock_timeout = '5s';

create table public.search_history_entries (
  id uuid primary key default extensions.gen_random_uuid(),
  user_id uuid not null references auth.users (id) on delete cascade,
  canonical_query text collate "C" not null,
  created_at timestamptz not null,
  last_submitted_at timestamptz not null,
  constraint search_history_entries_query_valid check (
    canonical_query = pg_catalog.btrim(canonical_query)
    and pg_catalog.char_length(canonical_query) between 1 and 120
    and canonical_query !~ '[[:cntrl:]]'
  ),
  constraint search_history_entries_timestamps_ordered check (
    created_at <= last_submitted_at
  ),
  constraint search_history_entries_user_query_unique unique (
    user_id,
    canonical_query
  )
);

create index search_history_entries_user_recent_idx
on public.search_history_entries (
  user_id,
  last_submitted_at desc,
  id desc
)
include (canonical_query, created_at);

create table public.search_history_preferences (
  user_id uuid primary key references auth.users (id) on delete cascade,
  activity_personalization_enabled boolean not null default false
);

comment on table public.search_history_entries is
  'Server authority for canonical keyword searches explicitly submitted by an authenticated account.';
comment on column public.search_history_entries.canonical_query is
  'Owner-visible submitted text. It must never be copied to analytics, crash reports, or application logs.';
comment on table public.search_history_preferences is
  'Search-derived personalization preference. Absence and the stored default both mean disabled.';

alter table public.search_history_entries enable row level security;
alter table public.search_history_entries force row level security;
alter table public.search_history_preferences enable row level security;
alter table public.search_history_preferences force row level security;

create policy "owners read their search history"
on public.search_history_entries
for select
to authenticated
using (
  (select auth.uid()) is not null
  and (select auth.uid()) = user_id
);

create policy "owners read their search history preference"
on public.search_history_preferences
for select
to authenticated
using (
  (select auth.uid()) is not null
  and (select auth.uid()) = user_id
);

revoke all on table public.search_history_entries
from public, anon, authenticated, service_role;
revoke all on table public.search_history_preferences
from public, anon, authenticated, service_role;

create or replace function app_private.search_history_canonicalize_v1(
  p_query text
)
returns text
language plpgsql
immutable
strict
parallel safe
set search_path = ''
as $$
declare
  canonical_query text := pg_catalog.btrim(p_query);
begin
  if pg_catalog.char_length(canonical_query) < 1
    or pg_catalog.char_length(canonical_query) > 120
    or p_query ~ '[[:cntrl:]]'
  then
    raise invalid_parameter_value
      using message = 'Submitted search query is invalid';
  end if;

  return canonical_query;
end;
$$;

comment on function app_private.search_history_canonicalize_v1(text) is
  'Validates and trims submitted keyword text without case-folding or logging its value.';

revoke all
on function app_private.search_history_canonicalize_v1(text)
from public, anon, authenticated, service_role;

create or replace function public.record_search_history_v1(
  p_query text
)
returns table (
  entry_id uuid,
  query_text text,
  created_at timestamptz,
  last_submitted_at timestamptz
)
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  current_user_id uuid := (select auth.uid());
  canonical_query text;
  recorded_at timestamptz := pg_catalog.statement_timestamp();
  recorded_entry public.search_history_entries%rowtype;
begin
  if current_user_id is null then
    raise insufficient_privilege
      using message = 'Authentication required';
  end if;

  if p_query is null then
    raise invalid_parameter_value
      using message = 'Submitted search query is invalid';
  end if;

  canonical_query := app_private.search_history_canonicalize_v1(p_query);

  -- This is the same account-scoped lock used by account deletion. It also
  -- serializes upsert plus eviction, making the 200-row cap deterministic
  -- under concurrent submissions from several devices.
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(current_user_id::text, 0)
  );

  if exists (
    select 1
    from public.account_deletion_requests as deletion_request
    where deletion_request.user_id = current_user_id
  ) then
    raise insufficient_privilege
      using message = 'Account deletion in progress';
  end if;

  insert into public.search_history_preferences (user_id)
  values (current_user_id)
  on conflict (user_id) do nothing;

  insert into public.search_history_entries (
    user_id,
    canonical_query,
    created_at,
    last_submitted_at
  )
  values (
    current_user_id,
    canonical_query,
    recorded_at,
    recorded_at
  )
  on conflict on constraint search_history_entries_user_query_unique
  do update
  set last_submitted_at = greatest(
    public.search_history_entries.last_submitted_at,
    excluded.last_submitted_at
  )
  returning public.search_history_entries.* into recorded_entry;

  delete from public.search_history_entries as history_entry
  using (
    select candidate.id
    from public.search_history_entries as candidate
    where candidate.user_id = current_user_id
    order by
      candidate.last_submitted_at desc,
      candidate.created_at desc,
      candidate.id desc
    offset 200
  ) as evicted
  where history_entry.id = evicted.id
    and history_entry.user_id = current_user_id;

  return query
  select
    recorded_entry.id,
    recorded_entry.canonical_query,
    recorded_entry.created_at,
    recorded_entry.last_submitted_at;
end;
$$;

comment on function public.record_search_history_v1(text) is
  'Records one authenticated submitted query, preserves its identity and creation time on resubmission, and enforces the 200-row account cap.';

create or replace function public.list_search_history_v1()
returns table (
  entry_id uuid,
  query_text text,
  created_at timestamptz,
  last_submitted_at timestamptz
)
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  current_user_id uuid := (select auth.uid());
begin
  if current_user_id is null then
    raise insufficient_privilege
      using message = 'Authentication required';
  end if;

  perform pg_catalog.pg_advisory_xact_lock_shared(
    pg_catalog.hashtextextended(current_user_id::text, 0)
  );

  if exists (
    select 1
    from public.account_deletion_requests as deletion_request
    where deletion_request.user_id = current_user_id
  ) then
    raise insufficient_privilege
      using message = 'Account deletion in progress';
  end if;

  return query
  select
    history_entry.id,
    history_entry.canonical_query,
    history_entry.created_at,
    history_entry.last_submitted_at
  from public.search_history_entries as history_entry
  where history_entry.user_id = current_user_id
  order by
    history_entry.last_submitted_at desc,
    history_entry.created_at desc,
    history_entry.id desc
  limit 200;
end;
$$;

comment on function public.list_search_history_v1() is
  'Returns the complete bounded owner snapshot newest first; it is not an incremental synchronization feed.';

create or replace function public.delete_search_history_entry_v1(
  p_entry_id uuid
)
returns boolean
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  current_user_id uuid := (select auth.uid());
begin
  if current_user_id is null then
    raise insufficient_privilege
      using message = 'Authentication required';
  end if;

  if p_entry_id is null then
    raise invalid_parameter_value
      using message = 'Search history entry identifier is invalid';
  end if;

  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(current_user_id::text, 0)
  );

  if exists (
    select 1
    from public.account_deletion_requests as deletion_request
    where deletion_request.user_id = current_user_id
  ) then
    raise insufficient_privilege
      using message = 'Account deletion in progress';
  end if;

  delete from public.search_history_entries as history_entry
  where history_entry.user_id = current_user_id
    and history_entry.id = p_entry_id;

  return found;
end;
$$;

comment on function public.delete_search_history_entry_v1(uuid) is
  'Physically deletes one owner entry without revealing whether the identifier belongs to another account.';

create or replace function public.clear_search_history_v1()
returns integer
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  current_user_id uuid := (select auth.uid());
  deleted_count integer;
begin
  if current_user_id is null then
    raise insufficient_privilege
      using message = 'Authentication required';
  end if;

  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(current_user_id::text, 0)
  );

  if exists (
    select 1
    from public.account_deletion_requests as deletion_request
    where deletion_request.user_id = current_user_id
  ) then
    raise insufficient_privilege
      using message = 'Account deletion in progress';
  end if;

  delete from public.search_history_entries as history_entry
  where history_entry.user_id = current_user_id;

  get diagnostics deleted_count = row_count;
  return deleted_count;
end;
$$;

comment on function public.clear_search_history_v1() is
  'Physically clears the owner history while preserving the separate personalization preference.';

revoke all on function public.record_search_history_v1(text)
from public, anon, authenticated, service_role;
revoke all on function public.list_search_history_v1()
from public, anon, authenticated, service_role;
revoke all on function public.delete_search_history_entry_v1(uuid)
from public, anon, authenticated, service_role;
revoke all on function public.clear_search_history_v1()
from public, anon, authenticated, service_role;

grant execute on function public.record_search_history_v1(text)
to authenticated;
grant execute on function public.list_search_history_v1()
to authenticated;
grant execute on function public.delete_search_history_entry_v1(uuid)
to authenticated;
grant execute on function public.clear_search_history_v1()
to authenticated;

create or replace function app_private.purge_search_history_account_data(
  p_user_id uuid
)
returns void
language plpgsql
volatile
security invoker
set search_path = ''
as $$
begin
  delete from public.search_history_entries as history_entry
  where history_entry.user_id = p_user_id;

  delete from public.search_history_preferences as history_preference
  where history_preference.user_id = p_user_id;
end;
$$;

revoke all
on function app_private.purge_search_history_account_data(uuid)
from public, anon, authenticated, service_role;

comment on function app_private.purge_search_history_account_data(uuid) is
  'Internal replayable purge used by both account-deletion phases; Auth deletion also cascades as a final invariant.';

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
  delete from public.notifications where user_id = p_user_id;
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
  delete from public.notifications where user_id = p_user_id;
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

  -- Keep only the non-PII sentinel required by mobile session routing. The
  -- owner can read it, but the tombstone hides it publicly and blocks updates.
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

comment on function app_private.prepare_account_data_for_deletion(uuid)
is
  'Cleans replayable application data including search history and retains only a pseudonymized profile sentinel for safe retry routing.';

reset lock_timeout;

commit;
