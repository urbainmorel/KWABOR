package com.kwabor.shared.presentation.notification

import com.kwabor.shared.app.DispatcherProvider
import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.notification.NotificationInboxRepository
import com.kwabor.shared.domain.notification.NotificationOfflineRepository
import com.kwabor.shared.domain.notification.NotificationPreferencesRepository
import com.kwabor.shared.domain.notification.NotificationSyncRepository
import com.kwabor.shared.presentation.session.ViewerSessionScopeTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose

private val notificationRuntimeScopeQualifier = named("notification-runtime-scope")

internal fun notificationPresentationModule(hasPersistence: Boolean): Module = module {
    single<CoroutineScope>(qualifier = notificationRuntimeScopeQualifier) {
        CoroutineScope(SupervisorJob() + get<DispatcherProvider>().io)
    } onClose { scope -> scope?.cancel() }
    single { NotificationPresenter(clockProvider = get()) }
    single {
        NotificationSyncCoordinator(
            repository = if (hasPersistence) get<NotificationSyncRepository>() else null,
            viewerSessionScopeTracker = get<ViewerSessionScopeTracker>(),
            clockProvider = get<ClockProvider>(),
            coroutineScope = get(qualifier = notificationRuntimeScopeQualifier),
        )
    } onClose { coordinator -> coordinator?.close() }
    single {
        NotificationRuntime(
            repositories = NotificationRuntimeRepositories(
                inbox = get<NotificationInboxRepository>(),
                preferences = get<NotificationPreferencesRepository>(),
                offline = if (hasPersistence) get<NotificationOfflineRepository>() else null,
            ),
            presenter = get<NotificationPresenter>(),
            clockProvider = get<ClockProvider>(),
            viewerSessionScopeTracker = get<ViewerSessionScopeTracker>(),
            syncCoordinator = get<NotificationSyncCoordinator>(),
            coroutineScope = get(qualifier = notificationRuntimeScopeQualifier),
        )
    } onClose { runtime -> runtime?.close() }
}
