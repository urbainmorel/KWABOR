package com.kwabor.shared.domain.organization

import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.money.MoneyXof
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OrganizationRequestsTest {
    @Test
    fun inviteRequest_normalizesEmail() {
        val result = OrganizationInviteRequest.create(
            organizationId = "organization-1",
            invitedByMemberId = "member-owner",
            email = "  Editor@Kwabor.Test  ",
            proposedRole = OrganizationRole.Editor,
            expiresAtEpochMilliseconds = 2_000,
            nowEpochMilliseconds = 1_000,
        )

        val success = assertIs<DomainResult.Success<OrganizationInviteRequest>>(result)
        assertEquals("member-owner", success.value.invitedByMemberId)
        assertEquals("editor@kwabor.test", success.value.email)
    }

    @Test
    fun inviteRequest_rejectsOwnerInvitation() {
        val result = OrganizationInviteRequest.create(
            organizationId = "organization-1",
            invitedByMemberId = "member-owner",
            email = "owner@kwabor.test",
            proposedRole = OrganizationRole.Owner,
            expiresAtEpochMilliseconds = 2_000,
            nowEpochMilliseconds = 1_000,
        )

        assertIs<DomainResult.Failure>(result)
    }

    @Test
    fun inviteRequest_rejectsExpiredInvitation() {
        val result = OrganizationInviteRequest.create(
            organizationId = "organization-1",
            invitedByMemberId = "member-owner",
            email = "editor@kwabor.test",
            proposedRole = OrganizationRole.Editor,
            expiresAtEpochMilliseconds = 1_000,
            nowEpochMilliseconds = 1_000,
        )

        assertIs<DomainResult.Failure>(result)
    }

    @Test
    fun memberRoleUpdate_rejectsOwnerTransferThroughRoleUpdate() {
        val result = OrganizationMemberRoleUpdate.create(
            organizationId = "organization-1",
            memberId = "member-1",
            newRole = OrganizationRole.Owner,
        )

        assertIs<DomainResult.Failure>(result)
    }

    @Test
    fun budgetAllocation_acceptsEditorBudget() {
        val money = assertIs<DomainResult.Success<MoneyXof>>(MoneyXof.fromAmount(10_000)).value

        val result = MemberAdBudgetAllocationRequest.create(
            organizationId = "organization-1",
            memberId = "member-editor",
            allocatedByMemberId = "member-manager",
            memberRole = OrganizationRole.Editor,
            periodStartEpochDay = 20_000,
            periodEndEpochDay = 20_030,
            allocatedXof = money,
        )

        assertIs<DomainResult.Success<MemberAdBudgetAllocationRequest>>(result)
    }

    @Test
    fun budgetAllocation_rejectsModeratorBudget() {
        val money = assertIs<DomainResult.Success<MoneyXof>>(MoneyXof.fromAmount(10_000)).value

        val result = MemberAdBudgetAllocationRequest.create(
            organizationId = "organization-1",
            memberId = "member-moderator",
            allocatedByMemberId = "member-manager",
            memberRole = OrganizationRole.Moderator,
            periodStartEpochDay = 20_000,
            periodEndEpochDay = 20_030,
            allocatedXof = money,
        )

        assertIs<DomainResult.Failure>(result)
    }

    @Test
    fun budgetAllocation_rejectsZeroBudget() {
        val money = assertIs<DomainResult.Success<MoneyXof>>(MoneyXof.fromAmount(0)).value

        val result = MemberAdBudgetAllocationRequest.create(
            organizationId = "organization-1",
            memberId = "member-editor",
            allocatedByMemberId = "member-manager",
            memberRole = OrganizationRole.Editor,
            periodStartEpochDay = 20_000,
            periodEndEpochDay = 20_030,
            allocatedXof = money,
        )

        assertIs<DomainResult.Failure>(result)
    }

    @Test
    fun budgetAllocation_rejectsInvalidPeriod() {
        val money = assertIs<DomainResult.Success<MoneyXof>>(MoneyXof.fromAmount(10_000)).value

        val result = MemberAdBudgetAllocationRequest.create(
            organizationId = "organization-1",
            memberId = "member-editor",
            allocatedByMemberId = "member-manager",
            memberRole = OrganizationRole.Editor,
            periodStartEpochDay = 20_030,
            periodEndEpochDay = 20_000,
            allocatedXof = money,
        )

        assertIs<DomainResult.Failure>(result)
    }

    @Test
    fun budgetAllocation_rejectsSelfAllocation() {
        val money = assertIs<DomainResult.Success<MoneyXof>>(MoneyXof.fromAmount(10_000)).value

        val result = MemberAdBudgetAllocationRequest.create(
            organizationId = "organization-1",
            memberId = "member-manager",
            allocatedByMemberId = "member-manager",
            memberRole = OrganizationRole.Manager,
            periodStartEpochDay = 20_000,
            periodEndEpochDay = 20_030,
            allocatedXof = money,
        )

        assertIs<DomainResult.Failure>(result)
    }
}
