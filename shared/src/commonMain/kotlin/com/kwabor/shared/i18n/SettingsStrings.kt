package com.kwabor.shared.i18n

import com.kwabor.shared.domain.auth.AuthenticationMethod

data class SettingsStrings(
    val title: String,
    val profileEntrySubtitle: String,
    val accountSectionTitle: String,
    val emailLabel: String,
    val emailUnavailable: String,
    val authenticationMethodLabel: String,
    val authenticationMethodEmail: String,
    val authenticationMethodGoogle: String,
    val authenticationMethodApple: String,
    val authenticationMethodUnavailable: String,
    val profileDetailsTitle: String,
    val profileNoCity: String,
    val profileClearCity: String,
    val profileSave: String,
    val profileSaved: String,
    val profileLoadFailed: String,
    val profileSaveFailed: String,
    val privacySectionTitle: String,
    val privacySectionSupport: String,
    val analyticsConsent: String,
    val diagnosticsConsent: String,
    val remoteConfigurationConsent: String,
    val privacyPersistenceError: String,
) {
    fun accountEmail(rawValue: String?): String = rawValue
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: emailUnavailable

    fun authenticationMethodName(authenticationMethod: AuthenticationMethod?): String = when (authenticationMethod) {
        AuthenticationMethod.Email -> authenticationMethodEmail
        AuthenticationMethod.Google -> authenticationMethodGoogle
        AuthenticationMethod.Apple -> authenticationMethodApple
        null -> authenticationMethodUnavailable
    }
}

internal val frenchSettingsStrings = SettingsStrings(
    title = "Paramètres",
    profileEntrySubtitle = "Compte, confidentialité et suppression du compte",
    accountSectionTitle = "Compte",
    emailLabel = "Adresse e-mail",
    emailUnavailable = "Adresse e-mail indisponible",
    authenticationMethodLabel = "Méthode de connexion",
    authenticationMethodEmail = "E-mail et mot de passe",
    authenticationMethodGoogle = "Google",
    authenticationMethodApple = "Apple",
    authenticationMethodUnavailable = "Non renseignée",
    profileDetailsTitle = "Informations du profil",
    profileNoCity = "Aucune ville sélectionnée",
    profileClearCity = "Retirer la ville",
    profileSave = "Enregistrer",
    profileSaved = "Profil enregistré.",
    profileLoadFailed = "Impossible de charger le profil.",
    profileSaveFailed = "Impossible d’enregistrer le profil.",
    privacySectionTitle = "Confidentialité",
    privacySectionSupport =
    "Ces choix sont facultatifs. Vous pouvez les modifier ou les retirer à tout moment.",
    analyticsConsent = "Partager des statistiques d'utilisation pour améliorer Kwabor",
    diagnosticsConsent = "Partager des informations sur les pannes et les lenteurs",
    remoteConfigurationConsent =
    "Autoriser certains réglages de l'application sans mise à jour (hors vidéo)",
    privacyPersistenceError = "Impossible de terminer ce changement de confidentialité. Réessayez.",
)
