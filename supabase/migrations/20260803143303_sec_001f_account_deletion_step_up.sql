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
  'Cleans replayable application data and retains only a pseudonymized profile sentinel for safe retry routing.';

create or replace function app_private.profile_publication_allowed(
  p_user_id uuid
)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select not exists (
    select 1
    from public.account_deletion_requests as request
    where request.user_id = p_user_id
  );
$$;

revoke all
on function app_private.profile_publication_allowed(uuid)
from public;

grant execute
on function app_private.profile_publication_allowed(uuid)
to anon, authenticated;

drop policy "completed profiles are publicly readable"
on public.profiles;

create policy "completed profiles are publicly readable"
on public.profiles
for select
to anon, authenticated
using (
  (select auth.uid()) = user_id
  or (
    onboarding_completed_at is not null
    and app_private.profile_publication_allowed(user_id)
  )
);

create or replace function public.prepare_account_deletion(
  p_user_id uuid,
  p_idempotency_key uuid
)
returns table (
  status text,
  effective_idempotency_key uuid
)
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  existing_request public.account_deletion_requests%rowtype;
  resolved_idempotency_key uuid := p_idempotency_key;
  has_storage_objects boolean := false;
begin
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(p_user_id::text, 0)
  );

  select request.*
  into existing_request
  from public.account_deletion_requests as request
  where request.user_id = p_user_id
    and request.idempotency_key = p_idempotency_key
  for update;

  if found then
    resolved_idempotency_key := existing_request.idempotency_key;
  else
    select request.*
    into existing_request
    from public.account_deletion_requests as request
    where request.user_id = p_user_id
      and request.status = 'prepared'
    for update;

    if found then
      resolved_idempotency_key := existing_request.idempotency_key;
    end if;
  end if;

  if exists (
    select 1
    from public.organizations as organization
    where organization.primary_owner_id = p_user_id
  ) or exists (
    select 1
    from public.organization_members as member
    where member.user_id = p_user_id
      and member.role = 'proprietaire'
      and member.status in ('active', 'invited', 'suspended')
  ) then
    return query select 'ownership_conflict'::text, resolved_idempotency_key;
    return;
  end if;

  if to_regclass('storage.objects') is not null then
    execute
      'select exists (select 1 from storage.objects where owner_id::text = $1::text)'
      into has_storage_objects
      using p_user_id;
  end if;

  if has_storage_objects then
    return query select 'storage_conflict'::text, resolved_idempotency_key;
    return;
  end if;

  if existing_request.id is null then
    insert into public.account_deletion_requests (user_id, idempotency_key)
    values (p_user_id, p_idempotency_key)
    returning idempotency_key into resolved_idempotency_key;
  end if;

  -- Keep a pseudonymized completed-profile sentinel until Auth deletion
  -- succeeds. A process or network failure can then be recovered by signing in
  -- and proving a fresh step-up session without reopening onboarding.
  perform app_private.prepare_account_data_for_deletion(p_user_id);

  return query
  select
    case
      when existing_request.status = 'completed' then 'completed'::text
      else 'prepared'::text
    end,
    resolved_idempotency_key;
end;
$$;

revoke all
on function public.prepare_account_deletion(uuid, uuid)
from public, anon, authenticated;

grant execute
on function public.prepare_account_deletion(uuid, uuid)
to service_role;

create or replace function public.mark_account_deletion_completed(
  p_user_id uuid,
  p_idempotency_key uuid
)
returns table (status text)
language plpgsql
volatile
security definer
set search_path = ''
as $$
begin
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(p_user_id::text, 0)
  );

  if exists (
    select 1
    from auth.users as account
    where account.id = p_user_id
  ) then
    raise object_not_in_prerequisite_state
      using message = 'Auth user still present';
  end if;

  -- Auth deletion applies the declared FK actions first. This replayable pass
  -- removes any privileged residual rows before the tombstone becomes final.
  perform app_private.cleanup_account_data(p_user_id);

  update public.account_deletion_requests as request
  set status = 'completed',
      completed_at = coalesce(request.completed_at, now())
  where request.user_id = p_user_id
    and request.idempotency_key = p_idempotency_key
    and request.status = 'prepared';

  if not found and not exists (
    select 1
    from public.account_deletion_requests as request
    where request.user_id = p_user_id
      and request.idempotency_key = p_idempotency_key
      and request.status = 'completed'
  ) then
    raise invalid_parameter_value
      using message = 'Unknown account deletion request';
  end if;

  return query select 'completed'::text;
end;
$$;

revoke all
on function public.mark_account_deletion_completed(uuid, uuid)
from public, anon, authenticated;

grant execute
on function public.mark_account_deletion_completed(uuid, uuid)
to service_role;

comment on function public.mark_account_deletion_completed(uuid, uuid)
is
  'Completes application cleanup only after the Auth user is absent, then finalizes the deletion tombstone.';

create or replace function public.prepare_account_deletion_with_session(
  p_user_id uuid,
  p_session_id uuid,
  p_idempotency_key uuid
)
returns table (
  status text,
  effective_idempotency_key uuid
)
language plpgsql
volatile
security definer
set search_path = ''
as $$
begin
  if p_user_id is null or p_session_id is null then
    raise insufficient_privilege
      using message = 'Live authentication session required';
  end if;

  if p_idempotency_key is null then
    raise invalid_parameter_value
      using message = 'Invalid account deletion request';
  end if;

  perform 1
  from auth.sessions as auth_session
  where auth_session.id = p_session_id
    and auth_session.user_id = p_user_id
    and (
      auth_session.not_after is null
      or auth_session.not_after > pg_catalog.statement_timestamp()
    )
  for key share of auth_session;

  if not found then
    raise insufficient_privilege
      using message = 'Live authentication session required';
  end if;

  return query
  select
    preparation.status,
    preparation.effective_idempotency_key
  from public.prepare_account_deletion(
    p_user_id,
    p_idempotency_key
  ) as preparation;
end;
$$;

revoke all
on function public.prepare_account_deletion_with_session(uuid, uuid, uuid)
from public, anon, authenticated, service_role;

grant execute
on function public.prepare_account_deletion_with_session(uuid, uuid, uuid)
to service_role;

comment on function
  public.prepare_account_deletion_with_session(uuid, uuid, uuid)
is
  'Server-only account deletion preparation requiring a live Auth session locked through the first mutation.';
