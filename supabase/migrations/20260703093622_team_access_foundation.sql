create schema if not exists app_private;

revoke all on schema app_private from public;
grant usage on schema app_private to authenticated;

create type public.organization_type as enum ('promoteur', 'institution', 'admin_kwabor', 'etablissement');
create type public.organization_verification_status as enum ('unverified', 'pending', 'verified', 'rejected', 'suspended');
create type public.organization_role as enum ('moderateur', 'editeur', 'gestionnaire', 'proprietaire');
create type public.organization_member_status as enum ('invited', 'active', 'suspended', 'removed');
create type public.organization_invite_status as enum ('pending', 'accepted', 'revoked', 'expired');

create table public.organizations (
  id uuid primary key default gen_random_uuid(),
  type public.organization_type not null,
  name text not null,
  slug text not null unique,
  verification_status public.organization_verification_status not null default 'pending',
  verified boolean generated always as (verification_status = 'verified') stored,
  primary_owner_id uuid not null references auth.users (id) on delete restrict,
  created_by uuid references auth.users (id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint organizations_name_not_blank check (length(trim(name)) > 0),
  constraint organizations_slug_format check (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$')
);

create table public.organization_members (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations (id) on delete cascade,
  user_id uuid not null references auth.users (id) on delete cascade,
  role public.organization_role not null,
  status public.organization_member_status not null default 'invited',
  invited_by uuid references public.organization_members (id) on delete set null,
  accepted_at timestamptz,
  suspended_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint organization_members_unique_user unique (organization_id, user_id),
  constraint organization_members_id_org_unique unique (id, organization_id),
  constraint organization_members_active_has_acceptance check (status <> 'active' or accepted_at is not null),
  constraint organization_members_suspended_has_timestamp check (status <> 'suspended' or suspended_at is not null)
);

create unique index organization_members_one_owner_idx
on public.organization_members (organization_id)
where role = 'proprietaire'
  and status in ('invited', 'active', 'suspended');

create index organization_members_user_status_idx
on public.organization_members (user_id, status);

create index organization_members_org_role_status_idx
on public.organization_members (organization_id, role, status);

create table public.organization_invites (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations (id) on delete cascade,
  email text not null,
  token_hash text not null unique,
  proposed_role public.organization_role not null,
  invited_by_member_id uuid not null,
  status public.organization_invite_status not null default 'pending',
  expires_at timestamptz not null,
  accepted_by uuid references auth.users (id) on delete set null,
  accepted_at timestamptz,
  revoked_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint organization_invites_inviter_same_org
    foreign key (invited_by_member_id, organization_id)
    references public.organization_members (id, organization_id)
    on delete restrict,
  constraint organization_invites_email_normalized check (
    email = lower(trim(email))
    and position('@' in email) > 1
  ),
  constraint organization_invites_token_hash_not_blank check (length(trim(token_hash)) >= 32),
  constraint organization_invites_expiry_after_creation check (expires_at > created_at),
  constraint organization_invites_accepted_fields check (
    status <> 'accepted'
    or (accepted_by is not null and accepted_at is not null)
  ),
  constraint organization_invites_revoked_fields check (
    status <> 'revoked'
    or revoked_at is not null
  )
);

create index organization_invites_org_status_idx
on public.organization_invites (organization_id, status);

create index organization_invites_email_status_idx
on public.organization_invites (email, status);

create table public.member_ad_budgets (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations (id) on delete cascade,
  member_id uuid not null,
  allocated_by_member_id uuid not null,
  period_start date not null,
  period_end date not null,
  allocated_xof integer not null,
  spent_xof integer not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint member_ad_budgets_member_same_org
    foreign key (member_id, organization_id)
    references public.organization_members (id, organization_id)
    on delete cascade,
  constraint member_ad_budgets_allocator_same_org
    foreign key (allocated_by_member_id, organization_id)
    references public.organization_members (id, organization_id)
    on delete restrict,
  constraint member_ad_budgets_no_self_allocation check (member_id <> allocated_by_member_id),
  constraint member_ad_budgets_period_valid check (period_end >= period_start),
  constraint member_ad_budgets_allocated_positive check (allocated_xof > 0),
  constraint member_ad_budgets_spent_valid check (spent_xof >= 0 and spent_xof <= allocated_xof),
  constraint member_ad_budgets_unique_member_period unique (member_id, period_start, period_end)
);

create index member_ad_budgets_org_period_idx
on public.member_ad_budgets (organization_id, period_start, period_end);

create index member_ad_budgets_allocator_period_idx
on public.member_ad_budgets (allocated_by_member_id, period_start, period_end);

create trigger organizations_touch_updated_at
before update on public.organizations
for each row execute function public.touch_updated_at();

create trigger organization_members_touch_updated_at
before update on public.organization_members
for each row execute function public.touch_updated_at();

create trigger organization_invites_touch_updated_at
before update on public.organization_invites
for each row execute function public.touch_updated_at();

create trigger member_ad_budgets_touch_updated_at
before update on public.member_ad_budgets
for each row execute function public.touch_updated_at();

create or replace function public.organization_role_rank(target_role public.organization_role)
returns integer
language sql
immutable
set search_path = public
as $$
  select case target_role
    when 'moderateur' then 10
    when 'editeur' then 20
    when 'gestionnaire' then 30
    when 'proprietaire' then 40
  end;
$$;

create or replace function app_private.current_user_membership_id(target_organization_id uuid)
returns uuid
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  select member.id
  from public.organization_members member
  join public.organizations organization
    on organization.id = member.organization_id
  where member.organization_id = target_organization_id
    and member.user_id = (select auth.uid())
    and member.status = 'active'
    and organization.verified
  order by public.organization_role_rank(member.role) desc
  limit 1;
$$;

create or replace function app_private.current_user_organization_role(target_organization_id uuid)
returns public.organization_role
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  select member.role
  from public.organization_members member
  join public.organizations organization
    on organization.id = member.organization_id
  where member.organization_id = target_organization_id
    and member.user_id = (select auth.uid())
    and member.status = 'active'
    and organization.verified
  order by public.organization_role_rank(member.role) desc
  limit 1;
$$;

create or replace function app_private.current_user_has_organization_role(
  target_organization_id uuid,
  minimum_role public.organization_role
)
returns boolean
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  select
    public.current_user_has_verified_role('admin')
    or coalesce(
      public.organization_role_rank(app_private.current_user_organization_role(target_organization_id))
        >= public.organization_role_rank(minimum_role),
      false
    );
$$;

create or replace function app_private.current_user_can_assign_organization_role(
  target_organization_id uuid,
  assigned_role public.organization_role
)
returns boolean
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
declare
  actor_role public.organization_role;
begin
  if public.current_user_has_verified_role('admin') then
    return true;
  end if;

  actor_role := app_private.current_user_organization_role(target_organization_id);

  if actor_role = 'proprietaire' then
    return assigned_role in ('gestionnaire', 'editeur', 'moderateur');
  end if;

  if actor_role = 'gestionnaire' then
    return assigned_role in ('editeur', 'moderateur');
  end if;

  return false;
end;
$$;

create or replace function app_private.member_budget_allocation_is_allowed(
  target_organization_id uuid,
  allocator_member_id uuid,
  target_member_id uuid,
  requested_xof integer,
  requested_period_start date,
  requested_period_end date
)
returns boolean
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
declare
  allocator record;
  target record;
  manager_available_xof integer;
  manager_allocated_xof integer;
begin
  if requested_xof <= 0 or requested_period_end < requested_period_start then
    return false;
  end if;

  if public.current_user_has_verified_role('admin') then
    return true;
  end if;

  select member.id, member.user_id, member.role, member.status
  into allocator
  from public.organization_members member
  where member.id = allocator_member_id
    and member.organization_id = target_organization_id;

  select member.id, member.role, member.status
  into target
  from public.organization_members member
  where member.id = target_member_id
    and member.organization_id = target_organization_id;

  if allocator.id is null
    or target.id is null
    or allocator.user_id <> (select auth.uid())
    or allocator.status <> 'active'
    or target.status <> 'active'
  then
    return false;
  end if;

  if allocator.role = 'proprietaire' then
    return target.role in ('gestionnaire', 'editeur');
  end if;

  if allocator.role = 'gestionnaire' and target.role = 'editeur' then
    select coalesce(sum(budget.allocated_xof - budget.spent_xof), 0)
    into manager_available_xof
    from public.member_ad_budgets budget
    where budget.member_id = allocator_member_id
      and budget.period_start = requested_period_start
      and budget.period_end = requested_period_end;

    select coalesce(sum(budget.allocated_xof), 0)
    into manager_allocated_xof
    from public.member_ad_budgets budget
    where budget.allocated_by_member_id = allocator_member_id
      and budget.period_start = requested_period_start
      and budget.period_end = requested_period_end;

    return requested_xof <= greatest(manager_available_xof - manager_allocated_xof, 0);
  end if;

  return false;
end;
$$;

revoke all on function app_private.current_user_membership_id(uuid) from public;
revoke all on function app_private.current_user_organization_role(uuid) from public;
revoke all on function app_private.current_user_has_organization_role(uuid, public.organization_role) from public;
revoke all on function app_private.current_user_can_assign_organization_role(uuid, public.organization_role) from public;
revoke all on function app_private.member_budget_allocation_is_allowed(
  uuid,
  uuid,
  uuid,
  integer,
  date,
  date
) from public;

grant execute on function app_private.current_user_membership_id(uuid) to authenticated;
grant execute on function app_private.current_user_organization_role(uuid) to authenticated;
grant execute on function app_private.current_user_has_organization_role(uuid, public.organization_role) to authenticated;
grant execute on function app_private.current_user_can_assign_organization_role(uuid, public.organization_role) to authenticated;
grant execute on function app_private.member_budget_allocation_is_allowed(
  uuid,
  uuid,
  uuid,
  integer,
  date,
  date
) to authenticated;

alter table public.organizations enable row level security;
alter table public.organization_members enable row level security;
alter table public.organization_invites enable row level security;
alter table public.member_ad_budgets enable row level security;

create policy "members read their organizations"
on public.organizations
for select
to authenticated
using (
  public.current_user_has_verified_role('admin')
  or app_private.current_user_membership_id(id) is not null
);

create policy "admins create organizations"
on public.organizations
for insert
to authenticated
with check (public.current_user_has_verified_role('admin'));

create policy "admins update organizations"
on public.organizations
for update
to authenticated
using (public.current_user_has_verified_role('admin'))
with check (public.current_user_has_verified_role('admin'));

create policy "members read organization members"
on public.organization_members
for select
to authenticated
using (
  public.current_user_has_verified_role('admin')
  or user_id = (select auth.uid())
  or app_private.current_user_has_organization_role(organization_id, 'gestionnaire')
);

create policy "managers invite organization members"
on public.organization_members
for insert
to authenticated
with check (
  (
    public.current_user_has_verified_role('admin')
    and status in ('invited', 'active')
  )
  or (
    status = 'invited'
    and app_private.current_user_can_assign_organization_role(organization_id, role)
  )
);

create policy "managers update organization members"
on public.organization_members
for update
to authenticated
using (
  public.current_user_has_verified_role('admin')
  or app_private.current_user_can_assign_organization_role(organization_id, role)
)
with check (
  public.current_user_has_verified_role('admin')
  or app_private.current_user_can_assign_organization_role(organization_id, role)
);

create policy "managers read organization invites"
on public.organization_invites
for select
to authenticated
using (
  public.current_user_has_verified_role('admin')
  or app_private.current_user_has_organization_role(organization_id, 'gestionnaire')
);

create policy "managers create organization invites"
on public.organization_invites
for insert
to authenticated
with check (
  status = 'pending'
  and invited_by_member_id = app_private.current_user_membership_id(organization_id)
  and app_private.current_user_can_assign_organization_role(organization_id, proposed_role)
);

create policy "managers update organization invites"
on public.organization_invites
for update
to authenticated
using (
  public.current_user_has_verified_role('admin')
  or (
    status = 'pending'
    and app_private.current_user_can_assign_organization_role(organization_id, proposed_role)
  )
)
with check (
  public.current_user_has_verified_role('admin')
  or app_private.current_user_can_assign_organization_role(organization_id, proposed_role)
);

create policy "members read organization budgets"
on public.member_ad_budgets
for select
to authenticated
using (
  public.current_user_has_verified_role('admin')
  or app_private.current_user_has_organization_role(organization_id, 'gestionnaire')
  or exists (
    select 1
    from public.organization_members member
    where member.id = member_ad_budgets.member_id
      and member.user_id = (select auth.uid())
      and member.status = 'active'
  )
);

create policy "owners and managers allocate ad budgets"
on public.member_ad_budgets
for insert
to authenticated
with check (
  app_private.member_budget_allocation_is_allowed(
    organization_id,
    allocated_by_member_id,
    member_id,
    allocated_xof,
    period_start,
    period_end
  )
);

grant select, insert, update on table public.organizations to authenticated;
grant select, insert, update on table public.organization_members to authenticated;
grant select, insert, update on table public.organization_invites to authenticated;
grant select, insert on table public.member_ad_budgets to authenticated;
