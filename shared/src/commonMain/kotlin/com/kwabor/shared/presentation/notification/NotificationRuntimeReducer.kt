package com.kwabor.shared.presentation.notification

import com.kwabor.shared.domain.notification.NotificationAccountScope
import com.kwabor.shared.domain.notification.NotificationSyncCommand
import com.kwabor.shared.domain.notification.NotificationSyncOperationOutcome
import com.kwabor.shared.presentation.session.ViewerSessionScope
import kotlinx.coroutines.sync.withLock

internal class NotificationRuntimeReducer(
    private val context: NotificationRuntimeContext,
    private val pageController: NotificationRuntimePageController,
    private val statusController: NotificationRuntimeStatusController,
    private val preferencesController: NotificationRuntimePreferencesController,
    private val actionController: NotificationRuntimeActionController,
    private val publisher: NotificationRuntimePublisher,
) {
    suspend fun reactivateViewer(viewerScope: ViewerSessionScope) {
        updateViewerContext(viewerScope)
    }

    suspend fun handle(command: NotificationRuntimeCommand) {
        val commandLease = command.sourceScope?.let { scope -> context.syncCoordinator.beginOperation(scope) }
        if (command.sourceScope != null && commandLease == null) return
        try {
            if (!isCurrent(command, commandLease)) return
            when (command) {
                is NotificationRuntimeCommand.Intent -> handleIntent(command.intent, command.sourceScope)
                is NotificationRuntimeCommand.SyncChanged -> handleSyncSignal(command.signal)
                is NotificationRuntimeCommand.ViewerChanged -> updateViewerContext(command.scope)
            }
        } finally {
            if (commandLease != null) context.syncCoordinator.endOperation(commandLease)
        }
    }

    private suspend fun handleIntent(
        intent: NotificationIntent,
        sourceScope: NotificationAccountScope?,
    ) {
        when (intent) {
            is NotificationIntent.Lifecycle -> handleLifecycle(intent)
            is NotificationIntent.Page -> handlePage(intent, sourceScope)
            is NotificationIntent.ItemAction -> handleItemAction(intent, sourceScope)
            is NotificationIntent.PreferenceAction -> handlePreferenceAction(intent, sourceScope)
            is NotificationIntent.DetailPresentation -> handleDetailPresentation(intent)
        }
    }

    private suspend fun isCurrent(
        command: NotificationRuntimeCommand,
        commandLease: NotificationAccountOperationLease?,
    ): Boolean {
        val sourceScope = command.sourceScope
        return context.lifecycleMutex.withLock {
            command.runtimeGeneration == context.currentLifecycleGeneration() &&
                if (sourceScope == null) {
                    commandLease == null
                } else {
                    commandLease != null &&
                        context.syncCoordinator.isOperationLeaseCurrent(commandLease, sourceScope)
                }
        }
    }

    private suspend fun handleLifecycle(intent: NotificationIntent.Lifecycle) {
        when (intent) {
            NotificationIntent.ScreenAppeared -> screenAppeared()
            NotificationIntent.ScreenDisappeared ->
                context.lifecycleMutex.withLock {
                    context.session.screenVisible = false
                }
            NotificationIntent.Foregrounded -> foregrounded()
            is NotificationIntent.SnapshotPresented -> actionController.snapshotPresented(intent)
            is NotificationIntent.ViewerContextChanged -> updateViewerContext(intent.scope)
        }
    }

    private suspend fun handlePage(
        intent: NotificationIntent.Page,
        sourceScope: NotificationAccountScope?,
    ) {
        val scope = sourceScope ?: return
        if (!sessionMatches(scope)) return
        when (intent) {
            NotificationIntent.LoadNext -> pageController.startAppend(scope)
            NotificationIntent.Refresh -> pageController.startPageLoad(scope, NotificationPageLoadMode.Refresh)
            NotificationIntent.Retry -> {
                context.syncCoordinator.wake(NotificationWakeRetryMode.Manual, scope)
                pageController.startPageLoad(scope, NotificationPageLoadMode.Retry)
            }
        }
    }

    private suspend fun handleItemAction(
        intent: NotificationIntent.ItemAction,
        sourceScope: NotificationAccountScope?,
    ) {
        val scope = sourceScope ?: return
        if (!sessionMatches(scope)) return
        when (intent) {
            is NotificationIntent.HideNotification ->
                actionController.submitItemCommand(
                    scope,
                    NotificationSyncCommand.Hide(scope, intent.notificationId),
                )
            NotificationIntent.MarkAllRead -> actionController.markAllRead(scope)
            is NotificationIntent.OpenNotification -> actionController.openNotification(scope, intent.notificationId)
        }
    }

    private suspend fun handlePreferenceAction(
        intent: NotificationIntent.PreferenceAction,
        sourceScope: NotificationAccountScope?,
    ) {
        when (intent) {
            NotificationIntent.PreferencesScreenAppeared -> preferencesScreenAppeared()
            NotificationIntent.PreferencesScreenDisappeared ->
                context.lifecycleMutex.withLock {
                    context.session.preferencesVisible = false
                }
            NotificationIntent.OpenPreferences -> sourceScope?.let { scope -> actionController.openPreferences(scope) }
            NotificationIntent.RetryPreferences ->
                sourceScope?.let { scope ->
                    if (sessionMatches(scope)) {
                        context.syncCoordinator.wake(NotificationWakeRetryMode.Manual, scope)
                        preferencesController.startLoad(scope)
                    }
                }
            is NotificationIntent.SetPreference ->
                sourceScope?.let { scope ->
                    if (sessionMatches(scope)) {
                        actionController.submitPreferenceCommand(
                            scope,
                            NotificationSyncCommand.SetFamilyEnabled(scope, intent.family, intent.enabled),
                        )
                    }
                }
        }
    }

    private suspend fun handleDetailPresentation(intent: NotificationIntent.DetailPresentation) {
        when (intent) {
            is NotificationIntent.DetailSheetPresentationConfirmed -> actionController.confirmDetailPresentation(intent)
            is NotificationIntent.DetailSheetPresentationFailed -> actionController.failDetailPresentation(intent)
        }
    }

    private suspend fun updateViewerContext(viewerScope: ViewerSessionScope) {
        val notificationScope = viewerScope.toNotificationAccountScopeOrNull()
        context.syncCoordinator.onViewerContextChanged(viewerScope)
        val visibility =
            context.lifecycleMutex.withLock {
                if (
                    context.session.viewerScope == viewerScope &&
                    context.session.activeScope == notificationScope
                ) {
                    return@withLock null
                }
                context.session.switchViewer(viewerScope, notificationScope)
                NotificationVisibility(context.session.screenVisible, context.session.preferencesVisible)
            } ?: return
        if (notificationScope == null) return
        if (visibility.notificationCenter) {
            pageController.startPageLoad(notificationScope, NotificationPageLoadMode.Initial)
        } else {
            statusController.startStatusLoad(notificationScope)
        }
        if (visibility.preferences) preferencesController.startLoad(notificationScope)
    }

    private suspend fun screenAppeared() {
        context.syncCoordinator.wake(NotificationWakeRetryMode.Automatic)
        val scope =
            context.lifecycleMutex.withLock {
                context.session.screenVisible = true
                context.session.activeScope
            } ?: return
        pageController.startPageLoad(scope, NotificationPageLoadMode.Initial)
    }

    private suspend fun preferencesScreenAppeared() {
        context.syncCoordinator.wake(NotificationWakeRetryMode.Automatic)
        val scope =
            context.lifecycleMutex.withLock {
                context.session.preferencesVisible = true
                context.session.activeScope
            } ?: return
        preferencesController.startLoad(scope)
    }

    private suspend fun foregrounded() {
        context.syncCoordinator.wake(NotificationWakeRetryMode.Automatic)
        val work =
            context.lifecycleMutex.withLock {
                val scope = context.session.activeScope ?: return@withLock null
                NotificationForegroundWork(scope, context.session.screenVisible, context.session.preferencesVisible)
            } ?: return
        if (work.notificationCenter) {
            pageController.startPageLoad(work.scope, NotificationPageLoadMode.Refresh)
        } else {
            statusController.startStatusLoad(work.scope)
        }
        if (work.preferences) preferencesController.startLoad(work.scope)
    }

    private suspend fun handleSyncSignal(signal: NotificationSyncSignal) {
        if (!sessionMatches(signal.scope)) return
        when (signal) {
            is NotificationSyncSignal.Failed ->
                context.lifecycleMutex.withLock {
                    if (context.session.isCurrent(signal.scope)) {
                        publisher.publishMutationFailure(signal.scope, signal.error)
                    }
                }
            is NotificationSyncSignal.Reconcile -> {
                val hasConfirmed =
                    signal.outcome.operations.any { outcome ->
                        outcome is NotificationSyncOperationOutcome.Confirmed ||
                            outcome is NotificationSyncOperationOutcome.Superseded
                    }
                val work =
                    context.lifecycleMutex.withLock {
                        if (!context.session.isCurrent(signal.scope)) return@withLock null
                        NotificationForegroundWork(
                            signal.scope,
                            context.session.screenVisible,
                            context.session.preferencesVisible,
                        )
                    } ?: return
                if (work.notificationCenter && hasConfirmed) {
                    pageController.startPageLoad(work.scope, NotificationPageLoadMode.Refresh)
                } else {
                    statusController.startStatusLoad(work.scope)
                }
                if (work.preferences) preferencesController.startLoad(work.scope)
            }
        }
    }

    private suspend fun sessionMatches(scope: NotificationAccountScope): Boolean =
        context.lifecycleMutex.withLock { context.session.isCurrent(scope) }
}
