package com.kwabor.shared.domain.interaction

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InteractionModelsTest {
    @Test
    fun hydrationEligibilityKeepsRetryableSuspensionsAndExcludesRejections() {
        assertTrue(pendingWith(PendingInteractionStatus.Scheduled(100L)).isHydratable)
        assertTrue(pendingWith(PendingInteractionStatus.SuspendedForSession).isHydratable)
        assertTrue(pendingWith(PendingInteractionStatus.SuspendedForManualRetry).isHydratable)
        assertFalse(
            pendingWith(
                PendingInteractionStatus.Rejected(InteractionRejectionReason.PermissionDenied),
            ).isHydratable,
        )
    }
}

private fun pendingWith(status: PendingInteractionStatus): PendingInteraction = PendingInteraction(
    operationId = 1L,
    accountId = "11111111-1111-4111-8111-111111111111",
    listingId = "33333333-3333-4333-8333-333333333333",
    kind = InteractionKind.Like,
    desiredSelected = true,
    enqueuedAtEpochMilliseconds = 100L,
    attemptCount = 1,
    status = status,
)
