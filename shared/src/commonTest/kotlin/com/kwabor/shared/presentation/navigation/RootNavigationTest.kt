package com.kwabor.shared.presentation.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RootNavigationTest {
    @Test
    fun parse_acceptsEveryTypedRootDestination() {
        RootNavigationDestination.entries.forEach { destination ->
            val result = RootDeepLinkParser.parse("kwabor://app/${destination.routeKey}")

            assertEquals(destination, assertIs<RootDeepLinkResult.Accepted>(result).destination)
        }
    }

    @Test
    fun parse_acceptsCaseInsensitiveSchemeAndHost() {
        val result = RootDeepLinkParser.parse("KWABOR://APP/home")

        assertEquals(
            RootNavigationDestination.Home,
            assertIs<RootDeepLinkResult.Accepted>(result).destination,
        )
    }

    @Test
    fun parse_rejectsUnsupportedSchemeAndHost() {
        assertRejected(
            rawUrl = "https://app/home",
            expectedReason = RootDeepLinkRejection.UnsupportedScheme,
        )
        assertRejected(
            rawUrl = "kwabor://auth/home",
            expectedReason = RootDeepLinkRejection.UnsupportedHost,
        )
    }

    @Test
    fun parse_rejectsUnknownOrAmbiguousPaths() {
        assertRejected(
            rawUrl = "kwabor://app/settings",
            expectedReason = RootDeepLinkRejection.UnknownDestination,
        )
        listOf(
            "kwabor://app/home/child",
            "kwabor://app/home?source=push",
            "kwabor://app/home#section",
            " kwabor://app/home",
        ).forEach { rawUrl ->
            assertRejected(rawUrl = rawUrl, expectedReason = RootDeepLinkRejection.Malformed)
        }
    }

    private fun assertRejected(rawUrl: String, expectedReason: RootDeepLinkRejection) {
        val result = assertIs<RootDeepLinkResult.Rejected>(RootDeepLinkParser.parse(rawUrl))
        assertEquals(expectedReason, result.reason)
    }
}
