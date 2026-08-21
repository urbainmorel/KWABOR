-- Keep new application objects private until a migration grants the exact API
-- surface required by RLS. Supabase-hosted defaults otherwise expose every
-- privilege to anon and authenticated for objects created by postgres.
alter default privileges for role postgres in schema public
revoke all privileges on tables from public, anon, authenticated;

alter default privileges for role postgres in schema public
revoke all privileges on sequences from public, anon, authenticated;

-- PostgreSQL grants EXECUTE on new functions to PUBLIC globally. A
-- schema-scoped revoke cannot override that built-in global default.
alter default privileges for role postgres
revoke all privileges on functions from public;

alter default privileges for role postgres in schema public
revoke all privileges on functions from public, anon, authenticated;

-- Hosted defaults predate the explicit team grants. Rebuild the four affected
-- tables from zero so TRUNCATE, REFERENCES, TRIGGER and DELETE never leak to
-- client roles. service_role retains its platform-managed privileges.
revoke all privileges on table
  public.organizations,
  public.organization_members,
  public.organization_invites,
  public.member_ad_budgets
from public, anon, authenticated;

grant select, insert, update
on table public.organizations
to authenticated;

grant select
on table public.organization_members
to authenticated;

grant update (role)
on table public.organization_members
to authenticated;

grant select, insert, update
on table public.organization_invites
to authenticated;

grant select, insert
on table public.member_ad_budgets
to authenticated;
