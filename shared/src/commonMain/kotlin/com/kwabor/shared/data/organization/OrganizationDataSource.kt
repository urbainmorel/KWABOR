package com.kwabor.shared.data.organization

import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.PageRequest
import com.kwabor.shared.domain.organization.MemberAdBudgetAllocationRequest
import com.kwabor.shared.domain.organization.OrganizationInviteRequest
import com.kwabor.shared.domain.organization.OrganizationMemberRoleUpdate

internal interface OrganizationDataSource {
    suspend fun listOrganizations(page: PageRequest): List<OrganizationDto>

    suspend fun getOrganization(organizationId: String): OrganizationDto

    suspend fun listMembers(organizationId: String): List<OrganizationMemberDto>

    suspend fun createInvite(request: OrganizationInviteRequest): OrganizationInviteDto

    suspend fun listInvites(organizationId: String): List<OrganizationInviteDto>

    suspend fun acceptInvite(inviteToken: String): OrganizationMemberDto

    suspend fun revokeInvite(inviteId: String): OrganizationInviteDto

    suspend fun updateMemberRole(request: OrganizationMemberRoleUpdate): OrganizationMemberDto

    suspend fun suspendMember(organizationId: String, memberId: String): OrganizationMemberDto

    suspend fun listMemberAdBudgets(organizationId: String, page: PageRequest): List<MemberAdBudgetDto>

    suspend fun allocateMemberAdBudget(request: MemberAdBudgetAllocationRequest): MemberAdBudgetDto
}

internal sealed class OrganizationDataException(
    val domainError: DomainError,
) : RuntimeException(domainError.messageKey) {
    class NotFound(
        messageKey: String = "error.organization.not_found",
    ) : OrganizationDataException(DomainError.NotFound(messageKey))

    class PermissionDenied(
        messageKey: String = "error.organization.permission_denied",
    ) : OrganizationDataException(DomainError.PermissionDenied(messageKey))

    class NetworkUnavailable : OrganizationDataException(DomainError.NetworkUnavailable())
}
