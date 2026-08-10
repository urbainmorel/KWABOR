package com.kwabor.shared.presentation.explore

import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.interaction.InteractionAccountScope
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.interaction.InteractionAccountLifecycleGeneration
import com.kwabor.shared.presentation.interaction.InteractionCoordinator
import com.kwabor.shared.presentation.interaction.InteractionCoordinatorEvent
import com.kwabor.shared.presentation.interaction.InteractionHydration
import com.kwabor.shared.presentation.interaction.InteractionReconciliationSignal
import com.kwabor.shared.presentation.session.ViewerSessionScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ExploreDurableSignalReconciler(
    private val coordinator: InteractionCoordinator,
    private val strings: KwaborStrings,
    private val stateStore: ExploreStateStore,
    private val interactionMutex: Mutex,
    private val callbacks: ExploreDurableRuntimeCallbacks,
    private val scheduleAuthoritativeReconciliation: (ViewerSessionScope) -> Unit,
) {
    suspend fun reconcile(
        signal: InteractionReconciliationSignal,
        expectedScope: ViewerSessionScope,
        forceAuthoritativeReconciliation: Boolean,
    ): Boolean {
        val current = stateStore.snapshot()
        if (current.viewerScope != expectedScope) return false
        val visibleIds = current.listings.map(ExploreListingItem::id).normalizeVisibleListingIds()
        val windows = loadWindows(expectedScope, visibleIds) ?: return false
        val result = commit(signal, expectedScope, windows, forceAuthoritativeReconciliation) ?: return false
        if (result.requiresAuthoritativeReconciliation) {
            scheduleAuthoritativeReconciliation(expectedScope)
        }
        return true
    }

    suspend fun hydrateVisible(
        expectedScope: ViewerSessionScope,
        listingIds: List<String>,
        forceAuthoritativeReconciliation: Boolean,
        deliveryEvent: InteractionCoordinatorEvent? = null,
    ): Boolean {
        val interactionScope = expectedScope.toInteractionAccountScopeOrNull() ?: return false
        val generation = coordinator.deliveryCommitGate.captureLifecycleGeneration(interactionScope) ?: return false
        val windows = loadWindows(expectedScope, listingIds.normalizeVisibleListingIds()) ?: return false
        val result = commitVisible(
            expectedScope = expectedScope,
            windows = windows,
            forceAuthoritativeReconciliation = forceAuthoritativeReconciliation,
            deliveryEvent = deliveryEvent,
            generation = generation,
        ) ?: return false
        if (result.requiresAuthoritativeReconciliation) {
            scheduleAuthoritativeReconciliation(expectedScope)
        }
        return true
    }

    private suspend fun loadWindows(
        expectedScope: ViewerSessionScope,
        visibleIds: List<String>,
    ): List<ExploreHydrationWindow>? {
        val interactionScope = expectedScope.toInteractionAccountScopeOrNull() ?: return null
        val windows = mutableListOf<ExploreHydrationWindow>()
        for (listingIds in visibleIds.chunked(EXPLORE_RUNTIME_HYDRATION_WINDOW_SIZE)) {
            val window = loadWindow(expectedScope, interactionScope, listingIds) ?: return null
            windows += window
        }
        return windows
    }

    private suspend fun loadWindow(
        expectedScope: ViewerSessionScope,
        interactionScope: InteractionAccountScope,
        listingIds: List<String>,
    ): ExploreHydrationWindow? {
        if (!callbacks.isCurrentScope(expectedScope)) return null
        val expectations = stateStore.captureDurableHydrationExpectations(
            requestedListingIds = listingIds,
            expectedScope = expectedScope,
        ) ?: return null
        val hydration = when (val loaded = coordinator.hydrate(interactionScope, listingIds)) {
            is DomainResult.Failure -> return null
            is DomainResult.Success -> loaded.value
        }
        return ExploreHydrationWindow(listingIds, hydration, expectations).takeIf {
            hydration.isValidFor(expectedScope, listingIds)
        }
    }

    private suspend fun commitVisible(
        expectedScope: ViewerSessionScope,
        windows: List<ExploreHydrationWindow>,
        forceAuthoritativeReconciliation: Boolean,
        deliveryEvent: InteractionCoordinatorEvent?,
        generation: InteractionAccountLifecycleGeneration,
    ): ExploreHydrationResult.Succeeded? {
        var result: ExploreHydrationResult = ExploreHydrationResult.Failed
        val commitAction: suspend () -> Unit = {
            result = commitWindows(expectedScope, windows, forceAuthoritativeReconciliation)
        }
        val committed = if (deliveryEvent != null) {
            coordinator.deliveryCommitGate.runIfEventDeliveryValid(deliveryEvent, commitAction)
        } else {
            coordinator.deliveryCommitGate.runIfLifecycleGenerationCurrent(
                scope = expectedScope.toInteractionAccountScopeOrNull() ?: return null,
                generation = generation,
                action = commitAction,
            )
        }
        if (!committed) return null
        return result as? ExploreHydrationResult.Succeeded
    }

    private suspend fun commit(
        signal: InteractionReconciliationSignal,
        expectedScope: ViewerSessionScope,
        windows: List<ExploreHydrationWindow>,
        forceAuthoritativeReconciliation: Boolean,
    ): ExploreHydrationResult.Succeeded? {
        var result: ExploreHydrationResult = ExploreHydrationResult.Failed
        val committedCurrentSignal = coordinator.deliveryCommitGate.runIfReconciliationCurrent(signal) {
            result = commitWindows(expectedScope, windows, forceAuthoritativeReconciliation)
        }
        if (!committedCurrentSignal) return null
        return result as? ExploreHydrationResult.Succeeded
    }

    private suspend fun commitWindows(
        expectedScope: ViewerSessionScope,
        windows: List<ExploreHydrationWindow>,
        forceAuthoritativeReconciliation: Boolean,
    ): ExploreHydrationResult = interactionMutex.withLock {
        if (!callbacks.isCurrentScope(expectedScope)) return@withLock ExploreHydrationResult.Failed
        applyWindows(expectedScope, windows, forceAuthoritativeReconciliation)
    }

    private suspend fun applyWindows(
        expectedScope: ViewerSessionScope,
        windows: List<ExploreHydrationWindow>,
        forceAuthoritativeReconciliation: Boolean,
    ): ExploreHydrationResult {
        var requiresAuthoritativeReconciliation = forceAuthoritativeReconciliation
        windows.forEach { window ->
            val commit = stateStore.applyDurableHydration(
                hydration = window.hydration,
                requestedListingIds = window.listingIds,
                expectations = window.expectations,
                expectedScope = expectedScope,
                strings = strings,
            ) ?: return ExploreHydrationResult.Failed
            requiresAuthoritativeReconciliation = requiresAuthoritativeReconciliation ||
                commit.requiresAuthoritativeReconciliation
        }
        return ExploreHydrationResult.Succeeded(requiresAuthoritativeReconciliation)
    }
}

private data class ExploreHydrationWindow(
    val listingIds: List<String>,
    val hydration: InteractionHydration,
    val expectations: Map<ExploreInteractionRevisionKey, ExploreDurableHydrationExpectation>,
)
