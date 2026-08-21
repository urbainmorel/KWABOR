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

create or replace function tests.statement_succeeds_as(
  db_role text,
  uid uuid,
  sql text
)
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

create or replace function tests.statement_fails_as(
  db_role text,
  uid uuid,
  sql text
)
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

create or replace function tests.affected_rows_as(
  db_role text,
  uid uuid,
  sql text
)
returns bigint
language plpgsql
as $$
declare
  affected_rows bigint;
begin
  perform tests.use_auth_context(db_role, uid);
  execute sql;
  get diagnostics affected_rows = row_count;
  reset role;
  return affected_rows;
exception
  when others then
    reset role;
    raise;
end;
$$;

create or replace function tests.sqlstate_as(
  db_role text,
  uid uuid,
  sql text
)
returns text
language plpgsql
as $$
declare
  returned_state text;
begin
  perform tests.use_auth_context(db_role, uid);
  execute sql;
  reset role;
  return null;
exception
  when others then
    get stacked diagnostics returned_state = returned_sqlstate;
    reset role;
    return returned_state;
end;
$$;

select plan(79);

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
    '91000000-0000-4000-8000-000000000001',
    'authenticated',
    'authenticated',
    'sec-admin@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    '91000000-0000-4000-8000-000000000002',
    'authenticated',
    'authenticated',
    'sec-promoter@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    '91000000-0000-4000-8000-000000000003',
    'authenticated',
    'authenticated',
    'sec-guide@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    '91000000-0000-4000-8000-000000000004',
    'authenticated',
    'authenticated',
    'sec-institution@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    '91000000-0000-4000-8000-000000000005',
    'authenticated',
    'authenticated',
    'sec-user@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    '91000000-0000-4000-8000-000000000006',
    'authenticated',
    'authenticated',
    'sec-member@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    '91000000-0000-4000-8000-000000000007',
    'authenticated',
    'authenticated',
    'sec-google@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    '91000000-0000-4000-8000-000000000008',
    'authenticated',
    'authenticated',
    'sec-apple@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    '91000000-0000-4000-8000-000000000009',
    'authenticated',
    'authenticated',
    'sec-github@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    '91000000-0000-4000-8000-00000000000a',
    'authenticated',
    'authenticated',
    'sec-otp@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    '91000000-0000-4000-8000-00000000000b',
    'authenticated',
    'authenticated',
    'sec-other-author@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    '91000000-0000-4000-8000-00000000000c',
    'authenticated',
    'authenticated',
    'sec-manager@kwabor.test',
    '',
    now(),
    now(),
    now()
  );

insert into auth.identities (
  id,
  provider_id,
  user_id,
  identity_data,
  provider,
  last_sign_in_at,
  created_at,
  updated_at
)
values
  (
    '93000000-0000-4000-8000-000000000001',
    'google-sec-onboarding',
    '91000000-0000-4000-8000-000000000007',
    jsonb_build_object(
      'sub', 'google-sec-onboarding',
      'email', 'sec-google@kwabor.test',
      'email_verified', true
    ),
    'google',
    now(),
    now(),
    now()
  ),
  (
    '93000000-0000-4000-8000-000000000002',
    'apple-sec-onboarding',
    '91000000-0000-4000-8000-000000000008',
    jsonb_build_object(
      'sub', 'apple-sec-onboarding',
      'email', 'sec-apple@kwabor.test',
      'email_verified', true
    ),
    'apple',
    now(),
    now(),
    now()
  ),
  (
    '93000000-0000-4000-8000-000000000003',
    'github-sec-onboarding',
    '91000000-0000-4000-8000-000000000009',
    jsonb_build_object(
      'sub', 'github-sec-onboarding',
      'email', 'sec-github@kwabor.test',
      'email_verified', true
    ),
    'github',
    now(),
    now(),
    now()
  );

insert into public.legal_documents (
  id,
  document_type,
  version,
  locale,
  content_url,
  content_sha256,
  effective_at,
  active
)
values
  (
    '92000000-0000-4000-8000-000000000001',
    'terms',
    'sec-001',
    'fr',
    'https://legal.kwabor.test/sec-001/terms',
    repeat('1', 64),
    now() - interval '1 day',
    true
  ),
  (
    '92000000-0000-4000-8000-000000000002',
    'privacy_policy',
    'sec-001',
    'fr',
    'https://legal.kwabor.test/sec-001/privacy',
    repeat('2', 64),
    now() - interval '1 day',
    true
  ),
  (
    '92000000-0000-4000-8000-000000000003',
    'ugc_license',
    'sec-001',
    'fr',
    'https://legal.kwabor.test/sec-001/ugc',
    repeat('3', 64),
    now() - interval '1 day',
    true
  );

