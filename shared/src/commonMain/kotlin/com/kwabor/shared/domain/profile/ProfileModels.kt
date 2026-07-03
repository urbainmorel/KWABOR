package com.kwabor.shared.domain.profile

import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.money.KwaborCurrency

enum class UserRole {
    User,
    Guide,
    Institution,
    Promoter,
    Admin,
}

enum class RoleVerificationStatus {
    Unverified,
    Pending,
    Verified,
    Rejected,
}

data class UserRoleAssignment(
    val role: UserRole,
    val verificationStatus: RoleVerificationStatus,
)

data class UserProfile(
    val userId: String,
    val firstName: String,
    val lastName: String,
    val avatarUrl: String?,
    val coverUrl: String?,
    val bio: String?,
    val cityId: String?,
    val preferredLocale: AppLocale,
    val preferredCurrency: KwaborCurrency,
    val roles: List<UserRoleAssignment>,
)

data class PublicProfile(
    val userId: String,
    val displayName: String,
    val avatarUrl: String?,
    val coverUrl: String?,
    val bio: String?,
    val roles: List<UserRoleAssignment>,
    val followersCount: Int,
    val followingCount: Int,
)
