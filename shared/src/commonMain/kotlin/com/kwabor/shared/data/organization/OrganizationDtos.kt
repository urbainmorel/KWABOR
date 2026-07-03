package com.kwabor.shared.data.organization

import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.money.MoneyXof
import com.kwabor.shared.domain.organization.MemberAdBudget
import com.kwabor.shared.domain.organization.MemberAdBudgetAllocationRequest
import com.kwabor.shared.domain.organization.Organization
import com.kwabor.shared.domain.organization.OrganizationInvite
import com.kwabor.shared.domain.organization.OrganizationInviteRequest
import com.kwabor.shared.domain.organization.OrganizationInviteStatus
import com.kwabor.shared.domain.organization.OrganizationMember
import com.kwabor.shared.domain.organization.OrganizationMemberRoleUpdate
import com.kwabor.shared.domain.organization.OrganizationMemberStatus
import com.kwabor.shared.domain.organization.OrganizationRole
import com.kwabor.shared.domain.organization.OrganizationType
import com.kwabor.shared.domain.organization.OrganizationVerificationStatus
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
internal data class OrganizationDto(
    @SerialName("id")
    val id: String,
    @SerialName("type")
    val type: String,
    @SerialName("name")
    val name: String,
    @SerialName("slug")
    val slug: String,
    @SerialName("verification_status")
    val verificationStatus: String,
    @SerialName("primary_owner_id")
    val primaryOwnerId: String,
)

@Serializable
internal data class OrganizationMemberDto(
    @SerialName("id")
    val id: String,
    @SerialName("organization_id")
    val organizationId: String,
    @SerialName("user_id")
    val userId: String,
    @SerialName("role")
    val role: String,
    @SerialName("status")
    val status: String,
    @SerialName("accepted_at")
    val acceptedAt: String? = null,
)

@Serializable
internal data class OrganizationInviteDto(
    @SerialName("id")
    val id: String,
    @SerialName("organization_id")
    val organizationId: String,
    @SerialName("email")
    val email: String,
    @SerialName("proposed_role")
    val proposedRole: String,
    @SerialName("invited_by_member_id")
    val invitedByMemberId: String,
    @SerialName("status")
    val status: String,
    @SerialName("expires_at")
    val expiresAt: String,
    @SerialName("accepted_at")
    val acceptedAt: String? = null,
)

@Serializable
internal data class MemberAdBudgetDto(
    @SerialName("id")
    val id: String,
    @SerialName("organization_id")
    val organizationId: String,
    @SerialName("member_id")
    val memberId: String,
    @SerialName("allocated_by_member_id")
    val allocatedByMemberId: String,
    @SerialName("period_start")
    val periodStart: String,
    @SerialName("period_end")
    val periodEnd: String,
    @SerialName("allocated_xof")
    val allocatedXof: Long,
    @SerialName("spent_xof")
    val spentXof: Long,
)

@Serializable
internal data class MemberAdBudgetCommandDto(
    @SerialName("organization_id")
    val organizationId: String,
    @SerialName("member_id")
    val memberId: String,
    @SerialName("allocated_by_member_id")
    val allocatedByMemberId: String,
    @SerialName("period_start")
    val periodStart: String,
    @SerialName("period_end")
    val periodEnd: String,
    @SerialName("allocated_xof")
    val allocatedXof: Long,
)

@Serializable
internal data class CreateOrganizationInviteRpcDto(
    @SerialName("p_organization_id")
    val organizationId: String,
    @SerialName("p_invited_by_member_id")
    val invitedByMemberId: String,
    @SerialName("p_email")
    val email: String,
    @SerialName("p_proposed_role")
    val proposedRole: String,
    @SerialName("p_expires_at")
    val expiresAt: String,
)

@Serializable
internal data class AcceptOrganizationInviteRpcDto(
    @SerialName("p_invite_token")
    val inviteToken: String,
)

@Serializable
internal data class RevokeOrganizationInviteRpcDto(
    @SerialName("p_invite_id")
    val inviteId: String,
)

@Serializable
internal data class SuspendOrganizationMemberRpcDto(
    @SerialName("p_organization_id")
    val organizationId: String,
    @SerialName("p_member_id")
    val memberId: String,
)

@Serializable
internal data class OrganizationMemberRolePatchDto(
    @SerialName("role")
    val role: String,
)

internal fun OrganizationDto.toDomain(): Organization = Organization(
    id = id,
    type = type.toOrganizationType(),
    name = name,
    slug = slug,
    verificationStatus = verificationStatus.toOrganizationVerificationStatus(),
    primaryOwnerId = primaryOwnerId,
)

internal fun OrganizationMemberDto.toDomain(): OrganizationMember = OrganizationMember(
    id = id,
    organizationId = organizationId,
    userId = userId,
    role = role.toOrganizationRole(),
    status = status.toOrganizationMemberStatus(),
    acceptedAtEpochMilliseconds = acceptedAt?.toEpochMilliseconds(),
)

internal fun OrganizationInviteDto.toDomain(): OrganizationInvite = OrganizationInvite(
    id = id,
    organizationId = organizationId,
    email = email,
    proposedRole = proposedRole.toOrganizationRole(),
    invitedByMemberId = invitedByMemberId,
    status = status.toOrganizationInviteStatus(),
    expiresAtEpochMilliseconds = expiresAt.toEpochMilliseconds(),
    acceptedAtEpochMilliseconds = acceptedAt?.toEpochMilliseconds(),
)

