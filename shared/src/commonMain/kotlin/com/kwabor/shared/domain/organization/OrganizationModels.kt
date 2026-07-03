package com.kwabor.shared.domain.organization

import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.money.MoneyXof

enum class OrganizationType {
    Promoter,
    Institution,
    KwaborAdmin,
    Establishment,
}

enum class OrganizationVerificationStatus {
    Unverified,
    Pending,
    Verified,
    Rejected,
    Suspended,
}

enum class OrganizationRole {
    Moderator,
    Editor,
    Manager,
    Owner,
}

val OrganizationRole.rank: Int
    get() = when (this) {
        OrganizationRole.Moderator -> 10
        OrganizationRole.Editor -> 20
        OrganizationRole.Manager -> 30
        OrganizationRole.Owner -> 40
    }

fun OrganizationRole.includes(requiredRole: OrganizationRole): Boolean = rank >= requiredRole.rank

val OrganizationRole.canReplyToCustomers: Boolean
    get() = includes(OrganizationRole.Moderator)

val OrganizationRole.canEditOrganizationContent: Boolean
    get() = includes(OrganizationRole.Editor)

val OrganizationRole.canManageTeam: Boolean
    get() = includes(OrganizationRole.Manager)

val OrganizationRole.canManageGlobalBudget: Boolean
    get() = this == OrganizationRole.Owner

fun OrganizationRole.canAssign(targetRole: OrganizationRole): Boolean = when (this) {
    OrganizationRole.Owner -> targetRole in setOf(
        OrganizationRole.Manager,
        OrganizationRole.Editor,
        OrganizationRole.Moderator,
    )
    OrganizationRole.Manager -> targetRole in setOf(OrganizationRole.Editor, OrganizationRole.Moderator)
    OrganizationRole.Editor,
    OrganizationRole.Moderator,
    -> false
}

fun OrganizationRole.canAllocateBudgetTo(targetRole: OrganizationRole): Boolean = when (this) {
    OrganizationRole.Owner -> targetRole in setOf(OrganizationRole.Manager, OrganizationRole.Editor)
    OrganizationRole.Manager -> targetRole == OrganizationRole.Editor
    OrganizationRole.Editor,
    OrganizationRole.Moderator,
    -> false
}

enum class OrganizationMemberStatus {
    Invited,
    Active,
    Suspended,
    Removed,
}

enum class OrganizationInviteStatus {
    Pending,
    Accepted,
    Revoked,
    Expired,
}

data class Organization(
    val id: String,
    val type: OrganizationType,
    val name: String,
    val slug: String,
    val verificationStatus: OrganizationVerificationStatus,
    val primaryOwnerId: String,
)

data class OrganizationMember(
    val id: String,
    val organizationId: String,
    val userId: String,
    val role: OrganizationRole,
    val status: OrganizationMemberStatus,
    val acceptedAtEpochMilliseconds: Long?,
)

data class OrganizationInvite(
    val id: String,
    val organizationId: String,
    val email: String,
    val proposedRole: OrganizationRole,
    val invitedByMemberId: String,
    val status: OrganizationInviteStatus,
    val expiresAtEpochMilliseconds: Long,
    val acceptedAtEpochMilliseconds: Long?,
)

data class MemberAdBudget(
    val id: String,
    val organizationId: String,
    val memberId: String,
    val allocatedByMemberId: String,
    val periodStartEpochDay: Int,
    val periodEndEpochDay: Int,
    val allocatedXof: MoneyXof,
    val spentXof: MoneyXof,
)

class OrganizationInviteRequest private constructor(
    val organizationId: String,
    val invitedByMemberId: String,
    val email: String,
    val proposedRole: OrganizationRole,
    val expiresAtEpochMilliseconds: Long,
) {
    companion object {
        fun create(
            organizationId: String,
            invitedByMemberId: String,
            email: String,
            proposedRole: OrganizationRole,
            expiresAtEpochMilliseconds: Long,
            nowEpochMilliseconds: Long,
        ): DomainResult<OrganizationInviteRequest> {
            if (organizationId.isBlank() || invitedByMemberId.isBlank()) {
                return DomainResult.Failure(DomainError.Validation("error.organization.member_required"))
            }

            val normalizedEmail = email.trim().lowercase()
            if (!normalizedEmail.looksLikeEmail()) {
                return DomainResult.Failure(DomainError.Validation("error.organization.invite_email_invalid"))
            }

            if (proposedRole == OrganizationRole.Owner) {
                return DomainResult.Failure(DomainError.Validation("error.organization.owner_invite_forbidden"))
            }

            if (expiresAtEpochMilliseconds <= nowEpochMilliseconds) {
                return DomainResult.Failure(DomainError.Validation("error.organization.invite_expired"))
            }

            return DomainResult.Success(
                OrganizationInviteRequest(
                    organizationId = organizationId,
                    invitedByMemberId = invitedByMemberId,
                    email = normalizedEmail,
                    proposedRole = proposedRole,
                    expiresAtEpochMilliseconds = expiresAtEpochMilliseconds,
                ),
            )
        }
    }

    override fun equals(other: Any?): Boolean = other is OrganizationInviteRequest &&
        organizationId == other.organizationId &&
        invitedByMemberId == other.invitedByMemberId &&
        email == other.email &&
        proposedRole == other.proposedRole &&
        expiresAtEpochMilliseconds == other.expiresAtEpochMilliseconds

    override fun hashCode(): Int {
        var result = organizationId.hashCode()
        result = 31 * result + invitedByMemberId.hashCode()
        result = 31 * result + email.hashCode()
        result = 31 * result + proposedRole.hashCode()
        result = 31 * result + expiresAtEpochMilliseconds.hashCode()
        return result
    }

    override fun toString(): String =
        "OrganizationInviteRequest(organizationId=$organizationId, invitedByMemberId=$invitedByMemberId, " +
            "email=$email, proposedRole=$proposedRole, expiresAt=$expiresAtEpochMilliseconds)"
}

