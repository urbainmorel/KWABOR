begin;

create schema if not exists tests;

create or replace function tests.use_auth_context(db_role text, uid uuid)
returns void
language plpgsql
as $$
begin
  execute format('set local role %I', db_role);
  perform set_config('request.jwt.claim.role', db_role, true);
  perform set_config('request.jwt.claim.sub', coalesce(uid::text, ''), true);
  perform set_config('request.jwt.claim', '', true);
  perform set_config(
    'request.jwt.claims',
    jsonb_build_object(
      'role', db_role,
      'sub', coalesce(uid::text, ''),
      'amr', '[]'::jsonb
    )::text,
    true
  );
end;
$$;

create or replace function tests.set_auth_amr(method text, authenticated_at bigint)
returns void
language plpgsql
as $$
begin
  perform set_config(
    'request.jwt.claims',
    jsonb_build_object(
      'role', current_setting('request.jwt.claim.role', true),
      'sub', current_setting('request.jwt.claim.sub', true),
      'amr',
      jsonb_build_array(
        jsonb_build_object(
          'method', method,
          'timestamp', authenticated_at
        )
      )
    )::text,
    true
  );
end;
$$;

create or replace function tests.set_auth_amr_entries(amr jsonb)
returns void
language plpgsql
as $$
begin
  perform set_config(
    'request.jwt.claims',
    jsonb_build_object(
      'role', current_setting('request.jwt.claim.role', true),
      'sub', current_setting('request.jwt.claim.sub', true),
      'amr', amr
    )::text,
    true
  );
end;
$$;

grant usage on schema tests to anon, authenticated;
grant execute on function tests.set_auth_amr(text, bigint) to authenticated;
grant execute on function tests.set_auth_amr_entries(jsonb) to authenticated;

create or replace function tests.statement_succeeds_as(db_role text, uid uuid, sql text)
returns boolean
language plpgsql
as $$
begin
  perform tests.use_auth_context(db_role, uid);
  execute sql;
  reset role;
  return true;
exception
  when others then
    reset role;
    raise notice 'statement_succeeds_as failed: %', sqlerrm;
    return false;
end;
$$;

create or replace function tests.statement_fails_as(db_role text, uid uuid, sql text)
returns boolean
language plpgsql
as $$
begin
  perform tests.use_auth_context(db_role, uid);
  execute sql;
  reset role;
  return false;
exception
  when others then
    reset role;
    return true;
end;
$$;

create or replace function tests.cron_job_exists(target_job_name text)
returns boolean
language plpgsql
as $$
declare
  job_exists boolean := false;
begin
  if to_regclass('cron.job') is null then
    return false;
  end if;

  execute
    'select exists (select 1 from cron.job where jobname = $1)'
    into job_exists
    using target_job_name;
  return job_exists;
end;
$$;

select plan(73);

create policy "auth005 test owned upload"
on storage.objects
for insert
to authenticated
with check (
  bucket_id = 'auth005-private'
  and owner_id = (select auth.uid())::text
);

insert into auth.users (
  id,
  aud,
  role,
  email,
  encrypted_password,
  email_confirmed_at,
  created_at,
  updated_at
)
values
  (
    'a1000000-0000-4000-8000-000000000001',
    'authenticated',
    'authenticated',
    'auth005-admin@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    'a2000000-0000-4000-8000-000000000002',
    'authenticated',
    'authenticated',
    'invitee@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    'a3000000-0000-4000-8000-000000000003',
    'authenticated',
    'authenticated',
    'wrong-invitee@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    'a4000000-0000-4000-8000-000000000004',
    'authenticated',
    'authenticated',
    'auth005-owner@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    'a5000000-0000-4000-8000-000000000005',
    'authenticated',
    'authenticated',
    'auth005-delete@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    'a6000000-0000-4000-8000-000000000006',
    'authenticated',
    'authenticated',
    'auth005-storage@kwabor.test',
    '',
    now(),
    now(),
    now()
  );

insert into public.profiles (
  user_id,
  first_name,
  last_name,
  city_id,
  onboarding_completed_at
)
values
  ('a1000000-0000-4000-8000-000000000001', 'Admin', 'Auth005', 'cotonou', now()),
  ('a2000000-0000-4000-8000-000000000002', 'Invite', 'Auth005', 'cotonou', null),
  ('a3000000-0000-4000-8000-000000000003', 'Mauvais', 'Compte', 'cotonou', now()),
  ('a4000000-0000-4000-8000-000000000004', 'Proprietaire', 'Auth005', 'cotonou', now()),
  ('a5000000-0000-4000-8000-000000000005', 'Suppression', 'Auth005', 'cotonou', now()),
  ('a6000000-0000-4000-8000-000000000006', 'Media', 'Auth005', 'cotonou', now());

