set lock_timeout = '5s';

create type public.catalog_detail_variant as enum (
  'place',
  'lodging',
  'food',
  'nightlife',
  'guide',
  'event'
);

create type public.listing_media_kind as enum ('image', 'video');

alter table public.listings
add column is_claimable boolean generated always as (
  listing_class in ('commercial', 'evenementiel')
  and owner_id is null
  and organization_id is null
) stored;

comment on column public.listings.is_claimable is
  'Public, generated authority signal. True only for unowned commercial or event listings; authority UUIDs remain private.';

alter table public.categories
add column detail_variant public.catalog_detail_variant;

update public.categories
set detail_variant = case
  when listing_type = 'lieu' then 'place'::public.catalog_detail_variant
  when listing_type = 'evenement' then 'event'::public.catalog_detail_variant
  when listing_type = 'etablissement' and subtype = 'hotel' then 'lodging'::public.catalog_detail_variant
  when listing_type = 'etablissement' and subtype = 'restaurant' then 'food'::public.catalog_detail_variant
  when listing_type = 'etablissement' and subtype = 'guide' then 'guide'::public.catalog_detail_variant
  else null
end;

do $$
begin
  if exists (
    select 1
    from public.categories category
    where category.detail_variant is null
  ) then
    raise exception 'Existing categories require an explicit catalog detail variant'
      using
        errcode = '23514',
        hint = 'Review every unmapped establishment subtype and backfill detail_variant before retrying.';
  end if;
end;
$$;

alter table public.categories
alter column detail_variant set not null;

alter table public.categories
add constraint categories_detail_variant_matches_type check (
  (
    listing_type = 'lieu'
    and detail_variant = 'place'
    and default_listing_class in ('patrimonial', 'commercial')
  )
  or (
    listing_type = 'evenement'
    and detail_variant = 'event'
    and default_listing_class = 'evenementiel'
  )
  or (
    listing_type = 'etablissement'
    and detail_variant in ('lodging', 'food', 'nightlife', 'guide')
    and default_listing_class = 'commercial'
  )
);

create or replace function app_private.catalog_opening_hours_is_valid(value jsonb)
returns boolean
language plpgsql
immutable
set search_path = ''
as $$
declare
  day_key text;
  day_entry jsonb;
  interval_entry jsonb;
  day_status text;
  opens_minute integer;
  closes_minute integer;
  closes_next_day boolean;
  previous_closes_minute integer;
  previous_was_overnight boolean;
begin
  if value is null or jsonb_typeof(value) <> 'object' then
    return false;
  end if;

  if value = '{}'::jsonb then
    return true;
  end if;

  if (
    select array_agg(key order by key)
    from jsonb_object_keys(value) key
  ) is distinct from array[
    'friday',
    'monday',
    'saturday',
    'sunday',
    'thursday',
    'tuesday',
    'wednesday'
  ]::text[] then
    return false;
  end if;

  foreach day_key in array array[
    'monday',
    'tuesday',
    'wednesday',
    'thursday',
    'friday',
    'saturday',
    'sunday'
  ] loop
    day_entry := value -> day_key;
    if jsonb_typeof(day_entry) <> 'object'
      or (
        select array_agg(key order by key)
        from jsonb_object_keys(day_entry) key
      ) is distinct from array['periods', 'status']::text[]
      or jsonb_typeof(day_entry -> 'status') <> 'string'
      or jsonb_typeof(day_entry -> 'periods') <> 'array'
    then
      return false;
    end if;

    day_status := day_entry ->> 'status';
    if day_status not in ('closed', 'open_24_hours', 'periods') then
      return false;
    end if;

    if day_status in ('closed', 'open_24_hours') then
      if jsonb_array_length(day_entry -> 'periods') <> 0 then
        return false;
      end if;
      continue;
    end if;

    if jsonb_array_length(day_entry -> 'periods') = 0 then
      return false;
    end if;

    previous_closes_minute := null;
    previous_was_overnight := false;

    for interval_entry in
      select item from jsonb_array_elements(day_entry -> 'periods') item
    loop
      if jsonb_typeof(interval_entry) <> 'object'
        or (
          select array_agg(key order by key)
          from jsonb_object_keys(interval_entry) key
        ) is distinct from array['closes_minute', 'closes_next_day', 'opens_minute']::text[]
        or jsonb_typeof(interval_entry -> 'opens_minute') <> 'number'
        or jsonb_typeof(interval_entry -> 'closes_minute') <> 'number'
        or jsonb_typeof(interval_entry -> 'closes_next_day') <> 'boolean'
        or (interval_entry ->> 'opens_minute') !~ '^(0|[1-9][0-9]{0,3})$'
        or (interval_entry ->> 'closes_minute') !~ '^(0|[1-9][0-9]{0,3})$'
      then
        return false;
      end if;

      opens_minute := (interval_entry ->> 'opens_minute')::integer;
      closes_minute := (interval_entry ->> 'closes_minute')::integer;
      closes_next_day := (interval_entry ->> 'closes_next_day')::boolean;
      if opens_minute not between 0 and 1439
        or closes_minute not between 0 and 1439
        or (not closes_next_day and closes_minute <= opens_minute)
        or (closes_next_day and closes_minute > opens_minute)
        or previous_was_overnight
        or (
          previous_closes_minute is not null
          and opens_minute < previous_closes_minute
        )
      then
        return false;
      end if;

      previous_closes_minute := closes_minute;
      previous_was_overnight := closes_next_day;
    end loop;
  end loop;

  return true;
end;
$$;

create or replace function app_private.catalog_text_has_mobile_whitespace(value text)
returns boolean
language sql
immutable
strict
set search_path = ''
as $$
  with characters as (
    select ascii(substr(value, character_index, 1)) as code_point
    from generate_series(1, char_length(value)) as indexes(character_index)
  )
  select exists (
    select 1
    from characters
    where code_point between 9 and 13
      or code_point between 28 and 32
      or code_point in (160, 5760, 8232, 8233, 8239, 8287, 12288)
      or code_point between 8192 and 8202
  );
$$;

create or replace function app_private.catalog_https_url_is_valid(value text)
returns boolean
language plpgsql
immutable
strict
set search_path = ''
as $$
declare
  authority text;
  host text;
begin
  if value <> btrim(value)
    or octet_length(value) > 2048
    or value !~ '^https://'
    or app_private.catalog_text_has_mobile_whitespace(value)
    or position('#' in value) > 0
    or position(chr(92) in value) > 0
    or position('%' in regexp_replace(value, '%[0-9A-Fa-f]{2}', '', 'g')) > 0
  then
    return false;
  end if;

  authority := substring(value from 9);
  authority := split_part(authority, '/', 1);
  authority := split_part(authority, '?', 1);

  if authority = '' or position('@' in authority) > 0 then
    return false;
  end if;

  if right(authority, 4) = ':443' then
    host := left(authority, char_length(authority) - 4);
  elsif position(':' in authority) > 0 then
    return false;
  else
    host := authority;
  end if;

  if char_length(host) > 253
    or host <> lower(host)
    or host !~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$'
    or host ~ '^[0-9.]+$'
    or host = 'localhost'
    or host like '%.localhost'
    or host = 'local'
    or host like '%.local'
    or host = 'internal'
    or host like '%.internal'
    or host = 'lan'
    or host like '%.lan'
    or host = 'home.arpa'
    or host like '%.home.arpa'
  then
    return false;
  end if;

  return true;
end;
$$;

create or replace function app_private.catalog_socials_are_valid(value jsonb)
returns boolean
language plpgsql
immutable
set search_path = ''
as $$
declare
  platform text;
  encoded_url jsonb;
  url text;
begin
  if value is null or jsonb_typeof(value) <> 'object' then
    return false;
  end if;

  for platform, encoded_url in select key, item from jsonb_each(value) as link(key, item) loop
    if platform not in ('instagram', 'facebook', 'tiktok', 'youtube', 'x', 'linkedin')
      or jsonb_typeof(encoded_url) <> 'string'
    then
      return false;
    end if;

    url := encoded_url #>> '{}';
    if not app_private.catalog_https_url_is_valid(url) then
      return false;
    end if;
  end loop;

  return true;
end;
$$;

create or replace function app_private.catalog_text_has_canonical_edges(value text)
returns boolean
language sql
immutable
strict
set search_path = ''
as $$
  select
    value <> ''
    and not app_private.catalog_text_has_mobile_whitespace(left(value, 1))
    and not app_private.catalog_text_has_mobile_whitespace(right(value, 1));
$$;

create or replace function app_private.catalog_timestamp_is_mobile_safe(value timestamptz)
returns boolean
language sql
immutable
strict
set search_path = ''
as $$
  select
    value >= '0001-01-01 00:00:00+00'::timestamptz
    and value < '10000-01-01 00:00:00+00'::timestamptz;
$$;

create or replace function app_private.catalog_tags_are_valid(value text[])
returns boolean
language sql
immutable
set search_path = ''
as $$
  select
    value is not null
    and coalesce(array_ndims(value), 1) = 1
    and cardinality(value) <= 10
    and cardinality(value) = (
      select count(distinct tag)
      from unnest(value) tag
    )
    and coalesce(
      (
        select bool_and(
          app_private.catalog_text_has_canonical_edges(tag)
          and char_length(tag) between 1 and 24
          and tag !~ '[[:cntrl:]]'
        )
        from unnest(value) tag
      ),
      true
    );
$$;

create or replace function app_private.catalog_text_array_is_valid(
  value text[],
  require_non_empty boolean
)
returns boolean
language sql
immutable
set search_path = ''
as $$
  select
    value is not null
    and coalesce(array_ndims(value), 1) = 1
    and (not require_non_empty or cardinality(value) > 0)
    and cardinality(value) = (
      select count(distinct item)
      from unnest(value) item
    )
    and coalesce(
      (
        select bool_and(
          app_private.catalog_text_has_canonical_edges(item)
          and item !~ '[[:cntrl:]]'
        )
        from unnest(value) item
      ),
      true
    );
$$;

