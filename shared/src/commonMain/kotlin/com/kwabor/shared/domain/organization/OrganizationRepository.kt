package com.kwabor.shared.domain.organization

import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.core.PageRequest
import com.kwabor.shared.domain.core.PageResult

interface OrganizationRepository {
    suspend fun listOrganizations(page: PageRequest = PageRequest()): DomainResult<PageResult<Organization>>

    suspend fun getOrganization(organizationId: String): DomainResult<Organization>

    suspend fun listMembers(organizationId: String): DomainResult<List<OrganizationMember>>

    suspend fun inviteMember(request: OrganizationInviteRequest): DomainResult<OrganizationInvite>

    suspend fun listInvites(organizationId: String): DomainResult<List<OrganizationInvite>>

    suspend fun acceptInvite(inviteToken: String): DomainResult<OrganizationMember>

    suspend fun revokeInvite(inviteId: String): DomainResult<OrganizationInvite>

    suspend fun updateMemberRole(request: OrganizationMemberRoleUpdate): DomainResult<OrganizationMember>

    suspend fun suspendMember(organizationId: String, memberId: String): DomainResult<OrganizationMember>

    suspend fun listMemberAdBudgets(
        organizationId: String,
        page: PageRequest = PageRequest(),
    ): DomainResult<PageResult<MemberAdBudget>>

    suspend fun allocateMemberAdBudget(request: MemberAdBudgetAllocationRequest): DomainResult<MemberAdBudget>
}
