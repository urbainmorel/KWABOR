package com.kwabor.shared.data.notification

import com.kwabor.shared.app.DispatcherProvider
import com.kwabor.shared.domain.notification.NotificationInboxRepository
import com.kwabor.shared.domain.notification.NotificationOfflineRepository
import com.kwabor.shared.domain.notification.NotificationPreferencesRepository
import com.kwabor.shared.domain.notification.NotificationSyncRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose

private val notificationDrainScopeQualifier = named("notification-drain-scope")

internal fun notificationDataModule(hasPersistence: Boolean): Module = module {
    registerNotificationNetworkData()
    if (hasPersistence) registerNotificationPersistenceData()
}

private fun Module.registerNotificationNetworkData() {
    single<NotificationDataSource> {
        SupabaseNotificationDataSource(postgrest = get<SupabaseClient>().postgrest)
    }
    single {
        DataNotificationInboxRepository(
            dataSource = get(),
            activeAccountProvider = get(),
        )
    }
    single {
        DataNotificationPreferencesRepository(
            dataSource = get(),
            activeAccountProvider = get(),
        )
    }
    single<NotificationInboxRepository> { get<DataNotificationInboxRepository>() }
    single<NotificationPreferencesRepository> { get<DataNotificationPreferencesRepository>() }
}

private fun Module.registerNotificationPersistenceData() {
    single<CoroutineScope>(qualifier = notificationDrainScopeQualifier) {
        CoroutineScope(SupervisorJob() + get<DispatcherProvider>().io)
    } onClose { scope -> scope?.cancel() }
    single {
        NotificationDrainSingleFlight(
            workerScope = get(qualifier = notificationDrainScopeQualifier),
        )
    }
    single<NotificationOfflineRepository> {
        DataNotificationOfflineRepository(
            inboxStore = get(),
            preferencesStore = get(),
            activeAccountProvider = get(),
        )
    }
    single {
        DataNotificationSyncRepository(
            outboxStore = get(),
            settlementStore = get(),
            drainSingleFlight = get(),
            dependencies = NotificationSyncDependencies(
                inboxRepository = get(),
                preferencesRepository = get(),
                inboxStore = get(),
                preferencesStore = get(),
                activeAccountProvider = get(),
            ),
            clockProvider = get(),
        )
    }
    single<NotificationSyncRepository> { get<DataNotificationSyncRepository>() }
}
