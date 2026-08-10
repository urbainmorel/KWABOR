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
    '1a1e0000-0000-4000-8000-000000000001',
    'authenticated',
    'authenticated',
    'like-v1-owner@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    '1a1e0000-0000-4000-8000-000000000002',
    'authenticated',
    'authenticated',
    'like-v1-other@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    '1a1e0000-0000-4000-8000-000000000003',
    'authenticated',
    'authenticated',
    'like-v1-incomplete@kwabor.test',
    '',
    now(),
    now(),
    now()
  ),
  (
    '1a1e0000-0000-4000-8000-000000000004',
    'authenticated',
    'authenticated',
    'like-v1-deletion@kwabor.test',
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
    '1a1e0000-0000-4000-8000-000000000001',
    'Like',
    'Proprietaire',
    'cotonou',
    now()
  ),
  (
    '1a1e0000-0000-4000-8000-000000000002',
    'Like',
    'Autre',
    'ouidah',
    now()
  ),
  (
    '1a1e0000-0000-4000-8000-000000000003',
    'Like',
    'Incomplet',
    'cotonou',
    null
  ),
  (
    '1a1e0000-0000-4000-8000-000000000004',
    'Like',
    'Suppression',
    'cotonou',
    now()
  );

create temporary table like_v1_baseline (
  listing_id uuid primary key,
  likes_count integer not null
) on commit drop;

insert into like_v1_baseline (listing_id, likes_count)
select listing.id, listing.likes_count
from public.listings as listing
where listing.id in (
  '00000000-0000-4000-8000-000000000101',
  '00000000-0000-4000-8000-000000000102'
);

grant select on table like_v1_baseline to authenticated;

select ok(
  to_regprocedure('public.set_listing_like_v1(uuid,boolean)') is not null,
  'the versioned target-state Like setter exists'
);

select ok(
  not (
    select procedure_definition.prosecdef
    from pg_catalog.pg_proc as procedure_definition
    where procedure_definition.oid =
      'public.set_listing_like_v1(uuid,boolean)'::regprocedure
  ),
  'the Like setter is security invoker'
);

select is(
  (
    select procedure_definition.provolatile
    from pg_catalog.pg_proc as procedure_definition
    where procedure_definition.oid =
      'public.set_listing_like_v1(uuid,boolean)'::regprocedure
  ),
  'v'::"char",
  'the Like setter is volatile because it mutates and locks'
);

select is(
  (
    select procedure_definition.proconfig
    from pg_catalog.pg_proc as procedure_definition
    where procedure_definition.oid =
      'public.set_listing_like_v1(uuid,boolean)'::regprocedure
  ),
  array['search_path=""']::text[],
  'the Like setter has an empty search path'
);

select is(
  (
    select procedure_definition.proargnames
    from pg_catalog.pg_proc as procedure_definition
    where procedure_definition.oid =
      'public.set_listing_like_v1(uuid,boolean)'::regprocedure
  ),
  array[
    'p_listing_id',
    'p_liked',
    'listing_id',
    'liked',
    'likes_count',
    'mutated_at'
  ]::text[],
  'the Like setter exposes only the strict input and output field names'
);

select is(
  pg_catalog.pg_get_function_result(
    'public.set_listing_like_v1(uuid,boolean)'::regprocedure
  ),
  'TABLE(listing_id uuid, liked boolean, likes_count integer, mutated_at timestamp with time zone)',
  'the Like setter exposes the strict result types'
);

select ok(
  position(
    'pg_advisory_xact_lock' in pg_catalog.pg_get_functiondef(
      'public.set_listing_like_v1(uuid,boolean)'::regprocedure
    )
  ) > 0
  and position(
    'pg_advisory_xact_lock' in pg_catalog.pg_get_functiondef(
      'public.set_listing_like_v1(uuid,boolean)'::regprocedure
    )
  ) < position(
    'require_completed_onboarding' in pg_catalog.pg_get_functiondef(
      'public.set_listing_like_v1(uuid,boolean)'::regprocedure
    )
  ),
  'the account lock is acquired before onboarding and tombstone revalidation'
);

select ok(
  position(
    'hashtextextended(current_user_id::text, 0)' in
      pg_catalog.pg_get_functiondef(
        'public.set_listing_like_v1(uuid,boolean)'::regprocedure
      )
  ) > 0
  and position(
    'hashtextextended(p_user_id::text, 0)' in
      pg_catalog.pg_get_functiondef(
        'public.prepare_account_deletion(uuid,uuid)'::regprocedure
      )
  ) > 0,
  'Like mutation and account deletion share the same per-account lock namespace'
);

select ok(
  has_function_privilege(
    'authenticated',
    'public.set_listing_like_v1(uuid,boolean)',
    'EXECUTE'
  ),
  'authenticated can execute the Like setter'
);

select ok(
  not has_function_privilege(
    'anon',
    'public.set_listing_like_v1(uuid,boolean)',
    'EXECUTE'
  ),
  'anonymous callers cannot execute the Like setter'
);

select ok(
  not has_function_privilege(
    'service_role',
    'public.set_listing_like_v1(uuid,boolean)',
    'EXECUTE'
  ),
  'service role has no unnecessary Like-setter grant'
);

