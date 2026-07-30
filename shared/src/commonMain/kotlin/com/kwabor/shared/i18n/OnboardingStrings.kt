package com.kwabor.shared.i18n

class OnboardingStrings internal constructor(
    common: OnboardingCommonStrings,
    intro: OnboardingIntroStrings,
    auth: OnboardingAuthStrings,
    security: OnboardingSecurityStrings,
    registration: OnboardingRegistrationStrings,
    settings: SettingsStrings,
) {
    val home: String = common.home
    val retry: String = common.retry
    val loading: String = common.loading
    val introSkip: String = intro.introSkip
    val introContinue: String = intro.introContinue
    val introAccessibilityLabel: String = intro.introAccessibilityLabel
    val title: String = intro.title
    val subtitle: String = intro.subtitle
    val signUp: String = intro.signUp
    val signIn: String = intro.signIn
    val continueWithoutAccount: String = intro.continueWithoutAccount
    val guestDisclosure: String = intro.guestDisclosure
    val guestConfirm: String = intro.guestConfirm
    val guestCancel: String = intro.guestCancel
    val languageLabel: String = intro.languageLabel
    val authTitle: String = auth.authTitle
    val authSubtitle: String = auth.authSubtitle
    val authEmail: String = auth.authEmail
    val authPassword: String = auth.authPassword
    val authEmailContinue: String = auth.authEmailContinue
    val authForgotPassword: String = auth.authForgotPassword
    val authCreateAccount: String = auth.authCreateAccount
    val authFirstName: String = auth.authFirstName
    val authLastName: String = auth.authLastName
    val authOtpCode: String = auth.authOtpCode
    val authRequestOtp: String = auth.authRequestOtp
    val authVerifyOtp: String = auth.authVerifyOtp
    val authLegalAcceptance: String = auth.authLegalAcceptance
    val authContinueAsGuest: String = auth.authContinueAsGuest
    val authUnavailable: String = auth.authUnavailable
    val authInvalidInput: String = auth.authInvalidInput
    val authInvalidCredentials: String = auth.authInvalidCredentials
    val authEmailNotConfirmed: String = auth.authEmailNotConfirmed
    val authRateLimited: String = auth.authRateLimited
    val authAccount: String = auth.authAccount
    val authSignOut: String = auth.authSignOut
    val authSignOutTitle: String = auth.authSignOutTitle
    val authSignOutConfirmation: String = auth.authSignOutConfirmation
    val authConfirm: String = auth.authConfirm
    val authCancel: String = auth.authCancel
    val authSignInWithGoogle: String = security.authSignInWithGoogle
    val authSignInWithApple: String = security.authSignInWithApple
    val authOrSeparator: String = security.authOrSeparator
    val authFederatedUnavailable: String = security.authFederatedUnavailable
    val authReauthenticationFailed: String = security.authReauthenticationFailed
    val dangerZoneTitle: String = security.dangerZoneTitle
    val authDeleteAccount: String = security.authDeleteAccount
    val authDeleteAccountWarning: String = security.authDeleteAccountWarning
    val authDeleteAccountPasswordPrompt: String = security.authDeleteAccountPasswordPrompt
    val authDeleteAccountConfirmationPrompt: String = security.authDeleteAccountConfirmationPrompt
    val authDeleteAccountConfirmationPhrase: String = security.authDeleteAccountConfirmationPhrase
    val authDeleteAccountConfirm: String = security.authDeleteAccountConfirm
    val authAccountDeletionFailed: String = security.authAccountDeletionFailed
    val promoterActivationTitle: String = security.promoterActivationTitle
    val promoterActivationBusinessName: String = security.promoterActivationBusinessName
    val promoterActivationInvitePrompt: String = security.promoterActivationInvitePrompt
    val promoterActivationPasswordPrompt: String = security.promoterActivationPasswordPrompt
    val promoterActivationSuccess: String = security.promoterActivationSuccess
    val authContinueWithPassword: String = security.authContinueWithPassword
    val authPromoterInviteInvalid: String = security.authPromoterInviteInvalid
    val passwordRecoveryTitle: String = auth.passwordRecoveryTitle
    val passwordRecoverySubtitle: String = auth.passwordRecoverySubtitle
    val passwordRecoveryCode: String = auth.passwordRecoveryCode
    val passwordRecoverySendCode: String = auth.passwordRecoverySendCode
    val passwordRecoveryCodeSent: String = auth.passwordRecoveryCodeSent
    val passwordRecoveryResendCode: String = auth.passwordRecoveryResendCode
    val passwordRecoveryResendCountdown: String = auth.passwordRecoveryResendCountdown
    val passwordRecoveryNewPassword: String = auth.passwordRecoveryNewPassword
    val passwordRecoveryConfirmation: String = auth.passwordRecoveryConfirmation
    val passwordRecoverySuccess: String = auth.passwordRecoverySuccess
    val passwordRecoveryBackToSignIn: String = auth.passwordRecoveryBackToSignIn
    val registrationTitle: String = registration.registrationTitle
    val registrationStepProgress: String = registration.registrationStepProgress
    val registrationFinalStep: String = registration.registrationFinalStep
    val registrationEditEmail: String = registration.registrationEditEmail
    val registrationPassword: String = registration.registrationPassword
    val registrationPasswordShow: String = registration.registrationPasswordShow
    val registrationPasswordHide: String = registration.registrationPasswordHide
    val registrationPasswordConfirmation: String = registration.registrationPasswordConfirmation
    val registrationIdentityTitle: String = registration.legacy.registrationIdentityTitle
    val registrationProfileTitle: String = registration.registrationProfileTitle
    val registrationProfileSupport: String = registration.registrationProfileSupport
    val registrationCityTitle: String = registration.registrationCityTitle
    val registrationUseLocation: String = registration.legacy.registrationUseLocation
    val registrationLocationPermissionDenied: String = registration.legacy.registrationLocationPermissionDenied
    val registrationLocationUnavailable: String = registration.legacy.registrationLocationUnavailable
    val registrationLocationOutsideBenin: String = registration.legacy.registrationLocationOutsideBenin
    val registrationCurrencyTitle: String = registration.registrationCurrencyTitle
    val registrationLegalTitle: String = registration.registrationLegalTitle
    val registrationTermsAcceptance: String = registration.registrationTermsAcceptance
    val registrationPrivacyAcceptance: String = registration.registrationPrivacyAcceptance
    val registrationUgcAcceptance: String = registration.registrationUgcAcceptance
    val registrationObservabilityTitle: String = registration.legacy.registrationObservabilityTitle
    val registrationObservabilitySupport: String = registration.legacy.registrationObservabilitySupport
    val registrationAnalyticsConsent: String = registration.legacy.registrationAnalyticsConsent
    val registrationDiagnosticsConsent: String = registration.legacy.registrationDiagnosticsConsent
    val registrationRemoteConfigConsent: String = registration.legacy.registrationRemoteConfigConsent
    val registrationNotificationTitle: String = registration.legacy.registrationNotificationTitle
    val registrationNotificationSupport: String = registration.legacy.registrationNotificationSupport
    val registrationNotificationEnable: String = registration.legacy.registrationNotificationEnable
    val registrationLater: String = registration.legacy.registrationLater
    val registrationContinue: String = registration.registrationContinue
    val registrationBack: String = registration.registrationBack
    val registrationOtpWait: String = registration.registrationOtpWait
    val registrationOtpResendCountdown: String = registration.registrationOtpResendCountdown
    val registrationOtpExpired: String = registration.registrationOtpExpired
    val registrationPasswordTooShort: String = registration.registrationPasswordTooShort
    val registrationPasswordMismatch: String = registration.registrationPasswordMismatch
    val registrationNameRequired: String = registration.registrationNameRequired
    val registrationNameTooLong: String = registration.registrationNameTooLong
    val registrationCityRequired: String = registration.registrationCityRequired
    val registrationLegalRequired: String = registration.registrationLegalRequired
    val registrationLegalUnavailable: String = registration.registrationLegalUnavailable
    val registrationComplete: String = registration.registrationComplete
    val registrationCompleteDefault: String = registration.registrationCompleteDefault
    val registrationCompleteContextual: String = registration.registrationCompleteContextual
    val settings: SettingsStrings = settings
}

