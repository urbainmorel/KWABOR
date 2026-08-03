begin;

set local lock_timeout = '5s';

create table public.guide_languages (
  id text primary key,
  label text not null,
  is_active boolean not null default true,
  display_order integer not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint guide_languages_id_valid check (
    app_private.catalog_text_has_canonical_edges(id)
    and char_length(id) between 1 and 80
    and id ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'
  ),
  constraint guide_languages_label_valid check (
    app_private.catalog_text_has_canonical_edges(label)
    and char_length(label) between 1 and 80
    and label !~ '[[:cntrl:]]'
  ),
  constraint guide_languages_display_order_non_negative check (display_order >= 0),
  constraint guide_languages_display_order_unique unique (display_order)
);

create unique index guide_languages_label_ci_unique_idx
on public.guide_languages (lower(label));

create table public.guide_specialties (
  id text primary key,
  label text not null,
  is_active boolean not null default true,
  display_order integer not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint guide_specialties_id_valid check (
    app_private.catalog_text_has_canonical_edges(id)
    and char_length(id) between 1 and 80
    and id ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'
  ),
  constraint guide_specialties_label_valid check (
    app_private.catalog_text_has_canonical_edges(label)
    and char_length(label) between 1 and 80
    and label !~ '[[:cntrl:]]'
  ),
  constraint guide_specialties_display_order_non_negative check (display_order >= 0),
  constraint guide_specialties_display_order_unique unique (display_order)
);

create unique index guide_specialties_label_ci_unique_idx
on public.guide_specialties (lower(label));

insert into public.guide_languages (id, label, display_order)
values
  ('francais', 'Français', 10),
  ('fon', 'Fon', 20),
  ('yoruba', 'Yoruba', 30),
  ('mina', 'Mina', 40),
  ('adja', 'Adja', 50),
  ('bariba', 'Bariba', 60),
  ('dendi', 'Dendi', 70),
  ('anglais', 'Anglais', 80),
  ('portugais', 'Portugais', 90);

insert into public.guide_specialties (id, label, display_order)
values
  ('histoire', 'Histoire', 10),
  ('patrimoine', 'Patrimoine', 20),
  ('culture', 'Culture', 30),
  ('art-artisanat', 'Art et artisanat', 40),
  ('gastronomie', 'Gastronomie', 50),
  ('nature-ecotourisme', 'Nature et écotourisme', 60),
  ('architecture', 'Architecture', 70),
  ('vie-locale', 'Vie locale', 80);

create trigger guide_languages_touch_updated_at
before update on public.guide_languages
for each row execute function public.touch_updated_at();

create trigger guide_specialties_touch_updated_at
before update on public.guide_specialties
for each row execute function public.touch_updated_at();

create table public.guide_service_cities (
  listing_id uuid not null references public.guide_details (listing_id) on delete cascade,
  city_id text not null references public.cities (id) on delete restrict,
  display_order integer not null default 0,
  created_at timestamptz not null default now(),
  primary key (listing_id, city_id),
  constraint guide_service_cities_display_order_range check (display_order between 0 and 19),
  constraint guide_service_cities_listing_order_unique unique (listing_id, display_order)
);

create table public.guide_service_languages (
  listing_id uuid not null references public.guide_details (listing_id) on delete cascade,
  language_id text not null references public.guide_languages (id) on delete restrict,
  display_order integer not null default 0,
  created_at timestamptz not null default now(),
  primary key (listing_id, language_id),
  constraint guide_service_languages_display_order_range check (display_order between 0 and 19),
  constraint guide_service_languages_listing_order_unique unique (listing_id, display_order)
);

create table public.guide_service_specialties (
  listing_id uuid not null references public.guide_details (listing_id) on delete cascade,
  specialty_id text not null references public.guide_specialties (id) on delete restrict,
  display_order integer not null default 0,
  created_at timestamptz not null default now(),
  primary key (listing_id, specialty_id),
  constraint guide_service_specialties_display_order_range check (display_order between 0 and 19),
  constraint guide_service_specialties_listing_order_unique unique (listing_id, display_order)
);

create index guide_service_cities_city_listing_idx
on public.guide_service_cities (city_id, listing_id);

create index guide_service_languages_language_listing_idx
on public.guide_service_languages (language_id, listing_id);

create index guide_service_specialties_specialty_listing_idx
on public.guide_service_specialties (specialty_id, listing_id);

-- Keep legacy arrays stable until the backfill and its synchronization trigger
-- are installed in the same migration transaction. The lock fails closed under
-- the migration-level lock_timeout instead of allowing a lost concurrent write.
lock table public.guide_details in share row exclusive mode;

do $$
declare
  invalid_record record;
