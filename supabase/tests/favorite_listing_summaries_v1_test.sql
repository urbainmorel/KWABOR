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
end;
$$;

select plan(82);

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
    'fa100000-0000-4000-8000-000000000001',
    'authenticated',
    'authenticated',
    'favorites-owner@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    'fa100000-0000-4000-8000-000000000002',
    'authenticated',
    'authenticated',
    'favorites-other@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    'fa100000-0000-4000-8000-000000000003',
    'authenticated',
    'authenticated',
    'favorites-incomplete@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    'fa100000-0000-4000-8000-000000000004',
    'authenticated',
    'authenticated',
    'favorites-deletion@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    'fa100000-0000-4000-8000-000000000005',
    'authenticated',
    'authenticated',
    'favorites-cascade@kwabor.test',
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
  (
    'fa100000-0000-4000-8000-000000000001',
    'Favoris',
    'Proprietaire',
    'cotonou',
    now()
  ),
  (
    'fa100000-0000-4000-8000-000000000002',
    'Favoris',
    'Autre',
    'ouidah',
    now()
  ),
  (
    'fa100000-0000-4000-8000-000000000003',
    'Favoris',
    'Incomplet',
    'cotonou',
    null
  ),
  (
    'fa100000-0000-4000-8000-000000000004',
    'Favoris',
    'Suppression',
    'cotonou',
    now()
  ),
  (
    'fa100000-0000-4000-8000-000000000005',
    'Favoris',
    'Cascade',
    'cotonou',
    now()
  );

insert into public.favorites (user_id, listing_id, created_at)
values
  (
    'fa100000-0000-4000-8000-000000000001',
    '00000000-0000-4000-8000-000000000101',
    '2026-08-01 01:00:00+00'
  ),
  (
    'fa100000-0000-4000-8000-000000000001',
    '00000000-0000-4000-8000-000000000102',
    '2026-08-01 02:00:00+00'
  ),
  (
    'fa100000-0000-4000-8000-000000000001',
    '00000000-0000-4000-8000-000000000103',
    '2026-08-01 03:00:00+00'
  ),
  (
    'fa100000-0000-4000-8000-000000000001',
    '00000000-0000-4000-8000-000000000104',
    '2026-08-01 04:00:00+00'
  ),
  (
    'fa100000-0000-4000-8000-000000000002',
    '00000000-0000-4000-8000-000000000101',
    '2026-08-01 05:00:00+00'
  ),
  (
    'fa100000-0000-4000-8000-000000000004',
    '00000000-0000-4000-8000-000000000101',
    '2026-08-01 06:00:00+00'
  ),
  (
    'fa100000-0000-4000-8000-000000000005',
    '00000000-0000-4000-8000-000000000101',
    '2026-08-01 07:00:00+00'
  );

insert into public.likes (user_id, listing_id, created_at)
values (
  'fa100000-0000-4000-8000-000000000001',
  '00000000-0000-4000-8000-000000000101',
  '2026-08-01 01:30:00+00'
);

update public.event_details
set start_at = '2000-01-01 18:00:00+01',
    end_at = '2000-01-01 22:00:00+01'
where listing_id = '00000000-0000-4000-8000-000000000104';

update public.listings
set sponsored_until = '2090-01-01 00:00:00+00'
where id = '00000000-0000-4000-8000-000000000101';

select ok(
  exists (
    select 1
    from pg_catalog.pg_constraint as constraint_definition
    where constraint_definition.conrelid = 'public.favorites'::regclass
      and constraint_definition.conname = 'favorites_created_at_finite'
      and constraint_definition.convalidated
  ),
  'favorite timestamps have a validated finite-time constraint'
);

select ok(
  to_regclass('public.favorites_owner_recent_idx') is not null,
  'the owner recency index exists'
);

select ok(
  pg_catalog.pg_get_indexdef('public.favorites_owner_recent_idx'::regclass)
    like '%(user_id, created_at DESC, listing_id DESC)%',
  'the recency index matches the keyset order'
);

select ok(
  (
    select
      guard_definition.prosecdef
      and guard_definition.provolatile = 'v'::"char"
      and guard_definition.proconfig = array['search_path=""']::text[]
    from pg_catalog.pg_proc as guard_definition
    where guard_definition.oid =
      'app_private.favorite_owner_write_allowed_v1(uuid)'::regprocedure
  ),
  'the private direct-write guard is volatile, definer-owned, and search-path safe'
);

