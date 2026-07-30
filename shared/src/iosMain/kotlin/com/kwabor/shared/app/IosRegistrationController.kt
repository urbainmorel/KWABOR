package com.kwabor.shared.app

import com.kwabor.shared.domain.auth.AccountSetupStatus
import com.kwabor.shared.domain.auth.AuthSession
import com.kwabor.shared.domain.auth.LegalDocumentType
import com.kwabor.shared.domain.core.DispatcherProvider
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.money.KwaborCurrency
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.auth.RegistrationIntent
import com.kwabor.shared.presentation.auth.RegistrationMethod
import com.kwabor.shared.presentation.auth.RegistrationPresenter
import com.kwabor.shared.presentation.auth.RegistrationRequirementsStatus
import com.kwabor.shared.presentation.auth.RegistrationStartContext
import com.kwabor.shared.presentation.auth.RegistrationStep
import com.kwabor.shared.presentation.auth.RegistrationUiState
import com.kwabor.shared.presentation.auth.initialRegistrationUiState
import com.kwabor.shared.presentation.auth.mergeRequirementsFrom
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

sealed interface IosRegistrationAsyncIntent : IosRegistrationIntent

data object IosRegistrationRequestOtpIntent : IosRegistrationAsyncIntent

class IosRegistrationVerifyOtpIntent(val otpCode: String) : IosRegistrationAsyncIntent

class IosRegistrationSetInitialPasswordIntent(val password: String) : IosRegistrationAsyncIntent

data object IosRegistrationLoadRequirementsIntent : IosRegistrationAsyncIntent

data object IosRegistrationCompleteProfileIntent : IosRegistrationAsyncIntent

sealed interface IosRegistrationNavigationIntent : IosRegistrationIntent

data object IosRegistrationGoBackIntent : IosRegistrationNavigationIntent

class IosLegalAcceptanceController internal constructor(
    private val updateAcceptance: (LegalDocumentType, Boolean) -> Unit,
) {
    fun updateTerms(accepted: Boolean) {
        updateAcceptance(LegalDocumentType.Terms, accepted)
    }

    fun updatePrivacy(accepted: Boolean) {
        updateAcceptance(LegalDocumentType.PrivacyPolicy, accepted)
    }

    fun updateUgc(accepted: Boolean) {
        updateAcceptance(LegalDocumentType.UgcLicense, accepted)
    }
}

