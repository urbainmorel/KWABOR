package com.kwabor.shared.presentation.interaction

import com.kwabor.shared.app.DispatcherProvider
import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.interaction.ActiveInteractionScopeProvider
import com.kwabor.shared.domain.interaction.InteractionRepository
import com.kwabor.shared.presentation.session.ViewerSessionScopeTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose

private val interactionCoordinatorScopeQualifier = named("interaction-coordinator-scope")

internal val interactionPresentationModule: Module = module {
    single<CoroutineScope>(qualifier = interactionCoordinatorScopeQualifier) {
        CoroutineScope(SupervisorJob() + get<DispatcherProvider>().io)
    } onClose { scope -> scope?.cancel() }
    single<ActiveInteractionScopeProvider> {
        ViewerSessionActiveInteractionScopeProvider(
            viewerSessionScopeTracker = get<ViewerSessionScopeTracker>(),
        )
    }
    single {
        InteractionCoordinator(
            repository = get<InteractionRepository>(),
            viewerSessionScopeTracker = get<ViewerSessionScopeTracker>(),
            clockProvider = get<ClockProvider>(),
            coroutineScope = get(qualifier = interactionCoordinatorScopeQualifier),
        )
    }
}
