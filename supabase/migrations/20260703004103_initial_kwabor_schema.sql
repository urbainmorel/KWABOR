create schema if not exists extensions;

create extension if not exists pgcrypto with schema extensions;
create extension if not exists postgis with schema extensions;

create type public.listing_type as enum ('lieu', 'etablissement', 'evenement');
create type public.listing_class as enum ('patrimonial', 'commercial', 'evenementiel');
create type public.listing_status as enum ('brouillon', 'en_attente', 'publie', 'rejete', 'archive');
create type public.price_unit as enum ('par_nuit', 'par_personne', 'consommation', 'par_entree', 'aucune');
create type public.user_role as enum ('user', 'guide', 'institution', 'promoteur', 'admin');
create type public.role_verification_status as enum ('unverified', 'pending', 'verified', 'rejected');
create type public.claim_status as enum ('en_attente', 'approuve', 'refuse');
create type public.report_status as enum ('nouveau', 'en_revue', 'traite', 'rejete');
create type public.social_media_type as enum ('photo', 'diaporama', 'video');
create type public.social_post_status as enum ('en_attente', 'publie', 'rejete', 'masque');
create type public.notification_type as enum ('social', 'listing', 'promotion', 'system');
create type public.campaign_status as enum ('brouillon', 'en_attente_paiement', 'active', 'terminee');
create type public.payment_status as enum ('en_cours', 'reussi', 'echoue');

create table public.cities (
  id text primary key,
  name text not null,
  slug text not null unique,
  country_code char(2) not null default 'BJ',
  latitude numeric(9, 6),
  longitude numeric(9, 6),
  enabled boolean not null default true,
  created_at timestamptz not null default now(),
  constraint cities_country_code_benin check (country_code = 'BJ'),
  constraint cities_name_not_blank check (length(trim(name)) > 0)
);

create table public.categories (
  id text primary key,
  listing_type public.listing_type not null,
  subtype text not null,
  name_key text not null,
  default_listing_class public.listing_class not null,
  sort_order integer not null default 0,
  created_at timestamptz not null default now(),
  constraint categories_subtype_not_blank check (length(trim(subtype)) > 0),
  constraint categories_name_key_not_blank check (length(trim(name_key)) > 0),
  constraint categories_unique_subtype unique (listing_type, subtype)
);

create table public.profiles (
  user_id uuid primary key references auth.users (id) on delete cascade,
  first_name text not null,
  last_name text not null,
  avatar_url text,
  cover_url text,
  bio text,
  city_id text references public.cities (id) on delete set null,
  preferred_locale text not null default 'fr',
  preferred_currency text not null default 'XOF',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint profiles_first_name_not_blank check (length(trim(first_name)) > 0),
  constraint profiles_last_name_not_blank check (length(trim(last_name)) > 0),
  constraint profiles_locale_supported check (preferred_locale in ('fr', 'en', 'pt', 'de', 'es', 'it')),
  constraint profiles_currency_supported check (preferred_currency in ('XOF', 'NGN', 'USD', 'EUR'))
);

create table public.user_roles (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users (id) on delete cascade,
  role public.user_role not null,
  verification_status public.role_verification_status not null default 'unverified',
  verified boolean generated always as (verification_status = 'verified') stored,
  proof_url text,
  rejection_reason text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint user_roles_unique_role unique (user_id, role),
  constraint user_roles_user_role_unverified check (
    role <> 'user'
    or verification_status in ('unverified', 'verified')
  )
);

