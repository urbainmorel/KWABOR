package com.kwabor.shared.presentation.auth

import com.kwabor.shared.app.DispatcherProvider
import com.kwabor.shared.domain.auth.AccountPrivateDataPurgeRepository
import com.kwabor.shared.presentation.interaction.InteractionCoordinator
import com.kwabor.shared.presentation.notification.NotificationRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose

private val accountPrivateDataPurgeScopeQualifier = named("account-private-data-purge-scope")

internal val accountPrivateDataPurgePresentationModule: Module = module {
    single<CoroutineScope>(qualifier = accountPrivateDataPurgeScopeQualifier) {
        CoroutineScope(SupervisorJob() + get<DispatcherProvider>().io)
    } onClose { scope -> scope?.cancel() }
    single {
        AccountPrivateDataPurgeCoordinator(
            repository = get<AccountPrivateDataPurgeRepository>(),
            interactionCoordinator = get<InteractionCoordinator>(),
            notificationRuntime = get<NotificationRuntime>(),
            workerScope = get(qualifier = accountPrivateDataPurgeScopeQualifier),
        )
    }
}
