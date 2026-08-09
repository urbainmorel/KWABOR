package com.kwabor.shared.presentation.favorites

import com.kwabor.shared.i18n.FavoritesStrings
import com.kwabor.shared.presentation.session.ViewerSessionScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class FavoritesMutationCoordinator(
    private val presenter: FavoritesPresenter,
    private val strings: FavoritesStrings,
    context: FavoritesRuntimeContext,
    private val pageCoordinator: FavoritesPageCoordinator,
) {
    private val runtimeScope: CoroutineScope = context.runtimeScope
    private val lifecycleMutex: Mutex = context.lifecycleMutex
    private val stateStore: FavoritesStateStore = context.stateStore
    private val sessionState: FavoritesSessionState = context.sessionState
    private val effectChannel = Channel<FavoritesEffect>(capacity = Channel.BUFFERED)
    val effects: Flow<FavoritesEffect> = effectChannel.receiveAsFlow()
    private var mutationGeneration = 0L
    private val mutationJobs = mutableMapOf<String, Job>()
    private val mutationTokens = mutableMapOf<String, Long>()
    private val lastConfirmedMutationSequences = mutableMapOf<String, Long>()

    init {
        runtimeScope.coroutineContext[Job]?.invokeOnCompletion { effectChannel.close() }
    }

    fun resetForViewerLocked() {
        mutationJobs.values.forEach { job -> job.cancel() }
        mutationJobs.clear()
        mutationTokens.clear()
        lastConfirmedMutationSequences.clear()
    }

    suspend fun applyExternalFavoriteState(intent: FavoritesIntent.ExternalFavoriteStateChanged) {
        val action = lifecycleMutex.withLock {
            val listingId = intent.listingId.trim()
            if (!intent.canApplyTo(listingId, sessionState, stateStore)) {
                return@withLock FavoritesPageAction.None
            }
            val lastConfirmedSequence = lastConfirmedMutationSequences[listingId] ?: 0L
            if (intent.clientMutationSequence <= lastConfirmedSequence) {
                return@withLock FavoritesPageAction.None
            }
            lastConfirmedMutationSequences[listingId] = intent.clientMutationSequence
            if (intent.favorited) {
                applyExternalAdditionLocked(listingId, intent.scope)
            } else {
                applyExternalRemovalLocked(listingId, intent.scope)
            }
        }
        pageCoordinator.runAction(action)
    }

    suspend fun removeFavorite(rawListingId: String, sourceScope: ViewerSessionScope) {
        val launch = lifecycleMutex.withLock {
            prepareRemovalLocked(rawListingId.trim(), sourceScope)
        } ?: return
        launchRemoval(launch)
    }

    suspend fun openListing(rawListingId: String, sourceScope: ViewerSessionScope) {
        val listingId = rawListingId.trim()
        val scope = lifecycleMutex.withLock {
            sessionState.activeViewerScope.takeIf { candidate ->
                candidate == sourceScope &&
                    candidate.isAuthenticated &&
                    stateStore.value.viewerScope == candidate &&
                    stateStore.value.items.any { item -> item.id == listingId }
            }
        }
        scope?.let { currentScope ->
            effectChannel.send(FavoritesEffect.OpenCatalogDetail(listingId, currentScope))
        }
    }

    private fun applyExternalAdditionLocked(listingId: String, scope: ViewerSessionScope): FavoritesPageAction {
        sessionState.removedListingIds -= listingId
        stateStore.updateForScope(scope) { current ->
            val clearsTargetMessage = current.mutationMessageListingId == listingId
            val mutationMessageIsOffline = current.mutationMessageIsOffline && !clearsTargetMessage
            current.copy(
                removingListingIds = current.removingListingIds - listingId,
                isOffline = current.contentIsOffline || mutationMessageIsOffline,
                mutationMessage = current.mutationMessage.takeUnless { clearsTargetMessage },
                mutationMessageListingId = current.mutationMessageListingId.takeUnless { clearsTargetMessage },
                mutationMessageIsOffline = mutationMessageIsOffline,
                viewerScope = scope,
            )
        } ?: return FavoritesPageAction.None
        return if (sessionState.isScreenVisible) {
            FavoritesPageAction.Refresh
        } else {
            pageCoordinator.markExternalAdditionDirtyLocked()
            FavoritesPageAction.None
        }
    }

    private fun applyExternalRemovalLocked(listingId: String, scope: ViewerSessionScope): FavoritesPageAction {
        sessionState.removedListingIds += listingId
        val updated = stateStore.updateForScope(scope) { current ->
            val clearsTargetMessage = current.mutationMessageListingId == listingId
            val mutationMessageIsOffline = current.mutationMessageIsOffline && !clearsTargetMessage
            current.copy(
                items = current.items.filterNot { item -> item.id == listingId },
                removingListingIds = current.removingListingIds - listingId,
                isOffline = current.contentIsOffline || mutationMessageIsOffline,
                mutationMessage = current.mutationMessage.takeUnless { clearsTargetMessage },
                mutationMessageListingId = current.mutationMessageListingId.takeUnless { clearsTargetMessage },
                mutationMessageIsOffline = mutationMessageIsOffline,
                viewerScope = scope,
            )
        } ?: return FavoritesPageAction.None
        val backfill = pageCoordinator.registerRemovalBackfillLocked(scope, updated)
        return backfill?.let { request -> FavoritesPageAction.Append(request) } ?: FavoritesPageAction.None
    }

    private fun prepareRemovalLocked(listingId: String, sourceScope: ViewerSessionScope): FavoriteMutationLaunch? {
        val scope = sessionState.activeViewerScope.takeIf { candidate -> candidate.isAuthenticated } ?: return null
        val current = stateStore.value
        if (!canPrepareRemoval(current, listingId, sourceScope, scope)) return null
        val token = ++mutationGeneration
        val baselineConfirmedSequence = lastConfirmedMutationSequences[listingId] ?: 0L
        mutationTokens[listingId] = token
        stateStore.updateForScope(scope) { latest ->
            val clearsTargetMessage = latest.mutationMessageListingId == listingId
            val mutationMessageIsOffline = latest.mutationMessageIsOffline && !clearsTargetMessage
            latest.copy(
                isOffline = latest.contentIsOffline || mutationMessageIsOffline,
                mutationMessage = latest.mutationMessage.takeUnless { clearsTargetMessage },
                mutationMessageListingId = latest.mutationMessageListingId.takeUnless { clearsTargetMessage },
                mutationMessageIsOffline = mutationMessageIsOffline,
                removingListingIds = latest.removingListingIds + listingId,
                viewerScope = scope,
            )
        } ?: return null
        return FavoriteMutationLaunch(
            listingId = listingId,
            scope = scope,
            token = token,
            baselineConfirmedSequence = baselineConfirmedSequence,
        )
    }

    private fun canPrepareRemoval(
        state: FavoritesUiState,
        listingId: String,
        sourceScope: ViewerSessionScope,
        activeScope: ViewerSessionScope,
    ): Boolean = sourceScope == activeScope &&
        state.viewerScope == sourceScope &&
        sessionState.hasCurrentActiveAccount(stateStore) &&
        listingId.isNotEmpty() &&
        state.items.any { item -> item.id == listingId } &&
        listingId !in mutationTokens

    private suspend fun launchRemoval(launch: FavoriteMutationLaunch) {
        lifecycleMutex.withLock {
            if (!launch.isCurrent(sessionState, stateStore, mutationTokens)) return@withLock
            val job = runtimeScope.launch(start = CoroutineStart.LAZY) {
                val canStart = lifecycleMutex.withLock {
                    launch.isCurrent(sessionState, stateStore, mutationTokens)
                }
                if (!canStart) return@launch
                val outcome = presenter.removeFavorite(listingId = launch.listingId, strings = strings)
                completeRemoval(launch, outcome)
            }
            mutationJobs[launch.listingId] = job
            job.start()
        }
    }

    private suspend fun completeRemoval(launch: FavoriteMutationLaunch, outcome: FavoriteRemovalOutcome) {
        val completion = lifecycleMutex.withLock {
            val ownsMutation = mutationTokens[launch.listingId] == launch.token
            if (ownsMutation) {
                mutationJobs.remove(launch.listingId)
                mutationTokens.remove(launch.listingId)
            }
            if (!ownsMutation || !launch.hasCurrentScope(sessionState, stateStore)) return@withLock null
            when (outcome) {
                is FavoriteRemovalOutcome.Removed -> completeConfirmedRemovalLocked(launch, outcome)
                is FavoriteRemovalOutcome.Failed -> {
                    completeFailedRemovalLocked(launch, outcome)
                    null
                }
            }
        } ?: return
        completion.autoAppend?.let { backfill -> pageCoordinator.startAppend(backfill) }
        effectChannel.send(completion.effect)
    }

    private fun completeConfirmedRemovalLocked(
        launch: FavoriteMutationLaunch,
        outcome: FavoriteRemovalOutcome.Removed,
    ): FavoriteRemovalCompletion? {
        val lastConfirmedSequence = lastConfirmedMutationSequences[launch.listingId] ?: 0L
        if (outcome.clientMutationSequence <= lastConfirmedSequence) {
            clearPendingRemovalLocked(launch)
            return null
        }
        lastConfirmedMutationSequences[launch.listingId] = outcome.clientMutationSequence
        return completeRemovedLocked(launch, outcome)
    }

    private fun completeRemovedLocked(
        launch: FavoriteMutationLaunch,
        outcome: FavoriteRemovalOutcome.Removed,
    ): FavoriteRemovalCompletion? {
        sessionState.removedListingIds += outcome.listingId
        val updated = stateStore.updateForScope(launch.scope) { latest ->
            val clearsOwnMessage = latest.mutationMessageListingId == outcome.listingId
            val mutationMessageIsOffline = latest.mutationMessageIsOffline && !clearsOwnMessage
            latest.copy(
                items = latest.items.filterNot { item -> item.id == outcome.listingId },
                isOffline = latest.contentIsOffline || mutationMessageIsOffline,
                mutationMessage = latest.mutationMessage.takeUnless { clearsOwnMessage },
                mutationMessageListingId = latest.mutationMessageListingId.takeUnless { clearsOwnMessage },
                mutationMessageIsOffline = mutationMessageIsOffline,
                removingListingIds = latest.removingListingIds - outcome.listingId,
                viewerScope = launch.scope,
            )
        } ?: return null
        return FavoriteRemovalCompletion(
            effect = FavoritesEffect.FavoriteChanged(
                listingId = outcome.listingId,
                favorited = false,
                clientMutationSequence = outcome.clientMutationSequence,
                scope = launch.scope,
            ),
            autoAppend = pageCoordinator.registerRemovalBackfillLocked(launch.scope, updated),
        )
    }

    private fun completeFailedRemovalLocked(launch: FavoriteMutationLaunch, outcome: FavoriteRemovalOutcome.Failed) {
        val lastConfirmedSequence = lastConfirmedMutationSequences[launch.listingId] ?: 0L
        if (lastConfirmedSequence > launch.baselineConfirmedSequence) {
            clearPendingRemovalLocked(launch)
            return
        }
        stateStore.updateForScope(launch.scope) { latest ->
            latest.copy(
                isOffline = latest.contentIsOffline || outcome.isOffline,
                mutationMessage = outcome.message,
                mutationMessageListingId = launch.listingId,
                mutationMessageIsOffline = outcome.isOffline,
                removingListingIds = latest.removingListingIds - launch.listingId,
                viewerScope = launch.scope,
            )
        }
    }

    private fun clearPendingRemovalLocked(launch: FavoriteMutationLaunch) {
        stateStore.updateForScope(launch.scope) { latest ->
            latest.copy(
                removingListingIds = latest.removingListingIds - launch.listingId,
                viewerScope = launch.scope,
            )
        }
    }
}

