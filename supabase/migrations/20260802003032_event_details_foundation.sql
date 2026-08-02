set lock_timeout = '5s';

create type public.ticket_type as enum ('gratuit', 'payant');

create table public.event_details (
  listing_id uuid primary key references public.listings (id) on delete cascade,
  category text not null,
  start_at timestamptz not null,
  end_at timestamptz,
  venue_listing_id uuid references public.listings (id) on delete restrict,
  organizer_name text not null,
  organizer_contact text not null,
  ticket_type public.ticket_type not null,
  ticket_url text,
  capacity integer,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint event_details_category_not_blank check (btrim(category) <> ''),
  constraint event_details_start_at_finite check (isfinite(start_at)),
  constraint event_details_end_at_valid check (
    end_at is null
    or (isfinite(end_at) and end_at >= start_at)
  ),
  constraint event_details_venue_not_self check (
    venue_listing_id is null or venue_listing_id <> listing_id
  ),
  constraint event_details_organizer_name_not_blank check (btrim(organizer_name) <> ''),
  constraint event_details_organizer_contact_valid check (
    btrim(organizer_contact) ~ '^\+[1-9][0-9]{7,14}$'
    or btrim(organizer_contact) ~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$'
  ),
  constraint event_details_ticket_url_valid check (
    ticket_url is null
    or (
      ticket_url = btrim(ticket_url)
      and ticket_url ~ '^https://[^[:space:]]+$'
    )
  ),
  constraint event_details_capacity_positive check (
    capacity is null or capacity > 0
  )
);

do $$
begin
  if exists (
    select 1
    from public.listings listing
    where listing.type = 'evenement'
      and listing.status in ('en_attente', 'publie')
      and not exists (
        select 1
        from public.event_details detail
        where detail.listing_id = listing.id
      )
  ) then
    raise exception 'Existing active event listings require event details before this migration'
      using
        errcode = '23514',
        hint = 'Move affected listings to brouillon or prepare a reviewed backfill before retrying.';
  end if;
end;
$$;

create index event_details_start_listing_idx
on public.event_details (start_at, listing_id);

create index event_details_venue_listing_idx
on public.event_details (venue_listing_id)
where venue_listing_id is not null;

create trigger event_details_touch_updated_at
before update on public.event_details
for each row execute function public.touch_updated_at();

create or replace function app_private.enforce_event_detail_references()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  parent_subtype text;
  parent_address text;
  parent_lat numeric;
  parent_lng numeric;
  parent_status public.listing_status;
  venue_status public.listing_status;
begin
  new.category := btrim(new.category);
  new.organizer_name := btrim(new.organizer_name);
  new.organizer_contact := btrim(new.organizer_contact);
  new.ticket_url := case
    when new.ticket_url is null then null
    else btrim(new.ticket_url)
  end;

  if (select auth.role()) = 'authenticated'
    and (
      not app_private.current_user_has_completed_onboarding()
      or not public.current_user_can_manage_listing(new.listing_id)
    )
  then
    raise insufficient_privilege using
      message = 'Event details require a completed manager of the parent listing';
  end if;

  select
    listing.subtype,
    listing.address,
    listing.lat,
    listing.lng,
    listing.status
  into
    parent_subtype,
    parent_address,
    parent_lat,
    parent_lng,
    parent_status
  from public.listings listing
  where listing.id = new.listing_id
    and listing.type = 'evenement'
  for update;

  if not found or parent_subtype <> new.category then
    raise exception 'Event details must match an event listing category'
      using errcode = '23514';
  end if;

  if new.venue_listing_id is null then
    if parent_address is null
      or btrim(parent_address) = ''
      or parent_lat is null
      or parent_lng is null
    then
      raise exception 'An event requires a venue listing or an address with coordinates'
        using errcode = '23514';
    end if;
  else
    select venue.status
    into venue_status
    from public.listings venue
    where venue.id = new.venue_listing_id
      and venue.type in ('lieu', 'etablissement')
      and (
        (select auth.role()) is distinct from 'authenticated'
        or venue.status = 'publie'
        or public.current_user_can_manage_listing(venue.id)
      )
    for update;

    if not found then
      raise exception 'An event venue must reference an accessible place or establishment'
        using errcode = '23514';
    end if;

    if parent_status in ('en_attente', 'publie')
      and venue_status <> 'publie'
    then
      raise exception 'An event under review or published requires a published venue'
        using errcode = '23514';
    end if;
  end if;

  return new;
end;
$$;

comment on function app_private.enforce_event_detail_references() is
  'Trigger-only integrity guard. It locks parent and venue listings to serialize reference changes, while explicit manager and venue visibility checks preserve the authenticated RLS boundary.';

revoke all on function app_private.enforce_event_detail_references()
from public, anon, authenticated, service_role;

create trigger event_details_enforce_references
before insert or update
on public.event_details
for each row execute function app_private.enforce_event_detail_references();

create or replace function app_private.enforce_listing_event_detail_consistency()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  detail_category text;
  detail_venue_listing_id uuid;
  detail_venue_status public.listing_status;