create or replace function app_private.catalog_point_is_within_benin(
  latitude numeric,
  longitude numeric
)
returns boolean
language sql
immutable
strict
set search_path = ''
as $$
  select extensions.st_covers(
    extensions.st_geomfromtext(
      'POLYGON((2.704951 6.466417,2.762828 6.776321,2.769495 7.052194,2.721967 7.410584,2.729674 7.761267,2.699164 8.307426,2.739710 8.751015,2.781859 9.038032,3.167440 9.317639,3.291024 9.658551,3.528666 9.855088,3.686358 10.180234,3.712871 10.437187,3.845650 10.677744,3.697103 11.107366,3.521014 11.774246,3.288455 11.917360,2.884896 12.353014,2.689956 12.386787,2.558418 12.282378,2.461408 12.240450,2.467163 12.021768,2.412082 11.863922,2.203811 11.595094,1.509006 11.457502,1.348529 11.370687,1.271810 11.285154,1.143560 11.175813,1.110676 11.021256,0.893165 10.904647,1.339223 9.940176,1.330180 9.560360,1.618742 8.895270,1.612165 8.383093,1.649847 7.418291,1.623674 6.842812,1.609699 6.648433,1.689444 6.554712,1.793030 6.396044,1.663136 6.268617,1.631896 6.259573,1.649596 6.215779,1.743680 6.240868,1.806402 6.253413,2.535530 6.333301,2.704951 6.466417))',
      4326
    ),
    extensions.st_setsrid(extensions.st_makepoint(longitude, latitude), 4326)
  );
$$;

revoke all on function app_private.catalog_opening_hours_is_valid(jsonb)
from public, anon, authenticated;
revoke all on function app_private.catalog_https_url_is_valid(text)
from public, anon, authenticated;
revoke all on function app_private.catalog_socials_are_valid(jsonb)
from public, anon, authenticated;
revoke all on function app_private.catalog_text_has_mobile_whitespace(text)
from public, anon, authenticated;
revoke all on function app_private.catalog_text_has_canonical_edges(text)
from public, anon, authenticated;
revoke all on function app_private.catalog_timestamp_is_mobile_safe(timestamptz)
from public, anon, authenticated;
revoke all on function app_private.catalog_tags_are_valid(text[])
from public, anon, authenticated;
revoke all on function app_private.catalog_text_array_is_valid(text[], boolean)
from public, anon, authenticated;
revoke all on function app_private.catalog_point_is_within_benin(numeric, numeric)
from public, anon, authenticated;

grant execute on function app_private.catalog_opening_hours_is_valid(jsonb)
to authenticated, service_role;
grant execute on function app_private.catalog_https_url_is_valid(text)
to authenticated, service_role;
grant execute on function app_private.catalog_socials_are_valid(jsonb)
to authenticated, service_role;
grant execute on function app_private.catalog_text_has_mobile_whitespace(text)
to authenticated, service_role;
grant execute on function app_private.catalog_text_has_canonical_edges(text)
to authenticated, service_role;
grant execute on function app_private.catalog_timestamp_is_mobile_safe(timestamptz)
to authenticated, service_role;
grant execute on function app_private.catalog_tags_are_valid(text[])
to authenticated, service_role;
grant execute on function app_private.catalog_text_array_is_valid(text[], boolean)
to authenticated, service_role;
grant execute on function app_private.catalog_point_is_within_benin(numeric, numeric)
to authenticated, service_role;

alter table public.listings
add constraint listings_v1_name_length check (
  char_length(btrim(name)) between 3 and 80
) not valid;

alter table public.listings
add constraint listings_v1_description_length check (
  char_length(btrim(description)) between 40 and 1500
) not valid;

alter table public.listings
add constraint listings_v1_text_canonical check (
  app_private.catalog_text_has_canonical_edges(name)
  and app_private.catalog_text_has_canonical_edges(slug)
  and slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
  and app_private.catalog_text_has_canonical_edges(description)
  and (
    district is null
    or app_private.catalog_text_has_canonical_edges(district)
  )
  and (
    address is null
    or app_private.catalog_text_has_canonical_edges(address)
  )
) not valid;

alter table public.cities
add constraint cities_v1_name_canonical check (
  app_private.catalog_text_has_canonical_edges(name)
) not valid;

alter table public.cities
add constraint cities_v1_id_canonical check (
  app_private.catalog_text_has_canonical_edges(id)
  and id ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
) not valid;

alter table public.categories
add constraint categories_v1_name_key_canonical check (
  app_private.catalog_text_has_canonical_edges(name_key)
) not valid;

alter table public.categories
add constraint categories_v1_id_canonical check (
  app_private.catalog_text_has_canonical_edges(id)
  and id ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
) not valid;

alter table public.categories
add constraint categories_v1_subtype_canonical check (
  app_private.catalog_text_has_canonical_edges(subtype)
  and subtype ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
) not valid;

alter table public.listings
add constraint listings_published_at_finite check (
  published_at is null
  or app_private.catalog_timestamp_is_mobile_safe(published_at)
) not valid;

alter table public.listings
add constraint listings_sponsored_until_mobile_safe check (
  sponsored_until is null
  or app_private.catalog_timestamp_is_mobile_safe(sponsored_until)
) not valid;

alter table public.listings
add constraint listings_location_pair check (
  (lat is null and lng is null)
  or (lat is not null and lng is not null)
) not valid;

alter table public.listings
add constraint listings_location_within_benin check (
  lat is null
  or app_private.catalog_point_is_within_benin(lat, lng)
) not valid;

alter table public.listings
add constraint listings_contact_phone_e164_benin check (
  contact_phone is null
  or (
    app_private.catalog_text_has_canonical_edges(contact_phone)
    and contact_phone ~ '^\+229[0-9]{5,12}$'
  )
) not valid;

alter table public.listings
add constraint listings_contact_whatsapp_e164_benin check (
  contact_whatsapp is null
  or (
    app_private.catalog_text_has_canonical_edges(contact_whatsapp)
    and contact_whatsapp ~ '^\+229[0-9]{5,12}$'
  )
) not valid;

alter table public.listings
add constraint listings_external_url_https check (
  external_url is null
  or app_private.catalog_https_url_is_valid(external_url)
) not valid;

alter table public.listings
add constraint listings_email_valid check (
  email is null
  or (
    app_private.catalog_text_has_canonical_edges(email)
    and not app_private.catalog_text_has_mobile_whitespace(email)
    and email ~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$'
  )
) not valid;

alter table public.listings
add constraint listings_opening_hours_contract check (
  app_private.catalog_opening_hours_is_valid(opening_hours)
) not valid;

alter table public.listings
add constraint listings_socials_contract check (
  app_private.catalog_socials_are_valid(socials)
) not valid;

alter table public.listings
add constraint listings_tags_contract check (
  app_private.catalog_tags_are_valid(tags)
) not valid;

alter table public.listings validate constraint listings_v1_name_length;
alter table public.listings validate constraint listings_v1_description_length;
alter table public.listings validate constraint listings_v1_text_canonical;
alter table public.cities validate constraint cities_v1_name_canonical;
alter table public.cities validate constraint cities_v1_id_canonical;
alter table public.categories validate constraint categories_v1_name_key_canonical;
alter table public.categories validate constraint categories_v1_id_canonical;
alter table public.categories validate constraint categories_v1_subtype_canonical;
alter table public.listings validate constraint listings_published_at_finite;
alter table public.listings validate constraint listings_sponsored_until_mobile_safe;
alter table public.listings validate constraint listings_location_pair;
alter table public.listings validate constraint listings_location_within_benin;
alter table public.listings validate constraint listings_contact_phone_e164_benin;
alter table public.listings validate constraint listings_contact_whatsapp_e164_benin;
alter table public.listings validate constraint listings_external_url_https;
alter table public.listings validate constraint listings_email_valid;
alter table public.listings validate constraint listings_opening_hours_contract;
alter table public.listings validate constraint listings_socials_contract;
alter table public.listings validate constraint listings_tags_contract;

alter table public.event_details
drop constraint event_details_ticket_url_valid;

alter table public.event_details
add constraint event_details_ticket_url_valid check (
  ticket_url is null
  or app_private.catalog_https_url_is_valid(ticket_url)
) not valid;

alter table public.event_details
validate constraint event_details_ticket_url_valid;

alter table public.event_details
drop constraint event_details_start_at_finite;

alter table public.event_details
add constraint event_details_start_at_finite check (
  app_private.catalog_timestamp_is_mobile_safe(start_at)
) not valid;

alter table public.event_details
drop constraint event_details_end_at_valid;

alter table public.event_details
add constraint event_details_end_at_valid check (
  end_at is null
  or (
    app_private.catalog_timestamp_is_mobile_safe(end_at)
    and end_at >= start_at
  )
) not valid;

alter table public.event_details
add constraint event_details_v1_text_canonical check (
  app_private.catalog_text_has_canonical_edges(category)
  and app_private.catalog_text_has_canonical_edges(organizer_name)
  and app_private.catalog_text_has_canonical_edges(organizer_contact)
  and not app_private.catalog_text_has_mobile_whitespace(organizer_contact)
) not valid;

alter table public.event_details validate constraint event_details_start_at_finite;
alter table public.event_details validate constraint event_details_end_at_valid;
alter table public.event_details validate constraint event_details_v1_text_canonical;

alter table public.listing_media
add column kind public.listing_media_kind not null default 'image';

alter table public.listing_media
add constraint listing_media_url_https check (
  app_private.catalog_https_url_is_valid(url)
) not valid;

alter table public.listing_media
add constraint listing_media_cover_is_image check (
  not is_cover or kind = 'image'
) not valid;

alter table public.listing_media
add constraint listing_media_alt_canonical check (
  app_private.catalog_text_has_canonical_edges(alt)
) not valid;

alter table public.listing_media validate constraint listing_media_url_https;
alter table public.listing_media validate constraint listing_media_cover_is_image;
alter table public.listing_media validate constraint listing_media_alt_canonical;

do $$
begin
  if exists (
    select 1
    from public.listing_media media
    where media.is_cover
    group by media.listing_id
    having count(*) > 1
  ) then
    raise exception 'Existing listings contain more than one official cover media'
      using errcode = '23514';
  end if;
end;
$$;

do $$
begin
  if exists (
    select 1
    from public.listing_media media
    group by media.listing_id, media.display_order
    having count(*) > 1
  ) then
    raise exception 'Existing official media contain duplicate display orders for a listing'
      using
        errcode = '23505',
        hint = 'Assign a unique display_order within every listing before retrying.';
  end if;
end;
$$;

alter table public.listing_media
add constraint listing_media_listing_order_unique unique (listing_id, display_order);