insert into public.profiles (
  user_id,
  first_name,
  last_name,
  city_id,
  onboarding_completed_at
)
values
  ('91000000-0000-4000-8000-000000000001', 'Admin', 'SEC', 'cotonou', now()),
  ('91000000-0000-4000-8000-000000000002', 'Promoteur', 'SEC', 'cotonou', now()),
  ('91000000-0000-4000-8000-000000000003', 'Guide', 'SEC', 'cotonou', now()),
  ('91000000-0000-4000-8000-000000000004', 'Institution', 'SEC', 'ouidah', now()),
  ('91000000-0000-4000-8000-000000000005', 'Utilisateur', 'SEC', 'cotonou', now()),
  ('91000000-0000-4000-8000-000000000006', 'Membre', 'SEC', 'cotonou', now()),
  ('91000000-0000-4000-8000-00000000000b', 'Autre', 'Auteur', 'cotonou', now()),
  ('91000000-0000-4000-8000-00000000000c', 'Gestionnaire', 'SEC', 'cotonou', now());

insert into public.user_roles (
  user_id,
  role,
  verification_status
)
values
  ('91000000-0000-4000-8000-000000000001', 'admin', 'verified'),
  ('91000000-0000-4000-8000-000000000002', 'promoteur', 'verified'),
  ('91000000-0000-4000-8000-000000000003', 'guide', 'verified'),
  ('91000000-0000-4000-8000-000000000004', 'institution', 'verified'),
  ('91000000-0000-4000-8000-000000000005', 'user', 'unverified'),
  ('91000000-0000-4000-8000-000000000006', 'user', 'unverified'),
  ('91000000-0000-4000-8000-00000000000b', 'user', 'unverified'),
  ('91000000-0000-4000-8000-00000000000c', 'user', 'unverified');

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
  '94000000-0000-4000-8000-000000000001',
  'promoteur',
  'Organisation SEC',
  'organisation-sec',
  'verified',
  '91000000-0000-4000-8000-000000000002',
  '91000000-0000-4000-8000-000000000001'
);

insert into public.organization_members (
  id,
  organization_id,
  user_id,
  role,
  status,
  accepted_at
)
values
  (
    '95000000-0000-4000-8000-000000000001',
    '94000000-0000-4000-8000-000000000001',
    '91000000-0000-4000-8000-000000000002',
    'proprietaire',
    'active',
    now()
  ),
  (
    '95000000-0000-4000-8000-000000000002',
    '94000000-0000-4000-8000-000000000001',
    '91000000-0000-4000-8000-00000000000c',
    'gestionnaire',
    'active',
    now()
  ),
  (
    '95000000-0000-4000-8000-000000000003',
    '94000000-0000-4000-8000-000000000001',
    '91000000-0000-4000-8000-000000000006',
    'editeur',
    'active',
    now()
  );

-- This row models legacy ownership so media authorization is tested independently
-- from the new INSERT trigger.
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
  city_id
)
values (
  '96000000-0000-4000-8000-000000000001',
  'etablissement',
  'restaurant',
  'commercial',
  'commercial-restaurant',
  '91000000-0000-4000-8000-000000000003',
  '91000000-0000-4000-8000-000000000003',
  'brouillon',
  'Restaurant historique du guide',
  'restaurant-historique-guide-sec',
  'Fiche coherente creee avant le durcissement de la matrice de roles.',
  'cotonou'
);

select ok(
  tests.statement_succeeds_as(
    'authenticated',
    '91000000-0000-4000-8000-000000000007',
    $sql$
      select * from public.complete_user_onboarding(
        'Google',
        'Utilisateur',
        'cotonou',
        'fr',
        'XOF',
        '92000000-0000-4000-8000-000000000001',
        '92000000-0000-4000-8000-000000000002',
        '92000000-0000-4000-8000-000000000003'
      )
    $sql$
  ),
  'a Google identity can complete onboarding without a Supabase password'
);

select is(
  (
    select count(*)
    from public.profiles
    where user_id = '91000000-0000-4000-8000-000000000007'
      and onboarding_completed_at is not null
  ),
  1::bigint,
  'Google onboarding persists one completed profile'
);

select is(
  (
    select count(*)
    from public.user_legal_acceptances
    where user_id = '91000000-0000-4000-8000-000000000007'
  ),
  3::bigint,
  'Google onboarding records all legal acceptances'
);

select is(
  (
    select count(*)
    from public.user_roles
    where user_id = '91000000-0000-4000-8000-000000000007'
      and role = 'user'
  ),
  1::bigint,
  'Google onboarding grants exactly one base user role'
);

select ok(
  tests.statement_succeeds_as(
    'authenticated',
    '91000000-0000-4000-8000-000000000007',
    $sql$
      select * from public.complete_user_onboarding(
        'Ignored',
        'Retry',
        'ouidah',
        'fr',
        'EUR',
        '92000000-0000-4000-8000-000000000001',
        '92000000-0000-4000-8000-000000000002',
        '92000000-0000-4000-8000-000000000003'
      )
    $sql$
  ),
  'federated onboarding remains idempotent'
);

