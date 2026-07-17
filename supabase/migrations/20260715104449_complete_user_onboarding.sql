create type public.legal_document_type as enum (
  'terms',
  'privacy_policy',
  'ugc_license'
);

create table public.legal_documents (
  id uuid primary key default gen_random_uuid(),
  document_type public.legal_document_type not null,
  version text not null,
  locale text not null,
  content_url text not null,
  content_sha256 text not null,
  effective_at timestamptz not null,
  active boolean not null default false,
  created_at timestamptz not null default now(),
  constraint legal_documents_version_not_blank check (
    length(trim(version)) between 1 and 50
  ),
  constraint legal_documents_locale_supported check (
    locale in ('fr', 'en', 'pt', 'de', 'es', 'it')
  ),
  constraint legal_documents_content_url_https check (
    content_url ~ '^https://[^[:space:]]+$'
  ),
  constraint legal_documents_content_sha256_valid check (
    content_sha256 ~ '^[0-9a-f]{64}$'
  ),
  constraint legal_documents_unique_revision unique (
    document_type,
    locale,
    version
  )
);

create unique index legal_documents_one_active_revision_idx
on public.legal_documents (document_type, locale)
where active;

create index legal_documents_active_locale_effective_idx
on public.legal_documents (locale, effective_at desc)
where active;

create table public.user_legal_acceptances (
  user_id uuid not null references auth.users (id) on delete cascade,
  legal_document_id uuid not null references public.legal_documents (id) on delete restrict,
  accepted_at timestamptz not null default now(),
  primary key (user_id, legal_document_id)
);

create index user_legal_acceptances_document_idx
on public.user_legal_acceptances (legal_document_id, accepted_at);

create or replace function app_private.enforce_legal_document_revision_immutability()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if tg_op = 'DELETE' then
    raise exception 'Legal document revisions cannot be deleted'
      using errcode = '22023';
  end if;

  if row(
    new.id,
    new.document_type,
    new.version,
    new.locale,
    new.content_url,
    new.content_sha256,
    new.effective_at,
    new.created_at
  ) is distinct from row(
    old.id,
    old.document_type,
    old.version,
    old.locale,
    old.content_url,
    old.content_sha256,
    old.effective_at,
    old.created_at
  ) then
    raise exception 'Legal document revisions are immutable'
      using errcode = '22023';
  end if;

  return new;
end;
$$;

create trigger enforce_legal_document_revision_immutability
before update or delete on public.legal_documents
for each row execute function app_private.enforce_legal_document_revision_immutability();

revoke all on function app_private.enforce_legal_document_revision_immutability()
from public, anon, authenticated;

alter table public.profiles
add column onboarding_completed_at timestamptz;

alter table public.profiles
add constraint profiles_first_name_max_length check (
  length(trim(first_name)) <= 80
),
add constraint profiles_last_name_max_length check (
  length(trim(last_name)) <= 80
);

alter table public.legal_documents enable row level security;
alter table public.user_legal_acceptances enable row level security;

create policy "active legal documents are readable"
on public.legal_documents
for select
to anon, authenticated
using (
  active
  and effective_at <= now()
);

create policy "users read their legal acceptances"
on public.user_legal_acceptances
for select
to authenticated
using ((select auth.uid()) = user_id);

drop policy "profiles are publicly readable" on public.profiles;
create policy "completed profiles are publicly readable"
on public.profiles
for select
to anon, authenticated
using (
  onboarding_completed_at is not null
  or (select auth.uid()) = user_id
);

drop policy "users create their profile" on public.profiles;
drop policy "users create their base role" on public.user_roles;

revoke all privileges on table public.legal_documents from anon, authenticated;
revoke all privileges on table public.user_legal_acceptances from anon, authenticated;

grant select on table public.legal_documents to anon, authenticated;
grant select on table public.user_legal_acceptances to authenticated;

