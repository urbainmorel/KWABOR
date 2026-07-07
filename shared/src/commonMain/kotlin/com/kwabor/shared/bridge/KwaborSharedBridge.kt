package com.kwabor.shared.bridge

import com.kwabor.shared.app.KwaborRuntimeDependencies
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.stringsFor

class KwaborSharedBridge(
    supabaseUrl: String? = null,
    supabasePublishableKey: String? = null,
) {
    private val strings = stringsFor(AppLocale.French)
    private val runtimeDependencies = KwaborRuntimeDependencies.createOrNull(
        supabaseUrl = supabaseUrl,
        supabasePublishableKey = supabasePublishableKey,
    )

    fun appName(): String = strings.appName

    fun homeTitle(): String = strings.homeTitle

    fun foundationStatus(): String = strings.foundationStatus

    fun hasCatalogConfiguration(): Boolean = runtimeDependencies != null
}