insert into public.user_roles (user_id, role, verification_status)
values
  ('a1000000-0000-4000-8000-000000000001', 'admin', 'verified'),
  ('a2000000-0000-4000-8000-000000000002', 'user', 'unverified'),
  ('a3000000-0000-4000-8000-000000000003', 'user', 'unverified'),
  ('a4000000-0000-4000-8000-000000000004', 'promoteur', 'verified'),
  ('a5000000-0000-4000-8000-000000000005', 'user', 'unverified'),
  ('a6000000-0000-4000-8000-000000000006', 'user', 'unverified');

insert into public.organizations (
  id,
  type,
  name,
  slug,
  verification_status,
  primary_owner_id,
  created_by
)
values (
  'b1000000-0000-4000-8000-000000000001',
  'promoteur',
  'Etablissement Auth005',
  'etablissement-auth005',
  'verified',
  'a4000000-0000-4000-8000-000000000004',
  'a1000000-0000-4000-8000-000000000001'
);

insert into public.organization_members (
  organization_id,
  user_id,
  role,
  status,
  accepted_at
)
values (
  'b1000000-0000-4000-8000-000000000001',
  'a4000000-0000-4000-8000-000000000004',
  'proprietaire',
  'active',
  now()
);

insert into public.listings (
  id,
  type,
  subtype,
  listing_class,
  category_id,
  owner_id,
  submitted_by,
  status,
  name,
  slug,
  description,
  city_id,
  address,
  lat,
  lng,
  price_from_xof,
  price_unit
)
values (
  'c1000000-0000-4000-8000-000000000001',
  'etablissement',
  'restaurant',
  'commercial',
  'commercial-restaurant',
  'a4000000-0000-4000-8000-000000000004',
  'a1000000-0000-4000-8000-000000000001',
  'brouillon',
  'Etablissement Auth005',
  'etablissement-auth005',
  'Fiche commerciale preinscrite pour le test AUTH-005.',
  'cotonou',
  'Cotonou',
  6.37,
  2.39,
  5000,
  'consommation'
);

select ok(
  not has_table_privilege('authenticated', 'public.promoter_invites', 'select'),
  'promoter invite hashes are not readable through the authenticated Data API'
);
select ok(
  not has_table_privilege('authenticated', 'public.account_deletion_requests', 'select'),
  'account deletion tombstones are server-only'
);
select is(
  (
    select count(*)::integer
    from pg_catalog.pg_policies policy
    where policy.schemaname = 'storage'
      and policy.tablename = 'objects'
      and policy.policyname in (
        'account deletion fences storage inserts',
        'account deletion fences storage updates'
      )
      and policy.permissive = 'RESTRICTIVE'
  ),
  2,
  'restrictive Storage policies fence writes during account deletion'
);
select ok(
  has_function_privilege(
    'authenticated',
    'app_private.current_user_storage_write_allowed()',
    'execute'
  ),
  'authenticated Storage policies can execute the deletion fence'
);
select ok(
  not has_function_privilege(
    'authenticated',
    'app_private.current_jwt_has_recent_strong_authentication()',
    'execute'
  ),
  'clients cannot call the private recent strong authentication guard directly'
);
select ok(
  not has_function_privilege(
    'authenticated',
    'public.prepare_account_deletion(uuid,uuid)',
    'execute'
  ),
  'clients cannot prepare account deletion directly'
);
select ok(
  not has_function_privilege(
    'authenticated',
    'public.mark_account_deletion_completed(uuid,uuid)',
    'execute'
  ),
  'clients cannot complete account deletion directly'
);
select ok(
  not has_function_privilege('anon', 'public.activate_promoter_invite(text)', 'execute'),
  'anonymous callers cannot activate promoter invites'
);
select ok(
  has_function_privilege('authenticated', 'public.activate_promoter_invite(text)', 'execute'),
  'authenticated callers can invoke the guarded activation RPC'
);
select ok(
  not has_table_privilege('authenticated', 'public.listings', 'insert'),
  'authenticated clients do not retain table-wide listing insert privileges'
);
select ok(
  not has_table_privilege('authenticated', 'public.listings', 'update'),
  'authenticated clients do not retain table-wide listing update privileges'
);
select ok(
  has_column_privilege('authenticated', 'public.listings', 'description', 'update'),
  'authenticated listing managers may update an explicitly safe content column'
);
select ok(
  not has_column_privilege('authenticated', 'public.listings', 'owner_id', 'update'),
  'listing ownership is not client-updatable'
);
select ok(
  not has_column_privilege('authenticated', 'public.listings', 'sponsored_until', 'update'),
  'listing sponsorship state is not client-updatable'
);