create table public.listings (
  id uuid primary key default gen_random_uuid(),
  type public.listing_type not null,
  subtype text not null,
  listing_class public.listing_class not null,
  category_id text not null references public.categories (id),
  owner_id uuid references auth.users (id) on delete set null,
  steward_id uuid references auth.users (id) on delete set null,
  submitted_by uuid references auth.users (id) on delete set null,
  status public.listing_status not null default 'en_attente',
  name text not null,
  slug text not null unique,
  description text not null,
  content_lang text not null default 'fr',
  city_id text not null references public.cities (id),
  district text,
  address text,
  lat numeric(9, 6),
  lng numeric(9, 6),
  geog extensions.geography(Point, 4326) generated always as (
    case
      when lat is null or lng is null then null
      else extensions.st_setsrid(extensions.st_makepoint(lng, lat), 4326)::extensions.geography
    end
  ) stored,
  google_place_id text,
  price_from_xof integer,
  price_unit public.price_unit not null default 'aucune',
  price_tier smallint,
  opening_hours jsonb not null default '{}'::jsonb,
  contact_phone text,
  contact_whatsapp text,
  external_url text,
  email text,
  socials jsonb not null default '{}'::jsonb,
  tags text[] not null default '{}',
  verified boolean not null default false,
  sponsored_until timestamptz,
  editorial_pin_until timestamptz,
  rating_avg numeric(3, 2),
  rating_count integer not null default 0,
  views_count integer not null default 0,
  likes_count integer not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  published_at timestamptz,
  constraint listings_name_length check (length(trim(name)) between 3 and 120),
  constraint listings_description_length check (length(trim(description)) between 40 and 2000),
  constraint listings_content_lang_supported check (content_lang in ('fr', 'en', 'pt', 'de', 'es', 'it')),
  constraint listings_price_xof_positive check (price_from_xof is null or price_from_xof >= 0),
  constraint listings_price_unit_consistent check (
    (price_from_xof is null and price_unit = 'aucune')
    or (price_from_xof is not null and price_unit <> 'aucune')
  ),
  constraint listings_price_tier_range check (price_tier is null or price_tier between 1 and 4),
  constraint listings_rating_avg_range check (rating_avg is null or rating_avg between 0 and 5),
  constraint listings_counts_positive check (
    rating_count >= 0
    and views_count >= 0
    and likes_count >= 0
  ),
  constraint listings_lat_benin_range check (lat is null or lat between 6.0 and 12.6),
  constraint listings_lng_benin_range check (lng is null or lng between 0.7 and 4.2),
  constraint listings_heritage_has_no_private_owner check (
    listing_class <> 'patrimonial'
    or owner_id is null
  ),
  constraint listings_editorial_not_sponsored check (
    editorial_pin_until is null
    or sponsored_until is null
    or editorial_pin_until <> sponsored_until
  )
);

create table public.listing_media (
  id uuid primary key default gen_random_uuid(),
  listing_id uuid not null references public.listings (id) on delete cascade,
  storage_path text,
  url text not null,
  alt text not null,
  display_order integer not null default 0,
  is_cover boolean not null default false,
  created_at timestamptz not null default now(),
  constraint listing_media_url_not_blank check (length(trim(url)) > 0),
  constraint listing_media_alt_not_blank check (length(trim(alt)) > 0),
  constraint listing_media_display_order_positive check (display_order >= 0)
);

create table public.social_posts (
  id uuid primary key default gen_random_uuid(),
  author_id uuid not null references auth.users (id) on delete cascade,
  social_media_type public.social_media_type not null,
  listing_id uuid not null references public.listings (id) on delete restrict,
  caption text,
  content_lang text not null default 'fr',
  moderation_status public.social_post_status not null default 'en_attente',
  watermark_applied boolean not null default false,
  likes_count integer not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint social_posts_content_lang_supported check (content_lang in ('fr', 'en', 'pt', 'de', 'es', 'it')),
  constraint social_posts_likes_positive check (likes_count >= 0)
);

create table public.social_media (
  id uuid primary key default gen_random_uuid(),
  post_id uuid not null references public.social_posts (id) on delete cascade,
  storage_path text,
  url text not null,
  alt text not null,
  display_order integer not null default 0,
  created_at timestamptz not null default now(),
  constraint social_media_url_not_blank check (length(trim(url)) > 0),
  constraint social_media_alt_not_blank check (length(trim(alt)) > 0),
  constraint social_media_display_order_positive check (display_order >= 0),
  constraint social_media_unique_order unique (post_id, display_order)
);