select ok(
  tests.statement_succeeds_as(
    'authenticated',
    '91000000-0000-4000-8000-000000000008',
    $sql$
      select * from public.complete_user_onboarding(
        'Apple',
        'Utilisateur',
        'ouidah',
        'fr',
        'XOF',
        '92000000-0000-4000-8000-000000000001',
        '92000000-0000-4000-8000-000000000002',
        '92000000-0000-4000-8000-000000000003'
      )
    $sql$
  ),
  'an Apple identity can complete onboarding without a Supabase password'
);

select is(
  (
    select count(*)
    from public.profiles
    where user_id = '91000000-0000-4000-8000-000000000008'
      and onboarding_completed_at is not null
  ),
  1::bigint,
  'Apple onboarding persists one completed profile'
);

select is(
  tests.sqlstate_as(
    'authenticated',
    '91000000-0000-4000-8000-000000000009',
    $sql$
      select * from public.complete_user_onboarding(
        'Github',
        'Utilisateur',
        'cotonou',
        'fr',
        'XOF',
        '92000000-0000-4000-8000-000000000001',
        '92000000-0000-4000-8000-000000000002',
        '92000000-0000-4000-8000-000000000003'
      )
    $sql$
  ),
  '22023',
  'an unsupported federated provider cannot bypass the credential requirement'
);

select is(
  tests.sqlstate_as(
    'authenticated',
    '91000000-0000-4000-8000-00000000000a',
    $sql$
      select * from public.complete_user_onboarding(
        'OTP',
        'Utilisateur',
        'cotonou',
        'fr',
        'XOF',
        '92000000-0000-4000-8000-000000000001',
        '92000000-0000-4000-8000-000000000002',
        '92000000-0000-4000-8000-000000000003'
      )
    $sql$
  ),
  '22023',
  'a passwordless account without a supported identity remains rejected'
);

select ok(
  not has_table_privilege('authenticated', 'public.social_posts', 'insert'),
  'social posts have no table-wide authenticated INSERT privilege'
);

select is(
  (
    select string_agg(attribute.attname, ',' order by attribute.attname)
    from pg_catalog.pg_attribute attribute
    where attribute.attrelid = 'public.social_posts'::regclass
      and attribute.attnum > 0
      and not attribute.attisdropped
      and has_column_privilege(
        'authenticated',
        'public.social_posts',
        attribute.attname,
        'insert'
      )
  ),
  'author_id,caption,content_lang,listing_id,social_media_type',
  'social INSERT privileges expose exactly the author-owned columns'
);

select is(
  (
    select string_agg(attribute.attname, ',' order by attribute.attname)
    from pg_catalog.pg_attribute attribute
    where attribute.attrelid = 'public.social_posts'::regclass
      and attribute.attnum > 0
      and not attribute.attisdropped
      and has_column_privilege(
        'authenticated',
        'public.social_posts',
        attribute.attname,
        'update'
      )
  ),
  'caption,content_lang',
  'social UPDATE privileges expose exactly editable content'
);

select ok(
  not has_function_privilege(
    'anon',
    'public.moderate_social_post(uuid, public.social_post_status, boolean)',
    'execute'
  ),
  'anonymous sessions cannot execute the social moderation RPC'
);

select ok(
  not has_function_privilege(
    'anon',
    'public.current_user_can_manage_listing(uuid)',
    'execute'
  ),
  'anonymous sessions cannot execute the privileged listing helper'
);

select ok(
  not has_function_privilege(
    'anon',
    'public.accept_organization_invite(text)',
    'execute'
  ),
  'anonymous sessions cannot execute the organization invitation acceptance RPC'
);

select ok(
  has_function_privilege(
    'authenticated',
    'public.accept_organization_invite(text)',
    'execute'
  ),
  'signed-in users can execute the organization invitation acceptance RPC'
);

select ok(
  not has_function_privilege(
    'service_role',
    'public.accept_organization_invite(text)',
    'execute'
  ),
  'the elevated backend role cannot execute the end-user invitation RPC'
);

select ok(
  not exists (
    select 1
    from pg_catalog.pg_proc function_record
    join pg_catalog.pg_namespace function_namespace
      on function_namespace.oid = function_record.pronamespace
    where function_namespace.nspname = 'public'
      and function_record.proname = 'rls_auto_enable'
      and function_record.pronargs = 0
      and (
        has_function_privilege('anon', function_record.oid, 'execute')
        or has_function_privilege(
          'authenticated',
          function_record.oid,
          'execute'
        )
        or has_function_privilege(
          'service_role',
          function_record.oid,
          'execute'
        )
      )
  ),
  'Data API roles cannot execute the hosted RLS event-trigger helper'
);