internal data class OnboardingCommonStrings(
    val home: String,
    val retry: String,
    val loading: String,
)

internal data class OnboardingIntroStrings(
    val introSkip: String,
    val introContinue: String,
    val introAccessibilityLabel: String,
    val title: String,
    val subtitle: String,
    val signUp: String,
    val signIn: String,
    val continueWithoutAccount: String,
    val guestDisclosure: String,
    val guestConfirm: String,
    val guestCancel: String,
    val languageLabel: String,
)

internal data class OnboardingAuthStrings(
    val authTitle: String,
    val authSubtitle: String,
    val authEmail: String,
    val authPassword: String,
    val authEmailContinue: String,
    val authForgotPassword: String,
    val authCreateAccount: String,
    val authFirstName: String,
    val authLastName: String,
    val authOtpCode: String,
    val authRequestOtp: String,
    val authVerifyOtp: String,
    val authLegalAcceptance: String,
    val authContinueAsGuest: String,
    val authUnavailable: String,
    val authInvalidInput: String,
    val authInvalidCredentials: String,
    val authEmailNotConfirmed: String,
    val authRateLimited: String,
    val authAccount: String,
    val authSignOut: String,
    val authSignOutTitle: String,
    val authSignOutConfirmation: String,
    val authConfirm: String,
    val authCancel: String,
    val passwordRecoveryTitle: String,
    val passwordRecoverySubtitle: String,
    val passwordRecoveryCode: String,
    val passwordRecoverySendCode: String,
    val passwordRecoveryCodeSent: String,
    val passwordRecoveryResendCode: String,
    val passwordRecoveryResendCountdown: String,
    val passwordRecoveryNewPassword: String,
    val passwordRecoveryConfirmation: String,
    val passwordRecoverySuccess: String,
    val passwordRecoveryBackToSignIn: String,
)