select ok(
  not exists (
    select 1
    from pg_catalog.pg_proc as procedure_definition
    cross join lateral pg_catalog.aclexplode(
      coalesce(
        procedure_definition.proacl,
        pg_catalog.acldefault('f', procedure_definition.proowner)
      )
    ) as privilege_definition
    where procedure_definition.oid =
      'public.set_listing_like_v1(uuid,boolean)'::regprocedure
      and privilege_definition.grantee = 0
      and privilege_definition.privilege_type = 'EXECUTE'
  ),
  'PUBLIC has no implicit execute path to the Like setter'
);

select ok(
  not exists (
    select 1
    from pg_catalog.pg_proc as procedure_definition
    cross join lateral pg_catalog.aclexplode(
      coalesce(
        procedure_definition.proacl,
        pg_catalog.acldefault('f', procedure_definition.proowner)
      )
    ) as privilege_definition
    join pg_catalog.pg_roles as grantee
      on grantee.oid = privilege_definition.grantee
    join pg_catalog.pg_roles as procedure_owner
      on procedure_owner.oid = procedure_definition.proowner
    where procedure_definition.oid =
      'public.set_listing_like_v1(uuid,boolean)'::regprocedure
      and privilege_definition.privilege_type = 'EXECUTE'
      and grantee.rolname <> procedure_owner.rolname
      and grantee.rolname <> 'authenticated'
  ),
  'authenticated is the only non-owner role with a direct execute grant'
);

select ok(
  (
    select
      guard_definition.prosecdef
      and guard_definition.provolatile = 'v'::"char"
      and guard_definition.proconfig = array['search_path=""']::text[]
    from pg_catalog.pg_proc as guard_definition
    where guard_definition.oid =
      'app_private.like_owner_write_allowed_v1(uuid)'::regprocedure
  ),
  'the direct Like write guard is volatile, definer-owned, and search-path safe'
);

select ok(
  position(
    'pg_advisory_xact_lock' in pg_catalog.pg_get_functiondef(
      'app_private.like_owner_write_allowed_v1(uuid)'::regprocedure
    )
  ) > 0
  and position(
    'pg_advisory_xact_lock' in pg_catalog.pg_get_functiondef(
      'app_private.like_owner_write_allowed_v1(uuid)'::regprocedure
    )
  ) < position(
    'from public.profiles' in pg_catalog.pg_get_functiondef(
      'app_private.like_owner_write_allowed_v1(uuid)'::regprocedure
    )
  )
  and position(
    'hashtextextended(current_user_id::text, 0)' in
      pg_catalog.pg_get_functiondef(
        'app_private.like_owner_write_allowed_v1(uuid)'::regprocedure
      )
  ) > 0,
  'the direct-write guard locks the deletion namespace before fresh tombstone checks'
);

select ok(
  has_function_privilege(
    'authenticated',
    'app_private.like_owner_write_allowed_v1(uuid)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'anon',
    'app_private.like_owner_write_allowed_v1(uuid)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'service_role',
    'app_private.like_owner_write_allowed_v1(uuid)',
    'EXECUTE'
  ),
  'only authenticated compatibility writes can invoke the private Like guard'
);

select ok(
  (
    select position(
      'like_owner_write_allowed_v1' in pg_catalog.pg_get_expr(
        policy_definition.polwithcheck,
        policy_definition.polrelid
      )
    ) > 0
    and position(
      'published_at' in pg_catalog.pg_get_expr(
        policy_definition.polwithcheck,
        policy_definition.polrelid
      )
    ) > 0
    from pg_catalog.pg_policy as policy_definition
    where policy_definition.polrelid = 'public.likes'::regclass
      and policy_definition.polname = 'users like published listings'
  )
  and (
    select position(
      'like_owner_write_allowed_v1' in pg_catalog.pg_get_expr(
        policy_definition.polqual,
        policy_definition.polrelid
      )
    ) > 0
    from pg_catalog.pg_policy as policy_definition
    where policy_definition.polrelid = 'public.likes'::regclass
      and policy_definition.polname = 'users delete their likes'
  ),
  'both direct Like write policies serialize with account deletion'
);

select ok(
  has_table_privilege('authenticated', 'public.likes', 'SELECT')
  and has_table_privilege('authenticated', 'public.likes', 'DELETE')
  and has_column_privilege(
    'authenticated',
    'public.likes',
    'user_id',
    'INSERT'
  )
  and has_column_privilege(
    'authenticated',
    'public.likes',
    'listing_id',
    'INSERT'
  )
  and not has_column_privilege(
    'authenticated',
    'public.likes',
    'created_at',
    'INSERT'
  ),
  'direct Like compatibility writes cannot forge their server timestamp'
);

select ok(
  (
    select
      count(*) = 2
      and pg_catalog.bool_and(not wrapper_definition.prosecdef)
      and pg_catalog.bool_and(wrapper_definition.provolatile = 'v'::"char")
      and pg_catalog.bool_and(
        wrapper_definition.proconfig = array['search_path=""']::text[]
      )
      and pg_catalog.bool_and(
        position(
          'set_listing_like_v1' in pg_catalog.pg_get_functiondef(
            wrapper_definition.oid
          )
        ) > 0
      )
    from pg_catalog.pg_proc as wrapper_definition
    where wrapper_definition.oid in (
      'public.like_listing(uuid)'::regprocedure,
      'public.unlike_listing(uuid)'::regprocedure
    )
  ),
  'both security-invoker compatibility RPCs delegate to the target-state setter'
);