select ok(
  has_function_privilege(
    'authenticated',
    'app_private.favorite_owner_write_allowed_v1(uuid)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'anon',
    'app_private.favorite_owner_write_allowed_v1(uuid)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'service_role',
    'app_private.favorite_owner_write_allowed_v1(uuid)',
    'EXECUTE'
  ),
  'only authenticated legacy table writes can invoke the private guard'
);

select ok(
  to_regprocedure(
    'public.list_favorite_listing_summaries_v1(text,text,integer)'
  ) is not null,
  'the versioned owner read model exists'
);

select ok(
  to_regprocedure('public.set_listing_favorite_v1(uuid,boolean)') is not null,
  'the versioned idempotent setter exists'
);

select ok(
  not (
    select procedure_definition.prosecdef
    from pg_catalog.pg_proc as procedure_definition
    where procedure_definition.oid =
      'public.list_favorite_listing_summaries_v1(text,text,integer)'::regprocedure
  ),
  'the favorites read model is security invoker'
);

select ok(
  not (
    select procedure_definition.prosecdef
    from pg_catalog.pg_proc as procedure_definition
    where procedure_definition.oid =
      'public.set_listing_favorite_v1(uuid,boolean)'::regprocedure
  ),
  'the favorites setter is security invoker'
);

select is(
  (
    select procedure_definition.provolatile
    from pg_catalog.pg_proc as procedure_definition
    where procedure_definition.oid =
      'public.list_favorite_listing_summaries_v1(text,text,integer)'::regprocedure
  ),
  'v'::"char",
  'the read model is volatile because it acquires an account lock'
);

select is(
  (
    select procedure_definition.provolatile
    from pg_catalog.pg_proc as procedure_definition
    where procedure_definition.oid =
      'public.set_listing_favorite_v1(uuid,boolean)'::regprocedure
  ),
  'v'::"char",
  'the setter is volatile because it mutates and acquires an account lock'
);

select is(
  (
    select procedure_definition.proconfig
    from pg_catalog.pg_proc as procedure_definition
    where procedure_definition.oid =
      'public.list_favorite_listing_summaries_v1(text,text,integer)'::regprocedure
  ),
  array['search_path=""']::text[],
  'the read model has an empty search path'
);

select is(
  (
    select procedure_definition.proconfig
    from pg_catalog.pg_proc as procedure_definition
    where procedure_definition.oid =
      'public.set_listing_favorite_v1(uuid,boolean)'::regprocedure
  ),
  array['search_path=""']::text[],
  'the setter has an empty search path'
);

select ok(
  position(
    'p_cursor is null' in pg_catalog.lower(
      pg_catalog.pg_get_functiondef(
        'public.list_favorite_listing_summaries_v1(text,text,integer)'::regprocedure
      )
    )
  ) = 0
  and position(
    '(favorite.created_at, favorite.listing_id)' in
      pg_catalog.pg_get_functiondef(
        'public.list_favorite_listing_summaries_v1(text,text,integer)'::regprocedure
      )
  ) > 0,
  'the keyset bound stays indexable in a generic plan without a cursor-null OR'
);

select ok(
  has_function_privilege(
    'authenticated',
    'public.list_favorite_listing_summaries_v1(text,text,integer)',
    'EXECUTE'
  ),
  'authenticated can execute the owner read model'
);

select ok(
  not has_function_privilege(
    'anon',
    'public.list_favorite_listing_summaries_v1(text,text,integer)',
    'EXECUTE'
  ),
  'anonymous callers cannot execute the owner read model'
);

select ok(
  not has_function_privilege(
    'service_role',
    'public.list_favorite_listing_summaries_v1(text,text,integer)',
    'EXECUTE'
  ),
  'service role has no unnecessary read-model execute grant'
);

select ok(
  has_function_privilege(
    'authenticated',
    'public.set_listing_favorite_v1(uuid,boolean)',
    'EXECUTE'
  ),
  'authenticated can execute the setter'
);

select ok(
  has_function_privilege(
    'service_role',
    'public.set_listing_favorite_v1(uuid,boolean)',
    'EXECUTE'
  )
  and has_function_privilege(
    'service_role',
    'app_private.require_completed_onboarding()',
    'EXECUTE'
  ),
  'the preserved service-role legacy RPC path can reach its V1 dependency'
);