revoke insert, update on table public.profiles from authenticated;
grant update (
  first_name,
  last_name,
  avatar_url,
  cover_url,
  bio,
  city_id,
  preferred_locale,
  preferred_currency
) on table public.profiles to authenticated;

revoke insert on table public.user_roles from authenticated;

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
      and length(coalesce(account.encrypted_password, '')) > 0
  ) then
    raise exception 'Initial password is required'
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

create or replace function public.complete_user_onboarding(
  p_first_name text,
  p_last_name text,
  p_city_id text,
  p_preferred_locale text,
  p_preferred_currency text,
  p_terms_document_id uuid,
  p_privacy_document_id uuid,
  p_ugc_document_id uuid
)
returns setof public.profiles
language sql
security definer
set search_path = ''
as $$
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

revoke all on function public.complete_user_onboarding(
  text,
  text,
  text,
  text,
  text,
  uuid,
  uuid,
  uuid
) from public, anon, authenticated;

grant execute on function public.complete_user_onboarding(
  text,
  text,
  text,
  text,
  text,
  uuid,
  uuid,
  uuid
) to authenticated;

create or replace function app_private.current_user_has_completed_onboarding()
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select
    (select auth.uid()) is not null
    and exists (
      select 1
      from public.profiles profile
      where profile.user_id = (select auth.uid())
        and profile.onboarding_completed_at is not null
    );
$$;

revoke all on function app_private.current_user_has_completed_onboarding()
from public, anon, authenticated;

grant execute on function app_private.current_user_has_completed_onboarding()
to authenticated;

drop policy "users update their profile" on public.profiles;
create policy "users update their profile"
on public.profiles
for update
to authenticated
using (
  (select app_private.current_user_has_completed_onboarding())
  and (select auth.uid()) = user_id
)
with check (
  (select app_private.current_user_has_completed_onboarding())
  and (select auth.uid()) = user_id
);

drop policy "verified roles create allowed listings" on public.listings;
create policy "verified roles create allowed listings"
on public.listings
for insert
to authenticated
with check (
  (select app_private.current_user_has_completed_onboarding())
  and submitted_by = (select auth.uid())
  and (
    public.current_user_has_verified_role('admin')
    or (
      listing_class = 'patrimonial'
      and owner_id is null
      and status in ('brouillon', 'en_attente')
      and public.current_user_has_verified_role('institution')
    )
    or (
      listing_class in ('commercial', 'evenementiel')
      and owner_id = (select auth.uid())
      and status in ('brouillon', 'en_attente')
      and (
        public.current_user_has_verified_role('promoteur')
        or public.current_user_has_verified_role('guide')
      )
    )
  )
);

drop policy "verified roles update allowed listings" on public.listings;
create policy "verified roles update allowed listings"
on public.listings
for update
to authenticated
using (
  (select app_private.current_user_has_completed_onboarding())
  and (
    public.current_user_has_verified_role('admin')
    or (
      listing_class = 'patrimonial'
      and (
        steward_id = (select auth.uid())
        or public.current_user_has_verified_role('institution')
      )
    )
    or (
      listing_class in ('commercial', 'evenementiel')
      and owner_id = (select auth.uid())
    )
  )
)
with check (
  (select app_private.current_user_has_completed_onboarding())
  and (
    public.current_user_has_verified_role('admin')
    or (
      listing_class = 'patrimonial'
      and owner_id is null
      and status in ('brouillon', 'en_attente')
      and (
        steward_id = (select auth.uid())
        or public.current_user_has_verified_role('institution')
      )
    )
    or (
      listing_class in ('commercial', 'evenementiel')
      and owner_id = (select auth.uid())
      and status in ('brouillon', 'en_attente')
      and (
        public.current_user_has_verified_role('promoteur')
        or public.current_user_has_verified_role('guide')
      )
    )
  )
);

drop policy "listing managers create media" on public.listing_media;
create policy "listing managers create media"
on public.listing_media
for insert
to authenticated
with check (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_can_manage_listing(listing_id)
);

