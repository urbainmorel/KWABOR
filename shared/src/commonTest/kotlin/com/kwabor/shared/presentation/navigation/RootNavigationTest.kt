package com.kwabor.shared.presentation.navigation

import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.stringsFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RootNavigationTest {
    @Test
    fun labels_preserveTheFullProfileAndNameTheClosedBetaRootsExactly() {
        val strings = stringsFor(AppLocale.French)

        assertEquals(
            listOf("Accueil", "Social", "Ajouter", "Notifications", "Profil"),
            RootNavigationProfile.Full.destinations.map { destination ->
                destination.label(strings, RootNavigationProfile.Full)
            },
        )
        assertEquals(
            listOf("Explorer", "Compte"),
            RootNavigationProfile.ClosedBetaCatalog.destinations.map { destination ->
                destination.label(strings, RootNavigationProfile.ClosedBetaCatalog)
            },
        )
    }

    @Test
    fun parse_appliesTheSelectedNavigationProfile() {
        RootNavigationDestination.entries.forEach { destination ->
            val result = RootDeepLinkParser.parse(
                rawUrl = "kwabor://app/${destination.routeKey}",
                profile = RootNavigationProfile.Full,
            )

            assertEquals(destination, assertIs<RootDeepLinkResult.Accepted>(result).destination)
        }
        closedBetaRootDestinations.forEach { destination ->
            val result = RootDeepLinkParser.parse(
                rawUrl = "kwabor://app/${destination.routeKey}",
                profile = RootNavigationProfile.ClosedBetaCatalog,
            )

            assertEquals(destination, assertIs<RootDeepLinkResult.Accepted>(result).destination)
        }
        RootNavigationDestination.entries
            .filterNot { destination -> destination.isVisibleIn(RootNavigationProfile.ClosedBetaCatalog) }
            .forEach { destination ->
                assertRejected(
                    rawUrl = "kwabor://app/${destination.routeKey}",
                    expectedReason = RootDeepLinkRejection.DestinationUnavailable,
                    profile = RootNavigationProfile.ClosedBetaCatalog,
                )
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

    private fun assertRejected(
        rawUrl: String,
        expectedReason: RootDeepLinkRejection,
        profile: RootNavigationProfile = RootNavigationProfile.Full,
    ) {
        val result = assertIs<RootDeepLinkResult.Rejected>(RootDeepLinkParser.parse(rawUrl, profile))
        assertEquals(expectedReason, result.reason)
    }
}
