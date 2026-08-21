package com.kwabor.shared.presentation.notification

import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.notification.NotificationAccountScope

internal class NotificationRuntimePublisher(
    private val context: NotificationRuntimeContext,
) {
    fun publishInbox(
        scope: NotificationAccountScope,
        isOffline: Boolean,
        localStorageUnavailable: Boolean,
        message: NotificationUiMessage?,
        operation: NotificationPageOperation,
        incrementGeneration: Boolean,
    ) {
        val projected = context.session.projectedInbox(context.clockProvider.safeNotificationNow())
        if (projected == null) {
            publishMissingInbox(scope, isOffline, localStorageUnavailable, message, operation)
            return
        }
        when (val presentation = context.presenter.present(projected.items)) {
            NotificationPresentationResult.InvalidPayload ->
                publishInvalidInbox(scope, projected, isOffline, localStorageUnavailable)
            is NotificationPresentationResult.Content ->
                publishContent(
                    scope,
                    presentation,
                    projected,
                    NotificationPublishContext(
                        isOffline,
                        localStorageUnavailable,
                        message,
                        operation,
                        incrementGeneration,
                    ),
                )
        }
    }

    private fun publishInvalidInbox(
        scope: NotificationAccountScope,
        projected: NotificationRuntimeInbox,
        isOffline: Boolean,
        localStorageUnavailable: Boolean,
    ) {
        context.stateStore.update(scope) { current ->
            current.copy(
                page =
                    NotificationPageUiState(
                        content = NotificationPageContentUiState.Error(frenchNotificationStrings.errors.loadFailed),
                        isOffline = isOffline,
                        isLocalCacheUnavailable = localStorageUnavailable,
                    ),
                badge = projected.status.toBadgeUiState(),
            )
        }
    }

    private fun publishMissingInbox(
        scope: NotificationAccountScope,
        isOffline: Boolean,
        localStorageUnavailable: Boolean,
        message: NotificationUiMessage?,
        operation: NotificationPageOperation,
    ) {
        context.stateStore.update(scope) { current ->
            current.copy(
                page =
                    current.page.copy(
                        operation = operation,
                        isOffline = isOffline,
                        isLocalCacheUnavailable = localStorageUnavailable,
                        message = message,
                    ),
            )
        }
    }

    fun publishPreferences(
        scope: NotificationAccountScope,
        isLoading: Boolean,
        isOffline: Boolean,
        localStorageUnavailable: Boolean,
        message: String?,
    ) {
        val projected = context.session.pending.overlayPreferences(context.session.preferences)
        context.stateStore.update(scope) { current ->
            current.copy(
                preferences =
                    NotificationPreferencesUiState(
                        entries = context.presenter.presentPreferences(projected),
                        isLoading = isLoading,
                        savingFamilies = context.session.pending.savingFamilies,
                        isOffline = isOffline,
                        isLocalCacheUnavailable = localStorageUnavailable,
                        message = message,
                    ),
            )
        }
    }

    fun publishBadge(scope: NotificationAccountScope) {
        val inbox = context.session.projectedInbox(context.clockProvider.safeNotificationNow()) ?: return
        context.stateStore.update(scope) { current -> current.copy(badge = inbox.status.toBadgeUiState()) }
    }

    fun publishPageFailure(
        scope: NotificationAccountScope,
        mode: NotificationPageLoadMode,
        error: DomainError,
        localStorageUnavailable: Boolean,
    ) {
        val current = context.stateStore.value
        val hasContent =
            current.page.content is NotificationPageContentUiState.Content ||
                current.page.content is NotificationPageContentUiState.Empty
        val message =
            when (mode) {
                NotificationPageLoadMode.Initial,
                NotificationPageLoadMode.Retry,
                -> frenchNotificationStrings.errors.loadFailed
                NotificationPageLoadMode.Refresh -> frenchNotificationStrings.errors.refreshFailed
            }
        context.stateStore.update(scope) { state ->
            state.copy(
                page =
                    state.page.copy(
                        content = if (hasContent) state.page.content else NotificationPageContentUiState.Error(message),
                        operation = NotificationPageOperation.Idle,
                        isOffline = error is DomainError.NetworkUnavailable,
                        isLocalCacheUnavailable = localStorageUnavailable,
                        message =
                            message.takeIf { hasContent }?.let { text ->
                                NotificationUiMessage(text, NotificationMessagePlacement.Refresh)
                            },
                    ),
            )
        }
    }

    fun publishAppendFailure(
        scope: NotificationAccountScope,
        error: DomainError,
    ) {
        context.stateStore.update(scope) { current ->
            current.copy(
                page =
                    current.page.copy(
                        operation = NotificationPageOperation.Idle,
                        isOffline = error is DomainError.NetworkUnavailable,
                        message =
                            NotificationUiMessage(
                                frenchNotificationStrings.errors.loadMoreFailed,
                                NotificationMessagePlacement.Append,
                            ),
                    ),
            )
        }
    }

    fun publishMutationFailure(
        scope: NotificationAccountScope,
        error: DomainError?,
    ) {
        val localUnavailable = error is DomainError.LocalStorageUnavailable
        val text =
            if (localUnavailable) {
                frenchNotificationStrings.errors.localCacheUnavailable
            } else {
                frenchNotificationStrings.errors.mutationFailed
            }
        context.stateStore.update(scope) { current ->
            current.copy(
                page =
                    current.page.copy(
                        isOffline = current.page.isOffline || error is DomainError.NetworkUnavailable,
                        isLocalCacheUnavailable = current.page.isLocalCacheUnavailable || localUnavailable,
                        message = NotificationUiMessage(text, NotificationMessagePlacement.Mutation),
                    ),
                preferences =
                    current.preferences.copy(
                        isOffline = current.preferences.isOffline || error is DomainError.NetworkUnavailable,
                        isLocalCacheUnavailable = current.preferences.isLocalCacheUnavailable || localUnavailable,
                        message = text,
                    ),
            )
        }
    }

    private fun publishContent(
        scope: NotificationAccountScope,
        presentation: NotificationPresentationResult.Content,
        projected: NotificationRuntimeInbox,
        publishContext: NotificationPublishContext,
    ) {
        val content =
            if (presentation.sections.isEmpty()) {
                NotificationPageContentUiState.Empty
            } else {
                NotificationPageContentUiState.Content(presentation.sections)
            }
        context.stateStore.update(scope) { current ->
            current.copy(
                page =
                    NotificationPageUiState(
                        content = content,
                        window = NotificationPageWindow(projected.snapshotSequence, projected.nextCursor),
                        operation = publishContext.operation,
                        isOffline = publishContext.isOffline,
                        isLocalCacheUnavailable = publishContext.localStorageUnavailable,
                        message = publishContext.message,
                    ),
                badge = projected.status.toBadgeUiState(),
                presentationGeneration =
                    if (publishContext.incrementGeneration) {
                        current.presentationGeneration.nextRuntimeGeneration()
                    } else {
                        current.presentationGeneration
                    },
            )
        }
    }
}

private data class NotificationPublishContext(
    val isOffline: Boolean,
    val localStorageUnavailable: Boolean,
    val message: NotificationUiMessage?,
    val operation: NotificationPageOperation,
    val incrementGeneration: Boolean,
)
