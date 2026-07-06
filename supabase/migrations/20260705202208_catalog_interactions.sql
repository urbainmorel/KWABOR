create schema if not exists app_private;

drop policy if exists "users manage their favorites" on public.favorites;
create policy "users read their favorites"
on public.favorites
for select
to authenticated
using ((select auth.uid()) = user_id);

create policy "users favorite published listings"
on public.favorites
for insert
to authenticated
with check (
  (select auth.uid()) = user_id
  and exists (
    select 1
    from public.listings listing
    where listing.id = favorites.listing_id
      and listing.status = 'publie'
  )
);

create policy "users delete their favorites"
on public.favorites
for delete
to authenticated
using ((select auth.uid()) = user_id);

drop policy if exists "users manage their likes" on public.likes;
create policy "users read their likes"
on public.likes
for select
to authenticated
using ((select auth.uid()) = user_id);

create policy "users like published listings"
on public.likes
for insert
to authenticated
with check (
  (select auth.uid()) = user_id
  and exists (
    select 1
    from public.listings listing
    where listing.id = likes.listing_id
      and listing.status = 'publie'
  )
);

create policy "users delete their likes"
on public.likes
for delete
to authenticated
using ((select auth.uid()) = user_id);

create index if not exists favorites_listing_idx on public.favorites (listing_id);
create index if not exists likes_listing_idx on public.likes (listing_id);

create or replace function app_private.refresh_listing_likes_count()
returns trigger
language plpgsql
security definer
set search_path = public, app_private
as $$
begin
  if TG_OP = 'INSERT' then
    update public.listings
    set
      likes_count = likes_count + 1,
      updated_at = now()
    where id = new.listing_id;

    return new;
  end if;

  if TG_OP = 'DELETE' then
    update public.listings
    set
      likes_count = greatest(likes_count - 1, 0),
      updated_at = now()
    where id = old.listing_id;

    return old;
  end if;

  return null;
end;
$$;

revoke all on function app_private.refresh_listing_likes_count() from public;
revoke all on function app_private.refresh_listing_likes_count() from anon;
revoke all on function app_private.refresh_listing_likes_count() from authenticated;

drop trigger if exists likes_refresh_listing_count_after_insert on public.likes;
create trigger likes_refresh_listing_count_after_insert
after insert on public.likes
for each row
execute function app_private.refresh_listing_likes_count();

drop trigger if exists likes_refresh_listing_count_after_delete on public.likes;
create trigger likes_refresh_listing_count_after_delete
after delete on public.likes
for each row
execute function app_private.refresh_listing_likes_count();

create or replace function public.get_listing_viewer_interaction(p_listing_id uuid)
returns table (
  listing_id uuid,
  liked_by_current_user boolean,
  favorited_by_current_user boolean,
  likes_count integer
)
language plpgsql
security invoker
stable
set search_path = public
as $$
declare
  current_user_id uuid := (select auth.uid());
begin
  if current_user_id is null then
    raise insufficient_privilege using message = 'authentication required';
  end if;

  if not exists (
    select 1
    from public.listings listing
    where listing.id = p_listing_id
      and listing.status = 'publie'
  ) then
    raise no_data_found using message = 'listing not found';
  end if;

  return query
  select
    listing.id,
    exists (
      select 1
      from public.likes like_row
      where like_row.user_id = current_user_id
        and like_row.listing_id = p_listing_id
    ),
    exists (
      select 1
      from public.favorites favorite_row
      where favorite_row.user_id = current_user_id
        and favorite_row.listing_id = p_listing_id
    ),
    listing.likes_count
  from public.listings listing
  where listing.id = p_listing_id
    and listing.status = 'publie';
end;
$$;

create or replace function public.list_listing_viewer_interactions(p_listing_ids uuid[])
returns table (
  listing_id uuid,
  liked_by_current_user boolean,
  favorited_by_current_user boolean,
  likes_count integer
)
language plpgsql
security invoker
stable
set search_path = public
as $$
declare
  current_user_id uuid := (select auth.uid());
