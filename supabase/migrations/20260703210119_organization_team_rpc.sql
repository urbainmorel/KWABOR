create or replace function public.create_organization_invite(
  p_organization_id uuid,
  p_invited_by_member_id uuid,
  p_email text,
  p_proposed_role public.organization_role,
  p_expires_at timestamptz
)
returns setof public.organization_invites
language plpgsql
security invoker
set search_path = public, extensions, pg_temp
as $$
declare
  normalized_email text := lower(trim(p_email));
  invite_token text := encode(extensions.gen_random_bytes(32), 'hex');
begin
  if normalized_email = ''
    or position('@' in normalized_email) <= 1
    or position('.' in substring(normalized_email from position('@' in normalized_email) + 2)) = 0
  then
    raise exception 'Invalid organization invitation email'
      using errcode = '22023';
  end if;

  return query
    insert into public.organization_invites (
      organization_id,
      email,
      token_hash,
      proposed_role,
      invited_by_member_id,
      expires_at
    )
    values (
      p_organization_id,
      normalized_email,
      encode(extensions.digest(invite_token, 'sha256'), 'hex'),
      p_proposed_role,
      p_invited_by_member_id,
      p_expires_at
    )
    returning *;
end;
$$;

create or replace function public.revoke_organization_invite(p_invite_id uuid)
returns setof public.organization_invites
language plpgsql
security invoker
set search_path = public, pg_temp
as $$
begin
  return query
    update public.organization_invites invite
    set
      status = 'revoked',
      revoked_at = now(),
      updated_at = now()
    where invite.id = p_invite_id
      and invite.status = 'pending'
    returning invite.*;

  if not found then
    raise exception 'Organization invitation not found or not revocable'
      using errcode = 'P0002';
  end if;
end;
$$;

create or replace function public.suspend_organization_member(
  p_organization_id uuid,
  p_member_id uuid
)
returns setof public.organization_members
language plpgsql
security invoker
set search_path = public, pg_temp
as $$
begin
  return query
    update public.organization_members member
    set
      status = 'suspended',
      suspended_at = now(),
      updated_at = now()
    where member.organization_id = p_organization_id
      and member.id = p_member_id
      and member.status = 'active'
      and member.role <> 'proprietaire'
    returning member.*;

  if not found then
    raise exception 'Organization member not found or not suspendable'
      using errcode = 'P0002';
  end if;
end;
$$;

create or replace function public.accept_organization_invite(p_invite_token text)
returns setof public.organization_members
language plpgsql
security definer
set search_path = public, extensions, pg_temp
as $$
declare
  current_user_id uuid := (select auth.uid());
  current_user_email text;
  target_invite public.organization_invites%rowtype;
  accepted_member public.organization_members%rowtype;
begin
  if current_user_id is null then
    raise exception 'Authentication required'
      using errcode = '42501';
  end if;

  select lower(trim(auth_user.email))
  into current_user_email
  from auth.users auth_user
  where auth_user.id = current_user_id;

  if current_user_email is null then
    raise exception 'Authenticated user email not found'
      using errcode = '42501';
  end if;

  select invite.*
  into target_invite
  from public.organization_invites invite
  where invite.token_hash = encode(extensions.digest(p_invite_token, 'sha256'), 'hex')
    and invite.status = 'pending'
    and invite.expires_at > now()
  for update;

  if not found then
    raise exception 'Organization invitation not found or expired'
      using errcode = 'P0002';
  end if;

  if target_invite.proposed_role = 'proprietaire' then
    raise exception 'Owner invitations require a dedicated transfer flow'
      using errcode = '42501';
  end if;

  if target_invite.email <> current_user_email then
    raise exception 'Organization invitation email does not match authenticated user'
      using errcode = '42501';
  end if;

  update public.organization_invites invite
  set
    status = 'accepted',
    accepted_by = current_user_id,
    accepted_at = now(),
    updated_at = now()
  where invite.id = target_invite.id;

  insert into public.organization_members (
    organization_id,
    user_id,
    role,
    status,
    invited_by,
    accepted_at
  )
  values (
    target_invite.organization_id,
    current_user_id,
    target_invite.proposed_role,
    'active',
    target_invite.invited_by_member_id,
    now()
  )
  on conflict (organization_id, user_id)
  do update
  set
    role = excluded.role,
    status = 'active',
    invited_by = excluded.invited_by,
    accepted_at = coalesce(public.organization_members.accepted_at, excluded.accepted_at),
    suspended_at = null,
    updated_at = now()
  returning * into accepted_member;

  return next accepted_member;
end;
$$;

revoke all on function public.create_organization_invite(
  uuid,
  uuid,
  text,
  public.organization_role,
  timestamptz
) from public;
revoke all on function public.revoke_organization_invite(uuid) from public;
revoke all on function public.suspend_organization_member(uuid, uuid) from public;
revoke all on function public.accept_organization_invite(text) from public;

grant execute on function public.create_organization_invite(
  uuid,
  uuid,
  text,
  public.organization_role,
  timestamptz
) to authenticated;
grant execute on function public.revoke_organization_invite(uuid) to authenticated;
grant execute on function public.suspend_organization_member(uuid, uuid) to authenticated;
grant execute on function public.accept_organization_invite(text) to authenticated;