select ok(
  has_function_privilege(
    'authenticated',
    'public.like_listing(uuid)',
    'EXECUTE'
  )
  and has_function_privilege(
    'authenticated',
    'public.unlike_listing(uuid)',
    'EXECUTE'
  )
  and not has_function_privilege('anon', 'public.like_listing(uuid)', 'EXECUTE')
  and not has_function_privilege('anon', 'public.unlike_listing(uuid)', 'EXECUTE')
  and not has_function_privilege(
    'service_role',
    'public.like_listing(uuid)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'service_role',
    'public.unlike_listing(uuid)',
    'EXECUTE'
  )
  and position(
    'DEPRECATED' in coalesce(
      pg_catalog.obj_description('public.like_listing(uuid)'::regprocedure),
      ''
    )
  ) > 0
  and position(
    'DEPRECATED' in coalesce(
      pg_catalog.obj_description('public.unlike_listing(uuid)'::regprocedure),
      ''
    )
  ) > 0,
  'legacy Like RPC compatibility is explicit and least-privileged'
);

select ok(
  to_regprocedure('public.set_listing_like_v2(uuid,boolean,uuid)') is not null
  and (
    select procedure_definition.proargnames = array[
      'p_listing_id',
      'p_liked',
      'p_expected_account_id',
      'listing_id',
      'liked',
      'likes_count',
      'mutated_at'
    ]::text[]
      and pg_catalog.pg_get_function_result(procedure_definition.oid) =
        'TABLE(listing_id uuid, liked boolean, likes_count integer, mutated_at timestamp with time zone)'
    from pg_catalog.pg_proc as procedure_definition
    where procedure_definition.oid =
      to_regprocedure('public.set_listing_like_v2(uuid,boolean,uuid)')
  ),
  'the account-bound Like v2 RPC has the exact input and result identity'
);

select ok(
  to_regprocedure('public.set_listing_favorite_v2(uuid,boolean,uuid)') is not null
  and (
    select procedure_definition.proargnames = array[
      'p_listing_id',
      'p_favorited',
      'p_expected_account_id',
      'listing_id',
      'favorited_by_current_user',
      'favorited_at'
    ]::text[]
      and pg_catalog.pg_get_function_result(procedure_definition.oid) =
        'TABLE(listing_id uuid, favorited_by_current_user boolean, favorited_at timestamp with time zone)'
    from pg_catalog.pg_proc as procedure_definition
    where procedure_definition.oid =
      to_regprocedure('public.set_listing_favorite_v2(uuid,boolean,uuid)')
  ),
  'the account-bound Favorite v2 RPC has the exact input and result identity'
);

select ok(
  (
    select count(*) = 2
      and pg_catalog.bool_and(not procedure_definition.prosecdef)
      and pg_catalog.bool_and(procedure_definition.provolatile = 'v'::"char")
      and pg_catalog.bool_and(
        procedure_definition.proconfig = array['search_path=""']::text[]
      )
      and pg_catalog.bool_and(position('p_listing_id is null' in source.definition) > 0)
      and pg_catalog.bool_and(position('p_expected_account_id is null' in source.definition) > 0)
      and pg_catalog.bool_and(
        case procedure_definition.proname
          when 'set_listing_like_v2'
          then position('p_liked is null' in source.definition) > 0
          when 'set_listing_favorite_v2'
          then position('p_favorited is null' in source.definition) > 0
          else false
        end
      )
      and pg_catalog.bool_and(
        position('is distinct from p_expected_account_id' in source.definition) > 0
      )
      and pg_catalog.bool_and(position('pg_advisory_xact_lock' in source.definition) = 0)
      and pg_catalog.bool_and(
        position('is distinct from p_expected_account_id' in source.definition) <
          case procedure_definition.proname
            when 'set_listing_like_v2'
            then position('set_listing_like_v1' in source.definition)
            when 'set_listing_favorite_v2'
            then position('set_listing_favorite_v1' in source.definition)
            else 0
          end
      )
    from pg_catalog.pg_proc as procedure_definition
    cross join lateral (
      select pg_catalog.lower(
        pg_catalog.pg_get_functiondef(procedure_definition.oid)
      ) as definition
    ) as source
    where procedure_definition.oid in (
      to_regprocedure('public.set_listing_like_v2(uuid,boolean,uuid)'),
      to_regprocedure('public.set_listing_favorite_v2(uuid,boolean,uuid)')
    )
  )
  and position(
    'request jwt' in pg_catalog.lower(
      coalesce(
        pg_catalog.obj_description(
          'public.set_listing_like_v2(uuid,boolean,uuid)'::regprocedure
        ),
        ''
      )
    )
  ) > 0
  and position(
    'request jwt' in pg_catalog.lower(
      coalesce(
        pg_catalog.obj_description(
          'public.set_listing_favorite_v2(uuid,boolean,uuid)'::regprocedure
        ),
        ''
      )
    )
  ) > 0,
  'both v2 RPCs validate the expected account before delegated locking or mutation'
);