select ok(
  tests.statement_fails_as(
    'authenticated',
    'a3000000-0000-4000-8000-000000000003',
    $sql$
      insert into public.listings (
        type,
        subtype,
        listing_class,
        category_id,
        owner_id,
        submitted_by,
        status,
        name,
        slug,
        description,
        city_id,
        price_from_xof,
        price_unit,
        organization_id
      )
      values (
        'etablissement',
        'restaurant',
        'commercial',
        'commercial-restaurant',
        null,
        'a3000000-0000-4000-8000-000000000003',
        'brouillon',
        'Insertion IDOR Auth005',
        'insertion-idor-auth005',
        'Une tentative complete de rattachement IDOR a une organisation qui ne doit jamais etre acceptee.',
        'cotonou',
        5000,
        'consommation',
        'b1000000-0000-4000-8000-000000000001'
      )
    $sql$
  ),
  'an outsider cannot attach a new listing to a verified organization'
);

select ok(
  tests.statement_fails_as(
    'authenticated',
    'a1000000-0000-4000-8000-000000000001',
    $sql$
      insert into public.listings (
        type,
        subtype,
        listing_class,
        category_id,
        steward_id,
        submitted_by,
        status,
        name,
        slug,
        description,
        city_id,
        price_unit
      )
      values (
        'lieu',
        'historique',
        'patrimonial',
        'heritage-historique',
        'a3000000-0000-4000-8000-000000000003',
        'a1000000-0000-4000-8000-000000000001',
        'brouillon',
        'Patrimoine usurpe Auth005',
        'patrimoine-usurpe-auth005',
        'Une fiche patrimoniale suffisamment detaillee qui tente illicitement de designer un autre steward.',
        'cotonou',
        'aucune'
      )
    $sql$
  ),
  'a patrimonial listing cannot nominate another user as steward'
);
select ok(
  tests.statement_succeeds_as(
    'authenticated',
    'a1000000-0000-4000-8000-000000000001',
    $sql$
      insert into public.listings (
        type,
        subtype,
        listing_class,
        category_id,
        steward_id,
        submitted_by,
        status,
        name,
        slug,
        description,
        city_id,
        price_unit
      )
      values (
        'lieu',
        'historique',
        'patrimonial',
        'heritage-historique',
        'a1000000-0000-4000-8000-000000000001',
        'a1000000-0000-4000-8000-000000000001',
        'brouillon',
        'Patrimoine legitime Auth005',
        'patrimoine-legitime-auth005',
        'Une fiche patrimoniale suffisamment detaillee dont le steward correspond exactement au contributeur.',
        'cotonou',
        'aucune'
      )
    $sql$
  ),
  'a patrimonial contributor can only create a listing stewarded by itself'
);

update public.profiles
set onboarding_completed_at = null
where user_id = 'a1000000-0000-4000-8000-000000000001';

select ok(
  tests.statement_fails_as(
    'authenticated',
    'a1000000-0000-4000-8000-000000000001',
    $sql$
      select *
      from public.create_promoter_invite(
        'b1000000-0000-4000-8000-000000000001',
        'c1000000-0000-4000-8000-000000000001',
        'invitee@kwabor.test',
        now() + interval '1 day'
      )
    $sql$
  ),
  'an admin with incomplete onboarding cannot create promoter invites'
);

update public.profiles
set onboarding_completed_at = now()
where user_id = 'a1000000-0000-4000-8000-000000000001';

select ok(
  tests.statement_fails_as(
    'authenticated',
    'a3000000-0000-4000-8000-000000000003',
    $sql$
      select *
      from public.create_promoter_invite(
        'b1000000-0000-4000-8000-000000000001',
        'c1000000-0000-4000-8000-000000000001',
        'invitee@kwabor.test',
        now() + interval '1 day'
      )
    $sql$
  ),
  'non-admin users cannot create promoter invites'
);

select tests.use_auth_context(
  'authenticated',
  'a1000000-0000-4000-8000-000000000001'
);
create temporary table auth005_invite as
select *
from public.create_promoter_invite(
  'b1000000-0000-4000-8000-000000000001',
  'c1000000-0000-4000-8000-000000000001',
  'invitee@kwabor.test',
  now() + interval '1 day'
);
reset role;

select ok(
  (select invite_token from auth005_invite) ~ '^[0-9a-f]{64}$',
  'the admin receives a 256-bit lowercase hexadecimal invite token'
);
select isnt(
  (
    select token_hash
    from public.promoter_invites
    where id = (select invite_id from auth005_invite)
  ),
  (select invite_token from auth005_invite),
  'only the invite token hash is persisted'
);
select is(
  (
    select token_hash
    from public.promoter_invites
    where id = (select invite_id from auth005_invite)
  ),
  encode(
    extensions.digest((select invite_token from auth005_invite), 'sha256'),
    'hex'
  ),
  'the persisted promoter token hash is SHA-256'
);

