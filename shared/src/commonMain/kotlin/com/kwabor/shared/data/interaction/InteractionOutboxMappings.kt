package com.kwabor.shared.data.interaction

import com.kwabor.shared.data.local.InteractionOutboxKind
import com.kwabor.shared.data.local.InteractionOutboxOperation
import com.kwabor.shared.data.local.InteractionOutboxTerminalError
import com.kwabor.shared.domain.interaction.InteractionKind
import com.kwabor.shared.domain.interaction.InteractionRejectionReason
import com.kwabor.shared.domain.interaction.PendingInteraction
import com.kwabor.shared.domain.interaction.PendingInteractionStatus

internal val INTERACTION_TERMINAL_SESSION = InteractionOutboxTerminalError.Session.storedValue
internal val INTERACTION_TERMINAL_MANUAL = InteractionOutboxTerminalError.Manual.storedValue
internal val INTERACTION_TERMINAL_VALIDATION = InteractionOutboxTerminalError.Validation.storedValue
internal val INTERACTION_TERMINAL_NOT_FOUND = InteractionOutboxTerminalError.NotFound.storedValue
internal val INTERACTION_TERMINAL_PERMISSION = InteractionOutboxTerminalError.Permission.storedValue

internal fun InteractionOutboxOperation.toDomain(): PendingInteraction = PendingInteraction(
    operationId = operationId,
    accountId = accountId,
    listingId = listingId,
    kind = kind.toDomain(),
    desiredSelected = desiredSelected,
    enqueuedAtEpochMilliseconds = enqueuedAtEpochMilliseconds,
    attemptCount = attemptCount,
    status = terminalErrorCode.toDomainStatus(nextAttemptAtEpochMilliseconds),
)

internal fun InteractionKind.toOutboxKind(): InteractionOutboxKind = when (this) {
    InteractionKind.Like -> InteractionOutboxKind.Like
    InteractionKind.Favorite -> InteractionOutboxKind.Favorite
}

private fun InteractionOutboxKind.toDomain(): InteractionKind = when (this) {
    InteractionOutboxKind.Like -> InteractionKind.Like
    InteractionOutboxKind.Favorite -> InteractionKind.Favorite
}

private fun String?.toDomainStatus(nextAttemptAtEpochMilliseconds: Long): PendingInteractionStatus = when (this) {
    null -> PendingInteractionStatus.Scheduled(nextAttemptAtEpochMilliseconds)
    INTERACTION_TERMINAL_SESSION -> PendingInteractionStatus.SuspendedForSession
    INTERACTION_TERMINAL_MANUAL -> PendingInteractionStatus.SuspendedForManualRetry
    INTERACTION_TERMINAL_VALIDATION ->
        PendingInteractionStatus.Rejected(InteractionRejectionReason.Validation)
    INTERACTION_TERMINAL_NOT_FOUND ->
        PendingInteractionStatus.Rejected(InteractionRejectionReason.NotFound)
    INTERACTION_TERMINAL_PERMISSION ->
        PendingInteractionStatus.Rejected(InteractionRejectionReason.PermissionDenied)
    else -> PendingInteractionStatus.Rejected(InteractionRejectionReason.Validation)
}