select ok(
  has_function_privilege(
    'authenticated',
    'public.set_listing_like_v2(uuid,boolean,uuid)',
    'EXECUTE'
  )
  and has_function_privilege(
    'authenticated',
    'public.set_listing_favorite_v2(uuid,boolean,uuid)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'anon',
    'public.set_listing_like_v2(uuid,boolean,uuid)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'anon',
    'public.set_listing_favorite_v2(uuid,boolean,uuid)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'service_role',
    'public.set_listing_like_v2(uuid,boolean,uuid)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'service_role',
    'public.set_listing_favorite_v2(uuid,boolean,uuid)',
    'EXECUTE'
  )
  and not exists (
    select 1
    from pg_catalog.pg_proc as procedure_definition
    cross join lateral pg_catalog.aclexplode(
      coalesce(
        procedure_definition.proacl,
        pg_catalog.acldefault('f', procedure_definition.proowner)
      )
    ) as privilege_definition
    left join pg_catalog.pg_roles as grantee
      on grantee.oid = privilege_definition.grantee
    where procedure_definition.oid in (
      'public.set_listing_like_v2(uuid,boolean,uuid)'::regprocedure,
      'public.set_listing_favorite_v2(uuid,boolean,uuid)'::regprocedure
    )
      and privilege_definition.privilege_type = 'EXECUTE'
      and (
        privilege_definition.grantee = 0
        or (
          privilege_definition.grantee <> procedure_definition.proowner
          and grantee.rolname <> 'authenticated'
        )
      )
  ),
  'authenticated is the only non-owner role that can execute either v2 RPC'
);

select tests.use_auth_context('authenticated', null);
select throws_ok(
  $sql$
    select *
    from public.set_listing_like_v2(
      '00000000-0000-4000-8000-000000000101',
      true,
      '1a1e0000-0000-4000-8000-000000000001'
    )
  $sql$,
  '42501',
  'Authentication required',
  'the Like v2 RPC rejects a role without a JWT identity'
);
select throws_ok(
  $sql$
    select *
    from public.set_listing_favorite_v2(
      '00000000-0000-4000-8000-000000000101',
      true,
      '1a1e0000-0000-4000-8000-000000000001'
    )
  $sql$,
  '42501',
  'Authentication required',
  'the Favorite v2 RPC rejects a role without a JWT identity'
);
reset role;

select tests.use_auth_context(
  'authenticated',
  '1a1e0000-0000-4000-8000-000000000002'
);
select throws_ok(
  $sql$
    select *
    from public.set_listing_like_v2(
      null,
      true,
      '1a1e0000-0000-4000-8000-000000000002'
    )
  $sql$,
  '22023',
  'Like mutation parameters are invalid',
  'the Like v2 RPC rejects a null listing identifier'
);
select throws_ok(
  $sql$
    select *
    from public.set_listing_like_v2(
      '00000000-0000-4000-8000-000000000101',
      null,
      '1a1e0000-0000-4000-8000-000000000002'
    )
  $sql$,
  '22023',
  'Like mutation parameters are invalid',
  'the Like v2 RPC rejects a null desired state'
);
select throws_ok(
  $sql$
    select *
    from public.set_listing_like_v2(
      '00000000-0000-4000-8000-000000000101',
      true,
      null
    )
  $sql$,
  '22023',
  'Like mutation parameters are invalid',
  'the Like v2 RPC rejects a null expected account'
);
select throws_ok(
  $sql$
    select *
    from public.set_listing_favorite_v2(
      null,
      true,
      '1a1e0000-0000-4000-8000-000000000002'
    )
  $sql$,
  '22023',
  'Favorite mutation parameters are invalid',
  'the Favorite v2 RPC rejects a null listing identifier'
);
select throws_ok(
  $sql$
    select *
    from public.set_listing_favorite_v2(
      '00000000-0000-4000-8000-000000000101',
      null,
      '1a1e0000-0000-4000-8000-000000000002'
    )
  $sql$,
  '22023',
  'Favorite mutation parameters are invalid',
  'the Favorite v2 RPC rejects a null desired state'
);
select throws_ok(
  $sql$
    select *
    from public.set_listing_favorite_v2(
      '00000000-0000-4000-8000-000000000101',
      true,
      null
    )
  $sql$,
  '22023',
  'Favorite mutation parameters are invalid',
  'the Favorite v2 RPC rejects a null expected account'
);

select throws_ok(
  $sql$
    select *
    from public.set_listing_like_v2(
      '00000000-0000-4000-8000-000000000101',
      true,
      '1a1e0000-0000-4000-8000-000000000001'
    )
  $sql$,
  '42501',
  'Authentication required',
  'a queued account A Like cannot run with account B JWT credentials'
);
select throws_ok(
  $sql$
    select *
    from public.set_listing_favorite_v2(
      '00000000-0000-4000-8000-000000000101',
      true,
      '1a1e0000-0000-4000-8000-000000000001'
    )
  $sql$,
  '42501',
  'Authentication required',
  'a queued account A Favorite cannot run with account B JWT credentials'
);
reset role;

select ok(
  not exists (
    select 1
    from public.likes as viewer_like
    where viewer_like.user_id = '1a1e0000-0000-4000-8000-000000000002'
      and viewer_like.listing_id = '00000000-0000-4000-8000-000000000101'
  )
  and (
    select listing.likes_count = baseline.likes_count
    from public.listings as listing
    join like_v1_baseline as baseline on baseline.listing_id = listing.id
    where listing.id = '00000000-0000-4000-8000-000000000101'
  ),
  'a mismatched true Like leaves both account B relation and counter untouched'
);
select ok(
  not exists (
    select 1
    from public.favorites as favorite
    where favorite.user_id = '1a1e0000-0000-4000-8000-000000000002'
      and favorite.listing_id = '00000000-0000-4000-8000-000000000101'
  ),
  'a mismatched true Favorite leaves account B state untouched'
);

