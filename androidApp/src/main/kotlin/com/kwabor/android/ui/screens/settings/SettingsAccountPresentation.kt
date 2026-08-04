package com.kwabor.android.ui.screens.settings

import com.kwabor.shared.domain.auth.AuthenticationMethod
import com.kwabor.shared.i18n.KwaborStrings

internal data class SettingsAccountPresentation(
    val email: String,
    val authenticationMethod: String,
    val accountDeletionAvailable: Boolean,
)

internal fun settingsAccountPresentation(
    email: String?,
    authenticationMethod: AuthenticationMethod?,
    strings: KwaborStrings,
): SettingsAccountPresentation = SettingsAccountPresentation(
    email = strings.settings.accountEmail(email),
    authenticationMethod = strings.settings.authenticationMethodName(authenticationMethod),
    accountDeletionAvailable = authenticationMethod != null,
)
