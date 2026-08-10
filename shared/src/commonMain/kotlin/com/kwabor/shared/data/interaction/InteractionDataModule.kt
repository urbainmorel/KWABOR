package com.kwabor.shared.data.interaction

import com.kwabor.shared.data.local.InteractionOutboxStore
import com.kwabor.shared.data.local.KwaborDatabaseBuilderResult
import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.interaction.AccountScopedFavoriteMutationRepository
import com.kwabor.shared.domain.interaction.AccountScopedListingLikeRepository
import com.kwabor.shared.domain.interaction.ActiveInteractionScopeProvider
import com.kwabor.shared.domain.interaction.InteractionRepository
import org.koin.core.module.Module
import org.koin.dsl.module

internal val interactionDataModule: Module = module {
    single<InteractionOutboxPersistence> {
        if (get<KwaborDatabaseBuilderResult>().supportsDurableInteractionOutbox) {
            RoomInteractionOutboxPersistence(storeFactory = { get<InteractionOutboxStore>() })
        } else {
            UnavailableInteractionOutboxPersistence()
        }
    }
    single<InteractionRetryDelayPolicy> {
        EqualJitterInteractionRetryDelayPolicy(jitterSource = DefaultInteractionJitterSource)
    }
    single<InteractionRepository> {
        DataInteractionRepository(
            outbox = get<InteractionOutboxPersistence>(),
            listingLikeRepository = get<AccountScopedListingLikeRepository>(),
            favoriteMutationRepository = get<AccountScopedFavoriteMutationRepository>(),
            activeScopeProvider = get<ActiveInteractionScopeProvider>(),
            clockProvider = get<ClockProvider>(),
            retryDelayPolicy = get<InteractionRetryDelayPolicy>(),
        )
    }
}
