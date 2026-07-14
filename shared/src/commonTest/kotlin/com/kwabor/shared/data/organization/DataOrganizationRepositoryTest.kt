package com.kwabor.shared.data.organization

import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.core.PageRequest
import com.kwabor.shared.domain.core.PageResult
import com.kwabor.shared.domain.money.MoneyXof
import com.kwabor.shared.domain.organization.MemberAdBudget
import com.kwabor.shared.domain.organization.MemberAdBudgetAllocationRequest
import com.kwabor.shared.domain.organization.MemberAdBudgetAllocationValues
import com.kwabor.shared.domain.organization.Organization
import com.kwabor.shared.domain.organization.OrganizationInvite
import com.kwabor.shared.domain.organization.OrganizationInviteRequest
import com.kwabor.shared.domain.organization.OrganizationInviteValues
import com.kwabor.shared.domain.organization.OrganizationMemberRoleUpdate
import com.kwabor.shared.domain.organization.OrganizationRole
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DataOrganizationRepositoryTest {
    @Test
    fun listOrganizations_mapsPageAndNextOffset() = runTest {
        val dataSource = FakeOrganizationDataSource(
            organizations = listOf(
                organizationDto(id = "organization-1"),
                organizationDto(id = "organization-2"),
            ),
        )
        val repository = DataOrganizationRepository(dataSource)

        val result = repository.listOrganizations(PageRequest(offset = 10, limit = 2))

        val success = assertIs<DomainResult.Success<PageResult<Organization>>>(result)
        assertEquals(2, success.value.items.size)
        assertEquals(12, success.value.nextOffset)
    }

    @Test
    fun inviteMember_delegatesRequestAndMapsResponse() = runTest {
        val dataSource = FakeOrganizationDataSource()
        val repository = DataOrganizationRepository(dataSource)
        val request = inviteRequest()

        val result = repository.inviteMember(request)

        val invite = assertIs<DomainResult.Success<OrganizationInvite>>(result).value
        assertEquals("editor@kwabor.test", invite.email)
        assertEquals("member-owner", dataSource.lastInviteRequest?.invitedByMemberId)
    }

    @Test
    fun allocateMemberAdBudget_delegatesRequestAndMapsBudget() = runTest {
        val dataSource = FakeOrganizationDataSource()
        val repository = DataOrganizationRepository(dataSource)
        val request = budgetRequest()

        val result = repository.allocateMemberAdBudget(request)

        val budget = assertIs<DomainResult.Success<MemberAdBudget>>(result).value
        assertEquals(50_000, budget.allocatedXof.amount)
        assertEquals("member-manager", dataSource.lastBudgetRequest?.allocatedByMemberId)
    }

    @Test
    fun getOrganization_mapsMissingRowsToNotFound() = runTest {
        val repository = DataOrganizationRepository(
            FakeOrganizationDataSource(throwOnGetOrganization = true),
        )

        val result = repository.getOrganization("missing")

        val failure = assertIs<DomainResult.Failure>(result)
        assertIs<DomainError.NotFound>(failure.error)
    }

    @Test
    fun invalidDto_mapsToUnexpectedFailure() = runTest {
        val repository = DataOrganizationRepository(
            FakeOrganizationDataSource(
                organizations = listOf(organizationDto(type = "invalid")),
            ),
        )

        val result = repository.listOrganizations(PageRequest(limit = 1))

        val failure = assertIs<DomainResult.Failure>(result)
        assertIs<DomainError.Unexpected>(failure.error)
    }

    private fun inviteRequest(): OrganizationInviteRequest = assertIs<DomainResult.Success<OrganizationInviteRequest>>(
        OrganizationInviteRequest.create(
            OrganizationInviteValues(
                organizationId = "organization-1",
                invitedByMemberId = "member-owner",
                email = "editor@kwabor.test",
                proposedRole = OrganizationRole.Editor,
                expiresAtEpochMilliseconds = 1_783_073_730_000,
                nowEpochMilliseconds = 1_783_070_000_000,
            ),
        ),
    ).value

    private fun budgetRequest(): MemberAdBudgetAllocationRequest {
        val money = assertIs<DomainResult.Success<MoneyXof>>(MoneyXof.fromAmount(50_000)).value
        return assertIs<DomainResult.Success<MemberAdBudgetAllocationRequest>>(
            MemberAdBudgetAllocationRequest.create(
                MemberAdBudgetAllocationValues(
                    organizationId = "organization-1",
                    memberId = "member-editor",
                    allocatedByMemberId = "member-manager",
                    memberRole = OrganizationRole.Editor,
                    periodStartEpochDay = 20_635,
                    periodEndEpochDay = 20_665,
                    allocatedXof = money,
                ),
            ),
        ).value
    }
}

