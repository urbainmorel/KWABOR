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

    init {
        runtimeScope.coroutineContext[Job]?.invokeOnCompletion { effectChannel.close() }
    }

    fun resetForViewerLocked() {
        mutationJobs.values.forEach { job -> job.cancel() }
        mutationJobs.clear()
        mutationTokens.clear()
    }

    suspend fun applyExternalFavoriteState(intent: FavoritesIntent.ExternalFavoriteStateChanged) {
        val action = lifecycleMutex.withLock {
            val listingId = intent.listingId.trim()
            if (!canApplyExternalState(listingId, intent.scope)) {
                return@withLock FavoritesPageAction.None
            }
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

    private fun canApplyExternalState(listingId: String, scope: ViewerSessionScope): Boolean =
        listingId.isNotEmpty() &&
            scope.isAuthenticated &&
            scope == sessionState.activeViewerScope &&
            stateStore.value.viewerScope == scope

    private fun applyExternalAdditionLocked(
        listingId: String,
        scope: ViewerSessionScope,
    ): FavoritesPageAction {
        sessionState.removedListingIds -= listingId
        mutationJobs.remove(listingId)?.cancel()
        mutationTokens.remove(listingId)
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

    private fun applyExternalRemovalLocked(
        listingId: String,
        scope: ViewerSessionScope,
    ): FavoritesPageAction {
        sessionState.removedListingIds += listingId
        mutationJobs.remove(listingId)?.cancel()
        mutationTokens.remove(listingId)
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

    private fun prepareRemovalLocked(
        listingId: String,
        sourceScope: ViewerSessionScope,
    ): FavoriteMutationLaunch? {
        val scope = sessionState.activeViewerScope.takeIf { candidate -> candidate.isAuthenticated } ?: return null
        val current = stateStore.value
        if (!canPrepareRemoval(current, listingId, sourceScope, scope)) return null
        val token = ++mutationGeneration
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
        return FavoriteMutationLaunch(listingId = listingId, scope = scope, token = token)
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
        listingId !in state.removingListingIds

    private suspend fun launchRemoval(launch: FavoriteMutationLaunch) {
        lifecycleMutex.withLock {
            if (!launch.isCurrent()) return@withLock
            val job = runtimeScope.launch(start = CoroutineStart.LAZY) {
                val canStart = lifecycleMutex.withLock { launch.isCurrent() }
                if (!canStart) return@launch
                val outcome = presenter.removeFavorite(listingId = launch.listingId, strings = strings)
                completeRemoval(launch, outcome)
            }
            mutationJobs[launch.listingId] = job
            job.start()
        }
    }

    private suspend fun completeRemoval(
        launch: FavoriteMutationLaunch,
        outcome: FavoriteRemovalOutcome,
    ) {
        val completion = lifecycleMutex.withLock {
            if (!launch.isCurrent()) return@withLock null
            mutationJobs.remove(launch.listingId)
            mutationTokens.remove(launch.listingId)
            when (outcome) {
                is FavoriteRemovalOutcome.Removed -> completeRemovedLocked(launch, outcome)
                is FavoriteRemovalOutcome.Failed -> {
                    completeFailedLocked(launch, outcome)
                    null
                }
            }
        } ?: return
        completion.autoAppend?.let { backfill -> pageCoordinator.startAppend(backfill) }
        effectChannel.send(completion.effect)
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
                scope = launch.scope,
            ),
            autoAppend = pageCoordinator.registerRemovalBackfillLocked(launch.scope, updated),
        )
    }

    private fun completeFailedLocked(
        launch: FavoriteMutationLaunch,
        outcome: FavoriteRemovalOutcome.Failed,
    ) {
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

    private fun FavoriteMutationLaunch.isCurrent(): Boolean =
        sessionState.activeViewerScope == scope &&
            stateStore.value.viewerScope == scope &&
            mutationTokens[listingId] == token
}

private data class FavoriteMutationLaunch(
    val listingId: String,
    val scope: ViewerSessionScope,
    val token: Long,
)

private data class FavoriteRemovalCompletion(
    val effect: FavoritesEffect.FavoriteChanged,
    val autoAppend: FavoriteRemovalBackfill?,
)