create unique index listing_media_one_cover_idx
on public.listing_media (listing_id)
where is_cover;

create table public.amenities (
  id text primary key,
  name_key text not null unique,
  allowed_variants public.catalog_detail_variant[] not null,
  sort_order integer not null default 0,
  created_at timestamptz not null default now(),
  constraint amenities_id_valid check (
    app_private.catalog_text_has_canonical_edges(id)
    and id ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'
  ),
  constraint amenities_name_key_valid check (
    app_private.catalog_text_has_canonical_edges(name_key)
  ),
  constraint amenities_variants_not_empty check (
    cardinality(allowed_variants) > 0
    and array_position(allowed_variants, null) is null
    and not ('event'::public.catalog_detail_variant = any(allowed_variants))
  )
);

create table public.place_details (
  listing_id uuid primary key references public.listings (id) on delete cascade,
  place_category text not null,
  is_free boolean not null default true,
  entry_fee_xof integer,
  fee_note text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint place_details_category_not_blank check (
    app_private.catalog_text_has_canonical_edges(place_category)
  ),
  constraint place_details_fee_consistent check (
    (is_free and entry_fee_xof is null)
    or (not is_free and entry_fee_xof is not null and entry_fee_xof > 0)
  ),
  constraint place_details_fee_note_valid check (
    fee_note is null
    or app_private.catalog_text_has_canonical_edges(fee_note)
  )
);

create table public.lodging_details (
  listing_id uuid primary key references public.listings (id) on delete cascade,
  star_rating smallint,
  room_count integer,
  checkin_time time,
  checkout_time time,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint lodging_details_star_rating_range check (
    star_rating is null or star_rating between 0 and 5
  ),
  constraint lodging_details_room_count_positive check (
    room_count is null or room_count > 0
  ),
  constraint lodging_details_checkin_minute_precision check (
    checkin_time is null
    or (
      extract(hour from checkin_time) between 0 and 23
      and extract(second from checkin_time) = 0
    )
  ),
  constraint lodging_details_checkout_minute_precision check (
    checkout_time is null
    or (
      extract(hour from checkout_time) between 0 and 23
      and extract(second from checkout_time) = 0
    )
  )
);

create table public.room_types (
  id uuid primary key default gen_random_uuid(),
  listing_id uuid not null references public.lodging_details (listing_id) on delete cascade,
  name text not null,
  price_xof integer not null,
  display_order integer not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint room_types_name_valid check (
    app_private.catalog_text_has_canonical_edges(name)
  ),
  constraint room_types_price_non_negative check (price_xof >= 0),
  constraint room_types_display_order_non_negative check (display_order >= 0),
  constraint room_types_listing_name_unique unique (listing_id, name),
  constraint room_types_listing_order_unique unique (listing_id, display_order)
);

create table public.food_details (
  listing_id uuid primary key references public.listings (id) on delete cascade,
  cuisines text[] not null default '{}',
  meals text[] not null default '{}',
  reservation boolean not null default false,
  menu_url text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint food_details_cuisines_valid check (
    app_private.catalog_text_array_is_valid(cuisines, false)
  ),
  constraint food_details_meals_valid check (
    app_private.catalog_text_array_is_valid(meals, false)
  ),
  constraint food_details_menu_url_https check (
    menu_url is null
    or app_private.catalog_https_url_is_valid(menu_url)
  )
);

create table public.nightlife_details (
  listing_id uuid primary key references public.listings (id) on delete cascade,
  venue_kind text not null,
  min_age smallint,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint nightlife_details_venue_kind_valid check (
    app_private.catalog_text_has_canonical_edges(venue_kind)
  ),
  constraint nightlife_details_min_age_range check (
    min_age is null or min_age between 16 and 25
  )
);

create table public.guide_details (
  listing_id uuid primary key references public.listings (id) on delete cascade,
  languages text[] not null default '{}',
  zones text[] not null default '{}',
  specialties text[] not null default '{}',
  indicative_price_xof integer,
  accreditation text,
  experience_years smallint,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint guide_details_languages_valid check (
    app_private.catalog_text_array_is_valid(languages, false)
  ),
  constraint guide_details_zones_valid check (
    app_private.catalog_text_array_is_valid(zones, false)
  ),
  constraint guide_details_specialties_valid check (
    app_private.catalog_text_array_is_valid(specialties, false)
  ),
  constraint guide_details_price_non_negative check (
    indicative_price_xof is null or indicative_price_xof >= 0
  ),
  constraint guide_details_accreditation_valid check (
    accreditation is null
    or app_private.catalog_text_has_canonical_edges(accreditation)
  ),
  constraint guide_details_experience_range check (
    experience_years is null or experience_years between 0 and 80
  )
);

create table public.ticket_tiers (
  id uuid primary key default gen_random_uuid(),
  listing_id uuid not null references public.event_details (listing_id) on delete cascade,
  label text not null,
  price_xof integer not null,
  display_order integer not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint ticket_tiers_label_valid check (
    app_private.catalog_text_has_canonical_edges(label)
  ),
  constraint ticket_tiers_price_non_negative check (price_xof >= 0),
  constraint ticket_tiers_display_order_non_negative check (display_order >= 0),
  constraint ticket_tiers_listing_label_unique unique (listing_id, label),
  constraint ticket_tiers_listing_order_unique unique (listing_id, display_order)
);

create table public.listing_amenities (
  listing_id uuid not null references public.listings (id) on delete cascade,
  amenity_id text not null references public.amenities (id) on delete restrict,
  display_order integer not null default 0,
  created_at timestamptz not null default now(),
  primary key (listing_id, amenity_id),
  constraint listing_amenities_display_order_non_negative check (display_order >= 0),
  constraint listing_amenities_listing_order_unique unique (listing_id, display_order)
);

create index room_types_listing_order_idx
on public.room_types (listing_id, display_order, id);

create index ticket_tiers_listing_order_idx
on public.ticket_tiers (listing_id, display_order, id);

create index listing_amenities_listing_order_idx
on public.listing_amenities (listing_id, display_order, amenity_id);