begin
  select detail.listing_id
  into invalid_record
  from public.guide_details detail
  where cardinality(detail.languages) > 20
    or cardinality(detail.zones) > 20
    or cardinality(detail.specialties) > 20
  order by detail.listing_id
  limit 1;

  if found then
    raise exception 'Guide % has more than twenty legacy values in a discovery dimension',
      invalid_record.listing_id
      using
        errcode = '23514',
        hint = 'Reduce every guide language, zone and specialty array to at most twenty values before retrying.';
  end if;

  select detail.listing_id, legacy.value
  into invalid_record
  from public.guide_details detail
  cross join lateral unnest(detail.languages) with ordinality as legacy(value, display_ordinal)
  where (
    select count(*)
    from public.guide_languages language
    where language.is_active
      and (
        lower(language.id) = lower(btrim(legacy.value))
        or lower(language.label) = lower(btrim(legacy.value))
      )
  ) <> 1
  order by detail.listing_id, legacy.display_ordinal
  limit 1;

  if found then
    raise exception 'Guide % has an unknown or ambiguous legacy language: %',
      invalid_record.listing_id,
      invalid_record.value
      using
        errcode = '23514',
        hint = 'Add one active canonical guide_languages row or correct guide_details.languages before retrying.';
  end if;

  select mapped.listing_id, mapped.language_id
  into invalid_record
  from (
    select detail.listing_id, language.id as language_id
    from public.guide_details detail
    cross join lateral unnest(detail.languages) as legacy(value)
    join public.guide_languages language
      on language.is_active
      and (
        lower(language.id) = lower(btrim(legacy.value))
        or lower(language.label) = lower(btrim(legacy.value))
      )
  ) mapped
  group by mapped.listing_id, mapped.language_id
  having count(*) > 1
  order by mapped.listing_id, mapped.language_id
  limit 1;

  if found then
    raise exception 'Guide % maps more than one legacy language to canonical language %',
      invalid_record.listing_id,
      invalid_record.language_id
      using
        errcode = '23514',
        hint = 'Deduplicate guide_details.languages before retrying.';
  end if;

  select detail.listing_id, legacy.value
  into invalid_record
  from public.guide_details detail
  cross join lateral unnest(detail.specialties) with ordinality as legacy(value, display_ordinal)
  where (
    select count(*)
    from public.guide_specialties specialty
    where specialty.is_active
      and (
        lower(specialty.id) = lower(btrim(legacy.value))
        or lower(specialty.label) = lower(btrim(legacy.value))
      )
  ) <> 1
  order by detail.listing_id, legacy.display_ordinal
  limit 1;

  if found then
    raise exception 'Guide % has an unknown or ambiguous legacy specialty: %',
      invalid_record.listing_id,
      invalid_record.value
      using
        errcode = '23514',
        hint = 'Add one active canonical guide_specialties row or correct guide_details.specialties before retrying.';
  end if;

  select mapped.listing_id, mapped.specialty_id
  into invalid_record
  from (
    select detail.listing_id, specialty.id as specialty_id
    from public.guide_details detail
    cross join lateral unnest(detail.specialties) as legacy(value)
    join public.guide_specialties specialty
      on specialty.is_active
      and (
        lower(specialty.id) = lower(btrim(legacy.value))
        or lower(specialty.label) = lower(btrim(legacy.value))
      )
  ) mapped
  group by mapped.listing_id, mapped.specialty_id
  having count(*) > 1
  order by mapped.listing_id, mapped.specialty_id
  limit 1;

  if found then
    raise exception 'Guide % maps more than one legacy specialty to canonical specialty %',
      invalid_record.listing_id,
      invalid_record.specialty_id
      using
        errcode = '23514',
        hint = 'Deduplicate guide_details.specialties before retrying.';
  end if;

  select detail.listing_id, legacy.value
  into invalid_record
  from public.guide_details detail
  cross join lateral unnest(detail.zones) with ordinality as legacy(value, display_ordinal)
  where (
    select count(distinct city.id)
    from public.cities city
    where city.enabled
      and (
        lower(city.id) = lower(btrim(legacy.value))
        or lower(city.slug) = lower(btrim(legacy.value))
        or lower(city.name) = lower(btrim(legacy.value))
      )
  ) <> 1
  order by detail.listing_id, legacy.display_ordinal
  limit 1;

  if found then
    raise exception 'Guide % has an unknown or ambiguous legacy service city: %',
      invalid_record.listing_id,
      invalid_record.value
      using
        errcode = '23514',
        hint = 'Enable exactly one matching city or correct guide_details.zones before retrying.';
  end if;

  select mapped.listing_id, mapped.city_id
  into invalid_record
  from (
    select detail.listing_id, city.id as city_id
    from public.guide_details detail
    cross join lateral unnest(detail.zones) as legacy(value)
    join public.cities city
      on city.enabled
      and (
        lower(city.id) = lower(btrim(legacy.value))
        or lower(city.slug) = lower(btrim(legacy.value))
        or lower(city.name) = lower(btrim(legacy.value))
      )
  ) mapped
  group by mapped.listing_id, mapped.city_id
  having count(*) > 1
  order by mapped.listing_id, mapped.city_id
  limit 1;

  if found then
    raise exception 'Guide % maps more than one legacy zone to service city %',
      invalid_record.listing_id,
      invalid_record.city_id
      using
        errcode = '23514',
        hint = 'Deduplicate guide_details.zones before retrying.';
  end if;
end;
$$;

insert into public.guide_service_languages (listing_id, language_id, display_order)
select detail.listing_id, language.id, legacy.display_ordinal::integer - 1
from public.guide_details detail
cross join lateral unnest(detail.languages) with ordinality as legacy(value, display_ordinal)
join public.guide_languages language
  on language.is_active
  and (
    lower(language.id) = lower(btrim(legacy.value))
    or lower(language.label) = lower(btrim(legacy.value))
  );

insert into public.guide_service_specialties (listing_id, specialty_id, display_order)
select detail.listing_id, specialty.id, legacy.display_ordinal::integer - 1
from public.guide_details detail
cross join lateral unnest(detail.specialties) with ordinality as legacy(value, display_ordinal)
join public.guide_specialties specialty
  on specialty.is_active
  and (
    lower(specialty.id) = lower(btrim(legacy.value))
    or lower(specialty.label) = lower(btrim(legacy.value))
  );

insert into public.guide_service_cities (listing_id, city_id, display_order)
select detail.listing_id, city.id, legacy.display_ordinal::integer - 1
from public.guide_details detail
cross join lateral unnest(detail.zones) with ordinality as legacy(value, display_ordinal)
join public.cities city
  on city.enabled
  and (
    lower(city.id) = lower(btrim(legacy.value))
    or lower(city.slug) = lower(btrim(legacy.value))
    or lower(city.name) = lower(btrim(legacy.value))
  );

do $$
declare
  invalid_listing_id uuid;
begin
  select detail.listing_id
  into invalid_listing_id
  from public.guide_details detail
  where cardinality(detail.languages) <> (
      select count(*) from public.guide_service_languages link
      where link.listing_id = detail.listing_id
    )
    or cardinality(detail.specialties) <> (
      select count(*) from public.guide_service_specialties link
      where link.listing_id = detail.listing_id
    )
    or cardinality(detail.zones) <> (
      select count(*) from public.guide_service_cities link
      where link.listing_id = detail.listing_id
    )
  order by detail.listing_id
  limit 1;

  if found then
    raise exception 'Guide discovery backfill lost legacy values for guide %', invalid_listing_id
      using
        errcode = '23514',
        hint = 'The migration is fail-closed; repair the legacy arrays before retrying.';
  end if;
