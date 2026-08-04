-- Keep taxonomy validation independent from the authorization hotfix. Existing
-- data drift may reject this migration, but must never prevent the preceding
-- credential, ACL and membership guardrails from deploying.
set lock_timeout = '5s';

-- A category is the source of truth for a listing classification. Validation is
-- deliberately fail-closed so pre-existing drift requires explicit cleanup.
alter table public.categories
add constraint categories_taxonomy_key
unique (
  id,
  listing_type,
  subtype,
  default_listing_class
);

alter table public.listings
add constraint listings_category_taxonomy_fkey
foreign key (
  category_id,
  type,
  subtype,
  listing_class
)
references public.categories (
  id,
  listing_type,
  subtype,
  default_listing_class
)
not valid;

alter table public.listings
validate constraint listings_category_taxonomy_fkey;

create or replace function app_private.enforce_listing_taxonomy_and_actor_role()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  selected_type public.listing_type;
  selected_subtype text;
  selected_class public.listing_class;
begin
  select
    category.listing_type,
    category.subtype,
    category.default_listing_class
  into
    selected_type,
    selected_subtype,
    selected_class
  from public.categories category
  where category.id = new.category_id;

  if not found
    or row(new.type, new.subtype, new.listing_class)
      is distinct from row(selected_type, selected_subtype, selected_class)
  then
    raise exception 'Listing type, subtype and class must match its category'
      using errcode = '23514';
  end if;

  if current_user <> 'authenticated'
    or new.organization_id is not null
    or public.current_user_has_verified_role('admin')
  then
    return new;
  end if;

  if new.listing_class = 'patrimonial'
    and public.current_user_has_verified_role('institution')
  then
    return new;
  end if;

  if new.listing_class = 'evenementiel'
    and (
      public.current_user_has_verified_role('guide')
      or public.current_user_has_verified_role('promoteur')
    )
  then
    return new;
  end if;

  if new.listing_class = 'commercial'
    and (
      public.current_user_has_verified_role('promoteur')
      or (
        new.type = 'etablissement'
        and new.subtype = 'guide'
        and public.current_user_has_verified_role('guide')
      )
    )
  then
    return new;
  end if;

  raise insufficient_privilege using
    message = 'Verified role cannot manage this listing classification';
end;
$$;

revoke all on function app_private.enforce_listing_taxonomy_and_actor_role()
from public, anon, authenticated;

create trigger listings_enforce_taxonomy_and_actor_role
before insert or update on public.listings
for each row execute function app_private.enforce_listing_taxonomy_and_actor_role();

reset lock_timeout;
