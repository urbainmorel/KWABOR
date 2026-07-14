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

private const val MODERATOR_ROLE_RANK = 10
private const val EDITOR_ROLE_RANK = 20
private const val MANAGER_ROLE_RANK = 30
private const val OWNER_ROLE_RANK = 40

val OrganizationRole.rank: Int
    get() = when (this) {
        OrganizationRole.Moderator -> MODERATOR_ROLE_RANK
        OrganizationRole.Editor -> EDITOR_ROLE_RANK
        OrganizationRole.Manager -> MANAGER_ROLE_RANK
        OrganizationRole.Owner -> OWNER_ROLE_RANK
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

data class OrganizationInviteValues(
    val organizationId: String,
    val invitedByMemberId: String,
    val email: String,
    val proposedRole: OrganizationRole,
    val expiresAtEpochMilliseconds: Long,
    val nowEpochMilliseconds: Long,
)

class OrganizationInviteRequest private constructor(
    val organizationId: String,
    val invitedByMemberId: String,
    val email: String,
    val proposedRole: OrganizationRole,
    val expiresAtEpochMilliseconds: Long,
) {
    companion object {
        fun create(values: OrganizationInviteValues): DomainResult<OrganizationInviteRequest> {
            if (values.organizationId.isBlank() || values.invitedByMemberId.isBlank()) {
                return DomainResult.Failure(DomainError.Validation("error.organization.member_required"))
            }

            val normalizedEmail = values.email.trim().lowercase()
            if (!normalizedEmail.looksLikeEmail()) {
                return DomainResult.Failure(DomainError.Validation("error.organization.invite_email_invalid"))
            }

            if (values.proposedRole == OrganizationRole.Owner) {
                return DomainResult.Failure(DomainError.Validation("error.organization.owner_invite_forbidden"))
            }

            if (values.expiresAtEpochMilliseconds <= values.nowEpochMilliseconds) {
                return DomainResult.Failure(DomainError.Validation("error.organization.invite_expired"))
            }

            return DomainResult.Success(
                OrganizationInviteRequest(
                    organizationId = values.organizationId,
                    invitedByMemberId = values.invitedByMemberId,
                    email = normalizedEmail,
                    proposedRole = values.proposedRole,
                    expiresAtEpochMilliseconds = values.expiresAtEpochMilliseconds,
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

data class MemberAdBudgetAllocationValues(
    val organizationId: String,
    val memberId: String,
    val allocatedByMemberId: String,
    val memberRole: OrganizationRole,
    val periodStartEpochDay: Int,
    val periodEndEpochDay: Int,
    val allocatedXof: MoneyXof,
)

class MemberAdBudgetAllocationRequest private constructor(
    private val values: MemberAdBudgetAllocationValues,
) {
    val organizationId: String get() = values.organizationId
    val memberId: String get() = values.memberId
    val allocatedByMemberId: String get() = values.allocatedByMemberId
    val memberRole: OrganizationRole get() = values.memberRole
    val periodStartEpochDay: Int get() = values.periodStartEpochDay
    val periodEndEpochDay: Int get() = values.periodEndEpochDay
    val allocatedXof: MoneyXof get() = values.allocatedXof

    companion object {
        fun create(values: MemberAdBudgetAllocationValues): DomainResult<MemberAdBudgetAllocationRequest> {
            if (values.organizationId.isBlank() || values.memberId.isBlank() || values.allocatedByMemberId.isBlank()) {
                return DomainResult.Failure(DomainError.Validation("error.organization.member_required"))
            }

            if (values.memberId == values.allocatedByMemberId) {
                return DomainResult.Failure(
                    DomainError.Validation("error.organization.budget_self_allocation_forbidden"),
                )
            }

            if (values.memberRole == OrganizationRole.Moderator || values.memberRole == OrganizationRole.Owner) {
                return DomainResult.Failure(DomainError.Validation("error.organization.budget_role_forbidden"))
            }

            if (values.periodEndEpochDay < values.periodStartEpochDay) {
                return DomainResult.Failure(DomainError.Validation("error.organization.budget_period_invalid"))
            }

            if (values.allocatedXof.amount <= 0) {
                return DomainResult.Failure(DomainError.Validation("error.organization.budget_positive_required"))
            }

            return DomainResult.Success(MemberAdBudgetAllocationRequest(values))
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
