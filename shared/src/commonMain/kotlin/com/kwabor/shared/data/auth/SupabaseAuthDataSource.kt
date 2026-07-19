package com.kwabor.shared.data.auth

import com.kwabor.shared.domain.auth.AUTH_EMAIL_NOT_CONFIRMED_ERROR_KEY
import com.kwabor.shared.domain.auth.AUTH_INVALID_CREDENTIALS_ERROR_KEY
import com.kwabor.shared.domain.auth.AUTH_OTP_EXPIRED_ERROR_KEY
import com.kwabor.shared.domain.auth.AUTH_PASSWORD_RECOVERY_REQUIRED_ERROR_KEY
import com.kwabor.shared.domain.auth.AUTH_PASSWORD_SAME_ERROR_KEY
import com.kwabor.shared.domain.auth.AUTH_PASSWORD_TOO_WEAK_ERROR_KEY
import com.kwabor.shared.domain.auth.AUTH_RATE_LIMITED_ERROR_KEY
import com.kwabor.shared.domain.auth.AuthSessionPurpose
import com.kwabor.shared.domain.auth.CompleteOnboardingRequest
import com.kwabor.shared.domain.auth.LegalDocumentRevision
import com.kwabor.shared.domain.auth.LegalDocumentType
import com.kwabor.shared.domain.auth.SocialAuthProvider
import com.kwabor.shared.domain.auth.SocialSignInRequest
import com.kwabor.shared.domain.i18n.AppLocale
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.OtpVerifyResult
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.exception.AuthSessionMissingException
import io.github.jan.supabase.auth.exception.AuthWeakPasswordException
import io.github.jan.supabase.auth.providers.Apple
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.IDTokenProvider
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlin.time.Instant

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_CONFLICT = 409
private const val HTTP_UNPROCESSABLE_CONTENT = 422
private const val HTTP_TOO_MANY_REQUESTS = 429