select ok(
  not has_function_privilege(
    'anon',
    'public.set_listing_favorite_v1(uuid,boolean)',
    'EXECUTE'
  ),
  'anonymous callers cannot execute the setter'
);

select ok(
  has_function_privilege(
    'authenticated',
    'public.add_listing_to_favorites(uuid)',
    'EXECUTE'
  ),
  'the legacy add RPC remains executable by released clients'
);

select ok(
  has_function_privilege(
    'authenticated',
    'public.remove_listing_from_favorites(uuid)',
    'EXECUTE'
  ),
  'the legacy remove RPC remains executable by released clients'
);

select ok(
  position(
    'DEPRECATED' in coalesce(
      obj_description('public.add_listing_to_favorites(uuid)'::regprocedure),
      ''
    )
  ) > 0
  and position(
    'DEPRECATED' in coalesce(
      obj_description('public.remove_listing_from_favorites(uuid)'::regprocedure),
      ''
    )
  ) > 0,
  'both compatibility wrappers are explicitly deprecated'
);

select ok(
  exists (
    select 1
    from pg_catalog.pg_policy as policy_definition
    where policy_definition.polrelid = 'public.favorites'::regclass
      and policy_definition.polname = 'users read their favorites'
  )
  and exists (
    select 1
    from pg_catalog.pg_policy as policy_definition
    where policy_definition.polrelid = 'public.favorites'::regclass
      and policy_definition.polname = 'users favorite published listings'
  )
  and exists (
    select 1
    from pg_catalog.pg_policy as policy_definition
    where policy_definition.polrelid = 'public.favorites'::regclass
      and policy_definition.polname = 'users delete their favorites'
  ),
  'legacy owner RLS policies remain present during the compatibility window'
);

select ok(
  (
    select position(
      'favorite_owner_write_allowed_v1' in
        pg_catalog.pg_get_expr(
          policy_definition.polwithcheck,
          policy_definition.polrelid
        )
    ) > 0
    from pg_catalog.pg_policy as policy_definition
    where policy_definition.polrelid = 'public.favorites'::regclass
      and policy_definition.polname = 'users favorite published listings'
  )
  and (
    select position(
      'favorite_owner_write_allowed_v1' in
        pg_catalog.pg_get_expr(
          policy_definition.polqual,
          policy_definition.polrelid
        )
    ) > 0
    from pg_catalog.pg_policy as policy_definition
    where policy_definition.polrelid = 'public.favorites'::regclass
      and policy_definition.polname = 'users delete their favorites'
  ),
  'both legacy write policies serialize with account deletion'
);

select ok(
  has_table_privilege('authenticated', 'public.favorites', 'SELECT')
  and has_table_privilege('authenticated', 'public.favorites', 'DELETE')
  and has_column_privilege('authenticated', 'public.favorites', 'user_id', 'INSERT')
  and has_column_privilege('authenticated', 'public.favorites', 'listing_id', 'INSERT')
  and not has_column_privilege('authenticated', 'public.favorites', 'created_at', 'INSERT'),
  'legacy writes retain relation access while the server owns favorite timestamps'
);

select tests.use_auth_context(
  'authenticated',
  'fa100000-0000-4000-8000-000000000002'
);
select throws_ok(
  $sql$
    insert into public.favorites (user_id, listing_id, created_at)
    values (
      'fa100000-0000-4000-8000-000000000002',
      '00000000-0000-4000-8000-000000000102',
      '9999-12-31 23:59:59+00'
    )
  $sql$,
  '42501',
  null,
  'a legacy client cannot forge a future favorite timestamp'
);
select lives_ok(
  $sql$
    insert into public.favorites (user_id, listing_id)
    values (
      'fa100000-0000-4000-8000-000000000002',
      '00000000-0000-4000-8000-000000000102'
    )
  $sql$,
  'a released direct-table client can still create a relation with server time'
);
select ok(
  (
    select created_at = pg_catalog.transaction_timestamp()
    from public.favorites
    where user_id = 'fa100000-0000-4000-8000-000000000002'
      and listing_id = '00000000-0000-4000-8000-000000000102'
  ),
  'the compatibility insert receives the authoritative server timestamp'
);
delete from public.favorites
where user_id = 'fa100000-0000-4000-8000-000000000002'
  and listing_id = '00000000-0000-4000-8000-000000000102';