select tests.use_auth_context(
  'authenticated',
  '1a1e0000-0000-4000-8000-000000000002'
);
create temporary table like_v2_matching_result on commit drop as
select *
from public.set_listing_like_v2(
  '00000000-0000-4000-8000-000000000101',
  true,
  '1a1e0000-0000-4000-8000-000000000002'
);
create temporary table favorite_v2_matching_result on commit drop as
select *
from public.set_listing_favorite_v2(
  '00000000-0000-4000-8000-000000000101',
  true,
  '1a1e0000-0000-4000-8000-000000000002'
);
reset role;

select results_eq(
  $sql$
    select listing_id, liked, likes_count
    from like_v2_matching_result
  $sql$,
  $sql$
    select baseline.listing_id, true, baseline.likes_count + 1
    from like_v1_baseline as baseline
    where baseline.listing_id = '00000000-0000-4000-8000-000000000101'
  $sql$,
  'the Like v2 RPC delegates successfully when JWT and expected account match'
);
select ok(
  (
    select result.listing_id = '00000000-0000-4000-8000-000000000101'::uuid
      and result.favorited_by_current_user
      and result.favorited_at is not null
    from favorite_v2_matching_result as result
  ),
  'the Favorite v2 RPC delegates successfully when JWT and expected account match'
);

select tests.use_auth_context(
  'authenticated',
  '1a1e0000-0000-4000-8000-000000000002'
);
select throws_ok(
  $sql$
    select *
    from public.set_listing_like_v2(
      '00000000-0000-4000-8000-000000000101',
      false,
      '1a1e0000-0000-4000-8000-000000000001'
    )
  $sql$,
  '42501',
  'Authentication required',
  'a queued account A Unlike cannot delete account B state'
);
select throws_ok(
  $sql$
    select *
    from public.set_listing_favorite_v2(
      '00000000-0000-4000-8000-000000000101',
      false,
      '1a1e0000-0000-4000-8000-000000000001'
    )
  $sql$,
  '42501',
  'Authentication required',
  'a queued account A unfavorite cannot delete account B state'
);
reset role;

select ok(
  exists (
    select 1
    from public.likes as viewer_like
    where viewer_like.user_id = '1a1e0000-0000-4000-8000-000000000002'
      and viewer_like.listing_id = '00000000-0000-4000-8000-000000000101'
  )
  and (
    select listing.likes_count = baseline.likes_count + 1
    from public.listings as listing
    join like_v1_baseline as baseline on baseline.listing_id = listing.id
    where listing.id = '00000000-0000-4000-8000-000000000101'
  ),
  'a mismatched false Like preserves account B relation and counter'
);
select ok(
  exists (
    select 1
    from public.favorites as favorite
    where favorite.user_id = '1a1e0000-0000-4000-8000-000000000002'
      and favorite.listing_id = '00000000-0000-4000-8000-000000000101'
  ),
  'a mismatched false Favorite preserves account B state'
);

delete from public.likes as viewer_like
where viewer_like.user_id = '1a1e0000-0000-4000-8000-000000000002'
  and viewer_like.listing_id = '00000000-0000-4000-8000-000000000101';
delete from public.favorites as favorite
where favorite.user_id = '1a1e0000-0000-4000-8000-000000000002'
  and favorite.listing_id = '00000000-0000-4000-8000-000000000101';

select tests.use_auth_context('authenticated', null);
select throws_ok(
  $sql$
    select *
    from public.set_listing_like_v1(
      '00000000-0000-4000-8000-000000000101',
      true
    )
  $sql$,
  '42501',
  'Authentication required',
  'an authenticated database role without a user identity fails closed'
);
reset role;

select tests.use_auth_context(
  'authenticated',
  '1a1e0000-0000-4000-8000-000000000001'
);
select throws_ok(
  $sql$select * from public.set_listing_like_v1(null, true)$sql$,
  '22023',
  'Like mutation parameters are invalid',
  'a null listing identifier is rejected'
);
select throws_ok(
  $sql$select * from public.set_listing_like_v1('not-a-uuid', true)$sql$,
  '22P02',
  null,
  'a malformed listing identifier is rejected at the typed boundary'
);
select throws_ok(
  $sql$
    select *
    from public.set_listing_like_v1(
      '00000000-0000-4000-8000-000000000101',
      null
    )
  $sql$,
  '22023',
  'Like mutation parameters are invalid',
  'a null desired state is rejected'
);
reset role;

select tests.use_auth_context(
  'authenticated',
  '1a1e0000-0000-4000-8000-000000000003'
);
select throws_ok(
  $sql$
    select *
    from public.set_listing_like_v1(
      '00000000-0000-4000-8000-000000000101',
      true
    )
  $sql$,
  '42501',
  'Onboarding completion required',
  'an incomplete account cannot mutate Likes'
);
reset role;

select tests.use_auth_context(
  'authenticated',
  '1a1e0000-0000-4000-8000-000000000001'
);
select throws_ok(
  $sql$
    select *
    from public.set_listing_like_v1(
      '1a1f0000-0000-4000-8000-000000000001',
      true
    )
  $sql$,
  'P0002',
  'listing not found',
  'a new Like on an unknown listing is rejected'
);
reset role;

select tests.use_auth_context(
  'authenticated',
  '1a1e0000-0000-4000-8000-000000000001'
);
create temporary table like_v1_owner_initial_result on commit drop as
select *
from public.set_listing_like_v1(
  '00000000-0000-4000-8000-000000000101',
  true
);
reset role;

