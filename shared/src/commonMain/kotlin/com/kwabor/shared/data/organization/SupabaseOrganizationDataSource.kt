package com.kwabor.shared.data.organization

import com.kwabor.shared.domain.core.PageRequest
import com.kwabor.shared.domain.organization.MemberAdBudgetAllocationRequest
import com.kwabor.shared.domain.organization.OrganizationInviteRequest
import com.kwabor.shared.domain.organization.OrganizationMemberRoleUpdate
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import io.ktor.client.plugins.HttpRequestTimeoutException

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_CONFLICT = 409
private const val HTTP_UNPROCESSABLE_CONTENT = 422

internal class SupabaseOrganizationDataSource(
    private val postgrest: Postgrest,
) : OrganizationDataSource {
    override suspend fun listOrganizations(page: PageRequest): List<OrganizationDto> = runPostgrest {
        postgrest.from(ORGANIZATIONS)
            .select {
                order("name", Order.ASCENDING)
                applyPage(page)
            }
            .decodeList()
    }

    override suspend fun getOrganization(organizationId: String): OrganizationDto = runPostgrest {
        postgrest.from(ORGANIZATIONS)
            .select {
                filter {
                    eq("id", organizationId)
                }
                limit(1)
            }
            .decodeSingle()
    }

    override suspend fun listMembers(organizationId: String): List<OrganizationMemberDto> = runPostgrest {
        postgrest.from(ORGANIZATION_MEMBERS)
            .select {
                filter {
                    eq("organization_id", organizationId)
                }
                order("role", Order.DESCENDING)
            }
            .decodeList()
    }

    override suspend fun createInvite(request: OrganizationInviteRequest): OrganizationInviteDto = runPostgrest {
        postgrest.rpc(
            function = "create_organization_invite",
            parameters = request.toRpcDto(),
        ).decodeSingle()
    }

    override suspend fun listInvites(organizationId: String): List<OrganizationInviteDto> = runPostgrest {
        postgrest.from(ORGANIZATION_INVITES)
            .select {
                filter {
                    eq("organization_id", organizationId)
                }
                order("expires_at", Order.DESCENDING)
            }
            .decodeList()
    }

    override suspend fun acceptInvite(inviteToken: String): OrganizationMemberDto = runPostgrest {
        postgrest.rpc(
            function = "accept_organization_invite",
            parameters = AcceptOrganizationInviteRpcDto(inviteToken = inviteToken),
        ).decodeSingle()
    }

    override suspend fun revokeInvite(inviteId: String): OrganizationInviteDto = runPostgrest {
        postgrest.rpc(
            function = "revoke_organization_invite",
            parameters = RevokeOrganizationInviteRpcDto(inviteId = inviteId),
        ).decodeSingle()
    }

    override suspend fun updateMemberRole(request: OrganizationMemberRoleUpdate): OrganizationMemberDto = runPostgrest {
        postgrest.from(ORGANIZATION_MEMBERS)
            .update(request.toPatchDto()) {
                filter {
                    eq("organization_id", request.organizationId)
                    eq("id", request.memberId)
                }
                select()
            }
            .decodeSingle()
    }

    override suspend fun suspendMember(organizationId: String, memberId: String): OrganizationMemberDto = runPostgrest {
        postgrest.rpc(
            function = "suspend_organization_member",
            parameters = SuspendOrganizationMemberRpcDto(
                organizationId = organizationId,
                memberId = memberId,
            ),
        ).decodeSingle()
    }

    override suspend fun listMemberAdBudgets(organizationId: String, page: PageRequest): List<MemberAdBudgetDto> =
        runPostgrest {
            postgrest.from(MEMBER_AD_BUDGETS)
                .select {
                    filter {
                        eq("organization_id", organizationId)
                    }
                    order("period_start", Order.DESCENDING)
                    applyPage(page)
                }
                .decodeList()
        }

    override suspend fun allocateMemberAdBudget(request: MemberAdBudgetAllocationRequest): MemberAdBudgetDto =
        runPostgrest {
            postgrest.from(MEMBER_AD_BUDGETS)
                .insert(request.toCommandDto()) {
                    select()
                }
                .decodeSingle()
        }
}

private const val ORGANIZATIONS = "organizations"
private const val ORGANIZATION_MEMBERS = "organization_members"
private const val ORGANIZATION_INVITES = "organization_invites"
private const val MEMBER_AD_BUDGETS = "member_ad_budgets"

private fun io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder.applyPage(page: PageRequest) {
    range(
        from = page.offset.toLong(),
        to = (page.offset + page.limit - 1).toLong(),
    )
}

private suspend fun <T> runPostgrest(block: suspend () -> T): T = try {
    block()
} catch (exception: PostgrestRestException) {
    throw exception.toOrganizationDataException()
} catch (exception: RestException) {
    throw exception.toOrganizationDataException()
} catch (exception: HttpRequestTimeoutException) {
    throw OrganizationDataException.NetworkUnavailable(exception)
} catch (exception: HttpRequestException) {
    throw OrganizationDataException.NetworkUnavailable(exception)
}

private fun RestException.toOrganizationDataException(): OrganizationDataException {
    if (this is PostgrestRestException) {
        when (code) {
            "P0002", "PGRST116" -> return OrganizationDataException.NotFound(cause = this)
            "42501" -> return OrganizationDataException.PermissionDenied(cause = this)
            "22023", "23503", "23505", "23514" -> return OrganizationDataException.Validation(cause = this)
        }
    }

    return when (statusCode) {
        HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> OrganizationDataException.PermissionDenied(cause = this)
        HTTP_NOT_FOUND -> OrganizationDataException.NotFound(cause = this)
        HTTP_BAD_REQUEST,
        HTTP_CONFLICT,
        HTTP_UNPROCESSABLE_CONTENT,
        -> OrganizationDataException.Validation(cause = this)
        else -> OrganizationDataException.Unexpected(this)
    }
}
