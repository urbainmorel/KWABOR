package com.kwabor.shared.presentation.notification

import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.notification.NotificationAccountScope
import com.kwabor.shared.domain.notification.NotificationDrainOutcome
import com.kwabor.shared.domain.notification.NotificationSubmitOutcome
import com.kwabor.shared.domain.notification.NotificationSyncCommand
import com.kwabor.shared.domain.notification.NotificationSyncRepository
import com.kwabor.shared.domain.notification.PendingNotificationSync
import com.kwabor.shared.presentation.session.ViewerSessionScope
import com.kwabor.shared.presentation.session.ViewerSessionScopeTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class NotificationSyncCoordinator(
    private val repository: NotificationSyncRepository?,
    private val viewerSessionScopeTracker: ViewerSessionScopeTracker,
    clockProvider: ClockProvider,
    coroutineScope: CoroutineScope,
) {
    private val coordinatorJob = SupervisorJob(coroutineScope.coroutineContext[Job])
    private val coordinatorScope = CoroutineScope(coroutineScope.coroutineContext + coordinatorJob)
    private val lifecycleGate = NotificationLifecycleGate()
    private val wakeAccumulator = NotificationWakeAccumulator()
    private val wakeScheduler =
        NotificationWakeScheduler(
            coordinatorScope,
            clockProvider,
            ::currentNotificationScope,
            wakeAccumulator,
        )
    private val mutableSignal = MutableStateFlow<NotificationSyncSignal?>(null)
    internal val signals: StateFlow<NotificationSyncSignal?> = mutableSignal.asStateFlow()
    private var signalRevision: Long = 0L
    private val wakeProcessor =
        NotificationSyncWakeProcessor(
            NotificationSyncWakeContext(
                repository = repository,
                acquire = ::beginOperation,
                release = ::endOperation,
                isCurrent = ::isOperationLeaseCurrent,
                currentScope = ::currentNotificationScope,
                scheduler = wakeScheduler,
                publish = ::publishSignal,
            ),
        )

    init {
        coordinatorScope.launch {
            for (ignored in wakeAccumulator.signal) {
                var request = wakeAccumulator.take()
                while (request != null) {
                    wakeProcessor.process(request)
                    request = wakeAccumulator.take()
                }
            }
        }
    }

    suspend fun submit(command: NotificationSyncCommand): DomainResult<NotificationSubmitOutcome> {
        val availableRepository =
            repository
                ?: return DomainResult.Failure(DomainError.LocalStorageUnavailable())
        val lease =
            beginOperation(command.scope)
                ?: return DomainResult.Failure(DomainError.AuthenticationRequired())
        return try {
            val repositoryResult = availableRepository.submit(command)
            if (!isOperationLeaseCurrent(lease, command.scope)) {
                return DomainResult.Failure(DomainError.AuthenticationRequired())
            }
            if (repositoryResult is DomainResult.Success) {
                wakeAccumulator.offer(
                    request = NotificationWakeRequest.immediate(command.scope),
                    currentScope = currentNotificationScope(),
                )
            }
            repositoryResult
        } finally {
            endOperation(lease)
        }
    }

    internal suspend fun loadPending(
        expectedScope: NotificationAccountScope,
    ): DomainResult<List<PendingNotificationSync>> {
        val availableRepository =
            repository
                ?: return DomainResult.Failure(DomainError.LocalStorageUnavailable())
        val lease =
            beginOperation(expectedScope)
                ?: return DomainResult.Failure(DomainError.AuthenticationRequired())
        return try {
            val result = availableRepository.loadPending(expectedScope)
            if (isOperationLeaseCurrent(lease, expectedScope)) {
                result
            } else {
                DomainResult.Failure(DomainError.AuthenticationRequired())
            }
        } finally {
            endOperation(lease)
        }
    }

    fun onViewerContextChanged(scope: ViewerSessionScope) {
        val notificationScope = scope.toNotificationAccountScopeOrNull()
        wakeScheduler.cancel { wake -> wake.scope != notificationScope }
        wakeAccumulator.clear()
        if (notificationScope == null) {
            mutableSignal.value = null
        } else {
            wakeAccumulator.offer(
                request = NotificationWakeRequest.automatic(notificationScope),
                currentScope = currentNotificationScope(),
            )
        }
    }

    internal fun wake(
        retryMode: NotificationWakeRetryMode,
        expectedScope: NotificationAccountScope? = currentNotificationScope(),
    ) {
        val scope = expectedScope ?: return
        wakeAccumulator.offer(
            request = NotificationWakeRequest(scope, retryMode),
            currentScope = currentNotificationScope(),
        )
    }

    fun close() {
        wakeScheduler.cancel()
        wakeAccumulator.clear()
        wakeAccumulator.signal.close()
        coordinatorJob.cancel()
    }

    /**
     * Clears account-owned in-memory work after the composite Room transaction has committed.
     *
     * The deletion block must already be registered and its captured [NotificationDeletionBlockRegistration.Owner.idle]
     * signal completed before this method is called. The block generation is the operation fence; the runtime
     * invalidation advances the presentation generation separately.
     */
    internal fun invalidateAfterCompositePurge(accountId: String) {
        val canonicalAccountId = accountId.toCanonicalNotificationAccountId()
        wakeScheduler.cancel { wake -> wake.scope.accountId == canonicalAccountId }
        wakeAccumulator.clearAccount(canonicalAccountId)
        if (mutableSignal.value?.scope?.accountId == canonicalAccountId) mutableSignal.value = null
    }

    internal suspend fun beginOperation(expectedScope: NotificationAccountScope): NotificationAccountOperationLease? =
        lifecycleGate.beginOperation(
            expectedScope = expectedScope,
            currentScope = currentNotificationScope(),
        )

    internal suspend fun endOperation(lease: NotificationAccountOperationLease) {
        lifecycleGate.endOperation(lease)
    }

    internal suspend fun registerAccountDeletionBlock(accountId: String): NotificationDeletionBlockRegistration =
        lifecycleGate.registerDeletionBlock(accountId.toCanonicalNotificationAccountId())

    internal suspend fun finishAccountDeletionBlock(
        token: NotificationDeletionBlockToken,
        committed: Boolean,
    ): Boolean = lifecycleGate.finishDeletionBlock(token, committed)

    internal suspend fun resumeAfterAccountDeletionFailure(accountId: String): Boolean =
        lifecycleGate.resume(accountId.toCanonicalNotificationAccountId())

    internal suspend fun isOperationLeaseCurrent(
        lease: NotificationAccountOperationLease,
        expectedScope: NotificationAccountScope,
    ): Boolean =
        lifecycleGate.isLeaseCurrent(
            lease = lease,
            expectedScope = expectedScope,
            currentScope = currentNotificationScope(),
        )

    private fun currentNotificationScope(): NotificationAccountScope? =
        viewerSessionScopeTracker.currentScope.toNotificationAccountScopeOrNull()

    private fun publishSignal(draft: NotificationSyncSignalDraft) {
        signalRevision =
            when (signalRevision) {
                Long.MAX_VALUE -> Long.MAX_VALUE
                else -> signalRevision + 1L
            }
        mutableSignal.value =
            when (draft) {
                is NotificationSyncSignalDraft.Failed ->
                    NotificationSyncSignal.Failed(
                        draft.scope,
                        signalRevision,
                        draft.error,
                    )
                is NotificationSyncSignalDraft.Reconcile ->
                    NotificationSyncSignal.Reconcile(
                        draft.scope,
                        signalRevision,
                        draft.outcome,
                    )
            }
    }
}

internal sealed interface NotificationSyncSignal {
    val scope: NotificationAccountScope
    val revision: Long

    data class Reconcile(
        override val scope: NotificationAccountScope,
        override val revision: Long,
        val outcome: NotificationDrainOutcome,
    ) : NotificationSyncSignal

    data class Failed(
        override val scope: NotificationAccountScope,
        override val revision: Long,
        val error: DomainError,
    ) : NotificationSyncSignal
}

internal fun ViewerSessionScope.toNotificationAccountScopeOrNull(): NotificationAccountScope? =
    accountId?.let { accountId ->
        NotificationAccountScope(accountId = accountId.toCanonicalNotificationAccountId(), epoch = epoch)
    }

internal fun String.toCanonicalNotificationAccountId(): String = trim().lowercase()
