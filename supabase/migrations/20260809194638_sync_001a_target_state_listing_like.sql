begin;

set local lock_timeout = '5s';

create or replace function app_private.like_owner_write_allowed_v1(
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

  -- Direct REST writes and the compatibility RPCs must share the deletion
  -- boundary too. VOLATILE gives the checks after this wait fresh visibility
  -- instead of reusing the outer INSERT/DELETE statement snapshot.
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
on function app_private.like_owner_write_allowed_v1(uuid)
from public, anon, authenticated, service_role;

grant execute
on function app_private.like_owner_write_allowed_v1(uuid)
to authenticated;

comment on function app_private.like_owner_write_allowed_v1(uuid) is
  'Serializes owner-only legacy Like table writes with account deletion and rejects writes after its tombstone.';

-- Preserve the released direct-table surface while making every unprivileged
-- INSERT/DELETE cooperate with account deletion. The first stable predicate
-- remains a cheap fail-closed check; the volatile guard is the post-wait fence.
alter policy "users like published listings"
on public.likes
with check (
  (select app_private.current_user_has_completed_onboarding())
  and app_private.like_owner_write_allowed_v1(user_id)
  and exists (
    select 1
    from public.listings as listing
    where listing.id = likes.listing_id
      and listing.status = 'publie'::public.listing_status
      and listing.published_at is not null
  )
);

alter policy "users delete their likes"
on public.likes
using (
  (select app_private.current_user_has_completed_onboarding())
  and app_private.like_owner_write_allowed_v1(user_id)
);

-- Compatibility callers never need to forge the server mutation timestamp.
revoke insert on table public.likes from authenticated;
grant insert (user_id, listing_id) on table public.likes to authenticated;

create or replace function public.set_listing_like_v1(
  p_listing_id uuid,
  p_liked boolean
)
returns table (
  listing_id uuid,
  liked boolean,
  likes_count integer,
  mutated_at timestamptz
)
language plpgsql
volatile
security invoker
set search_path = ''
as $$
declare
  current_user_id uuid := (select auth.uid());
  visible_likes_count integer;
begin
  if current_user_id is null then
    raise insufficient_privilege
      using message = 'Authentication required';
  end if;

  if p_listing_id is null or p_liked is null then
    raise invalid_parameter_value
      using message = 'Like mutation parameters are invalid';
  end if;

  -- Serialize target-state changes with each other and with account deletion.
  -- Onboarding is deliberately rechecked after the wait so a deletion
  -- tombstone committed by the earlier transaction fences this mutation.
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(current_user_id::text, 0)
  );
  perform app_private.require_completed_onboarding();

  if p_liked then
    -- A lost-response retry confirms an existing private relation before
    -- consulting catalogue visibility. Moderation may have hidden the listing
    -- after the original mutation, but that must not turn the retry into a
    -- failure or duplicate the relation.
    perform 1
    from public.likes as viewer_like
    where viewer_like.user_id = current_user_id
      and viewer_like.listing_id = p_listing_id;

    if not found then
      -- A new relation is valid only for a currently published listing. The
      -- INSERT policy independently rechecks published status in the mutation
      -- statement, without requiring a privileged catalogue row lock.
      perform listing.id
      from public.listings as listing
      where listing.id = p_listing_id
        and listing.status = 'publie'::public.listing_status
        and listing.published_at is not null;

      if not found then
        raise no_data_found
          using message = 'listing not found';
      end if;

      insert into public.likes (user_id, listing_id)
      values (current_user_id, p_listing_id)
      on conflict on constraint likes_pkey do nothing;
    end if;
  else
    -- Removal never joins `listings`: the owner can confirm the absent target
    -- state after moderation, archival, a hard-delete cascade, or a retry.
    delete from public.likes as viewer_like
    where viewer_like.user_id = current_user_id
      and viewer_like.listing_id = p_listing_id;
  end if;

  -- Aggregate visibility follows the public catalogue contract. A hidden or
  -- unknown listing returns NULL without rolling back a successful removal or
  -- an existing-relation retry.
  select listing.likes_count
  into visible_likes_count
  from public.listings as listing
  where listing.id = p_listing_id
    and listing.status = 'publie'::public.listing_status
    and listing.published_at is not null;

  return query
  select
    p_listing_id,
    p_liked,
    visible_likes_count,
    pg_catalog.clock_timestamp();
end;
$$;

comment on function public.set_listing_like_v1(uuid, boolean) is
  'Idempotently confirms one owner Like target state. New Likes require a published listing; retries and removals survive later catalogue invisibility, whose aggregate count remains private.';

revoke all
on function public.set_listing_like_v1(uuid, boolean)
from public, anon, authenticated, service_role;

grant execute
on function public.set_listing_like_v1(uuid, boolean)
to authenticated;

