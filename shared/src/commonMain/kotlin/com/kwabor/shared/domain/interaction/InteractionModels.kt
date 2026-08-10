package com.kwabor.shared.domain.interaction

data class InteractionAccountScope(
    val accountId: String,
    val epoch: Long,
) {
    init {
        require(accountId.isNotEmpty() && accountId == accountId.trim()) {
            "Interaction account id must be normalized."
        }
        require(epoch >= 0L) { "Interaction account epoch must be non-negative." }
    }
}

enum class InteractionKind {
    Like,
    Favorite,
}

data class InteractionCommand(
    val scope: InteractionAccountScope,
    val listingId: String,
    val kind: InteractionKind,
    val desiredSelected: Boolean,
) {
    init {
        require(listingId.isNotEmpty() && listingId == listingId.trim()) {
            "Interaction listing id must be normalized."
        }
    }
}

enum class InteractionRejectionReason {
    Validation,
    NotFound,
    PermissionDenied,
}

sealed interface PendingInteractionStatus {
    val isTerminal: Boolean

    data class Scheduled(
        val nextAttemptAtEpochMilliseconds: Long,
    ) : PendingInteractionStatus {
        init {
            require(nextAttemptAtEpochMilliseconds >= 0L) {
                "Interaction retry timestamp must be non-negative."
            }
        }

        override val isTerminal: Boolean = false
    }

    data object SuspendedForSession : PendingInteractionStatus {
        override val isTerminal: Boolean = true
    }

    data object SuspendedForManualRetry : PendingInteractionStatus {
        override val isTerminal: Boolean = true
    }

    data class Rejected(
        val reason: InteractionRejectionReason,
    ) : PendingInteractionStatus {
        override val isTerminal: Boolean = true
    }
}

data class PendingInteraction(
    val operationId: Long,
    val accountId: String,
    val listingId: String,
    val kind: InteractionKind,
    val desiredSelected: Boolean,
    val enqueuedAtEpochMilliseconds: Long,
    val attemptCount: Int,
    val status: PendingInteractionStatus,
) {
    init {
        require(operationId > 0L) { "Interaction operation id must be positive." }
        require(accountId.isNotEmpty() && accountId == accountId.trim()) {
            "Pending interaction account id must be normalized."
        }
        require(listingId.isNotEmpty() && listingId == listingId.trim()) {
            "Pending interaction listing id must be normalized."
        }
        require(enqueuedAtEpochMilliseconds >= 0L) {
            "Interaction enqueue timestamp must be non-negative."
        }
        require(attemptCount >= 0) { "Interaction attempt count must be non-negative." }
    }

    val isHydratable: Boolean
        get() = when (status) {
            is PendingInteractionStatus.Scheduled -> true
            PendingInteractionStatus.SuspendedForSession -> true
            PendingInteractionStatus.SuspendedForManualRetry -> true
            is PendingInteractionStatus.Rejected -> false
        }
}

sealed interface InteractionConfirmation {
    val operationId: Long
    val scope: InteractionAccountScope
    val listingId: String

    data class Like(
        override val operationId: Long,
        override val scope: InteractionAccountScope,
        override val listingId: String,
        val liked: Boolean,
        val likesCount: Int?,
        val mutatedAtEpochMilliseconds: Long,
    ) : InteractionConfirmation

    data class Favorite(
        override val operationId: Long,
        override val scope: InteractionAccountScope,
        override val listingId: String,
        val favorited: Boolean,
        val favoritedAtEpochMilliseconds: Long?,
        val clientMutationSequence: Long,
    ) : InteractionConfirmation
}

sealed interface InteractionSubmitOutcome {
    val command: InteractionCommand

    data class Queued(
        override val command: InteractionCommand,
        val pending: PendingInteraction,
    ) : InteractionSubmitOutcome

    data class Superseded(
        override val command: InteractionCommand,
        val operationId: Long,
    ) : InteractionSubmitOutcome
}

sealed interface InteractionOperationOutcome {
    val command: InteractionCommand

    data class Confirmed(
        override val command: InteractionCommand,
        val confirmation: InteractionConfirmation,
    ) : InteractionOperationOutcome

    data class Retrying(
        override val command: InteractionCommand,
        val pending: PendingInteraction,
    ) : InteractionOperationOutcome

    data class Rejected(
        override val command: InteractionCommand,
        val operationId: Long,
        val reason: InteractionRejectionReason,
    ) : InteractionOperationOutcome

    data class Superseded(
        override val command: InteractionCommand,
        val operationId: Long,
    ) : InteractionOperationOutcome
}

data class InteractionDrainOutcome(
    val scope: InteractionAccountScope,
    val operations: List<InteractionOperationOutcome>,
)