select is(
  (
    select array_agg(function_record.proname::text order by function_record.proname)
    from pg_catalog.pg_proc function_record
    join pg_catalog.pg_namespace function_namespace
      on function_namespace.oid = function_record.pronamespace
    where function_namespace.nspname = 'public'
      and function_record.prosecdef
      and has_function_privilege(
        'authenticated',
        function_record.oid,
        'execute'
      )
  ),
  array[
    'accept_organization_invite',
    'activate_promoter_invite',
    'clear_search_history_v1',
    'complete_user_onboarding',
    'create_promoter_invite',
    'current_user_can_manage_listing',
    'delete_search_history_entry_v1',
    'list_search_history_v1',
    'moderate_social_post',
    'preview_promoter_invite',
    'record_search_history_v1',
    'suspend_organization_member'
  ]::text[],
  'the reviewed authenticated SECURITY DEFINER RPC allowlist is exact'
);

select ok(
  has_column_privilege(
    'authenticated',
    'public.social_posts',
    'author_id',
    'insert'
  ),
  'social authors can provide their own author identifier'
);

select ok(
  not has_column_privilege(
    'authenticated',
    'public.social_posts',
    'moderation_status',
    'insert'
  ),
  'social authors cannot choose moderation state'
);

select ok(
  not has_column_privilege(
    'authenticated',
    'public.social_posts',
    'likes_count',
    'insert'
  ),
  'social authors cannot seed engagement counters'
);

select ok(
  tests.statement_succeeds_as(
    'authenticated',
    '91000000-0000-4000-8000-000000000005',
    $sql$
      insert into public.social_posts (
        author_id,
        social_media_type,
        listing_id,
        caption,
        content_lang
      )
      values (
        '91000000-0000-4000-8000-000000000005',
        'photo',
        '00000000-0000-4000-8000-000000000101',
        'Publication SEC normale',
        'fr'
      )
    $sql$
  ),
  'an onboarded author can create a normal pending social post'
);

select is(
  (
    select
      moderation_status::text
      || '|'
      || watermark_applied::text
      || '|'
      || likes_count::text
    from public.social_posts
    where caption = 'Publication SEC normale'
  ),
  'en_attente|false|0',
  'server-owned social authority fields keep their safe defaults'
);

select is(
  tests.sqlstate_as(
    'authenticated',
    '91000000-0000-4000-8000-000000000005',
    $sql$
      insert into public.social_posts (
        author_id,
        social_media_type,
        listing_id,
        caption,
        content_lang,
        moderation_status
      )
      values (
        '91000000-0000-4000-8000-000000000005',
        'photo',
        '00000000-0000-4000-8000-000000000101',
        'Auto publication interdite',
        'fr',
        'publie'
      )
    $sql$
  ),
  '42501',
  'an author cannot self-publish a social post'
);

select is(
  tests.sqlstate_as(
    'authenticated',
    '91000000-0000-4000-8000-000000000005',
    $sql$
      insert into public.social_posts (
        author_id,
        social_media_type,
        listing_id,
        caption,
        content_lang,
        watermark_applied
      )
      values (
        '91000000-0000-4000-8000-000000000005',
        'photo',
        '00000000-0000-4000-8000-000000000101',
        'Watermark falsifie',
        'fr',
        true
      )
    $sql$
  ),
  '42501',
  'an author cannot claim that a social watermark was applied'
);

select is(
  tests.affected_rows_as(
    'authenticated',
    '91000000-0000-4000-8000-000000000005',
    $sql$
      update public.social_posts
      set caption = 'Publication SEC modifiee'
      where caption = 'Publication SEC normale'
    $sql$
  ),
  1::bigint,
  'an author can edit the caption of their pending post'
);

select is(
  tests.sqlstate_as(
    'authenticated',
    '91000000-0000-4000-8000-000000000005',
    $sql$
      update public.social_posts
      set moderation_status = 'publie'
      where caption = 'Publication SEC modifiee'
    $sql$
  ),
  '42501',
  'an author cannot update social moderation state'
);

select is(
  tests.affected_rows_as(
    'authenticated',
    '91000000-0000-4000-8000-00000000000b',
    $sql$
      update public.social_posts
      set caption = 'Mutation par un tiers'
      where caption = 'Publication SEC modifiee'
    $sql$
  ),
  0::bigint,
  'another author cannot edit a pending social post'
);

select is(
  tests.sqlstate_as(
    'anon',
    null,
    $sql$
      select * from public.moderate_social_post(
        (
          select id
          from public.social_posts
          where caption = 'Publication SEC modifiee'
        ),
        'publie',
        true
      )
    $sql$
  ),
  '42501',
  'anonymous sessions are rejected by the social moderation RPC ACL'
);

select is(
  tests.sqlstate_as(
    'authenticated',
    '91000000-0000-4000-8000-000000000005',
    $sql$
      select * from public.moderate_social_post(
        (
          select id
          from public.social_posts
          where caption = 'Publication SEC modifiee'
        ),
        'publie',
        true
      )
    $sql$
  ),
  '42501',
  'a non-admin cannot moderate a social post'
);

