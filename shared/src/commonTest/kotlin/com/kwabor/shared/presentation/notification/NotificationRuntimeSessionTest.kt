package com.kwabor.shared.presentation.notification

import com.kwabor.shared.domain.notification.NotificationAccountScope
import com.kwabor.shared.presentation.session.ViewerSessionScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class NotificationRuntimeSessionTest {
    @Test
    fun detailTicketsNeverWrapOrReuseAfterExhaustion() =
        runTest {
            val sequence = NotificationDetailTicketSequence(MAXIMUM_NOTIFICATION_DETAIL_TICKET_VALUE - 1L)

            assertEquals(MAXIMUM_NOTIFICATION_DETAIL_TICKET_VALUE, sequence.next()?.value)
            assertNull(sequence.next())
            assertNull(sequence.next())
        }

    @Test
    fun viewerSwitchAndCompositeInvalidationNeverResetTicketIdentity() =
        runTest {
            val session = NotificationRuntimeSession(backgroundScope)
            val viewer = ViewerSessionScope("account-a", epoch = 3L)
            val scope = NotificationAccountScope("account-a", epoch = 3L)
            session.switchViewer(viewer, scope)
            val first = requireNotNull(session.nextDetailTicket())

            session.resetForInvalidation()
            session.switchViewer(viewer, scope)
            val second = requireNotNull(session.nextDetailTicket())

            assertEquals(1L, first.value)
            assertEquals(2L, second.value)
            val firstRequest = com.kwabor.shared.presentation.detail.CatalogDetailOpenRequestId.correlated(first.value)
            val secondRequest =
                com.kwabor.shared.presentation.detail.CatalogDetailOpenRequestId.correlated(
                    second.value,
                )
            assertFalse(firstRequest == secondRequest)
            assertEquals(1L, firstRequest.value)
            assertEquals(3L, secondRequest.value)
        }
}
