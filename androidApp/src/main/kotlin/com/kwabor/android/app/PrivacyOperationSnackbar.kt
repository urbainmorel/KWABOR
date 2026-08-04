package com.kwabor.android.app

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.kwabor.shared.i18n.KwaborStrings

@Composable
internal fun rememberPrivacySnackbarHostState(
    state: HomeShellState,
    strings: KwaborStrings,
    dependencies: HomeShellDependencies,
): SnackbarHostState {
    val snackbarHostState = remember { SnackbarHostState() }
    val currentOnRetry by rememberUpdatedState(
        dependencies.observabilityController::retryPendingMaintenance,
    )
    val operationFailed = state.observabilityPrivacyOperationFailed
    val persistenceErrorMessage = strings.settings.privacyPersistenceError
    val retryLabel = strings.retry
    LaunchedEffect(operationFailed, persistenceErrorMessage, retryLabel) {
        if (!operationFailed) {
            snackbarHostState.currentSnackbarData?.dismiss()
            return@LaunchedEffect
        }
        var retryFailed: Boolean
        do {
            val result = snackbarHostState.showSnackbar(
                message = persistenceErrorMessage,
                actionLabel = retryLabel,
                duration = SnackbarDuration.Indefinite,
            )
            retryFailed = result == SnackbarResult.ActionPerformed && !currentOnRetry()
        } while (retryFailed)
    }
    return snackbarHostState
}