select results_eq(
  $sql$
    select listing_id, liked, likes_count
    from like_v1_owner_initial_result
  $sql$,
  $sql$
    select
      baseline.listing_id,
      true,
      baseline.likes_count + 1
    from like_v1_baseline as baseline
    where baseline.listing_id = '00000000-0000-4000-8000-000000000101'
  $sql$,
  'desired true creates one Like and returns the authoritative public count'
);

select ok(
  (
    select result.mutated_at >= '0001-01-01 00:00:00+00'::timestamptz
      and result.mutated_at < '10000-01-01 00:00:00+00'::timestamptz
      and result.mutated_at <= pg_catalog.clock_timestamp()
    from like_v1_owner_initial_result as result
  ),
  'the server confirmation timestamp is finite and not in the future'
);

select is(
  (
    select count(*)
    from public.likes as viewer_like
    where viewer_like.user_id = '1a1e0000-0000-4000-8000-000000000001'
      and viewer_like.listing_id = '00000000-0000-4000-8000-000000000101'
  ),
  1::bigint,
  'desired true stores exactly one owner relation'
);

select is(
  (
    select listing.likes_count
    from public.listings as listing
    where listing.id = '00000000-0000-4000-8000-000000000101'
  ),
  (
    select baseline.likes_count + 1
    from like_v1_baseline as baseline
    where baseline.listing_id = '00000000-0000-4000-8000-000000000101'
  ),
  'the insert trigger increments the aggregate exactly once'
);

select tests.use_auth_context(
  'authenticated',
  '1a1e0000-0000-4000-8000-000000000001'
);
create temporary table like_v1_owner_retry_result on commit drop as
select *
from public.set_listing_like_v1(
  '00000000-0000-4000-8000-000000000101',
  true
);
reset role;

select results_eq(
  $sql$
    select listing_id, liked, likes_count
    from like_v1_owner_retry_result
  $sql$,
  $sql$
    select
      baseline.listing_id,
      true,
      baseline.likes_count + 1
    from like_v1_baseline as baseline
    where baseline.listing_id = '00000000-0000-4000-8000-000000000101'
  $sql$,
  'repeating desired true confirms the same target state and count'
);

select ok(
  (
    select count(*) = 1
    from public.likes as viewer_like
    where viewer_like.user_id = '1a1e0000-0000-4000-8000-000000000001'
      and viewer_like.listing_id = '00000000-0000-4000-8000-000000000101'
  )
  and (
    select listing.likes_count = baseline.likes_count + 1
    from public.listings as listing
    join like_v1_baseline as baseline on baseline.listing_id = listing.id
    where listing.id = '00000000-0000-4000-8000-000000000101'
  ),
  'the true retry neither duplicates the relation nor increments twice'
);

select tests.use_auth_context(
  'authenticated',
  '1a1e0000-0000-4000-8000-000000000002'
);
select is(
  (
    select count(*)
    from public.likes as viewer_like
    where viewer_like.listing_id = '00000000-0000-4000-8000-000000000101'
  ),
  0::bigint,
  'RLS hides another account Like relation'
);
with deleted_like as (
  delete from public.likes as viewer_like
  where viewer_like.user_id = '1a1e0000-0000-4000-8000-000000000001'
    and viewer_like.listing_id = '00000000-0000-4000-8000-000000000101'
  returning viewer_like.listing_id
)
select is(
  (select count(*) from deleted_like),
  0::bigint,
  'RLS prevents a direct cross-account Like deletion'
);
create temporary table like_v1_other_false_result on commit drop as
select *
from public.set_listing_like_v1(
  '00000000-0000-4000-8000-000000000101',
  false
);
reset role;

select results_eq(
  $sql$
    select listing_id, liked, likes_count
    from like_v1_other_false_result
  $sql$,
  $sql$
    select
      baseline.listing_id,
      false,
      baseline.likes_count + 1
    from like_v1_baseline as baseline
    where baseline.listing_id = '00000000-0000-4000-8000-000000000101'
  $sql$,
  'another account can confirm only its own absent state'
);

select ok(
  exists (
    select 1
    from public.likes as viewer_like
    where viewer_like.user_id = '1a1e0000-0000-4000-8000-000000000001'
      and viewer_like.listing_id = '00000000-0000-4000-8000-000000000101'
  ),
  'a cross-account false mutation cannot remove the owner relation'
);

select tests.use_auth_context(
  'authenticated',
  '1a1e0000-0000-4000-8000-000000000001'
);
create temporary table like_v1_owner_false_result on commit drop as
select *
from public.set_listing_like_v1(
  '00000000-0000-4000-8000-000000000101',
  false
);
reset role;

select results_eq(
  $sql$
    select listing_id, liked, likes_count
    from like_v1_owner_false_result
  $sql$,
  $sql$
    select baseline.listing_id, false, baseline.likes_count
    from like_v1_baseline as baseline
    where baseline.listing_id = '00000000-0000-4000-8000-000000000101'
  $sql$,
  'desired false removes the owner Like and returns the decremented count'
);

select ok(
  not exists (
    select 1
    from public.likes as viewer_like
    where viewer_like.user_id = '1a1e0000-0000-4000-8000-000000000001'
      and viewer_like.listing_id = '00000000-0000-4000-8000-000000000101'
  )
  and (
    select listing.likes_count = baseline.likes_count
    from public.listings as listing
    join like_v1_baseline as baseline on baseline.listing_id = listing.id
    where listing.id = '00000000-0000-4000-8000-000000000101'
  ),
  'desired false removes one relation and decrements the aggregate exactly once'
);