create table public.favorites (
  user_id uuid not null references auth.users (id) on delete cascade,
  listing_id uuid not null references public.listings (id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (user_id, listing_id)
);

create table public.likes (
  user_id uuid not null references auth.users (id) on delete cascade,
  listing_id uuid not null references public.listings (id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (user_id, listing_id)
);

create table public.notifications (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users (id) on delete cascade,
  type public.notification_type not null,
  title_key text not null,
  body_key text not null,
  related_listing_id uuid references public.listings (id) on delete set null,
  sponsored boolean not null default false,
  read boolean not null default false,
  created_at timestamptz not null default now(),
  constraint notifications_title_key_not_blank check (length(trim(title_key)) > 0),
  constraint notifications_body_key_not_blank check (length(trim(body_key)) > 0)
);

create table public.claims (
  id uuid primary key default gen_random_uuid(),
  listing_id uuid not null references public.listings (id) on delete cascade,
  claimant_id uuid not null references auth.users (id) on delete cascade,
  proof_url text,
  contact_phone text,
  status public.claim_status not null default 'en_attente',
  decision_reason text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint claims_unique_pending unique (listing_id, claimant_id),
  constraint claims_proof_or_contact_required check (
    proof_url is not null
    or contact_phone is not null
  )
);

create table public.missing_place_reports (
  id uuid primary key default gen_random_uuid(),
  reporter_id uuid not null references auth.users (id) on delete cascade,
  name text not null,
  presumed_type public.listing_type not null,
  city_id text not null references public.cities (id),
  lat numeric(9, 6),
  lng numeric(9, 6),
  note text,
  photo_url text,
  status public.report_status not null default 'nouveau',
  assigned_admin_id uuid references auth.users (id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint missing_place_reports_name_not_blank check (length(trim(name)) > 0),
  constraint missing_place_reports_lat_benin_range check (lat is null or lat between 6.0 and 12.6),
  constraint missing_place_reports_lng_benin_range check (lng is null or lng between 0.7 and 4.2)
);

create table public.campaigns (
  id uuid primary key default gen_random_uuid(),
  listing_id uuid not null references public.listings (id) on delete cascade,
  owner_id uuid not null references auth.users (id) on delete cascade,
  city_ids text[] not null,
  cost_xof integer not null,
  status public.campaign_status not null default 'brouillon',
  starts_at timestamptz not null,
  ends_at timestamptz not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint campaigns_city_ids_not_empty check (array_length(city_ids, 1) > 0),
  constraint campaigns_cost_xof_positive check (cost_xof > 0),
  constraint campaigns_period_valid check (ends_at > starts_at)
);

create table public.payments (
  id uuid primary key default gen_random_uuid(),
  campaign_id uuid not null references public.campaigns (id) on delete cascade,
  payer_id uuid not null references auth.users (id) on delete cascade,
  amount_xof integer not null,
  status public.payment_status not null default 'en_cours',
  provider text not null,
  provider_reference text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint payments_amount_xof_positive check (amount_xof > 0),
  constraint payments_provider_not_blank check (length(trim(provider)) > 0)
);

create index listings_city_status_idx on public.listings (city_id, status);
create index listings_category_status_idx on public.listings (category_id, status);
create index listings_owner_idx on public.listings (owner_id);
create index listings_steward_idx on public.listings (steward_id);
create index listings_geog_idx on public.listings using gist (geog);
create index listing_media_listing_order_idx on public.listing_media (listing_id, display_order);
create index social_posts_listing_status_idx on public.social_posts (listing_id, moderation_status);
create index social_posts_author_idx on public.social_posts (author_id);
create index social_media_post_order_idx on public.social_media (post_id, display_order);
create index notifications_user_created_idx on public.notifications (user_id, created_at desc);
create index campaigns_owner_status_idx on public.campaigns (owner_id, status);
create index payments_payer_status_idx on public.payments (payer_id, status);

create or replace function public.touch_updated_at()
returns trigger
language plpgsql
set search_path = public
as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

create trigger profiles_touch_updated_at
before update on public.profiles
for each row execute function public.touch_updated_at();

create trigger user_roles_touch_updated_at
before update on public.user_roles
for each row execute function public.touch_updated_at();

create trigger listings_touch_updated_at
before update on public.listings
for each row execute function public.touch_updated_at();

create trigger social_posts_touch_updated_at
before update on public.social_posts
for each row execute function public.touch_updated_at();

create trigger claims_touch_updated_at
before update on public.claims
for each row execute function public.touch_updated_at();

create trigger missing_place_reports_touch_updated_at
before update on public.missing_place_reports
for each row execute function public.touch_updated_at();

create trigger campaigns_touch_updated_at
before update on public.campaigns
for each row execute function public.touch_updated_at();

create trigger payments_touch_updated_at
before update on public.payments
for each row execute function public.touch_updated_at();

create or replace function public.current_user_has_verified_role(required_role public.user_role)
returns boolean
language plpgsql
stable
set search_path = public
as $$
begin
  if (select auth.uid()) is null then
    return false;
  end if;

  return exists (
    select 1
    from public.user_roles role_check
    where role_check.user_id = (select auth.uid())
      and role_check.role = required_role
      and role_check.verified
  );
end;
$$;

create or replace function public.current_user_can_manage_listing(target_listing_id uuid)
returns boolean
language sql
stable
set search_path = public
as $$
  select exists (
    select 1
    from public.listings managed_listing
    where managed_listing.id = target_listing_id
      and (
        public.current_user_has_verified_role('admin')
        or managed_listing.owner_id = (select auth.uid())
        or managed_listing.steward_id = (select auth.uid())
        or (
          managed_listing.listing_class = 'patrimonial'
          and public.current_user_has_verified_role('institution')
        )
      )
  );
$$;

create or replace function public.reject_heritage_claim()
returns trigger
language plpgsql
set search_path = public
as $$
declare
  target_class public.listing_class;
begin
  select listing_class
  into target_class
  from public.listings
  where id = new.listing_id;

  if target_class = 'patrimonial' then
    raise exception 'Patrimonial listings cannot be claimed'
      using errcode = '23514';
  end if;

  return new;
end;
$$;

create trigger claims_reject_heritage_claim
before insert or update of listing_id on public.claims
for each row execute function public.reject_heritage_claim();

alter table public.cities enable row level security;
alter table public.categories enable row level security;
alter table public.profiles enable row level security;
alter table public.user_roles enable row level security;
alter table public.listings enable row level security;
alter table public.listing_media enable row level security;
alter table public.social_posts enable row level security;
alter table public.social_media enable row level security;
alter table public.favorites enable row level security;
alter table public.likes enable row level security;
alter table public.notifications enable row level security;
alter table public.claims enable row level security;
alter table public.missing_place_reports enable row level security;
alter table public.campaigns enable row level security;
alter table public.payments enable row level security;

create policy "cities are readable"
on public.cities
for select
to anon, authenticated
using (enabled);

create policy "categories are readable"
on public.categories
for select
to anon, authenticated
using (true);

create policy "profiles are publicly readable"
on public.profiles
for select
to anon, authenticated
using (true);

create policy "users create their profile"
on public.profiles
for insert
to authenticated
with check ((select auth.uid()) = user_id);

create policy "users update their profile"
on public.profiles
for update
to authenticated
using ((select auth.uid()) = user_id)
with check ((select auth.uid()) = user_id);

create policy "users read their roles"
on public.user_roles
for select
to authenticated
using ((select auth.uid()) = user_id);

create policy "users create their base role"
on public.user_roles
for insert
to authenticated
with check (
  (select auth.uid()) = user_id
  and role = 'user'
  and verification_status in ('unverified', 'verified')
);

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
);

create policy "verified roles create allowed listings"
on public.listings
for insert
to authenticated
with check (
  submitted_by = (select auth.uid())
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

create policy "verified roles update allowed listings"
on public.listings
for update
to authenticated
using (
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
with check (
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
);

create policy "published listing media are readable"
on public.listing_media
for select
to anon, authenticated
using (
  exists (
    select 1
    from public.listings listing
    where listing.id = listing_media.listing_id
      and listing.status = 'publie'
  )
);

create policy "listing managers create media"
on public.listing_media
for insert
to authenticated
with check (public.current_user_can_manage_listing(listing_id));

create policy "listing managers update media"
on public.listing_media
for update
to authenticated
using (public.current_user_can_manage_listing(listing_id))
with check (public.current_user_can_manage_listing(listing_id));

create policy "listing managers delete media"
on public.listing_media
for delete
to authenticated
using (public.current_user_can_manage_listing(listing_id));

create policy "published social posts are readable"
on public.social_posts
for select
to anon, authenticated
using (
  moderation_status = 'publie'
  or author_id = (select auth.uid())
  or public.current_user_has_verified_role('admin')
);

create policy "authenticated users create attached social posts"
on public.social_posts
for insert
to authenticated
with check (
  author_id = (select auth.uid())
  and listing_id is not null
);

create policy "authors update pending social posts"
on public.social_posts
for update
to authenticated
using (
  author_id = (select auth.uid())
  or public.current_user_has_verified_role('admin')
)
with check (
  public.current_user_has_verified_role('admin')
  or (
    author_id = (select auth.uid())
    and moderation_status in ('en_attente', 'rejete')
  )
);

create policy "authors delete social posts"
on public.social_posts
for delete
to authenticated
using (
  author_id = (select auth.uid())
  or public.current_user_has_verified_role('admin')
);

create policy "readable social media follows post"
on public.social_media
for select
to anon, authenticated
using (
  exists (
    select 1
    from public.social_posts post
    where post.id = social_media.post_id
      and (
        post.moderation_status = 'publie'
        or post.author_id = (select auth.uid())
        or public.current_user_has_verified_role('admin')
      )
  )
);

create policy "authors create social media"
on public.social_media
for insert
to authenticated
with check (
  exists (
    select 1
    from public.social_posts post
    where post.id = social_media.post_id
      and post.author_id = (select auth.uid())
  )
);

create policy "authors update social media"
on public.social_media
for update
to authenticated
using (
  exists (
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
  exists (
    select 1
    from public.social_posts post
    where post.id = social_media.post_id
      and (
        post.author_id = (select auth.uid())
        or public.current_user_has_verified_role('admin')
      )
  )
);

create policy "authors delete social media"
on public.social_media
for delete
to authenticated
using (
  exists (
    select 1
    from public.social_posts post
    where post.id = social_media.post_id
      and (
        post.author_id = (select auth.uid())
        or public.current_user_has_verified_role('admin')
      )
  )
);

create policy "users manage their favorites"
on public.favorites
for all
to authenticated
using ((select auth.uid()) = user_id)
with check ((select auth.uid()) = user_id);

create policy "users manage their likes"
on public.likes
for all
to authenticated
using ((select auth.uid()) = user_id)
with check ((select auth.uid()) = user_id);

create policy "users read their notifications"
on public.notifications
for select
to authenticated
using ((select auth.uid()) = user_id);

create policy "users mark their notifications read"
on public.notifications
for update
to authenticated
using ((select auth.uid()) = user_id)
with check ((select auth.uid()) = user_id);

create policy "users read their claims"
on public.claims
for select
to authenticated
using (
  claimant_id = (select auth.uid())
  or public.current_user_has_verified_role('admin')
);

create policy "verified promoters submit claims"
on public.claims
for insert
to authenticated
with check (
  claimant_id = (select auth.uid())
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

create policy "admins update claims"
on public.claims
for update
to authenticated
using (public.current_user_has_verified_role('admin'))
with check (public.current_user_has_verified_role('admin'));

create policy "users read their missing place reports"
on public.missing_place_reports
for select
to authenticated
using (
  reporter_id = (select auth.uid())
  or public.current_user_has_verified_role('admin')
);

create policy "authenticated users create missing place reports"
on public.missing_place_reports
for insert
to authenticated
with check (reporter_id = (select auth.uid()));

create policy "admins update missing place reports"
on public.missing_place_reports
for update
to authenticated
using (public.current_user_has_verified_role('admin'))
with check (public.current_user_has_verified_role('admin'));

create policy "campaign owners read campaigns"
on public.campaigns
for select
to authenticated
using (
  owner_id = (select auth.uid())
  or public.current_user_has_verified_role('admin')
);

create policy "payment owners read payments"
on public.payments
for select
to authenticated
using (
  payer_id = (select auth.uid())
  or public.current_user_has_verified_role('admin')
);

revoke all privileges on all tables in schema public from anon, authenticated;

grant usage on schema public to anon, authenticated;

grant select on table public.cities to anon, authenticated;
grant select on table public.categories to anon, authenticated;
grant select on table public.profiles to anon, authenticated;
grant insert, update on table public.profiles to authenticated;
grant select, insert on table public.user_roles to authenticated;
grant select on table public.listings to anon, authenticated;
grant insert, update on table public.listings to authenticated;
grant select on table public.listing_media to anon, authenticated;
grant insert, update, delete on table public.listing_media to authenticated;
grant select on table public.social_posts to anon, authenticated;
grant insert, update, delete on table public.social_posts to authenticated;
grant select on table public.social_media to anon, authenticated;
grant insert, update, delete on table public.social_media to authenticated;
grant select, insert, delete on table public.favorites to authenticated;
grant select, insert, delete on table public.likes to authenticated;
grant select on table public.notifications to authenticated;
grant update (read) on table public.notifications to authenticated;
grant select, insert on table public.claims to authenticated;
grant update on table public.claims to authenticated;
grant select, insert on table public.missing_place_reports to authenticated;
grant update on table public.missing_place_reports to authenticated;
grant select on table public.campaigns to authenticated;
grant select on table public.payments to authenticated;