drop policy "listing managers update media" on public.listing_media;
create policy "listing managers update media"
on public.listing_media
for update
to authenticated
using (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_can_manage_listing(listing_id)
)
with check (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_can_manage_listing(listing_id)
);

drop policy "listing managers delete media" on public.listing_media;
create policy "listing managers delete media"
on public.listing_media
for delete
to authenticated
using (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_can_manage_listing(listing_id)
);

drop policy "authenticated users create attached social posts" on public.social_posts;
create policy "authenticated users create attached social posts"
on public.social_posts
for insert
to authenticated
with check (
  (select app_private.current_user_has_completed_onboarding())
  and author_id = (select auth.uid())
  and listing_id is not null
);

drop policy "authors update pending social posts" on public.social_posts;
create policy "authors update pending social posts"
on public.social_posts
for update
to authenticated
using (
  (select app_private.current_user_has_completed_onboarding())
  and (
    author_id = (select auth.uid())
    or public.current_user_has_verified_role('admin')
  )
)
with check (
  (select app_private.current_user_has_completed_onboarding())
  and (
    public.current_user_has_verified_role('admin')
    or (
      author_id = (select auth.uid())
      and moderation_status in ('en_attente', 'rejete')
    )
  )
);

drop policy "authors delete social posts" on public.social_posts;
create policy "authors delete social posts"
on public.social_posts
for delete
to authenticated
using (
  (select app_private.current_user_has_completed_onboarding())
  and (
    author_id = (select auth.uid())
    or public.current_user_has_verified_role('admin')
  )
);

drop policy "authors create social media" on public.social_media;
create policy "authors create social media"
on public.social_media
for insert
to authenticated
with check (
  (select app_private.current_user_has_completed_onboarding())
  and exists (
    select 1
    from public.social_posts post
    where post.id = social_media.post_id
      and post.author_id = (select auth.uid())
  )
);

drop policy "authors update social media" on public.social_media;
create policy "authors update social media"
on public.social_media
for update
to authenticated
using (
  (select app_private.current_user_has_completed_onboarding())
  and exists (
    select 1
    from public.social_posts post
    where post.id = social_media.post_id
      and (
        post.author_id = (select auth.uid())
        or public.current_user_has_verified_role('admin')
      )
  )
)
with check (
  (select app_private.current_user_has_completed_onboarding())
  and exists (
    select 1
    from public.social_posts post
    where post.id = social_media.post_id
      and (
        post.author_id = (select auth.uid())
        or public.current_user_has_verified_role('admin')
      )
  )
);

drop policy "authors delete social media" on public.social_media;
create policy "authors delete social media"
on public.social_media
for delete
to authenticated
using (
  (select app_private.current_user_has_completed_onboarding())
  and exists (
    select 1
    from public.social_posts post
    where post.id = social_media.post_id
      and (
        post.author_id = (select auth.uid())
        or public.current_user_has_verified_role('admin')
      )
  )
);

drop policy "users favorite published listings" on public.favorites;
create policy "users favorite published listings"
on public.favorites
for insert
to authenticated
with check (
  (select app_private.current_user_has_completed_onboarding())
  and (select auth.uid()) = user_id
  and exists (
    select 1
    from public.listings listing
    where listing.id = favorites.listing_id
      and listing.status = 'publie'
  )
);

drop policy "users delete their favorites" on public.favorites;
create policy "users delete their favorites"
on public.favorites
for delete
to authenticated
using (
  (select app_private.current_user_has_completed_onboarding())
  and (select auth.uid()) = user_id
);

drop policy "users like published listings" on public.likes;
create policy "users like published listings"
on public.likes
for insert
to authenticated
with check (
  (select app_private.current_user_has_completed_onboarding())
  and (select auth.uid()) = user_id
  and exists (
    select 1
    from public.listings listing
    where listing.id = likes.listing_id
      and listing.status = 'publie'
  )
);

drop policy "users delete their likes" on public.likes;
create policy "users delete their likes"
on public.likes
for delete
to authenticated
using (
  (select app_private.current_user_has_completed_onboarding())
  and (select auth.uid()) = user_id
);

