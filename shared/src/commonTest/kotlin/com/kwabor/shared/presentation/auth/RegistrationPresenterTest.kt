package com.kwabor.shared.presentation.auth

import com.kwabor.shared.domain.auth.AUTH_OTP_EXPIRED_ERROR_KEY
import com.kwabor.shared.domain.auth.AccountSetupStatus
import com.kwabor.shared.domain.auth.AuthRepository
import com.kwabor.shared.domain.auth.AuthSession
import com.kwabor.shared.domain.auth.CompleteOnboardingRequest
import com.kwabor.shared.domain.auth.LegalDocumentRevision
import com.kwabor.shared.domain.auth.LegalDocumentType
import com.kwabor.shared.domain.auth.MAX_ONBOARDING_NAME_LENGTH
import com.kwabor.shared.domain.auth.PromoterActivationRequest
import com.kwabor.shared.domain.auth.SocialSignInRequest
import com.kwabor.shared.domain.catalog.CatalogRepository
import com.kwabor.shared.domain.catalog.Category
import com.kwabor.shared.domain.catalog.City
import com.kwabor.shared.domain.catalog.ListingDetail
import com.kwabor.shared.domain.catalog.ListingFilters
import com.kwabor.shared.domain.catalog.ListingSearchQuery
import com.kwabor.shared.domain.catalog.ListingSummary
import com.kwabor.shared.domain.catalog.ListingViewerInteraction
import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.core.PageRequest
import com.kwabor.shared.domain.core.PageResult
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.money.KwaborCurrency
import com.kwabor.shared.domain.observability.ObservabilityConsent
import com.kwabor.shared.i18n.stringsFor
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class RegistrationPresenterTest {
    private val strings = stringsFor(AppLocale.French)

    @Test
    fun requestOtp_enforcesThirtySecondCooldown() = runTest {
        val repository = FakeRegistrationAuthRepository()
        val clock = FakeClock(now = 1_000L)
        val presenter = presenter(repository, clock)
        val initial = initialRegistrationUiState().copy(email = "user@kwabor.test")

        val sent = presenter.requestOtp(initial, strings)
        val blocked = presenter.requestOtp(sent, strings)

        assertEquals(RegistrationStep.Otp, sent.step)
        assertEquals(31_000L, sent.resendAvailableAtEpochMilliseconds)
        assertEquals(strings.registrationOtpWait, blocked.errorMessage)
        assertEquals(1, repository.otpRequests)
    }

    @Test
    fun verifyOtp_keepsIncompleteSessionOutOfCompletedState() = runTest {
        val presenter = presenter(FakeRegistrationAuthRepository(), FakeClock())

        val result = presenter.verifyOtp(
            initialRegistrationUiState().copy(email = "user@kwabor.test", step = RegistrationStep.Otp),
            "123456",
            strings,
        )

        assertEquals(RegistrationStep.Password, result.step)
        assertEquals(AccountSetupStatus.OnboardingRequired, result.currentSession?.accountSetupStatus)
    }

    @Test
    fun verifyOtp_explainsThatAnExpiredCodeMustBeRequestedAgain() = runTest {
        val presenter = presenter(
            FakeRegistrationAuthRepository(
                otpFailure = DomainError.Validation(AUTH_OTP_EXPIRED_ERROR_KEY),
            ),
            FakeClock(),
        )

        val result = presenter.verifyOtp(
            initialRegistrationUiState().copy(email = "user@kwabor.test", step = RegistrationStep.Otp),
            "123456",
            strings,
        )

        assertEquals(strings.registrationOtpExpired, result.errorMessage)
        assertEquals(RegistrationStep.Otp, result.step)
    }

    @Test
    fun verifyOtp_routesExistingCompletedAccountWithoutOverwritingProfile() = runTest {
        val repository = FakeRegistrationAuthRepository(
            otpSession = authSession(AccountSetupStatus.Complete),
        )
        val presenter = presenter(repository, FakeClock())

        val result = presenter.verifyOtp(
            initialRegistrationUiState().copy(email = "user@kwabor.test", step = RegistrationStep.Otp),
            "123456",
            strings,
        )

        assertEquals(RegistrationStep.Completed, result.step)
        assertEquals(null, repository.completedRequest)
    }

    @Test
    fun setInitialPassword_validatesConfirmationWithoutRetainingSecret() = runTest {
        val repository = FakeRegistrationAuthRepository()
        val presenter = presenter(repository, FakeClock())
        val state = initialRegistrationUiState().copy(step = RegistrationStep.Password)

        val mismatch = presenter.setInitialPassword(state, "password123", "different", strings)
        val accepted = presenter.setInitialPassword(state, "password123", "password123", strings)

        assertEquals(strings.registrationPasswordMismatch, mismatch.errorMessage)
        assertEquals(RegistrationStep.Identity, accepted.step)
        assertEquals("password123", repository.initialPassword)
        assertFalse(accepted.toString().contains("password123"))
    }

    @Test
    fun loadRequirements_requiresOneActiveRevisionPerLegalType() = runTest {
        val repository = FakeRegistrationAuthRepository(
            legalDocuments = legalDocuments().filterNot { revision -> revision.type == LegalDocumentType.UgcLicense },
        )
        val presenter = presenter(repository, FakeClock())

        val result = presenter.loadRequirements(initialRegistrationUiState(), strings)

        assertEquals(strings.registrationLegalUnavailable, result.errorMessage)
        assertEquals(1, result.cities.size)
    }

    @Test
    fun completeOnboarding_sendsExplicitDocumentIdsAndWaitsForNotificationPriming() = runTest {
        val repository = FakeRegistrationAuthRepository()
        val presenter = presenter(repository, FakeClock())
        val requirements = presenter.loadRequirements(initialRegistrationUiState(), strings)
        val ready = requirements.copy(
            step = RegistrationStep.Observability,
            firstName = " Afi ",
            lastName = " Kwabor ",
            selectedCityId = "cotonou",
            preferredCurrency = KwaborCurrency.Eur,
            termsAccepted = true,
            privacyAccepted = true,
            ugcAccepted = true,
            observabilityConsent = ObservabilityConsent(
                analyticsAllowed = true,
                diagnosticsAllowed = false,
                remoteConfigurationAllowed = true,
            ),
        )

        val completed = presenter.completeOnboarding(ready, strings)

        val request = assertNotNull(repository.completedRequest)
        assertEquals("terms-id", request.termsDocumentId)
        assertEquals("privacy-id", request.privacyDocumentId)
        assertEquals("ugc-id", request.ugcDocumentId)
        assertEquals("Afi", request.firstName)
        assertEquals(RegistrationStep.NotificationPriming, completed.step)
        assertEquals(AccountSetupStatus.Complete, completed.currentSession?.accountSetupStatus)
        assertEquals(
            RegistrationStep.Completed,
            presenter.reducer.reduce(completed, RegistrationIntent.FinishNotificationPriming, strings).step,
        )
    }

    @Test
    fun legalAndLocationTransitionsRemainBlockingUntilValid() {
        val presenter = presenter(FakeRegistrationAuthRepository(), FakeClock())

        val identityBlocked = presenter.reducer.reduce(
            initialRegistrationUiState(),
            RegistrationIntent.ContinueFromIdentity,
            strings,
        )
        val cityBlocked = presenter.reducer.reduce(
            initialRegistrationUiState().copy(step = RegistrationStep.City),
            RegistrationIntent.ContinueFromCity,
            strings,
        )
        val legalBlocked = presenter.reducer.reduce(
            initialRegistrationUiState().copy(step = RegistrationStep.Legal),
            RegistrationIntent.ContinueFromLegal,
            strings,
        )
        val nameTooLong = presenter.reducer.reduce(
            initialRegistrationUiState().copy(
                firstName = "A".repeat(MAX_ONBOARDING_NAME_LENGTH + 1),
                lastName = "Soglo",
            ),
            RegistrationIntent.ContinueFromIdentity,
            strings,
        )

        assertEquals(strings.registrationNameRequired, identityBlocked.errorMessage)
        assertEquals(strings.registrationNameTooLong, nameTooLong.errorMessage)
        assertEquals(strings.registrationCityRequired, cityBlocked.errorMessage)
        assertEquals(strings.registrationLegalRequired, legalBlocked.errorMessage)
    }
}

