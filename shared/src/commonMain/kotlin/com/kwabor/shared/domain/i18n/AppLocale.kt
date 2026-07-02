package com.kwabor.shared.domain.i18n

enum class AppLocale(val tag: String, val nativeName: String) {
    French(tag = "fr", nativeName = "Français"),
    English(tag = "en", nativeName = "English"),
    Portuguese(tag = "pt", nativeName = "Português"),
    German(tag = "de", nativeName = "Deutsch"),
    Spanish(tag = "es", nativeName = "Español"),
    Italian(tag = "it", nativeName = "Italiano"),
}

fun resolveAppLocale(preferredLanguageTags: List<String>, deliveredLocales: Set<AppLocale>): AppLocale {
    require(deliveredLocales.isNotEmpty()) { "At least one delivered locale is required." }

    val matchedLocale = preferredLanguageTags.firstNotNullOfOrNull { tag ->
        val language = tag.substringBefore(delimiter = "-").lowercase()
        deliveredLocales.firstOrNull { locale -> locale.tag == language }
    }

    if (matchedLocale != null) {
        return matchedLocale
    }

    return when {
        AppLocale.English in deliveredLocales -> AppLocale.English
        AppLocale.French in deliveredLocales -> AppLocale.French
        else -> deliveredLocales.first()
    }
}