reset role;

select throws_ok(
  $sql$
    insert into public.favorites (user_id, listing_id, created_at)
    values (
      'fa100000-0000-4000-8000-000000000005',
      '00000000-0000-4000-8000-000000000102',
      'infinity'::timestamptz
    )
  $sql$,
  '23514',
  null,
  'positive infinity cannot enter a favorite cursor key'
);

select throws_ok(
  $sql$
    insert into public.favorites (user_id, listing_id, created_at)
    values (
      'fa100000-0000-4000-8000-000000000005',
      '00000000-0000-4000-8000-000000000102',
      '-infinity'::timestamptz
    )
  $sql$,
  '23514',
  null,
  'negative infinity cannot enter a favorite cursor key'
);

select throws_ok(
  $sql$
    insert into public.favorites (user_id, listing_id, created_at)
    values (
      'fa100000-0000-4000-8000-000000000005',
      '00000000-0000-4000-8000-000000000102',
      '0001-01-01 00:00:00+00 BC'::timestamptz
    )
  $sql$,
  '23514',
  null,
  'a pre-common-era timestamp cannot produce an unusable cursor'
);

select throws_ok(
  $sql$
    insert into public.favorites (user_id, listing_id, created_at)
    values (
      'fa100000-0000-4000-8000-000000000005',
      '00000000-0000-4000-8000-000000000102',
      '10000-01-01 00:00:00+00'::timestamptz
    )
  $sql$,
  '23514',
  null,
  'a five-digit year cannot produce an unusable cursor'
);

select tests.use_auth_context(
  'service_role',
  'fa100000-0000-4000-8000-000000000002'
);
select lives_ok(
  $sql$
    select *
    from public.remove_listing_from_favorites(
      '00000000-0000-4000-8000-000000000101'
    )
  $sql$,
  'the preserved service-role grant can invoke legacy remove'
);
select lives_ok(
  $sql$
    select *
    from public.add_listing_to_favorites(
      '00000000-0000-4000-8000-000000000101'
    )
  $sql$,
  'the preserved service-role grant can invoke legacy add'
);
reset role;

select tests.use_auth_context(
  'authenticated',
  'fa100000-0000-4000-8000-000000000003'
);
select throws_ok(
  $sql$
    select *
    from public.set_listing_favorite_v1(
      '00000000-0000-4000-8000-000000000101',
      true
    )
  $sql$,
  '42501',
  'Onboarding completion required',
  'an incomplete account cannot mutate favorites'
);
select throws_ok(
  $sql$select * from public.list_favorite_listing_summaries_v1()$sql$,
  '42501',
  'Onboarding completion required',
  'an incomplete account cannot read the private snapshot'
);
reset role;

select tests.use_auth_context(
  'authenticated',
  'fa100000-0000-4000-8000-000000000001'
);
select is(
  (
    select favorited_at
    from public.set_listing_favorite_v1(
      '00000000-0000-4000-8000-000000000101',
      true
    )
  ),
  '2026-08-01 01:00:00+00'::timestamptz,
  'idempotent add preserves the first favorited timestamp'
);
select is(
  (
    select count(*)
    from public.favorites
    where listing_id = '00000000-0000-4000-8000-000000000101'
  ),
  1::bigint,
  'idempotent add keeps one owner relation'
);
select throws_ok(
  $sql$
    select *
    from public.set_listing_favorite_v1(
      '00000000-0000-4000-8000-000000000101',
      null
    )
  $sql$,
  '22023',
  'Favorite mutation parameters are invalid',
  'a null desired state is rejected'
);
select ok(
  not (
    select favorited_by_current_user
    from public.set_listing_favorite_v1(
      'fa1f0000-0000-4000-8000-000000000001',
      false
    )
  ),
  'removing an unknown listing identifier is idempotently absent'
);
select ok(
  not (
    select favorited_by_current_user
    from public.set_listing_favorite_v1(
      'fa1f0000-0000-4000-8000-000000000001',
      false
    )
  ),
  'repeating an absent removal remains successful'
);
reset role;

update public.listings
set status = 'archive'
where id = '00000000-0000-4000-8000-000000000103';