internal data class OnboardingSecurityStrings(
    val authSignInWithGoogle: String,
    val authSignInWithApple: String,
    val authOrSeparator: String,
    val authFederatedUnavailable: String,
    val authReauthenticationFailed: String,
    val dangerZoneTitle: String,
    val authDeleteAccount: String,
    val authDeleteAccountWarning: String,
    val authDeleteAccountPasswordPrompt: String,
    val authDeleteAccountConfirmationPrompt: String,
    val authDeleteAccountConfirmationPhrase: String,
    val authDeleteAccountConfirm: String,
    val authAccountDeletionFailed: String,
    val promoterActivationTitle: String,
    val promoterActivationBusinessName: String,
    val promoterActivationInvitePrompt: String,
    val promoterActivationPasswordPrompt: String,
    val promoterActivationSuccess: String,
    val authContinueWithPassword: String,
    val authPromoterInviteInvalid: String,
)

internal data class OnboardingRegistrationStrings(
    val registrationTitle: String,
    val registrationStepProgress: String,
    val registrationFinalStep: String,
    val registrationEditEmail: String,
    val registrationPassword: String,
    val registrationPasswordShow: String,
    val registrationPasswordHide: String,
    val registrationPasswordConfirmation: String,
    val registrationProfileTitle: String,
    val registrationProfileSupport: String,
    val registrationCityTitle: String,
    val registrationCurrencyTitle: String,
    val registrationLegalTitle: String,
    val registrationTermsAcceptance: String,
    val registrationPrivacyAcceptance: String,
    val registrationUgcAcceptance: String,
    val registrationContinue: String,
    val registrationBack: String,
    val registrationOtpWait: String,
    val registrationOtpResendCountdown: String,
    val registrationOtpExpired: String,
    val registrationPasswordTooShort: String,
    val registrationPasswordMismatch: String,
    val registrationNameRequired: String,
    val registrationNameTooLong: String,
    val registrationCityRequired: String,
    val registrationLegalRequired: String,
    val registrationLegalUnavailable: String,
    val registrationComplete: String,
    val registrationCompleteDefault: String,
    val registrationCompleteContextual: String,
    val legacy: OnboardingLegacyRegistrationStrings,
)

