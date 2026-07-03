package com.kwabor.shared.data.organization

import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.money.MoneyXof
import com.kwabor.shared.domain.organization.MemberAdBudgetAllocationRequest
import com.kwabor.shared.domain.organization.OrganizationInviteRequest
import com.kwabor.shared.domain.organization.OrganizationMemberRoleUpdate
import com.kwabor.shared.domain.organization.OrganizationRole
import com.kwabor.shared.domain.organization.OrganizationType
import com.kwabor.shared.domain.organization.OrganizationVerificationStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class OrganizationDtoMappingTest {
    @Test
    fun organizationDto_mapsDatabaseEnumsToDomain() {
        val dto = OrganizationDto(
            id = "organization-1",
            type = "institution",
            name = "Mairie de Cotonou",
            slug = "mairie-cotonou",
            verificationStatus = "verified",
            primaryOwnerId = "user-owner",
        )

        val domain = dto.toDomain()

        assertEquals(OrganizationType.Institution, domain.type)
        assertEquals(OrganizationVerificationStatus.Verified, domain.verificationStatus)
    }

    @Test
    fun memberDto_mapsRoleStatusAndAcceptedAt() {
        val dto = OrganizationMemberDto(
            id = "member-1",
            organizationId = "organization-1",
            userId = "user-1",
            role = "gestionnaire",
            status = "active",
            acceptedAt = "2026-07-03T10:15:30Z",
        )

        val domain = dto.toDomain()

        assertEquals(OrganizationRole.Manager, domain.role)
        assertEquals(1_783_073_730_000, domain.acceptedAtEpochMilliseconds)
    }

    @Test
    fun budgetDto_mapsDatesAndMoney() {
        val dto = MemberAdBudgetDto(
            id = "budget-1",
            organizationId = "organization-1",
            memberId = "member-editor",
            allocatedByMemberId = "member-manager",
            periodStart = "2026-07-01",
            periodEnd = "2026-07-31",
            allocatedXof = 50_000,
            spentXof = 12_500,
        )

        val domain = dto.toDomain()

        assertEquals(20_635, domain.periodStartEpochDay)
        assertEquals(20_665, domain.periodEndEpochDay)
        assertEquals(50_000, domain.allocatedXof.amount)
        assertEquals(12_500, domain.spentXof.amount)
    }

    @Test
    fun budgetDto_rejectsSpentAmountAboveAllocation() {
        val dto = MemberAdBudgetDto(
            id = "budget-1",
            organizationId = "organization-1",
            memberId = "member-editor",
            allocatedByMemberId = "member-manager",
            periodStart = "2026-07-01",
            periodEnd = "2026-07-31",
            allocatedXof = 50_000,
            spentXof = 50_001,
        )

        assertFailsWith<IllegalStateException> {
            dto.toDomain()
        }
    }

    @Test
    fun inviteRpcDto_serializesRoleAndExpiryForSupabase() {
        val request = assertIs<DomainResult.Success<OrganizationInviteRequest>>(
            OrganizationInviteRequest.create(
                organizationId = "organization-1",
                invitedByMemberId = "member-owner",
                email = "editor@kwabor.test",
                proposedRole = OrganizationRole.Editor,
                expiresAtEpochMilliseconds = 1_783_073_730_000,
                nowEpochMilliseconds = 1_783_070_000_000,
            ),
        ).value

        val dto = request.toRpcDto()

        assertEquals("organization-1", dto.organizationId)
        assertEquals("member-owner", dto.invitedByMemberId)
        assertEquals("editor@kwabor.test", dto.email)
        assertEquals("editeur", dto.proposedRole)
        assertEquals("2026-07-03T10:15:30Z", dto.expiresAt)
    }

    @Test
    fun memberRolePatchDto_serializesRoleForSupabaseUpdate() {
        val request = assertIs<DomainResult.Success<OrganizationMemberRoleUpdate>>(
            OrganizationMemberRoleUpdate.create(
                organizationId = "organization-1",
                memberId = "member-editor",
                newRole = OrganizationRole.Manager,
            ),
        ).value

        val dto = request.toPatchDto()

        assertEquals("gestionnaire", dto.role)
    }

    @Test
    fun budgetCommandDto_serializesDatesAndAmountForSupabase() {
        val money = assertIs<DomainResult.Success<MoneyXof>>(MoneyXof.fromAmount(50_000)).value
        val request = assertIs<DomainResult.Success<MemberAdBudgetAllocationRequest>>(
            MemberAdBudgetAllocationRequest.create(
                organizationId = "organization-1",
                memberId = "member-editor",
                allocatedByMemberId = "member-manager",
                memberRole = OrganizationRole.Editor,
                periodStartEpochDay = 20_635,
                periodEndEpochDay = 20_665,
                allocatedXof = money,
            ),
        ).value

        val dto = request.toCommandDto()

        assertEquals("2026-07-01", dto.periodStart)
        assertEquals("2026-07-31", dto.periodEnd)
        assertEquals(50_000, dto.allocatedXof)
    }
}