-- Durable interaction retries carry the account that owned the queued command.
-- Checking it before delegating closes the interval where the Supabase client
-- has installed account B's token but the presentation scope still says A.
create or replace function public.set_listing_like_v2(
  p_listing_id uuid,
  p_liked boolean,
  p_expected_account_id uuid
)
returns table (
  listing_id uuid,
  liked boolean,
  likes_count integer,
  mutated_at timestamptz
)
language plpgsql
volatile
security invoker
set search_path = ''
as $$
declare
  current_user_id uuid := (select auth.uid());
begin
  if current_user_id is null then
    raise insufficient_privilege
      using message = 'Authentication required';
  end if;

  if p_listing_id is null
    or p_liked is null
    or p_expected_account_id is null
  then
    raise invalid_parameter_value
      using message = 'Like mutation parameters are invalid';
  end if;

  if current_user_id is distinct from p_expected_account_id then
    raise insufficient_privilege
      using message = 'Authentication required';
  end if;

  return query
  select
    mutation.listing_id,
    mutation.liked,
    mutation.likes_count,
    mutation.mutated_at
  from public.set_listing_like_v1(p_listing_id, p_liked) as mutation;
end;
$$;

comment on function public.set_listing_like_v2(uuid, boolean, uuid) is
  'Sets one Like target state only when the request JWT still belongs to the account that queued the durable mutation.';

create or replace function public.set_listing_favorite_v2(
  p_listing_id uuid,
  p_favorited boolean,
  p_expected_account_id uuid
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
begin
  if current_user_id is null then
    raise insufficient_privilege
      using message = 'Authentication required';
  end if;

  if p_listing_id is null
    or p_favorited is null
    or p_expected_account_id is null
  then
    raise invalid_parameter_value
      using message = 'Favorite mutation parameters are invalid';
  end if;

  if current_user_id is distinct from p_expected_account_id then
    raise insufficient_privilege
      using message = 'Authentication required';
  end if;

  return query
  select
    mutation.listing_id,
    mutation.favorited_by_current_user,
    mutation.favorited_at
  from public.set_listing_favorite_v1(p_listing_id, p_favorited) as mutation;
end;
$$;

comment on function public.set_listing_favorite_v2(uuid, boolean, uuid) is
  'Sets one Favorite target state only when the request JWT still belongs to the account that queued the durable mutation.';

revoke all
on function public.set_listing_like_v2(uuid, boolean, uuid)
from public, anon, authenticated, service_role;
revoke all
on function public.set_listing_favorite_v2(uuid, boolean, uuid)
from public, anon, authenticated, service_role;

grant execute
on function public.set_listing_like_v2(uuid, boolean, uuid)
to authenticated;
grant execute
on function public.set_listing_favorite_v2(uuid, boolean, uuid)
to authenticated;

-- Released mobile clients keep the historical response shape, but all writes
-- now delegate to the target-state setter. A hidden or deleted listing uses a
-- neutral aggregate because its count is no longer public.
create or replace function public.like_listing(p_listing_id uuid)
returns table (
  listing_id uuid,
  liked_by_current_user boolean,
  favorited_by_current_user boolean,
  likes_count integer
)
language plpgsql
volatile
security invoker
set search_path = ''
as $$
declare
  current_user_id uuid := (select auth.uid());
  authoritative_likes_count integer;
begin
  select mutation.likes_count
  into authoritative_likes_count
  from public.set_listing_like_v1(p_listing_id, true) as mutation;

  return query
  select
    p_listing_id,
    true,
    exists (
      select 1
      from public.favorites as favorite
      where favorite.user_id = current_user_id
        and favorite.listing_id = p_listing_id
    ),
    coalesce(authoritative_likes_count, 0);
end;
$$;

comment on function public.like_listing(uuid) is
  'DEPRECATED compatibility wrapper. Migrate mobile clients atomically to the account-bound set_listing_like_v2 RPC before removal.';

create or replace function public.unlike_listing(p_listing_id uuid)
returns table (
  listing_id uuid,
  liked_by_current_user boolean,
  favorited_by_current_user boolean,
  likes_count integer
)
language plpgsql
volatile
security invoker
set search_path = ''
as $$
declare
  current_user_id uuid := (select auth.uid());
  authoritative_likes_count integer;
begin
  select mutation.likes_count
  into authoritative_likes_count
  from public.set_listing_like_v1(p_listing_id, false) as mutation;

  return query
  select
    p_listing_id,
    false,
    exists (
      select 1
      from public.favorites as favorite
      where favorite.user_id = current_user_id
        and favorite.listing_id = p_listing_id
    ),
    coalesce(authoritative_likes_count, 0);
end;
$$;

comment on function public.unlike_listing(uuid) is
  'DEPRECATED compatibility wrapper. Migrate to set_listing_like_v2; this wrapper remains idempotent for listings that are no longer public.';

revoke all
on function public.like_listing(uuid)
from public, anon, authenticated, service_role;
revoke all
on function public.unlike_listing(uuid)
from public, anon, authenticated, service_role;

grant execute
on function public.like_listing(uuid)
to authenticated;
grant execute
on function public.unlike_listing(uuid)
to authenticated;

commit;