drop policy "users mark their notifications read" on public.notifications;
create policy "users mark their notifications read"
on public.notifications
for update
to authenticated
using (
  (select app_private.current_user_has_completed_onboarding())
  and (select auth.uid()) = user_id
)
with check (
  (select app_private.current_user_has_completed_onboarding())
  and (select auth.uid()) = user_id
);

drop policy "verified promoters submit claims" on public.claims;
create policy "verified promoters submit claims"
on public.claims
for insert
to authenticated
with check (
  (select app_private.current_user_has_completed_onboarding())
  and claimant_id = (select auth.uid())
  and (
    public.current_user_has_verified_role('promoteur')
    or public.current_user_has_verified_role('guide')
  )
  and exists (
    select 1
    from public.listings listing
    where listing.id = claims.listing_id
      and listing.status = 'publie'
      and listing.owner_id is null
      and listing.listing_class in ('commercial', 'evenementiel')
  )
);

drop policy "admins update claims" on public.claims;
create policy "admins update claims"
on public.claims
for update
to authenticated
using (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_has_verified_role('admin')
)
with check (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_has_verified_role('admin')
);

drop policy "authenticated users create missing place reports" on public.missing_place_reports;
create policy "authenticated users create missing place reports"
on public.missing_place_reports
for insert
to authenticated
with check (
  (select app_private.current_user_has_completed_onboarding())
  and reporter_id = (select auth.uid())
);

drop policy "admins update missing place reports" on public.missing_place_reports;
create policy "admins update missing place reports"
on public.missing_place_reports
for update
to authenticated
using (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_has_verified_role('admin')
)
with check (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_has_verified_role('admin')
);

drop policy "admins create organizations" on public.organizations;
create policy "admins create organizations"
on public.organizations
for insert
to authenticated
with check (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_has_verified_role('admin')
);

drop policy "admins update organizations" on public.organizations;
create policy "admins update organizations"
on public.organizations
for update
to authenticated
using (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_has_verified_role('admin')
)
with check (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_has_verified_role('admin')
);

drop policy "managers invite organization members" on public.organization_members;
create policy "managers invite organization members"
on public.organization_members
for insert
to authenticated
with check (
  (select app_private.current_user_has_completed_onboarding())
  and (
    (
      public.current_user_has_verified_role('admin')
      and status in ('invited', 'active')
    )
    or (
      status = 'invited'
      and app_private.current_user_can_assign_organization_role(organization_id, role)
    )
  )
);

drop policy "managers update organization members" on public.organization_members;
create policy "managers update organization members"
on public.organization_members
for update
to authenticated
using (
  (select app_private.current_user_has_completed_onboarding())
  and (
    public.current_user_has_verified_role('admin')
    or app_private.current_user_can_assign_organization_role(organization_id, role)
  )
)
with check (
  (select app_private.current_user_has_completed_onboarding())
  and (
    public.current_user_has_verified_role('admin')
    or app_private.current_user_can_assign_organization_role(organization_id, role)
  )
);

drop policy "managers create organization invites" on public.organization_invites;
create policy "managers create organization invites"
on public.organization_invites
for insert
to authenticated
with check (
  (select app_private.current_user_has_completed_onboarding())
  and status = 'pending'
  and invited_by_member_id = app_private.current_user_membership_id(organization_id)
  and app_private.current_user_can_assign_organization_role(organization_id, proposed_role)
);

drop policy "managers update organization invites" on public.organization_invites;
create policy "managers update organization invites"
on public.organization_invites
for update
to authenticated
using (
  (select app_private.current_user_has_completed_onboarding())
  and (
    public.current_user_has_verified_role('admin')
    or (
      status = 'pending'
      and app_private.current_user_can_assign_organization_role(organization_id, proposed_role)
    )
  )
)
with check (
  (select app_private.current_user_has_completed_onboarding())
  and (
    public.current_user_has_verified_role('admin')
    or app_private.current_user_can_assign_organization_role(organization_id, proposed_role)
  )
);

