package com.kwabor.android.presentation.auth

import com.kwabor.shared.domain.auth.LegalDocumentType
import com.kwabor.shared.domain.money.KwaborCurrency

internal enum class AuthSurface {
    Hidden,
    SoftWall,
    Registration,
    SignIn,
    PasswordRecovery,
    PromoterActivation,
    SessionRestoreFailure,
}

internal enum class AuthEntryPoint {
    Landing,
    SoftWall,
}

internal enum class AuthProtectedAction {
    Like,
    Favorite,
    Other,
}

internal data class AuthSoftWallContext(
    val action: AuthProtectedAction,
    val suggestedCityId: String?,
)

internal data class AuthPlatformUiState(
    val surface: AuthSurface = AuthSurface.Hidden,
    val entryPoint: AuthEntryPoint = AuthEntryPoint.Landing,
    val softWallContext: AuthSoftWallContext? = null,
    val softWallErrorMessage: String? = null,
    val otpResendSecondsRemaining: Int = 0,
    val legalDocumentOpenFailed: Boolean = false,
    val federatedSignInInProgress: Boolean = false,
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
    val accountDeletionDialogVisible: Boolean = false,
    val accountDeletionInProgress: Boolean = false,
    val accountDeletionErrorMessage: String? = null,
)

internal enum class AuthSessionRestoreStatus {
    InProgress,
    Ready,
    Failed,
}

internal enum class PromoterActivationStage {
    Loading,
    Ready,
    Activating,
    Cancelling,
    Completed,
    Error,
}

internal data class PromoterActivationUiState(
    val stage: PromoterActivationStage = PromoterActivationStage.Loading,
    val businessName: String = "",
    val errorMessage: String? = null,
    val retryAvailable: Boolean = true,
)

internal sealed interface AuthIntent {
    sealed interface Journey : AuthIntent

    sealed interface Credentials : AuthIntent

    sealed interface SignIn : AuthIntent

    sealed interface PasswordRecovery : AuthIntent

    sealed interface Federated : AuthIntent

    sealed interface AccountSecurity : AuthIntent

    sealed interface PromoterActivation : AuthIntent

    sealed interface Profile : AuthIntent

    sealed interface ProfileField : Profile

    sealed interface ProfileProgress : Profile

    sealed interface Platform : AuthIntent

    data class OpenSoftWall(val context: AuthSoftWallContext) : Journey

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

    data object RetrySessionRestore : Journey

    data class ChangeEmail(val email: String) : Credentials

    data object RequestOtp : Credentials

    class SubmitOtp(val code: String) : Credentials {
        override fun toString(): String = "SubmitOtp(code=<redacted>)"
    }

    data object ResendOtp : Credentials

    class SubmitPassword(val password: String) : Credentials {
        override fun toString(): String = "SubmitPassword(password=<redacted>)"
    }

    data object RetryRequirements : Credentials

    data class ChangeSignInEmail(val email: String) : SignIn

    data object ContinueFromSignInEmail : SignIn

    class SubmitSignInPassword(val password: String) : SignIn {
        override fun toString(): String = "SubmitSignInPassword(password=<redacted>)"
    }

    data object ContinueWithGoogle : Federated

    data object RequestAccountDeletion : AccountSecurity

    data object CancelAccountDeletion : AccountSecurity

    data object AccountDeletionNavigationHandled : AccountSecurity

    class DeleteAccountWithPassword(
        val password: String,
        val confirmation: String,
    ) : AccountSecurity {
        override fun toString(): String = "DeleteAccountWithPassword(password=<redacted>, confirmation=<redacted>)"
    }

    class DeleteAccountWithGoogle(val confirmation: String) : AccountSecurity {
        override fun toString(): String = "DeleteAccountWithGoogle(confirmation=<redacted>)"
    }

    class OpenPromoterActivation(val callbackUrl: String) : PromoterActivation {
        override fun toString(): String = "OpenPromoterActivation(callbackUrl=<redacted>)"
    }

    data object RetryPromoterActivationLink : PromoterActivation

    class ActivatePromoterWithPassword(val password: String) : PromoterActivation {
        override fun toString(): String = "ActivatePromoterWithPassword(password=<redacted>)"
    }

    data object ActivatePromoterWithGoogle : PromoterActivation

    data object CancelPromoterActivation : PromoterActivation

    data object FinishPromoterActivation : PromoterActivation

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

    data object CompleteProfile : ProfileProgress

    data class OpenLegalDocument(val type: LegalDocumentType) : Platform

    data object LegalDocumentOpenFailed : Platform
}

internal sealed interface AuthEffect {
    data object AuthenticationCompleted : AuthEffect

    data object GuestContinuationSelected : AuthEffect

    data object SignedOut : AuthEffect

    data object AccountDeleted : AuthEffect

    data class PromoterActivationCompleted(
        val organizationId: String,
        val listingId: String,
    ) : AuthEffect
}

internal sealed interface AuthPlatformEffect {
    data class OpenLegalDocument(val url: String) : AuthPlatformEffect
}
