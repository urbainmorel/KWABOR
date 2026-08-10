package com.kwabor.android.presentation.auth

import android.app.Activity
import androidx.lifecycle.ViewModel
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.auth.AuthUiState
import com.kwabor.shared.presentation.auth.RegistrationUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn

internal class AuthViewModel(
    private val dependencies: AuthViewModelDependencies,
    strings: KwaborStrings,
    coroutineScope: CoroutineScope,
) : ViewModel() {
    private val runtime = AuthViewModelRuntime(
        registrationPresenter = dependencies.registrationPresenter,
        passwordRecoveryPresenter = dependencies.passwordRecoveryPresenter,
        strings = strings,
        coroutineScope = coroutineScope,
    )
    private val accountDeletionProviderCleanup = AccountDeletionProviderCleanupCoordinator(
        store = dependencies.accountDeletionProviderCleanupStore,
        googleIdentityProvider = dependencies.googleIdentityProvider,
        ioDispatcher = dependencies.accountDeletionIoDispatcher,
    )
    private val sessionCoordinator = AuthSessionCoordinator(
        runtime = runtime,
        dependencies = dependencies,
        accountDeletionProviderCleanup = accountDeletionProviderCleanup,
        onSessionRestoreReady = ::drainProviderCleanupBlockedPromoterCallback,
    )
    private val countdownCoordinator = OtpCountdownCoordinator(runtime, dependencies)
    private val credentialsCoordinator = AuthCredentialsCoordinator(
        runtime,
        countdownCoordinator,
        dependencies.authJourneyStore,
        sessionCoordinator,
        dependencies.track,
    )
    private val signInCoordinator = AuthSignInCoordinator(runtime, dependencies, sessionCoordinator)
    private val federatedCoordinator = AuthFederatedCoordinator(runtime, dependencies, sessionCoordinator)
    private val accountDeletionCoordinator = AccountDeletionCoordinator(
        runtime = runtime,
        dependencies = dependencies,
        providerCleanup = accountDeletionProviderCleanup,
    )
    private val promoterActivationCoordinator = PromoterActivationCoordinator(
        runtime = runtime,
        dependencies = dependencies,
        retrySessionRestore = sessionCoordinator::retrySessionRestore,
    )
    private val passwordRecoveryCoordinator = PasswordRecoveryCoordinator(
        runtime = runtime,
        dependencies = dependencies,
    )
    private val profileCoordinator = AuthProfileCoordinator(runtime, dependencies)
    private val platformCoordinator = AuthPlatformCoordinator(runtime)
    private var providerCleanupBlockedPromoterCallback: String? = null

    val state: StateFlow<AuthUiState> = runtime.authState.asStateFlow()
    val accessState: StateFlow<AuthAccessUiState> = runtime.accessState.asStateFlow()
    val registrationState: StateFlow<RegistrationUiState> = runtime.registrationState.asStateFlow()
    val passwordRecoveryState = runtime.passwordRecoveryState.asStateFlow()
    val promoterActivationState: StateFlow<PromoterActivationUiState> =
        runtime.promoterActivationState.asStateFlow()
    val platformState: StateFlow<AuthPlatformUiState> = runtime.platformState.asStateFlow()
    val isSessionRestoreComplete: StateFlow<Boolean> = runtime.sessionRestoreComplete.asStateFlow()
    val sessionRestoreStatus: StateFlow<AuthSessionRestoreStatus> =
        runtime.sessionRestoreStatus.asStateFlow()
    val accountDeletionBlocksViewerSession: StateFlow<Boolean> = combine(
        runtime.accessState,
        runtime.accountDeletionNavigationPending,
        runtime.accountDeletionOutcomeUnknown,
    ) { accessState, navigationPending, outcomeUnknown ->
        accessState.accountDeletionInProgress || navigationPending || outcomeUnknown
    }.stateIn(
        scope = runtime.coroutineScope,
        started = SharingStarted.Eagerly,
        initialValue = false,
    )
    val effects: Flow<AuthEffect> = runtime.effectChannel.receiveAsFlow()
    val platformEffects: Flow<AuthPlatformEffect> = runtime.platformEffectChannel.receiveAsFlow()

    init {
        observeProviderCleanupCallbackDrain()
        sessionCoordinator.loadCurrentSession(passwordRecoveryCoordinator::resumeSession)
    }

    fun onIntent(intent: AuthIntent) {
        if (blocksWhileAccountDeletionProviderCleanupPending(intent)) return
        if (blocksWhileSessionCleanupFailed(intent)) return
        if (promoterActivationCoordinator.blocks(intent)) return
        dispatch(intent)
    }

    private fun dispatch(intent: AuthIntent) {
        when (intent) {
            AuthIntent.OpenPasswordRecovery -> passwordRecoveryCoordinator.open()
            is AuthIntent.Journey -> handleJourneyIntent(intent)
            is AuthIntent.Credentials -> credentialsCoordinator.handle(intent)
            is AuthIntent.SignIn -> signInCoordinator.handle(intent)
            is AuthIntent.Federated -> federatedCoordinator.handle(intent)
            is AuthIntent.AccountSecurity -> accountDeletionCoordinator.handle(intent)
            is AuthIntent.PromoterActivation -> promoterActivationCoordinator.handle(intent)
            is AuthIntent.PasswordRecovery -> passwordRecoveryCoordinator.handle(intent)
            is AuthIntent.Profile -> profileCoordinator.handle(intent)
            is AuthIntent.Platform -> platformCoordinator.handle(intent)
        }
    }

    private fun handleJourneyIntent(intent: AuthIntent.Journey) {
        if (
            intent == AuthIntent.RetrySessionRestore &&
            runtime.accountDeletionOutcomeUnknown.value
        ) {
            sessionCoordinator.retrySessionRestore()
            return
        }
        if (intent == AuthIntent.Back && runtime.platformState.value.surface == AuthSurface.PasswordRecovery) {
            passwordRecoveryCoordinator.goBack()
        } else {
            sessionCoordinator.handle(intent)
        }
        if (intent == AuthIntent.RetrySessionRestore) {
            promoterActivationCoordinator.resumePendingCallbackAfterSessionRestoreRetry()
        }
    }

    private fun blocksWhileSessionCleanupFailed(intent: AuthIntent): Boolean {
        if (runtime.sessionRestoreStatus.value != AuthSessionRestoreStatus.Failed) return false
        return when (intent) {
            AuthIntent.RetrySessionRestore -> false
            is AuthIntent.OpenPromoterActivation -> false
            AuthIntent.AccountDeletionNavigationHandled -> false
            else -> true
        }
    }

    private fun blocksWhileAccountDeletionProviderCleanupPending(intent: AuthIntent): Boolean {
        val blockState = AccountDeletionProviderCleanupBlockState(
            providerCleanupPending = accountDeletionProviderCleanup.pending.value,
            retainedCallbackPending = providerCleanupBlockedPromoterCallback != null,
            deletionInProgress = runtime.accessState.value.accountDeletionInProgress,
            navigationPending = runtime.accountDeletionNavigationPending.value,
            outcomeUnknown = runtime.accountDeletionOutcomeUnknown.value,
            restoreStatus = runtime.sessionRestoreStatus.value,
        )
        if (blockState.allowsPromoterCallbackAfterFailedRestore(intent)) return false
        if (!blockState.blocksAuthentication()) return false
        return when (intent) {
            is AuthIntent.OpenPromoterActivation -> {
                if (blockState.deletionTerminal) {
                    discardProviderCleanupBlockedPromoterCallback()
                } else if (providerCleanupBlockedPromoterCallback == null) {
                    providerCleanupBlockedPromoterCallback = intent.callbackUrl
                }
                true
            }
            AuthIntent.RetrySessionRestore -> false
            AuthIntent.AccountDeletionNavigationHandled -> {
                if (blockState.deletionTerminal) discardProviderCleanupBlockedPromoterCallback()
                false
            }
            else -> true
        }
    }

    private fun drainProviderCleanupBlockedPromoterCallback() {
        val action = retainedPromoterCallbackAction(
            pending = accountDeletionProviderCleanup.pending.value,
            deletionInProgress = runtime.accessState.value.accountDeletionInProgress,
            restoreStatus = runtime.sessionRestoreStatus.value,
            navigationPending = runtime.accountDeletionNavigationPending.value,
            outcomeUnknown = runtime.accountDeletionOutcomeUnknown.value,
        )
        if (action != RetainedPromoterCallbackAction.DRAIN) return
        val callbackUrl = providerCleanupBlockedPromoterCallback ?: return
        providerCleanupBlockedPromoterCallback = null
        onIntent(AuthIntent.OpenPromoterActivation(callbackUrl))
    }

    private fun discardProviderCleanupBlockedPromoterCallback() {
        providerCleanupBlockedPromoterCallback = null
    }

    private fun observeProviderCleanupCallbackDrain() {
        combine(
            accountDeletionProviderCleanup.pending,
            runtime.accessState,
            runtime.sessionRestoreStatus,
            runtime.accountDeletionNavigationPending,
            runtime.accountDeletionOutcomeUnknown,
        ) { pending, accessState, restoreStatus, navigationPending, outcomeUnknown ->
            retainedPromoterCallbackAction(
                pending = pending,
                deletionInProgress = accessState.accountDeletionInProgress,
                restoreStatus = restoreStatus,
                navigationPending = navigationPending,
                outcomeUnknown = outcomeUnknown,
            )
        }
            .distinctUntilChanged()
            .onEach { action ->
                when (action) {
                    RetainedPromoterCallbackAction.KEEP -> Unit
                    RetainedPromoterCallbackAction.DRAIN -> drainProviderCleanupBlockedPromoterCallback()
                    RetainedPromoterCallbackAction.DISCARD -> discardProviderCleanupBlockedPromoterCallback()
                }
            }
            .launchIn(runtime.coroutineScope)
    }

    fun onForeground() {
        if (runtime.accountDeletionOutcomeUnknown.value) {
            sessionCoordinator.retrySessionRestore()
            return
        }
        if (
            !accountDeletionProviderCleanup.pending.value &&
            runtime.sessionRestoreStatus.value != AuthSessionRestoreStatus.Failed
        ) {
            return
        }
        sessionCoordinator.retrySessionRestore()
        promoterActivationCoordinator.resumePendingCallbackAfterSessionRestoreRetry()
    }

    fun attachGoogleIdentityActivity(activity: Activity) {
        dependencies.googleIdentityProvider.attachActivity(activity)
    }

    fun detachGoogleIdentityActivity(activity: Activity) {
        dependencies.googleIdentityProvider.detachActivity(activity)
    }

    override fun onCleared() {
        accountDeletionCoordinator.clearSensitiveState()
        promoterActivationCoordinator.clearSensitiveState()
        runtime.effectChannel.close()
        runtime.platformEffectChannel.close()
        runtime.coroutineScope.cancel()
        super.onCleared()
    }
}