drop policy "owners and managers allocate ad budgets" on public.member_ad_budgets;
create policy "owners and managers allocate ad budgets"
on public.member_ad_budgets
for insert
to authenticated
with check (
  (select app_private.current_user_has_completed_onboarding())
  and app_private.member_budget_allocation_is_allowed(
    organization_id,
    allocated_by_member_id,
    member_id,
    allocated_xof,
    period_start,
    period_end
  )
);

create or replace function app_private.require_completed_onboarding()
returns void
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  if not app_private.current_user_has_completed_onboarding() then
    raise exception 'Onboarding completion required'
      using errcode = '42501';
  end if;
end;
$$;

revoke all on function app_private.require_completed_onboarding()
from public, anon, authenticated;

grant execute on function app_private.require_completed_onboarding()
to authenticated;

create or replace function public.like_listing(p_listing_id uuid)
returns table (
  listing_id uuid,
  liked_by_current_user boolean,
  favorited_by_current_user boolean,
  likes_count integer
)
language plpgsql
security invoker
set search_path = public
as $$
declare
  current_user_id uuid := (select auth.uid());
begin
  perform app_private.require_completed_onboarding();

  if not exists (
    select 1
    from public.listings listing
    where listing.id = p_listing_id
      and listing.status = 'publie'
  ) then
    raise no_data_found using message = 'listing not found';
  end if;

  insert into public.likes (user_id, listing_id)
  values (current_user_id, p_listing_id)
  on conflict on constraint likes_pkey do nothing;

  return query
  select *
  from public.get_listing_viewer_interaction(p_listing_id);
end;
$$;

create or replace function public.unlike_listing(p_listing_id uuid)
returns table (
  listing_id uuid,
  liked_by_current_user boolean,
  favorited_by_current_user boolean,
  likes_count integer
)
language plpgsql
security invoker
set search_path = public
as $$
declare
  current_user_id uuid := (select auth.uid());
begin
  perform app_private.require_completed_onboarding();

  delete from public.likes like_row
  where like_row.user_id = current_user_id
    and like_row.listing_id = p_listing_id;

  return query
  select *
  from public.get_listing_viewer_interaction(p_listing_id);
end;
$$;

create or replace function public.add_listing_to_favorites(p_listing_id uuid)
returns table (
  listing_id uuid,
  liked_by_current_user boolean,
  favorited_by_current_user boolean,
  likes_count integer
)
language plpgsql
security invoker
set search_path = public
as $$
declare
  current_user_id uuid := (select auth.uid());
begin
  perform app_private.require_completed_onboarding();

  if not exists (
    select 1
    from public.listings listing
    where listing.id = p_listing_id
      and listing.status = 'publie'
  ) then
    raise no_data_found using message = 'listing not found';
  end if;

  insert into public.favorites (user_id, listing_id)
  values (current_user_id, p_listing_id)
  on conflict on constraint favorites_pkey do nothing;

  return query
  select *
  from public.get_listing_viewer_interaction(p_listing_id);
end;
$$;

create or replace function public.remove_listing_from_favorites(p_listing_id uuid)
returns table (
  listing_id uuid,
  liked_by_current_user boolean,
  favorited_by_current_user boolean,
  likes_count integer
)
language plpgsql
security invoker
set search_path = public
as $$
declare
  current_user_id uuid := (select auth.uid());
begin
  perform app_private.require_completed_onboarding();

  delete from public.favorites favorite_row
  where favorite_row.user_id = current_user_id
    and favorite_row.listing_id = p_listing_id;

  return query
  select *
  from public.get_listing_viewer_interaction(p_listing_id);
end;
$$;

create or replace function public.create_organization_invite(
  p_organization_id uuid,
  p_invited_by_member_id uuid,
  p_email text,
  p_proposed_role public.organization_role,
  p_expires_at timestamptz
)
returns setof public.organization_invites
language plpgsql
security invoker
set search_path = public, extensions, pg_temp
as $$
declare
  normalized_email text := lower(trim(p_email));
  invite_token text := encode(extensions.gen_random_bytes(32), 'hex');
