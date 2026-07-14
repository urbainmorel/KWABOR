package com.kwabor.shared.bridge

import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.stringsFor

class KwaborSharedBridge internal constructor(
    private val hasCatalogConfiguration: Boolean,
) {
    constructor() : this(hasCatalogConfiguration = false)

    private val strings = stringsFor(AppLocale.French)

    fun appName(): String = strings.appName

    fun homeTitle(): String = strings.homeTitle

    fun foundationStatus(): String = strings.foundationStatus

    fun hasCatalogConfiguration(): Boolean = hasCatalogConfiguration
}