internal class SupabaseAuthDataSource(
    private val auth: Auth,
    private val postgrest: Postgrest,
    private val passwordRecoverySessionStore: PasswordRecoverySessionStore,
) : AuthDataSource {
    private val passwordRecoverySessionCoordinator =
        PasswordRecoverySessionCoordinator(passwordRecoverySessionStore)

    override suspend fun getCurrentSession(): AuthSessionDto? = runAuthRequest {
        auth.awaitInitialization()
        if (passwordRecoverySessionStore.isPasswordRecoveryInProgress()) {
            passwordRecoverySessionCoordinator.restoreRecoverySessionOrNull(
                currentSession = auth.currentSessionOrNull(),
                loadStoredSession = auth.sessionManager::loadSessionOrNull,
                clearCurrentSession = auth::clearSession,
            )?.toDto(
                onboardingCompleted = false,
                purpose = AuthSessionPurpose.PasswordRecovery,
            )
        } else {
            auth.currentSessionOrNull()?.toDtoWithServerStatus(AuthSessionPurpose.Standard)
        }
    }

    override suspend fun requestEmailOtp(email: String): Unit = runAuthRequest {
        auth.signInWith(OTP) {
            this.email = email
            createUser = true
        }
    }

    override suspend fun verifyEmailOtp(email: String, otpCode: String): AuthSessionDto = runAuthRequest {
        val session = auth.verifyEmailOtpForSession(email = email, otpCode = otpCode)
        passwordRecoverySessionStore.clearPasswordRecovery()
        session.toDtoWithServerStatus(AuthSessionPurpose.Standard)
    }

    override suspend fun setInitialPassword(password: String): Unit = runAuthRequest {
        auth.updateUser(updateCurrentUser = true) {
            this.password = password
        }
    }

    override suspend fun listActiveLegalDocuments(locale: AppLocale): List<LegalDocumentRevision> = runAuthRequest {
        postgrest.from(LEGAL_DOCUMENTS)
            .select {
                filter {
                    eq("active", true)
                    eq("locale", locale.tag)
                }
                order("document_type", Order.ASCENDING)
            }
            .decodeList<LegalDocumentRevisionDto>()
            .map { revision -> revision.toDomain() }
    }

    override suspend fun completeOnboarding(request: CompleteOnboardingRequest): AuthSessionDto = runAuthRequest {
        val completedProfile = postgrest.rpc(
            function = COMPLETE_ONBOARDING_RPC,
            parameters = request.toRpcDto(),
        ).decodeSingle<OnboardingProfileStatusDto>()
        val session = auth.currentSessionOrNull() ?: throw AuthDataException.AuthenticationRequired()
        if (completedProfile.userId != session.user?.id || completedProfile.onboardingCompletedAt == null) {
            throw AuthDataException.Unexpected()
        }
        session.toDto(
            onboardingCompleted = true,
            purpose = AuthSessionPurpose.Standard,
        )
    }

    override suspend fun signInWithEmail(email: String, password: String): AuthSessionDto = runAuthRequest {
        auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
        passwordRecoverySessionStore.clearPasswordRecovery()
        (auth.currentSessionOrNull() ?: throw AuthDataException.AuthenticationRequired()).toDtoWithServerStatus(
            AuthSessionPurpose.Standard,
        )
    }

    override suspend fun requestPasswordRecovery(email: String): Unit = runAuthRequest {
        try {
            auth.resetPasswordForEmail(email = email, redirectUrl = null)
        } catch (exception: AuthRestException) {
            if (!exception.isUnknownAccountError()) throw exception
        }
    }

    override suspend fun verifyPasswordRecoveryOtp(email: String, otpCode: String): AuthSessionDto = runAuthRequest {
        val session = passwordRecoverySessionCoordinator.establishRecoverySession(
            clearCurrentSession = auth::clearSession,
        ) {
            auth.verifyPasswordRecoveryOtpForSession(email = email, otpCode = otpCode)
        }
        session.toDtoWithServerStatus(AuthSessionPurpose.PasswordRecovery)
    }

    override suspend fun completePasswordRecovery(newPassword: String): Unit = runAuthRequest {
        passwordRecoverySessionCoordinator.completeRecoverySession(
            hasCurrentSession = { auth.currentSessionOrNull() != null },
            missingSessionError = {
                AuthDataException.AuthenticationRequired(AUTH_PASSWORD_RECOVERY_REQUIRED_ERROR_KEY)
            },
            updatePassword = {
                auth.updateUser(updateCurrentUser = true, redirectUrl = null) {
                    password = newPassword
                }
            },
            clearCurrentSession = auth::clearSession,
        )
    }

    override suspend fun cancelPasswordRecovery(): Unit = runAuthRequest {
        passwordRecoverySessionCoordinator.cancelRecoverySession(auth::clearSession)
    }

    override suspend fun signInWithSocialProvider(request: SocialSignInRequest): AuthSessionDto = runAuthRequest {
        auth.signInWith(IDToken) {
            idToken = request.idToken
            provider = request.provider.toSupabaseProvider()
        }
        passwordRecoverySessionStore.clearPasswordRecovery()
        (auth.currentSessionOrNull() ?: throw AuthDataException.AuthenticationRequired()).toDtoWithServerStatus(
            AuthSessionPurpose.Standard,
        )
    }

    override suspend fun signOut(): Unit = runAuthRequest {
        passwordRecoverySessionCoordinator.signOut {
            auth.signOut(SignOutScope.LOCAL)
        }
    }

    private suspend fun UserSession.toDtoWithServerStatus(purpose: AuthSessionPurpose): AuthSessionDto {
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
        )
    }
}

private suspend fun Auth.verifyEmailOtpForSession(email: String, otpCode: String): UserSession =
    when (val result = verifyEmailOtp(OtpType.Email.EMAIL, email = email, token = otpCode)) {
        is OtpVerifyResult.Authenticated -> result.session
        OtpVerifyResult.VerifiedNoSession -> throw AuthDataException.AuthenticationRequired()
    }

private suspend fun Auth.verifyPasswordRecoveryOtpForSession(email: String, otpCode: String): UserSession =
    when (val result = verifyEmailOtp(OtpType.Email.RECOVERY, email = email, token = otpCode)) {
        is OtpVerifyResult.Authenticated -> result.session
        OtpVerifyResult.VerifiedNoSession -> throw AuthDataException.AuthenticationRequired()
    }

