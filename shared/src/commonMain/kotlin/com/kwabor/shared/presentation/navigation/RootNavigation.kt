package com.kwabor.shared.presentation.navigation

import com.kwabor.shared.i18n.KwaborStrings

enum class RootNavigationDestination(val routeKey: String) {
    Home("home"),
    Social("social"),
    Add("add"),
    Notifications("notifications"),
    Profile("profile"),
    ;

    companion object {
        fun fromRouteKey(routeKey: String): RootNavigationDestination? = entries.firstOrNull { destination ->
            destination.routeKey == routeKey
        }
    }
}

val closedBetaRootDestinations: List<RootNavigationDestination> = listOf(
    RootNavigationDestination.Home,
    RootNavigationDestination.Profile,
)

enum class RootNavigationProfile {
    Full,
    ClosedBetaCatalog,
    ;

    val destinations: List<RootNavigationDestination>
        get() = when (this) {
            Full -> RootNavigationDestination.entries
            ClosedBetaCatalog -> closedBetaRootDestinations
        }
}

fun RootNavigationDestination.isVisibleIn(profile: RootNavigationProfile): Boolean = this in profile.destinations

fun RootNavigationDestination.label(
    strings: KwaborStrings,
    profile: RootNavigationProfile = RootNavigationProfile.Full,
): String = when (profile) {
    RootNavigationProfile.Full -> fullProfileLabel(strings)
    RootNavigationProfile.ClosedBetaCatalog -> when (this) {
        RootNavigationDestination.Home -> strings.closedBetaExploreRoot
        RootNavigationDestination.Social -> strings.social
        RootNavigationDestination.Add -> strings.add
        RootNavigationDestination.Notifications -> strings.notifications
        RootNavigationDestination.Profile -> strings.closedBetaAccountRoot
    }
}

private fun RootNavigationDestination.fullProfileLabel(strings: KwaborStrings): String = when (this) {
    RootNavigationDestination.Home -> strings.home
    RootNavigationDestination.Social -> strings.social
    RootNavigationDestination.Add -> strings.add
    RootNavigationDestination.Notifications -> strings.notifications
    RootNavigationDestination.Profile -> strings.profile
}

sealed interface RootDeepLinkResult {
    data class Accepted(val destination: RootNavigationDestination) : RootDeepLinkResult

    data class Rejected(val reason: RootDeepLinkRejection) : RootDeepLinkResult
}

enum class RootDeepLinkRejection {
    Malformed,
    UnsupportedScheme,
    UnsupportedHost,
    UnknownDestination,
    DestinationUnavailable,
}

object RootDeepLinkParser {
    fun parse(rawUrl: String, profile: RootNavigationProfile = RootNavigationProfile.Full): RootDeepLinkResult {
        if (rawUrl.isBlank() || rawUrl != rawUrl.trim()) {
            return RootDeepLinkResult.Rejected(RootDeepLinkRejection.Malformed)
        }

        val parts = rawUrl.split(SCHEME_SEPARATOR, limit = 2)
        return if (parts.size != 2 || parts.first().isEmpty()) {
            RootDeepLinkResult.Rejected(RootDeepLinkRejection.Malformed)
        } else {
            parseScheme(
                scheme = parts.first(),
                authorityAndPath = parts.last(),
                profile = profile,
            )
        }
    }

    private fun parseScheme(
        scheme: String,
        authorityAndPath: String,
        profile: RootNavigationProfile,
    ): RootDeepLinkResult = if (scheme.equals(APP_SCHEME, ignoreCase = true)) {
        parseAuthority(authorityAndPath, profile)
    } else {
        RootDeepLinkResult.Rejected(RootDeepLinkRejection.UnsupportedScheme)
    }

    private fun parseAuthority(authorityAndPath: String, profile: RootNavigationProfile): RootDeepLinkResult {
        val pathStart = authorityAndPath.indexOf(PATH_SEPARATOR)
        return when {
            pathStart <= 0 || pathStart == authorityAndPath.lastIndex -> {
                RootDeepLinkResult.Rejected(RootDeepLinkRejection.Malformed)
            }
            !authorityAndPath.substring(startIndex = 0, endIndex = pathStart)
                .equals(APP_HOST, ignoreCase = true) -> {
                RootDeepLinkResult.Rejected(RootDeepLinkRejection.UnsupportedHost)
            }
            else -> parseRouteKey(
                routeKey = authorityAndPath.substring(startIndex = pathStart + 1),
                profile = profile,
            )
        }
    }

    private fun parseRouteKey(routeKey: String, profile: RootNavigationProfile): RootDeepLinkResult {
        val destination = RootNavigationDestination.fromRouteKey(routeKey)
        return when {
            routeKey.contains(PATH_SEPARATOR) || routeKey.contains('?') || routeKey.contains('#') -> {
                RootDeepLinkResult.Rejected(RootDeepLinkRejection.Malformed)
            }
            destination == null -> RootDeepLinkResult.Rejected(RootDeepLinkRejection.UnknownDestination)
            !destination.isVisibleIn(profile) ->
                RootDeepLinkResult.Rejected(RootDeepLinkRejection.DestinationUnavailable)
            else -> RootDeepLinkResult.Accepted(destination)
        }
    }
}

private const val APP_SCHEME = "kwabor"
private const val APP_HOST = "app"
private const val SCHEME_SEPARATOR = "://"
private const val PATH_SEPARATOR = '/'