select tests.use_auth_context(
  'authenticated',
  'a3000000-0000-4000-8000-000000000003'
);
select tests.set_auth_amr(
  'password',
  floor(extract(epoch from statement_timestamp()))::bigint
);
select is(
  (
    select status
    from public.preview_promoter_invite((select invite_token from auth005_invite))
  ),
  'invalid',
  'a confirmed account with a different email cannot preview invite details'
);
select is(
  (
    select status
    from public.activate_promoter_invite((select invite_token from auth005_invite))
  ),
  'invalid',
  'a confirmed account with a different email cannot consume the invite'
);
reset role;

select is(
  (
    select status::text
    from public.promoter_invites
    where id = (select invite_id from auth005_invite)
  ),
  'pending',
  'a mismatched account does not consume the promoter invite'
);

update public.profiles
set onboarding_completed_at = now()
where user_id = 'a2000000-0000-4000-8000-000000000002';

select tests.use_auth_context(
  'authenticated',
  'a2000000-0000-4000-8000-000000000002'
);
select is(
  (
    select status
    from public.preview_promoter_invite((select invite_token from auth005_invite))
  ),
  'ready',
  'the matching authenticated account can preview a ready invite'
);
select throws_ok(
  format(
    'select * from public.activate_promoter_invite(%L)',
    (select invite_token from auth005_invite)
  ),
  '42501',
  'Recent strong authentication required',
  'activation fails closed when the JWT has no AMR evidence'
);
select tests.set_auth_amr(
  'otp',
  floor(extract(epoch from statement_timestamp()))::bigint
);
select throws_ok(
  format(
    'select * from public.activate_promoter_invite(%L)',
    (select invite_token from auth005_invite)
  ),
  '42501',
  'Recent strong authentication required',
  'a recent OTP alone cannot activate a promoter invite'
);
select tests.set_auth_amr(
  'password',
  floor(extract(epoch from statement_timestamp()))::bigint - 360
);
select throws_ok(
  format(
    'select * from public.activate_promoter_invite(%L)',
    (select invite_token from auth005_invite)
  ),
  '42501',
  'Recent strong authentication required',
  'a password authentication older than five minutes cannot activate a promoter invite'
);
select tests.set_auth_amr(
  'password',
  floor(extract(epoch from statement_timestamp()))::bigint + 120
);
select throws_ok(
  format(
    'select * from public.activate_promoter_invite(%L)',
    (select invite_token from auth005_invite)
  ),
  '42501',
  'Recent strong authentication required',
  'a strong authentication timestamp beyond the clock-skew tolerance is rejected'
);
select tests.set_auth_amr_entries(
  jsonb_build_array(
    jsonb_build_object(
      'method', 'password',
      'timestamp', floor(extract(epoch from statement_timestamp()))::bigint - 30
    ),
    jsonb_build_object(
      'method', 'otp',
      'timestamp', floor(extract(epoch from statement_timestamp()))::bigint
    )
  )
);
select throws_ok(
  format(
    'select * from public.activate_promoter_invite(%L)',
    (select invite_token from auth005_invite)
  ),
  '42501',
  'Recent strong authentication required',
  'the most recent AMR method must itself be password or OAuth'
);
select tests.set_auth_amr(
  'password',
  floor(extract(epoch from statement_timestamp()))::bigint
);
select is(
  (
    select status
    from public.activate_promoter_invite((select invite_token from auth005_invite))
  ),
  'activated',
  'a matching onboarded account with a recent password authentication activates the promoter invite'
);
reset role;

select is(
  (
    select role::text
    from public.organization_members
    where organization_id = 'b1000000-0000-4000-8000-000000000001'
      and user_id = 'a2000000-0000-4000-8000-000000000002'
  ),
  'editeur',
  'activation grants only the non-critical editor organization role'
);
select is(
  (
    select verification_status::text
    from public.user_roles
    where user_id = 'a2000000-0000-4000-8000-000000000002'
      and role = 'promoteur'
  ),
  'verified',
  'activation grants the verified promoter product role'
);
select is(
  (
    select organization_id
    from public.listings
    where id = 'c1000000-0000-4000-8000-000000000001'
  ),
  'b1000000-0000-4000-8000-000000000001'::uuid,
  'activation links the prefilled listing to the verified organization'
);
select is(
  (
    select owner_id
    from public.listings
    where id = 'c1000000-0000-4000-8000-000000000001'
  ),
  null::uuid,
  'activation never transfers personal listing ownership'
);
select is(
  (
    select primary_owner_id
    from public.organizations
    where id = 'b1000000-0000-4000-8000-000000000001'
  ),
  'a4000000-0000-4000-8000-000000000004'::uuid,
  'activation never transfers critical organization ownership'
);

