package com.kwabor.shared.data.auth

import com.kwabor.shared.domain.auth.AuthSession
import com.kwabor.shared.domain.auth.EmailOtpProfileRequest
import com.kwabor.shared.domain.auth.EmailSignUpRequest
import com.kwabor.shared.domain.auth.OnboardingProfileInput
import com.kwabor.shared.domain.auth.PromoterActivationRequest
import com.kwabor.shared.domain.auth.SocialAuthProvider
import com.kwabor.shared.domain.auth.SocialSignInRequest
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.money.KwaborCurrency
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DataAuthRepositoryTest {
    @Test
    fun getCurrentSession_mapsDtoWithoutTokens() = runTest {
        val repository = DataAuthRepository(FakeAuthDataSource(session = authSessionDto()))

        val result = repository.getCurrentSession()

        val session = assertIs<DomainResult.Success<AuthSession?>>(result).value
        assertEquals("user-1", session?.userId)
        assertEquals("user@kwabor.test", session?.email)
        assertEquals(1_783_080_000_000, session?.expiresAtEpochMilliseconds)
    }

    @Test
    fun requestEmailOtp_rejectsInvalidEmailBeforeDataSourceCall() = runTest {
        val dataSource = FakeAuthDataSource()
        val repository = DataAuthRepository(dataSource)

        val result = repository.requestEmailOtp("invalid")

        val failure = assertIs<DomainResult.Failure>(result)
        assertIs<DomainError.Validation>(failure.error)
        assertEquals(0, dataSource.emailOtpRequests)
    }

    @Test
    fun signInWithEmail_delegatesAndMapsSession() = runTest {
        val dataSource = FakeAuthDataSource()
        val repository = DataAuthRepository(dataSource)

        val result = repository.signInWithEmail(email = " user@kwabor.test ", password = "password123")

        val session = assertIs<DomainResult.Success<AuthSession>>(result).value
        assertEquals("user@kwabor.test", dataSource.lastEmailSignIn)
        assertEquals("user-1", session.userId)
    }

    @Test
    fun signUpWithEmail_requiresOtpAndMapsSession() = runTest {
        val dataSource = FakeAuthDataSource()
        val repository = DataAuthRepository(dataSource)
        val request = EmailSignUpRequest(
            email = " user@kwabor.test ",
            password = "password123",
            otpCode = " 123456 ",
            onboarding = onboardingInput(),
        )

        val result = repository.signUpWithEmail(request)

        val session = assertIs<DomainResult.Success<AuthSession>>(result).value
        assertEquals("user@kwabor.test", dataSource.lastEmailSignUp?.email)
        assertEquals("123456", dataSource.lastEmailSignUp?.otpCode)
        assertEquals("user-1", session.userId)
    }

    @Test
    fun verifyEmailOtpWithProfile_trimsInputAndMapsSession() = runTest {
        val dataSource = FakeAuthDataSource()
        val repository = DataAuthRepository(dataSource)

        val result = repository.verifyEmailOtpWithProfile(
            EmailOtpProfileRequest(
                email = " user@kwabor.test ",
                otpCode = " 123456 ",
                onboarding = onboardingInput(),
            ),
        )

        val session = assertIs<DomainResult.Success<AuthSession>>(result).value
        assertEquals("user@kwabor.test", dataSource.lastEmailOtpProfile?.email)
        assertEquals("123456", dataSource.lastEmailOtpProfile?.otpCode)
        assertEquals("user-1", session.userId)
    }

    @Test
    fun verifyEmailOtpWithProfile_rejectsInvalidOtpBeforeDataSourceCall() = runTest {
        val dataSource = FakeAuthDataSource()
        val repository = DataAuthRepository(dataSource)

        val result = repository.verifyEmailOtpWithProfile(
            EmailOtpProfileRequest(
                email = "user@kwabor.test",
                otpCode = "123",
                onboarding = onboardingInput(),
            ),
        )

        val failure = assertIs<DomainResult.Failure>(result)
        assertIs<DomainError.Validation>(failure.error)
        assertEquals(null, dataSource.lastEmailOtpProfile)
    }

    @Test
    fun signInWithSocialProvider_requiresIdToken() = runTest {
        val repository = DataAuthRepository(FakeAuthDataSource())

        val result = repository.signInWithSocialProvider(
            SocialSignInRequest(
                provider = SocialAuthProvider.Google,
                idToken = " ",
                onboarding = null,
            ),
        )

        val failure = assertIs<DomainResult.Failure>(result)
        assertIs<DomainError.Validation>(failure.error)
    }

    @Test
    fun activatePromoterInvite_isBlockedUntilServerRpcExists() = runTest {
        val repository = DataAuthRepository(FakeAuthDataSource())

        val result = repository.activatePromoterInvite(
            PromoterActivationRequest(
                inviteToken = "invite-token",
                password = "password123",
                socialSignInRequest = null,
            ),
        )

        val failure = assertIs<DomainResult.Failure>(result)
        assertEquals("error.auth.promoter_activation_requires_server", failure.error.messageKey)
    }

    @Test
    fun dataException_mapsToAuthenticationRequired() = runTest {
        val repository = DataAuthRepository(
            FakeAuthDataSource(throwOnSignIn = AuthDataException.AuthenticationRequired()),
        )

        val result = repository.signInWithEmail(email = "user@kwabor.test", password = "password123")

        val failure = assertIs<DomainResult.Failure>(result)
        assertIs<DomainError.AuthenticationRequired>(failure.error)
    }
}

private class FakeAuthDataSource(
    private val session: AuthSessionDto? = null,
    private val throwOnSignIn: AuthDataException? = null,
) : AuthDataSource {
    var emailOtpRequests: Int = 0
        private set
    var lastEmailSignIn: String? = null
        private set
    var lastEmailSignUp: EmailSignUpRequest? = null
        private set
    var lastEmailOtpProfile: EmailOtpProfileRequest? = null
        private set

    override suspend fun getCurrentSession(): AuthSessionDto? = session

    override suspend fun requestEmailOtp(email: String) {
        emailOtpRequests += 1
    }

    override suspend fun verifyEmailOtp(email: String, otpCode: String) = Unit

    override suspend fun verifyEmailOtpWithProfile(request: EmailOtpProfileRequest): AuthSessionDto {
        lastEmailOtpProfile = request
        return authSessionDto()
    }

    override suspend fun signUpWithEmail(request: EmailSignUpRequest): AuthSessionDto {
        lastEmailSignUp = request
        return authSessionDto()
    }

    override suspend fun signInWithEmail(email: String, password: String): AuthSessionDto {
        throwOnSignIn?.let { throw it }
        lastEmailSignIn = email
        return authSessionDto()
    }

    override suspend fun signInWithSocialProvider(request: SocialSignInRequest): AuthSessionDto = authSessionDto()

    override suspend fun signOut() = Unit
}

private fun authSessionDto(): AuthSessionDto = AuthSessionDto(
    userId = "user-1",
    email = "user@kwabor.test",
    expiresAtEpochMilliseconds = 1_783_080_000_000,
)

private fun onboardingInput(): OnboardingProfileInput = assertIs<DomainResult.Success<OnboardingProfileInput>>(
    OnboardingProfileInput.create(
        firstName = "Afi",
        lastName = "Kwabor",
        cityId = "cotonou",
        preferredLocale = AppLocale.French,
        preferredCurrency = KwaborCurrency.Xof,
        termsAccepted = true,
        privacyPolicyAccepted = true,
        ugcLicenseAccepted = true,
    ),
).value
