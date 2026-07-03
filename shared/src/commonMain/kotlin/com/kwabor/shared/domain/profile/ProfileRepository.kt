package com.kwabor.shared.domain.profile

import com.kwabor.shared.domain.core.DomainResult

interface ProfileRepository {
    suspend fun getCurrentProfile(): DomainResult<UserProfile>

    suspend fun getPublicProfile(userId: String): DomainResult<PublicProfile>

    suspend fun updateProfile(profile: UserProfile): DomainResult<UserProfile>

    suspend fun listRoles(userId: String): DomainResult<List<UserRoleAssignment>>
}
