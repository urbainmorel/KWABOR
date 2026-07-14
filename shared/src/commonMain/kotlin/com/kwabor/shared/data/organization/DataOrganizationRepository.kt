package com.kwabor.shared.data.organization

import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.core.PageRequest
import com.kwabor.shared.domain.core.PageResult
import com.kwabor.shared.domain.organization.MemberAdBudget
import com.kwabor.shared.domain.organization.MemberAdBudgetAllocationRequest
import com.kwabor.shared.domain.organization.Organization
import com.kwabor.shared.domain.organization.OrganizationInvite
import com.kwabor.shared.domain.organization.OrganizationInviteRequest
import com.kwabor.shared.domain.organization.OrganizationMember
import com.kwabor.shared.domain.organization.OrganizationMemberRoleUpdate
import com.kwabor.shared.domain.organization.OrganizationRepository

class DataOrganizationRepository internal constructor(
    private val dataSource: OrganizationDataSource,
) : OrganizationRepository {
    override suspend fun listOrganizations(page: PageRequest): DomainResult<PageResult<Organization>> = runDataCall {
        dataSource.listOrganizations(page).map { it.toDomain() }.toPageResult(page)
    }

    override suspend fun getOrganization(organizationId: String): DomainResult<Organization> = runDataCall {
        dataSource.getOrganization(organizationId).toDomain()
    }

    override suspend fun listMembers(organizationId: String): DomainResult<List<OrganizationMember>> = runDataCall {
        dataSource.listMembers(organizationId).map { it.toDomain() }
    }

    override suspend fun inviteMember(request: OrganizationInviteRequest): DomainResult<OrganizationInvite> =
        runDataCall {
            dataSource.createInvite(request).toDomain()
        }

    override suspend fun listInvites(organizationId: String): DomainResult<List<OrganizationInvite>> = runDataCall {
        dataSource.listInvites(organizationId).map { it.toDomain() }
    }

    override suspend fun acceptInvite(inviteToken: String): DomainResult<OrganizationMember> = runDataCall {
        dataSource.acceptInvite(inviteToken).toDomain()
    }

    override suspend fun revokeInvite(inviteId: String): DomainResult<OrganizationInvite> = runDataCall {
        dataSource.revokeInvite(inviteId).toDomain()
    }

    override suspend fun updateMemberRole(request: OrganizationMemberRoleUpdate): DomainResult<OrganizationMember> =
        runDataCall {
            dataSource.updateMemberRole(request).toDomain()
        }

    override suspend fun suspendMember(organizationId: String, memberId: String): DomainResult<OrganizationMember> =
        runDataCall {
            dataSource.suspendMember(organizationId = organizationId, memberId = memberId).toDomain()
        }

    override suspend fun listMemberAdBudgets(
        organizationId: String,
        page: PageRequest,
    ): DomainResult<PageResult<MemberAdBudget>> = runDataCall {
        dataSource.listMemberAdBudgets(organizationId = organizationId, page = page)
            .map { it.toDomain() }
            .toPageResult(page)
    }

    override suspend fun allocateMemberAdBudget(
        request: MemberAdBudgetAllocationRequest,
    ): DomainResult<MemberAdBudget> = runDataCall {
        dataSource.allocateMemberAdBudget(request).toDomain()
    }
}

private inline fun <T> runDataCall(block: () -> T): DomainResult<T> = try {
    DomainResult.Success(block())
} catch (exception: OrganizationDataException) {
    DomainResult.Failure(exception.domainError)
}

private fun <T> List<T>.toPageResult(page: PageRequest): PageResult<T> = PageResult(
    items = this,
    nextOffset = if (size >= page.limit) page.offset + page.limit else null,
)