private fun retainedPromoterCallbackAction(
    pending: Boolean,
    deletionInProgress: Boolean,
    restoreStatus: AuthSessionRestoreStatus,
    navigationPending: Boolean,
    outcomeUnknown: Boolean,
): RetainedPromoterCallbackAction = when {
    navigationPending || outcomeUnknown -> RetainedPromoterCallbackAction.DISCARD
    !pending && !deletionInProgress && restoreStatus == AuthSessionRestoreStatus.Ready ->
        RetainedPromoterCallbackAction.DRAIN
    else -> RetainedPromoterCallbackAction.KEEP
}

private enum class RetainedPromoterCallbackAction {
    KEEP,
    DRAIN,
    DISCARD,
}

private data class AccountDeletionProviderCleanupBlockState(
    val providerCleanupPending: Boolean,
    val retainedCallbackPending: Boolean,
    val deletionInProgress: Boolean,
    val navigationPending: Boolean,
    val outcomeUnknown: Boolean,
    val restoreStatus: AuthSessionRestoreStatus,
) {
    val deletionTerminal: Boolean = navigationPending || outcomeUnknown

    fun allowsPromoterCallbackAfterFailedRestore(intent: AuthIntent): Boolean {
        if (intent !is AuthIntent.OpenPromoterActivation) return false
        if (deletionTerminal) return false
        if (deletionInProgress) return false
        if (retainedCallbackPending) return false
        return restoreStatus == AuthSessionRestoreStatus.Failed
    }

    fun blocksAuthentication(): Boolean {
        if (providerCleanupPending) return true
        if (retainedCallbackPending) return true
        if (deletionInProgress) return true
        return deletionTerminal
    }
}
