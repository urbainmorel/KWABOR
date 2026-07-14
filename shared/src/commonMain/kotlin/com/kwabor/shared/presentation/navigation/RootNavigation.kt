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

fun RootNavigationDestination.label(strings: KwaborStrings): String = when (this) {
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
}

object RootDeepLinkParser {
    fun parse(rawUrl: String): RootDeepLinkResult {
        if (rawUrl.isBlank() || rawUrl != rawUrl.trim()) {
            return RootDeepLinkResult.Rejected(RootDeepLinkRejection.Malformed)
        }

        val parts = rawUrl.split(SCHEME_SEPARATOR, limit = 2)
        return if (parts.size != 2 || parts.first().isEmpty()) {
            RootDeepLinkResult.Rejected(RootDeepLinkRejection.Malformed)
        } else {
            parseScheme(scheme = parts.first(), authorityAndPath = parts.last())
        }
    }

    private fun parseScheme(scheme: String, authorityAndPath: String): RootDeepLinkResult =
        if (scheme.equals(APP_SCHEME, ignoreCase = true)) {
            parseAuthority(authorityAndPath)
        } else {
            RootDeepLinkResult.Rejected(RootDeepLinkRejection.UnsupportedScheme)
        }

    private fun parseAuthority(authorityAndPath: String): RootDeepLinkResult {
        val pathStart = authorityAndPath.indexOf(PATH_SEPARATOR)
        return when {
            pathStart <= 0 || pathStart == authorityAndPath.lastIndex -> {
                RootDeepLinkResult.Rejected(RootDeepLinkRejection.Malformed)
            }
            !authorityAndPath.substring(startIndex = 0, endIndex = pathStart)
                .equals(APP_HOST, ignoreCase = true) -> {
                RootDeepLinkResult.Rejected(RootDeepLinkRejection.UnsupportedHost)
            }
            else -> parseRouteKey(authorityAndPath.substring(startIndex = pathStart + 1))
        }
    }

    private fun parseRouteKey(routeKey: String): RootDeepLinkResult {
        val destination = RootNavigationDestination.fromRouteKey(routeKey)
        return when {
            routeKey.contains(PATH_SEPARATOR) || routeKey.contains('?') || routeKey.contains('#') -> {
                RootDeepLinkResult.Rejected(RootDeepLinkRejection.Malformed)
            }
            destination == null -> RootDeepLinkResult.Rejected(RootDeepLinkRejection.UnknownDestination)
            else -> RootDeepLinkResult.Accepted(destination)
        }
    }
}

private const val APP_SCHEME = "kwabor"
private const val APP_HOST = "app"
private const val SCHEME_SEPARATOR = "://"
private const val PATH_SEPARATOR = '/'