class OrganizationMemberRoleUpdate private constructor(
    val organizationId: String,
    val memberId: String,
    val newRole: OrganizationRole,
) {
    companion object {
        fun create(
            organizationId: String,
            memberId: String,
            newRole: OrganizationRole,
        ): DomainResult<OrganizationMemberRoleUpdate> {
            if (organizationId.isBlank() || memberId.isBlank()) {
                return DomainResult.Failure(DomainError.Validation("error.organization.member_required"))
            }

            if (newRole == OrganizationRole.Owner) {
                return DomainResult.Failure(DomainError.Validation("error.organization.owner_transfer_required"))
            }

            return DomainResult.Success(
                OrganizationMemberRoleUpdate(
                    organizationId = organizationId,
                    memberId = memberId,
                    newRole = newRole,
                ),
            )
        }
    }

    override fun equals(other: Any?): Boolean = other is OrganizationMemberRoleUpdate &&
        organizationId == other.organizationId &&
        memberId == other.memberId &&
        newRole == other.newRole

    override fun hashCode(): Int {
        var result = organizationId.hashCode()
        result = 31 * result + memberId.hashCode()
        result = 31 * result + newRole.hashCode()
        return result
    }

    override fun toString(): String =
        "OrganizationMemberRoleUpdate(organizationId=$organizationId, memberId=$memberId, newRole=$newRole)"
}

class MemberAdBudgetAllocationRequest private constructor(
    val organizationId: String,
    val memberId: String,
    val allocatedByMemberId: String,
    val memberRole: OrganizationRole,
    val periodStartEpochDay: Int,
    val periodEndEpochDay: Int,
    val allocatedXof: MoneyXof,
) {
    companion object {
        fun create(
            organizationId: String,
            memberId: String,
            allocatedByMemberId: String,
            memberRole: OrganizationRole,
            periodStartEpochDay: Int,
            periodEndEpochDay: Int,
            allocatedXof: MoneyXof,
        ): DomainResult<MemberAdBudgetAllocationRequest> {
            if (organizationId.isBlank() || memberId.isBlank() || allocatedByMemberId.isBlank()) {
                return DomainResult.Failure(DomainError.Validation("error.organization.member_required"))
            }

            if (memberId == allocatedByMemberId) {
                return DomainResult.Failure(
                    DomainError.Validation("error.organization.budget_self_allocation_forbidden"),
                )
            }

            if (memberRole == OrganizationRole.Moderator || memberRole == OrganizationRole.Owner) {
                return DomainResult.Failure(DomainError.Validation("error.organization.budget_role_forbidden"))
            }

            if (periodEndEpochDay < periodStartEpochDay) {
                return DomainResult.Failure(DomainError.Validation("error.organization.budget_period_invalid"))
            }

            if (allocatedXof.amount <= 0) {
                return DomainResult.Failure(DomainError.Validation("error.organization.budget_positive_required"))
            }

            return DomainResult.Success(
                MemberAdBudgetAllocationRequest(
                    organizationId = organizationId,
                    memberId = memberId,
                    allocatedByMemberId = allocatedByMemberId,
                    memberRole = memberRole,
                    periodStartEpochDay = periodStartEpochDay,
                    periodEndEpochDay = periodEndEpochDay,
                    allocatedXof = allocatedXof,
                ),
            )
        }
    }

    override fun equals(other: Any?): Boolean = other is MemberAdBudgetAllocationRequest &&
        organizationId == other.organizationId &&
        memberId == other.memberId &&
        allocatedByMemberId == other.allocatedByMemberId &&
        memberRole == other.memberRole &&
        periodStartEpochDay == other.periodStartEpochDay &&
        periodEndEpochDay == other.periodEndEpochDay &&
        allocatedXof == other.allocatedXof

    override fun hashCode(): Int {
        var result = organizationId.hashCode()
        result = 31 * result + memberId.hashCode()
        result = 31 * result + allocatedByMemberId.hashCode()
        result = 31 * result + memberRole.hashCode()
        result = 31 * result + periodStartEpochDay
        result = 31 * result + periodEndEpochDay
        result = 31 * result + allocatedXof.hashCode()
        return result
    }

    override fun toString(): String =
        "MemberAdBudgetAllocationRequest(organizationId=$organizationId, memberId=$memberId, " +
            "allocatedByMemberId=$allocatedByMemberId, memberRole=$memberRole, periodStart=$periodStartEpochDay, " +
            "periodEnd=$periodEndEpochDay, allocatedXof=$allocatedXof)"
}

private fun String.looksLikeEmail(): Boolean {
    val atIndex = indexOf('@')
    val dotAfterAt = indexOf('.', startIndex = atIndex + 2)
    return atIndex > 0 && dotAfterAt > atIndex + 1 && dotAfterAt < lastIndex
}