select is(
  tests.sqlstate_as(
    'authenticated',
    '91000000-0000-4000-8000-000000000001',
    $sql$
      select * from public.moderate_social_post(
        (
          select id
          from public.social_posts
          where caption = 'Publication SEC modifiee'
        ),
        'publie',
        false
      )
    $sql$
  ),
  '22023',
  'an admin cannot publish social media without a watermark'
);

select is(
  tests.sqlstate_as(
    'authenticated',
    '91000000-0000-4000-8000-000000000001',
    $sql$
      select * from public.moderate_social_post(
        'ffffffff-ffff-4fff-8fff-ffffffffffff',
        'rejete',
        false
      )
    $sql$
  ),
  'P0002',
  'the moderation RPC rejects an unknown social post'
);

select ok(
  tests.statement_succeeds_as(
    'authenticated',
    '91000000-0000-4000-8000-000000000001',
    $sql$
      select * from public.moderate_social_post(
        (
          select id
          from public.social_posts
          where caption = 'Publication SEC modifiee'
        ),
        'publie',
        true
      )
    $sql$
  ),
  'an onboarded verified admin can publish watermarked social media'
);

select is(
  (
    select
      moderation_status::text
      || '|'
      || watermark_applied::text
    from public.social_posts
    where caption = 'Publication SEC modifiee'
  ),
  'publie|true',
  'the moderation RPC persists the approved state and watermark'
);

select is(
  tests.sqlstate_as(
    'authenticated',
    '91000000-0000-4000-8000-000000000005',
    $sql$
      update public.social_posts
      set caption = 'Mutation apres publication'
      where caption = 'Publication SEC modifiee'
    $sql$
  ),
  '42501',
  'an author cannot mutate a published social post'
);

select ok(
  not has_table_privilege(
    'authenticated',
    'public.organization_members',
    'insert'
  ),
  'organization membership creation is not exposed directly'
);

select is(
  (
    select string_agg(attribute.attname, ',' order by attribute.attname)
    from pg_catalog.pg_attribute attribute
    where attribute.attrelid = 'public.organization_members'::regclass
      and attribute.attnum > 0
      and not attribute.attisdropped
      and has_column_privilege(
        'authenticated',
        'public.organization_members',
        attribute.attname,
        'update'
      )
  ),
  'role',
  'organization member UPDATE privileges expose only role'
);

select ok(
  has_column_privilege(
    'authenticated',
    'public.organization_members',
    'role',
    'update'
  ),
  'organization managers retain role-only updates'
);

select ok(
  not has_column_privilege(
    'authenticated',
    'public.organization_members',
    'status',
    'update'
  ),
  'organization membership lifecycle is not client-writable'
);

select is(
  tests.affected_rows_as(
    'authenticated',
    '91000000-0000-4000-8000-00000000000c',
    $sql$
      update public.organization_members
      set role = 'moderateur'
      where id = '95000000-0000-4000-8000-000000000003'
    $sql$
  ),
  1::bigint,
  'a manager can downgrade an editor to moderator'
);

select is(
  (
    select role::text
    from public.organization_members
    where id = '95000000-0000-4000-8000-000000000003'
  ),
  'moderateur',
  'the authorized organization role change is persisted'
);

select is(
  tests.sqlstate_as(
    'authenticated',
    '91000000-0000-4000-8000-00000000000c',
    $sql$
      update public.organization_members
      set user_id = '91000000-0000-4000-8000-00000000000b'
      where id = '95000000-0000-4000-8000-000000000003'
    $sql$
  ),
  '42501',
  'a manager cannot reassign a membership to another user'
);

select is(
  tests.sqlstate_as(
    'authenticated',
    '91000000-0000-4000-8000-00000000000c',
    $sql$
      update public.organization_members
      set status = 'suspended'
      where id = '95000000-0000-4000-8000-000000000003'
    $sql$
  ),
  '42501',
  'a manager cannot bypass the membership lifecycle with direct SQL'
);

select ok(
  tests.statement_succeeds_as(
    'authenticated',
    '91000000-0000-4000-8000-000000000002',
    $sql$
      select * from public.suspend_organization_member(
        '94000000-0000-4000-8000-000000000001',
        '95000000-0000-4000-8000-000000000003'
      )
    $sql$
  ),
  'an owner can suspend a subordinate through the authorized RPC'
);

select is(
  (
    select status::text
    from public.organization_members
    where id = '95000000-0000-4000-8000-000000000003'
  ),
  'suspended',
  'the suspension RPC persists the lifecycle transition'
);

select is(
  tests.sqlstate_as(
    'authenticated',
    '91000000-0000-4000-8000-00000000000c',
    $sql$
      select * from public.suspend_organization_member(
        '94000000-0000-4000-8000-000000000001',
        '95000000-0000-4000-8000-000000000001'
      )
    $sql$
  ),
  'P0002',
  'a manager cannot suspend the organization owner'
);

