package com.kwabor.shared.data.auth

import com.kwabor.shared.domain.auth.EmailSignUpRequest
import com.kwabor.shared.domain.auth.OnboardingProfileInput
import com.kwabor.shared.domain.auth.SocialAuthProvider
import com.kwabor.shared.domain.auth.SocialSignInRequest
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
import io.github.jan.supabase.auth.user.UserUpdateBuilder
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.serialization.json.put

internal class SupabaseAuthDataSource(
    private val auth: Auth,
) : AuthDataSource {
    override suspend fun getCurrentSession(): AuthSessionDto? = runAuthRequest {
        auth.awaitInitialization()
        auth.currentSessionOrNull()?.toDto()
    }

    override suspend fun requestEmailOtp(email: String): Unit = runAuthRequest {
        auth.signInWith(OTP) {
            this.email = email
            createUser = true
        }
    }

    override suspend fun verifyEmailOtp(email: String, otpCode: String): Unit = runAuthRequest {
        auth.verifyEmailOtpForSession(email = email, otpCode = otpCode).let { }
    }

    override suspend fun signUpWithEmail(request: EmailSignUpRequest): AuthSessionDto = runAuthRequest {
        val verifiedSession = auth.verifyEmailOtpForSession(email = request.email, otpCode = request.otpCode)
            ?: throw AuthDataException.AuthenticationRequired()
        auth.updateUser(updateCurrentUser = true) {
            password = request.password
            applyOnboarding(request.onboarding)
        }
        auth.currentSessionOrNull()?.toDto() ?: verifiedSession
    }

    override suspend fun signInWithEmail(email: String, password: String): AuthSessionDto = runAuthRequest {
        auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
        auth.currentSessionOrNull()?.toDto() ?: throw AuthDataException.AuthenticationRequired()
    }

    override suspend fun signInWithSocialProvider(request: SocialSignInRequest): AuthSessionDto = runAuthRequest {
        auth.signInWith(IDToken) {
            idToken = request.idToken
            provider = request.provider.toSupabaseProvider()
        }
        request.onboarding?.let { onboarding ->
            auth.updateUser(updateCurrentUser = true) {
                applyOnboarding(onboarding)
            }
        }
        auth.currentSessionOrNull()?.toDto() ?: throw AuthDataException.AuthenticationRequired()
    }

    override suspend fun signOut(): Unit = runAuthRequest {
        auth.signOut(SignOutScope.LOCAL)
    }
}

private suspend fun Auth.verifyEmailOtpForSession(email: String, otpCode: String): AuthSessionDto? =
    when (val result = verifyEmailOtp(OtpType.Email.EMAIL, email = email, token = otpCode)) {
        is OtpVerifyResult.Authenticated -> result.session.toDto()
        OtpVerifyResult.VerifiedNoSession -> null
    }

private suspend inline fun <T> runAuthRequest(block: suspend () -> T): T = try {
    block()
} catch (exception: AuthDataException) {
    throw exception
} catch (exception: AuthWeakPasswordException) {
    throw AuthDataException.Validation("error.auth.password_too_weak")
} catch (exception: AuthSessionMissingException) {
    throw AuthDataException.AuthenticationRequired()
} catch (exception: AuthRestException) {
    throw exception.toAuthDataException()
} catch (exception: RestException) {
    throw exception.toAuthDataException()
} catch (exception: HttpRequestTimeoutException) {
    throw AuthDataException.NetworkUnavailable()
} catch (exception: HttpRequestException) {
    throw AuthDataException.NetworkUnavailable()
}

private fun RestException.toAuthDataException(): AuthDataException {
    if (this is AuthRestException) {
        when (errorCode?.name) {
            "EmailProviderDisabled",
            "OtpDisabled",
            "ProviderDisabled",
            "ProviderEmailNeedsVerification",
            "InvalidCredentials",
            -> return AuthDataException.Validation()

            "OtpExpired",
            "BadJwt",
            "SessionExpired",
            "SessionNotFound",
            -> return AuthDataException.AuthenticationRequired()

            "WeakPassword" -> return AuthDataException.Validation("error.auth.password_too_weak")
        }
    }

    return when (statusCode) {
        400, 409, 422 -> AuthDataException.Validation()
        401 -> AuthDataException.AuthenticationRequired()
        403 -> AuthDataException.PermissionDenied()
        else -> AuthDataException.Unexpected()
    }
}

private fun UserSession.toDto(): AuthSessionDto {
    val sessionUser = user ?: throw AuthDataException.AuthenticationRequired()
    return AuthSessionDto(
        userId = sessionUser.id,
        email = sessionUser.email,
        expiresAtEpochMilliseconds = expiresAt.toEpochMilliseconds(),
    )
}

private fun SocialAuthProvider.toSupabaseProvider(): IDTokenProvider = when (this) {
    SocialAuthProvider.Google -> Google
    SocialAuthProvider.Apple -> Apple
}

private fun UserUpdateBuilder.applyOnboarding(onboarding: OnboardingProfileInput) {
    data {
        put("first_name", onboarding.firstName)
        put("last_name", onboarding.lastName)
        onboarding.cityId?.let { cityId -> put("city_id", cityId) }
        put("preferred_locale", onboarding.preferredLocale.tag)
        put("preferred_currency", onboarding.preferredCurrency.name.uppercase())
        put("terms_accepted", onboarding.termsAccepted)
        put("privacy_policy_accepted", onboarding.privacyPolicyAccepted)
        put("ugc_license_accepted", onboarding.ugcLicenseAccepted)
    }
}
