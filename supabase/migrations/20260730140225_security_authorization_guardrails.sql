-- SEC-001: close authorization gaps found during the V1 production-readiness audit.
--
-- The onboarding function is repeated intentionally: it is already deployed, so
-- changing its behavior requires a forward migration rather than editing history.
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
    p_privacy_document_id,
    p_ugc_document_id
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
      or (
        document.id = p_ugc_document_id
        and document.document_type = 'ugc_license'
      )
    );

  if selected_document_count <> 3 then
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
    (current_user_id, p_privacy_document_id),
    (current_user_id, p_ugc_document_id)
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

-- Social authors can only create and edit user-owned content. Moderation,
-- counters, watermarks, timestamps and publication state remain server-owned.
revoke insert, update on table public.social_posts from authenticated;
grant insert (
  author_id,
  social_media_type,
  listing_id,
  caption,
  content_lang
) on table public.social_posts to authenticated;
grant update (
  caption,
  content_lang
) on table public.social_posts to authenticated;

create or replace function public.moderate_social_post(
  p_post_id uuid,
  p_moderation_status public.social_post_status,
  p_watermark_applied boolean
)
returns setof public.social_posts
language plpgsql
security definer
set search_path = ''
as $$
begin
  perform app_private.require_completed_onboarding();

  if not public.current_user_has_verified_role('admin') then
    raise insufficient_privilege using
      message = 'Verified Kwabor admin role required';
  end if;

  if p_moderation_status = 'publie' and not p_watermark_applied then
    raise exception 'Published social media must be watermarked'
      using errcode = '22023';
  end if;

  return query
    update public.social_posts post
    set
      moderation_status = p_moderation_status,
      watermark_applied = p_watermark_applied,
      updated_at = now()
    where post.id = p_post_id
    returning post.*;

  if not found then
    raise exception 'Social post not found'
      using errcode = 'P0002';
  end if;
end;
$$;

revoke all on function public.moderate_social_post(
  uuid,
  public.social_post_status,
  boolean
) from public, anon;
grant execute on function public.moderate_social_post(
  uuid,
  public.social_post_status,
  boolean
) to authenticated;

-- Team membership creation and lifecycle changes are RPC-only. The current KMP
-- client performs role edits with a role-only patch.
drop policy if exists "managers invite organization members"
on public.organization_members;
revoke insert, update on table public.organization_members from authenticated;
grant update (
  role
) on table public.organization_members to authenticated;

create or replace function public.suspend_organization_member(
  p_organization_id uuid,
  p_member_id uuid
)
returns setof public.organization_members
language plpgsql
security definer
set search_path = ''
as $$
begin
  perform app_private.require_completed_onboarding();

  return query
    update public.organization_members member
    set
      status = 'suspended',
      suspended_at = now(),
      updated_at = now()
    where member.organization_id = p_organization_id
      and member.id = p_member_id
      and member.status = 'active'
      and member.role <> 'proprietaire'
      and (
        public.current_user_has_verified_role('admin')
        or app_private.current_user_can_assign_organization_role(
          p_organization_id,
          member.role
        )
      )
    returning member.*;

  if not found then
    raise exception 'Organization member not found or not suspendable'
      using errcode = 'P0002';
  end if;
end;
$$;

revoke all on function public.suspend_organization_member(uuid, uuid)
from public, anon;
grant execute on function public.suspend_organization_member(uuid, uuid)
to authenticated;

-- Claim and missing-place authority fields are never accepted from clients.
-- Their narrow update grants remain protected by admin-only RLS policies.
revoke insert, update on table public.claims from authenticated;
grant insert (
  listing_id,
  claimant_id,
  proof_url,
  contact_phone
) on table public.claims to authenticated;
grant update (
  status,
  decision_reason
) on table public.claims to authenticated;

revoke insert, update on table public.missing_place_reports from authenticated;
grant insert (
  reporter_id,
  name,
  presumed_type,
  city_id,
  lat,
  lng,
  note,
  photo_url
) on table public.missing_place_reports to authenticated;
grant update (
  status,
  assigned_admin_id
) on table public.missing_place_reports to authenticated;

create or replace function public.current_user_can_manage_listing(target_listing_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1
    from public.listings managed_listing
    where managed_listing.id = target_listing_id
      and (
        public.current_user_has_verified_role('admin')
        or (
          managed_listing.organization_id is null
          and managed_listing.owner_id = (select auth.uid())
          and (
            (
              managed_listing.listing_class in ('commercial', 'evenementiel')
              and public.current_user_has_verified_role('promoteur')
            )
            or (
              public.current_user_has_verified_role('guide')
              and (
                managed_listing.listing_class = 'evenementiel'
                or (
                  managed_listing.listing_class = 'commercial'
                  and managed_listing.type = 'etablissement'
                  and managed_listing.subtype = 'guide'
                )
              )
            )
          )
        )
        or (
          managed_listing.organization_id is null
          and managed_listing.listing_class = 'patrimonial'
          and managed_listing.steward_id = (select auth.uid())
          and public.current_user_has_verified_role('institution')
        )
        or app_private.current_user_can_manage_organization_listing(managed_listing.id)
      )
  );
$$;

revoke all on function public.current_user_can_manage_listing(uuid)
from public, anon, authenticated;
grant execute on function public.current_user_can_manage_listing(uuid)
to authenticated;

-- Keep the public catalogue policy independent from the privileged ownership
-- helper. Authenticated actors receive a separate policy for their own drafts.
drop policy if exists "published listings are readable" on public.listings;
create policy "published listings are readable"
on public.listings
for select
to anon, authenticated
using (status = 'publie');

drop policy if exists "authenticated users read managed listings"
on public.listings;
create policy "authenticated users read managed listings"
on public.listings
for select
to authenticated
using (
  submitted_by = (select auth.uid())
  or owner_id = (select auth.uid())
  or steward_id = (select auth.uid())
  or public.current_user_has_verified_role('admin')
  or public.current_user_can_manage_listing(id)
);
