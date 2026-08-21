-- Supabase-hosted projects may provision this helper outside the canonical
-- migration history. Keep canonical resets portable while removing every API
-- role's direct EXECUTE privilege when the hosted-only function is present.
-- A successful canonical reset where the function is absent does not exercise
-- or prove the hosted branch; staging must verify the effective hosted ACL.
do $migration$
begin
  if to_regprocedure('public.rls_auto_enable()') is not null then
    execute $revoke$
      revoke execute on function public.rls_auto_enable()
      from public, anon, authenticated, service_role
    $revoke$;
  end if;
end;
$migration$;
