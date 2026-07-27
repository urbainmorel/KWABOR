create type public.account_deletion_status as enum ('prepared', 'completed');

alter table public.listings
add column organization_id uuid references public.organizations (id) on delete set null;

alter table public.organization_invites
drop constraint organization_invites_inviter_same_org;

alter table public.organization_invites
alter column invited_by_member_id drop not null;

alter table public.organization_invites
add constraint organization_invites_inviter_same_org
foreign key (invited_by_member_id, organization_id)
references public.organization_members (id, organization_id)
on delete set null (invited_by_member_id);

alter table public.member_ad_budgets
drop constraint member_ad_budgets_allocator_same_org;

alter table public.member_ad_budgets
alter column allocated_by_member_id drop not null;

alter table public.member_ad_budgets
add constraint member_ad_budgets_allocator_same_org
foreign key (allocated_by_member_id, organization_id)
references public.organization_members (id, organization_id)
on delete set null (allocated_by_member_id);

alter table public.organization_invites
drop constraint organization_invites_accepted_fields;

alter table public.organization_invites
add constraint organization_invites_accepted_fields check (
  status <> 'accepted'
  or accepted_at is not null
);

create index listings_organization_status_idx
on public.listings (organization_id, status)
where organization_id is not null;

create table public.promoter_invites (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations (id) on delete cascade,
  listing_id uuid not null references public.listings (id) on delete cascade,
  email text not null,
  token_hash text not null unique,
  status public.organization_invite_status not null default 'pending',
  expires_at timestamptz not null,
  accepted_by uuid references auth.users (id) on delete set null,
  accepted_at timestamptz,
  revoked_at timestamptz,
  created_by uuid references auth.users (id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint promoter_invites_email_normalized check (email = lower(trim(email)) and position('@' in email) > 1),
  constraint promoter_invites_token_hash_hex check (token_hash ~ '^[a-f0-9]{64}$'),
  constraint promoter_invites_expiry_after_creation check (expires_at > created_at),
  constraint promoter_invites_accepted_fields check (
    (status = 'accepted' and accepted_at is not null)
    or (status <> 'accepted' and accepted_by is null and accepted_at is null)
  )
);

create unique index promoter_invites_one_pending_listing_idx
on public.promoter_invites (listing_id)
where status = 'pending';

create index promoter_invites_org_status_idx
on public.promoter_invites (organization_id, status, expires_at);

create trigger promoter_invites_touch_updated_at
before update on public.promoter_invites
for each row execute function public.touch_updated_at();

alter table public.promoter_invites enable row level security;

revoke all on table public.promoter_invites from public, anon, authenticated;
grant all on table public.promoter_invites to service_role;

create table public.account_deletion_requests (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null,
  idempotency_key uuid not null,
  status public.account_deletion_status not null default 'prepared',
  prepared_at timestamptz not null default now(),
  completed_at timestamptz,
  constraint account_deletion_requests_idempotent unique (user_id, idempotency_key),
  constraint account_deletion_requests_completed_at check (
    (status = 'completed' and completed_at is not null)
    or (status = 'prepared' and completed_at is null)
  )
);

create unique index account_deletion_requests_one_prepared_user_idx
on public.account_deletion_requests (user_id)
where status = 'prepared';

alter table public.account_deletion_requests enable row level security;

revoke all on table public.account_deletion_requests from public, anon, authenticated;
grant all on table public.account_deletion_requests to service_role;

create or replace function app_private.current_user_storage_write_allowed()
returns boolean
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  requesting_user_id uuid := (select auth.uid());
begin
  if requesting_user_id is null then
    return false;
  end if;

  -- Storage uploads and account deletion share this transaction-scoped lock.
  -- An upload already in flight must commit before deletion checks objects;
  -- a later upload waits for the tombstone and is then rejected.
  perform pg_catalog.pg_advisory_xact_lock_shared(
    pg_catalog.hashtextextended(requesting_user_id::text, 0)
  );

  return not exists (
    select 1
    from public.account_deletion_requests request
    where request.user_id = requesting_user_id
  );
end;
$$;

revoke all on function app_private.current_user_storage_write_allowed()
from public, anon, authenticated;
grant execute on function app_private.current_user_storage_write_allowed()
to authenticated;

create policy "account deletion fences storage inserts"
on storage.objects
as restrictive
for insert
to authenticated
with check (
  owner_id = (select auth.uid())::text
  and app_private.current_user_storage_write_allowed()
);

create policy "account deletion fences storage updates"
on storage.objects
as restrictive
for update
to authenticated
using (
  owner_id = (select auth.uid())::text
  and app_private.current_user_storage_write_allowed()
)
with check (
  owner_id = (select auth.uid())::text
  and app_private.current_user_storage_write_allowed()
);

create or replace function app_private.current_user_can_manage_organization_listing(target_listing_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1
    from public.listings listing
    join public.organizations organization
      on organization.id = listing.organization_id
     and organization.verification_status = 'verified'
    join public.organization_members member
      on member.organization_id = organization.id
     and member.user_id = (select auth.uid())
     and member.status = 'active'
     and member.role in ('editeur', 'gestionnaire', 'proprietaire')
    where listing.id = target_listing_id
  );
$$;

revoke all on function app_private.current_user_can_manage_organization_listing(uuid)
from public, anon, authenticated;
grant execute on function app_private.current_user_can_manage_organization_listing(uuid)
to authenticated;

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
          and managed_listing.listing_class in ('commercial', 'evenementiel')
          and managed_listing.owner_id = (select auth.uid())
          and (
            public.current_user_has_verified_role('promoteur')
            or public.current_user_has_verified_role('guide')
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

revoke all on function public.current_user_can_manage_listing(uuid) from public, anon, authenticated;
grant execute on function public.current_user_can_manage_listing(uuid) to anon, authenticated;

drop policy "verified roles create allowed listings" on public.listings;
create policy "verified roles create allowed listings"
on public.listings
for insert
to authenticated
with check (
  (select app_private.current_user_has_completed_onboarding())
  and submitted_by = (select auth.uid())
  and status in ('brouillon', 'en_attente')
  and (
    (
      organization_id is null
      and (
        (
          listing_class = 'patrimonial'
          and owner_id is null
          and steward_id = (select auth.uid())
          and (
            public.current_user_has_verified_role('institution')
            or public.current_user_has_verified_role('admin')
          )
        )
        or (
          listing_class in ('commercial', 'evenementiel')
          and owner_id = (select auth.uid())
          and steward_id is null
          and (
            public.current_user_has_verified_role('promoteur')
            or public.current_user_has_verified_role('guide')
            or public.current_user_has_verified_role('admin')
          )
        )
      )
    )
    or (
      organization_id is not null
      and owner_id is null
      and (
        (
          listing_class = 'patrimonial'
          and steward_id = (select auth.uid())
        )
        or (
          listing_class in ('commercial', 'evenementiel')
          and steward_id is null
        )
      )
      and exists (
        select 1
        from public.organizations organization
        join public.organization_members member
          on member.organization_id = organization.id
         and member.user_id = (select auth.uid())
         and member.status = 'active'
         and member.role in ('editeur', 'gestionnaire', 'proprietaire')
        where organization.id = listings.organization_id
          and organization.verification_status = 'verified'
          and (
            (
              listings.listing_class = 'patrimonial'
              and organization.type in ('institution', 'admin_kwabor')
            )
            or (
              listings.listing_class in ('commercial', 'evenementiel')
              and organization.type in ('promoteur', 'etablissement', 'admin_kwabor')
            )
          )
      )
    )
  )
);

drop policy "published listings are readable" on public.listings;
create policy "published listings are readable"
on public.listings
for select
to anon, authenticated
using (
  status = 'publie'
  or submitted_by = (select auth.uid())
  or owner_id = (select auth.uid())
  or steward_id = (select auth.uid())
  or public.current_user_has_verified_role('admin')
  or public.current_user_can_manage_listing(id)
);

drop policy "verified roles update allowed listings" on public.listings;
create policy "verified roles update allowed listings"
on public.listings
for update
to authenticated
using (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_can_manage_listing(id)
)
with check (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_can_manage_listing(id)
  and (
    public.current_user_has_verified_role('admin')
    or (
      listing_class = 'patrimonial'
      and owner_id is null
      and status in ('brouillon', 'en_attente')
      and (
        organization_id is not null
        or public.current_user_has_verified_role('institution')
      )
    )
    or (
      listing_class in ('commercial', 'evenementiel')
      and status in ('brouillon', 'en_attente')
      and (
        organization_id is not null
        or public.current_user_has_verified_role('promoteur')
        or public.current_user_has_verified_role('guide')
      )
    )
  )
);

create or replace function app_private.guard_authenticated_listing_authority_columns()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if current_user = 'authenticated'
    and row(
      new.organization_id,
      new.owner_id,
      new.steward_id,
      new.submitted_by,
      new.listing_class,
      new.status,
      new.verified,
      new.sponsored_until,
      new.editorial_pin_until,
      new.rating_avg,
      new.rating_count,
      new.views_count,
      new.likes_count,
      new.created_at,
      new.published_at
    ) is distinct from row(
      old.organization_id,
      old.owner_id,
      old.steward_id,
      old.submitted_by,
      old.listing_class,
      old.status,
      old.verified,
      old.sponsored_until,
      old.editorial_pin_until,
      old.rating_avg,
      old.rating_count,
      old.views_count,
      old.likes_count,
      old.created_at,
      old.published_at
    )
  then
    raise insufficient_privilege using
      message = 'Listing authority fields are server-managed';
  end if;

  return new;
end;
$$;

revoke all on function app_private.guard_authenticated_listing_authority_columns()
from public, anon, authenticated;

create trigger listings_guard_authenticated_authority_columns
before update on public.listings
for each row execute function app_private.guard_authenticated_listing_authority_columns();

revoke insert, update on table public.listings from authenticated;

grant insert (
  id,
  type,
  subtype,
  listing_class,
  category_id,
  owner_id,
  steward_id,
  submitted_by,
  status,
  name,
  slug,
  description,
  content_lang,
  city_id,
  district,
  address,
  lat,
  lng,
  google_place_id,
  price_from_xof,
  price_unit,
  price_tier,
  opening_hours,
  contact_phone,
  contact_whatsapp,
  external_url,
  email,
  socials,
  tags,
  organization_id
) on table public.listings to authenticated;

grant update (
  type,
  subtype,
  category_id,
  name,
  slug,
  description,
  content_lang,
  city_id,
  district,
  address,
  lat,
  lng,
  google_place_id,
  price_from_xof,
  price_unit,
  price_tier,
  opening_hours,
  contact_phone,
  contact_whatsapp,
  external_url,
  email,
  socials,
  tags
) on table public.listings to authenticated;

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

create or replace function public.create_promoter_invite(
  p_organization_id uuid,
  p_listing_id uuid,
  p_email text,
  p_expires_at timestamptz
)
returns table (
  invite_id uuid,
  invite_token text,
  organization_id uuid,
  listing_id uuid,
  expires_at timestamptz
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  generated_token text;
  inserted_invite public.promoter_invites%rowtype;
begin
  if (select auth.uid()) is null or not public.current_user_has_verified_role('admin') then
    raise insufficient_privilege using message = 'Promoter invite administration is restricted';
  end if;

  perform app_private.require_completed_onboarding();

  if p_email is null or lower(trim(p_email)) !~ '^[^[:space:]@]+@[^[:space:]@]+[.][^[:space:]@]+$' then
    raise invalid_parameter_value using message = 'Invalid promoter invite request';
  end if;

  if p_expires_at <= now() or p_expires_at > now() + interval '30 days' then
    raise invalid_parameter_value using message = 'Invalid promoter invite request';
  end if;

  if not exists (
    select 1
    from public.organizations organization
    join public.listings listing on listing.id = p_listing_id
    where organization.id = p_organization_id
      and organization.verification_status = 'verified'
      and organization.type in ('promoteur', 'etablissement')
      and listing.listing_class in ('commercial', 'evenementiel')
      and (listing.organization_id is null or listing.organization_id = organization.id)
  ) then
    raise invalid_parameter_value using message = 'Invalid promoter invite request';
  end if;

  update public.promoter_invites existing
  set status = 'revoked', revoked_at = now()
  where existing.listing_id = p_listing_id
    and existing.status = 'pending';

  generated_token := encode(extensions.gen_random_bytes(32), 'hex');

  insert into public.promoter_invites (
    organization_id,
    listing_id,
    email,
    token_hash,
    expires_at,
    created_by
  )
  values (
    p_organization_id,
    p_listing_id,
    lower(trim(p_email)),
    encode(extensions.digest(generated_token, 'sha256'), 'hex'),
    p_expires_at,
    (select auth.uid())
  )
  returning * into inserted_invite;

  return query
  select
    inserted_invite.id,
    generated_token,
    inserted_invite.organization_id,
    inserted_invite.listing_id,
    inserted_invite.expires_at;
end;
$$;

create or replace function public.preview_promoter_invite(p_invite_token text)
returns table (
  status text,
  organization_id uuid,
  listing_id uuid,
  business_name text
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  current_user_id uuid := (select auth.uid());
  current_user_email text;
  target_invite public.promoter_invites%rowtype;
  target_business_name text;
  invite_found boolean := false;
begin
  if current_user_id is null then
    raise insufficient_privilege using message = 'Authentication required';
  end if;

  if p_invite_token is null or p_invite_token !~ '^[a-f0-9]{64}$' then
    return query select 'invalid'::text, null::uuid, null::uuid, null::text;
    return;
  end if;

  select lower(account.email)
  into current_user_email
  from auth.users account
  where account.id = current_user_id
    and account.email_confirmed_at is not null;

  if current_user_email is null then
    return query select 'invalid'::text, null::uuid, null::uuid, null::text;
    return;
  end if;

  select invite.*
  into target_invite
  from public.promoter_invites invite
  where invite.token_hash = encode(extensions.digest(p_invite_token, 'sha256'), 'hex')
  limit 1;

  invite_found := found;
  if invite_found then
    select listing.name
    into target_business_name
    from public.listings listing
    where listing.id = target_invite.listing_id;
  end if;

  if not invite_found or target_invite.email <> current_user_email then
    return query select 'invalid'::text, null::uuid, null::uuid, null::text;
    return;
  elsif target_invite.status <> 'pending' then
    return query
    select target_invite.status::text, target_invite.organization_id, target_invite.listing_id, target_business_name;
  elsif target_invite.expires_at <= now() then
    return query
    select 'expired'::text, target_invite.organization_id, target_invite.listing_id, target_business_name;
  else
    return query
    select 'ready'::text, target_invite.organization_id, target_invite.listing_id, target_business_name;
  end if;
end;
$$;

create or replace function app_private.current_jwt_has_recent_strong_authentication()
returns boolean
language sql
stable
security invoker
set search_path = ''
as $$
  with jwt_claims as (
    select coalesce(
      nullif(current_setting('request.jwt.claim', true), ''),
      nullif(current_setting('request.jwt.claims', true), ''),
      '{}'
    )::jsonb as claims
  ),
  amr_entries as (
    select
      entry.value,
      entry.ordinality,
      entry.value ->> 'method' as method,
      case
        when jsonb_typeof(entry.value) = 'object'
          and jsonb_typeof(entry.value -> 'timestamp') = 'number'
          and (entry.value ->> 'timestamp') ~ '^[0-9]{1,10}$'
        then (entry.value ->> 'timestamp')::bigint
        else null
      end as authenticated_at
    from jwt_claims
    cross join lateral jsonb_array_elements(
      case
        when jsonb_typeof(jwt_claims.claims -> 'amr') = 'array'
        then jwt_claims.claims -> 'amr'
        else '[]'::jsonb
      end
    ) with ordinality as entry(value, ordinality)
  ),
  validated_amr as (
    select
      coalesce(
        bool_and(
          jsonb_typeof(value) = 'object'
          and jsonb_typeof(value -> 'method') = 'string'
          and authenticated_at is not null
        ),
        false
      ) as is_valid
    from amr_entries
  ),
  most_recent_method as (
    select method, authenticated_at
    from amr_entries
    where authenticated_at is not null
    order by authenticated_at desc, ordinality desc
    limit 1
  )
  select
    validated_amr.is_valid
    and coalesce(
      (
        select
          most_recent_method.method in ('password', 'oauth')
          and most_recent_method.authenticated_at
            >= pg_catalog.floor(
              extract(epoch from pg_catalog.statement_timestamp())
            )::bigint - 300
          and most_recent_method.authenticated_at
            <= pg_catalog.floor(
              extract(epoch from pg_catalog.statement_timestamp())
            )::bigint + 30
        from most_recent_method
      ),
      false
    )
  from validated_amr;
$$;

revoke all on function app_private.current_jwt_has_recent_strong_authentication()
from public, anon, authenticated;

create or replace function public.activate_promoter_invite(p_invite_token text)
returns table (
  status text,
  organization_id uuid,
  listing_id uuid,
  business_name text
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  current_user_id uuid := (select auth.uid());
  current_user_email text;
  target_invite public.promoter_invites%rowtype;
  target_business_name text;
  existing_member public.organization_members%rowtype;
  has_existing_member boolean := false;
  invite_found boolean := false;
begin
  if current_user_id is null then
    raise insufficient_privilege using message = 'Authentication required';
  end if;

  -- Serialize activation with account deletion. If activation owns the shared
  -- lock first, a later deletion waits and cleans the resulting writes. If
  -- deletion owns the exclusive lock first, activation observes its tombstone.
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

  perform app_private.require_completed_onboarding();

  if not app_private.current_jwt_has_recent_strong_authentication() then
    raise insufficient_privilege using message = 'Recent strong authentication required';
  end if;

  if p_invite_token is null or p_invite_token !~ '^[a-f0-9]{64}$' then
    return query select 'invalid'::text, null::uuid, null::uuid, null::text;
    return;
  end if;

  select lower(account.email)
  into current_user_email
  from auth.users account
  where account.id = current_user_id
    and account.email_confirmed_at is not null;

  if current_user_email is null then
    return query select 'unconfirmed'::text, null::uuid, null::uuid, null::text;
    return;
  end if;

  select invite.*
  into target_invite
  from public.promoter_invites invite
  where invite.token_hash = encode(extensions.digest(p_invite_token, 'sha256'), 'hex')
  for update;

  invite_found := found;
  if invite_found then
    select listing.name
    into target_business_name
    from public.listings listing
    where listing.id = target_invite.listing_id;
  end if;

  if not invite_found or target_invite.email <> current_user_email then
    return query select 'invalid'::text, null::uuid, null::uuid, null::text;
    return;
  elsif target_invite.status <> 'pending' then
    return query
    select target_invite.status::text, target_invite.organization_id, target_invite.listing_id, target_business_name;
    return;
  elsif target_invite.expires_at <= now() then
    update public.promoter_invites set status = 'expired' where id = target_invite.id;
    return query
    select 'expired'::text, target_invite.organization_id, target_invite.listing_id, target_business_name;
    return;
  end if;

  perform 1
    from public.organizations organization
    join public.listings listing on listing.id = target_invite.listing_id
    where organization.id = target_invite.organization_id
      and organization.verification_status = 'verified'
      and organization.type in ('promoteur', 'etablissement')
      and listing.listing_class in ('commercial', 'evenementiel')
      and (listing.organization_id is null or listing.organization_id = organization.id)
    for update of listing;

  if not found then
    return query select 'invalid'::text, null::uuid, null::uuid, null::text;
    return;
  end if;

  select member.*
  into existing_member
  from public.organization_members member
  where member.organization_id = target_invite.organization_id
    and member.user_id = current_user_id
  for update;

  has_existing_member := found;

  if has_existing_member and existing_member.status <> 'active' then
    return query select 'invalid'::text, null::uuid, null::uuid, null::text;
    return;
  end if;

  insert into public.user_roles (user_id, role, verification_status)
  values (current_user_id, 'promoteur', 'verified')
  on conflict (user_id, role) do update
  set verification_status = 'verified',
      rejection_reason = null;

  if not has_existing_member then
    insert into public.organization_members (
      organization_id,
      user_id,
      role,
      status,
      accepted_at
    )
    values (
      target_invite.organization_id,
      current_user_id,
      'editeur',
      'active',
      now()
    );
  elsif existing_member.role = 'moderateur' then
    update public.organization_members
    set role = 'editeur'
    where id = existing_member.id;
  end if;

  update public.listings
  set organization_id = target_invite.organization_id,
      owner_id = null,
      steward_id = null
  where id = target_invite.listing_id;

  update public.promoter_invites
  set status = 'accepted',
      accepted_by = current_user_id,
      accepted_at = now()
  where id = target_invite.id;

  return query
  select
    'activated'::text,
    target_invite.organization_id,
    target_invite.listing_id,
    target_business_name;
end;
$$;

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
    )
    and not exists (
      select 1
      from public.account_deletion_requests request
      where request.user_id = (select auth.uid())
    );
$$;

revoke all on function app_private.current_user_has_completed_onboarding()
from public, anon, authenticated;
grant execute on function app_private.current_user_has_completed_onboarding()
to authenticated;

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
from public, anon, authenticated;

create or replace function public.prepare_account_deletion(
  p_user_id uuid,
  p_idempotency_key uuid
)
returns table (
  status text,
  effective_idempotency_key uuid
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  existing_request public.account_deletion_requests%rowtype;
  resolved_idempotency_key uuid := p_idempotency_key;
  has_storage_objects boolean := false;
begin
  -- Serialize distinct client keys for the same user. This keeps the partial
  -- unique index as a final invariant while allowing a restarted client to
  -- resume the already prepared server operation with a fresh key.
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(p_user_id::text, 0)
  );

  select request.*
  into existing_request
  from public.account_deletion_requests request
  where request.user_id = p_user_id
    and request.idempotency_key = p_idempotency_key
  for update;

  if found then
    resolved_idempotency_key := existing_request.idempotency_key;
  else
    select request.*
    into existing_request
    from public.account_deletion_requests request
    where request.user_id = p_user_id
      and request.status = 'prepared'
    for update;

    if found then
      resolved_idempotency_key := existing_request.idempotency_key;
    end if;
  end if;

  -- Revalidate blockers on every retry. A privileged concurrent process could
  -- have introduced a new ownership or Storage conflict after preparation.
  if exists (
    select 1
    from public.organizations organization
    where organization.primary_owner_id = p_user_id
  ) or exists (
    select 1
    from public.organization_members member
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

  -- Cleanup is deliberately replayable so a retry repairs any dependent data
  -- inserted by a privileged process while the request was prepared.
  perform app_private.cleanup_account_data(p_user_id);

  return query
  select
    case
      when existing_request.status = 'completed' then 'completed'::text
      else 'prepared'::text
    end,
    resolved_idempotency_key;
end;
$$;

create or replace function public.mark_account_deletion_completed(
  p_user_id uuid,
  p_idempotency_key uuid
)
returns table (status text)
language plpgsql
security definer
set search_path = ''
as $$
begin
  update public.account_deletion_requests request
  set status = 'completed',
      completed_at = coalesce(request.completed_at, now())
  where request.user_id = p_user_id
    and request.idempotency_key = p_idempotency_key
    and request.status = 'prepared';

  if not found and not exists (
    select 1
    from public.account_deletion_requests request
    where request.user_id = p_user_id
      and request.idempotency_key = p_idempotency_key
      and request.status = 'completed'
  ) then
    raise invalid_parameter_value using message = 'Unknown account deletion request';
  end if;

  return query select 'completed'::text;
end;
$$;

create or replace function app_private.reconcile_account_deletion_requests()
returns table (
  completed_count integer,
  purged_count integer
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  pending_request record;
  reconciled_count integer := 0;
  deleted_count integer := 0;
begin
  for pending_request in
    select request.user_id, request.idempotency_key
    from public.account_deletion_requests request
    where request.status = 'prepared'
      and not exists (
        select 1
        from auth.users account
        where account.id = request.user_id
      )
    for update skip locked
  loop
    perform app_private.cleanup_account_data(pending_request.user_id);

    update public.account_deletion_requests request
    set status = 'completed',
        completed_at = coalesce(request.completed_at, now())
    where request.user_id = pending_request.user_id
      and request.idempotency_key = pending_request.idempotency_key
      and request.status = 'prepared';

    if found then
      reconciled_count := reconciled_count + 1;
    end if;
  end loop;

  delete from public.account_deletion_requests request
  where request.status = 'completed'
    and request.completed_at < now() - interval '30 days';
  get diagnostics deleted_count = row_count;

  return query select reconciled_count, deleted_count;
end;
$$;

revoke all on function app_private.reconcile_account_deletion_requests()
from public, anon, authenticated, service_role;

do $$
begin
  if exists (
    select 1
    from pg_catalog.pg_available_extensions
    where name = 'pg_cron'
  ) then
    execute 'create extension if not exists pg_cron';
    execute $schedule$
      select cron.schedule(
        'kwabor-account-deletion-reconcile',
        '23 3 * * *',
        'select app_private.reconcile_account_deletion_requests()'
      )
    $schedule$;
  end if;
end;
$$;

revoke all on function public.create_promoter_invite(uuid, uuid, text, timestamptz)
from public, anon, authenticated;
revoke all on function public.preview_promoter_invite(text)
from public, anon, authenticated;
revoke all on function public.activate_promoter_invite(text)
from public, anon, authenticated;
revoke all on function public.prepare_account_deletion(uuid, uuid)
from public, anon, authenticated;
revoke all on function public.mark_account_deletion_completed(uuid, uuid)
from public, anon, authenticated;
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

grant execute on function public.create_promoter_invite(uuid, uuid, text, timestamptz)
to authenticated;
grant execute on function public.preview_promoter_invite(text)
to authenticated;
grant execute on function public.activate_promoter_invite(text)
to authenticated;
grant execute on function public.prepare_account_deletion(uuid, uuid)
to service_role;
grant execute on function public.mark_account_deletion_completed(uuid, uuid)
to service_role;
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