select tests.use_auth_context(
  'authenticated',
  'fa100000-0000-4000-8000-000000000001'
);
select is(
  (
    select count(*)
    from public.list_favorite_listing_summaries_v1()
    where id = '00000000-0000-4000-8000-000000000103'
  ),
  0::bigint,
  'an archived listing is hidden from the active favorites read model'
);
select is(
  (
    select favorited_at
    from public.set_listing_favorite_v1(
      '00000000-0000-4000-8000-000000000103',
      true
    )
  ),
  '2026-08-01 03:00:00+00'::timestamptz,
  'a retry of an existing favorite stays idempotent after depublication'
);
create temporary table legacy_hidden_add_retry_result as
select *
from public.add_listing_to_favorites(
  '00000000-0000-4000-8000-000000000103'
);
reset role;

select is(
  (select count(*) from legacy_hidden_add_retry_result),
  1::bigint,
  'legacy add returns one compatibility row on a hidden retry'
);

select ok(
  (select favorited_by_current_user from legacy_hidden_add_retry_result)
  and (select likes_count from legacy_hidden_add_retry_result) = 0,
  'legacy hidden add preserves the authoritative favorite without leaking metrics'
);

select ok(
  exists (
    select 1
    from public.favorites
    where user_id = 'fa100000-0000-4000-8000-000000000001'
      and listing_id = '00000000-0000-4000-8000-000000000103'
  ),
  'archiving preserves the private relation'
);

update public.listings
set status = 'publie'
where id = '00000000-0000-4000-8000-000000000103';

select tests.use_auth_context(
  'authenticated',
  'fa100000-0000-4000-8000-000000000001'
);
select is(
  (
    select favorited_at
    from public.list_favorite_listing_summaries_v1()
    where id = '00000000-0000-4000-8000-000000000103'
  ),
  '2026-08-01 03:00:00+00'::timestamptz,
  'republication restores the favorite at its original position'
);
reset role;

update public.listings
set status = 'archive'
where id = '00000000-0000-4000-8000-000000000102';

select tests.use_auth_context(
  'authenticated',
  'fa100000-0000-4000-8000-000000000001'
);
create temporary table legacy_hidden_remove_result as
select *
from public.remove_listing_from_favorites(
  '00000000-0000-4000-8000-000000000102'
);
reset role;

select is(
  (select count(*) from legacy_hidden_remove_result),
  1::bigint,
  'legacy remove returns one compatibility row for a hidden listing'
);

select ok(
  not (select favorited_by_current_user from legacy_hidden_remove_result),
  'legacy remove reports the authoritative absent state for a hidden listing'
);

select ok(
  not exists (
    select 1
    from public.favorites
    where user_id = 'fa100000-0000-4000-8000-000000000001'
      and listing_id = '00000000-0000-4000-8000-000000000102'
  ),
  'legacy remove actually deletes the hidden relation'
);

select tests.use_auth_context(
  'authenticated',
  'fa100000-0000-4000-8000-000000000001'
);
create temporary table legacy_hidden_remove_retry_result as
select *
from public.remove_listing_from_favorites(
  '00000000-0000-4000-8000-000000000102'
);
select throws_ok(
  $sql$
    select *
    from public.set_listing_favorite_v1(
      '00000000-0000-4000-8000-000000000102',
      true
    )
  $sql$,
  'P0002',
  'listing not found',
  'adding a hidden listing remains forbidden'
);
reset role;

select is(
  (select count(*) from legacy_hidden_remove_retry_result),
  1::bigint,
  'legacy hidden removal is idempotent on retry'
);

update public.listings
set status = 'publie'
where id = '00000000-0000-4000-8000-000000000102';

select tests.use_auth_context(
  'authenticated',
  'fa100000-0000-4000-8000-000000000001'
);
select lives_ok(
  $sql$
    select *
    from public.add_listing_to_favorites(
      '00000000-0000-4000-8000-000000000102'
    )
  $sql$,
  'the released legacy add wrapper remains functional'
);
reset role;

select is(
  (
    select count(*)
    from public.favorites
    where user_id = 'fa100000-0000-4000-8000-000000000001'
  ),
  4::bigint,
  'the owner has four active favorite relations after compatibility checks'
);