select tests.use_auth_context(
  'authenticated',
  '1a1e0000-0000-4000-8000-000000000001'
);
create temporary table like_v1_owner_false_retry_result on commit drop as
select *
from public.set_listing_like_v1(
  '00000000-0000-4000-8000-000000000101',
  false
);
reset role;

select results_eq(
  $sql$
    select listing_id, liked, likes_count
    from like_v1_owner_false_retry_result
  $sql$,
  $sql$
    select baseline.listing_id, false, baseline.likes_count
    from like_v1_baseline as baseline
    where baseline.listing_id = '00000000-0000-4000-8000-000000000101'
  $sql$,
  'repeating desired false confirms the same absent target state'
);

select tests.use_auth_context(
  'authenticated',
  '1a1e0000-0000-4000-8000-000000000001'
);
create temporary table like_v1_legacy_like_result on commit drop as
select *
from public.like_listing(
  '00000000-0000-4000-8000-000000000101'
);
reset role;

select results_eq(
  $sql$
    select
      listing_id,
      liked_by_current_user,
      favorited_by_current_user,
      likes_count
    from like_v1_legacy_like_result
  $sql$,
  $sql$
    select
      baseline.listing_id,
      true,
      false,
      baseline.likes_count + 1
    from like_v1_baseline as baseline
    where baseline.listing_id = '00000000-0000-4000-8000-000000000101'
  $sql$,
  'the released Like RPC delegates to the target-state mutation and response'
);

select tests.use_auth_context(
  'authenticated',
  '1a1e0000-0000-4000-8000-000000000001'
);
create temporary table like_v1_legacy_unlike_result on commit drop as
select *
from public.unlike_listing(
  '00000000-0000-4000-8000-000000000101'
);
reset role;

select results_eq(
  $sql$
    select
      listing_id,
      liked_by_current_user,
      favorited_by_current_user,
      likes_count
    from like_v1_legacy_unlike_result
  $sql$,
  $sql$
    select
      baseline.listing_id,
      false,
      false,
      baseline.likes_count
    from like_v1_baseline as baseline
    where baseline.listing_id = '00000000-0000-4000-8000-000000000101'
  $sql$,
  'the released Unlike RPC delegates without rolling the aggregate twice'
);

select ok(
  not exists (
    select 1
    from public.likes as viewer_like
    where viewer_like.user_id = '1a1e0000-0000-4000-8000-000000000001'
      and viewer_like.listing_id = '00000000-0000-4000-8000-000000000101'
  )
  and (
    select listing.likes_count = baseline.likes_count
    from public.listings as listing
    join like_v1_baseline as baseline on baseline.listing_id = listing.id
    where listing.id = '00000000-0000-4000-8000-000000000101'
  ),
  'the compatibility round trip leaves one authoritative absent state'
);

select tests.use_auth_context(
  'authenticated',
  '1a1e0000-0000-4000-8000-000000000001'
);
create temporary table like_v1_unknown_false_result on commit drop as
select *
from public.set_listing_like_v1(
  '1a1f0000-0000-4000-8000-000000000001',
  false
);
reset role;

select ok(
  (
    select result.listing_id = '1a1f0000-0000-4000-8000-000000000001'::uuid
      and not result.liked
      and result.likes_count is null
      and result.mutated_at is not null
    from like_v1_unknown_false_result as result
  ),
  'desired false is idempotently absent for an unknown valid identifier'
);

select tests.use_auth_context(
  'authenticated',
  '1a1e0000-0000-4000-8000-000000000001'
);
create temporary table like_v1_hidden_initial_result on commit drop as
select *
from public.set_listing_like_v1(
  '00000000-0000-4000-8000-000000000102',
  true
);
reset role;

select results_eq(
  $sql$
    select listing_id, liked, likes_count
    from like_v1_hidden_initial_result
  $sql$,
  $sql$
    select
      baseline.listing_id,
      true,
      baseline.likes_count + 1
    from like_v1_baseline as baseline
    where baseline.listing_id = '00000000-0000-4000-8000-000000000102'
  $sql$,
  'a published listing accepts the initial desired true mutation'
);

update public.listings
set status = 'archive'
where id = '00000000-0000-4000-8000-000000000102';

select tests.use_auth_context(
  'authenticated',
  '1a1e0000-0000-4000-8000-000000000001'
);
create temporary table like_v1_hidden_retry_result on commit drop as
select *
from public.set_listing_like_v1(
  '00000000-0000-4000-8000-000000000102',
  true
);
reset role;

select ok(
  (
    select result.listing_id = '00000000-0000-4000-8000-000000000102'::uuid
      and result.liked
      and result.likes_count is null
      and result.mutated_at is not null
    from like_v1_hidden_retry_result as result
  ),
  'a lost-response true retry confirms an existing Like after moderation'
);

select ok(
  exists (
    select 1
    from public.likes as viewer_like
    where viewer_like.user_id = '1a1e0000-0000-4000-8000-000000000001'
      and viewer_like.listing_id = '00000000-0000-4000-8000-000000000102'
  ),
  'the hidden true retry preserves the existing relation'
);

select tests.use_auth_context(
  'authenticated',
  '1a1e0000-0000-4000-8000-000000000002'
);
select throws_ok(
  $sql$
    select *
    from public.set_listing_like_v1(
      '00000000-0000-4000-8000-000000000102',
      true
    )
  $sql$,
  'P0002',
  'listing not found',
  'a new desired true relation remains forbidden after moderation'
);
reset role;