begin
  if current_user_id is null then
    raise insufficient_privilege using message = 'authentication required';
  end if;

  return query
  with requested_listings as (
    select distinct unnest(coalesce(p_listing_ids, array[]::uuid[])) as id
  )
  select
    listing.id,
    exists (
      select 1
      from public.likes like_row
      where like_row.user_id = current_user_id
        and like_row.listing_id = listing.id
    ),
    exists (
      select 1
      from public.favorites favorite_row
      where favorite_row.user_id = current_user_id
        and favorite_row.listing_id = listing.id
    ),
    listing.likes_count
  from public.listings listing
  join requested_listings requested on requested.id = listing.id
  where listing.status = 'publie'
  order by listing.id;
end;
$$;

create or replace function public.like_listing(p_listing_id uuid)
returns table (
  listing_id uuid,
  liked_by_current_user boolean,
  favorited_by_current_user boolean,
  likes_count integer
)
language plpgsql
security invoker
set search_path = public
as $$
declare
  current_user_id uuid := (select auth.uid());
begin
  if current_user_id is null then
    raise insufficient_privilege using message = 'authentication required';
  end if;

  if not exists (
    select 1
    from public.listings listing
    where listing.id = p_listing_id
      and listing.status = 'publie'
  ) then
    raise no_data_found using message = 'listing not found';
  end if;

  insert into public.likes (user_id, listing_id)
  values (current_user_id, p_listing_id)
  on conflict on constraint likes_pkey do nothing;

  return query
  select *
  from public.get_listing_viewer_interaction(p_listing_id);
end;
$$;

create or replace function public.unlike_listing(p_listing_id uuid)
returns table (
  listing_id uuid,
  liked_by_current_user boolean,
  favorited_by_current_user boolean,
  likes_count integer
)
language plpgsql
security invoker
set search_path = public
as $$
declare
  current_user_id uuid := (select auth.uid());
begin
  if current_user_id is null then
    raise insufficient_privilege using message = 'authentication required';
  end if;

  delete from public.likes like_row
  where like_row.user_id = current_user_id
    and like_row.listing_id = p_listing_id;

  return query
  select *
  from public.get_listing_viewer_interaction(p_listing_id);
end;
$$;

create or replace function public.add_listing_to_favorites(p_listing_id uuid)
returns table (
  listing_id uuid,
  liked_by_current_user boolean,
  favorited_by_current_user boolean,
  likes_count integer
)
language plpgsql
security invoker
set search_path = public
as $$
declare
  current_user_id uuid := (select auth.uid());
begin
  if current_user_id is null then
    raise insufficient_privilege using message = 'authentication required';
  end if;

  if not exists (
    select 1
    from public.listings listing
    where listing.id = p_listing_id
      and listing.status = 'publie'
  ) then
    raise no_data_found using message = 'listing not found';
  end if;

  insert into public.favorites (user_id, listing_id)
  values (current_user_id, p_listing_id)
  on conflict on constraint favorites_pkey do nothing;

  return query
  select *
  from public.get_listing_viewer_interaction(p_listing_id);
end;
$$;

create or replace function public.remove_listing_from_favorites(p_listing_id uuid)
returns table (
  listing_id uuid,
  liked_by_current_user boolean,
  favorited_by_current_user boolean,
  likes_count integer
)
language plpgsql
security invoker
set search_path = public
as $$
declare
  current_user_id uuid := (select auth.uid());
begin
  if current_user_id is null then
    raise insufficient_privilege using message = 'authentication required';
  end if;

  delete from public.favorites favorite_row
  where favorite_row.user_id = current_user_id
    and favorite_row.listing_id = p_listing_id;

  return query
  select *
  from public.get_listing_viewer_interaction(p_listing_id);
end;
$$;

revoke all on function public.get_listing_viewer_interaction(uuid) from public, anon, authenticated;
revoke all on function public.list_listing_viewer_interactions(uuid[]) from public, anon, authenticated;
revoke all on function public.like_listing(uuid) from public, anon, authenticated;
revoke all on function public.unlike_listing(uuid) from public, anon, authenticated;
revoke all on function public.add_listing_to_favorites(uuid) from public, anon, authenticated;
revoke all on function public.remove_listing_from_favorites(uuid) from public, anon, authenticated;

grant execute on function public.get_listing_viewer_interaction(uuid) to authenticated;
grant execute on function public.list_listing_viewer_interactions(uuid[]) to authenticated;
grant execute on function public.like_listing(uuid) to authenticated;
grant execute on function public.unlike_listing(uuid) to authenticated;
grant execute on function public.add_listing_to_favorites(uuid) to authenticated;
grant execute on function public.remove_listing_from_favorites(uuid) to authenticated;
