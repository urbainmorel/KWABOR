package com.kwabor.shared.app

import com.kwabor.shared.domain.auth.AccountSetupStatus
import com.kwabor.shared.domain.auth.AuthSession
import com.kwabor.shared.domain.auth.LegalDocumentType
import com.kwabor.shared.domain.catalog.GeoPoint
import com.kwabor.shared.domain.catalog.isWithinBeninBounds
import com.kwabor.shared.domain.catalog.nearestCity
import com.kwabor.shared.domain.core.DispatcherProvider
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.money.KwaborCurrency
import com.kwabor.shared.domain.observability.ObservabilityConsent
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.auth.RegistrationIntent
import com.kwabor.shared.presentation.auth.RegistrationPresenter
import com.kwabor.shared.presentation.auth.RegistrationStep
import com.kwabor.shared.presentation.auth.RegistrationUiState
import com.kwabor.shared.presentation.auth.initialRegistrationUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

sealed interface IosRegistrationIntent

sealed interface IosRegistrationFieldIntent : IosRegistrationIntent

data class IosRegistrationUpdateEmailIntent(val email: String) : IosRegistrationFieldIntent

data class IosRegistrationUpdateFirstNameIntent(val firstName: String) : IosRegistrationFieldIntent

data class IosRegistrationUpdateLastNameIntent(val lastName: String) : IosRegistrationFieldIntent

data class IosRegistrationSelectCityIntent(val cityId: String) : IosRegistrationFieldIntent

data class IosRegistrationSelectCurrencyIntent(val currency: KwaborCurrency) : IosRegistrationFieldIntent

data class IosRegistrationUpdateLegalAcceptanceIntent(
    val type: LegalDocumentType,
    val accepted: Boolean,
) : IosRegistrationFieldIntent

data class IosRegistrationUpdateObservabilityConsentIntent(
    val consent: ObservabilityConsent,
) : IosRegistrationFieldIntent

data class IosRegistrationSelectNearestCityIntent(
    val latitude: Double,
    val longitude: Double,
) : IosRegistrationIntent

sealed interface IosRegistrationAsyncIntent : IosRegistrationIntent

data object IosRegistrationRequestOtpIntent : IosRegistrationAsyncIntent

class IosRegistrationVerifyOtpIntent(val otpCode: String) : IosRegistrationAsyncIntent

class IosRegistrationSetInitialPasswordIntent(
    val password: String,
    val confirmation: String,
) : IosRegistrationAsyncIntent

data object IosRegistrationLoadRequirementsIntent : IosRegistrationAsyncIntent

data object IosRegistrationCompleteOnboardingIntent : IosRegistrationAsyncIntent

sealed interface IosRegistrationNavigationIntent : IosRegistrationIntent

data object IosRegistrationContinueFromIdentityIntent : IosRegistrationNavigationIntent

data object IosRegistrationContinueFromCityIntent : IosRegistrationNavigationIntent

data object IosRegistrationContinueFromCurrencyIntent : IosRegistrationNavigationIntent

data object IosRegistrationContinueFromLegalIntent : IosRegistrationNavigationIntent

data object IosRegistrationFinishNotificationPrimingIntent : IosRegistrationNavigationIntent

data object IosRegistrationGoBackIntent : IosRegistrationNavigationIntent