insert into public.listings (
  id,
  type,
  subtype,
  listing_class,
  category_id,
  owner_id,
  submitted_by,
  status,
  name,
  slug,
  description,
  city_id,
  price_from_xof,
  price_unit
)
values (
  'c7000000-0000-4000-8000-000000000007',
  'etablissement',
  'restaurant',
  'commercial',
  'commercial-restaurant',
  'a4000000-0000-4000-8000-000000000004',
  'a1000000-0000-4000-8000-000000000001',
  'brouillon',
  'OAuth Auth005',
  'oauth-auth005',
  'Fiche commerciale preinscrite pour verifier une activation OAuth fraiche.',
  'cotonou',
  5000,
  'consommation'
);

select tests.use_auth_context(
  'authenticated',
  'a1000000-0000-4000-8000-000000000001'
);
create temporary table auth005_oauth_invite as
select *
from public.create_promoter_invite(
  'b1000000-0000-4000-8000-000000000001',
  'c7000000-0000-4000-8000-000000000007',
  'invitee@kwabor.test',
  now() + interval '1 day'
);
reset role;

select tests.use_auth_context(
  'authenticated',
  'a2000000-0000-4000-8000-000000000002'
);
select tests.set_auth_amr(
  'oauth',
  floor(extract(epoch from statement_timestamp()))::bigint
);
select is(
  (
    select status
    from public.activate_promoter_invite(
      (select invite_token from auth005_oauth_invite)
    )
  ),
  'activated',
  'a matching onboarded account with a recent OAuth authentication activates the promoter invite'
);
reset role;

select is(
  (
    select owner_id
    from public.listings
    where id = 'c7000000-0000-4000-8000-000000000007'
  ),
  null::uuid,
  'OAuth activation does not transfer personal listing ownership'
);
select is(
  (
    select count(*)::integer
    from public.organization_members
    where organization_id = 'b1000000-0000-4000-8000-000000000001'
      and user_id = 'a2000000-0000-4000-8000-000000000002'
      and role = 'editeur'
      and status = 'active'
  ),
  1,
  'repeated strong activation preserves one bounded editor membership'
);

update public.profiles
set onboarding_completed_at = null
where user_id = 'a2000000-0000-4000-8000-000000000002';

select is(
  tests.statement_succeeds_as(
    'authenticated',
    'a2000000-0000-4000-8000-000000000002',
    $sql$
      update public.listings
      set description = 'Tentative avant onboarding'
      where id = 'c1000000-0000-4000-8000-000000000001'
    $sql$
  ),
  true,
  'an incomplete organization editor receives a non-throwing RLS update response'
);
select is(
  (
    select description
    from public.listings
    where id = 'c1000000-0000-4000-8000-000000000001'
  ),
  'Fiche commerciale preinscrite pour le test AUTH-005.',
  'an incomplete organization editor cannot update a listing'
);
select ok(
  tests.statement_fails_as(
    'authenticated',
    'a2000000-0000-4000-8000-000000000002',
    $sql$
      insert into public.listing_media (listing_id, url, alt)
      values (
        'c1000000-0000-4000-8000-000000000001',
        'https://media.kwabor.test/auth005.jpg',
        'Media interdit avant onboarding'
      )
    $sql$
  ),
  'an incomplete organization editor cannot create listing media'
);

update public.profiles
set onboarding_completed_at = now()
where user_id = 'a2000000-0000-4000-8000-000000000002';

select is(
  tests.statement_succeeds_as(
    'authenticated',
    'a2000000-0000-4000-8000-000000000002',
    $sql$
      update public.listings
      set description = 'Modification autorisee apres onboarding par l editeur actif de l organisation verifiee Kwabor.'
      where id = 'c1000000-0000-4000-8000-000000000001'
    $sql$
  ),
  true,
  'the completed organization editor can update its linked listing'
);
select is(
  (
    select description
    from public.listings
    where id = 'c1000000-0000-4000-8000-000000000001'
  ),
  'Modification autorisee apres onboarding par l editeur actif de l organisation verifiee Kwabor.',
  'the completed organization editor update is persisted'
);

