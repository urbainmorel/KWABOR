-- ADR-0040: onboarding records only the current terms and privacy policy.
-- Accept the optional legacy RPC argument during app upgrades. It has no effect.
create or replace function app_private.complete_user_onboarding_internal(
  p_first_name text,
  p_last_name text,
  p_city_id text,
  p_preferred_locale text,
  p_preferred_currency text,
  p_terms_document_id uuid,
  p_privacy_document_id uuid,
  p_ugc_document_id uuid
)
returns public.profiles
language plpgsql
security invoker
set search_path = ''
as $$
declare
  current_user_id uuid := (select auth.uid());
  completed_profile public.profiles%rowtype;
  normalized_first_name text := trim(p_first_name);
  normalized_last_name text := trim(p_last_name);
  selected_document_count integer;
begin
  if current_user_id is null then
    raise exception 'Authentication required'
      using errcode = '42501';
  end if;

  select profile.*
  into completed_profile
  from public.profiles profile
  where profile.user_id = current_user_id
    and profile.onboarding_completed_at is not null;

  if found then
    return completed_profile;
  end if;

  if not exists (
    select 1
    from auth.users account
    where account.id = current_user_id
      and (
        length(coalesce(account.encrypted_password, '')) > 0
        or exists (
          select 1
          from auth.identities identity
          where identity.user_id = account.id
            and identity.provider in ('apple', 'google')
        )
      )
  ) then
    raise exception 'Initial password or supported federated identity is required'
      using errcode = '22023';
  end if;

  if length(normalized_first_name) not between 1 and 80
    or length(normalized_last_name) not between 1 and 80
  then
    raise exception 'Invalid profile identity'
      using errcode = '22023';
  end if;

  if p_preferred_locale not in ('fr', 'en', 'pt', 'de', 'es', 'it') then
    raise exception 'Unsupported preferred locale'
      using errcode = '22023';
  end if;

  if p_preferred_currency not in ('XOF', 'NGN', 'USD', 'EUR') then
    raise exception 'Unsupported preferred currency'
      using errcode = '22023';
  end if;

  if not exists (
    select 1
    from public.cities city
    where city.id = p_city_id
      and city.country_code = 'BJ'
      and city.enabled
  ) then
    raise exception 'Invalid or unavailable Benin city'
      using errcode = '22023';
  end if;

  select count(*)::integer
  into selected_document_count
  from public.legal_documents document
  where document.id in (
    p_terms_document_id,
    p_privacy_document_id
  )
    and document.locale = p_preferred_locale
    and document.active
    and document.effective_at <= now()
    and (
      (document.id = p_terms_document_id and document.document_type = 'terms')
      or (
        document.id = p_privacy_document_id
        and document.document_type = 'privacy_policy'
      )
    );

  if selected_document_count <> 2 then
    raise exception 'Required legal document revisions are invalid'
      using errcode = '22023';
  end if;

  insert into public.profiles (
    user_id,
    first_name,
    last_name,
    city_id,
    preferred_locale,
    preferred_currency
  )
  values (
    current_user_id,
    normalized_first_name,
    normalized_last_name,
    p_city_id,
    p_preferred_locale,
    p_preferred_currency
  )
  on conflict (user_id)
  do update
  set
    first_name = excluded.first_name,
    last_name = excluded.last_name,
    city_id = excluded.city_id,
    preferred_locale = excluded.preferred_locale,
    preferred_currency = excluded.preferred_currency,
    updated_at = now()
  where public.profiles.onboarding_completed_at is null
  returning * into completed_profile;

  if not found then
    select profile.*
    into completed_profile
    from public.profiles profile
    where profile.user_id = current_user_id
      and profile.onboarding_completed_at is not null;

    if found then
      return completed_profile;
    end if;

    raise exception 'Profile cannot be completed'
      using errcode = '40001';
  end if;

  insert into public.user_roles (
    user_id,
    role,
    verification_status
  )
  values (
    current_user_id,
    'user',
    'unverified'
  )
  on conflict (user_id, role) do nothing;

  insert into public.user_legal_acceptances (
    user_id,
    legal_document_id
  )
  values
    (current_user_id, p_terms_document_id),
    (current_user_id, p_privacy_document_id)
  on conflict (user_id, legal_document_id) do nothing;

  update public.profiles profile
  set
    onboarding_completed_at = now(),
    updated_at = now()
  where profile.user_id = current_user_id
    and profile.onboarding_completed_at is null
  returning profile.* into completed_profile;

  if found then
    return completed_profile;
  end if;

  select profile.*
  into completed_profile
  from public.profiles profile
  where profile.user_id = current_user_id
    and profile.onboarding_completed_at is not null;

  if found then
    return completed_profile;
  end if;

  raise exception 'Profile completion was not persisted'
    using errcode = '40001';
end;
$$;

revoke all on function app_private.complete_user_onboarding_internal(
  text,
  text,
  text,
  text,
  text,
  uuid,
  uuid,
  uuid
) from public, anon, authenticated;

create or replace function public.complete_user_onboarding(
  p_first_name text,
  p_last_name text,
  p_city_id text,
  p_preferred_locale text,
  p_preferred_currency text,
  p_terms_document_id uuid,
  p_privacy_document_id uuid,
  p_ugc_document_id uuid default null
)
returns setof public.profiles
language plpgsql
security definer
set search_path = ''
as $$
declare
  current_user_id uuid := (select auth.uid());
begin
  if current_user_id is null then
    raise insufficient_privilege using message = 'Authentication required';
  end if;

  -- Keep onboarding finalization and account deletion mutually ordered for the
  -- same user, including the idempotent already-completed onboarding path.
  perform pg_catalog.pg_advisory_xact_lock_shared(
    pg_catalog.hashtextextended(current_user_id::text, 0)
  );

  if exists (
    select 1
    from public.account_deletion_requests request
    where request.user_id = current_user_id
  ) then
    raise insufficient_privilege using message = 'Account deletion in progress';
  end if;

  return query
  select result.*
  from app_private.complete_user_onboarding_internal(
    p_first_name,
    p_last_name,
    p_city_id,
    p_preferred_locale,
    p_preferred_currency,
    p_terms_document_id,
    p_privacy_document_id,
    p_ugc_document_id
  ) result;
end;
$$;

notify pgrst, 'reload schema';