private class FakeOrganizationDataSource(
    private val organizations: List<OrganizationDto> = listOf(organizationDto()),
    private val throwOnGetOrganization: Boolean = false,
) : OrganizationDataSource {
    var lastInviteRequest: OrganizationInviteRequest? = null
        private set
    var lastBudgetRequest: MemberAdBudgetAllocationRequest? = null
        private set

    override suspend fun listOrganizations(page: PageRequest): List<OrganizationDto> = organizations

    override suspend fun getOrganization(organizationId: String): OrganizationDto {
        if (throwOnGetOrganization) {
            throw OrganizationDataException.NotFound()
        }
        return organizations.first()
    }

    override suspend fun listMembers(organizationId: String): List<OrganizationMemberDto> = listOf(memberDto())

    override suspend fun createInvite(request: OrganizationInviteRequest): OrganizationInviteDto {
        lastInviteRequest = request
        return inviteDto(email = request.email, proposedRole = "editeur")
    }

    override suspend fun listInvites(organizationId: String): List<OrganizationInviteDto> = listOf(inviteDto())

    override suspend fun acceptInvite(inviteToken: String): OrganizationMemberDto = memberDto()

    override suspend fun revokeInvite(inviteId: String): OrganizationInviteDto = inviteDto(status = "revoked")

    override suspend fun updateMemberRole(request: OrganizationMemberRoleUpdate): OrganizationMemberDto =
        memberDto(role = "gestionnaire")

    override suspend fun suspendMember(organizationId: String, memberId: String): OrganizationMemberDto =
        memberDto(status = "suspended")

    override suspend fun listMemberAdBudgets(organizationId: String, page: PageRequest): List<MemberAdBudgetDto> =
        listOf(budgetDto())

    override suspend fun allocateMemberAdBudget(request: MemberAdBudgetAllocationRequest): MemberAdBudgetDto {
        lastBudgetRequest = request
        return budgetDto(allocatedXof = request.allocatedXof.amount)
    }
}

private fun organizationDto(id: String = "organization-1", type: String = "promoteur"): OrganizationDto =
    OrganizationDto(
        id = id,
        type = type,
        name = "Kwabor Test",
        slug = "kwabor-test",
        verificationStatus = "verified",
        primaryOwnerId = "user-owner",
    )

private fun memberDto(role: String = "editeur", status: String = "active"): OrganizationMemberDto =
    OrganizationMemberDto(
        id = "member-editor",
        organizationId = "organization-1",
        userId = "user-editor",
        role = role,
        status = status,
        acceptedAt = "2026-07-03T10:15:30Z",
    )

private fun inviteDto(
    email: String = "editor@kwabor.test",
    proposedRole: String = "editeur",
    status: String = "pending",
): OrganizationInviteDto = OrganizationInviteDto(
    id = "invite-1",
    organizationId = "organization-1",
    email = email,
    proposedRole = proposedRole,
    invitedByMemberId = "member-owner",
    status = status,
    expiresAt = "2026-07-03T10:15:30Z",
)

private fun budgetDto(allocatedXof: Long = 50_000): MemberAdBudgetDto = MemberAdBudgetDto(
    id = "budget-1",
    organizationId = "organization-1",
    memberId = "member-editor",
    allocatedByMemberId = "member-manager",
    periodStart = "2026-07-01",
    periodEnd = "2026-07-31",
    allocatedXof = allocatedXof,
    spentXof = 12_500,
)
