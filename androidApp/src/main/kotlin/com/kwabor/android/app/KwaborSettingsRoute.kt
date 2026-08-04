package com.kwabor.android.app

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.kwabor.android.observability.AndroidObservabilityController
import com.kwabor.android.ui.screens.profile.ProfileScreen
import com.kwabor.android.ui.screens.profile.ProfileScreenUiModel
import com.kwabor.android.ui.screens.settings.SettingsPrivacyActions
import com.kwabor.android.ui.screens.settings.SettingsScreen
import com.kwabor.android.ui.screens.settings.SettingsScreenCallbacks
import com.kwabor.android.ui.screens.settings.SettingsScreenUiModel
import com.kwabor.shared.domain.observability.ObservabilityConsent
import com.kwabor.shared.i18n.KwaborStrings

@Composable
internal fun ObservabilitySessionBindingEffect(userId: String?, controller: AndroidObservabilityController) {
    LaunchedEffect(userId, controller) {
        controller.bindToAuthenticatedUser(userId)
    }
}

internal fun NavGraphBuilder.profileRoute(
    navController: NavHostController,
    paddingValues: PaddingValues,
    strings: KwaborStrings,
    state: HomeShellState,
) {
    composable<ProfileRoute> {
        ProfileScreen(
            model = ProfileScreenUiModel(email = state.auth.currentSession?.email),
            strings = strings,
            onSettingsRequested = {
                navController.navigate(SettingsRoute) { launchSingleTop = true }
            },
            modifier = Modifier.padding(paddingValues),
        )
    }
}

internal fun NavGraphBuilder.settingsRoute(
    navController: NavHostController,
    paddingValues: PaddingValues,
    strings: KwaborStrings,
    dependencies: HomeShellDependencies,
    state: HomeShellState,
) {
    composable<SettingsRoute> {
        val ownerUserId = state.auth.currentSession?.userId
        SettingsScreen(
            model = SettingsScreenUiModel(
                email = state.auth.currentSession?.email,
                authenticationMethod = state.auth.currentSession?.authenticationMethod,
                authAccessState = state.authAccess,
                observabilityConsent = state.observabilityConsent,
                observabilityPrivacyOperationFailed = state.observabilityPrivacyOperationFailed,
            ),
            strings = strings,
            callbacks = SettingsScreenCallbacks(
                account = remember(dependencies.authViewModel) {
                    dependencies.authViewModel.settingsScreenActions()
                },
                privacy = rememberSettingsPrivacyActions(
                    controller = dependencies.observabilityController,
                    ownerUserId = ownerUserId,
                ),
                onBack = { navController.popBackStack() },
            ),
            modifier = Modifier.padding(bottom = paddingValues.calculateBottomPadding()),
        )
    }
}

@Composable
private fun rememberSettingsPrivacyActions(
    controller: AndroidObservabilityController,
    ownerUserId: String?,
): SettingsPrivacyActions = remember(controller, ownerUserId) {
    SettingsPrivacyActions(
        onAnalyticsConsentChanged = { allowed ->
            controller.updateConsent(ownerUserId) { consent ->
                consent.copy(analyticsAllowed = allowed)
            }
        },
        onDiagnosticsConsentChanged = { allowed ->
            controller.updateConsent(ownerUserId) { consent ->
                consent.copy(diagnosticsAllowed = allowed)
            }
        },
        onRemoteConfigurationConsentChanged = { allowed ->
            controller.updateConsent(ownerUserId) { consent ->
                consent.copy(remoteConfigurationAllowed = allowed)
            }
        },
    )
}

private fun AndroidObservabilityController.updateConsent(
    ownerUserId: String?,
    transform: (ObservabilityConsent) -> ObservabilityConsent,
): Boolean = ownerUserId?.let { userId ->
    updateConsent(userId, transform(consent.value))
} ?: updateConsent("", transform(consent.value))
