package com.kwabor.shared.i18n

import com.kwabor.shared.domain.i18n.AppLocale

data class KwaborStrings(
    val appName: String,
    val homeTitle: String,
    val home: String,
    val social: String,
    val add: String,
    val notifications: String,
    val profile: String,
    val foundationStatus: String,
    val free: String,
)

fun stringsFor(locale: AppLocale): KwaborStrings = when (locale) {
    AppLocale.French,
    AppLocale.English,
    AppLocale.Portuguese,
    AppLocale.German,
    AppLocale.Spanish,
    AppLocale.Italian,
    -> frenchStrings
}

private val frenchStrings = KwaborStrings(
    appName = "Kwabor",
    homeTitle = "Découvrez le Bénin",
    home = "Accueil",
    social = "Social",
    add = "Ajouter",
    notifications = "Notifications",
    profile = "Profil",
    foundationStatus = "Socle applicatif en place",
    free = "Gratuit",
)