class IosRegistrationController internal constructor(
    private val presenter: RegistrationPresenter?,
    dispatcherProvider: DispatcherProvider,
) {
    private val strings = stringsFor(AppLocale.French)
    private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.main)
    private var observer: ((RegistrationUiState) -> Unit)? = null
    private var operationJob: Job? = null
    private var state = initialRegistrationUiState()

    val isConfigured: Boolean get() = presenter != null

    fun observe(observer: (RegistrationUiState) -> Unit) {
        this.observer = observer
        observer(state)
    }

    fun resumeIncompleteSession(session: AuthSession?) {
        if (session?.accountSetupStatus != AccountSetupStatus.OnboardingRequired) return
        if (state.currentSession?.userId == session.userId && state.step != RegistrationStep.Email) return
        operationJob?.cancel()
        state = initialRegistrationUiState().copy(
            step = RegistrationStep.Password,
            email = session.email.orEmpty(),
            currentSession = session,
        )
        publish()
    }

    fun reset() {
        operationJob?.cancel()
        operationJob = null
        state = initialRegistrationUiState()
        publish()
    }

    fun dispatch(intent: IosRegistrationIntent) {
        when (intent) {
            is IosRegistrationFieldIntent -> reduce(intent.toSharedIntent())
            is IosRegistrationNavigationIntent -> reduce(intent.toSharedIntent())
            is IosRegistrationSelectNearestCityIntent -> selectNearestCity(intent)
            is IosRegistrationAsyncIntent -> dispatchAsync(intent)
        }
    }

    fun close() {
        observer = null
        scope.cancel()
    }

    private fun reduce(intent: RegistrationIntent) = updateState { currentState ->
        presenter?.reducer?.reduce(currentState, intent, strings) ?: currentState
    }

    private fun selectNearestCity(intent: IosRegistrationSelectNearestCityIntent) = updateState { currentState ->
        val location = GeoPoint(latitude = intent.latitude, longitude = intent.longitude)
        if (!location.isWithinBeninBounds) {
            return@updateState currentState.copy(errorMessage = strings.registrationLocationOutsideBenin)
        }
        val city = nearestCity(currentState.cities, location)
            ?: return@updateState currentState.copy(errorMessage = strings.registrationLocationUnavailable)
        presenter?.reducer?.reduce(
            state = currentState,
            intent = RegistrationIntent.SelectCity(city.id),
            strings = strings,
        ) ?: currentState
    }

    private fun dispatchAsync(intent: IosRegistrationAsyncIntent) = launchOperation { currentPresenter, currentState ->
        when (intent) {
            IosRegistrationRequestOtpIntent -> currentPresenter.requestOtp(currentState, strings)
            is IosRegistrationVerifyOtpIntent -> currentPresenter.verifyOtp(currentState, intent.otpCode, strings)
            is IosRegistrationSetInitialPasswordIntent -> currentPresenter.setPasswordAndLoadRequirements(
                state = currentState,
                password = intent.password,
                confirmation = intent.confirmation,
            )
            IosRegistrationLoadRequirementsIntent -> currentPresenter.loadRequirements(currentState, strings)
            IosRegistrationCompleteOnboardingIntent -> currentPresenter.completeOnboarding(currentState, strings)
        }
    }

    private suspend fun RegistrationPresenter.setPasswordAndLoadRequirements(
        state: RegistrationUiState,
        password: String,
        confirmation: String,
    ): RegistrationUiState {
        val passwordState = setInitialPassword(
            state = state,
            password = password,
            confirmation = confirmation,
            strings = strings,
        )
        return if (passwordState.step == RegistrationStep.Identity && passwordState.errorMessage == null) {
            loadRequirements(passwordState, strings)
        } else {
            passwordState
        }
    }

    private fun launchOperation(
        operation: suspend (RegistrationPresenter, RegistrationUiState) -> RegistrationUiState,
    ) {
        val currentPresenter = presenter ?: return
        if (state.isLoading) return
        operationJob?.cancel()
        state = state.copy(isLoading = true, errorMessage = null, noticeMessage = null)
        publish()
        operationJob = scope.launch {
            state = operation(currentPresenter, state).copy(isLoading = false)
            publish()
        }
    }

    private fun updateState(transform: (RegistrationUiState) -> RegistrationUiState) {
        if (state.isLoading) return
        state = transform(state)
        publish()
    }

    private fun publish() {
        observer?.invoke(state)
    }
}

private fun IosRegistrationFieldIntent.toSharedIntent(): RegistrationIntent.Field = when (this) {
    is IosRegistrationUpdateEmailIntent -> RegistrationIntent.UpdateEmail(email)
    is IosRegistrationUpdateFirstNameIntent -> RegistrationIntent.UpdateFirstName(firstName)
    is IosRegistrationUpdateLastNameIntent -> RegistrationIntent.UpdateLastName(lastName)
    is IosRegistrationSelectCityIntent -> RegistrationIntent.SelectCity(cityId)
    is IosRegistrationSelectCurrencyIntent -> RegistrationIntent.SelectCurrency(currency)
    is IosRegistrationUpdateLegalAcceptanceIntent -> RegistrationIntent.UpdateLegalAcceptance(type, accepted)
    is IosRegistrationUpdateObservabilityConsentIntent -> RegistrationIntent.UpdateObservabilityConsent(consent)
}

private fun IosRegistrationNavigationIntent.toSharedIntent(): RegistrationIntent.Navigation = when (this) {
    IosRegistrationContinueFromIdentityIntent -> RegistrationIntent.ContinueFromIdentity
    IosRegistrationContinueFromCityIntent -> RegistrationIntent.ContinueFromCity
    IosRegistrationContinueFromCurrencyIntent -> RegistrationIntent.ContinueFromCurrency
    IosRegistrationContinueFromLegalIntent -> RegistrationIntent.ContinueFromLegal
    IosRegistrationFinishNotificationPrimingIntent -> RegistrationIntent.FinishNotificationPriming
    IosRegistrationGoBackIntent -> RegistrationIntent.GoBack
}