begin
  select
    detail.category,
    detail.venue_listing_id
  into
    detail_category,
    detail_venue_listing_id
  from public.event_details detail
  where detail.listing_id = new.id;

  if new.status <> 'publie' and exists (
    select 1
    from public.event_details detail
    join public.listings event_listing on event_listing.id = detail.listing_id
    where detail.venue_listing_id = new.id
      and event_listing.status in ('en_attente', 'publie')
  ) then
    raise exception 'A venue used by an event under review or published must remain published'
      using errcode = '23514';
  end if;

  if new.type = 'evenement' and exists (
    select 1
    from public.event_details detail
    where detail.venue_listing_id = new.id
  ) then
    raise exception 'A listing used as an event venue cannot become an event'
      using errcode = '23514';
  end if;

  if new.type = 'evenement'
    and new.status in ('en_attente', 'publie')
    and not found
  then
    raise exception 'An event must have details before review or publication'
      using errcode = '23514';
  end if;

  if found then
    if new.type <> 'evenement' or new.subtype <> detail_category then
      raise exception 'A listing with event details must remain an event with the same category'
        using errcode = '23514';
    end if;

    if detail_venue_listing_id is null
      and (
        new.address is null
        or btrim(new.address) = ''
        or new.lat is null
        or new.lng is null
      )
    then
      raise exception 'An event without a venue listing must keep an address and coordinates'
        using errcode = '23514';
    end if;

    if new.status in ('en_attente', 'publie')
      and detail_venue_listing_id is not null
    then
      select venue.status
      into detail_venue_status
      from public.listings venue
      where venue.id = detail_venue_listing_id
      for update;

      if not found or detail_venue_status <> 'publie' then
        raise exception 'An event under review or published requires a published venue'
          using errcode = '23514';
      end if;
    end if;
  end if;

  return new;
end;
$$;

comment on function app_private.enforce_listing_event_detail_consistency() is
  'Trigger-only integrity guard. It requires details before review/publication and inspects dependencies hidden by RLS; the fixed search path and revoked execute privilege keep it out of the Data API.';

revoke all on function app_private.enforce_listing_event_detail_consistency()
from public, anon, authenticated, service_role;

create trigger listings_require_event_details_before_insert
before insert
on public.listings
for each row execute function app_private.enforce_listing_event_detail_consistency();

create trigger listings_preserve_event_detail_consistency
before update of type, subtype, address, lat, lng, status
on public.listings
for each row execute function app_private.enforce_listing_event_detail_consistency();

create or replace function app_private.prevent_required_event_detail_removal()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  parent_type public.listing_type;
  parent_status public.listing_status;
begin
  select
    listing.type,
    listing.status
  into
    parent_type,
    parent_status
  from public.listings listing
  where listing.id = old.listing_id
  for update;

  if found
    and parent_type = 'evenement'
    and parent_status in ('en_attente', 'publie')
  then
    raise exception 'An event under review or published must keep its event details'
      using errcode = '23514';
  end if;

  if tg_op = 'DELETE' then
    return old;
  end if;

  return new;
end;
$$;

comment on function app_private.prevent_required_event_detail_removal() is
  'Trigger-only integrity guard preventing direct removal or reassignment of required event details while preserving parent cascade deletion.';

revoke all on function app_private.prevent_required_event_detail_removal()
from public, anon, authenticated, service_role;

create trigger event_details_preserve_required_parent_before_delete
before delete
on public.event_details
for each row execute function app_private.prevent_required_event_detail_removal();

create trigger event_details_preserve_required_parent_before_move
before update of listing_id
on public.event_details
for each row execute function app_private.prevent_required_event_detail_removal();

alter table public.event_details enable row level security;

create policy "anonymous users read published event details"
on public.event_details
for select
to anon
using (
  exists (
    select 1
    from public.listings listing
    where listing.id = event_details.listing_id
      and listing.status = 'publie'
  )
);

create policy "authenticated users read permitted event details"
on public.event_details
for select
to authenticated
using (
  exists (
    select 1
    from public.listings listing
    where listing.id = event_details.listing_id
      and listing.status = 'publie'
  )
  or public.current_user_can_manage_listing(listing_id)
);

create policy "event managers create event details"
on public.event_details
for insert
to authenticated
with check (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_can_manage_listing(listing_id)
  and exists (
    select 1
    from public.listings listing
    where listing.id = event_details.listing_id
      and (
        listing.status in ('brouillon', 'en_attente')
        or (select public.current_user_has_verified_role('admin'))
      )
  )
);

create policy "event managers update event details"
on public.event_details
for update
to authenticated
using (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_can_manage_listing(listing_id)
  and exists (
    select 1
    from public.listings listing
    where listing.id = event_details.listing_id
      and (
        listing.status in ('brouillon', 'en_attente')
        or (select public.current_user_has_verified_role('admin'))
      )
  )
)
with check (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_can_manage_listing(listing_id)
  and exists (
    select 1
    from public.listings listing
    where listing.id = event_details.listing_id
      and (
        listing.status in ('brouillon', 'en_attente')
        or (select public.current_user_has_verified_role('admin'))
      )
  )
);

create policy "event managers delete event details"
on public.event_details
for delete
to authenticated
using (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_can_manage_listing(listing_id)
  and exists (
    select 1
    from public.listings listing
    where listing.id = event_details.listing_id
      and listing.status = 'brouillon'
  )
);

revoke all on table public.event_details
from public, anon, authenticated, service_role;

grant select on table public.event_details
to anon, authenticated;

grant insert (
  listing_id,
  category,
  start_at,
  end_at,
  venue_listing_id,
  organizer_name,
  organizer_contact,
  ticket_type,
  ticket_url,
  capacity
) on table public.event_details to authenticated;

grant update (
  category,
  start_at,
  end_at,
  venue_listing_id,
  organizer_name,
  organizer_contact,
  ticket_type,
  ticket_url,
  capacity
) on table public.event_details to authenticated;

grant delete on table public.event_details to authenticated;
grant select, insert, update, delete on table public.event_details to service_role;

reset lock_timeout;
