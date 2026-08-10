package com.kwabor.shared.presentation.interaction

import com.kwabor.shared.domain.interaction.InteractionAccountScope
import com.kwabor.shared.domain.interaction.InteractionCommand
import com.kwabor.shared.domain.interaction.InteractionConfirmation
import com.kwabor.shared.domain.interaction.InteractionKind
import com.kwabor.shared.domain.interaction.InteractionRejectionReason
import com.kwabor.shared.domain.interaction.PendingInteraction
import com.kwabor.shared.domain.interaction.PendingInteractionStatus

data class InteractionOverlay(
    val listingId: String,
    val liked: Boolean?,
    val favorited: Boolean?,
    val pending: List<PendingInteraction>,
) {
    val restoresOfflineState: Boolean
        get() = pending.any { interaction ->
            interaction.attemptCount > 0 && interaction.status is PendingInteractionStatus.Scheduled
        }
}

data class InteractionHydration(
    val scope: InteractionAccountScope,
    val overlays: List<InteractionOverlay>,
    val pending: List<PendingInteraction>,
)

internal data class InteractionReconciliationKey(
    val listingId: String,
    val kind: InteractionKind,
)

internal enum class InteractionReconciliationConsumer {
    Explore,
    Favorites,
}

internal data class InteractionReconciliationStatus(
    val requiresPendingValidation: Boolean,
    val acknowledgedConsumers: Set<InteractionReconciliationConsumer>,
)

internal class InteractionReconciliationSignal(
    val scope: InteractionAccountScope,
    val revision: Long,
    val stateVersion: Long,
    val deliveryWatermark: Long,
    val terminalWatermarks: Map<InteractionReconciliationKey, Long>,
    val status: InteractionReconciliationStatus,
) {
    val requiresPendingValidation: Boolean
        get() = status.requiresPendingValidation
    val acknowledgedConsumers: Set<InteractionReconciliationConsumer>
        get() = status.acknowledgedConsumers
}

internal fun InteractionReconciliationSignal.terminalWatermark(listingId: String, kind: InteractionKind): Long? =
    terminalWatermarks[InteractionReconciliationKey(listingId, kind)]

sealed interface InteractionCoordinatorEvent {
    val scope: InteractionAccountScope
    val deliverySequence: Long
    val command: InteractionCommand

    data class Queued(
        override val scope: InteractionAccountScope,
        override val deliverySequence: Long,
        override val command: InteractionCommand,
        val pending: PendingInteraction,
    ) : InteractionCoordinatorEvent

    data class Confirmed(
        override val scope: InteractionAccountScope,
        override val deliverySequence: Long,
        override val command: InteractionCommand,
        val confirmation: InteractionConfirmation,
    ) : InteractionCoordinatorEvent

    data class Retrying(
        override val scope: InteractionAccountScope,
        override val deliverySequence: Long,
        override val command: InteractionCommand,
        val pending: PendingInteraction,
    ) : InteractionCoordinatorEvent

    data class Rejected(
        override val scope: InteractionAccountScope,
        override val deliverySequence: Long,
        override val command: InteractionCommand,
        val operationId: Long,
        val reason: InteractionRejectionReason,
    ) : InteractionCoordinatorEvent

    data class Superseded(
        override val scope: InteractionAccountScope,
        override val deliverySequence: Long,
        override val command: InteractionCommand,
        val operationId: Long,
    ) : InteractionCoordinatorEvent
}