private class FakeRegistrationAuthRepository(
    private val otpSession: AuthSession = authSession(AccountSetupStatus.OnboardingRequired),
    private val otpFailure: DomainError? = null,
    private val legalDocuments: List<LegalDocumentRevision> = legalDocuments(),
) : AuthRepository {
    var otpRequests: Int = 0
        private set
    var initialPassword: String? = null
        private set
    var completedRequest: CompleteOnboardingRequest? = null
        private set

    override suspend fun getCurrentSession(): DomainResult<AuthSession?> = DomainResult.Success(null)

    override suspend fun requestEmailOtp(email: String): DomainResult<Unit> {
        otpRequests += 1
        return DomainResult.Success(Unit)
    }

    override suspend fun verifyEmailOtp(email: String, otpCode: String): DomainResult<AuthSession> =
        otpFailure?.let { error -> DomainResult.Failure(error) } ?: DomainResult.Success(otpSession)

    override suspend fun setInitialPassword(password: String): DomainResult<Unit> {
        initialPassword = password
        return DomainResult.Success(Unit)
    }

    override suspend fun listActiveLegalDocuments(locale: AppLocale): DomainResult<List<LegalDocumentRevision>> =
        DomainResult.Success(legalDocuments)

    override suspend fun completeOnboarding(request: CompleteOnboardingRequest): DomainResult<AuthSession> {
        completedRequest = request
        return DomainResult.Success(authSession(AccountSetupStatus.Complete))
    }

    override suspend fun signInWithEmail(email: String, password: String): DomainResult<AuthSession> =
        DomainResult.Success(authSession(AccountSetupStatus.Complete))

    override suspend fun signInWithSocialProvider(request: SocialSignInRequest): DomainResult<AuthSession> =
        DomainResult.Success(authSession(AccountSetupStatus.Complete))

    override suspend fun activatePromoterInvite(request: PromoterActivationRequest): DomainResult<AuthSession> =
        DomainResult.Failure(DomainError.Validation("error.auth.unused"))

    override suspend fun signOut(): DomainResult<Unit> = DomainResult.Success(Unit)
}

