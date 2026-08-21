package com.kwabor.shared.presentation.notification

import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.notification.NotificationAccountScope
import com.kwabor.shared.domain.notification.NotificationInboxPage
import com.kwabor.shared.domain.notification.NotificationInboxRepository
import com.kwabor.shared.domain.notification.NotificationInboxStatus
import com.kwabor.shared.domain.notification.NotificationKind
import com.kwabor.shared.domain.notification.NotificationOfflineRepository
import com.kwabor.shared.domain.notification.NotificationPreferences
import com.kwabor.shared.domain.notification.NotificationPreferencesRepository
import com.kwabor.shared.presentation.session.ViewerSessionScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex

internal class NotificationRuntimeContext(
    val lifecycleMutex: Mutex,
    val stateStore: NotificationStateStore,
    val session: NotificationRuntimeSession,
    val syncCoordinator: NotificationSyncCoordinator,
    val clockProvider: ClockProvider,
    val presenter: NotificationPresenter,
) {
    private val lifecycle =
        kotlinx.coroutines.flow.MutableStateFlow(
            NotificationRuntimeLifecycle(ViewerSessionScope.InitialGuest, generation = 0L),
        )

    fun currentLifecycleGeneration(): Long = lifecycle.value.generation

    fun advanceLifecycleGeneration(): Long {
        while (true) {
            val current = lifecycle.value
            val next = current.copy(generation = current.generation.nextRuntimeGeneration())
            if (lifecycle.compareAndSet(current, next)) return next.generation
        }
    }

    fun admitViewerTransition(
        viewerScope: ViewerSessionScope,
        force: Boolean,
    ): Long? {
        while (true) {
            val current = lifecycle.value
            if (viewerScope.epoch < current.viewerScope.epoch) return null
            if (viewerScope.epoch == current.viewerScope.epoch && viewerScope != current.viewerScope) return null
            if (!force && viewerScope == current.viewerScope) return null
            val next = NotificationRuntimeLifecycle(viewerScope, current.generation.nextRuntimeGeneration())
            if (lifecycle.compareAndSet(current, next)) return next.generation
        }
    }
}

private data class NotificationRuntimeLifecycle(
    val viewerScope: ViewerSessionScope,
    val generation: Long,
)

internal data class NotificationRuntimeRepositories(
    val inbox: NotificationInboxRepository,
    val preferences: NotificationPreferencesRepository,
    val offline: NotificationOfflineRepository?,
)

internal class NotificationRuntimeSession(runtimeScope: CoroutineScope) {
    var viewerScope: ViewerSessionScope = ViewerSessionScope.InitialGuest
    var activeScope: NotificationAccountScope? = null
    var screenVisible: Boolean = false
    var preferencesVisible: Boolean = false
    var inbox: NotificationRuntimeInbox? = null
    var preferences: NotificationPreferences = NotificationPreferences.disabled()
    var pending: NotificationPendingProjection = NotificationPendingProjection(emptyList())
    var presentedSnapshot: NotificationPresentedSnapshot? = null
    var pendingDetail: NotificationPendingDetail? = null
    var pageToken: NotificationPageOperationToken? = null
    var statusToken: NotificationStatusOperationToken? = null
    var preferencesToken: NotificationPreferencesOperationToken? = null
    var pageJob: Job? = null
    var statusJob: Job? = null
    var preferencesJob: Job? = null
    private var viewerJob = SupervisorJob(runtimeScope.coroutineContext[Job])
    private val parentScope = runtimeScope
    private val detailTickets = NotificationDetailTicketSequence()
    var viewerCoroutineScope: CoroutineScope = CoroutineScope(runtimeScope.coroutineContext + viewerJob)
        private set

    fun switchViewer(
        viewerScope: ViewerSessionScope,
        notificationScope: NotificationAccountScope?,
    ) {
        resetViewerJob()
        this.viewerScope = viewerScope
        activeScope = notificationScope
        clearAccountState()
    }

    fun resetForInvalidation() {
        resetViewerJob()
        activeScope = null
        clearAccountState()
    }

    fun isCurrent(scope: NotificationAccountScope): Boolean = activeScope == scope

    fun accepts(
        scope: NotificationAccountScope,
        token: NotificationPageOperationToken,
    ): Boolean = activeScope == scope && pageToken === token

    fun accepts(
        scope: NotificationAccountScope,
        token: NotificationStatusOperationToken,
    ): Boolean = activeScope == scope && statusToken === token

    fun accepts(
        scope: NotificationAccountScope,
        token: NotificationPreferencesOperationToken,
    ): Boolean = activeScope == scope && preferencesToken === token

    fun projectedInbox(nowEpochMilliseconds: Long): NotificationRuntimeInbox? =
        activeScope?.let { scope ->
            inbox
                ?.takeIf { cached -> cached.accountId == scope.accountId }
                ?.overlayPending(scope, pending.operations, nowEpochMilliseconds)
        }

    fun nextDetailTicket(): NotificationDetailTicket? = detailTickets.next()

