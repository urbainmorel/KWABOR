package com.kwabor.shared.domain.profile

import com.kwabor.shared.domain.core.DomainResult

data class ProfileDetails(
    val firstName: String = "",
    val lastName: String = "",
    val cityId: String? = null,
)

interface ProfileDetailsRepository {
    suspend fun get(ownerId: String): DomainResult<ProfileDetails>
    suspend fun update(ownerId: String, details: ProfileDetails): DomainResult<ProfileDetails>
}
