package com.kwabor.shared.presentation.notification

import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.notification.NotificationAccountScope
import com.kwabor.shared.domain.notification.NotificationCachedInbox
import com.kwabor.shared.domain.notification.NotificationInboxPage
import com.kwabor.shared.domain.notification.NotificationInboxRepository
import com.kwabor.shared.domain.notification.NotificationOfflineRepository
import com.kwabor.shared.domain.notification.NotificationPageRequest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

internal class NotificationRuntimePageController(
    private val context: NotificationRuntimeContext,
    private val inboxRepository: NotificationInboxRepository,
    private val offlineRepository: NotificationOfflineRepository?,
    private val persistence: NotificationRuntimePersistence,
    private val publisher: NotificationRuntimePublisher,
) {
    suspend fun startPageLoad(
        scope: NotificationAccountScope,
        mode: NotificationPageLoadMode,
    ) {
        val job =
            context.lifecycleMutex.withLock {
                if (!context.session.isCurrent(scope) || !context.session.screenVisible) return@withLock null
                context.session.pageJob?.cancel()
                val token = NotificationPageOperationToken()
                context.session.pageToken = token
                context.stateStore.update(scope) { current ->
                    val hasContent = current.page.content is NotificationPageContentUiState.Content
                    current.copy(
                        page =
                            current.page.copy(
                                content =
                                    if (hasContent) {
                                        current.page.content
                                    } else {
                                        NotificationPageContentUiState.Skeleton
                                    },
                                operation =
                                    if (hasContent) {
                                        NotificationPageOperation.Refreshing
                                    } else {
                                        NotificationPageOperation.Idle
                                    },
                                message = null,
                            ),
                    )
                }
                context.session.viewerCoroutineScope.launch(start = CoroutineStart.LAZY) {
                    performPageLoad(NotificationPageLoadRequest(scope, token, mode))
                }.also { created -> context.session.pageJob = created }
            }
        job?.start()
    }

    suspend fun startAppend(scope: NotificationAccountScope) {
        val job =
            context.lifecycleMutex.withLock {
                val request = prepareAppend(scope) ?: return@withLock null
                context.session.viewerCoroutineScope.launch(start = CoroutineStart.LAZY) {
                    performAppend(request)
                }.also { created -> context.session.pageJob = created }
            }
        job?.start()
    }

    suspend fun loadLocalProjection(scope: NotificationAccountScope): NotificationLocalProjection {
        val availableOffline =
            offlineRepository
                ?: return NotificationLocalProjection(null, NotificationPendingProjection(emptyList()), true)
        return coroutineScope {
            val inbox = async { availableOffline.readInbox(scope) }
            val pending = async { context.syncCoordinator.loadPending(scope) }
            val inboxResult = inbox.await()
            val pendingResult = pending.await()
            val cachedInbox = (inboxResult as? DomainResult.Success)?.value
            val cacheTargetsAnotherAccount = cachedInbox != null && cachedInbox.accountId != scope.accountId
            NotificationLocalProjection(
                inbox =
                    cachedInbox
                        ?.takeIf { cached -> cached.accountId == scope.accountId }
                        ?.toRuntimeInbox(),
                pending =
                    NotificationPendingProjection(
                        (pendingResult as? DomainResult.Success)
                            ?.value
                            .orEmpty()
                            .filter { operation -> operation.command.scope == scope },
                    ),
                localStorageUnavailable =
                    inboxResult.isLocalStorageFailure() ||
                        pendingResult.isLocalStorageFailure() ||
                        cacheTargetsAnotherAccount,
            )
        }
    }

    private suspend fun performPageLoad(request: NotificationPageLoadRequest) {
        val lease = context.syncCoordinator.beginOperation(request.scope) ?: return clearPageJob(request)
        try {
            val local = loadLocalProjection(request.scope)
            commitLocalPage(request, local, lease)
            if (!context.syncCoordinator.isOperationLeaseCurrent(lease, request.scope)) return
            commitNetworkPage(request, loadNetworkFirstPage(request.scope), local.localStorageUnavailable, lease)
        } finally {
            context.syncCoordinator.endOperation(lease)
            clearPageJob(request)
        }
    }

    private suspend fun loadNetworkFirstPage(scope: NotificationAccountScope): NotificationNetworkPageResult =
        coroutineScope {
            val page =
                async {
                    inboxRepository.listInbox(
                        expectedScope = scope,
                        page = NotificationPageRequest(limit = NotificationPageRequest.DEFAULT_LIMIT),
                    )
                }
            val status = async { inboxRepository.getStatus(scope) }
            NotificationNetworkPageResult(page.await(), status.await())
        }

    private suspend fun commitLocalPage(
        request: NotificationPageLoadRequest,
        local: NotificationLocalProjection,
        lease: NotificationAccountOperationLease,
    ) {
        context.lifecycleMutex.withLock {
            if (!context.syncCoordinator.isOperationLeaseCurrent(lease, request.scope)) return@withLock
            if (!context.session.accepts(request.scope, request.token)) return@withLock
            context.session.pending = local.pending
            local.inbox?.let { inbox -> context.session.inbox = inbox }
            publisher.publishInbox(
                scope = request.scope,
                isOffline = false,
                localStorageUnavailable = local.localStorageUnavailable,
                message = null,
                operation =
                    if (local.inbox == null) {
                        NotificationPageOperation.Idle
                    } else {
                        NotificationPageOperation.Refreshing
                    },
                incrementGeneration = local.inbox != null,
            )
        }
    }

    private suspend fun commitNetworkPage(
        request: NotificationPageLoadRequest,
        network: NotificationNetworkPageResult,
        localStorageUnavailable: Boolean,
        lease: NotificationAccountOperationLease,
    ) {
        val page = (network.page as? DomainResult.Success)?.value
        val status = (network.status as? DomainResult.Success)?.value
        if (page == null || status == null) {
            val error = network.page.failureOrNull() ?: network.status.failureOrNull() ?: DomainError.Unexpected()
            publishPageFailure(request, lease, error, localStorageUnavailable)
            return
        }
        val networkInbox = runCatching { page.toRuntimeInbox(request.scope, status) }.getOrNull()
        if (networkInbox == null) {
            publishPageFailure(
                request,
                lease,
                DomainError.Validation("error.notifications.invalid_page"),
                localStorageUnavailable,
            )
            return
        }
        val persisted = persistence.replaceInbox(request.scope, page, status)
        val confirmed = (persisted as? DomainResult.Success)?.value?.toRuntimeInbox() ?: networkInbox
        commitReplacement(request, lease, confirmed, localStorageUnavailable || persisted.isLocalStorageFailure())
    }

    private suspend fun publishPageFailure(
        request: NotificationPageLoadRequest,
        lease: NotificationAccountOperationLease,
        error: DomainError,
        localStorageUnavailable: Boolean,
    ) {
        context.lifecycleMutex.withLock {
            if (!context.syncCoordinator.isOperationLeaseCurrent(lease, request.scope)) return@withLock
            if (!context.session.accepts(request.scope, request.token)) return@withLock
            publisher.publishPageFailure(request.scope, request.mode, error, localStorageUnavailable)
        }
    }

    private suspend fun commitReplacement(
        request: NotificationPageLoadRequest,
        lease: NotificationAccountOperationLease,
        inbox: NotificationRuntimeInbox,
        localStorageUnavailable: Boolean,
    ) {
        context.lifecycleMutex.withLock {
            if (!context.syncCoordinator.isOperationLeaseCurrent(lease, request.scope)) return@withLock
            if (!context.session.accepts(request.scope, request.token)) return@withLock
            context.session.inbox = inbox
            publisher.publishInbox(
                request.scope,
                false,
                localStorageUnavailable,
                localStorageUnavailable.localStorageMessageOrNull(),
                NotificationPageOperation.Idle,
                true,
            )
        }
    }

    private fun prepareAppend(scope: NotificationAccountScope): NotificationAppendRequest? {
        if (!context.session.isCurrent(scope) || !context.session.screenVisible) return null
        val current = context.session.inbox ?: return null
        val cursor = current.nextCursor ?: return null
        val remaining = NotificationCachedInbox.MAX_ITEMS - current.items.size
        if (remaining <= 0) {
            context.session.inbox = current.copy(nextCursor = null)
            publisher.publishInbox(scope, false, false, null, NotificationPageOperation.Idle, false)
            return null
        }
        val state = context.stateStore.value
        if (state.page.operation != NotificationPageOperation.Idle || state.page.isOffline) return null
        val token = NotificationPageOperationToken()
        context.session.pageToken = token
        context.stateStore.update(scope) { ui ->
            ui.copy(page = ui.page.copy(operation = NotificationPageOperation.Appending, message = null))
        }
        return NotificationAppendRequest(
            scope,
            token,
            current.snapshotSequence,
            cursor,
            minOf(NotificationPageRequest.DEFAULT_LIMIT, remaining),
        )
    }

    private suspend fun performAppend(request: NotificationAppendRequest) {
        val lease =
            context.syncCoordinator.beginOperation(request.scope)
                ?: return clearPageJob(request.asPageRequest())
        try {
            val pageResult =
                inboxRepository.listInbox(
                    expectedScope = request.scope,
                    page = NotificationPageRequest(cursor = request.cursor, limit = request.limit),
                )
            if (!context.syncCoordinator.isOperationLeaseCurrent(lease, request.scope)) return
            val page = (pageResult as? DomainResult.Success)?.value
            if (page == null) {
                publishAppendFailure(request, lease, pageResult.failureOrNull() ?: DomainError.Unexpected())
                return
            }
            val base = loadAppendBase(request, lease) ?: return
            val appended = runCatching { base.appendPage(page) }.getOrNull()
            if (appended == null) {
                publishAppendFailure(request, lease, DomainError.Validation("error.notifications.invalid_append"))
                return
            }
            commitAppend(request, lease, page, appended)
        } finally {
            context.syncCoordinator.endOperation(lease)
            clearPageJob(request.asPageRequest())
        }
    }

    private suspend fun loadAppendBase(
        request: NotificationAppendRequest,
        lease: NotificationAccountOperationLease,
    ): NotificationRuntimeInbox? =
        context.lifecycleMutex.withLock {
            if (!context.syncCoordinator.isOperationLeaseCurrent(lease, request.scope)) return@withLock null
            context.session.inbox?.takeIf { inbox ->
                context.session.accepts(request.scope, request.token) &&
                    inbox.snapshotSequence == request.snapshotSequence &&
                    inbox.nextCursor == request.cursor
            }
        }

    private suspend fun publishAppendFailure(
        request: NotificationAppendRequest,
        lease: NotificationAccountOperationLease,
        error: DomainError,
    ) {
        context.lifecycleMutex.withLock {
            if (!context.syncCoordinator.isOperationLeaseCurrent(lease, request.scope)) return@withLock
            if (context.session.accepts(request.scope, request.token)) {
                publisher.publishAppendFailure(request.scope, error)
            }
        }
    }

    private suspend fun commitAppend(
        request: NotificationAppendRequest,
        lease: NotificationAccountOperationLease,
        page: NotificationInboxPage,
        appended: NotificationRuntimeInbox,
    ) {
        val persisted = persistence.appendInbox(request, page)
        val confirmed = (persisted as? DomainResult.Success)?.value?.toRuntimeInbox() ?: appended
        context.lifecycleMutex.withLock {
            if (!context.syncCoordinator.isOperationLeaseCurrent(lease, request.scope)) return@withLock
            if (!context.session.accepts(request.scope, request.token)) return@withLock
            context.session.inbox = confirmed
            val storageUnavailable = persisted.isLocalStorageFailure()
            publisher.publishInbox(
                request.scope,
                false,
                storageUnavailable,
                storageUnavailable.localStorageMessageOrNull(),
                NotificationPageOperation.Idle,
                true,
            )
        }
    }

    private suspend fun clearPageJob(request: NotificationPageLoadRequest) {
        context.lifecycleMutex.withLock {
            if (context.session.accepts(request.scope, request.token)) context.session.pageJob = null
        }
    }
}
