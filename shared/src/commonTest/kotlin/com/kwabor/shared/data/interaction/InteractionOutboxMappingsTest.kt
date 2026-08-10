package com.kwabor.shared.data.interaction

import com.kwabor.shared.data.local.InteractionOutboxKind
import com.kwabor.shared.data.local.InteractionOutboxOperation
import com.kwabor.shared.domain.interaction.InteractionRejectionReason
import com.kwabor.shared.domain.interaction.PendingInteractionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class InteractionOutboxMappingsTest {
    @Test
    fun unknownTerminalStatusIsRejectedAndCannotRestoreAnOverlay() {
        val pending = InteractionOutboxOperation(
            operationId = 1L,
            accountId = "11111111-1111-4111-8111-111111111111",
            listingId = "33333333-3333-4333-8333-333333333333",
            kind = InteractionOutboxKind.Like,
            desiredSelected = true,
            enqueuedAtEpochMilliseconds = 1_000L,
            attemptCount = 1,
            nextAttemptAtEpochMilliseconds = 2_000L,
            terminalErrorCode = "corrupt_status",
        ).toDomain()

        val status = assertIs<PendingInteractionStatus.Rejected>(pending.status)
        assertEquals(InteractionRejectionReason.Validation, status.reason)
        assertFalse(pending.isHydratable)
    }
}