select ok(
  tests.statement_succeeds_as(
    'authenticated',
    'a2000000-0000-4000-8000-000000000002',
    $sql$
      insert into public.listings (
        type,
        subtype,
        listing_class,
        category_id,
        submitted_by,
        status,
        name,
        slug,
        description,
        city_id,
        price_from_xof,
        price_unit,
        organization_id
      )
      values (
        'etablissement',
        'restaurant',
        'commercial',
        'commercial-restaurant',
        'a2000000-0000-4000-8000-000000000002',
        'brouillon',
        'Nouvelle fiche organisation Auth005',
        'nouvelle-fiche-organisation-auth005',
        'Une fiche commerciale complete creee par un editeur actif de l organisation verifiee concernee.',
        'cotonou',
        5000,
        'consommation',
        'b1000000-0000-4000-8000-000000000001'
      )
    $sql$
  ),
  'an onboarded active editor can create a listing for its verified organization'
);
select ok(
  tests.statement_fails_as(
    'authenticated',
    'a2000000-0000-4000-8000-000000000002',
    $sql$
      insert into public.listings (
        type,
        subtype,
        listing_class,
        category_id,
        steward_id,
        submitted_by,
        status,
        name,
        slug,
        description,
        city_id,
        price_from_xof,
        price_unit,
        organization_id
      )
      values (
        'etablissement',
        'restaurant',
        'commercial',
        'commercial-restaurant',
        'a3000000-0000-4000-8000-000000000003',
        'a2000000-0000-4000-8000-000000000002',
        'brouillon',
        'Fiche organisation steward usurpe',
        'fiche-organisation-steward-usurpe',
        'Une tentative suffisamment detaillee pour attribuer illicitement la gestion a un utilisateur externe.',
        'cotonou',
        5000,
        'consommation',
        'b1000000-0000-4000-8000-000000000001'
      )
    $sql$
  ),
  'an organization editor cannot grant steward authority to an outsider'
);
select ok(
  tests.statement_fails_as(
    'authenticated',
    'a2000000-0000-4000-8000-000000000002',
    $sql$
      update public.listings
      set owner_id = 'a2000000-0000-4000-8000-000000000002'
      where id = 'c1000000-0000-4000-8000-000000000001'
    $sql$
  ),
  'an organization editor cannot transfer listing ownership'
);
select ok(
  tests.statement_fails_as(
    'authenticated',
    'a2000000-0000-4000-8000-000000000002',
    $sql$
      update public.listings
      set sponsored_until = now() + interval '1 year'
      where id = 'c1000000-0000-4000-8000-000000000001'
    $sql$
  ),
  'an organization editor cannot self-sponsor a listing'
);
select is(
  tests.statement_succeeds_as(
    'authenticated',
    'a3000000-0000-4000-8000-000000000003',
    $sql$
      update public.listings
      set description = 'Mutation IDOR interdite'
      where id = 'c1000000-0000-4000-8000-000000000001'
    $sql$
  ),
  true,
  'an outsider listing update is filtered by RLS without leaking existence'
);
select is(
  (
    select description
    from public.listings
    where id = 'c1000000-0000-4000-8000-000000000001'
  ),
  'Modification autorisee apres onboarding par l editeur actif de l organisation verifiee Kwabor.',
  'the outsider IDOR attempt does not mutate listing content'
);

select tests.use_auth_context(
  'authenticated',
  'a2000000-0000-4000-8000-000000000002'
);
select tests.set_auth_amr(
  'password',
  floor(extract(epoch from statement_timestamp()))::bigint
);
select is(
  (
    select status
    from public.activate_promoter_invite((select invite_token from auth005_invite))
  ),
  'accepted',
  'a consumed promoter invite cannot grant privileges twice'
);
reset role;

insert into public.listings (
  id,
  type,
  subtype,
  listing_class,
  category_id,
  submitted_by,
  status,
  name,
  slug,
  description,
  city_id,
  price_from_xof,
  price_unit,
  organization_id
)
values (
  'c2000000-0000-4000-8000-000000000002',
  'etablissement',
  'restaurant',
  'commercial',
  'commercial-restaurant',
  'a1000000-0000-4000-8000-000000000001',
  'brouillon',
  'Archive invitation Auth005',
  'archive-invitation-auth005',
  'Une seconde fiche commerciale utilisee pour verifier l anonymisation durable des invitations.',
  'cotonou',
  5000,
  'consommation',
  'b1000000-0000-4000-8000-000000000001'
);

insert into public.promoter_invites (
  organization_id,
  listing_id,
  email,
  token_hash,
  status,
  expires_at,
  created_by
)
values (
  'b1000000-0000-4000-8000-000000000001',
  'c2000000-0000-4000-8000-000000000002',
  'auth005-delete@kwabor.test',
  encode(extensions.digest(repeat('4', 64), 'sha256'), 'hex'),
  'pending',
  now() + interval '1 day',
  'a1000000-0000-4000-8000-000000000001'
);

insert into public.organization_members (
  organization_id,
  user_id,
  role,
  status,
  accepted_at
)
values
  (
    'b1000000-0000-4000-8000-000000000001',
    'a3000000-0000-4000-8000-000000000003',
    'moderateur',
    'active',
    now()
  ),
  (
    'b1000000-0000-4000-8000-000000000001',
    'a5000000-0000-4000-8000-000000000005',
    'editeur',
    'active',
    now()
  );

insert into public.organization_invites (
  organization_id,
  email,
  token_hash,
  proposed_role,
  invited_by_member_id,
  status,
  expires_at
)
values (
  'b1000000-0000-4000-8000-000000000001',
  'auth005-delete@kwabor.test',
  repeat('1', 64),
  'editeur',
  (
    select id
    from public.organization_members
    where user_id = 'a5000000-0000-4000-8000-000000000005'
      and organization_id = 'b1000000-0000-4000-8000-000000000001'
  ),
  'pending',
  now() + interval '1 day'
);