private class FakeRegistrationCatalogRepository : CatalogRepository {
    override suspend fun listCities(): DomainResult<List<City>> = DomainResult.Success(
        listOf(City(id = "cotonou", name = "Cotonou", latitude = 6.3703, longitude = 2.3912)),
    )

    override suspend fun listCategories(): DomainResult<List<Category>> = unused()

    override suspend fun listListings(
        filters: ListingFilters,
        page: PageRequest,
    ): DomainResult<PageResult<ListingSummary>> = unused()

    override suspend fun searchListings(
        query: ListingSearchQuery,
        page: PageRequest,
    ): DomainResult<PageResult<ListingSummary>> = unused()

    override suspend fun getListingDetail(listingId: String): DomainResult<ListingDetail> = unused()

    override suspend fun getListingViewerInteraction(listingId: String): DomainResult<ListingViewerInteraction> =
        unused()

    override suspend fun listListingViewerInteractions(
        listingIds: List<String>,
    ): DomainResult<List<ListingViewerInteraction>> = unused()

    override suspend fun likeListing(listingId: String): DomainResult<ListingViewerInteraction> = unused()

    override suspend fun unlikeListing(listingId: String): DomainResult<ListingViewerInteraction> = unused()

    override suspend fun favoriteListing(listingId: String): DomainResult<ListingViewerInteraction> = unused()

    override suspend fun unfavoriteListing(listingId: String): DomainResult<ListingViewerInteraction> = unused()

    private fun <T> unused(): DomainResult<T> = DomainResult.Failure(DomainError.Unexpected("unused"))
}

private class FakeClock(var now: Long = 0L) : ClockProvider {
    override fun nowEpochMilliseconds(): Long = now
}

private fun presenter(repository: AuthRepository, clock: ClockProvider): RegistrationPresenter = RegistrationPresenter(
    authRepository = repository,
    catalogRepository = FakeRegistrationCatalogRepository(),
    clockProvider = clock,
    reducer = RegistrationReducer(),
)

private fun authSession(status: AccountSetupStatus): AuthSession = AuthSession(
    userId = "user-1",
    email = "user@kwabor.test",
    expiresAtEpochMilliseconds = 1_783_080_000_000,
    accountSetupStatus = status,
)

private fun legalDocuments(): List<LegalDocumentRevision> = listOf(
    legalDocument("terms-id", LegalDocumentType.Terms),
    legalDocument("privacy-id", LegalDocumentType.PrivacyPolicy),
    legalDocument("ugc-id", LegalDocumentType.UgcLicense),
)

private fun legalDocument(id: String, type: LegalDocumentType): LegalDocumentRevision = LegalDocumentRevision(
    id = id,
    type = type,
    version = "2026-07-15",
    locale = AppLocale.French,
    url = "https://legal.kwabor.test/$id",
    effectiveAtEpochMilliseconds = 1_768_435_200_000,
)