select ok(
  has_column_privilege(
    'authenticated',
    'public.claims',
    'claimant_id',
    'insert'
  ),
  'claimants can submit their own identifier'
);

select is(
  (
    select string_agg(attribute.attname, ',' order by attribute.attname)
    from pg_catalog.pg_attribute attribute
    where attribute.attrelid = 'public.claims'::regclass
      and attribute.attnum > 0
      and not attribute.attisdropped
      and has_column_privilege(
        'authenticated',
        'public.claims',
        attribute.attname,
        'insert'
      )
  ),
  'claimant_id,contact_phone,listing_id,proof_url',
  'claim INSERT privileges expose exactly submission fields'
);

select is(
  (
    select string_agg(attribute.attname, ',' order by attribute.attname)
    from pg_catalog.pg_attribute attribute
    where attribute.attrelid = 'public.claims'::regclass
      and attribute.attnum > 0
      and not attribute.attisdropped
      and has_column_privilege(
        'authenticated',
        'public.claims',
        attribute.attname,
        'update'
      )
  ),
  'decision_reason,status',
  'claim UPDATE privileges expose exactly administrative decision fields'
);

select ok(
  not has_column_privilege(
    'authenticated',
    'public.claims',
    'status',
    'insert'
  ),
  'claimants cannot choose a claim decision'
);

select ok(
  tests.statement_succeeds_as(
    'authenticated',
    '91000000-0000-4000-8000-000000000002',
    $sql$
      insert into public.claims (
        listing_id,
        claimant_id,
        contact_phone
      )
      values (
        '00000000-0000-4000-8000-000000000103',
        '91000000-0000-4000-8000-000000000002',
        '+2290191000002'
      )
    $sql$
  ),
  'a verified promoter can submit a safe pending claim'
);

select is(
  (
    select
      status::text
      || '|'
      || coalesce(decision_reason, 'null')
    from public.claims
    where claimant_id = '91000000-0000-4000-8000-000000000002'
  ),
  'en_attente|null',
  'claim authority fields retain server defaults'
);

select is(
  tests.sqlstate_as(
    'authenticated',
    '91000000-0000-4000-8000-000000000002',
    $sql$
      insert into public.claims (
        listing_id,
        claimant_id,
        contact_phone,
        status
      )
      values (
        '00000000-0000-4000-8000-000000000102',
        '91000000-0000-4000-8000-000000000002',
        '+2290191000003',
        'approuve'
      )
    $sql$
  ),
  '42501',
  'a claimant cannot self-approve a claim'
);

select is(
  tests.affected_rows_as(
    'authenticated',
    '91000000-0000-4000-8000-000000000001',
    $sql$
      update public.claims
      set
        status = 'approuve',
        decision_reason = 'Justificatif valide'
      where claimant_id = '91000000-0000-4000-8000-000000000002'
    $sql$
  ),
  1::bigint,
  'an onboarded verified admin can decide a claim'
);

select is(
  (
    select
      status::text
      || '|'
      || decision_reason
    from public.claims
    where claimant_id = '91000000-0000-4000-8000-000000000002'
  ),
  'approuve|Justificatif valide',
  'the authorized claim decision is persisted'
);

select is(
  (
    select string_agg(attribute.attname, ',' order by attribute.attname)
    from pg_catalog.pg_attribute attribute
    where attribute.attrelid = 'public.missing_place_reports'::regclass
      and attribute.attnum > 0
      and not attribute.attisdropped
      and has_column_privilege(
        'authenticated',
        'public.missing_place_reports',
        attribute.attname,
        'insert'
      )
  ),
  'city_id,lat,lng,name,note,photo_url,presumed_type,reporter_id',
  'missing-place INSERT privileges expose exactly reporter-owned fields'
);

select is(
  (
    select string_agg(attribute.attname, ',' order by attribute.attname)
    from pg_catalog.pg_attribute attribute
    where attribute.attrelid = 'public.missing_place_reports'::regclass
      and attribute.attnum > 0
      and not attribute.attisdropped
      and has_column_privilege(
        'authenticated',
        'public.missing_place_reports',
        attribute.attname,
        'update'
      )
  ),
  'assigned_admin_id,status',
  'missing-place UPDATE privileges expose exactly moderation fields'
);

select ok(
  tests.statement_succeeds_as(
    'authenticated',
    '91000000-0000-4000-8000-000000000005',
    $sql$
      insert into public.missing_place_reports (
        reporter_id,
        name,
        presumed_type,
        city_id,
        note
      )
      values (
        '91000000-0000-4000-8000-000000000005',
        'Lieu SEC a verifier',
        'lieu',
        'cotonou',
        'Signalement de test sans decision client.'
      )
    $sql$
  ),
  'an onboarded user can submit a safe missing-place report'
);

