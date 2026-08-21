-- Invitation acceptance is an end-user action. Keep the elevated backend role
-- out of the callable surface even if the hosted project granted it explicitly.
revoke all on function public.accept_organization_invite(text)
from service_role;