insert into public.organization_invites (
  organization_id,
  email,
  token_hash,
  proposed_role,
  invited_by_member_id,
  status,
  expires_at,
  accepted_by,
  accepted_at
)
values (
  'b1000000-0000-4000-8000-000000000001',
  'legacy-auth005-delete@kwabor.test',
  repeat('2', 64),
  'editeur',
  (
    select id
    from public.organization_members
    where user_id = 'a4000000-0000-4000-8000-000000000004'
      and organization_id = 'b1000000-0000-4000-8000-000000000001'
  ),
  'accepted',
  now() + interval '1 day',
  'a5000000-0000-4000-8000-000000000005',
  now()
);

insert into public.member_ad_budgets (
  organization_id,
  member_id,
  allocated_by_member_id,
  period_start,
  period_end,
  allocated_xof
)
values (
  'b1000000-0000-4000-8000-000000000001',
  (
    select id
    from public.organization_members
    where user_id = 'a3000000-0000-4000-8000-000000000003'
      and organization_id = 'b1000000-0000-4000-8000-000000000001'
  ),
  (
    select id
    from public.organization_members
    where user_id = 'a5000000-0000-4000-8000-000000000005'
      and organization_id = 'b1000000-0000-4000-8000-000000000001'
  ),
  current_date,
  current_date + 7,
  10000
);

insert into public.promoter_invites (
  organization_id,
  listing_id,
  email,
  token_hash,
  status,
  expires_at,
  accepted_by,
  accepted_at,
  created_by
)
values (
  'b1000000-0000-4000-8000-000000000001',
  'c2000000-0000-4000-8000-000000000002',
  'legacy-promoter-delete@kwabor.test',
  repeat('3', 64),
  'accepted',
  now() + interval '1 day',
  'a5000000-0000-4000-8000-000000000005',
  now(),
  'a1000000-0000-4000-8000-000000000001'
);

set local role service_role;
select is(
  (
    select status
    from public.prepare_account_deletion(
      'a4000000-0000-4000-8000-000000000004',
      'd1000000-0000-4000-8000-000000000001'
    )
  ),
  'ownership_conflict',
  'account deletion is blocked while the user owns an organization'
);
reset role;

insert into storage.buckets (id, name, public)
values ('auth005-private', 'auth005-private', false);
insert into storage.objects (bucket_id, name, owner_id)
values (
  'auth005-private',
  'a6000000-0000-4000-8000-000000000006/object.jpg',
  'a6000000-0000-4000-8000-000000000006'
);

select ok(
  tests.statement_succeeds_as(
    'authenticated',
    'a5000000-0000-4000-8000-000000000005',
    $sql$
      insert into storage.objects (bucket_id, name, owner_id)
      values (
        'auth005-private',
        'a5000000-0000-4000-8000-000000000005/preparation-race.jpg',
        'a5000000-0000-4000-8000-000000000005'
      )
    $sql$
  ),
  'an onboarded user without a tombstone can pass the restrictive Storage fence'
);
update storage.objects
set owner_id = null
where bucket_id = 'auth005-private'
  and name = 'a5000000-0000-4000-8000-000000000005/preparation-race.jpg';

set local role service_role;
select is(
  (
    select status
    from public.prepare_account_deletion(
      'a6000000-0000-4000-8000-000000000006',
      'd2000000-0000-4000-8000-000000000002'
    )
  ),
  'storage_conflict',
  'account deletion is blocked while Auth-owned Storage objects remain'
);
select is(
  (
    select status
    from public.prepare_account_deletion(
      'a5000000-0000-4000-8000-000000000005',
      'd3000000-0000-4000-8000-000000000003'
    )
  ),
  'prepared',
  'a non-owner account without Storage objects can be prepared for deletion'
);

reset role;
select tests.use_auth_context(
  'authenticated',
  'a5000000-0000-4000-8000-000000000005'
);
select tests.set_auth_amr(
  'password',
  floor(extract(epoch from statement_timestamp()))::bigint
);
select throws_ok(
  $sql$
    select *
    from public.activate_promoter_invite(repeat('4', 64))
  $sql$,
  '42501',
  'Account deletion in progress',
  'a prepared deletion tombstone blocks promoter activation while the retry profile is retained'
);
select throws_ok(
  $sql$
    select *
    from public.complete_user_onboarding(
      'Donnee',
      'Reintroduite',
      'cotonou',
      'fr',
      'XOF',
      null,
      null,
      null
    )
  $sql$,
  '42501',
  'Account deletion in progress',
  'a prepared deletion tombstone blocks the idempotent onboarding completion path'
);
reset role;

