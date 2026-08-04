begin;

set local lock_timeout = '5s';

alter table public.favorites
add constraint favorites_created_at_finite check (
  created_at >= '0001-01-01 00:00:00+00'::timestamptz
  and created_at < '10000-01-01 00:00:00+00'::timestamptz
) not valid;

alter table public.favorites
validate constraint favorites_created_at_finite;

create index favorites_owner_recent_idx
on public.favorites (
  user_id,
  created_at desc,
  listing_id desc
);

comment on index public.favorites_owner_recent_idx is
  'Supports the owner-only newest-favorite keyset read model without sponsored ranking.';

create or replace function app_private.favorite_owner_write_allowed_v1(
  p_user_id uuid
)
returns boolean
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  current_user_id uuid := (select auth.uid());
begin
  if current_user_id is null or current_user_id is distinct from p_user_id then
    return false;
  end if;

  -- This lock is also held by account deletion. Because this function is
  -- VOLATILE, the checks below run after the wait with current visibility,
  -- rather than reusing the direct INSERT/DELETE statement's old snapshot.
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(current_user_id::text, 0)
  );

  return exists (
    select 1
    from public.profiles as profile
    where profile.user_id = current_user_id
      and profile.onboarding_completed_at is not null
  ) and not exists (
    select 1
    from public.account_deletion_requests as deletion_request
    where deletion_request.user_id = current_user_id
  );
end;
$$;

revoke all
on function app_private.favorite_owner_write_allowed_v1(uuid)
from public, anon, authenticated, service_role;

grant execute
on function app_private.favorite_owner_write_allowed_v1(uuid)
to authenticated;

comment on function app_private.favorite_owner_write_allowed_v1(uuid) is
  'Serializes owner-only legacy table writes with account deletion and rejects writes after its tombstone.';

-- Preserve the released direct-table compatibility surface and policy names,
-- but make both mutations participate in the account deletion lock.
alter policy "users favorite published listings"
on public.favorites
with check (
  (select app_private.current_user_has_completed_onboarding())
  and app_private.favorite_owner_write_allowed_v1(user_id)
  and exists (
    select 1
    from public.listings as listing
    where listing.id = favorites.listing_id
      and listing.status = 'publie'
      and listing.published_at is not null
  )
);

alter policy "users delete their favorites"
on public.favorites
using (
  (select app_private.current_user_has_completed_onboarding())
  and app_private.favorite_owner_write_allowed_v1(user_id)
);

-- Released direct-table clients only need the relation identifiers. Keeping
-- created_at writable would let a caller forge the owner-private recency order
-- or create a relation that stays hidden behind the read model's as_of fence.
revoke insert on table public.favorites from authenticated;
grant insert (user_id, listing_id) on table public.favorites to authenticated;

create or replace function public.list_favorite_listing_summaries_v1(
  p_listing_type text default null,
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
  city_name text,
  category_id text,
  cover_image_url text,
  cover_image_alt text,
  price_from_xof integer,
  rating_avg numeric,
  likes_count integer,
  verified boolean,
  liked_by_current_user boolean,
  favorited_by_current_user boolean,
  favorited_at timestamptz,
  event_start_at timestamptz,
  event_end_at timestamptz,
  is_event_ended boolean,
  is_sponsored_placement boolean,
  row_cursor text
)
language plpgsql
volatile
security invoker
set search_path = ''
as $$
declare
  current_user_id uuid := (select auth.uid());
  v_listing_type public.listing_type;
  v_fingerprint text;
  v_cursor_payload jsonb;
  v_as_of timestamptz := pg_catalog.statement_timestamp();
  v_cursor_favorited_at timestamptz := 'infinity'::timestamptz;
  v_cursor_id uuid := 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid;
