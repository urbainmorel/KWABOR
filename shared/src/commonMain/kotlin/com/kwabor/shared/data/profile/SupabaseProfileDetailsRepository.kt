package com.kwabor.shared.data.profile

import com.kwabor.shared.data.auth.AuthDataException
import com.kwabor.shared.data.auth.runAuthCall
import com.kwabor.shared.data.auth.runAuthRequest
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.profile.ProfileDetails
import com.kwabor.shared.domain.profile.ProfileDetailsRepository
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal class SupabaseProfileDetailsRepository(
    private val auth: Auth,
    private val postgrest: Postgrest,
) : ProfileDetailsRepository {
    override suspend fun get(ownerId: String): DomainResult<ProfileDetails> = withOwner(ownerId) {
        postgrest.from("profiles")
            .select(columns = Columns.list("first_name", "last_name", "city_id")) {
                filter { eq("user_id", ownerId) }
            }
            .decodeSingle<ProfileDetailsDto>()
            .toDomain()
    }

    override suspend fun update(ownerId: String, details: ProfileDetails): DomainResult<ProfileDetails> =
        withOwner(ownerId) {
            postgrest.rpc(
                function = "update_my_profile_details",
                parameters = UpdateProfileDetailsDto(
                    ownerId = ownerId,
                    firstName = details.firstName,
                    lastName = details.lastName,
                    cityId = details.cityId.orEmpty(),
                ),
            ).decodeSingle<ProfileDetailsDto>().toDomain()
        }

    private suspend fun withOwner(ownerId: String, block: suspend () -> ProfileDetails): DomainResult<ProfileDetails> =
        runAuthCall {
            runAuthRequest {
                requireOwner(ownerId)
                block().also { requireOwner(ownerId) }
            }
        }

    private fun requireOwner(ownerId: String) {
        if (ownerId.isBlank() || ownerId != auth.currentSessionOrNull()?.user?.id) {
            throw AuthDataException.AuthenticationRequired()
        }
    }
}

@Serializable
private data class ProfileDetailsDto(
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    @SerialName("city_id") val cityId: String? = null,
) {
    fun toDomain() = ProfileDetails(firstName.orEmpty(), lastName.orEmpty(), cityId)
}

@Serializable
private data class UpdateProfileDetailsDto(
    @SerialName("p_expected_user_id") val ownerId: String,
    @SerialName("p_first_name") val firstName: String,
    @SerialName("p_last_name") val lastName: String,
    @SerialName("p_city_id") val cityId: String,
)