select is(
  (
    select
      status::text
      || '|'
      || coalesce(assigned_admin_id::text, 'null')
    from public.missing_place_reports
    where reporter_id = '91000000-0000-4000-8000-000000000005'
  ),
  'nouveau|null',
  'missing-place authority fields retain server defaults'
);

select is(
  tests.sqlstate_as(
    'authenticated',
    '91000000-0000-4000-8000-000000000005',
    $sql$
      insert into public.missing_place_reports (
        reporter_id,
        name,
        presumed_type,
        city_id,
        status
      )
      values (
        '91000000-0000-4000-8000-000000000005',
        'Signalement auto traite',
        'lieu',
        'cotonou',
        'traite'
      )
    $sql$
  ),
  '42501',
  'a reporter cannot pre-process their own report'
);

select is(
  tests.affected_rows_as(
    'authenticated',
    '91000000-0000-4000-8000-000000000001',
    $sql$
      update public.missing_place_reports
      set
        status = 'en_revue',
        assigned_admin_id = '91000000-0000-4000-8000-000000000001'
      where reporter_id = '91000000-0000-4000-8000-000000000005'
    $sql$
  ),
  1::bigint,
  'an onboarded verified admin can process and assign a report'
);

select is(
  (
    select
      status::text
      || '|'
      || assigned_admin_id::text
    from public.missing_place_reports
    where reporter_id = '91000000-0000-4000-8000-000000000005'
  ),
  'en_revue|91000000-0000-4000-8000-000000000001',
  'the authorized missing-place moderation change is persisted'
);

select is(
  tests.affected_rows_as(
    'authenticated',
    '91000000-0000-4000-8000-000000000005',
    $sql$
      update public.missing_place_reports
      set status = 'traite'
      where reporter_id = '91000000-0000-4000-8000-000000000005'
    $sql$
  ),
  0::bigint,
  'a reporter cannot change report moderation state'
);

select is(
  tests.sqlstate_as(
    'authenticated',
    '91000000-0000-4000-8000-000000000002',
    $sql$
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
        city_id
      )
      values (
        '96000000-0000-4000-8000-000000000010',
        'lieu',
        'restaurant',
        'commercial',
        'commercial-restaurant',
        '91000000-0000-4000-8000-000000000002',
        '91000000-0000-4000-8000-000000000002',
        'brouillon',
        'Taxonomie incoherente promoteur',
        'taxonomie-incoherente-promoteur-sec',
        'Cette fiche doit etre rejetee.',
        'cotonou'
      )
    $sql$
  ),
  '23514',
  'a promoter cannot submit mismatched category taxonomy'
);

select is(
  tests.sqlstate_as(
    'authenticated',
    '91000000-0000-4000-8000-000000000001',
    $sql$
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
        city_id
      )
      values (
        '96000000-0000-4000-8000-000000000011',
        'lieu',
        'restaurant',
        'commercial',
        'commercial-restaurant',
        '91000000-0000-4000-8000-000000000001',
        '91000000-0000-4000-8000-000000000001',
        'brouillon',
        'Taxonomie incoherente admin',
        'taxonomie-incoherente-admin-sec',
        'Les administrateurs respectent aussi la taxonomie.',
        'cotonou'
      )
    $sql$
  ),
  '23514',
  'an admin cannot bypass category taxonomy'
);

select is(
  tests.sqlstate_as(
    'authenticated',
    '91000000-0000-4000-8000-000000000003',
    $sql$
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
        city_id
      )
      values (
        '96000000-0000-4000-8000-000000000012',
        'etablissement',
        'restaurant',
        'commercial',
        'commercial-restaurant',
        '91000000-0000-4000-8000-000000000003',
        '91000000-0000-4000-8000-000000000003',
        'brouillon',
        'Restaurant du guide refuse',
        'restaurant-guide-refuse-sec',
        'Un guide ne peut pas creer un restaurant.',
        'cotonou'
      )
    $sql$
  ),
  '42501',
  'a guide cannot submit a restaurant listing'
);

select ok(
  tests.statement_succeeds_as(
    'authenticated',
    '91000000-0000-4000-8000-000000000003',
    $sql$
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
        city_id
      )
      values (
        '96000000-0000-4000-8000-000000000002',
        'etablissement',
        'guide',
        'commercial',
        'guide-touristique',
        '91000000-0000-4000-8000-000000000003',
        '91000000-0000-4000-8000-000000000003',
        'brouillon',
        'Service de guide SEC',
        'service-guide-sec',
        'Service de guide autorise par la matrice produit.',
        'cotonou'
      )
    $sql$
  ),
  'a guide can submit a guide-service listing'
);

select tests.use_auth_context(
  'authenticated',
  '91000000-0000-4000-8000-000000000003'
);
select is(
  (
    select count(*)
    from public.listings
    where id = '96000000-0000-4000-8000-000000000002'
  ),
  1::bigint,
  'an authenticated listing manager can still read their own draft'
);
reset role;

