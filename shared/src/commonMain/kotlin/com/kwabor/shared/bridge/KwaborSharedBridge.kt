package com.kwabor.shared.bridge

import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.navigation.RootDeepLinkParser
import com.kwabor.shared.presentation.navigation.RootDeepLinkResult

class KwaborSharedBridge internal constructor(
    private val hasCatalogConfiguration: Boolean,
) {
    constructor() : this(hasCatalogConfiguration = false)

    private val strings = stringsFor(AppLocale.French)

    fun appName(): String = strings.appName

    fun homeTitle(): String = strings.homeTitle

    fun foundationStatus(): String = strings.foundationStatus

    fun homeLabel(): String = strings.home

    fun socialLabel(): String = strings.social

    fun addLabel(): String = strings.add

    fun notificationsLabel(): String = strings.notifications

    fun profileLabel(): String = strings.profile

    fun rootDestinationKeyForDeepLink(rawUrl: String): String? = when (val result = RootDeepLinkParser.parse(rawUrl)) {
        is RootDeepLinkResult.Accepted -> result.destination.routeKey
        is RootDeepLinkResult.Rejected -> null
    }

    fun hasCatalogConfiguration(): Boolean = hasCatalogConfiguration
}
