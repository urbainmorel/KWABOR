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
    profileEntrySubtitle = "Compte, connexion et suppression du compte",
    accountSectionTitle = "Compte",
    emailLabel = "Adresse e-mail",
    emailUnavailable = "Adresse e-mail indisponible",
    authenticationMethodLabel = "Méthode de connexion",
    authenticationMethodEmail = "E-mail et mot de passe",
    authenticationMethodGoogle = "Google",
    authenticationMethodApple = "Apple",
    authenticationMethodUnavailable = "Non renseignée",
)
