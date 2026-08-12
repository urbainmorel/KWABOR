package com.kwabor.shared.bridge

import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.OnboardingStrings
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.i18n.toOnboardingStrings
import com.kwabor.shared.presentation.detail.CatalogDetailDeepLinkParser
import com.kwabor.shared.presentation.detail.CatalogDetailDeepLinkResult
import com.kwabor.shared.presentation.navigation.RootDeepLinkParser
import com.kwabor.shared.presentation.navigation.RootDeepLinkRejection
import com.kwabor.shared.presentation.navigation.RootDeepLinkResult
import com.kwabor.shared.presentation.navigation.RootNavigationDestination
import com.kwabor.shared.presentation.navigation.RootNavigationProfile
import com.kwabor.shared.presentation.navigation.label
import com.kwabor.shared.presentation.onboarding.OnboardingEntryResolver

class KwaborSharedBridge internal constructor(
    val hasCatalogConfiguration: Boolean,
    private val rootNavigationProfile: RootNavigationProfile = RootNavigationProfile.Full,
) {
    constructor() : this(hasCatalogConfiguration = false)

    private val strings = stringsFor(AppLocale.French)

    fun appName(): String = strings.appName

    fun homeTitle(): String = strings.homeTitle

    fun foundationStatus(): String = strings.foundationStatus

    fun onboardingStrings(): OnboardingStrings = strings.toOnboardingStrings()

    fun homeLabel(): String = RootNavigationDestination.Home.label(strings, rootNavigationProfile)

    fun socialLabel(): String = RootNavigationDestination.Social.label(strings, rootNavigationProfile)

    fun addLabel(): String = RootNavigationDestination.Add.label(strings, rootNavigationProfile)

    fun notificationsLabel(): String = RootNavigationDestination.Notifications.label(strings, rootNavigationProfile)

    fun profileLabel(): String = RootNavigationDestination.Profile.label(strings, rootNavigationProfile)

    fun rootDestinationKeyForDeepLink(rawUrl: String): String? = when (
        val result = RootDeepLinkParser.parse(rawUrl, rootNavigationProfile)
    ) {
        is RootDeepLinkResult.Accepted -> result.destination.routeKey
        is RootDeepLinkResult.Rejected -> null
    }

    fun isUnavailableRootDeepLink(rawUrl: String): Boolean = RootDeepLinkParser.parse(rawUrl, rootNavigationProfile) ==
        RootDeepLinkResult.Rejected(RootDeepLinkRejection.DestinationUnavailable)

    val isClosedBetaCatalog: Boolean
        get() = rootNavigationProfile == RootNavigationProfile.ClosedBetaCatalog

    val rootDestinationUnavailableMessage: String
        get() = strings.rootDestinationUnavailable

    fun catalogDetailListingIdForDeepLink(rawUrl: String): String? =
        when (val result = CatalogDetailDeepLinkParser.parse(rawUrl)) {
            is CatalogDetailDeepLinkResult.Accepted -> result.listingId
            is CatalogDetailDeepLinkResult.Rejected -> null
        }

    fun onboardingEntryKey(
        firstLaunchCompleted: Boolean,
        sessionRestoreCompleted: Boolean,
        isAuthenticated: Boolean,
        guestAccessGranted: Boolean,
    ): String = OnboardingEntryResolver.resolve(
        firstLaunchCompleted = firstLaunchCompleted,
        sessionRestoreCompleted = sessionRestoreCompleted,
        isAuthenticated = isAuthenticated,
        guestAccessGranted = guestAccessGranted,
    ).routeKey

    fun onboardingTelemetry(): OnboardingTelemetry = OnboardingTelemetry()
}
