package com.kwabor.shared.presentation.notification

import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.presentation.session.ViewerSessionScope
import com.kwabor.shared.presentation.session.ViewerSessionScopeTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class NotificationRuntime internal constructor(
    repositories: NotificationRuntimeRepositories,
    presenter: NotificationPresenter,
    clockProvider: ClockProvider,
    private val viewerSessionScopeTracker: ViewerSessionScopeTracker,
    private val syncCoordinator: NotificationSyncCoordinator,
    coroutineScope: CoroutineScope,
) {
    private val runtimeJob = SupervisorJob(coroutineScope.coroutineContext[Job])
    private val runtimeScope = CoroutineScope(coroutineScope.coroutineContext + runtimeJob)
    private val lifecycleMutex = Mutex()
    private val commandQueue = NotificationRuntimeCommandQueue()
    private val effectQueue = NotificationRuntimeEffectQueue()
    private val stateStore = NotificationStateStore()
    private val session = NotificationRuntimeSession(runtimeScope)
    private val context =
        NotificationRuntimeContext(
            lifecycleMutex,
            stateStore,
            session,
            syncCoordinator,
            clockProvider,
            presenter,
        )
    private val publisher = NotificationRuntimePublisher(context)
    private val persistence = NotificationRuntimePersistence(repositories.offline, clockProvider)
    private val pageController =
        NotificationRuntimePageController(
            context,
            repositories.inbox,
            repositories.offline,
            persistence,
            publisher,
        )
    private val preferencesController =
        NotificationRuntimePreferencesController(
            context,
            repositories.preferences,
            repositories.offline,
            persistence,
            publisher,
        )
    private val actionController = NotificationRuntimeActionController(context, publisher, effectQueue)
    private val statusController =
        NotificationRuntimeStatusController(
            context,
            repositories.inbox,
            pageController,
            persistence,
            publisher,
        )
    private val reducer =
        NotificationRuntimeReducer(
            context,
            pageController,
            statusController,
            preferencesController,
            actionController,
            publisher,
        )
    val state: StateFlow<NotificationUiState> = stateStore.state
    val effects: Flow<NotificationEffect> = effectQueue.asFlow().filter(::isEffectCurrent)

    init {
        collectSyncSignals()
        runtimeScope.launch {
            for (ignored in commandQueue.signal) {
                var command = commandQueue.take()
                while (command != null) {
                    reducer.handle(command)
                    command = commandQueue.take()
                }
            }
        }
        activateInitialViewer()
    }

    fun dispatch(intent: NotificationIntent) {
        if (!runtimeJob.isActive) return
        if (intent is NotificationIntent.ViewerContextChanged) {
            dispatchViewerChanged(intent.scope)
            return
        }
        val current = stateStore.value
        offerCommand(
            NotificationRuntimeCommand.Intent(intent, current.accountScope, context.currentLifecycleGeneration()),
        )
    }

    fun close() {
        commandQueue.clear()
        commandQueue.signal.close()
        effectQueue.close()
        runtimeJob.cancel()
    }

    internal suspend fun invalidateAfterCompositePurge(accountId: String) {
        val canonicalAccountId = accountId.toCanonicalNotificationAccountId()
        syncCoordinator.invalidateAfterCompositePurge(canonicalAccountId)
        val needsReplacementViewer =
            lifecycleMutex.withLock {
                val sessionBelongsToAccount = session.activeScope?.accountId == canonicalAccountId
                if (!sessionBelongsToAccount) return@withLock false
                session.resetForInvalidation()
                stateStore.invalidateAccount(canonicalAccountId)
                context.advanceLifecycleGeneration()
                true
            }
        commandQueue.clearAccount(canonicalAccountId)
        effectQueue.clearAccount(canonicalAccountId)
        if (needsReplacementViewer) {
            transitionToLatestViewer(excludedAccountId = canonicalAccountId)
        }
    }

    internal suspend fun registerAccountDeletionBlock(accountId: String): NotificationDeletionBlockRegistration =
        syncCoordinator.registerAccountDeletionBlock(accountId.toCanonicalNotificationAccountId())

    internal suspend fun finishAccountDeletionBlock(
        token: NotificationDeletionBlockToken,
        committed: Boolean,
    ): Boolean = syncCoordinator.finishAccountDeletionBlock(token, committed)

    internal suspend fun resumeAfterAccountDeletionFailure(accountId: String): Boolean {
        val canonicalAccountId = accountId.toCanonicalNotificationAccountId()
        val resumed = syncCoordinator.resumeAfterAccountDeletionFailure(canonicalAccountId)
        if (!resumed) return false
        val sessionNeedsReactivation =
            lifecycleMutex.withLock {
                session.viewerScope.toNotificationAccountScopeOrNull()?.accountId == canonicalAccountId
            }
        if (!sessionNeedsReactivation) return true
        val latestViewer = transitionToLatestViewer()
        val latestScope = latestViewer.toNotificationAccountScopeOrNull()
        if (
            latestScope?.accountId == canonicalAccountId &&
            viewerSessionScopeTracker.currentScope == latestViewer
        ) {
            syncCoordinator.wake(NotificationWakeRetryMode.Automatic, latestScope)
        }
        return true
    }

    private fun collectSyncSignals() {
        runtimeScope.launch {
            var handledRevision = 0L
            syncCoordinator.signals.collect { signal ->
                if (signal != null && signal.revision > handledRevision) {
                    handledRevision = signal.revision
                    offerCommand(
                        NotificationRuntimeCommand.SyncChanged(
                            signal,
                            context.currentLifecycleGeneration(),
                        ),
                    )
                }
            }
        }
    }

    private fun activateInitialViewer() {
        val initialScope = viewerSessionScopeTracker.currentScope
        if (initialScope == ViewerSessionScope.InitialGuest) return
        transitionToViewer(initialScope, force = true)
    }

    private fun dispatchViewerChanged(scope: ViewerSessionScope) {
        transitionToViewer(scope, force = false)
    }

    private fun enqueueViewerChanged(
        scope: ViewerSessionScope,
        generation: Long = context.currentLifecycleGeneration(),
    ) {
        offerCommand(
            NotificationRuntimeCommand.ViewerChanged(scope, generation),
        )
    }

    private fun transitionToViewer(
        scope: ViewerSessionScope,
        force: Boolean,
    ): Boolean {
        if (scope != viewerSessionScopeTracker.currentScope) return false
        val generation = context.admitViewerTransition(scope, force) ?: return false
        stateStore.publishViewerScope(scope)
        enqueueViewerChanged(scope, generation)
        return true
    }

    private fun transitionToLatestViewer(excludedAccountId: String? = null): ViewerSessionScope {
        var force = true
        while (true) {
            val candidate = viewerSessionScopeTracker.currentScope
            if (candidate.toNotificationAccountScopeOrNull()?.accountId != excludedAccountId) {
                transitionToViewer(candidate, force)
            }
            val latest = viewerSessionScopeTracker.currentScope
            if (candidate == latest) return latest
            force = false
        }
    }

    private fun offerCommand(command: NotificationRuntimeCommand) {
        val result = commandQueue.offer(command)
        val rejectedScope = (result as? NotificationCommandOfferResult.Rejected)?.scope ?: return
        if (command !is NotificationRuntimeCommand.Intent) return
        publisher.publishMutationFailure(rejectedScope, result.rejectionError())
        syncCoordinator.wake(NotificationWakeRetryMode.Manual, rejectedScope)
    }

    private fun isEffectCurrent(effect: NotificationEffect): Boolean {
        val current = stateStore.value
        return current.accountScope == effect.scope &&
            context.currentLifecycleGeneration() == effect.presentationGeneration
    }
}