begin
  if current_user_id is null then
    raise insufficient_privilege
      using message = 'Authentication required';
  end if;

  if p_limit is null or p_limit < 1 or p_limit > 50 then
    raise invalid_parameter_value
      using message = 'p_limit must be between 1 and 50';
  end if;

  if p_listing_type is not null then
    begin
      v_listing_type :=
        pg_catalog.lower(pg_catalog.btrim(p_listing_type))::public.listing_type;
    exception
      when invalid_text_representation then
        raise invalid_parameter_value
          using message = 'p_listing_type is invalid';
    end;
  end if;

  v_fingerprint := pg_catalog.md5(
    pg_catalog.jsonb_build_object(
      'contract', 'favorite-listing-summaries-v1',
      'listing_type', v_listing_type::text,
      'user_id', current_user_id
    )::text
  );

  if p_cursor is not null then
    if pg_catalog.btrim(p_cursor) = ''
      or pg_catalog.char_length(p_cursor) > 4096
      or p_cursor ~ '[[:space:]]'
    then
      raise invalid_parameter_value
        using message = 'p_cursor is invalid';
    end if;

    begin
      v_cursor_payload := pg_catalog.convert_from(
        pg_catalog.decode(p_cursor, 'base64'),
        'UTF8'
      )::jsonb;
    exception
      when others then
        raise invalid_parameter_value
          using message = 'p_cursor is malformed';
    end;

    if pg_catalog.jsonb_typeof(v_cursor_payload) is distinct from 'object'
      or pg_catalog.jsonb_typeof(v_cursor_payload -> 'v') is distinct from 'number'
      or v_cursor_payload ->> 'v' is distinct from '1'
    then
      raise invalid_parameter_value
        using message = 'p_cursor version is unsupported';
    end if;

    if pg_catalog.jsonb_typeof(v_cursor_payload -> 'as_of') is distinct from 'string'
      or pg_catalog.jsonb_typeof(v_cursor_payload -> 'fingerprint') is distinct from 'string'
      or pg_catalog.jsonb_typeof(v_cursor_payload -> 'favorited_at') is distinct from 'string'
      or pg_catalog.jsonb_typeof(v_cursor_payload -> 'id') is distinct from 'string'
    then
      raise invalid_parameter_value
        using message = 'p_cursor fields are malformed';
    end if;

    if v_cursor_payload ->> 'fingerprint' is distinct from v_fingerprint then
      raise invalid_parameter_value
        using message = 'p_cursor does not match favorite filters or owner';
    end if;

    begin
      v_as_of := (v_cursor_payload ->> 'as_of')::timestamptz;
      v_cursor_favorited_at :=
        (v_cursor_payload ->> 'favorited_at')::timestamptz;
      v_cursor_id := (v_cursor_payload ->> 'id')::uuid;
    exception
      when others then
        raise invalid_parameter_value
          using message = 'p_cursor fields are malformed';
    end;

    if v_as_of < '0001-01-01 00:00:00+00'::timestamptz
      or v_as_of > pg_catalog.statement_timestamp()
      or v_as_of >= '10000-01-01 00:00:00+00'::timestamptz
      or v_cursor_favorited_at < '0001-01-01 00:00:00+00'::timestamptz
      or v_cursor_favorited_at >= '10000-01-01 00:00:00+00'::timestamptz
      or v_cursor_favorited_at > v_as_of
    then
      raise invalid_parameter_value
        using message = 'p_cursor fields are invalid';
    end if;
  end if;

  -- Account deletion takes the matching exclusive lock. The shared lock keeps
  -- this private snapshot ahead of cleanup or makes it observe the tombstone.
  perform pg_catalog.pg_advisory_xact_lock_shared(
    pg_catalog.hashtextextended(current_user_id::text, 0)
  );
  perform app_private.require_completed_onboarding();

  return query
  with favorite_page as (
    select
      listing.id,
      listing.type,
      listing.listing_class,
      listing.status,
      listing.name,
      listing.city_id,
      city.name as city_name,
      listing.category_id,
      cover.url as cover_image_url,
      cover.alt as cover_image_alt,
      listing.price_from_xof,
      listing.rating_avg,
      listing.likes_count,
      listing.verified,
      exists (
        select 1
        from public.likes as viewer_like
        where viewer_like.user_id = current_user_id
          and viewer_like.listing_id = listing.id
      ) as liked_by_current_user,
      favorite.created_at as favorited_at,
      event_detail.start_at as event_start_at,
      event_detail.end_at as event_end_at
    from public.favorites as favorite
    join public.listings as listing
      on listing.id = favorite.listing_id
    join public.cities as city
      on city.id = listing.city_id
    left join public.event_details as event_detail
      on event_detail.listing_id = listing.id
    left join lateral (
      select media.url, media.alt
      from public.listing_media as media
      where media.listing_id = listing.id
      order by media.is_cover desc, media.display_order asc
      limit 1
    ) as cover on true
    where favorite.user_id = current_user_id
      and favorite.created_at <= v_as_of
      and listing.status = 'publie'::public.listing_status
      and listing.published_at is not null
      and (v_listing_type is null or listing.type = v_listing_type)
      and (favorite.created_at, favorite.listing_id)
        < (v_cursor_favorited_at, v_cursor_id)
    order by favorite.created_at desc, favorite.listing_id desc
    limit (p_limit + 1)
  )
  select
    page.id,
    page.type,
    page.listing_class,
    page.status,
    page.name,
    page.city_id,
    page.city_name,
    page.category_id,
    page.cover_image_url,
    page.cover_image_alt,
    page.price_from_xof,
    page.rating_avg,
    page.likes_count,
    page.verified,
    page.liked_by_current_user,
    true as favorited_by_current_user,
    page.favorited_at,
    page.event_start_at,
    page.event_end_at,
    case
      when page.type = 'evenement'::public.listing_type
        and page.event_start_at is not null
      then v_as_of > coalesce(page.event_end_at, page.event_start_at)
      else false
    end as is_event_ended,
    false as is_sponsored_placement,
    pg_catalog.replace(
      pg_catalog.replace(
        pg_catalog.encode(
          pg_catalog.convert_to(
            pg_catalog.jsonb_build_object(
              'v', 1,
              'as_of', v_as_of,
              'fingerprint', v_fingerprint,
              'favorited_at', page.favorited_at,
              'id', page.id
            )::text,
            'UTF8'
          ),
          'base64'
        ),
        pg_catalog.chr(10),
        ''
      ),
      pg_catalog.chr(13),
      ''
    ) as row_cursor
  from favorite_page as page
  order by page.favorited_at desc, page.id desc;
