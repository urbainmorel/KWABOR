package com.kwabor.android.app

import com.kwabor.android.auth.AndroidDeepLinkDestination
import com.kwabor.shared.presentation.detail.CatalogDetailDeepLinkParser
import com.kwabor.shared.presentation.detail.CatalogDetailDeepLinkResult
import com.kwabor.shared.presentation.navigation.RootDeepLinkParser
import com.kwabor.shared.presentation.navigation.RootDeepLinkRejection
import com.kwabor.shared.presentation.navigation.RootDeepLinkResult
import com.kwabor.shared.presentation.navigation.RootNavigationDestination
import com.kwabor.shared.presentation.navigation.RootNavigationProfile

internal sealed interface AndroidNavigationDeepLink {
    data class Root(val destination: RootNavigationDestination) : AndroidNavigationDeepLink

    data class CatalogDetail(val listingId: String) : AndroidNavigationDeepLink

    data object UnavailableRoot : AndroidNavigationDeepLink

    data object Rejected : AndroidNavigationDeepLink
}

internal object AndroidNavigationDeepLinkParser {
    fun parse(rawUrl: String, profile: RootNavigationProfile = RootNavigationProfile.Full): AndroidNavigationDeepLink {
        val rootResult = RootDeepLinkParser.parse(rawUrl, profile)
        if (rootResult is RootDeepLinkResult.Accepted) {
            return AndroidNavigationDeepLink.Root(rootResult.destination)
        }
        if (
            rootResult ==
            RootDeepLinkResult.Rejected(RootDeepLinkRejection.DestinationUnavailable)
        ) {
            return AndroidNavigationDeepLink.UnavailableRoot
        }

        return when (val detailResult = CatalogDetailDeepLinkParser.parse(rawUrl)) {
            is CatalogDetailDeepLinkResult.Accepted ->
                AndroidNavigationDeepLink.CatalogDetail(detailResult.listingId)
            is CatalogDetailDeepLinkResult.Rejected -> AndroidNavigationDeepLink.Rejected
        }
    }
}

internal data class AndroidDeepLinkHomeEligibility(
    val isSessionRestoreComplete: Boolean,
    val isIntroRequired: Boolean,
    val isAuthenticated: Boolean,
    val isGuestSession: Boolean,
)

internal object AndroidDeepLinkDispatchPolicy {
    fun readyDelivery(
        delivery: AndroidDeepLinkDelivery?,
        eligibility: AndroidDeepLinkHomeEligibility,
    ): AndroidDeepLinkDelivery? = delivery.takeIf {
        eligibility.isSessionRestoreComplete &&
            !eligibility.isIntroRequired &&
            (eligibility.isAuthenticated || eligibility.isGuestSession)
    }
}

internal object AndroidSensitiveAuthDeepLinkPolicy {
    fun shouldResetPending(signOutInProgress: Boolean, accountDeletionInProgress: Boolean): Boolean =
        signOutInProgress || accountDeletionInProgress

    fun shouldRetainNavigation(
        destination: AndroidDeepLinkDestination,
        signOutInProgress: Boolean,
        accountDeletionInProgress: Boolean,
    ): Boolean = !shouldResetPending(signOutInProgress, accountDeletionInProgress) &&
        (
            destination == AndroidDeepLinkDestination.RootNavigation ||
                destination == AndroidDeepLinkDestination.CatalogDetail
            )
}

internal data class AndroidNavigationDeepLinkDispatchActions(
    val onRootDestination: (RootNavigationDestination) -> Unit,
    val onHomeDestination: () -> Unit,
    val onUnavailableRoot: () -> Unit,
    val onCatalogDetailOpen: (String) -> Unit,
    val onAcknowledged: (Long) -> Unit,
)

internal fun dispatchAndroidNavigationDeepLink(
    delivery: AndroidDeepLinkDelivery,
    profile: RootNavigationProfile,
    actions: AndroidNavigationDeepLinkDispatchActions,
) {
    when (val deepLink = AndroidNavigationDeepLinkParser.parse(delivery.rawUrl, profile)) {
        is AndroidNavigationDeepLink.Root -> actions.onRootDestination(deepLink.destination)
        is AndroidNavigationDeepLink.CatalogDetail -> {
            actions.onHomeDestination()
            actions.onCatalogDetailOpen(deepLink.listingId)
        }
        AndroidNavigationDeepLink.UnavailableRoot -> {
            actions.onHomeDestination()
            actions.onUnavailableRoot()
        }
        AndroidNavigationDeepLink.Rejected -> Unit
    }
    actions.onAcknowledged(delivery.deliveryId)
}