private data class FavoriteMutationLaunch(
    val listingId: String,
    val scope: ViewerSessionScope,
    val token: Long,
    val baselineConfirmedSequence: Long,
)

private fun FavoriteMutationLaunch.isCurrent(
    sessionState: FavoritesSessionState,
    stateStore: FavoritesStateStore,
    mutationTokens: Map<String, Long>,
): Boolean = hasCurrentScope(sessionState, stateStore) && mutationTokens[listingId] == token

private fun FavoriteMutationLaunch.hasCurrentScope(
    sessionState: FavoritesSessionState,
    stateStore: FavoritesStateStore,
): Boolean = sessionState.activeViewerScope == scope && stateStore.value.viewerScope == scope

private fun FavoritesIntent.ExternalFavoriteStateChanged.canApplyTo(
    normalizedListingId: String,
    sessionState: FavoritesSessionState,
    stateStore: FavoritesStateStore,
): Boolean {
    if (clientMutationSequence <= 0L || normalizedListingId.isEmpty()) return false
    if (!scope.isAuthenticated || stateStore.value.viewerScope != scope) return false
    return scope == sessionState.activeViewerScope
}

private data class FavoriteRemovalCompletion(
    val effect: FavoritesEffect.FavoriteChanged,
    val autoAppend: FavoriteRemovalBackfill?,
)