private suspend inline fun <T> runAuthRequest(block: suspend () -> T): T =
    runAuthTransportRequest { runAuthSdkRequest(block) }

private suspend inline fun <T> runAuthSdkRequest(block: suspend () -> T): T = try {
    block()
} catch (exception: AuthDataException) {
    throw exception
} catch (exception: AuthWeakPasswordException) {
    throw AuthDataException.Validation(AUTH_PASSWORD_TOO_WEAK_ERROR_KEY, exception)
} catch (exception: AuthSessionMissingException) {
    throw AuthDataException.AuthenticationRequired(cause = exception)
} catch (exception: AuthRestException) {
    throw exception.toAuthDataException()
}

private suspend inline fun <T> runAuthTransportRequest(block: suspend () -> T): T = try {
    block()
} catch (exception: PostgrestRestException) {
    throw exception.toAuthDataException()
} catch (exception: RestException) {
    throw exception.toAuthDataException()
} catch (exception: HttpRequestTimeoutException) {
    throw AuthDataException.NetworkUnavailable(exception)
} catch (exception: HttpRequestException) {
    throw AuthDataException.NetworkUnavailable(exception)
} catch (exception: SerializationException) {
    throw AuthDataException.Unexpected(exception)
} catch (exception: CancellationException) {
    throw exception
} catch (exception: IllegalArgumentException) {
    throw AuthDataException.Unexpected(exception)
} catch (exception: IllegalStateException) {
    throw AuthDataException.Unexpected(exception)
}

private fun RestException.toAuthDataException(): AuthDataException =
    (this as? AuthRestException)?.toAuthCodeDataExceptionOrNull()
        ?: (this as? PostgrestRestException)?.toPostgrestCodeDataExceptionOrNull()
        ?: toHttpStatusDataException()

private fun AuthRestException.toAuthCodeDataExceptionOrNull(): AuthDataException? = when (errorCode?.name) {
    "EmailProviderDisabled",
    "OtpDisabled",
    "ProviderDisabled",
    "ProviderEmailNeedsVerification",
    -> AuthDataException.Validation(cause = this)

    "InvalidCredentials" -> AuthDataException.Validation(AUTH_INVALID_CREDENTIALS_ERROR_KEY, this)
    "EmailNotConfirmed" -> AuthDataException.Validation(AUTH_EMAIL_NOT_CONFIRMED_ERROR_KEY, this)
    "OverEmailSendRateLimit",
    "OverRequestRateLimit",
    -> AuthDataException.Validation(AUTH_RATE_LIMITED_ERROR_KEY, this)

    "BadJwt",
    "SessionExpired",
    "SessionNotFound",
    -> AuthDataException.AuthenticationRequired(cause = this)

    "OtpExpired" -> AuthDataException.Validation(AUTH_OTP_EXPIRED_ERROR_KEY, this)
    "WeakPassword" -> AuthDataException.Validation(AUTH_PASSWORD_TOO_WEAK_ERROR_KEY, this)
    "SamePassword" -> AuthDataException.Validation(AUTH_PASSWORD_SAME_ERROR_KEY, this)
    else -> null
}

private fun AuthRestException.isUnknownAccountError(): Boolean = errorCode?.name == "UserNotFound"

private fun PostgrestRestException.toPostgrestCodeDataExceptionOrNull(): AuthDataException? = when (code) {
    "42501" -> AuthDataException.PermissionDenied(cause = this)
    "P0001", "22023", "23503", "23505", "23514" -> AuthDataException.Validation(cause = this)
    "P0002", "PGRST116" -> AuthDataException.LegalDocumentsUnavailable(this)
    else -> null
}

private fun RestException.toHttpStatusDataException(): AuthDataException = when (statusCode) {
    HTTP_BAD_REQUEST,
    HTTP_CONFLICT,
    HTTP_UNPROCESSABLE_CONTENT,
    -> AuthDataException.Validation(cause = this)
    HTTP_UNAUTHORIZED -> AuthDataException.AuthenticationRequired(cause = this)
    HTTP_FORBIDDEN -> AuthDataException.PermissionDenied(cause = this)
    HTTP_NOT_FOUND -> AuthDataException.LegalDocumentsUnavailable(this)
    HTTP_TOO_MANY_REQUESTS -> AuthDataException.Validation(AUTH_RATE_LIMITED_ERROR_KEY, this)
    else -> AuthDataException.Unexpected(this)
}

