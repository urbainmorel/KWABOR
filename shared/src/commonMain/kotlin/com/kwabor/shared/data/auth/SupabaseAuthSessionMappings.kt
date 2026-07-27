package com.kwabor.shared.data.auth

import com.kwabor.shared.domain.auth.AuthSessionPurpose
import com.kwabor.shared.domain.auth.AuthenticationMethod
import com.kwabor.shared.domain.auth.SocialAuthProvider
import io.github.jan.supabase.auth.providers.Apple
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.IDTokenProvider
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant

internal suspend fun UserSession.toDtoWithServerStatus(
    postgrest: Postgrest,
    purpose: AuthSessionPurpose,
    authenticationMethod: AuthenticationMethod? = null,
    suggestedFirstName: String? = null,
    suggestedLastName: String? = null,
): AuthSessionDto {
    val sessionUser = user ?: throw AuthDataException.AuthenticationRequired()
    val profile = postgrest.from(PROFILES)
        .select {
            filter { eq("user_id", sessionUser.id) }
            limit(1)
        }
        .decodeList<OnboardingProfileStatusDto>()
        .firstOrNull()
    return toDto(
        onboardingCompleted = profile?.onboardingCompletedAt != null,
        purpose = purpose,
        authenticationMethod = authenticationMethod,
        suggestedFirstName = suggestedFirstName,
        suggestedLastName = suggestedLastName,
    )
}

internal fun UserSession.toDto(
    onboardingCompleted: Boolean,
    purpose: AuthSessionPurpose,
    authenticationMethod: AuthenticationMethod? = null,
    suggestedFirstName: String? = null,
    suggestedLastName: String? = null,
): AuthSessionDto {
    val sessionUser = user ?: throw AuthDataException.AuthenticationRequired()
    return AuthSessionDto(
        userId = sessionUser.id,
        email = sessionUser.email,
        expiresAtEpochMilliseconds = expiresAt.toEpochMilliseconds(),
        onboardingCompleted = onboardingCompleted,
        purpose = purpose,
        authenticationMethod = authenticationMethod ?: sessionUser.authenticationMethod(),
        suggestedFirstName = suggestedFirstName ?: sessionUser.metadataValue("given_name"),
        suggestedLastName = suggestedLastName ?: sessionUser.metadataValue("family_name"),
    )
}

internal fun SocialAuthProvider.toSupabaseProvider(): IDTokenProvider = when (this) {
    SocialAuthProvider.Google -> Google
    SocialAuthProvider.Apple -> Apple
}

internal fun SocialAuthProvider.toAuthenticationMethod(): AuthenticationMethod = when (this) {
    SocialAuthProvider.Google -> AuthenticationMethod.Google
    SocialAuthProvider.Apple -> AuthenticationMethod.Apple
}

private fun UserInfo.authenticationMethod(): AuthenticationMethod =
    when (appMetadata?.get("provider")?.jsonPrimitive?.contentOrNull) {
        "google" -> AuthenticationMethod.Google
        "apple" -> AuthenticationMethod.Apple
        else -> AuthenticationMethod.Email
    }

private fun UserInfo.metadataValue(key: String): String? =
    userMetadata?.get(key)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)

internal fun String.toEpochMilliseconds(): Long = try {
    Instant.parse(this).toEpochMilliseconds()
} catch (exception: IllegalArgumentException) {
    throw AuthDataException.Unexpected(exception)
}

internal fun invalidDatabaseValue(fieldName: String, value: String): Nothing = throw AuthDataException.Unexpected(
    IllegalStateException("Invalid database value for $fieldName: $value"),
)

@Serializable
internal data class OnboardingProfileStatusDto(
    @SerialName("user_id")
    val userId: String,
    @SerialName("onboarding_completed_at")
    val onboardingCompletedAt: String? = null,
)

private const val PROFILES = "profiles"
