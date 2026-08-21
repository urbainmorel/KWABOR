package com.kwabor.shared.presentation.notification

import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.notification.NotificationAccountScope
import com.kwabor.shared.domain.notification.NotificationSyncCommand
import com.kwabor.shared.domain.notification.PendingNotificationSync
import kotlinx.coroutines.sync.withLock

internal class NotificationRuntimeActionController(
    private val context: NotificationRuntimeContext,
    private val publisher: NotificationRuntimePublisher,
    private val effectQueue: NotificationRuntimeEffectQueue,
) {
    suspend fun snapshotPresented(intent: NotificationIntent.SnapshotPresented) {
        val request =
            context.lifecycleMutex.withLock {
                val current = context.stateStore.value
                val snapshot = context.session.inbox?.snapshotSequence ?: return@withLock null
                val duplicate =
                    context.session.presentedSnapshot ==
                        NotificationPresentedSnapshot(snapshot, intent.presentationGeneration)
                if (
                    !context.session.screenVisible ||
                    !context.session.isCurrent(intent.scope) ||
                    current.presentationGeneration != intent.presentationGeneration ||
                    snapshot != intent.snapshotSequence ||
                    duplicate
                ) {
                    return@withLock null
                }
                NotificationPresentedSnapshot(snapshot, intent.presentationGeneration)
            } ?: return
        if (request.snapshotSequence == 0L) {
            context.lifecycleMutex.withLock {
                if (context.session.isCurrent(intent.scope)) context.session.presentedSnapshot = request
            }
            return
        }
        submitCommandAndProject(
            intent.scope,
            NotificationSyncCommand.AdvanceSeenThrough(intent.scope, request.snapshotSequence),
        ) { context.session.presentedSnapshot = request }
    }

    suspend fun markAllRead(scope: NotificationAccountScope) {
        val boundary =
            context.lifecycleMutex.withLock {
                context.session.inbox?.snapshotSequence?.takeIf { snapshot ->
                    context.session.isCurrent(scope) && snapshot > 0L
                }
            } ?: return
        submitCommandAndProject(scope, NotificationSyncCommand.MarkAllReadThrough(scope, boundary))
    }

    suspend fun submitItemCommand(
        scope: NotificationAccountScope,
        command: NotificationSyncCommand,
    ) {
        val targetExists =
            context.lifecycleMutex.withLock {
                val id =
                    when (command) {
                        is NotificationSyncCommand.Hide -> command.notificationId
                        is NotificationSyncCommand.MarkRead -> command.notificationId
                        is NotificationSyncCommand.AdvanceSeenThrough,
                        is NotificationSyncCommand.MarkAllReadThrough,
                        is NotificationSyncCommand.SetFamilyEnabled,
                        -> return@withLock false
                    }
                context.session.projectedInbox(context.clockProvider.safeNotificationNow())
                    ?.items
                    ?.any { item -> item.id == id } == true
            }
        if (targetExists) submitCommandAndProject(scope, command)
    }

    suspend fun submitPreferenceCommand(
        scope: NotificationAccountScope,
        command: NotificationSyncCommand.SetFamilyEnabled,
    ) {
        submitCommandAndProject(scope, command)
    }

    suspend fun openNotification(
        scope: NotificationAccountScope,
        notificationId: String,
    ) {
        val lease = context.syncCoordinator.beginOperation(scope) ?: return
        try {
            val prepared = prepareDetail(scope, notificationId, lease) ?: return
            val submitted = context.syncCoordinator.submit(NotificationSyncCommand.MarkRead(scope, notificationId))
            val pending = if (submitted is DomainResult.Success) context.syncCoordinator.loadPending(scope) else null
            if (!context.syncCoordinator.isOperationLeaseCurrent(lease, scope)) return
            val effect = commitDetailOpen(scope, prepared, submitted, pending, lease) ?: return
            tryPublishNavigationEffect(effect, scope, lease)
        } finally {
            context.syncCoordinator.endOperation(lease)
        }
    }

    suspend fun openPreferences(scope: NotificationAccountScope) {
        val lease = context.syncCoordinator.beginOperation(scope) ?: return
        try {
            val effect =
                context.lifecycleMutex.withLock {
                    if (!context.syncCoordinator.isOperationLeaseCurrent(lease, scope)) return@withLock null
                    if (!context.session.isCurrent(scope)) return@withLock null
                    NotificationEffect.OpenNotificationPreferences(
                        scope = scope,
                        presentationGeneration = context.currentLifecycleGeneration(),
                    )
                } ?: return
            tryPublishNavigationEffect(effect, scope, lease)
        } finally {
            context.syncCoordinator.endOperation(lease)
        }
    }

    suspend fun confirmDetailPresentation(intent: NotificationIntent.DetailSheetPresentationConfirmed) {
        val lease = context.syncCoordinator.beginOperation(intent.scope) ?: return
        try {
            val effect =
                context.lifecycleMutex.withLock {
                    if (!context.syncCoordinator.isOperationLeaseCurrent(lease, intent.scope)) return@withLock null
                    val pending = context.session.pendingDetail ?: return@withLock null
                    if (!pending.matches(intent, context.session)) return@withLock null
                    context.session.pendingDetail = null
                    NotificationEffect.RecordOpenedAnalytics(
                        notificationId = pending.notificationId,
                        kind = pending.kind,
                        cityId = pending.cityId,
                        ticket = pending.ticket,
                        scope = pending.scope,
                        presentationGeneration = pending.lifecycleGeneration,
                    )
                } ?: return
            if (context.syncCoordinator.isOperationLeaseCurrent(lease, intent.scope)) effectQueue.offer(effect)
        } finally {
            context.syncCoordinator.endOperation(lease)
        }
    }

    suspend fun failDetailPresentation(intent: NotificationIntent.DetailSheetPresentationFailed) {
        context.lifecycleMutex.withLock {
            val pending = context.session.pendingDetail ?: return@withLock
            if (
                context.session.isCurrent(intent.scope) &&
                pending.ticket == intent.ticket &&
                pending.scope == intent.scope &&
                pending.lifecycleGeneration == intent.presentationGeneration
            ) {
                context.session.pendingDetail = null
            }
        }
    }

    private suspend fun submitCommandAndProject(
        scope: NotificationAccountScope,
        command: NotificationSyncCommand,
        afterCommit: () -> Unit = {},
    ): Boolean {
        val lease = context.syncCoordinator.beginOperation(scope) ?: return false
        try {
            val submitted = context.syncCoordinator.submit(command)
            val pending = if (submitted is DomainResult.Success) context.syncCoordinator.loadPending(scope) else null
            if (!context.syncCoordinator.isOperationLeaseCurrent(lease, scope)) return false
            return context.lifecycleMutex.withLock {
                if (!context.syncCoordinator.isOperationLeaseCurrent(lease, scope)) return@withLock false
                if (!context.session.isCurrent(scope)) return@withLock false
                if (submitted !is DomainResult.Success || pending !is DomainResult.Success) {
                    publisher.publishMutationFailure(scope, submitted.failureOrNull() ?: pending?.failureOrNull())
                    return@withLock false
                }
                context.session.pending = NotificationPendingProjection(pending.value)
                afterCommit()
                val offline = context.session.pending.hasScheduledRetry
                publisher.publishInbox(
                    scope,
                    offline,
                    false,
                    null,
                    context.stateStore.value.page.operation,
                    false,
                )
                publisher.publishPreferences(scope, false, offline, false, null)
                true
            }
        } finally {
            context.syncCoordinator.endOperation(lease)
        }
    }

    private suspend fun prepareDetail(
        scope: NotificationAccountScope,
        notificationId: String,
        lease: NotificationAccountOperationLease,
    ): NotificationPendingDetail? =
        context.lifecycleMutex.withLock {
            if (!context.syncCoordinator.isOperationLeaseCurrent(lease, scope)) return@withLock null
            val inbox =
                context.session.projectedInbox(
                    context.clockProvider.safeNotificationNow(),
                ) ?: return@withLock null
            val item = inbox.items.firstOrNull { candidate -> candidate.id == notificationId } ?: return@withLock null
            val presented = context.stateStore.value.page.findNotification(notificationId) ?: return@withLock null
            val ticket = context.session.nextDetailTicket()
            if (ticket == null) {
                publisher.publishMutationFailure(scope, null)
                return@withLock null
            }
            NotificationPendingDetail(
                notificationId = notificationId,
                kind = item.kind,
                cityId = presented.target?.cityId,
                target = presented.target,
                ticket = ticket,
                scope = scope,
                lifecycleGeneration = context.currentLifecycleGeneration(),
            )
        }

    private suspend fun commitDetailOpen(
        scope: NotificationAccountScope,
        prepared: NotificationPendingDetail,
        submitted: DomainResult<*>,
        pending: DomainResult<List<PendingNotificationSync>>?,
        lease: NotificationAccountOperationLease,
    ): NotificationEffect? =
        context.lifecycleMutex.withLock {
            if (!context.syncCoordinator.isOperationLeaseCurrent(lease, scope)) return@withLock null
            if (!context.session.isCurrent(scope)) return@withLock null
            val pendingSuccess = pending as? DomainResult.Success
            if (submitted is DomainResult.Success && pendingSuccess != null) {
                context.session.pending = NotificationPendingProjection(pendingSuccess.value)
                publisher.publishInbox(scope, false, false, null, context.stateStore.value.page.operation, false)
            } else {
                publisher.publishMutationFailure(scope, submitted.failureOrNull() ?: pending?.failureOrNull())
            }
            detailEffect(scope, prepared)
        }

    private fun detailEffect(
        scope: NotificationAccountScope,
        prepared: NotificationPendingDetail,
    ): NotificationEffect {
        val target = prepared.target
        if (target == null) {
            context.session.pendingDetail = null
            return NotificationEffect.TargetUnavailable(
                notificationId = prepared.notificationId,
                scope = scope,
                presentationGeneration = prepared.lifecycleGeneration,
            )
        }
        context.session.pendingDetail = prepared
        return NotificationEffect.OpenCatalogDetail(
            notificationId = prepared.notificationId,
            target = target,
            ticket = prepared.ticket,
            scope = scope,
            presentationGeneration = prepared.lifecycleGeneration,
        )
    }

    internal suspend fun tryPublishNavigationEffect(
        effect: NotificationEffect,
        scope: NotificationAccountScope,
        lease: NotificationAccountOperationLease,
    ): Boolean {
        if (!context.syncCoordinator.isOperationLeaseCurrent(lease, scope)) return false
        if (effectQueue.offer(effect)) return true
        context.lifecycleMutex.withLock {
            context.session.pendingDetail = null
            publisher.publishMutationFailure(scope, null)
        }
        return false
    }
}

private fun NotificationPendingDetail.matches(
    intent: NotificationIntent.DetailSheetPresentationConfirmed,
    session: NotificationRuntimeSession,
): Boolean =
    session.isCurrent(intent.scope) &&
        ticket == intent.ticket &&
        target?.listingId == intent.listingId &&
        scope == intent.scope &&
        lifecycleGeneration == intent.presentationGeneration