private fun UserSession.toDto(onboardingCompleted: Boolean, purpose: AuthSessionPurpose): AuthSessionDto {
    val sessionUser = user ?: throw AuthDataException.AuthenticationRequired()
    return AuthSessionDto(
        userId = sessionUser.id,
        email = sessionUser.email,
        expiresAtEpochMilliseconds = expiresAt.toEpochMilliseconds(),
        onboardingCompleted = onboardingCompleted,
        purpose = purpose,
    )
}

private fun SocialAuthProvider.toSupabaseProvider(): IDTokenProvider = when (this) {
    SocialAuthProvider.Google -> Google
    SocialAuthProvider.Apple -> Apple
}

private fun CompleteOnboardingRequest.toRpcDto(): CompleteOnboardingRpcDto = CompleteOnboardingRpcDto(
    firstName = firstName,
    lastName = lastName,
    cityId = cityId,
    preferredLocale = preferredLocale.tag,
    preferredCurrency = preferredCurrency.name.uppercase(),
    termsDocumentId = termsDocumentId,
    privacyDocumentId = privacyDocumentId,
    ugcDocumentId = ugcDocumentId,
)

private fun LegalDocumentRevisionDto.toDomain(): LegalDocumentRevision = LegalDocumentRevision(
    id = id,
    type = documentType.toDomainType(),
    version = version,
    locale = locale.toAppLocale(),
    url = contentUrl,
    effectiveAtEpochMilliseconds = effectiveAt.toEpochMilliseconds(),
)

private fun String.toDomainType(): LegalDocumentType = when (this) {
    "terms" -> LegalDocumentType.Terms
    "privacy_policy" -> LegalDocumentType.PrivacyPolicy
    "ugc_license" -> LegalDocumentType.UgcLicense
    else -> invalidDatabaseValue("legal_documents.document_type", this)
}

private fun String.toAppLocale(): AppLocale = AppLocale.entries.firstOrNull { locale -> locale.tag == this }
    ?: invalidDatabaseValue("legal_documents.locale", this)

private fun String.toEpochMilliseconds(): Long = try {
    Instant.parse(this).toEpochMilliseconds()
} catch (exception: IllegalArgumentException) {
    throw AuthDataException.Unexpected(exception)
}

private fun invalidDatabaseValue(fieldName: String, value: String): Nothing = throw AuthDataException.Unexpected(
    IllegalStateException("Invalid database value for $fieldName: $value"),
)

@Serializable
private data class OnboardingProfileStatusDto(
    @SerialName("user_id")
    val userId: String,
    @SerialName("onboarding_completed_at")
    val onboardingCompletedAt: String? = null,
)

@Serializable
private data class LegalDocumentRevisionDto(
    @SerialName("id")
    val id: String,
    @SerialName("document_type")
    val documentType: String,
    @SerialName("version")
    val version: String,
    @SerialName("locale")
    val locale: String,
    @SerialName("content_url")
    val contentUrl: String,
    @SerialName("effective_at")
    val effectiveAt: String,
)

@Serializable
private data class CompleteOnboardingRpcDto(
    @SerialName("p_first_name")
    val firstName: String,
    @SerialName("p_last_name")
    val lastName: String,
    @SerialName("p_city_id")
    val cityId: String,
    @SerialName("p_preferred_locale")
    val preferredLocale: String,
    @SerialName("p_preferred_currency")
    val preferredCurrency: String,
    @SerialName("p_terms_document_id")
    val termsDocumentId: String,
    @SerialName("p_privacy_document_id")
    val privacyDocumentId: String,
    @SerialName("p_ugc_document_id")
    val ugcDocumentId: String,
)

private const val PROFILES = "profiles"
private const val LEGAL_DOCUMENTS = "legal_documents"
private const val COMPLETE_ONBOARDING_RPC = "complete_user_onboarding"