internal data class OnboardingLegacyRegistrationStrings(
    val registrationIdentityTitle: String,
    val registrationUseLocation: String,
    val registrationLocationPermissionDenied: String,
    val registrationLocationUnavailable: String,
    val registrationLocationOutsideBenin: String,
    val registrationObservabilityTitle: String,
    val registrationObservabilitySupport: String,
    val registrationAnalyticsConsent: String,
    val registrationDiagnosticsConsent: String,
    val registrationRemoteConfigConsent: String,
    val registrationNotificationTitle: String,
    val registrationNotificationSupport: String,
    val registrationNotificationEnable: String,
    val registrationLater: String,
)

internal fun KwaborStrings.toOnboardingStrings(): OnboardingStrings = OnboardingStrings(
    common = toOnboardingCommonStrings(),
    intro = toOnboardingIntroStrings(),
    auth = toOnboardingAuthStrings(),
    security = toOnboardingSecurityStrings(),
    registration = toOnboardingRegistrationStrings(),
    settings = settings,
)

private fun KwaborStrings.toOnboardingCommonStrings(): OnboardingCommonStrings = OnboardingCommonStrings(
    home = home,
    retry = retry,
    loading = loading,
)

private fun KwaborStrings.toOnboardingIntroStrings(): OnboardingIntroStrings = OnboardingIntroStrings(
    introSkip = introSkip,
    introContinue = introContinue,
    introAccessibilityLabel = introAccessibilityLabel,
    title = onboardingTitle,
    subtitle = onboardingSubtitle,
    signUp = onboardingSignUp,
    signIn = onboardingSignIn,
    continueWithoutAccount = onboardingContinueWithoutAccount,
    guestDisclosure = onboardingGuestDisclosure,
    guestConfirm = onboardingGuestConfirm,
    guestCancel = onboardingGuestCancel,
    languageLabel = onboardingLanguageLabel,
)

private fun KwaborStrings.toOnboardingAuthStrings(): OnboardingAuthStrings = OnboardingAuthStrings(
    authTitle = authTitle,
    authSubtitle = authSubtitle,
    authEmail = authEmail,
    authPassword = authPassword,
    authEmailContinue = authEmailContinue,
    authForgotPassword = authForgotPassword,
    authCreateAccount = authCreateAccount,
    authFirstName = authFirstName,
    authLastName = authLastName,
    authOtpCode = authOtpCode,
    authRequestOtp = authRequestOtp,
    authVerifyOtp = authVerifyOtp,
    authLegalAcceptance = authLegalAcceptance,
    authContinueAsGuest = authContinueAsGuest,
    authUnavailable = configurationUnavailable,
    authInvalidInput = authInvalidInput,
    authInvalidCredentials = authInvalidCredentials,
    authEmailNotConfirmed = authEmailNotConfirmed,
    authRateLimited = authRateLimited,
    authAccount = authAccount,
    authSignOut = authSignOut,
    authSignOutTitle = authSignOutTitle,
    authSignOutConfirmation = authSignOutConfirmation,
    authConfirm = authConfirm,
    authCancel = authCancel,
    passwordRecoveryTitle = passwordRecoveryTitle,
    passwordRecoverySubtitle = passwordRecoverySubtitle,
    passwordRecoveryCode = passwordRecoveryCode,
    passwordRecoverySendCode = passwordRecoverySendCode,
    passwordRecoveryCodeSent = passwordRecoveryCodeSent,
    passwordRecoveryResendCode = passwordRecoveryResendCode,
    passwordRecoveryResendCountdown = passwordRecoveryResendCountdown,
    passwordRecoveryNewPassword = passwordRecoveryNewPassword,
    passwordRecoveryConfirmation = passwordRecoveryConfirmation,
    passwordRecoverySuccess = passwordRecoverySuccess,
    passwordRecoveryBackToSignIn = passwordRecoveryBackToSignIn,
)