insert into storage.objects (bucket_id, name, owner_id)
values (
  'auth005-private',
  'a5000000-0000-4000-8000-000000000005/retry.jpg',
  'a5000000-0000-4000-8000-000000000005'
);

set local role service_role;
select is(
  (
    select status
    from public.prepare_account_deletion(
      'a5000000-0000-4000-8000-000000000005',
      'd4000000-0000-4000-8000-000000000004'
    )
  ),
  'storage_conflict',
  'a prepared retry revalidates newly introduced Storage conflicts'
);

update storage.objects
set owner_id = null
where bucket_id = 'auth005-private'
  and name = 'a5000000-0000-4000-8000-000000000005/retry.jpg';

create temporary table auth005_deletion_retry as
select *
from public.prepare_account_deletion(
  'a5000000-0000-4000-8000-000000000005',
  'd4000000-0000-4000-8000-000000000004'
);

select is(
  (select status from auth005_deletion_retry),
  'prepared',
  'a restarted deletion request resumes the existing prepared operation'
);
select is(
  (select effective_idempotency_key from auth005_deletion_retry),
  'd3000000-0000-4000-8000-000000000003'::uuid,
  'a fresh client key resolves to the original effective server key'
);
reset role;

select ok(
  tests.statement_fails_as(
    'authenticated',
    'a5000000-0000-4000-8000-000000000005',
    $sql$
      insert into storage.objects (bucket_id, name, owner_id)
      values (
        'auth005-private',
        'a5000000-0000-4000-8000-000000000005/late-upload.jpg',
        'a5000000-0000-4000-8000-000000000005'
      )
    $sql$
  ),
  'a prepared tombstone rejects new owned Storage objects'
);

select is(
  (
    select count(*)::integer
    from public.profiles
    where user_id = 'a5000000-0000-4000-8000-000000000005'
  ),
  1,
  'account deletion preparation retains the profile until Auth deletion succeeds'
);
select tests.use_auth_context(
  'authenticated',
  'a5000000-0000-4000-8000-000000000005'
);
select ok(
  not app_private.current_user_has_completed_onboarding(),
  'a prepared account cannot be treated as onboarded'
);
reset role;

delete from auth.users
where id = 'a5000000-0000-4000-8000-000000000005';

select is(
  (
    select invited_by_member_id
    from public.organization_invites
    where token_hash = repeat('1', 64)
  ),
  null::uuid,
  'deleting the Auth user preserves organization invites and nulls their inviter FK'
);
select is(
  (
    select allocated_by_member_id
    from public.member_ad_budgets
    where organization_id = 'b1000000-0000-4000-8000-000000000001'
      and allocated_xof = 10000
  ),
  null::uuid,
  'deleting the Auth user preserves budget history and nulls its allocator FK'
);
select is(
  (
    select count(*)::integer
    from public.organization_invites
    where email in (
      'auth005-delete@kwabor.test',
      'legacy-auth005-delete@kwabor.test'
    )
      or accepted_by = 'a5000000-0000-4000-8000-000000000005'
  ),
  0,
  'organization invitation PII is anonymized before Auth deletion'
);
select is(
  (
    select count(*)::integer
    from public.promoter_invites
    where email = 'legacy-promoter-delete@kwabor.test'
      or accepted_by = 'a5000000-0000-4000-8000-000000000005'
  ),
  0,
  'promoter invitation PII is anonymized before Auth deletion'
);

create temporary table auth005_reconcile as
select *
from app_private.reconcile_account_deletion_requests();

select is(
  (select completed_count from auth005_reconcile),
  1,
  'reconciliation completes prepared tombstones whose Auth user is absent'
);
select is(
  (
    select status
    from public.account_deletion_requests
    where user_id = 'a5000000-0000-4000-8000-000000000005'
      and idempotency_key = 'd3000000-0000-4000-8000-000000000003'
  ),
  'completed'::public.account_deletion_status,
  'reconciliation records durable account deletion completion'
);

update public.account_deletion_requests
set completed_at = now() - interval '31 days'
where user_id = 'a5000000-0000-4000-8000-000000000005';

create temporary table auth005_purge as
select *
from app_private.reconcile_account_deletion_requests();

select is(
  (select purged_count from auth005_purge),
  1,
  'reconciliation purges completed deletion tombstones after the 30-day TTL'
);
select is(
  (
    select count(*)::integer
    from public.account_deletion_requests
    where user_id = 'a5000000-0000-4000-8000-000000000005'
  ),
  0,
  'purged account deletion tombstones retain no long-lived user identifier'
);

select ok(
  not exists (
    select 1
    from pg_catalog.pg_available_extensions
    where name = 'pg_cron'
  )
  or tests.cron_job_exists('kwabor-account-deletion-reconcile'),
  'the reconciliation job is versioned whenever pg_cron is available'
);

select * from finish();
rollback;
