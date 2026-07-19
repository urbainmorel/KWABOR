package com.kwabor.android.presentation.auth

import com.kwabor.shared.domain.auth.LegalDocumentType
import com.kwabor.shared.domain.money.KwaborCurrency

internal enum class AuthSurface {
    Hidden,
    SoftWall,
    Registration,
    SignIn,
    PasswordRecovery,
}

internal enum class AuthEntryPoint {
    Landing,
    SoftWall,
}

internal enum class RegistrationLocationStatus {
    Idle,
    Loading,
    PermissionDenied,
    LocationDisabled,
    Unavailable,
    OutsideBenin,
}

internal data class AuthPlatformUiState(
    val surface: AuthSurface = AuthSurface.Hidden,
    val entryPoint: AuthEntryPoint = AuthEntryPoint.Landing,
    val locationStatus: RegistrationLocationStatus = RegistrationLocationStatus.Idle,
    val locationPermissionRequestInFlight: Boolean = false,
    val otpResendSecondsRemaining: Int = 0,
    val legalDocumentOpenFailed: Boolean = false,
    val observabilityConsentPersistenceFailed: Boolean = false,
    val notificationPermissionRequestInFlight: Boolean = false,
    val notificationPrimingPersistenceFailed: Boolean = false,
)

internal enum class SignInStep {
    Email,
    Password,
}

internal data class AuthAccessUiState(
    val signInStep: SignInStep = SignInStep.Email,
    val signInEmail: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val noticeMessage: String? = null,
    val recoveryResendSecondsRemaining: Int = 0,
    val signOutConfirmationVisible: Boolean = false,
    val signOutInProgress: Boolean = false,
    val signOutErrorMessage: String? = null,
)

internal sealed interface AuthIntent {
    sealed interface Journey : AuthIntent

    sealed interface Credentials : AuthIntent

    sealed interface SignIn : AuthIntent

    sealed interface PasswordRecovery : AuthIntent

    sealed interface ProfileField : AuthIntent

    sealed interface ProfileProgress : AuthIntent

    sealed interface Platform : AuthIntent

    data object OpenSoftWall : Journey

    data class OpenRegistration(val entryPoint: AuthEntryPoint = AuthEntryPoint.Landing) : Journey

    data class OpenSignIn(val entryPoint: AuthEntryPoint = AuthEntryPoint.Landing) : Journey

    data object Dismiss : Journey

    data object ContinueAsGuest : Journey

    data object Back : Journey

    data object OpenPasswordRecovery : Journey

    data object RequestSignOut : Journey

    data object CancelSignOut : Journey

    data object ConfirmSignOut : Journey

    data object SignOutNavigationHandled : Journey

    data class ChangeEmail(val email: String) : Credentials

    data object RequestOtp : Credentials

    class SubmitOtp(val code: String) : Credentials {
        override fun toString(): String = "SubmitOtp(code=<redacted>)"
    }

    data object ResendOtp : Credentials

    class SubmitPassword(val password: String, val confirmation: String) : Credentials {
        override fun toString(): String = "SubmitPassword(password=<redacted>, confirmation=<redacted>)"
    }

    data object RetryRequirements : Credentials

    data class ChangeSignInEmail(val email: String) : SignIn

    data object ContinueFromSignInEmail : SignIn

    class SubmitSignInPassword(val password: String) : SignIn {
        override fun toString(): String = "SubmitSignInPassword(password=<redacted>)"
    }

    data class ChangeRecoveryEmail(val email: String) : PasswordRecovery

    data object RequestRecoveryOtp : PasswordRecovery

    data object ResendRecoveryOtp : PasswordRecovery

    class SubmitRecoveryOtp(val code: String) : PasswordRecovery {
        override fun toString(): String = "SubmitRecoveryOtp(code=<redacted>)"
    }

    class SubmitRecoveryPassword(
        val password: String,
        val confirmation: String,
    ) : PasswordRecovery {
        override fun toString(): String = "SubmitRecoveryPassword(password=<redacted>, confirmation=<redacted>)"
    }

    data class ChangeFirstName(val firstName: String) : ProfileField

    data class ChangeLastName(val lastName: String) : ProfileField

    data class SelectCity(val cityId: String) : ProfileField

    data class SelectCurrency(val currency: KwaborCurrency) : ProfileField

    data class ChangeLegalAcceptance(val type: LegalDocumentType, val accepted: Boolean) : ProfileField

    data class ChangeAnalyticsConsent(val accepted: Boolean) : ProfileField

    data class ChangeDiagnosticsConsent(val accepted: Boolean) : ProfileField

    data class ChangeRemoteConfigurationConsent(val accepted: Boolean) : ProfileField

    data object ContinueFromIdentity : ProfileProgress

    data object ContinueFromCity : ProfileProgress

    data object ContinueFromCurrency : ProfileProgress

    data object ContinueFromLegal : ProfileProgress

    data object CompleteOnboarding : ProfileProgress

    data object RequestLocation : Platform

    data class LocationPermissionResult(val granted: Boolean) : Platform

    data class OpenLegalDocument(val type: LegalDocumentType) : Platform

    data object LegalDocumentOpenFailed : Platform

    data object EnableNotifications : Platform

    data object SkipNotifications : Platform

    data class NotificationPermissionResult(val granted: Boolean) : Platform
}

internal sealed interface AuthEffect {
    data object AuthenticationCompleted : AuthEffect

    data object GuestContinuationSelected : AuthEffect

    data object SignedOut : AuthEffect
}

internal sealed interface AuthPlatformEffect {
    data object RequestLocationPermission : AuthPlatformEffect

    data object RequestNotificationPermission : AuthPlatformEffect

    data class OpenLegalDocument(val url: String) : AuthPlatformEffect
}