select tests.use_auth_context(
  'authenticated',
  'fa100000-0000-4000-8000-000000000001'
);
select ok(
  (
    select to_jsonb(summary) ?& array[
      'id',
      'type',
      'listing_class',
      'status',
      'name',
      'city_id',
      'city_name',
      'category_id',
      'cover_image_url',
      'cover_image_alt',
      'price_from_xof',
      'rating_avg',
      'likes_count',
      'verified',
      'liked_by_current_user',
      'favorited_by_current_user',
      'favorited_at',
      'event_start_at',
      'event_end_at',
      'is_event_ended',
      'is_sponsored_placement',
      'row_cursor'
    ]
    from public.list_favorite_listing_summaries_v1() as summary
    limit 1
  ),
  'the read model exposes the complete bounded card projection'
);

select is(
  (
    select city_name || '|' || cover_image_alt
    from public.list_favorite_listing_summaries_v1()
    where id = '00000000-0000-4000-8000-000000000101'
  ),
  'Ouidah|Porte du Non-Retour a Ouidah',
  'city and deterministic official cover metadata are projected'
);

select ok(
  (
    select liked_by_current_user and favorited_by_current_user
    from public.list_favorite_listing_summaries_v1()
    where id = '00000000-0000-4000-8000-000000000101'
  ),
  'viewer interaction flags remain distinct and correct'
);

select is(
  (select count(*) from public.list_favorite_listing_summaries_v1()),
  4::bigint,
  'the owner read model exposes all four currently published favorites'
);

select is(
  (
    select count(*)
    from public.list_favorite_listing_summaries_v1(
      p_listing_type => 'evenement'
    )
  ),
  1::bigint,
  'listing type filters the private snapshot'
);

select ok(
  (
    select is_event_ended
    from public.list_favorite_listing_summaries_v1(
      p_listing_type => 'evenement'
    )
  ),
  'a published ended event remains visible with its derived badge state'
);

select ok(
  (
    select event_start_at is not null and event_end_at is not null
    from public.list_favorite_listing_summaries_v1(
      p_listing_type => 'evenement'
    )
  ),
  'event timing needed by the native badge is projected'
);

select ok(
  not exists (
    select 1
    from public.list_favorite_listing_summaries_v1()
    where is_sponsored_placement
  ),
  'private favorites never expose a sponsored placement'
);

select is(
  (
    select id
    from public.list_favorite_listing_summaries_v1()
    with ordinality as summary
    order by summary.ordinality
    limit 1
  ),
  '00000000-0000-4000-8000-000000000102'::uuid,
  'the newest favorite sorts first even when the oldest listing is sponsored'
);
reset role;

select tests.use_auth_context(
  'authenticated',
  'fa100000-0000-4000-8000-000000000002'
);
select is(
  (select count(*) from public.list_favorite_listing_summaries_v1()),
  1::bigint,
  'RLS and the explicit owner predicate isolate another account'
);
reset role;

select tests.use_auth_context(
  'authenticated',
  'fa100000-0000-4000-8000-000000000001'
);
create temporary table favorites_page_one as
select summary.*
from public.list_favorite_listing_summaries_v1(p_limit => 2)
  with ordinality as summary;

create temporary table favorites_page_two as
select summary.*
from public.list_favorite_listing_summaries_v1(
  p_cursor => (
    select row_cursor
    from favorites_page_one
    where ordinality = 2
  ),
  p_limit => 2
) with ordinality as summary;
reset role;

select is(
  (select count(*) from favorites_page_one),
  3::bigint,
  'the first page returns limit plus one sentinel row'
);

select is(
  (select count(*) from favorites_page_two),
  2::bigint,
  'the terminal page returns only the remaining rows'
);

select is(
  (
    select count(*)
    from (
      select id from favorites_page_one where ordinality <= 2
      union all
      select id from favorites_page_two
    ) as paged
  ),
  4::bigint,
  'client-kept pages contain every active favorite'
);

select is(
  (
    select count(distinct id)
    from (
      select id from favorites_page_one where ordinality <= 2
      union all
      select id from favorites_page_two
    ) as paged
  ),
  4::bigint,
  'keyset pages contain no overlap'
);

