package com.kwabor.android.app

import com.kwabor.android.auth.AndroidDeepLinkDestination
import com.kwabor.shared.presentation.navigation.RootNavigationDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AndroidNavigationDeepLinkTest {
    @Test
    fun parserPreservesTypedRootNavigation() {
        val result = AndroidNavigationDeepLinkParser.parse("kwabor://app/profile")

        assertEquals(
            RootNavigationDestination.Profile,
            assertIs<AndroidNavigationDeepLink.Root>(result).destination,
        )
    }

    @Test
    fun parserNormalizesCatalogDetailIdentifier() {
        val result = AndroidNavigationDeepLinkParser.parse(
            "KWABOR://LISTING/${VALID_LISTING_ID.uppercase()}",
        )

        assertEquals(
            VALID_LISTING_ID,
            assertIs<AndroidNavigationDeepLink.CatalogDetail>(result).listingId,
        )
    }

    @Test
    fun parserRejectsUnknownAndAmbiguousNavigationLinks() {
        listOf(
            "kwabor://app/unknown",
            "kwabor://listing/not-a-uuid",
            "kwabor://listing/$VALID_LISTING_ID?token=secret",
            "https://listing/$VALID_LISTING_ID",
        ).forEach { rawUrl ->
            assertIs<AndroidNavigationDeepLink.Rejected>(
                AndroidNavigationDeepLinkParser.parse(rawUrl),
            )
        }
    }

    @Test
    fun deliveryStaysPendingDuringBootstrapIntroAndExplicitGuestChoice() {
        val delivery = AndroidDeepLinkDelivery(
            deliveryId = TEST_DELIVERY_ID,
            rawUrl = "kwabor://listing/$VALID_LISTING_ID",
        )
        val ineligibleStates = listOf(
            eligibility(sessionRestored = false, introRequired = true),
            eligibility(sessionRestored = true, introRequired = true),
            eligibility(sessionRestored = true, introRequired = false),
        )

        ineligibleStates.forEach { eligibility ->
            assertNull(AndroidDeepLinkDispatchPolicy.readyDelivery(delivery, eligibility))
        }
        assertNull(
            AndroidDeepLinkDispatchPolicy.readyDelivery(
                delivery,
                eligibility(sessionRestored = true, introRequired = false),
            ),
        )
    }

    @Test
    fun explicitGuestConfirmationDispatchesHomeThenDetailThenAcknowledgement() {
        val calls = dispatchEligibleCatalogDetail(
            eligibility = eligibility(
                sessionRestored = true,
                introRequired = false,
                guestSession = true,
            ),
        )

        assertEquals(
            listOf("home", "open:$VALID_LISTING_ID", "ack:$TEST_DELIVERY_ID"),
            calls,
        )
    }

    @Test
    fun authenticationDispatchesHomeThenDetailThenAcknowledgement() {
        val calls = dispatchEligibleCatalogDetail(
            eligibility = eligibility(
                sessionRestored = true,
                introRequired = false,
                authenticated = true,
            ),
        )

        assertEquals(
            listOf("home", "open:$VALID_LISTING_ID", "ack:$TEST_DELIVERY_ID"),
            calls,
        )
    }

    @Test
    fun sensitiveAuthPolicyResetsAndTemporarilyRejectsNavigationLinks() {
        listOf(
            true to false,
            false to true,
            true to true,
        ).forEach { (signOutInProgress, accountDeletionInProgress) ->
            assertEquals(
                true,
                AndroidSensitiveAuthDeepLinkPolicy.shouldResetPending(
                    signOutInProgress = signOutInProgress,
                    accountDeletionInProgress = accountDeletionInProgress,
                ),
            )
            assertEquals(
                false,
                AndroidSensitiveAuthDeepLinkPolicy.shouldRetainNavigation(
                    destination = AndroidDeepLinkDestination.CatalogDetail,
                    signOutInProgress = signOutInProgress,
                    accountDeletionInProgress = accountDeletionInProgress,
                ),
            )
        }
        assertEquals(
            true,
            AndroidSensitiveAuthDeepLinkPolicy.shouldRetainNavigation(
                destination = AndroidDeepLinkDestination.CatalogDetail,
                signOutInProgress = false,
                accountDeletionInProgress = false,
            ),
        )
    }

    private fun dispatchEligibleCatalogDetail(eligibility: AndroidDeepLinkHomeEligibility): List<String> {
        val calls = mutableListOf<String>()
        val delivery = AndroidDeepLinkDelivery(
            deliveryId = TEST_DELIVERY_ID,
            rawUrl = "kwabor://listing/$VALID_LISTING_ID",
        )
        val readyDelivery = assertNotNull(
            AndroidDeepLinkDispatchPolicy.readyDelivery(delivery, eligibility),
        )

        dispatchAndroidNavigationDeepLink(
            delivery = readyDelivery,
            actions = AndroidNavigationDeepLinkDispatchActions(
                onRootDestination = { destination -> calls += "root:${destination.routeKey}" },
                onHomeDestination = { calls += "home" },
                onCatalogDetailOpen = { listingId -> calls += "open:$listingId" },
                onAcknowledged = { deliveryId -> calls += "ack:$deliveryId" },
            ),
        )
        return calls
    }
}

private const val VALID_LISTING_ID = "123e4567-e89b-42d3-a456-426614174000"
private const val TEST_DELIVERY_ID = 42L

private fun eligibility(
    sessionRestored: Boolean,
    introRequired: Boolean,
    authenticated: Boolean = false,
    guestSession: Boolean = false,
): AndroidDeepLinkHomeEligibility = AndroidDeepLinkHomeEligibility(
    isSessionRestoreComplete = sessionRestored,
    isIntroRequired = introRequired,
    isAuthenticated = authenticated,
    isGuestSession = guestSession,
)