end;
$$;

comment on function public.list_favorite_listing_summaries_v1(text, text, integer) is
  'Returns the completed account owner''s currently published favorites newest first, with keyset pagination, no sponsored ranking, and ended events retained.';

create or replace function public.set_listing_favorite_v1(
  p_listing_id uuid,
  p_favorited boolean
)
returns table (
  listing_id uuid,
  favorited_by_current_user boolean,
  favorited_at timestamptz
)
language plpgsql
volatile
security invoker
set search_path = ''
as $$
declare
  current_user_id uuid := (select auth.uid());
  stored_favorited_at timestamptz;
begin
  if current_user_id is null then
    raise insufficient_privilege
      using message = 'Authentication required';
  end if;

  if p_listing_id is null or p_favorited is null then
    raise invalid_parameter_value
      using message = 'Favorite mutation parameters are invalid';
  end if;

  -- Mutations share the account-deletion lock namespace and remain short.
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(current_user_id::text, 0)
  );
  perform app_private.require_completed_onboarding();

  if p_favorited then
    -- A retry remains successful if the private relation already exists,
    -- even when moderation hid the listing after the original add.
    select favorite.created_at
    into stored_favorited_at
    from public.favorites as favorite
    where favorite.user_id = current_user_id
      and favorite.listing_id = p_listing_id;

    if found then
      return query
      select p_listing_id, true, stored_favorited_at;
      return;
    end if;

    -- Validate the catalogue state through the published-listing SELECT
    -- policy. A row lock is intentionally avoided here: PostgreSQL applies
    -- the listing UPDATE RLS policy to locking reads, which would hide a
    -- public listing from an ordinary authenticated visitor. The INSERT
    -- policy repeats this published-state check in the mutation statement.
    perform listing.id
    from public.listings as listing
    where listing.id = p_listing_id
      and listing.status = 'publie'::public.listing_status
      and listing.published_at is not null;

    if not found then
      raise no_data_found
        using message = 'listing not found';
    end if;

    insert into public.favorites (user_id, listing_id)
    values (current_user_id, p_listing_id)
    on conflict on constraint favorites_pkey do nothing;

    select favorite.created_at
    into strict stored_favorited_at
    from public.favorites as favorite
    where favorite.user_id = current_user_id
      and favorite.listing_id = p_listing_id;

    return query
    select p_listing_id, true, stored_favorited_at;
    return;
  end if;

  -- Removal intentionally does not join `listings`: a relation remains
  -- removable after moderation, archival, or a hard-delete cascade.
  delete from public.favorites as favorite
  where favorite.user_id = current_user_id
    and favorite.listing_id = p_listing_id;

  return query
  select p_listing_id, false, null::timestamptz;
end;
$$;

comment on function public.set_listing_favorite_v1(uuid, boolean) is
  'Idempotently sets one owner favorite. Creating a relation requires a published listing; retries and removes never require renewed listing visibility.';