begin
  perform app_private.require_completed_onboarding();

  if normalized_email = ''
    or position('@' in normalized_email) <= 1
    or position('.' in substring(normalized_email from position('@' in normalized_email) + 2)) = 0
  then
    raise exception 'Invalid organization invitation email'
      using errcode = '22023';
  end if;

  return query
    insert into public.organization_invites (
      organization_id,
      email,
      token_hash,
      proposed_role,
      invited_by_member_id,
      expires_at
    )
    values (
      p_organization_id,
      normalized_email,
      encode(extensions.digest(invite_token, 'sha256'), 'hex'),
      p_proposed_role,
      p_invited_by_member_id,
      p_expires_at
    )
    returning *;
end;
$$;

create or replace function public.revoke_organization_invite(p_invite_id uuid)
returns setof public.organization_invites
language plpgsql
security invoker
set search_path = public, pg_temp
as $$
begin
  perform app_private.require_completed_onboarding();

  return query
    update public.organization_invites invite
    set
      status = 'revoked',
      revoked_at = now(),
      updated_at = now()
    where invite.id = p_invite_id
      and invite.status = 'pending'
    returning invite.*;

  if not found then
    raise exception 'Organization invitation not found or not revocable'
      using errcode = 'P0002';
  end if;
end;
$$;

create or replace function public.suspend_organization_member(
  p_organization_id uuid,
  p_member_id uuid
)
returns setof public.organization_members
language plpgsql
security invoker
set search_path = public, pg_temp
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
    returning member.*;

  if not found then
    raise exception 'Organization member not found or not suspendable'
      using errcode = 'P0002';
  end if;
end;
$$;

create or replace function public.accept_organization_invite(p_invite_token text)
returns setof public.organization_members
language plpgsql
security definer
set search_path = ''
as $$
declare
  current_user_id uuid := (select auth.uid());
  current_user_email text;
  target_invite public.organization_invites%rowtype;
  accepted_member public.organization_members%rowtype;
begin
  perform app_private.require_completed_onboarding();

  select lower(trim(auth_user.email))
  into current_user_email
  from auth.users auth_user
  where auth_user.id = current_user_id;

  if current_user_email is null then
    raise exception 'Authenticated user email not found'
      using errcode = '42501';
  end if;

  select invite.*
  into target_invite
  from public.organization_invites invite
  where invite.token_hash = encode(extensions.digest(p_invite_token, 'sha256'), 'hex')
    and invite.status = 'pending'
    and invite.expires_at > now()
  for update;

  if not found then
    raise exception 'Organization invitation not found or expired'
      using errcode = 'P0002';
  end if;

  if target_invite.proposed_role = 'proprietaire' then
    raise exception 'Owner invitations require a dedicated transfer flow'
      using errcode = '42501';
  end if;

  if target_invite.email <> current_user_email then
    raise exception 'Organization invitation email does not match authenticated user'
      using errcode = '42501';
  end if;

  update public.organization_invites invite
  set
    status = 'accepted',
    accepted_by = current_user_id,
    accepted_at = now(),
    updated_at = now()
  where invite.id = target_invite.id;

  insert into public.organization_members (
    organization_id,
    user_id,
    role,
    status,
    invited_by,
    accepted_at
  )
  values (
    target_invite.organization_id,
    current_user_id,
    target_invite.proposed_role,
    'active',
    target_invite.invited_by_member_id,
    now()
  )
  on conflict (organization_id, user_id)
  do update
  set
    role = excluded.role,
    status = 'active',
    invited_by = excluded.invited_by,
    accepted_at = coalesce(public.organization_members.accepted_at, excluded.accepted_at),
    suspended_at = null,
    updated_at = now()
  returning * into accepted_member;

  return next accepted_member;
end;
$$;
