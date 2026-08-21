-- Invitation acceptance is an authenticated end-user operation. Rebuild its
-- EXECUTE ACL explicitly so platform defaults or hosted grants cannot expose
-- the SECURITY DEFINER RPC to PUBLIC, anonymous clients, or service_role.
revoke execute on function public.accept_organization_invite(text)
from public, anon, authenticated, service_role;

grant execute on function public.accept_organization_invite(text)
to authenticated;