create or replace function app_private.assert_catalog_listing_detail_complete(
  target_listing_id uuid
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  listing_record record;
  detail_record record;
  cover_count integer;
  amenity_count integer;
  child_count integer;
  non_positive_child_count integer;
  minimum_child_price integer;
begin
  select
    listing.id,
    listing.type,
    listing.subtype,
    listing.status,
    listing.published_at,
    listing.address,
    listing.lat,
    listing.lng,
    listing.price_from_xof,
    listing.price_unit,
    listing.opening_hours,
    listing.contact_phone,
    listing.contact_whatsapp,
    listing.external_url,
    listing.email,
    category.detail_variant
  into listing_record
  from public.listings listing
  join public.categories category on category.id = listing.category_id
  where listing.id = target_listing_id;

  if not found or listing_record.status not in ('en_attente', 'publie') then
    return;
  end if;

  if listing_record.status = 'publie' and listing_record.published_at is null then
    raise exception 'A published catalog listing requires a publication timestamp'
      using errcode = '23514';
  end if;

  select count(*)
  into cover_count
  from public.listing_media media
  where media.listing_id = target_listing_id
    and media.is_cover
    and media.kind = 'image';

  if cover_count <> 1 then
    raise exception 'An active catalog listing requires exactly one official image cover'
      using errcode = '23514';
  end if;

  if listing_record.type in ('lieu', 'etablissement')
    and (
      listing_record.address is null
      or btrim(listing_record.address) = ''
      or listing_record.lat is null
      or listing_record.lng is null
    )
  then
    raise exception 'An active place or establishment requires an address and Benin coordinates'
      using errcode = '23514';
  end if;

  if listing_record.type = 'etablissement' then
    if listing_record.opening_hours = '{}'::jsonb then
      raise exception 'An active establishment requires a seven-day opening-hours schedule'
        using errcode = '23514';
    end if;

    if listing_record.contact_phone is null
      and listing_record.contact_whatsapp is null
      and listing_record.external_url is null
      and listing_record.email is null
    then
      raise exception 'An active establishment requires at least one contact channel'
        using errcode = '23514';
    end if;

    select count(*)
    into amenity_count
    from public.listing_amenities link
    where link.listing_id = target_listing_id;

    if amenity_count = 0 then
      raise exception 'An active establishment requires at least one amenity'
        using errcode = '23514';
    end if;
  end if;

  case listing_record.detail_variant
    when 'place' then
      select detail.*
      into detail_record
      from public.place_details detail
      where detail.listing_id = target_listing_id;

      if not found or detail_record.place_category <> listing_record.subtype then
        raise exception 'An active place requires matching place details'
          using errcode = '23514';
      end if;

      if detail_record.is_free then
        if listing_record.price_from_xof is not null or listing_record.price_unit <> 'aucune' then
          raise exception 'A free place must not expose a paid listing price'
            using errcode = '23514';
        end if;
      elsif detail_record.entry_fee_xof <= 0
        or listing_record.price_from_xof is distinct from detail_record.entry_fee_xof
        or listing_record.price_unit <> 'par_entree'
      then
        raise exception 'A paid place price must match its entry fee'
          using errcode = '23514';
      end if;

    when 'lodging' then
      select detail.*
      into detail_record
      from public.lodging_details detail
      where detail.listing_id = target_listing_id;

      if not found then
        raise exception 'An active lodging establishment requires lodging details'
          using errcode = '23514';
      end if;

      if listing_record.subtype = 'hotel'
        and (detail_record.star_rating is null or detail_record.star_rating not between 1 and 5)
      then
        raise exception 'An active hotel requires a star rating between one and five'
          using errcode = '23514';
      end if;

      if listing_record.price_from_xof is null or listing_record.price_unit <> 'par_nuit' then
        raise exception 'An active lodging establishment requires a nightly XOF price'
          using errcode = '23514';
      end if;

      select count(*), min(room.price_xof)
      into child_count, minimum_child_price
      from public.room_types room
      where room.listing_id = target_listing_id;

      if child_count > 0 and listing_record.price_from_xof <> minimum_child_price then
        raise exception 'A lodging starting price must equal its cheapest room type'
          using errcode = '23514';
      end if;

    when 'food' then
      select detail.*
      into detail_record
      from public.food_details detail
      where detail.listing_id = target_listing_id;

      if not found or not app_private.catalog_text_array_is_valid(detail_record.cuisines, true) then
        raise exception 'An active food establishment requires at least one cuisine'
          using errcode = '23514';
      end if;

      if listing_record.price_from_xof is null or listing_record.price_unit <> 'par_personne' then
        raise exception 'An active food establishment requires a per-person XOF price'
          using errcode = '23514';
      end if;

    when 'nightlife' then
      select detail.*
      into detail_record
      from public.nightlife_details detail
      where detail.listing_id = target_listing_id;

      if not found or detail_record.venue_kind <> listing_record.subtype then
        raise exception 'An active nightlife establishment requires matching nightlife details'
          using errcode = '23514';
      end if;

      if listing_record.subtype = 'club' and detail_record.min_age is null then
        raise exception 'An active club requires a minimum age'
          using errcode = '23514';
      end if;

      if listing_record.price_from_xof is null
        or listing_record.price_unit not in ('consommation', 'par_entree')
      then
        raise exception 'An active nightlife establishment requires an XOF consumption or entry price'
          using errcode = '23514';
      end if;

    when 'guide' then
      select detail.*
      into detail_record
      from public.guide_details detail
      where detail.listing_id = target_listing_id;

      if not found
        or not app_private.catalog_text_array_is_valid(detail_record.languages, true)
        or not app_private.catalog_text_array_is_valid(detail_record.zones, true)
        or not app_private.catalog_text_array_is_valid(detail_record.specialties, true)
      then
        raise exception 'An active guide requires languages, zones and specialties'
          using errcode = '23514';
      end if;

      if detail_record.indicative_price_xof is null
        or listing_record.price_from_xof is distinct from detail_record.indicative_price_xof
        or listing_record.price_unit <> 'par_personne'
      then
        raise exception 'An active guide price must match its indicative per-person XOF price'
          using errcode = '23514';
      end if;

    when 'event' then
      select detail.*
      into detail_record
      from public.event_details detail
      where detail.listing_id = target_listing_id;

      if not found or detail_record.category <> listing_record.subtype then
        raise exception 'An active event requires matching event details'
          using errcode = '23514';
      end if;

      select
        count(*),
        count(*) filter (where tier.price_xof <= 0),
        min(tier.price_xof)
      into child_count, non_positive_child_count, minimum_child_price
      from public.ticket_tiers tier
      where tier.listing_id = target_listing_id;

      if detail_record.ticket_type = 'gratuit' then
        if child_count <> 0
          or listing_record.price_from_xof is not null
          or listing_record.price_unit <> 'aucune'
        then
          raise exception 'A free event must not expose paid ticket tiers or a listing price'
            using errcode = '23514';
        end if;
      elsif detail_record.ticket_url is null
        or child_count = 0
        or non_positive_child_count > 0
        or listing_record.price_from_xof is distinct from minimum_child_price
        or listing_record.price_unit <> 'par_entree'
      then
        raise exception 'A paid event requires a ticket URL and positive tiers matching its starting XOF price'
          using errcode = '23514';
      end if;
  end case;
end;
$$;

comment on function app_private.assert_catalog_listing_detail_complete(uuid) is
  'Trigger-only final-state validator for typed catalog details. It runs as the table owner to inspect rows hidden by RLS, exposes no data, and is deferred so drafts can be assembled in any order.';

revoke all on function app_private.assert_catalog_listing_detail_complete(uuid)
from public, anon, authenticated, service_role;

create or replace function app_private.guard_catalog_variant_child()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  target_listing_id uuid;
  parent_variant public.catalog_detail_variant;
  expected_variant public.catalog_detail_variant := tg_argv[0]::public.catalog_detail_variant;
  parent_status public.listing_status;
begin
  target_listing_id := case when tg_op = 'DELETE' then old.listing_id else new.listing_id end;

  if tg_op = 'UPDATE' and new.listing_id is distinct from old.listing_id then
    raise exception 'Catalog detail rows cannot be reassigned to another listing'
      using errcode = '23514';
  end if;

  select category.detail_variant, listing.status
  into parent_variant, parent_status
  from public.listings listing
  join public.categories category on category.id = listing.category_id
  where listing.id = target_listing_id
  for update of listing;

  if not found then
    if tg_op = 'DELETE' then
      return old;
    end if;
    return new;
  end if;

  if (select auth.role()) = 'authenticated' then
    if not app_private.current_user_has_completed_onboarding()
      or not public.current_user_can_manage_listing(target_listing_id)
    then
      raise insufficient_privilege using
        message = 'Catalog details require a completed manager of the parent listing';
    end if;

    if tg_op = 'DELETE' and parent_status <> 'brouillon' then
      raise insufficient_privilege using
        message = 'Catalog details can only be deleted while the parent is a draft';
    elsif tg_op <> 'DELETE'
      and parent_status not in ('brouillon', 'en_attente')
      and not public.current_user_has_verified_role('admin')
    then
      raise insufficient_privilege using
        message = 'Catalog details can only change before publication';
    end if;
  end if;

  if parent_variant <> expected_variant then
    raise exception 'Catalog detail variant does not match the parent category'
      using errcode = '23514';
  end if;

  if tg_op = 'DELETE' and parent_status in ('en_attente', 'publie') then
    raise exception 'An active catalog listing must keep its typed detail'
      using errcode = '23514';
  end if;

  if tg_op = 'DELETE' then
    return old;
  end if;
  return new;
end;
$$;

create or replace function app_private.guard_catalog_collection_child()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  target_listing_id uuid;
  parent_variant public.catalog_detail_variant;
  parent_status public.listing_status;
  expected_variant public.catalog_detail_variant;
  allowed_variants public.catalog_detail_variant[];
begin
  target_listing_id := case when tg_op = 'DELETE' then old.listing_id else new.listing_id end;

  if tg_op = 'UPDATE' and new.listing_id is distinct from old.listing_id then
    raise exception 'Catalog child rows cannot be reassigned to another listing'
      using errcode = '23514';
  end if;

  select category.detail_variant, listing.status
  into parent_variant, parent_status
  from public.listings listing
  join public.categories category on category.id = listing.category_id
  where listing.id = target_listing_id
  for update of listing;

  if not found then
    if tg_op = 'DELETE' then
      return old;
    end if;
    return new;
  end if;

  if (select auth.role()) = 'authenticated' then
    if not app_private.current_user_has_completed_onboarding()
      or not public.current_user_can_manage_listing(target_listing_id)
    then
      raise insufficient_privilege using
        message = 'Catalog children require a completed manager of the parent listing';
    end if;

    if tg_op = 'DELETE' and parent_status <> 'brouillon' then
      raise insufficient_privilege using
        message = 'Catalog children can only be deleted while the parent is a draft';
    elsif tg_op <> 'DELETE'
      and parent_status not in ('brouillon', 'en_attente')
      and not public.current_user_has_verified_role('admin')
    then
      raise insufficient_privilege using
        message = 'Catalog children can only change before publication';
    end if;
  end if;

  if tg_table_name = 'room_types' then
    expected_variant := 'lodging';
  elsif tg_table_name = 'ticket_tiers' then
    expected_variant := 'event';
  elsif tg_table_name = 'listing_amenities' and tg_op <> 'DELETE' then
    select amenity.allowed_variants
    into allowed_variants
    from public.amenities amenity
    where amenity.id = new.amenity_id
    for share;

    if not found or not (parent_variant = any(allowed_variants)) then
      raise exception 'Amenity is not allowed for the parent detail variant'
        using errcode = '23514';
    end if;
  end if;

  if expected_variant is not null and parent_variant <> expected_variant then
    raise exception 'Catalog child row does not match the parent detail variant'
      using errcode = '23514';
  end if;

  if tg_op = 'DELETE' then
    return old;
  end if;
  return new;
end;
$$;

create or replace function app_private.lock_catalog_media_parent()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  target_listing_id uuid;
  parent_status public.listing_status;
begin
  target_listing_id := case when tg_op = 'DELETE' then old.listing_id else new.listing_id end;

  if tg_op = 'UPDATE' and new.listing_id is distinct from old.listing_id then
    raise exception 'Official media cannot be reassigned to another listing'
      using errcode = '23514';
  end if;

  select listing.status
  into parent_status
  from public.listings listing
  where listing.id = target_listing_id
  for update of listing;

  if not found then
    if tg_op = 'DELETE' then
      return old;
    end if;
    return new;
  end if;

  if (select auth.role()) = 'authenticated' then
    if (
      not app_private.current_user_has_completed_onboarding()
      or not public.current_user_can_manage_listing(target_listing_id)
    ) then
      raise insufficient_privilege using
        message = 'Official media require a completed manager of the parent listing';
    end if;

    if tg_op = 'DELETE' and parent_status <> 'brouillon' then
      raise insufficient_privilege using
        message = 'Official media can only be deleted while the parent is a draft';
    elsif tg_op <> 'DELETE'
      and parent_status not in ('brouillon', 'en_attente')
      and not public.current_user_has_verified_role('admin')
    then
      raise insufficient_privilege using
        message = 'Official media can only change before publication';
    end if;
  end if;

  if tg_op = 'DELETE' then
    return old;
  end if;
  return new;
end;
$$;

create or replace function app_private.validate_catalog_listing_trigger()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  perform app_private.assert_catalog_listing_detail_complete(new.id);
  return null;
end;
$$;

create or replace function app_private.validate_catalog_child_trigger()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  target_listing_id uuid;
begin
  target_listing_id := case when tg_op = 'DELETE' then old.listing_id else new.listing_id end;
  perform app_private.assert_catalog_listing_detail_complete(target_listing_id);
  return null;
end;
$$;

revoke all on function app_private.guard_catalog_variant_child()
from public, anon, authenticated, service_role;
revoke all on function app_private.guard_catalog_collection_child()
from public, anon, authenticated, service_role;
revoke all on function app_private.lock_catalog_media_parent()
from public, anon, authenticated, service_role;
revoke all on function app_private.validate_catalog_listing_trigger()
from public, anon, authenticated, service_role;
revoke all on function app_private.validate_catalog_child_trigger()
from public, anon, authenticated, service_role;

create trigger place_details_guard_parent
before insert or update or delete on public.place_details
for each row execute function app_private.guard_catalog_variant_child('place');

create trigger lodging_details_guard_parent
before insert or update or delete on public.lodging_details
for each row execute function app_private.guard_catalog_variant_child('lodging');

create trigger food_details_guard_parent
before insert or update or delete on public.food_details
for each row execute function app_private.guard_catalog_variant_child('food');

create trigger nightlife_details_guard_parent
before insert or update or delete on public.nightlife_details
for each row execute function app_private.guard_catalog_variant_child('nightlife');

create trigger guide_details_guard_parent
before insert or update or delete on public.guide_details
for each row execute function app_private.guard_catalog_variant_child('guide');

create trigger event_details_guard_parent
before insert or update or delete on public.event_details
for each row execute function app_private.guard_catalog_variant_child('event');

create trigger room_types_guard_parent
before insert or update or delete on public.room_types
for each row execute function app_private.guard_catalog_collection_child();

create trigger ticket_tiers_guard_parent
before insert or update or delete on public.ticket_tiers
for each row execute function app_private.guard_catalog_collection_child();

create trigger listing_amenities_guard_parent
before insert or update or delete on public.listing_amenities
for each row execute function app_private.guard_catalog_collection_child();

create trigger listing_media_lock_parent
before insert or update or delete on public.listing_media
for each row execute function app_private.lock_catalog_media_parent();

create constraint trigger listings_validate_catalog_detail
after insert or update on public.listings
deferrable initially deferred
for each row execute function app_private.validate_catalog_listing_trigger();

create constraint trigger place_details_validate_catalog_detail
after insert or update or delete on public.place_details
deferrable initially deferred
for each row execute function app_private.validate_catalog_child_trigger();

create constraint trigger lodging_details_validate_catalog_detail
after insert or update or delete on public.lodging_details
deferrable initially deferred
for each row execute function app_private.validate_catalog_child_trigger();

create constraint trigger food_details_validate_catalog_detail
after insert or update or delete on public.food_details
deferrable initially deferred
for each row execute function app_private.validate_catalog_child_trigger();

create constraint trigger nightlife_details_validate_catalog_detail
after insert or update or delete on public.nightlife_details
deferrable initially deferred
for each row execute function app_private.validate_catalog_child_trigger();

create constraint trigger guide_details_validate_catalog_detail
after insert or update or delete on public.guide_details
deferrable initially deferred
for each row execute function app_private.validate_catalog_child_trigger();

create constraint trigger event_details_validate_catalog_detail
after insert or update or delete on public.event_details
deferrable initially deferred
for each row execute function app_private.validate_catalog_child_trigger();

create constraint trigger room_types_validate_catalog_detail
after insert or update or delete on public.room_types
deferrable initially deferred
for each row execute function app_private.validate_catalog_child_trigger();

create constraint trigger ticket_tiers_validate_catalog_detail
after insert or update or delete on public.ticket_tiers
deferrable initially deferred
for each row execute function app_private.validate_catalog_child_trigger();

create constraint trigger listing_amenities_validate_catalog_detail
after insert or update or delete on public.listing_amenities
deferrable initially deferred
for each row execute function app_private.validate_catalog_child_trigger();

create constraint trigger listing_media_validate_catalog_detail
after insert or update or delete on public.listing_media
deferrable initially deferred
for each row execute function app_private.validate_catalog_child_trigger();

create or replace function app_private.lock_catalog_listing_category()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  perform 1
  from public.categories category
  where category.id = new.category_id
  for share;

  return new;
end;
$$;

revoke all on function app_private.lock_catalog_listing_category()
from public, anon, authenticated, service_role;

create trigger listings_catalog_category_lock
before insert or update of category_id on public.listings
for each row execute function app_private.lock_catalog_listing_category();

create or replace function app_private.prevent_category_detail_variant_drift()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if new.detail_variant is distinct from old.detail_variant
    and exists (
      select 1
      from public.listings listing
      where listing.category_id = old.id
    )
  then
    raise exception 'A category detail variant cannot change while listings reference it'
      using errcode = '23514';
  end if;

  return new;
end;
$$;

revoke all on function app_private.prevent_category_detail_variant_drift()
from public, anon, authenticated, service_role;

create trigger categories_preserve_detail_variant
before update of detail_variant on public.categories
for each row execute function app_private.prevent_category_detail_variant_drift();

create or replace function app_private.prevent_amenity_variant_drift()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if new.allowed_variants is distinct from old.allowed_variants
    and exists (
      select 1
      from public.listing_amenities link
      join public.listings listing on listing.id = link.listing_id
      join public.categories category on category.id = listing.category_id
      where link.amenity_id = old.id
        and not (category.detail_variant = any(new.allowed_variants))
    )
  then
    raise exception 'Amenity variants cannot exclude an existing listing link'
      using errcode = '23514';
  end if;

  return new;
end;
$$;

revoke all on function app_private.prevent_amenity_variant_drift()
from public, anon, authenticated, service_role;

create trigger amenities_preserve_linked_variants
before update of allowed_variants on public.amenities
for each row execute function app_private.prevent_amenity_variant_drift();

create trigger place_details_touch_updated_at
before update on public.place_details
for each row execute function public.touch_updated_at();

create trigger lodging_details_touch_updated_at
before update on public.lodging_details
for each row execute function public.touch_updated_at();

create trigger room_types_touch_updated_at
before update on public.room_types
for each row execute function public.touch_updated_at();

create trigger food_details_touch_updated_at
before update on public.food_details
for each row execute function public.touch_updated_at();

create trigger nightlife_details_touch_updated_at
before update on public.nightlife_details
for each row execute function public.touch_updated_at();

create trigger guide_details_touch_updated_at
before update on public.guide_details
for each row execute function public.touch_updated_at();

create trigger ticket_tiers_touch_updated_at
before update on public.ticket_tiers
for each row execute function public.touch_updated_at();

alter table public.amenities enable row level security;
alter table public.place_details enable row level security;
alter table public.lodging_details enable row level security;
alter table public.room_types enable row level security;
alter table public.food_details enable row level security;
alter table public.nightlife_details enable row level security;
alter table public.guide_details enable row level security;
alter table public.ticket_tiers enable row level security;
alter table public.listing_amenities enable row level security;

drop policy "listing managers create media" on public.listing_media;
create policy "listing managers create media"
on public.listing_media
for insert
to authenticated
with check (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_can_manage_listing(listing_id)
  and exists (
    select 1
    from public.listings listing
    where listing.id = listing_media.listing_id
      and (
        listing.status in ('brouillon', 'en_attente')
        or (select public.current_user_has_verified_role('admin'))
      )
  )
);

drop policy "listing managers update media" on public.listing_media;
create policy "listing managers update media"
on public.listing_media
for update
to authenticated
using (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_can_manage_listing(listing_id)
  and exists (
    select 1
    from public.listings listing
    where listing.id = listing_media.listing_id
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
    where listing.id = listing_media.listing_id
      and (
        listing.status in ('brouillon', 'en_attente')
        or (select public.current_user_has_verified_role('admin'))
      )
  )
);

drop policy "listing managers delete media" on public.listing_media;
create policy "listing managers delete media"
on public.listing_media
for delete
to authenticated
using (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_can_manage_listing(listing_id)
  and exists (
    select 1
    from public.listings listing
    where listing.id = listing_media.listing_id
      and listing.status = 'brouillon'
  )
);

create policy "catalog amenities are readable"
on public.amenities
for select
to anon, authenticated
using (true);

create policy "anonymous users read published place details"
on public.place_details
for select
to anon
using (
  exists (
    select 1
    from public.listings listing
    where listing.id = place_details.listing_id
      and listing.status = 'publie'
  )
);

create policy "authenticated users read permitted place details"
on public.place_details
for select
to authenticated
using (
  exists (
    select 1
    from public.listings listing
    where listing.id = place_details.listing_id
      and listing.status = 'publie'
  )
  or public.current_user_can_manage_listing(listing_id)
);

create policy "place managers create place details"
on public.place_details
for insert
to authenticated
with check (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_can_manage_listing(listing_id)
  and exists (
    select 1
    from public.listings listing
    where listing.id = place_details.listing_id
      and (
        listing.status in ('brouillon', 'en_attente')
        or (select public.current_user_has_verified_role('admin'))
      )
  )
);

create policy "place managers update place details"
on public.place_details
for update
to authenticated
using (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_can_manage_listing(listing_id)
  and exists (
    select 1
    from public.listings listing
    where listing.id = place_details.listing_id
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
    where listing.id = place_details.listing_id
      and (
        listing.status in ('brouillon', 'en_attente')
        or (select public.current_user_has_verified_role('admin'))
      )
  )
);

create policy "place managers delete draft place details"
on public.place_details
for delete
to authenticated
using (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_can_manage_listing(listing_id)
  and exists (
    select 1
    from public.listings listing
    where listing.id = place_details.listing_id
      and listing.status = 'brouillon'
  )
);

create policy "anonymous users read published lodging details"
on public.lodging_details
for select
to anon
using (
  exists (
    select 1 from public.listings listing
    where listing.id = lodging_details.listing_id and listing.status = 'publie'
  )
);

create policy "authenticated users read permitted lodging details"
on public.lodging_details
for select
to authenticated
using (
  exists (
    select 1 from public.listings listing
    where listing.id = lodging_details.listing_id and listing.status = 'publie'
  )
  or public.current_user_can_manage_listing(listing_id)
);

create policy "lodging managers create lodging details"
on public.lodging_details
for insert
to authenticated
with check (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_can_manage_listing(listing_id)
  and exists (
    select 1 from public.listings listing
    where listing.id = lodging_details.listing_id
      and (
        listing.status in ('brouillon', 'en_attente')
        or (select public.current_user_has_verified_role('admin'))
      )
  )
);

create policy "lodging managers update lodging details"
on public.lodging_details
for update
to authenticated
using (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_can_manage_listing(listing_id)
  and exists (
    select 1 from public.listings listing
    where listing.id = lodging_details.listing_id
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
    select 1 from public.listings listing
    where listing.id = lodging_details.listing_id
      and (
        listing.status in ('brouillon', 'en_attente')
        or (select public.current_user_has_verified_role('admin'))
      )
  )
);

create policy "lodging managers delete draft lodging details"
on public.lodging_details
for delete
to authenticated
using (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_can_manage_listing(listing_id)
  and exists (
    select 1 from public.listings listing
    where listing.id = lodging_details.listing_id and listing.status = 'brouillon'
  )
);

create policy "anonymous users read published food details"
on public.food_details
for select
to anon
using (
  exists (
    select 1 from public.listings listing
    where listing.id = food_details.listing_id and listing.status = 'publie'
  )
);

create policy "authenticated users read permitted food details"
on public.food_details
for select
to authenticated
using (
  exists (
    select 1 from public.listings listing
    where listing.id = food_details.listing_id and listing.status = 'publie'
  )
  or public.current_user_can_manage_listing(listing_id)
);

create policy "food managers create food details"
on public.food_details
for insert
to authenticated
with check (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_can_manage_listing(listing_id)
  and exists (
    select 1 from public.listings listing
    where listing.id = food_details.listing_id
      and (
        listing.status in ('brouillon', 'en_attente')
        or (select public.current_user_has_verified_role('admin'))
      )
  )
);

create policy "food managers update food details"
on public.food_details
for update
to authenticated
using (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_can_manage_listing(listing_id)
  and exists (
    select 1 from public.listings listing
    where listing.id = food_details.listing_id
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
    select 1 from public.listings listing
    where listing.id = food_details.listing_id
      and (
        listing.status in ('brouillon', 'en_attente')
        or (select public.current_user_has_verified_role('admin'))
      )
  )
);

create policy "food managers delete draft food details"
on public.food_details
for delete
to authenticated
using (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_can_manage_listing(listing_id)
  and exists (
    select 1 from public.listings listing
    where listing.id = food_details.listing_id and listing.status = 'brouillon'
  )
);

create policy "anonymous users read published nightlife details"
on public.nightlife_details
for select
to anon
using (
  exists (
    select 1 from public.listings listing
    where listing.id = nightlife_details.listing_id and listing.status = 'publie'
  )
);

create policy "authenticated users read permitted nightlife details"
on public.nightlife_details
for select
to authenticated
using (
  exists (
    select 1 from public.listings listing
    where listing.id = nightlife_details.listing_id and listing.status = 'publie'
  )
  or public.current_user_can_manage_listing(listing_id)
);

create policy "nightlife managers create nightlife details"
on public.nightlife_details
for insert
to authenticated
with check (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_can_manage_listing(listing_id)
  and exists (
    select 1 from public.listings listing
    where listing.id = nightlife_details.listing_id
      and (
        listing.status in ('brouillon', 'en_attente')
        or (select public.current_user_has_verified_role('admin'))
      )
  )
);

create policy "nightlife managers update nightlife details"
on public.nightlife_details
for update
to authenticated
using (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_can_manage_listing(listing_id)
  and exists (
    select 1 from public.listings listing
    where listing.id = nightlife_details.listing_id
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
    select 1 from public.listings listing
    where listing.id = nightlife_details.listing_id
      and (
        listing.status in ('brouillon', 'en_attente')
        or (select public.current_user_has_verified_role('admin'))
      )
  )
);

create policy "nightlife managers delete draft nightlife details"
on public.nightlife_details
for delete
to authenticated
using (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_can_manage_listing(listing_id)
  and exists (
    select 1 from public.listings listing
    where listing.id = nightlife_details.listing_id and listing.status = 'brouillon'
  )
);

create policy "anonymous users read published guide details"
on public.guide_details
for select
to anon
using (
  exists (
    select 1 from public.listings listing
    where listing.id = guide_details.listing_id and listing.status = 'publie'
  )
);

create policy "authenticated users read permitted guide details"
on public.guide_details
for select
to authenticated
using (
  exists (
    select 1 from public.listings listing
    where listing.id = guide_details.listing_id and listing.status = 'publie'
  )
  or public.current_user_can_manage_listing(listing_id)
);

create policy "guide managers create guide details"
on public.guide_details
for insert
to authenticated
with check (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_can_manage_listing(listing_id)
  and exists (
    select 1 from public.listings listing
    where listing.id = guide_details.listing_id
      and (
        listing.status in ('brouillon', 'en_attente')
        or (select public.current_user_has_verified_role('admin'))
      )
  )
);

create policy "guide managers update guide details"
on public.guide_details
for update
to authenticated
using (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_can_manage_listing(listing_id)
  and exists (
    select 1 from public.listings listing
    where listing.id = guide_details.listing_id
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
    select 1 from public.listings listing
    where listing.id = guide_details.listing_id
      and (
        listing.status in ('brouillon', 'en_attente')
        or (select public.current_user_has_verified_role('admin'))
      )
  )
);

create policy "guide managers delete draft guide details"
on public.guide_details
for delete
to authenticated
using (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_can_manage_listing(listing_id)
  and exists (
    select 1 from public.listings listing
    where listing.id = guide_details.listing_id and listing.status = 'brouillon'
  )
);

create policy "anonymous users read published room types"
on public.room_types
for select
to anon
using (
  exists (
    select 1 from public.listings listing
    where listing.id = room_types.listing_id and listing.status = 'publie'
  )
);

create policy "authenticated users read permitted room types"
on public.room_types
for select
to authenticated
using (
  exists (
    select 1 from public.listings listing
    where listing.id = room_types.listing_id and listing.status = 'publie'
  )
  or public.current_user_can_manage_listing(listing_id)
);

create policy "lodging managers create room types"
on public.room_types
for insert
to authenticated
with check (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_can_manage_listing(listing_id)
  and exists (
    select 1 from public.listings listing
    where listing.id = room_types.listing_id
      and (
        listing.status in ('brouillon', 'en_attente')
        or (select public.current_user_has_verified_role('admin'))
      )
  )
);

create policy "lodging managers update room types"
on public.room_types
for update
to authenticated
using (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_can_manage_listing(listing_id)
  and exists (
    select 1 from public.listings listing
    where listing.id = room_types.listing_id
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
    select 1 from public.listings listing
    where listing.id = room_types.listing_id
      and (
        listing.status in ('brouillon', 'en_attente')
        or (select public.current_user_has_verified_role('admin'))
      )
  )
);

create policy "lodging managers delete draft room types"
on public.room_types
for delete
to authenticated
using (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_can_manage_listing(listing_id)
  and exists (
    select 1 from public.listings listing
    where listing.id = room_types.listing_id and listing.status = 'brouillon'
  )
);

create policy "anonymous users read published ticket tiers"
on public.ticket_tiers
for select
to anon
using (
  exists (
    select 1 from public.listings listing
    where listing.id = ticket_tiers.listing_id and listing.status = 'publie'
  )
);

create policy "authenticated users read permitted ticket tiers"
on public.ticket_tiers
for select
to authenticated
using (
  exists (
    select 1 from public.listings listing
    where listing.id = ticket_tiers.listing_id and listing.status = 'publie'
  )
  or public.current_user_can_manage_listing(listing_id)
);

create policy "event managers create ticket tiers"
on public.ticket_tiers
for insert
to authenticated
with check (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_can_manage_listing(listing_id)
  and exists (
    select 1 from public.listings listing
    where listing.id = ticket_tiers.listing_id
      and (
        listing.status in ('brouillon', 'en_attente')
        or (select public.current_user_has_verified_role('admin'))
      )
  )
);

create policy "event managers update ticket tiers"
on public.ticket_tiers
for update
to authenticated
using (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_can_manage_listing(listing_id)
  and exists (
    select 1 from public.listings listing
    where listing.id = ticket_tiers.listing_id
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
    select 1 from public.listings listing
    where listing.id = ticket_tiers.listing_id
      and (
        listing.status in ('brouillon', 'en_attente')
        or (select public.current_user_has_verified_role('admin'))
      )
  )
);

create policy "event managers delete draft ticket tiers"
on public.ticket_tiers
for delete
to authenticated
using (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_can_manage_listing(listing_id)
  and exists (
    select 1 from public.listings listing
    where listing.id = ticket_tiers.listing_id and listing.status = 'brouillon'
  )
);

create policy "anonymous users read published listing amenities"
on public.listing_amenities
for select
to anon
using (
  exists (
    select 1 from public.listings listing
    where listing.id = listing_amenities.listing_id and listing.status = 'publie'
  )
);

create policy "authenticated users read permitted listing amenities"
on public.listing_amenities
for select
to authenticated
using (
  exists (
    select 1 from public.listings listing
    where listing.id = listing_amenities.listing_id and listing.status = 'publie'
  )
  or public.current_user_can_manage_listing(listing_id)
);

create policy "listing managers create listing amenities"
on public.listing_amenities
for insert
to authenticated
with check (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_can_manage_listing(listing_id)
  and exists (
    select 1 from public.listings listing
    where listing.id = listing_amenities.listing_id
      and (
        listing.status in ('brouillon', 'en_attente')
        or (select public.current_user_has_verified_role('admin'))
      )
  )
);

create policy "listing managers update listing amenities"
on public.listing_amenities
for update
to authenticated
using (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_can_manage_listing(listing_id)
  and exists (
    select 1 from public.listings listing
    where listing.id = listing_amenities.listing_id
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
    select 1 from public.listings listing
    where listing.id = listing_amenities.listing_id
      and (
        listing.status in ('brouillon', 'en_attente')
        or (select public.current_user_has_verified_role('admin'))
      )
  )
);

create policy "listing managers delete draft listing amenities"
on public.listing_amenities
for delete
to authenticated
using (
  (select app_private.current_user_has_completed_onboarding())
  and public.current_user_can_manage_listing(listing_id)
  and exists (
    select 1 from public.listings listing
    where listing.id = listing_amenities.listing_id and listing.status = 'brouillon'
  )
);

revoke all on table public.amenities
from public, anon, authenticated, service_role;
revoke all on table public.place_details
from public, anon, authenticated, service_role;
revoke all on table public.lodging_details
from public, anon, authenticated, service_role;
revoke all on table public.room_types
from public, anon, authenticated, service_role;
revoke all on table public.food_details
from public, anon, authenticated, service_role;
revoke all on table public.nightlife_details
from public, anon, authenticated, service_role;
revoke all on table public.guide_details
from public, anon, authenticated, service_role;
revoke all on table public.ticket_tiers
from public, anon, authenticated, service_role;
revoke all on table public.listing_amenities
from public, anon, authenticated, service_role;

grant select (id, name_key, allowed_variants, sort_order)
on table public.amenities to anon, authenticated;
grant select (listing_id, place_category, is_free, entry_fee_xof, fee_note)
on table public.place_details to anon, authenticated;
grant select (listing_id, star_rating, room_count, checkin_time, checkout_time)
on table public.lodging_details to anon, authenticated;
grant select (listing_id, name, price_xof, display_order)
on table public.room_types to anon, authenticated;
grant select (listing_id, cuisines, meals, reservation, menu_url)
on table public.food_details to anon, authenticated;
grant select (listing_id, venue_kind, min_age)
on table public.nightlife_details to anon, authenticated;
grant select (
  listing_id,
  languages,
  zones,
  specialties,
  indicative_price_xof,
  accreditation,
  experience_years
) on table public.guide_details to anon, authenticated;
grant select (listing_id, label, price_xof, display_order)
on table public.ticket_tiers to anon, authenticated;
grant select (listing_id, amenity_id, display_order)
on table public.listing_amenities to anon, authenticated;

grant insert (listing_id, place_category, is_free, entry_fee_xof, fee_note),
  update (place_category, is_free, entry_fee_xof, fee_note), delete
on table public.place_details to authenticated;

grant insert (listing_id, star_rating, room_count, checkin_time, checkout_time),
  update (star_rating, room_count, checkin_time, checkout_time), delete
on table public.lodging_details to authenticated;

grant insert (id, listing_id, name, price_xof, display_order),
  update (name, price_xof, display_order), delete
on table public.room_types to authenticated;

grant insert (listing_id, cuisines, meals, reservation, menu_url),
  update (cuisines, meals, reservation, menu_url), delete
on table public.food_details to authenticated;

grant insert (listing_id, venue_kind, min_age),
  update (venue_kind, min_age), delete
on table public.nightlife_details to authenticated;

grant insert (
  listing_id,
  languages,
  zones,
  specialties,
  indicative_price_xof,
  accreditation,
  experience_years
), update (
  languages,
  zones,
  specialties,
  indicative_price_xof,
  accreditation,
  experience_years
), delete on table public.guide_details to authenticated;

grant insert (id, listing_id, label, price_xof, display_order),
  update (label, price_xof, display_order), delete
on table public.ticket_tiers to authenticated;

grant insert (listing_id, amenity_id, display_order),
  update (amenity_id, display_order), delete
on table public.listing_amenities to authenticated;

grant select, insert, update, delete on table public.amenities to service_role;
grant select, insert, update, delete on table public.place_details to service_role;
grant select, insert, update, delete on table public.lodging_details to service_role;
grant select, insert, update, delete on table public.room_types to service_role;
grant select, insert, update, delete on table public.food_details to service_role;
grant select, insert, update, delete on table public.nightlife_details to service_role;
grant select, insert, update, delete on table public.guide_details to service_role;
grant select, insert, update, delete on table public.ticket_tiers to service_role;
grant select, insert, update, delete on table public.listing_amenities to service_role;

revoke select on table public.listings from public, anon, authenticated;
grant select (
  id,
  type,
  subtype,
  listing_class,
  category_id,
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
  verified,
  sponsored_until,
  rating_avg,
  rating_count,
  views_count,
  likes_count,
  published_at,
  is_claimable
) on table public.listings to anon, authenticated;

revoke select on table public.event_details from public, anon, authenticated;
grant select (
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
) on table public.event_details to anon, authenticated;

revoke select on table public.listing_media from public, anon, authenticated;
grant select (listing_id, url, alt, display_order, is_cover, kind)
on table public.listing_media to anon, authenticated;

revoke insert, update on table public.listing_media from authenticated;
grant insert (
  id,
  listing_id,
  storage_path,
  url,
  alt,
  display_order,
  is_cover,
  kind
) on table public.listing_media to authenticated;
grant update (
  storage_path,
  url,
  alt,
  display_order,
  is_cover,
  kind
) on table public.listing_media to authenticated;

create or replace function public.list_catalog_summaries(
  p_city_id text default null,
  p_category_id text default null,
  p_listing_type text default null,
  p_listing_class text default null,
  p_search_query text default null,
  p_cursor text default null,
  p_limit integer default 20
)
returns table (
  id uuid,
  type public.listing_type,
  listing_class public.listing_class,
  status public.listing_status,
  name text,
  city_id text,
  category_id text,
  cover_image_url text,
  price_from_xof integer,
  rating_avg numeric,
  likes_count integer,
  verified boolean,
  sponsored_until timestamptz,
  is_sponsored_placement boolean,
  row_cursor text
)
language plpgsql
stable
security invoker
set search_path = ''
as $$
declare
  v_city_id text;
  v_category_id text;
  v_listing_type public.listing_type;
  v_listing_class public.listing_class;
  v_search_query text;
  v_search_pattern text;
  v_fingerprint text;
  v_cursor_payload jsonb;
  v_as_of timestamptz := statement_timestamp();
  v_cursor_sponsored_until timestamptz;
  v_cursor_rating numeric;
  v_cursor_likes integer;
  v_cursor_published_at timestamptz;
  v_cursor_id uuid;
begin
  if p_limit is null or p_limit < 1 or p_limit > 50 then
    raise exception using
      errcode = '22023',
      message = 'p_limit must be between 1 and 50';
  end if;

  if p_city_id is not null then
    v_city_id := btrim(p_city_id);

    if v_city_id = '' or char_length(v_city_id) > 100 then
      raise exception using
        errcode = '22023',
        message = 'p_city_id is invalid';
    end if;

    if not exists (
      select 1
      from public.cities city
      where city.id = v_city_id
    ) then
      raise exception using
        errcode = '22023',
        message = 'p_city_id is unknown';
    end if;
  end if;

  if p_category_id is not null then
    v_category_id := btrim(p_category_id);

    if v_category_id = '' or char_length(v_category_id) > 100 then
      raise exception using
        errcode = '22023',
        message = 'p_category_id is invalid';
    end if;

    if not exists (
      select 1
      from public.categories category
      where category.id = v_category_id
    ) then
      raise exception using
        errcode = '22023',
        message = 'p_category_id is unknown';
    end if;
  end if;

  if p_listing_type is not null then
    begin
      v_listing_type := lower(btrim(p_listing_type))::public.listing_type;
    exception
      when invalid_text_representation then
        raise exception using
          errcode = '22023',
          message = 'p_listing_type is invalid';
    end;
  end if;

  if p_listing_class is not null then
    begin
      v_listing_class := lower(btrim(p_listing_class))::public.listing_class;
    exception
      when invalid_text_representation then
        raise exception using
          errcode = '22023',
          message = 'p_listing_class is invalid';
    end;
  end if;

  if p_search_query is not null then
    v_search_query := lower(btrim(p_search_query));

    if v_search_query = ''
      or char_length(v_search_query) > 120
      or v_search_query ~ '[[:cntrl:]]'
    then
      raise exception using
        errcode = '22023',
        message = 'p_search_query is invalid';
    end if;

    v_search_pattern := replace(v_search_query, E'\\', E'\\\\');
    v_search_pattern := replace(v_search_pattern, '%', E'\\%');
    v_search_pattern := replace(v_search_pattern, '_', E'\\_');
  end if;

  v_fingerprint := md5(
    jsonb_build_object(
      'category_id', v_category_id,
      'city_id', v_city_id,
      'listing_class', v_listing_class::text,
      'listing_type', v_listing_type::text,
      'search_query', v_search_query
    )::text
  );

  if p_cursor is not null then
    if btrim(p_cursor) = ''
      or char_length(p_cursor) > 4096
      or p_cursor ~ '[[:space:]]'
    then
      raise exception using
        errcode = '22023',
        message = 'p_cursor is invalid';
    end if;

    begin
      v_cursor_payload := convert_from(decode(p_cursor, 'base64'), 'UTF8')::jsonb;
    exception
      when others then
        raise exception using
          errcode = '22023',
          message = 'p_cursor is malformed';
    end;

    if jsonb_typeof(v_cursor_payload) <> 'object'
      or jsonb_typeof(v_cursor_payload -> 'v') <> 'number'
      or v_cursor_payload ->> 'v' <> '1'
    then
      raise exception using
        errcode = '22023',
        message = 'p_cursor version is unsupported';
    end if;

    if jsonb_typeof(v_cursor_payload -> 'as_of') <> 'string'
      or jsonb_typeof(v_cursor_payload -> 'fingerprint') <> 'string'
      or not (v_cursor_payload ? 'sponsored_until')
      or jsonb_typeof(v_cursor_payload -> 'sponsored_until') not in ('null', 'string')
      or jsonb_typeof(v_cursor_payload -> 'rating') <> 'number'
      or jsonb_typeof(v_cursor_payload -> 'likes') <> 'number'
      or jsonb_typeof(v_cursor_payload -> 'published_at') <> 'string'
      or jsonb_typeof(v_cursor_payload -> 'id') <> 'string'
    then
      raise exception using
        errcode = '22023',
        message = 'p_cursor fields are malformed';
    end if;

    if v_cursor_payload ->> 'fingerprint' <> v_fingerprint then
      raise exception using
        errcode = '22023',
        message = 'p_cursor does not match catalog filters';
    end if;

    begin
      v_as_of := (v_cursor_payload ->> 'as_of')::timestamptz;
      v_cursor_sponsored_until := case
        when jsonb_typeof(v_cursor_payload -> 'sponsored_until') = 'null' then null
        else (v_cursor_payload ->> 'sponsored_until')::timestamptz
      end;
      v_cursor_rating := (v_cursor_payload ->> 'rating')::numeric;
      v_cursor_likes := (v_cursor_payload ->> 'likes')::integer;
      v_cursor_published_at := (v_cursor_payload ->> 'published_at')::timestamptz;
      v_cursor_id := (v_cursor_payload ->> 'id')::uuid;
    exception
      when others then
        raise exception using
          errcode = '22023',
          message = 'p_cursor fields are malformed';
    end;

    if v_as_of < '0001-01-01 00:00:00+00'::timestamptz
      or v_as_of >= '10000-01-01 00:00:00+00'::timestamptz
      or v_cursor_published_at < '0001-01-01 00:00:00+00'::timestamptz
      or v_cursor_published_at >= '10000-01-01 00:00:00+00'::timestamptz
      or (
        v_cursor_sponsored_until is not null
        and (
          v_cursor_sponsored_until < '0001-01-01 00:00:00+00'::timestamptz
          or v_cursor_sponsored_until >= '10000-01-01 00:00:00+00'::timestamptz
          or v_cursor_sponsored_until <= v_as_of
        )
      )
      or v_cursor_rating < -1
      or v_cursor_rating > 5
      or v_cursor_likes < 0
    then
      raise exception using
        errcode = '22023',
        message = 'p_cursor fields are invalid';
    end if;
  end if;

  return query
  with ranked_catalog as (
    select
      listing.id,
      listing.type,
      listing.listing_class,
      listing.status,
      listing.name,
      listing.city_id,
      listing.category_id,
      cover.url as cover_image_url,
      listing.price_from_xof,
      listing.rating_avg,
      listing.likes_count,
      listing.verified,
      listing.sponsored_until,
      case
        when listing.sponsored_until > v_as_of then listing.sponsored_until
        else null
      end as sort_sponsored_until,
      coalesce(listing.rating_avg, -1::numeric) as sort_rating,
      listing.published_at as sort_published_at
    from public.listings listing
    left join lateral (
      select media.url
      from public.listing_media media
      where media.listing_id = listing.id
      order by
        media.is_cover desc,
        media.display_order asc
      limit 1
    ) cover on true
    where listing.status = 'publie'::public.listing_status
      and listing.published_at is not null
      and (v_city_id is null or listing.city_id = v_city_id)
      and (v_category_id is null or listing.category_id = v_category_id)
      and (v_listing_type is null or listing.type = v_listing_type)
      and (v_listing_class is null or listing.listing_class = v_listing_class)
      and (
        v_search_pattern is null
        or listing.name ilike '%' || v_search_pattern || '%' escape E'\\'
      )
  ),
  catalog_page as (
    select ranked.*
    from ranked_catalog ranked
    where p_cursor is null
      or (
        coalesce(ranked.sort_sponsored_until, '-infinity'::timestamptz),
        ranked.sort_rating,
        ranked.likes_count,
        ranked.sort_published_at,
        ranked.id
      ) < (
        coalesce(v_cursor_sponsored_until, '-infinity'::timestamptz),
        v_cursor_rating,
        v_cursor_likes,
        v_cursor_published_at,
        v_cursor_id
      )
    order by
      ranked.sort_sponsored_until desc nulls last,
      ranked.sort_rating desc,
      ranked.likes_count desc,
      ranked.sort_published_at desc,
      ranked.id desc
    limit (p_limit + 1)
  )
  select
    page.id,
    page.type,
    page.listing_class,
    page.status,
    page.name,
    page.city_id,
    page.category_id,
    page.cover_image_url,
    page.price_from_xof,
    page.rating_avg,
    page.likes_count,
    page.verified,
    page.sponsored_until,
    page.sort_sponsored_until is not null as is_sponsored_placement,
    replace(
      replace(
        encode(
          convert_to(
            jsonb_build_object(
              'v', 1,
              'as_of', v_as_of,
              'fingerprint', v_fingerprint,
              'sponsored_until', page.sort_sponsored_until,
              'rating', page.sort_rating,
              'likes', page.likes_count,
              'published_at', page.sort_published_at,
              'id', page.id
            )::text,
            'UTF8'
          ),
          'base64'
        ),
        chr(10),
        ''
      ),
      chr(13),
      ''
    ) as row_cursor
  from catalog_page page
  order by
    page.sort_sponsored_until desc nulls last,
    page.sort_rating desc,
    page.likes_count desc,
    page.sort_published_at desc,
    page.id desc;
end;
$$;

revoke execute on function public.list_catalog_summaries(
  text,
  text,
  text,
  text,
  text,
  text,
  integer
)
from public, anon, authenticated, service_role;

grant execute on function public.list_catalog_summaries(
  text,
  text,
  text,
  text,
  text,
  text,
  integer
)
to anon, authenticated;

create or replace function public.get_catalog_detail_v1(p_listing_id uuid)
returns table (payload jsonb)
language sql
stable
security invoker
set search_path = ''
as $$
  select jsonb_build_object(
    'schema_version', 1,
    'id', listing.id,
    'is_claimable', listing.is_claimable,
    'type', listing.type,
    'subtype', listing.subtype,
    'listing_class', listing.listing_class,
    'name', listing.name,
    'slug', listing.slug,
    'description', listing.description,
    'content_lang', listing.content_lang,
    'city', jsonb_build_object(
      'id', city.id,
      'name', city.name
    ),
    'category', jsonb_build_object(
      'id', category.id,
      'label_key', category.name_key
    ),
    'location', jsonb_build_object(
      'district', listing.district,
      'address', listing.address,
      'latitude', listing.lat,
      'longitude', listing.lng
    ),
    'price', jsonb_build_object(
      'from_xof', listing.price_from_xof,
      'unit', listing.price_unit,
      'tier', listing.price_tier
    ),
    'opening_hours', listing.opening_hours,
    'contact', jsonb_build_object(
      'phone', listing.contact_phone,
      'whatsapp', listing.contact_whatsapp,
      'external_url', listing.external_url,
      'email', listing.email
    ),
    'socials', listing.socials,
    'tags', to_jsonb(listing.tags),
    'verified', listing.verified,
    'metrics', jsonb_build_object(
      'rating_average', listing.rating_avg,
      'rating_count', listing.rating_count,
      'views_count', listing.views_count,
      'likes_count', listing.likes_count
    ),
    'published_at', listing.published_at,
    'media', coalesce(
      (
        select jsonb_agg(
          jsonb_build_object(
            'kind', media.kind,
            'url', media.url,
            'alt', media.alt,
            'display_order', media.display_order,
            'is_cover', media.is_cover
          )
          order by media.display_order
        )
        from public.listing_media media
        where media.listing_id = listing.id
      ),
      '[]'::jsonb
    ),
    'amenities', coalesce(
      (
        select jsonb_agg(
          jsonb_build_object(
            'id', amenity.id,
            'label_key', amenity.name_key,
            'display_order', link.display_order
          )
          order by link.display_order
        )
        from public.listing_amenities link
        join public.amenities amenity on amenity.id = link.amenity_id
        where link.listing_id = listing.id
      ),
      '[]'::jsonb
    ),
    'detail', case category.detail_variant
      when 'place' then jsonb_build_object(
        'variant', 'place',
        'place_category', place_detail.place_category,
        'is_free', place_detail.is_free,
        'entry_fee_xof', place_detail.entry_fee_xof,
        'fee_note', place_detail.fee_note
      )
      when 'lodging' then jsonb_build_object(
        'variant', 'lodging',
        'star_rating', lodging_detail.star_rating,
        'room_count', lodging_detail.room_count,
        'checkin_time', lodging_detail.checkin_time,
        'checkout_time', lodging_detail.checkout_time,
        'room_types', coalesce(
          (
            select jsonb_agg(
              jsonb_build_object(
                'name', room.name,
                'price_xof', room.price_xof,
                'display_order', room.display_order
              )
              order by room.display_order
            )
            from public.room_types room
            where room.listing_id = listing.id
          ),
          '[]'::jsonb
        )
      )
      when 'food' then jsonb_build_object(
        'variant', 'food',
        'cuisines', to_jsonb(food_detail.cuisines),
        'meals', to_jsonb(food_detail.meals),
        'reservation', food_detail.reservation,
        'menu_url', food_detail.menu_url
      )
      when 'nightlife' then jsonb_build_object(
        'variant', 'nightlife',
        'venue_kind', nightlife_detail.venue_kind,
        'min_age', nightlife_detail.min_age
      )
      when 'guide' then jsonb_build_object(
        'variant', 'guide',
        'languages', to_jsonb(guide_detail.languages),
        'zones', to_jsonb(guide_detail.zones),
        'specialties', to_jsonb(guide_detail.specialties),
        'indicative_price_xof', guide_detail.indicative_price_xof,
        'accreditation', guide_detail.accreditation,
        'experience_years', guide_detail.experience_years
      )
      when 'event' then jsonb_build_object(
        'variant', 'event',
        'category', event_detail.category,
        'start_at', event_detail.start_at,
        'end_at', event_detail.end_at,
        'venue_listing', case
          when venue.id is null then null
          else jsonb_build_object(
            'id', venue.id,
            'type', venue.type,
            'subtype', venue.subtype,
            'name', venue.name,
            'city', jsonb_build_object(
              'id', venue_city.id,
              'name', venue_city.name
            ),
            'address', venue.address,
            'latitude', venue.lat,
            'longitude', venue.lng
          )
        end,
        'organizer', jsonb_build_object(
          'name', event_detail.organizer_name,
          'contact', event_detail.organizer_contact
        ),
        'ticketing', jsonb_build_object(
          'type', event_detail.ticket_type,
          'url', event_detail.ticket_url,
          'tiers', coalesce(
            (
              select jsonb_agg(
                jsonb_build_object(
                  'label', tier.label,
                  'price_xof', tier.price_xof,
                  'display_order', tier.display_order
                )
                order by tier.display_order
              )
              from public.ticket_tiers tier
              where tier.listing_id = listing.id
            ),
            '[]'::jsonb
          )
        ),
        'capacity', event_detail.capacity
      )
    end
  ) as payload
  from public.listings listing
  join public.cities city on city.id = listing.city_id
  join public.categories category on category.id = listing.category_id
  left join public.place_details place_detail on place_detail.listing_id = listing.id
  left join public.lodging_details lodging_detail on lodging_detail.listing_id = listing.id
  left join public.food_details food_detail on food_detail.listing_id = listing.id
  left join public.nightlife_details nightlife_detail on nightlife_detail.listing_id = listing.id
  left join public.guide_details guide_detail on guide_detail.listing_id = listing.id
  left join public.event_details event_detail on event_detail.listing_id = listing.id
  left join public.listings venue on venue.id = event_detail.venue_listing_id
  left join public.cities venue_city on venue_city.id = venue.city_id
  where listing.id = p_listing_id
    and listing.status = 'publie'
    and listing.published_at is not null
    and (
      event_detail.venue_listing_id is null
      or venue_city.id is not null
    );
$$;

comment on function public.get_catalog_detail_v1(uuid) is
  'Versioned, publication-only catalog detail projection. Security invoker and RLS preserve the public read boundary; authority and UGC fields are intentionally absent.';

revoke all on function public.get_catalog_detail_v1(uuid)
from public, anon, authenticated, service_role;

grant execute on function public.get_catalog_detail_v1(uuid)
to anon, authenticated;

do $$
declare
  active_listing record;
begin
  for active_listing in
    select listing.id
    from public.listings listing
    where listing.status in ('en_attente', 'publie')
    order by listing.id
  loop
    perform app_private.assert_catalog_listing_detail_complete(active_listing.id);
  end loop;
end;
$$;

reset lock_timeout;