end;
$$;

create or replace function app_private.sync_guide_discovery_relations(
  target_listing_id uuid
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  detail_record record;
  invalid_value text;
  duplicate_canonical_id text;
  mapped_city_ids text[];
  mapped_language_ids text[];
  mapped_specialty_ids text[];
begin
  select detail.languages, detail.zones, detail.specialties
  into detail_record
  from public.guide_details detail
  where detail.listing_id = target_listing_id
  for update;

  if not found then
    return;
  end if;

  if pg_catalog.cardinality(detail_record.languages) > 20
    or pg_catalog.cardinality(detail_record.zones) > 20
    or pg_catalog.cardinality(detail_record.specialties) > 20
  then
    raise exception 'Guide % has more than twenty legacy values in a discovery dimension',
      target_listing_id
      using
        errcode = '23514',
        hint = 'Reduce every guide language, zone and specialty array to at most twenty values before retrying.';
  end if;

  select legacy.value
  into invalid_value
  from pg_catalog.unnest(detail_record.languages)
    with ordinality as legacy(value, display_ordinal)
  where (
    select count(*)
    from public.guide_languages language
    where language.is_active
      and (
        pg_catalog.lower(language.id) = pg_catalog.lower(pg_catalog.btrim(legacy.value))
        or pg_catalog.lower(language.label) = pg_catalog.lower(pg_catalog.btrim(legacy.value))
      )
  ) <> 1
  order by legacy.display_ordinal
  limit 1;

  if found then
    raise exception 'Guide % has an unknown or ambiguous legacy language: %',
      target_listing_id,
      invalid_value
      using
        errcode = '23514',
        hint = 'Add one active canonical guide_languages row or correct guide_details.languages before retrying.';
  end if;

  select coalesce(
    pg_catalog.array_agg(language.id order by legacy.display_ordinal),
    '{}'::text[]
  )
  into mapped_language_ids
  from pg_catalog.unnest(detail_record.languages)
    with ordinality as legacy(value, display_ordinal)
  join public.guide_languages language
    on language.is_active
    and (
      pg_catalog.lower(language.id) = pg_catalog.lower(pg_catalog.btrim(legacy.value))
      or pg_catalog.lower(language.label) = pg_catalog.lower(pg_catalog.btrim(legacy.value))
    );

  select mapped.language_id
  into duplicate_canonical_id
  from pg_catalog.unnest(mapped_language_ids) as mapped(language_id)
  group by mapped.language_id
  having count(*) > 1
  order by mapped.language_id
  limit 1;

  if found then
    raise exception 'Guide % maps more than one legacy language to canonical language %',
      target_listing_id,
      duplicate_canonical_id
      using
        errcode = '23514',
        hint = 'Deduplicate guide_details.languages before retrying.';
  end if;

  select legacy.value
  into invalid_value
  from pg_catalog.unnest(detail_record.zones)
    with ordinality as legacy(value, display_ordinal)
  where (
    select count(*)
    from public.cities city
    where city.enabled
      and (
        pg_catalog.lower(city.id) = pg_catalog.lower(pg_catalog.btrim(legacy.value))
        or pg_catalog.lower(city.slug) = pg_catalog.lower(pg_catalog.btrim(legacy.value))
        or pg_catalog.lower(city.name) = pg_catalog.lower(pg_catalog.btrim(legacy.value))
      )
  ) <> 1
  order by legacy.display_ordinal
  limit 1;

  if found then
    raise exception 'Guide % has an unknown or ambiguous legacy service city: %',
      target_listing_id,
      invalid_value
      using
        errcode = '23514',
        hint = 'Enable exactly one matching city or correct guide_details.zones before retrying.';
  end if;

  select coalesce(
    pg_catalog.array_agg(city.id order by legacy.display_ordinal),
    '{}'::text[]
  )
  into mapped_city_ids
  from pg_catalog.unnest(detail_record.zones)
    with ordinality as legacy(value, display_ordinal)
  join public.cities city
    on city.enabled
    and (
      pg_catalog.lower(city.id) = pg_catalog.lower(pg_catalog.btrim(legacy.value))
      or pg_catalog.lower(city.slug) = pg_catalog.lower(pg_catalog.btrim(legacy.value))
      or pg_catalog.lower(city.name) = pg_catalog.lower(pg_catalog.btrim(legacy.value))
    );

  select mapped.city_id
  into duplicate_canonical_id
  from pg_catalog.unnest(mapped_city_ids) as mapped(city_id)
  group by mapped.city_id
  having count(*) > 1
  order by mapped.city_id
  limit 1;

  if found then
    raise exception 'Guide % maps more than one legacy zone to service city %',
      target_listing_id,
      duplicate_canonical_id
      using
        errcode = '23514',
        hint = 'Deduplicate guide_details.zones before retrying.';
  end if;

  select legacy.value
  into invalid_value
  from pg_catalog.unnest(detail_record.specialties)
    with ordinality as legacy(value, display_ordinal)
  where (
    select count(*)
    from public.guide_specialties specialty
    where specialty.is_active
      and (
        pg_catalog.lower(specialty.id) = pg_catalog.lower(pg_catalog.btrim(legacy.value))
        or pg_catalog.lower(specialty.label) = pg_catalog.lower(pg_catalog.btrim(legacy.value))
      )
  ) <> 1
  order by legacy.display_ordinal
  limit 1;

  if found then
    raise exception 'Guide % has an unknown or ambiguous legacy specialty: %',
      target_listing_id,
      invalid_value
      using
        errcode = '23514',
        hint = 'Add one active canonical guide_specialties row or correct guide_details.specialties before retrying.';
  end if;

  select coalesce(
    pg_catalog.array_agg(specialty.id order by legacy.display_ordinal),
    '{}'::text[]
  )
  into mapped_specialty_ids
  from pg_catalog.unnest(detail_record.specialties)
    with ordinality as legacy(value, display_ordinal)
  join public.guide_specialties specialty
    on specialty.is_active
    and (
      pg_catalog.lower(specialty.id) = pg_catalog.lower(pg_catalog.btrim(legacy.value))
      or pg_catalog.lower(specialty.label) = pg_catalog.lower(pg_catalog.btrim(legacy.value))
    );

  select mapped.specialty_id
  into duplicate_canonical_id
  from pg_catalog.unnest(mapped_specialty_ids) as mapped(specialty_id)
  group by mapped.specialty_id
  having count(*) > 1
  order by mapped.specialty_id
  limit 1;

  if found then
    raise exception 'Guide % maps more than one legacy specialty to canonical specialty %',
      target_listing_id,
      duplicate_canonical_id
      using
        errcode = '23514',
        hint = 'Deduplicate guide_details.specialties before retrying.';
  end if;

  delete from public.guide_service_cities
  where listing_id = target_listing_id;
  delete from public.guide_service_languages
  where listing_id = target_listing_id;
  delete from public.guide_service_specialties
  where listing_id = target_listing_id;

  insert into public.guide_service_cities (listing_id, city_id, display_order)
  select target_listing_id, mapped.city_id, mapped.display_ordinal::integer - 1
  from pg_catalog.unnest(mapped_city_ids)
    with ordinality as mapped(city_id, display_ordinal);

  insert into public.guide_service_languages (listing_id, language_id, display_order)
  select target_listing_id, mapped.language_id, mapped.display_ordinal::integer - 1
  from pg_catalog.unnest(mapped_language_ids)
    with ordinality as mapped(language_id, display_ordinal);

  insert into public.guide_service_specialties (listing_id, specialty_id, display_order)
  select target_listing_id, mapped.specialty_id, mapped.display_ordinal::integer - 1
  from pg_catalog.unnest(mapped_specialty_ids)
    with ordinality as mapped(specialty_id, display_ordinal);
end;
$$;

create or replace function app_private.sync_guide_discovery_relations_from_legacy()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  perform app_private.sync_guide_discovery_relations(new.listing_id);
  return new;
end;
$$;

create or replace function app_private.assert_guide_discovery_relations_match(
  target_listing_id uuid
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  detail_record record;
  expected_city_ids text[];
  expected_language_ids text[];
  expected_specialty_ids text[];
  actual_city_ids text[];
  actual_language_ids text[];
  actual_specialty_ids text[];
  invalid_mapping boolean;
begin
  select detail.languages, detail.zones, detail.specialties
  into detail_record
  from public.guide_details detail
  where detail.listing_id = target_listing_id;

  if not found then
    return;
  end if;

  select
    exists (
      select 1
      from pg_catalog.unnest(detail_record.languages) as legacy(value)
      where (
        select count(*)
        from public.guide_languages language
        where language.is_active
          and (
            pg_catalog.lower(language.id) = pg_catalog.lower(pg_catalog.btrim(legacy.value))
            or pg_catalog.lower(language.label) = pg_catalog.lower(pg_catalog.btrim(legacy.value))
          )
      ) <> 1
    )
    or exists (
      select 1
      from pg_catalog.unnest(detail_record.zones) as legacy(value)
      where (
        select count(*)
        from public.cities city
        where city.enabled
          and (
            pg_catalog.lower(city.id) = pg_catalog.lower(pg_catalog.btrim(legacy.value))
            or pg_catalog.lower(city.slug) = pg_catalog.lower(pg_catalog.btrim(legacy.value))
            or pg_catalog.lower(city.name) = pg_catalog.lower(pg_catalog.btrim(legacy.value))
          )
      ) <> 1
    )
    or exists (
      select 1
      from pg_catalog.unnest(detail_record.specialties) as legacy(value)
      where (
        select count(*)
        from public.guide_specialties specialty
        where specialty.is_active
          and (
            pg_catalog.lower(specialty.id) = pg_catalog.lower(pg_catalog.btrim(legacy.value))
            or pg_catalog.lower(specialty.label) = pg_catalog.lower(pg_catalog.btrim(legacy.value))
          )
      ) <> 1
    )
  into invalid_mapping;

  select coalesce(
    pg_catalog.array_agg(city.id order by legacy.display_ordinal, city.id),
    '{}'::text[]
  )
  into expected_city_ids
  from pg_catalog.unnest(detail_record.zones)
    with ordinality as legacy(value, display_ordinal)
  join public.cities city
    on city.enabled
    and (
      pg_catalog.lower(city.id) = pg_catalog.lower(pg_catalog.btrim(legacy.value))
      or pg_catalog.lower(city.slug) = pg_catalog.lower(pg_catalog.btrim(legacy.value))
      or pg_catalog.lower(city.name) = pg_catalog.lower(pg_catalog.btrim(legacy.value))
    );

  select coalesce(
    pg_catalog.array_agg(language.id order by legacy.display_ordinal, language.id),
    '{}'::text[]
  )
  into expected_language_ids
  from pg_catalog.unnest(detail_record.languages)
    with ordinality as legacy(value, display_ordinal)
  join public.guide_languages language
    on language.is_active
    and (
      pg_catalog.lower(language.id) = pg_catalog.lower(pg_catalog.btrim(legacy.value))
      or pg_catalog.lower(language.label) = pg_catalog.lower(pg_catalog.btrim(legacy.value))
    );

  select coalesce(
    pg_catalog.array_agg(specialty.id order by legacy.display_ordinal, specialty.id),
    '{}'::text[]
  )
  into expected_specialty_ids
  from pg_catalog.unnest(detail_record.specialties)
    with ordinality as legacy(value, display_ordinal)
  join public.guide_specialties specialty
    on specialty.is_active
    and (
      pg_catalog.lower(specialty.id) = pg_catalog.lower(pg_catalog.btrim(legacy.value))
      or pg_catalog.lower(specialty.label) = pg_catalog.lower(pg_catalog.btrim(legacy.value))
    );

  select coalesce(
    pg_catalog.array_agg(link.city_id order by link.display_order),
    '{}'::text[]
  )
  into actual_city_ids
  from public.guide_service_cities link
  where link.listing_id = target_listing_id;

  select coalesce(
    pg_catalog.array_agg(link.language_id order by link.display_order),
    '{}'::text[]
  )
  into actual_language_ids
  from public.guide_service_languages link
  where link.listing_id = target_listing_id;

  select coalesce(
    pg_catalog.array_agg(link.specialty_id order by link.display_order),
    '{}'::text[]
  )
  into actual_specialty_ids
  from public.guide_service_specialties link
  where link.listing_id = target_listing_id;

  if invalid_mapping
    or pg_catalog.cardinality(expected_city_ids) <> pg_catalog.cardinality(detail_record.zones)
    or pg_catalog.cardinality(expected_language_ids) <> pg_catalog.cardinality(detail_record.languages)
    or pg_catalog.cardinality(expected_specialty_ids) <> pg_catalog.cardinality(detail_record.specialties)
    or actual_city_ids is distinct from expected_city_ids
    or actual_language_ids is distinct from expected_language_ids
    or actual_specialty_ids is distinct from expected_specialty_ids
  then
    raise exception 'Guide discovery relations diverge from legacy arrays for guide %',
      target_listing_id
      using
        errcode = '23514',
        hint = 'Write guide_details languages, zones and specialties so the private synchronizer owns normalized relations.';
  end if;
end;
$$;

create or replace function app_private.assert_all_guide_discovery_relations_trigger()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  target_listing_id uuid;
begin
  for target_listing_id in
    select detail.listing_id
    from public.guide_details detail
    order by detail.listing_id
  loop
    perform app_private.assert_guide_discovery_relations_match(target_listing_id);
  end loop;

  return null;
end;
$$;

create or replace function app_private.assert_guide_discovery_relations_trigger()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if tg_op in ('UPDATE', 'DELETE') then
    perform app_private.assert_guide_discovery_relations_match(old.listing_id);
  end if;

  if tg_op in ('INSERT', 'UPDATE')
    and (tg_op = 'INSERT' or new.listing_id is distinct from old.listing_id)
  then
    perform app_private.assert_guide_discovery_relations_match(new.listing_id);
  end if;

  return null;
end;
$$;

revoke all on function app_private.sync_guide_discovery_relations(uuid)
from public, anon, authenticated, service_role;
revoke all on function app_private.sync_guide_discovery_relations_from_legacy()
from public, anon, authenticated, service_role;
revoke all on function app_private.assert_guide_discovery_relations_match(uuid)
from public, anon, authenticated, service_role;
revoke all on function app_private.assert_all_guide_discovery_relations_trigger()
from public, anon, authenticated, service_role;
revoke all on function app_private.assert_guide_discovery_relations_trigger()
from public, anon, authenticated, service_role;

comment on function app_private.sync_guide_discovery_relations(uuid) is
  'Private trigger-only SECURITY DEFINER synchronizer; crosses guide relation RLS without exposing client DML.';
comment on function app_private.assert_guide_discovery_relations_match(uuid) is
  'Private deferred invariant check preventing privileged relation writes from diverging from legacy guide arrays.';
comment on function app_private.assert_all_guide_discovery_relations_trigger() is
  'Private deferred guard preventing reference-catalog mutations from invalidating legacy guide mappings.';

create trigger guide_details_sync_discovery_relations
after insert or update of languages, zones, specialties on public.guide_details
for each row execute function app_private.sync_guide_discovery_relations_from_legacy();

create constraint trigger guide_service_cities_match_legacy
after insert or update or delete on public.guide_service_cities
deferrable initially deferred
for each row execute function app_private.assert_guide_discovery_relations_trigger();

create constraint trigger guide_service_languages_match_legacy
after insert or update or delete on public.guide_service_languages
deferrable initially deferred
for each row execute function app_private.assert_guide_discovery_relations_trigger();

create constraint trigger guide_service_specialties_match_legacy
after insert or update or delete on public.guide_service_specialties
deferrable initially deferred
for each row execute function app_private.assert_guide_discovery_relations_trigger();

create constraint trigger guide_languages_preserve_discovery_mapping
after insert or update or delete on public.guide_languages
deferrable initially deferred
for each row execute function app_private.assert_all_guide_discovery_relations_trigger();

create constraint trigger guide_specialties_preserve_discovery_mapping
after insert or update or delete on public.guide_specialties
deferrable initially deferred
for each row execute function app_private.assert_all_guide_discovery_relations_trigger();

create constraint trigger cities_preserve_guide_discovery_mapping
after insert or update or delete on public.cities
deferrable initially deferred
for each row execute function app_private.assert_all_guide_discovery_relations_trigger();

alter table public.guide_languages enable row level security;
alter table public.guide_specialties enable row level security;
alter table public.guide_service_cities enable row level security;
alter table public.guide_service_languages enable row level security;
alter table public.guide_service_specialties enable row level security;

create policy "active guide languages are readable"
on public.guide_languages
for select
to anon, authenticated
using (is_active);

create policy "active guide specialties are readable"
on public.guide_specialties
for select
to anon, authenticated
using (is_active);

create policy "anonymous users read published guide service cities"
on public.guide_service_cities
for select
to anon
using (
  exists (
    select 1 from public.cities city
    where city.id = guide_service_cities.city_id and city.enabled
  )
  and
  exists (
    select 1
    from public.listings listing
    join public.categories category on category.id = listing.category_id
    where listing.id = guide_service_cities.listing_id
      and category.detail_variant = 'guide'
      and listing.status = 'publie'
      and listing.published_at is not null
  )
);

create policy "authenticated users read permitted guide service cities"
on public.guide_service_cities
for select
to authenticated
using (
  (
    exists (
      select 1 from public.cities city
      where city.id = guide_service_cities.city_id and city.enabled
    )
    and exists (
      select 1
      from public.listings listing
      join public.categories category on category.id = listing.category_id
      where listing.id = guide_service_cities.listing_id
        and category.detail_variant = 'guide'
        and listing.status = 'publie'
        and listing.published_at is not null
    )
  )
  or public.current_user_can_manage_listing(listing_id)
);

create policy "anonymous users read published guide service languages"
on public.guide_service_languages
for select
to anon
using (
  exists (
    select 1 from public.guide_languages language
    where language.id = guide_service_languages.language_id and language.is_active
  )
  and
  exists (
    select 1
    from public.listings listing
    join public.categories category on category.id = listing.category_id
    where listing.id = guide_service_languages.listing_id
      and category.detail_variant = 'guide'
      and listing.status = 'publie'
      and listing.published_at is not null
  )
);

create policy "authenticated users read permitted guide service languages"
on public.guide_service_languages
for select
to authenticated
using (
  (
    exists (
      select 1 from public.guide_languages language
      where language.id = guide_service_languages.language_id and language.is_active
    )
    and exists (
      select 1
      from public.listings listing
      join public.categories category on category.id = listing.category_id
      where listing.id = guide_service_languages.listing_id
        and category.detail_variant = 'guide'
        and listing.status = 'publie'
        and listing.published_at is not null
    )
  )
  or public.current_user_can_manage_listing(listing_id)
);

create policy "anonymous users read published guide service specialties"
on public.guide_service_specialties
for select
to anon
using (
  exists (
    select 1 from public.guide_specialties specialty
    where specialty.id = guide_service_specialties.specialty_id and specialty.is_active
  )
  and
  exists (
    select 1
    from public.listings listing
    join public.categories category on category.id = listing.category_id
    where listing.id = guide_service_specialties.listing_id
      and category.detail_variant = 'guide'
      and listing.status = 'publie'
      and listing.published_at is not null
  )
);

create policy "authenticated users read permitted guide service specialties"
on public.guide_service_specialties
for select
to authenticated
using (
  (
    exists (
      select 1 from public.guide_specialties specialty
      where specialty.id = guide_service_specialties.specialty_id and specialty.is_active
    )
    and exists (
      select 1
      from public.listings listing
      join public.categories category on category.id = listing.category_id
      where listing.id = guide_service_specialties.listing_id
        and category.detail_variant = 'guide'
        and listing.status = 'publie'
        and listing.published_at is not null
    )
  )
  or public.current_user_can_manage_listing(listing_id)
);

revoke all on table public.guide_languages
from public, anon, authenticated, service_role;
revoke all on table public.guide_specialties
from public, anon, authenticated, service_role;
revoke all on table public.guide_service_cities
from public, anon, authenticated, service_role;
revoke all on table public.guide_service_languages
from public, anon, authenticated, service_role;
revoke all on table public.guide_service_specialties
from public, anon, authenticated, service_role;

grant select (id, label, is_active, display_order)
on table public.guide_languages to anon, authenticated;
grant select (id, label, is_active, display_order)
on table public.guide_specialties to anon, authenticated;

grant select (listing_id, city_id, display_order)
on table public.guide_service_cities to anon, authenticated;
grant select (listing_id, language_id, display_order)
on table public.guide_service_languages to anon, authenticated;
grant select (listing_id, specialty_id, display_order)
on table public.guide_service_specialties to anon, authenticated;

grant select, insert, update, delete on table public.guide_languages to service_role;
grant select, insert, update, delete on table public.guide_specialties to service_role;
grant select, insert, update, delete on table public.guide_service_cities to service_role;
grant select, insert, update, delete on table public.guide_service_languages to service_role;
grant select, insert, update, delete on table public.guide_service_specialties to service_role;

create or replace function public.list_guide_facets_v1()
returns table (
  schema_version integer,
  facet_type text,
  facet_id text,
  label text
)
language sql
stable
security invoker
set search_path = ''
as $$
  with eligible_guides as (
    select listing.id
    from public.listings listing
    join public.categories category on category.id = listing.category_id
    join public.guide_details detail on detail.listing_id = listing.id
    join public.cities base_city on base_city.id = listing.city_id and base_city.enabled
    where category.detail_variant = 'guide'
      and listing.status = 'publie'
      and listing.published_at is not null
      and char_length(listing.name) between 3 and 80
      and listing.name = btrim(listing.name)
      and listing.name !~ '[[:cntrl:]]'
      and char_length(base_city.id) between 1 and 80
      and base_city.id ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'
      and char_length(base_city.name) between 1 and 80
      and base_city.name = btrim(base_city.name)
      and base_city.name !~ '[[:cntrl:]]'
      and (
        (listing.rating_count = 0 and listing.rating_avg is null)
        or (listing.rating_count > 0 and listing.rating_avg is not null)
      )
      and exists (
        select 1
        from public.listing_media media
        where media.listing_id = listing.id
          and media.is_cover
          and media.kind = 'image'
          and char_length(media.alt) between 1 and 280
          and media.alt = btrim(media.alt)
          and media.alt !~ '[[:cntrl:]]'
      )
      and exists (
        select 1
        from public.guide_service_cities city_link
        join public.cities city on city.id = city_link.city_id and city.enabled
        where city_link.listing_id = listing.id
          and char_length(city.id) between 1 and 80
          and city.id ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'
          and char_length(city.name) between 1 and 80
          and city.name = btrim(city.name)
          and city.name !~ '[[:cntrl:]]'
      )
      and not exists (
        select 1
        from public.guide_service_cities city_link
        join public.cities city on city.id = city_link.city_id and city.enabled
        where city_link.listing_id = listing.id
          and (
            char_length(city.id) not between 1 and 80
            or city.id !~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'
            or char_length(city.name) not between 1 and 80
            or city.name <> btrim(city.name)
            or city.name ~ '[[:cntrl:]]'
          )
      )
      and exists (
        select 1
        from public.guide_service_languages language_link
        join public.guide_languages language
          on language.id = language_link.language_id and language.is_active
        where language_link.listing_id = listing.id
      )
      and exists (
        select 1
        from public.guide_service_specialties specialty_link
        join public.guide_specialties specialty
          on specialty.id = specialty_link.specialty_id and specialty.is_active
        where specialty_link.listing_id = listing.id
      )
  ), available_facets as (
    select distinct
      10 as facet_order,
      0 as value_order,
      'city'::text as facet_type,
      city.id,
      city.name as label
    from eligible_guides guide
    join public.guide_service_cities link on link.listing_id = guide.id
    join public.cities city on city.id = link.city_id
    where city.enabled

    union all

    select distinct
      20,
      language.display_order,
      'language'::text,
      language.id,
      language.label
    from eligible_guides guide
    join public.guide_service_languages link on link.listing_id = guide.id
    join public.guide_languages language on language.id = link.language_id
    where language.is_active

    union all

    select distinct
      30,
      specialty.display_order,
      'specialty'::text,
      specialty.id,
      specialty.label
    from eligible_guides guide
    join public.guide_service_specialties link on link.listing_id = guide.id
    join public.guide_specialties specialty on specialty.id = link.specialty_id
    where specialty.is_active
  )
  select
    1,
    facet.facet_type,
    facet.id,
    facet.label
  from available_facets facet
  order by facet.facet_order, facet.value_order, facet.label, facet.id;
$$;

create or replace function public.list_guide_services_v1(
  p_city_id text default null,
  p_language_id text default null,
  p_specialty_id text default null,
  p_cursor text default null,
  p_limit integer default 20
)
returns table (
  schema_version integer,
  id uuid,
  name text,
  base_city_id text,
  base_city_name text,
  cover_image_url text,
  cover_image_alt text,
  languages jsonb,
  coverage_cities jsonb,
  specialties jsonb,
  indicative_price_xof integer,
  rating_avg numeric,
  rating_count integer,
  verified boolean,
  row_cursor text
)
language plpgsql
stable
security invoker
set search_path = ''
as $$
declare
  v_city_id text;
  v_language_id text;
  v_specialty_id text;
  v_fingerprint text;
  v_cursor_payload jsonb;
  v_cursor_rating numeric;
  v_cursor_rating_count integer;
  v_cursor_published_at timestamptz;
  v_cursor_id uuid;
begin
  if p_limit is null or p_limit < 1 or p_limit > 50 then
    raise exception using
      errcode = '22023',
      message = 'p_limit must be between 1 and 50';
  end if;

  if p_city_id is not null then
    if p_city_id <> btrim(p_city_id)
      or char_length(p_city_id) not between 1 and 80
      or p_city_id !~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'
    then
      raise exception using errcode = '22023', message = 'p_city_id is invalid';
    end if;

    v_city_id := p_city_id;

    if not exists (
      select 1 from public.cities city
      where city.id = v_city_id and city.enabled
    ) then
      raise exception using errcode = '22023', message = 'p_city_id is unknown';
    end if;
  end if;

  if p_language_id is not null then
    if p_language_id <> btrim(p_language_id)
      or char_length(p_language_id) not between 1 and 80
      or p_language_id !~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'
    then
      raise exception using errcode = '22023', message = 'p_language_id is invalid';
    end if;

    v_language_id := p_language_id;

    if not exists (
      select 1 from public.guide_languages language
      where language.id = v_language_id and language.is_active
    ) then
      raise exception using errcode = '22023', message = 'p_language_id is unknown';
    end if;
  end if;

  if p_specialty_id is not null then
    if p_specialty_id <> btrim(p_specialty_id)
      or char_length(p_specialty_id) not between 1 and 80
      or p_specialty_id !~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'
    then
      raise exception using errcode = '22023', message = 'p_specialty_id is invalid';
    end if;

    v_specialty_id := p_specialty_id;

    if not exists (
      select 1 from public.guide_specialties specialty
      where specialty.id = v_specialty_id and specialty.is_active
    ) then
      raise exception using errcode = '22023', message = 'p_specialty_id is unknown';
    end if;
  end if;

  v_fingerprint := md5(
    jsonb_build_object(
      'city_id', v_city_id,
      'language_id', v_language_id,
      'specialty_id', v_specialty_id
    )::text
  );

  if p_cursor is not null then
    if btrim(p_cursor) = ''
      or char_length(p_cursor) > 4096
      or p_cursor ~ '[[:space:]]'
    then
      raise exception using errcode = '22023', message = 'p_cursor is invalid';
    end if;

    begin
      v_cursor_payload := convert_from(decode(p_cursor, 'base64'), 'UTF8')::jsonb;
    exception
      when others then
        raise exception using errcode = '22023', message = 'p_cursor is malformed';
    end;

    if jsonb_typeof(v_cursor_payload) <> 'object'
      or jsonb_typeof(v_cursor_payload -> 'v') <> 'number'
      or v_cursor_payload ->> 'v' <> '1'
    then
      raise exception using errcode = '22023', message = 'p_cursor version is unsupported';
    end if;

    if jsonb_typeof(v_cursor_payload -> 'fingerprint') <> 'string'
      or jsonb_typeof(v_cursor_payload -> 'rating') <> 'number'
      or jsonb_typeof(v_cursor_payload -> 'rating_count') <> 'number'
      or jsonb_typeof(v_cursor_payload -> 'published_at') <> 'string'
      or jsonb_typeof(v_cursor_payload -> 'id') <> 'string'
      or v_cursor_payload - array[
        'v', 'fingerprint', 'rating', 'rating_count', 'published_at', 'id'
      ]::text[] <> '{}'::jsonb
    then
      raise exception using errcode = '22023', message = 'p_cursor fields are malformed';
    end if;

    if v_cursor_payload ->> 'fingerprint' <> v_fingerprint then
      raise exception using
        errcode = '22023',
        message = 'p_cursor does not match guide filters';
    end if;

    if v_cursor_payload ->> 'rating' !~ '^-?[0-9]+(?:\.[0-9]+)?$'
      or v_cursor_payload ->> 'rating_count' !~ '^[0-9]+$'
    then
      raise exception using errcode = '22023', message = 'p_cursor fields are malformed';
    end if;

    begin
      v_cursor_rating := (v_cursor_payload ->> 'rating')::numeric;
      v_cursor_rating_count := (v_cursor_payload ->> 'rating_count')::integer;
      v_cursor_published_at := (v_cursor_payload ->> 'published_at')::timestamptz;
      v_cursor_id := (v_cursor_payload ->> 'id')::uuid;
    exception
      when others then
        raise exception using errcode = '22023', message = 'p_cursor fields are malformed';
    end;

    if v_cursor_rating < -1
      or v_cursor_rating > 5
      or v_cursor_rating_count < 0
      or not isfinite(v_cursor_published_at)
    then
      raise exception using errcode = '22023', message = 'p_cursor fields are invalid';
    end if;
  end if;

  return query
  with ranked_guides as (
    select
      listing.id,
      listing.name,
      base_city.id as base_city_id,
      base_city.name as base_city_name,
      cover.url as cover_image_url,
      cover.alt as cover_image_alt,
      (
        select jsonb_agg(
          jsonb_build_object('id', city.id, 'label', city.name)
          order by link.display_order, city.name, city.id
        )
        from public.guide_service_cities link
        join public.cities city on city.id = link.city_id
        where link.listing_id = listing.id and city.enabled
      ) as coverage_cities,
      (
        select jsonb_agg(
          jsonb_build_object('id', language.id, 'label', language.label)
          order by link.display_order, language.display_order, language.id
        )
        from public.guide_service_languages link
        join public.guide_languages language on language.id = link.language_id
        where link.listing_id = listing.id and language.is_active
      ) as languages,
      (
        select jsonb_agg(
          jsonb_build_object('id', specialty.id, 'label', specialty.label)
          order by link.display_order, specialty.display_order, specialty.id
        )
        from public.guide_service_specialties link
        join public.guide_specialties specialty on specialty.id = link.specialty_id
        where link.listing_id = listing.id and specialty.is_active
      ) as specialties,
      detail.indicative_price_xof,
      listing.rating_avg,
      listing.rating_count,
      listing.verified,
      listing.published_at,
      coalesce(listing.rating_avg, -1::numeric) as sort_rating
    from public.listings listing
    join public.categories category on category.id = listing.category_id
    join public.guide_details detail on detail.listing_id = listing.id
    join public.cities base_city on base_city.id = listing.city_id and base_city.enabled
    join lateral (
      select media.url, media.alt
      from public.listing_media media
      where media.listing_id = listing.id
        and media.is_cover
        and media.kind = 'image'
      order by media.display_order
      limit 1
    ) cover on true
    where category.detail_variant = 'guide'
      and listing.status = 'publie'
      and listing.published_at is not null
      and char_length(listing.name) between 3 and 80
      and listing.name = btrim(listing.name)
      and listing.name !~ '[[:cntrl:]]'
      and char_length(cover.alt) between 1 and 280
      and cover.alt = btrim(cover.alt)
      and cover.alt !~ '[[:cntrl:]]'
      and char_length(base_city.id) between 1 and 80
      and base_city.id ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'
      and char_length(base_city.name) between 1 and 80
      and base_city.name = btrim(base_city.name)
      and base_city.name !~ '[[:cntrl:]]'
      and (
        (listing.rating_count = 0 and listing.rating_avg is null)
        or (listing.rating_count > 0 and listing.rating_avg is not null)
      )
      and exists (
        select 1
        from public.guide_service_cities link
        join public.cities city on city.id = link.city_id and city.enabled
        where link.listing_id = listing.id
          and char_length(city.id) between 1 and 80
          and city.id ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'
          and char_length(city.name) between 1 and 80
          and city.name = btrim(city.name)
          and city.name !~ '[[:cntrl:]]'
      )
      and not exists (
        select 1
        from public.guide_service_cities link
        join public.cities city on city.id = link.city_id and city.enabled
        where link.listing_id = listing.id
          and (
            char_length(city.id) not between 1 and 80
            or city.id !~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'
            or char_length(city.name) not between 1 and 80
            or city.name <> btrim(city.name)
            or city.name ~ '[[:cntrl:]]'
          )
      )
      and exists (
        select 1
        from public.guide_service_languages link
        join public.guide_languages language
          on language.id = link.language_id and language.is_active
        where link.listing_id = listing.id
      )
      and exists (
        select 1
        from public.guide_service_specialties link
        join public.guide_specialties specialty
          on specialty.id = link.specialty_id and specialty.is_active
        where link.listing_id = listing.id
      )
      and (
        v_city_id is null
        or exists (
          select 1 from public.guide_service_cities link
          where link.listing_id = listing.id and link.city_id = v_city_id
        )
      )
      and (
        v_language_id is null
        or exists (
          select 1 from public.guide_service_languages link
          where link.listing_id = listing.id and link.language_id = v_language_id
        )
      )
      and (
        v_specialty_id is null
        or exists (
          select 1 from public.guide_service_specialties link
          where link.listing_id = listing.id and link.specialty_id = v_specialty_id
        )
      )
  ),
  guide_page as (
    select guide.*
    from ranked_guides guide
    where p_cursor is null
      or (
        guide.sort_rating,
        guide.rating_count,
        guide.published_at,
        guide.id
      ) < (
        v_cursor_rating,
        v_cursor_rating_count,
        v_cursor_published_at,
        v_cursor_id
      )
    order by
      guide.sort_rating desc,
      guide.rating_count desc,
      guide.published_at desc,
      guide.id desc
    limit (p_limit + 1)
  )
  select
    1,
    page.id,
    page.name,
    page.base_city_id,
    page.base_city_name,
    page.cover_image_url,
    page.cover_image_alt,
    page.languages,
    page.coverage_cities,
    page.specialties,
    page.indicative_price_xof,
    page.rating_avg,
    page.rating_count,
    page.verified,
    replace(
      replace(
        encode(
          convert_to(
            jsonb_build_object(
              'v', 1,
              'fingerprint', v_fingerprint,
              'rating', page.sort_rating,
              'rating_count', page.rating_count,
              'published_at', page.published_at,
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
    )
  from guide_page page
  order by
    page.sort_rating desc,
    page.rating_count desc,
    page.published_at desc,
    page.id desc;
end;
$$;

comment on function public.list_guide_facets_v1() is
  'Versioned publication-only guide discovery facets. facet_type is exactly city, language or specialty.';

comment on function public.list_guide_services_v1(text, text, text, text, integer) is
  'Versioned publication-only guide discovery cards with AND filters and filter-bound keyset cursors. Sponsorship and authority data are intentionally absent.';

revoke all on function public.list_guide_facets_v1()
from public, anon, authenticated, service_role;
revoke all on function public.list_guide_services_v1(text, text, text, text, integer)
from public, anon, authenticated, service_role;

grant execute on function public.list_guide_facets_v1()
to anon, authenticated;
grant execute on function public.list_guide_services_v1(text, text, text, text, integer)
to anon, authenticated;

commit;
