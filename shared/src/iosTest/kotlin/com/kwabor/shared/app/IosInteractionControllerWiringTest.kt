package com.kwabor.shared.app

import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.interaction.InteractionAccountScope
import com.kwabor.shared.domain.interaction.InteractionCommand
import com.kwabor.shared.domain.interaction.InteractionDrainOutcome
import com.kwabor.shared.domain.interaction.InteractionRepository
import com.kwabor.shared.domain.interaction.InteractionSubmitOutcome
import com.kwabor.shared.domain.interaction.PendingInteraction
import com.kwabor.shared.presentation.interaction.InteractionCoordinator
import com.kwabor.shared.presentation.session.ViewerSessionScopeTracker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertSame

class IosInteractionControllerWiringTest {
    @Test
    fun exploreControllerForwardsDurableCoordinatorToRuntimeFactory() {
        val fixture = InteractionCoordinatorFixture()
        var receivedCoordinator: InteractionCoordinator? = null

        val controller = IosExploreController(
            runtimeProvider = { _, _, coordinator ->
                receivedCoordinator = coordinator
                null
            },
            dispatcherProvider = IosInteractionDispatcherProvider,
            viewerSessionScopeTracker = fixture.viewerSessionScopeTracker,
            interactionCoordinator = fixture.coordinator,
        )

        assertSame(fixture.coordinator, receivedCoordinator)
        controller.close()
        fixture.close()
    }

    @Test
    fun favoritesControllerForwardsDurableCoordinatorToRuntimeFactory() {
        val fixture = InteractionCoordinatorFixture()
        var receivedCoordinator: InteractionCoordinator? = null

        val controller = IosFavoritesController(
            runtimeProvider = { _, _, coordinator ->
                receivedCoordinator = coordinator
                null
            },
            dispatcherProvider = IosInteractionDispatcherProvider,
            viewerSessionScopeTracker = fixture.viewerSessionScopeTracker,
            interactionCoordinator = fixture.coordinator,
        )

        assertSame(fixture.coordinator, receivedCoordinator)
        controller.close()
        fixture.close()
    }
}

private class InteractionCoordinatorFixture {
    val viewerSessionScopeTracker = ViewerSessionScopeTracker()
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val coordinator = InteractionCoordinator(
        repository = NoOpInteractionRepository,
        viewerSessionScopeTracker = viewerSessionScopeTracker,
        clockProvider = IosInteractionClock,
        coroutineScope = coroutineScope,
    )

    fun close() {
        coroutineScope.cancel()
    }
}

private object NoOpInteractionRepository : InteractionRepository {
    override suspend fun submit(command: InteractionCommand): DomainResult<InteractionSubmitOutcome> =
        DomainResult.Failure(DomainError.Unexpected("unused"))

    override suspend fun loadPending(
        accountId: String,
        listingIds: List<String>,
    ): DomainResult<List<PendingInteraction>> = DomainResult.Success(emptyList())

    override suspend fun drainDue(scope: InteractionAccountScope): DomainResult<InteractionDrainOutcome> =
        DomainResult.Success(InteractionDrainOutcome(scope = scope, operations = emptyList()))

    override suspend fun nextAttemptAt(accountId: String): DomainResult<Long?> = DomainResult.Success(null)

    override suspend fun retryAccount(
        scope: InteractionAccountScope,
        includeManualFailures: Boolean,
    ): DomainResult<Int> = DomainResult.Success(0)

    override suspend fun purge(accountId: String): DomainResult<Int> = DomainResult.Success(0)
}

private object IosInteractionClock : ClockProvider {
    override fun nowEpochMilliseconds(): Long = 0L
}

private object IosInteractionDispatcherProvider : DispatcherProvider {
    override val default: CoroutineDispatcher = Dispatchers.Unconfined
    override val io: CoroutineDispatcher = Dispatchers.Unconfined
    override val main: CoroutineDispatcher = Dispatchers.Unconfined
}