internal fun MemberAdBudgetDto.toDomain(): MemberAdBudget {
    val allocated = allocatedXof.toPositiveMoney("member_ad_budgets.allocated_xof")
    val spent = spentXof.toNonNegativeMoney("member_ad_budgets.spent_xof")
    if (spent.amount > allocated.amount) {
        invalidDatabaseValue("member_ad_budgets.spent_xof", spentXof.toString())
    }

    return MemberAdBudget(
        id = id,
        organizationId = organizationId,
        memberId = memberId,
        allocatedByMemberId = allocatedByMemberId,
        periodStartEpochDay = periodStart.toEpochDay(),
        periodEndEpochDay = periodEnd.toEpochDay(),
        allocatedXof = allocated,
        spentXof = spent,
    )
}

internal fun OrganizationInviteRequest.toRpcDto(): CreateOrganizationInviteRpcDto = CreateOrganizationInviteRpcDto(
    organizationId = organizationId,
    invitedByMemberId = invitedByMemberId,
    email = email,
    proposedRole = proposedRole.toDatabaseValue(),
    expiresAt = Instant.fromEpochMilliseconds(expiresAtEpochMilliseconds).toString(),
)

internal fun OrganizationMemberRoleUpdate.toPatchDto(): OrganizationMemberRolePatchDto = OrganizationMemberRolePatchDto(
    role = newRole.toDatabaseValue(),
)

internal fun MemberAdBudgetAllocationRequest.toCommandDto(): MemberAdBudgetCommandDto = MemberAdBudgetCommandDto(
    organizationId = organizationId,
    memberId = memberId,
    allocatedByMemberId = allocatedByMemberId,
    periodStart = LocalDate.fromEpochDays(periodStartEpochDay).toString(),
    periodEnd = LocalDate.fromEpochDays(periodEndEpochDay).toString(),
    allocatedXof = allocatedXof.amount,
)

private fun String.toOrganizationType(): OrganizationType = when (this) {
    "promoteur" -> OrganizationType.Promoter
    "institution" -> OrganizationType.Institution
    "admin_kwabor" -> OrganizationType.KwaborAdmin
    "etablissement" -> OrganizationType.Establishment
    else -> invalidDatabaseValue("organizations.type", this)
}

private fun String.toOrganizationVerificationStatus(): OrganizationVerificationStatus = when (this) {
    "unverified" -> OrganizationVerificationStatus.Unverified
    "pending" -> OrganizationVerificationStatus.Pending
    "verified" -> OrganizationVerificationStatus.Verified
    "rejected" -> OrganizationVerificationStatus.Rejected
    "suspended" -> OrganizationVerificationStatus.Suspended
    else -> invalidDatabaseValue("organizations.verification_status", this)
}

private fun String.toOrganizationRole(): OrganizationRole = when (this) {
    "moderateur" -> OrganizationRole.Moderator
    "editeur" -> OrganizationRole.Editor
    "gestionnaire" -> OrganizationRole.Manager
    "proprietaire" -> OrganizationRole.Owner
    else -> invalidDatabaseValue("organization_members.role", this)
}

internal fun OrganizationRole.toDatabaseValue(): String = when (this) {
    OrganizationRole.Moderator -> "moderateur"
    OrganizationRole.Editor -> "editeur"
    OrganizationRole.Manager -> "gestionnaire"
    OrganizationRole.Owner -> "proprietaire"
}

private fun String.toOrganizationMemberStatus(): OrganizationMemberStatus = when (this) {
    "invited" -> OrganizationMemberStatus.Invited
    "active" -> OrganizationMemberStatus.Active
    "suspended" -> OrganizationMemberStatus.Suspended
    "removed" -> OrganizationMemberStatus.Removed
    else -> invalidDatabaseValue("organization_members.status", this)
}

private fun String.toOrganizationInviteStatus(): OrganizationInviteStatus = when (this) {
    "pending" -> OrganizationInviteStatus.Pending
    "accepted" -> OrganizationInviteStatus.Accepted
    "revoked" -> OrganizationInviteStatus.Revoked
    "expired" -> OrganizationInviteStatus.Expired
    else -> invalidDatabaseValue("organization_invites.status", this)
}

private fun String.toEpochMilliseconds(): Long = Instant.parse(this).toEpochMilliseconds()

private fun String.toEpochDay(): Int {
    val epochDay = LocalDate.parse(this).toEpochDays()
    if (epochDay !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        invalidDatabaseValue("date", this)
    }
    return epochDay.toInt()
}

private fun Long.toPositiveMoney(fieldName: String): MoneyXof {
    if (this <= 0) {
        invalidDatabaseValue(fieldName, toString())
    }
    return toNonNegativeMoney(fieldName)
}

private fun Long.toNonNegativeMoney(fieldName: String): MoneyXof = when (val result = MoneyXof.fromAmount(this)) {
    is DomainResult.Success -> result.value
    is DomainResult.Failure -> invalidDatabaseValue(fieldName, toString())
}

private fun invalidDatabaseValue(fieldName: String, value: String): Nothing {
    error("Invalid database value for $fieldName: $value")
}