class IosRegistrationController internal constructor(
    private val presenter: RegistrationPresenter?,
    dispatcherProvider: DispatcherProvider,
) {
    private val strings = stringsFor(AppLocale.French)
    private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.main)
    private var observer: ((RegistrationUiState) -> Unit)? = null
    private var operationJob: Job? = null
    private var requirementsJob: Job? = null
    private var state = initialRegistrationUiState()
        set(value) {
            field = value
            observer?.invoke(value)
        }

    val legalAcceptance = IosLegalAcceptanceController { type, accepted ->
        reduce(RegistrationIntent.UpdateLegalAcceptance(type, accepted))
    }

    val isConfigured: Boolean get() = presenter != null

    fun observe(observer: (RegistrationUiState) -> Unit) {
        this.observer = observer
        observer(state)
    }

    fun resumeIncompleteSession(session: AuthSession?) {
        resumeIncompleteSession(session = session, resumesAtProfile = false)
    }

    fun resumeIncompleteSocialSession(
        session: AuthSession?,
        suggestedFirstName: String?,
        suggestedLastName: String?,
        suggestedCityId: String?,
    ) {
        resumeIncompleteSession(
            session = session,
            resumesAtProfile = true,
            suggestedFirstName = suggestedFirstName,
            suggestedLastName = suggestedLastName,
            suggestedCityId = suggestedCityId,
        )
    }

    private fun resumeIncompleteSession(
        session: AuthSession?,
        resumesAtProfile: Boolean,
        suggestedFirstName: String? = null,
        suggestedLastName: String? = null,
        suggestedCityId: String? = null,
    ) {
        if (session?.accountSetupStatus != AccountSetupStatus.OnboardingRequired) return
        val isSameSessionInProgress = state.currentSession?.userId == session.userId &&
            state.step != RegistrationStep.Email
        if (isSameSessionInProgress && (!resumesAtProfile || state.step != RegistrationStep.Password)) return
        operationJob?.cancel()
        requirementsJob?.cancel()
        state = initialRegistrationUiState(RegistrationStartContext(suggestedCityId)).copy(
            step = if (resumesAtProfile) RegistrationStep.Profile else RegistrationStep.Password,
            method = if (resumesAtProfile) RegistrationMethod.Federated else RegistrationMethod.Email,
            email = session.email.orEmpty(),
            firstName = suggestedFirstName ?: session.suggestedFirstName.orEmpty(),
            lastName = suggestedLastName ?: session.suggestedLastName.orEmpty(),
            currentSession = session,
        )
        dispatchAsync(IosRegistrationLoadRequirementsIntent)
    }

    fun prepare(suggestedCityId: String?) {
        if (state.currentSession != null || state.step != RegistrationStep.Email) return
        if (
            state.requirementsStatus == RegistrationRequirementsStatus.Loading ||
            state.requirementsStatus == RegistrationRequirementsStatus.Ready
        ) {
            return
        }
        state = state.copy(startContext = RegistrationStartContext(suggestedCityId))
        dispatchAsync(IosRegistrationLoadRequirementsIntent)
    }

    fun reset() {
        operationJob?.cancel()
        operationJob = null
        requirementsJob?.cancel()
        requirementsJob = null
        state = initialRegistrationUiState()
    }

    fun dispatch(intent: IosRegistrationIntent) {
        when (intent) {
            is IosRegistrationFieldIntent -> reduce(intent.toSharedIntent())
            is IosRegistrationNavigationIntent -> reduce(intent.toSharedIntent())
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

    private fun dispatchAsync(intent: IosRegistrationAsyncIntent) {
        if (intent == IosRegistrationLoadRequirementsIntent) {
            launchRequirementsOperation()
            return
        }
        launchOperation { currentPresenter, currentState ->
            when (intent) {
                IosRegistrationRequestOtpIntent -> currentPresenter.requestOtp(currentState, strings)
                is IosRegistrationVerifyOtpIntent -> currentPresenter.verifyOtp(currentState, intent.otpCode, strings)
                is IosRegistrationSetInitialPasswordIntent -> currentPresenter.setInitialPassword(
                    state = currentState,
                    password = intent.password,
                    strings = strings,
                )
                IosRegistrationLoadRequirementsIntent -> currentState
                IosRegistrationCompleteProfileIntent -> currentPresenter.completeValidatedProfile(currentState)
            }
        }
    }

    private suspend fun RegistrationPresenter.completeValidatedProfile(
        state: RegistrationUiState,
    ): RegistrationUiState {
        val validatedState = reducer.reduce(
            state = state,
            intent = RegistrationIntent.CompleteProfile,
            strings = strings,
        )
        return if (validatedState.errorMessage == null) {
            completeOnboarding(validatedState, strings)
        } else {
            validatedState
        }
    }

    private fun launchOperation(
        operation: suspend (RegistrationPresenter, RegistrationUiState) -> RegistrationUiState,
    ) {
        val currentPresenter = presenter ?: return
        if (state.isLoading) return
        operationJob?.cancel()
        state = state.copy(isLoading = true, errorMessage = null, noticeMessage = null)
        operationJob = scope.launch {
            state = operation(currentPresenter, state).copy(isLoading = false)
        }
    }

    private fun launchRequirementsOperation() {
        val currentPresenter = presenter ?: return
        if (state.requirementsStatus == RegistrationRequirementsStatus.Loading) return
        requirementsJob?.cancel()
        state = state.copy(
            requirementsStatus = RegistrationRequirementsStatus.Loading,
            requirementsErrorMessage = null,
        )
        requirementsJob = scope.launch {
            val loadedRequirements = currentPresenter.loadRequirements(state, strings)
            state = state.mergeRequirementsFrom(loadedRequirements)
        }
    }

    private fun updateState(transform: (RegistrationUiState) -> RegistrationUiState) {
        if (state.isLoading) return
        state = transform(state)
    }
}

private fun IosRegistrationFieldIntent.toSharedIntent(): RegistrationIntent.Field = when (this) {
    is IosRegistrationUpdateEmailIntent -> RegistrationIntent.UpdateEmail(email)
    is IosRegistrationUpdateFirstNameIntent -> RegistrationIntent.UpdateFirstName(firstName)
    is IosRegistrationUpdateLastNameIntent -> RegistrationIntent.UpdateLastName(lastName)
    is IosRegistrationSelectCityIntent -> RegistrationIntent.SelectCity(cityId)
    is IosRegistrationSelectCurrencyIntent -> RegistrationIntent.SelectCurrency(currency)
}

private fun IosRegistrationNavigationIntent.toSharedIntent(): RegistrationIntent.Navigation = when (this) {
    IosRegistrationGoBackIntent -> RegistrationIntent.GoBack
}
