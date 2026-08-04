-- Supabase CLI 2.111 no longer supplies the historical implicit table ACLs
-- that masked this catalog backend contract. Keep the trusted server role
-- usable on fresh environments without reducing the broader privileges that
-- ADR-0022 deliberately preserves on already-provisioned environments.
grant select, insert, update, delete
on table public.listings, public.listing_media
to service_role;
