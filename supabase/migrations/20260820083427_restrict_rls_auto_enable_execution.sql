-- The hosted project can install this event-trigger helper outside the versioned
-- schema. It must remain executable only by its owner/event trigger, never by a
-- Data API role.
do $$
begin
  if to_regprocedure('public.rls_auto_enable()') is not null then
    execute
      'revoke all on function public.rls_auto_enable() '
      'from public, anon, authenticated, service_role';
  end if;
end;
$$;

-- Keep the invitation acceptance RPC authenticated-only even when this
-- migration is applied to a database whose default function grants differ.
revoke all on function public.accept_organization_invite(text)
from public, anon, authenticated;
grant execute on function public.accept_organization_invite(text)
to authenticated;