select tests.use_auth_context(
  'authenticated',
  '1a1e0000-0000-4000-8000-000000000001'
);
create temporary table like_v1_hidden_false_result on commit drop as
select *
from public.set_listing_like_v1(
  '00000000-0000-4000-8000-000000000102',
  false
);
reset role;

select ok(
  (
    select result.listing_id = '00000000-0000-4000-8000-000000000102'::uuid
      and not result.liked
      and result.likes_count is null
      and result.mutated_at is not null
    from like_v1_hidden_false_result as result
  ),
  'desired false succeeds after moderation without leaking the hidden count'
);

select ok(
  not exists (
    select 1
    from public.likes as viewer_like
    where viewer_like.user_id = '1a1e0000-0000-4000-8000-000000000001'
      and viewer_like.listing_id = '00000000-0000-4000-8000-000000000102'
  )
  and (
    select listing.likes_count = baseline.likes_count
    from public.listings as listing
    join like_v1_baseline as baseline on baseline.listing_id = listing.id
    where listing.id = '00000000-0000-4000-8000-000000000102'
  ),
  'the hidden removal commits and decrements the stored aggregate once'
);

select tests.use_auth_context(
  'authenticated',
  '1a1e0000-0000-4000-8000-000000000001'
);
create temporary table like_v1_hidden_false_retry_result on commit drop as
select *
from public.set_listing_like_v1(
  '00000000-0000-4000-8000-000000000102',
  false
);
reset role;

select ok(
  (
    select result.listing_id = '00000000-0000-4000-8000-000000000102'::uuid
      and not result.liked
      and result.likes_count is null
      and result.mutated_at is not null
    from like_v1_hidden_false_retry_result as result
  ),
  'a hidden desired false retry remains successfully absent'
);

select tests.use_auth_context(
  'authenticated',
  '1a1e0000-0000-4000-8000-000000000001'
);
create temporary table like_v1_legacy_hidden_unlike_result on commit drop as
select *
from public.unlike_listing(
  '00000000-0000-4000-8000-000000000102'
);
reset role;

select results_eq(
  $sql$
    select
      listing_id,
      liked_by_current_user,
      favorited_by_current_user,
      likes_count
    from like_v1_legacy_hidden_unlike_result
  $sql$,
  $sql$
    values (
      '00000000-0000-4000-8000-000000000102'::uuid,
      false,
      false,
      0::integer
    )
  $sql$,
  'the released Unlike RPC commits an idempotent hidden removal with a neutral count'
);

select tests.use_auth_context(
  'authenticated',
  '1a1e0000-0000-4000-8000-000000000004'
);
select lives_ok(
  $sql$
    select *
    from public.set_listing_like_v1(
      '00000000-0000-4000-8000-000000000101',
      true
    )
  $sql$,
  'the deletion fixture can create a Like before cleanup'
);
reset role;

set local role service_role;
select is(
  (
    select status
    from public.prepare_account_deletion(
      '1a1e0000-0000-4000-8000-000000000004',
      '1a1d0000-0000-4000-8000-000000000004'
    )
  ),
  'prepared',
  'account deletion preparation accepts the Like fixture'
);
reset role;

select ok(
  not exists (
    select 1
    from public.likes as viewer_like
    where viewer_like.user_id = '1a1e0000-0000-4000-8000-000000000004'
  ),
  'account deletion preparation purges the owner Likes'
);

select tests.use_auth_context(
  'authenticated',
  '1a1e0000-0000-4000-8000-000000000004'
);
select throws_ok(
  $sql$
    select *
    from public.set_listing_like_v1(
      '00000000-0000-4000-8000-000000000101',
      true
    )
  $sql$,
  '42501',
  'Onboarding completion required',
  'the deletion tombstone fences a later desired true mutation'
);
select throws_ok(
  $sql$
    select *
    from public.set_listing_like_v1(
      '00000000-0000-4000-8000-000000000101',
      false
    )
  $sql$,
  '42501',
  'Onboarding completion required',
  'the deletion tombstone also fences a later desired false mutation'
);
select throws_ok(
  $sql$
    select *
    from public.like_listing(
      '00000000-0000-4000-8000-000000000101'
    )
  $sql$,
  '42501',
  'Onboarding completion required',
  'the deletion tombstone fences the released Like RPC too'
);
select throws_ok(
  $sql$
    select *
    from public.unlike_listing(
      '00000000-0000-4000-8000-000000000101'
    )
  $sql$,
  '42501',
  'Onboarding completion required',
  'the deletion tombstone fences the released Unlike RPC too'
);
select throws_ok(
  $sql$
    insert into public.likes (user_id, listing_id)
    values (
      '1a1e0000-0000-4000-8000-000000000004',
      '00000000-0000-4000-8000-000000000101'
    )
  $sql$,
  '42501',
  null,
  'the volatile policy guard fences a direct REST-compatible insert after deletion preparation'
);
reset role;

select ok(
  position(
    'delete from public.likes' in pg_catalog.lower(
      pg_catalog.pg_get_functiondef(
        'app_private.prepare_account_data_for_deletion(uuid)'::regprocedure
      )
    )
  ) > 0
  and position(
    'delete from public.likes' in pg_catalog.lower(
      pg_catalog.pg_get_functiondef(
        'app_private.cleanup_account_data(uuid)'::regprocedure
      )
    )
  ) > 0,
  'both replayable account cleanup phases retain Like purging'
);

select * from finish();
rollback;