select tests.use_auth_context(
  'authenticated',
  'fa100000-0000-4000-8000-000000000001'
);
select throws_ok(
  format(
    'select * from public.list_favorite_listing_summaries_v1(p_listing_type => ''lieu'', p_cursor => %L)',
    (select row_cursor from favorites_page_one where ordinality = 2)
  ),
  '22023',
  'p_cursor does not match favorite filters or owner',
  'a cursor cannot be reused with another type filter'
);
select throws_ok(
  $sql$select * from public.list_favorite_listing_summaries_v1(p_cursor => '%%%')$sql$,
  '22023',
  'p_cursor is malformed',
  'a malformed cursor is rejected'
);
select throws_ok(
  format(
    'select * from public.list_favorite_listing_summaries_v1(p_cursor => %L)',
    repeat('A', 4097)
  ),
  '22023',
  'p_cursor is invalid',
  'an overlong cursor is rejected before decoding'
);
select throws_ok(
  $sql$select * from public.list_favorite_listing_summaries_v1(p_limit => 0)$sql$,
  '22023',
  'p_limit must be between 1 and 50',
  'a zero page limit is rejected'
);
select throws_ok(
  $sql$
    select *
    from public.list_favorite_listing_summaries_v1(
      p_listing_type => 'inconnu'
    )
  $sql$,
  '22023',
  'p_listing_type is invalid',
  'an unknown listing type is rejected'
);
reset role;

select tests.use_auth_context(
  'authenticated',
  'fa100000-0000-4000-8000-000000000002'
);
select throws_ok(
  format(
    'select * from public.list_favorite_listing_summaries_v1(p_cursor => %L)',
    (select row_cursor from favorites_page_one where ordinality = 2)
  ),
  '22023',
  'p_cursor does not match favorite filters or owner',
  'a cursor is bound to its account owner'
);
reset role;

create temporary table favorite_future_cursor as
select pg_catalog.replace(
  pg_catalog.replace(
    pg_catalog.encode(
      pg_catalog.convert_to(
        pg_catalog.jsonb_set(
          pg_catalog.convert_from(
            pg_catalog.decode(row_cursor, 'base64'),
            'UTF8'
          )::jsonb,
          '{as_of}',
          pg_catalog.to_jsonb(pg_catalog.statement_timestamp() + interval '1 day')
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
) as cursor_value
from favorites_page_one
where ordinality = 2;

grant select on table favorite_future_cursor to authenticated;

select tests.use_auth_context(
  'authenticated',
  'fa100000-0000-4000-8000-000000000001'
);
select throws_ok(
  format(
    'select * from public.list_favorite_listing_summaries_v1(p_cursor => %L)',
    (select cursor_value from favorite_future_cursor)
  ),
  '22023',
  'p_cursor fields are invalid',
  'a forged future snapshot is rejected'
);
reset role;

set local role service_role;
select is(
  (
    select status
    from public.prepare_account_deletion(
      'fa100000-0000-4000-8000-000000000004',
      'fa1d0000-0000-4000-8000-000000000004'
    )
  ),
  'prepared',
  'account deletion preparation accepts the favorites fixture'
);
reset role;

select ok(
  not exists (
    select 1
    from public.favorites
    where user_id = 'fa100000-0000-4000-8000-000000000004'
  ),
  'account deletion preparation purges favorites'
);

select tests.use_auth_context(
  'authenticated',
  'fa100000-0000-4000-8000-000000000004'
);
select throws_ok(
  $sql$
    select *
    from public.set_listing_favorite_v1(
      '00000000-0000-4000-8000-000000000101',
      true
    )
  $sql$,
  '42501',
  'Onboarding completion required',
  'the deletion tombstone fences later favorite mutations'
);
select throws_ok(
  $sql$select * from public.list_favorite_listing_summaries_v1()$sql$,
  '42501',
  'Onboarding completion required',
  'the deletion tombstone fences later favorite reads'
);
reset role;

delete from auth.users
where id = 'fa100000-0000-4000-8000-000000000005';

select ok(
  not exists (
    select 1
    from public.favorites
    where user_id = 'fa100000-0000-4000-8000-000000000005'
  ),
  'final Auth deletion cascades to favorites'
);

select ok(
  position(
    'delete from public.favorites' in pg_catalog.lower(
      pg_catalog.pg_get_functiondef(
        'app_private.prepare_account_data_for_deletion(uuid)'::regprocedure
      )
    )
  ) > 0
  and position(
    'delete from public.favorites' in pg_catalog.lower(
      pg_catalog.pg_get_functiondef(
        'app_private.cleanup_account_data(uuid)'::regprocedure
      )
    )
  ) > 0,
  'both replayable account cleanup phases retain favorite purging'
);

select * from finish();
rollback;
