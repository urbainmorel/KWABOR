package com.kwabor.shared.presentation.notification

import com.kwabor.shared.domain.notification.NotificationAccountScope
import com.kwabor.shared.domain.notification.NotificationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NotificationPresentationModelsTest {
    @Test
    fun badgeVisibilityUsesUnseenInsteadOfUnread() {
        val clearedBadge =
            NotificationBadgeUiState(
                unseenCount = 0,
                unreadCount = 3,
                seenThroughSequence = 12L,
            )
        val unseenBadge =
            NotificationBadgeUiState(
                unseenCount = 1,
                unreadCount = 3,
                seenThroughSequence = 11L,
            )

        assertFalse(clearedBadge.isVisible)
        assertTrue(unseenBadge.isVisible)
    }

    @Test
    fun pageStatesRepresentSkeletonEmptyErrorAndOfflineIndependently() {
        val skeleton = NotificationPageUiState(content = NotificationPageContentUiState.Skeleton)
        val emptyOffline =
            NotificationPageUiState(
                content = NotificationPageContentUiState.Empty,
                isOffline = true,
            )
        val errorOffline =
            NotificationPageUiState(
                content = NotificationPageContentUiState.Error("Impossible de charger vos notifications."),
                isOffline = true,
            )

        assertIs<NotificationPageContentUiState.Skeleton>(skeleton.content)
        assertIs<NotificationPageContentUiState.Empty>(emptyOffline.content)
        assertTrue(emptyOffline.isOffline)
        assertIs<NotificationPageContentUiState.Error>(errorOffline.content)
        assertTrue(errorOffline.isOffline)
    }

    @Test
    fun detailConfirmationCarriesTheExactScopeGenerationAndTicket() {
        val scope = NotificationAccountScope(accountId = "account-a", epoch = 7L)
        val ticket = NotificationDetailTicket(42L)

        val intent =
            NotificationIntent.DetailSheetPresentationConfirmed(
                ticket = ticket,
                listingId = "listing-a",
                scope = scope,
                presentationGeneration = 9L,
            )

        assertEquals(ticket, intent.ticket)
        assertEquals("listing-a", intent.listingId)
        assertEquals(scope, intent.scope)
        assertEquals(9L, intent.presentationGeneration)
    }

    @Test
    fun detailConfirmationRejectsANonNormalizedListingId() {
        assertFailsWith<IllegalArgumentException> {
            NotificationIntent.DetailSheetPresentationConfirmed(
                ticket = NotificationDetailTicket(42L),
                listingId = " listing-a ",
                scope = NotificationAccountScope(accountId = "account-a", epoch = 7L),
                presentationGeneration = 9L,
            )
        }
    }

    @Test
    fun analyticsEffectRejectsAnUnsafeCityId() {
        assertFailsWith<IllegalArgumentException> {
            NotificationEffect.RecordOpenedAnalytics(
                notificationId = "notification-a",
                kind = NotificationKind.Suggestion,
                cityId = "porto novo",
                ticket = NotificationDetailTicket(42L),
                scope = NotificationAccountScope(accountId = "account-a", epoch = 7L),
                presentationGeneration = 9L,
            )
        }
    }

    @Test
    fun detailTicketAcceptsOnlyTheCorrelatedDetailRange() {
        assertFailsWith<IllegalArgumentException> { NotificationDetailTicket(0L) }
        assertFailsWith<IllegalArgumentException> {
            NotificationDetailTicket(MAXIMUM_NOTIFICATION_DETAIL_TICKET_VALUE + 1L)
        }

        assertEquals(
            MAXIMUM_NOTIFICATION_DETAIL_TICKET_VALUE,
            NotificationDetailTicket(MAXIMUM_NOTIFICATION_DETAIL_TICKET_VALUE).value,
        )
    }

    @Test
    fun detailOpenRequestIdIsOddAndBoundedToTheTicket() {
        val effect =
            NotificationEffect.OpenCatalogDetail(
                notificationId = "notification-a",
                target =
                    NotificationTargetUiModel(
                        listingId = "listing-a",
                        listingType = com.kwabor.shared.domain.catalog.ListingType.Place,
                        listingName = "Musée",
                        cityId = "cotonou",
                    ),
                ticket = NotificationDetailTicket(42L),
                scope = NotificationAccountScope(accountId = "account-a", epoch = 7L),
                presentationGeneration = 9L,
            )

        assertEquals(83L, effect.openRequestId.value)
        assertTrue(effect.openRequestId.value % 2L == 1L)
    }
}
