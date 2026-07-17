package com.kwabor.android.app

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.kwabor.android.auth.LegalDocumentOpenResult
import com.kwabor.android.presentation.auth.AuthEntryPoint
import com.kwabor.android.presentation.auth.AuthIntent
import com.kwabor.android.presentation.auth.AuthPlatformEffect
import com.kwabor.android.presentation.auth.AuthViewModel
import com.kwabor.android.ui.screens.auth.AuthSheetActions
import com.kwabor.android.ui.screens.auth.RegistrationScreenActions

@Composable
internal fun AuthPlatformEffectHandler(dependencies: KwaborAppDependencies) {
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        dependencies.authViewModel.onIntent(AuthIntent.LocationPermissionResult(granted))
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        dependencies.authViewModel.onIntent(AuthIntent.NotificationPermissionResult(granted))
    }

    LaunchedEffect(dependencies.authViewModel, dependencies.legalDocumentLauncher) {
        dependencies.authViewModel.platformEffects.collect { effect ->
            when (effect) {
                AuthPlatformEffect.RequestLocationPermission -> {
                    locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
                AuthPlatformEffect.RequestNotificationPermission -> {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                is AuthPlatformEffect.OpenLegalDocument -> {
                    if (dependencies.legalDocumentLauncher.openHttps(effect.url) != LegalDocumentOpenResult.Opened) {
                        dependencies.authViewModel.onIntent(AuthIntent.LegalDocumentOpenFailed)
                    }
                }
            }
        }
    }
}

internal fun AuthViewModel.sheetActions(): AuthSheetActions = AuthSheetActions(
    onDismiss = { onIntent(AuthIntent.Dismiss) },
    onSignUp = { onIntent(AuthIntent.OpenRegistration(AuthEntryPoint.SoftWall)) },
    onSignIn = { onIntent(AuthIntent.OpenSignIn(AuthEntryPoint.SoftWall)) },
    onLater = { onIntent(AuthIntent.ContinueAsGuest) },
)

internal fun AuthViewModel.registrationActions(): RegistrationScreenActions = RegistrationScreenActions(
    onBack = { onIntent(AuthIntent.Back) },
    onEmailChange = { email -> onIntent(AuthIntent.ChangeEmail(email)) },
    onRequestOtp = { onIntent(AuthIntent.RequestOtp) },
    onSubmitOtp = { code -> onIntent(AuthIntent.SubmitOtp(code)) },
    onResendOtp = { onIntent(AuthIntent.ResendOtp) },
    onSubmitPassword = { password, confirmation ->
        onIntent(AuthIntent.SubmitPassword(password, confirmation))
    },
    onFirstNameChange = { firstName -> onIntent(AuthIntent.ChangeFirstName(firstName)) },
    onLastNameChange = { lastName -> onIntent(AuthIntent.ChangeLastName(lastName)) },
    onContinueFromIdentity = { onIntent(AuthIntent.ContinueFromIdentity) },
    onRetryRequirements = { onIntent(AuthIntent.RetryRequirements) },
    onCitySelected = { cityId -> onIntent(AuthIntent.SelectCity(cityId)) },
    onUseLocation = { onIntent(AuthIntent.RequestLocation) },
    onContinueFromCity = { onIntent(AuthIntent.ContinueFromCity) },
    onCurrencySelected = { currency -> onIntent(AuthIntent.SelectCurrency(currency)) },
    onContinueFromCurrency = { onIntent(AuthIntent.ContinueFromCurrency) },
    onLegalAcceptanceChanged = { type, accepted ->
        onIntent(AuthIntent.ChangeLegalAcceptance(type, accepted))
    },
    onOpenLegalDocument = { type -> onIntent(AuthIntent.OpenLegalDocument(type)) },
    onContinueFromLegal = { onIntent(AuthIntent.ContinueFromLegal) },
    onAnalyticsConsentChanged = { accepted -> onIntent(AuthIntent.ChangeAnalyticsConsent(accepted)) },
    onDiagnosticsConsentChanged = { accepted -> onIntent(AuthIntent.ChangeDiagnosticsConsent(accepted)) },
    onRemoteConfigurationConsentChanged = { accepted ->
        onIntent(AuthIntent.ChangeRemoteConfigurationConsent(accepted))
    },
    onCompleteOnboarding = { onIntent(AuthIntent.CompleteOnboarding) },
    onEnableNotifications = { onIntent(AuthIntent.EnableNotifications) },
    onSkipNotifications = { onIntent(AuthIntent.SkipNotifications) },
)