revoke all
on function public.list_favorite_listing_summaries_v1(text, text, integer)
from public, anon, authenticated, service_role;
revoke all
on function public.set_listing_favorite_v1(uuid, boolean)
from public, anon, authenticated, service_role;

grant execute
on function public.list_favorite_listing_summaries_v1(text, text, integer)
to authenticated;
grant execute
on function public.set_listing_favorite_v1(uuid, boolean)
to authenticated, service_role;

-- The released legacy RPCs have always retained service_role EXECUTE. Their
-- implementation now delegates to the V1 setter, so keep that real invocation
-- path operational as well as its catalogue-visible ACL.
grant execute
on function app_private.require_completed_onboarding()
to service_role;

-- Compatibility wrappers stay executable for the currently released KMP
-- clients. Their removal and the direct table ACL migration require one
-- later atomic mobile rollout.
create or replace function public.add_listing_to_favorites(p_listing_id uuid)
returns table (
  listing_id uuid,
  liked_by_current_user boolean,
  favorited_by_current_user boolean,
  likes_count integer
)
language plpgsql
security invoker
set search_path = ''
as $$
declare
  current_user_id uuid := (select auth.uid());
begin
  perform mutation.listing_id
  from public.set_listing_favorite_v1(p_listing_id, true) as mutation;

  return query
  select
    listing.id,
    exists (
      select 1
      from public.likes as viewer_like
      where viewer_like.user_id = current_user_id
        and viewer_like.listing_id = p_listing_id
    ),
    true,
    listing.likes_count
  from public.listings as listing
  where listing.id = p_listing_id
    and listing.status = 'publie'::public.listing_status;

  if found then
    return;
  end if;

  -- A lost-response retry can arrive after moderation hides the listing. The
  -- V1 setter has already confirmed that the private relation exists, so keep
  -- the released response shape without exposing a hidden aggregate metric.
  return query
  select
    p_listing_id,
    exists (
      select 1
      from public.likes as viewer_like
      where viewer_like.user_id = current_user_id
        and viewer_like.listing_id = p_listing_id
    ),
    true,
    0::integer;
end;
$$;

comment on function public.add_listing_to_favorites(uuid) is
  'DEPRECATED compatibility wrapper. Migrate mobile clients atomically to set_listing_favorite_v1 before removal.';

create or replace function public.remove_listing_from_favorites(p_listing_id uuid)
returns table (
  listing_id uuid,
  liked_by_current_user boolean,
  favorited_by_current_user boolean,
  likes_count integer
)
language plpgsql
security invoker
set search_path = ''
as $$
declare
  current_user_id uuid := (select auth.uid());
begin
  perform mutation.listing_id
  from public.set_listing_favorite_v1(p_listing_id, false) as mutation;

  return query
  select
    listing.id,
    exists (
      select 1
      from public.likes as viewer_like
      where viewer_like.user_id = current_user_id
        and viewer_like.listing_id = p_listing_id
    ),
    false,
    listing.likes_count
  from public.listings as listing
  where listing.id = p_listing_id
    and listing.status = 'publie'::public.listing_status;

  if found then
    return;
  end if;

  -- Hidden or deleted listings cannot expose their aggregate metric through
  -- this legacy shape. Returning one neutral compatibility row fixes the old
  -- post-delete `no_data_found` failure while keeping the authoritative
  -- favorite state and the caller's own like state correct.
  return query
  select
    p_listing_id,
    exists (
      select 1
      from public.likes as viewer_like
      where viewer_like.user_id = current_user_id
        and viewer_like.listing_id = p_listing_id
    ),
    false,
    0::integer;
end;
$$;

comment on function public.remove_listing_from_favorites(uuid) is
  'DEPRECATED compatibility wrapper. It remains idempotent and can remove a relation whose listing is no longer published.';

-- Supabase CLI 2.111 fresh role graphs do not preserve the historical
-- service_role inheritance observed by older local stacks. Restate every
-- compatibility grant explicitly so clean and upgraded environments agree.
revoke all
on function public.add_listing_to_favorites(uuid)
from public, anon;
revoke all
on function public.remove_listing_from_favorites(uuid)
from public, anon;

grant execute
on function public.add_listing_to_favorites(uuid)
to authenticated, service_role;
grant execute
on function public.remove_listing_from_favorites(uuid)
to authenticated, service_role;

-- Keep the owner-only favorites RLS policies until the KMP migration is
-- shipped as one backward-compatible lot.

reset lock_timeout;

commit;