select ok(
  tests.statement_succeeds_as(
    'authenticated',
    '91000000-0000-4000-8000-000000000003',
    $sql$
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
        city_id
      )
      values (
        '96000000-0000-4000-8000-000000000003',
        'evenement',
        'culture',
        'evenementiel',
        'event-culture',
        '91000000-0000-4000-8000-000000000003',
        '91000000-0000-4000-8000-000000000003',
        'brouillon',
        'Evenement du guide SEC',
        'evenement-guide-sec',
        'Evenement autorise pour un guide verifie.',
        'ouidah'
      )
    $sql$
  ),
  'a guide can submit an event listing'
);

select ok(
  tests.statement_succeeds_as(
    'authenticated',
    '91000000-0000-4000-8000-000000000002',
    $sql$
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
        city_id
      )
      values (
        '96000000-0000-4000-8000-000000000004',
        'etablissement',
        'restaurant',
        'commercial',
        'commercial-restaurant',
        '91000000-0000-4000-8000-000000000002',
        '91000000-0000-4000-8000-000000000002',
        'brouillon',
        'Restaurant promoteur SEC',
        'restaurant-promoteur-sec',
        'Restaurant autorise pour un promoteur verifie.',
        'cotonou'
      )
    $sql$
  ),
  'a promoter can submit a commercial restaurant'
);

select ok(
  tests.statement_succeeds_as(
    'authenticated',
    '91000000-0000-4000-8000-000000000004',
    $sql$
      insert into public.listings (
        id,
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
        city_id
      )
      values (
        '96000000-0000-4000-8000-000000000005',
        'lieu',
        'historique',
        'patrimonial',
        'heritage-historique',
        '91000000-0000-4000-8000-000000000004',
        '91000000-0000-4000-8000-000000000004',
        'brouillon',
        'Patrimoine institution SEC',
        'patrimoine-institution-sec',
        'Fiche patrimoniale autorisee pour une institution.',
        'ouidah'
      )
    $sql$
  ),
  'an institution can submit a heritage listing'
);

select is(
  tests.sqlstate_as(
    'authenticated',
    '91000000-0000-4000-8000-000000000004',
    $sql$
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
        city_id
      )
      values (
        '96000000-0000-4000-8000-000000000013',
        'etablissement',
        'restaurant',
        'commercial',
        'commercial-restaurant',
        '91000000-0000-4000-8000-000000000004',
        '91000000-0000-4000-8000-000000000004',
        'brouillon',
        'Restaurant institution refuse',
        'restaurant-institution-refuse-sec',
        'Une institution ne peut pas creer cette fiche commerciale.',
        'cotonou'
      )
    $sql$
  ),
  '42501',
  'an institution cannot submit a commercial restaurant'
);

select tests.use_auth_context(
  'authenticated',
  '91000000-0000-4000-8000-000000000003'
);
select is(
  public.current_user_can_manage_listing(
    '96000000-0000-4000-8000-000000000001'
  ),
  false,
  'a guide cannot manage a legacy restaurant they own'
);
reset role;

select is(
  tests.sqlstate_as(
    'authenticated',
    '91000000-0000-4000-8000-000000000003',
    $sql$
      insert into public.listing_media (
        listing_id,
        url,
        alt,
        display_order
      )
      values (
        '96000000-0000-4000-8000-000000000001',
        'https://media.kwabor.test/sec/guide-restaurant.jpg',
        'Media refuse sur le restaurant historique du guide',
        0
      )
    $sql$
  ),
  '42501',
  'a guide cannot manage media for a legacy restaurant'
);

select tests.use_auth_context(
  'authenticated',
  '91000000-0000-4000-8000-000000000003'
);
select is(
  public.current_user_can_manage_listing(
    '96000000-0000-4000-8000-000000000002'
  ),
  true,
  'a guide can manage the guide-service listing they own'
);
reset role;

select is(
  tests.sqlstate_as(
    'authenticated',
    '91000000-0000-4000-8000-000000000003',
    $sql$
      update public.listings
      set category_id = 'commercial-restaurant'
      where id = '96000000-0000-4000-8000-000000000002'
    $sql$
  ),
  '23514',
  'the taxonomy guard rejects an inconsistent listing update'
);

select is(
  (
    select constraint_record.convalidated
    from pg_catalog.pg_constraint constraint_record
    where constraint_record.conname = 'listings_category_taxonomy_fkey'
      and constraint_record.conrelid = 'public.listings'::regclass
  ),
  true,
  'the composite listing taxonomy foreign key is validated'
);

select is(
  (
    select count(*)
    from public.listings listing
    join public.categories category
      on category.id = listing.category_id
    where row(
      listing.type,
      listing.subtype,
      listing.listing_class
    ) is distinct from row(
      category.listing_type,
      category.subtype,
      category.default_listing_class
    )
  ),
  0::bigint,
  'all persisted listings remain consistent with category taxonomy'
);

select * from finish();
rollback;