    private fun resetViewerJob() {
        viewerJob.cancel()
        viewerJob = SupervisorJob(parentScope.coroutineContext[Job])
        viewerCoroutineScope = CoroutineScope(parentScope.coroutineContext + viewerJob)
    }

    private fun clearAccountState() {
        inbox = null
        preferences = NotificationPreferences.disabled()
        pending = NotificationPendingProjection(emptyList())
        presentedSnapshot = null
        pendingDetail = null
        pageToken = null
        statusToken = null
        preferencesToken = null
        pageJob = null
        statusJob = null
        preferencesJob = null
    }
}

internal class NotificationDetailTicketSequence(
    initialValue: Long = 0L,
) {
    private var value = initialValue

    init {
        require(initialValue in 0L..MAXIMUM_NOTIFICATION_DETAIL_TICKET_VALUE)
    }

    fun next(): NotificationDetailTicket? {
        if (value == MAXIMUM_NOTIFICATION_DETAIL_TICKET_VALUE) return null
        value += 1L
        return NotificationDetailTicket(value)
    }
}

internal sealed interface NotificationRuntimeCommand {
    val runtimeGeneration: Long
    val sourceScope: NotificationAccountScope?

    data class Intent(
        val intent: NotificationIntent,
        override val sourceScope: NotificationAccountScope?,
        override val runtimeGeneration: Long,
    ) : NotificationRuntimeCommand

    data class SyncChanged(
        val signal: NotificationSyncSignal,
        override val runtimeGeneration: Long,
    ) : NotificationRuntimeCommand {
        override val sourceScope: NotificationAccountScope = signal.scope
    }

    data class ViewerChanged(
        val scope: ViewerSessionScope,
        override val runtimeGeneration: Long,
    ) : NotificationRuntimeCommand {
        override val sourceScope: NotificationAccountScope? = scope.toNotificationAccountScopeOrNull()
    }
}

internal enum class NotificationPageLoadMode {
    Initial,
    Refresh,
    Retry,
}

internal class NotificationPageOperationToken

internal class NotificationStatusOperationToken

internal class NotificationPreferencesOperationToken

internal data class NotificationPageLoadRequest(
    val scope: NotificationAccountScope,
    val token: NotificationPageOperationToken,
    val mode: NotificationPageLoadMode,
)

internal data class NotificationAppendRequest(
    val scope: NotificationAccountScope,
    val token: NotificationPageOperationToken,
    val snapshotSequence: Long,
    val cursor: String,
    val limit: Int,
) {
    fun asPageRequest(): NotificationPageLoadRequest =
        NotificationPageLoadRequest(scope, token, NotificationPageLoadMode.Refresh)
}

internal data class NotificationLocalProjection(
    val inbox: NotificationRuntimeInbox?,
    val pending: NotificationPendingProjection,
    val localStorageUnavailable: Boolean,
)

internal data class NotificationNetworkPageResult(
    val page: DomainResult<NotificationInboxPage>,
    val status: DomainResult<NotificationInboxStatus>,
)

internal data class NotificationLocalPreferences(
    val preferences: NotificationPreferences,
    val pending: NotificationPendingProjection,
    val localStorageUnavailable: Boolean,
)

internal data class NotificationPendingDetail(
    val notificationId: String,
    val kind: NotificationKind,
    val cityId: String?,
    val target: NotificationTargetUiModel?,
    val ticket: NotificationDetailTicket,
    val scope: NotificationAccountScope,
    val lifecycleGeneration: Long,
)

internal data class NotificationPresentedSnapshot(
    val snapshotSequence: Long,
    val presentationGeneration: Long,
)

internal data class NotificationVisibility(
    val notificationCenter: Boolean,
    val preferences: Boolean,
)

internal data class NotificationForegroundWork(
    val scope: NotificationAccountScope,
    val notificationCenter: Boolean,
    val preferences: Boolean,
)

internal fun NotificationPageUiState.findNotification(notificationId: String): NotificationItemUiModel? =
    (content as? NotificationPageContentUiState.Content)
        ?.sections
        ?.asSequence()
        ?.flatMap { section -> section.items.asSequence() }
        ?.firstOrNull { item -> item.id == notificationId }

internal fun DomainResult<*>.failureOrNull(): DomainError? = (this as? DomainResult.Failure)?.error

internal fun DomainResult<*>?.isLocalStorageFailure(): Boolean =
    this == null || (this as? DomainResult.Failure)?.error is DomainError.LocalStorageUnavailable

internal fun Boolean.localStorageMessageOrNull(): NotificationUiMessage? =
    takeIf { unavailable -> unavailable }?.let {
        NotificationUiMessage(
            frenchNotificationStrings.errors.localCacheUnavailable,
            NotificationMessagePlacement.Mutation,
        )
    }

internal fun ClockProvider.safeNotificationNow(): Long = nowEpochMilliseconds().coerceAtLeast(0L)

internal fun Long.nextRuntimeGeneration(): Long = if (this == Long.MAX_VALUE) Long.MAX_VALUE else this + 1L