private fun KwaborStrings.toOnboardingSecurityStrings(): OnboardingSecurityStrings = OnboardingSecurityStrings(
    authSignInWithGoogle = authSignInWithGoogle,
    authSignInWithApple = authSignInWithApple,
    authOrSeparator = authOrSeparator,
    authFederatedUnavailable = authFederatedUnavailable,
    authReauthenticationFailed = authReauthenticationFailed,
    dangerZoneTitle = dangerZoneTitle,
    authDeleteAccount = authDeleteAccount,
    authDeleteAccountWarning = authDeleteAccountWarning,
    authDeleteAccountPasswordPrompt = authDeleteAccountPasswordPrompt,
    authDeleteAccountConfirmationPrompt = authDeleteAccountConfirmationPrompt,
    authDeleteAccountConfirmationPhrase = authDeleteAccountConfirmationPhrase,
    authDeleteAccountConfirm = authDeleteAccountConfirm,
    authAccountDeletionFailed = authAccountDeletionFailed,
    promoterActivationTitle = promoterActivationTitle,
    promoterActivationBusinessName = promoterActivationBusinessName,
    promoterActivationInvitePrompt = promoterActivationInvitePrompt,
    promoterActivationPasswordPrompt = promoterActivationPasswordPrompt,
    promoterActivationSuccess = promoterActivationSuccess,
    authContinueWithPassword = authContinueWithPassword,
    authPromoterInviteInvalid = authPromoterInviteInvalid,
)

private fun KwaborStrings.toOnboardingRegistrationStrings(): OnboardingRegistrationStrings =
    OnboardingRegistrationStrings(
        registrationTitle = registrationTitle,
        registrationStepProgress = registrationStepProgress,
        registrationFinalStep = registrationFinalStep,
        registrationEditEmail = registrationEditEmail,
        registrationPassword = registrationPassword,
        registrationPasswordShow = registrationPasswordShow,
        registrationPasswordHide = registrationPasswordHide,
        registrationPasswordConfirmation = registrationPasswordConfirmation,
        registrationProfileTitle = registrationProfileTitle,
        registrationProfileSupport = registrationProfileSupport,
        registrationCityTitle = registrationCityTitle,
        registrationCurrencyTitle = registrationCurrencyTitle,
        registrationLegalTitle = registrationLegalTitle,
        registrationTermsAcceptance = registrationTermsAcceptance,
        registrationPrivacyAcceptance = registrationPrivacyAcceptance,
        registrationUgcAcceptance = registrationUgcAcceptance,
        registrationContinue = registrationContinue,
        registrationBack = registrationBack,
        registrationOtpWait = registrationOtpWait,
        registrationOtpResendCountdown = registrationOtpResendCountdown,
        registrationOtpExpired = registrationOtpExpired,
        registrationPasswordTooShort = registrationPasswordTooShort,
        registrationPasswordMismatch = registrationPasswordMismatch,
        registrationNameRequired = registrationNameRequired,
        registrationNameTooLong = registrationNameTooLong,
        registrationCityRequired = registrationCityRequired,
        registrationLegalRequired = registrationLegalRequired,
        registrationLegalUnavailable = registrationLegalUnavailable,
        registrationComplete = registrationComplete,
        registrationCompleteDefault = registrationCompleteDefault,
        registrationCompleteContextual = registrationCompleteContextual,
        legacy = toOnboardingLegacyRegistrationStrings(),
    )

private fun KwaborStrings.toOnboardingLegacyRegistrationStrings(): OnboardingLegacyRegistrationStrings =
    OnboardingLegacyRegistrationStrings(
        registrationIdentityTitle = registrationIdentityTitle,
        registrationUseLocation = registrationUseLocation,
        registrationLocationPermissionDenied = registrationLocationPermissionDenied,
        registrationLocationUnavailable = registrationLocationUnavailable,
        registrationLocationOutsideBenin = registrationLocationOutsideBenin,
        registrationObservabilityTitle = registrationObservabilityTitle,
        registrationObservabilitySupport = registrationObservabilitySupport,
        registrationAnalyticsConsent = registrationAnalyticsConsent,
        registrationDiagnosticsConsent = registrationDiagnosticsConsent,
        registrationRemoteConfigConsent = registrationRemoteConfigConsent,
        registrationNotificationTitle = registrationNotificationTitle,
        registrationNotificationSupport = registrationNotificationSupport,
        registrationNotificationEnable = registrationNotificationEnable,
        registrationLater = registrationLater,
    )
