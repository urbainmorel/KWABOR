package com.kwabor.android.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.kwabor.android.auth.LegalDocumentOpenResult
import com.kwabor.android.presentation.auth.AuthEntryPoint
import com.kwabor.android.presentation.auth.AuthIntent
import com.kwabor.android.presentation.auth.AuthPlatformEffect
import com.kwabor.android.presentation.auth.AuthViewModel
import com.kwabor.android.ui.screens.auth.AuthSheetActions
import com.kwabor.android.ui.screens.auth.PasswordRecoveryScreenActions
import com.kwabor.android.ui.screens.auth.PromoterActivationScreenActions
import com.kwabor.android.ui.screens.auth.RegistrationScreenActions
import com.kwabor.android.ui.screens.auth.SignInScreenActions
import com.kwabor.android.ui.screens.profile.ProfileSessionScreenActions

@Composable
internal fun AuthPlatformEffectHandler(dependencies: KwaborAppDependencies) {
    LaunchedEffect(dependencies.authViewModel, dependencies.legalDocumentLauncher) {
        dependencies.authViewModel.platformEffects.collect { effect ->
            when (effect) {
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
    onGoogleSignIn = { onIntent(AuthIntent.ContinueWithGoogle) },
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
    onSubmitPassword = { password -> onIntent(AuthIntent.SubmitPassword(password)) },
    onGoogleSignIn = { onIntent(AuthIntent.ContinueWithGoogle) },
    onFirstNameChange = { firstName -> onIntent(AuthIntent.ChangeFirstName(firstName)) },
    onLastNameChange = { lastName -> onIntent(AuthIntent.ChangeLastName(lastName)) },
    onRetryRequirements = { onIntent(AuthIntent.RetryRequirements) },
    onCitySelected = { cityId -> onIntent(AuthIntent.SelectCity(cityId)) },
    onCurrencySelected = { currency -> onIntent(AuthIntent.SelectCurrency(currency)) },
    onLegalAcceptanceChanged = { type, accepted ->
        onIntent(AuthIntent.ChangeLegalAcceptance(type, accepted))
    },
    onOpenLegalDocument = { type -> onIntent(AuthIntent.OpenLegalDocument(type)) },
    onCompleteProfile = { onIntent(AuthIntent.CompleteProfile) },
)

internal fun AuthViewModel.signInActions(): SignInScreenActions = SignInScreenActions(
    onBack = { onIntent(AuthIntent.Back) },
    onEmailChange = { email -> onIntent(AuthIntent.ChangeSignInEmail(email)) },
    onContinueFromEmail = { onIntent(AuthIntent.ContinueFromSignInEmail) },
    onSubmitPassword = { password -> onIntent(AuthIntent.SubmitSignInPassword(password)) },
    onGoogleSignIn = { onIntent(AuthIntent.ContinueWithGoogle) },
    onForgotPassword = { onIntent(AuthIntent.OpenPasswordRecovery) },
    onSignUp = { onIntent(AuthIntent.OpenRegistration(platformState.value.entryPoint)) },
)

internal fun AuthViewModel.passwordRecoveryActions(): PasswordRecoveryScreenActions = PasswordRecoveryScreenActions(
    onBack = { onIntent(AuthIntent.Back) },
    onEmailChange = { email -> onIntent(AuthIntent.ChangeRecoveryEmail(email)) },
    onRequestCode = { onIntent(AuthIntent.RequestRecoveryOtp) },
    onSubmitOtp = { code -> onIntent(AuthIntent.SubmitRecoveryOtp(code)) },
    onResendCode = { onIntent(AuthIntent.ResendRecoveryOtp) },
    onSubmitPassword = { password, confirmation ->
        onIntent(AuthIntent.SubmitRecoveryPassword(password, confirmation))
    },
)

internal fun AuthViewModel.profileSessionActions(): ProfileSessionScreenActions = ProfileSessionScreenActions(
    onRequestSignOut = { onIntent(AuthIntent.RequestSignOut) },
    onCancelSignOut = { onIntent(AuthIntent.CancelSignOut) },
    onConfirmSignOut = { onIntent(AuthIntent.ConfirmSignOut) },
    onRequestAccountDeletion = { onIntent(AuthIntent.RequestAccountDeletion) },
    onCancelAccountDeletion = { onIntent(AuthIntent.CancelAccountDeletion) },
    onDeleteAccountWithPassword = { password, confirmation ->
        onIntent(AuthIntent.DeleteAccountWithPassword(password, confirmation))
    },
    onDeleteAccountWithGoogle = { confirmation ->
        onIntent(AuthIntent.DeleteAccountWithGoogle(confirmation))
    },
)

internal fun AuthViewModel.promoterActivationActions(): PromoterActivationScreenActions =
    PromoterActivationScreenActions(
        onBack = { onIntent(AuthIntent.CancelPromoterActivation) },
        onRetryLink = { onIntent(AuthIntent.RetryPromoterActivationLink) },
        onActivateWithPassword = { password -> onIntent(AuthIntent.ActivatePromoterWithPassword(password)) },
        onActivateWithGoogle = { onIntent(AuthIntent.ActivatePromoterWithGoogle) },
        onFinish = { onIntent(AuthIntent.FinishPromoterActivation) },
    )
