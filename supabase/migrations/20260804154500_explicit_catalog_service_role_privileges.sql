-- Supabase CLI 2.111 no longer supplies the historical implicit table ACLs
-- that masked this catalog backend contract. Keep the trusted server role
-- usable on fresh environments without reducing the broader privileges that
-- ADR-0022 deliberately preserves on already-provisioned environments.
grant select, insert, update, delete
on table public.listings, public.listing_media
to service_role;

-- The listing taxonomy trigger is SECURITY INVOKER and validates every write
-- against the canonical category row before any privileged server workflow proceeds.
grant select
on table public.categories
to service_role;
