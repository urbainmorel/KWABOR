package com.kwabor.shared.data.catalog

import io.github.jan.supabase.auth.Auth

internal fun interface CatalogSessionState {
    suspend fun hasCurrentSession(): Boolean
}

internal class SupabaseCatalogSessionState(
    private val auth: Auth,
) : CatalogSessionState {
    override suspend fun hasCurrentSession(): Boolean {
        auth.awaitInitialization()
        return auth.currentSessionOrNull() != null
    }
}

internal object GuestCatalogSessionState : CatalogSessionState {
    override suspend fun hasCurrentSession(): Boolean = false
}

internal class SessionAwareCatalogDataSource(
    private val delegate: CatalogDataSource,
    private val sessionState: CatalogSessionState,
) : CatalogDataSource by delegate {
    override suspend fun listListingViewerInteractions(listingIds: List<String>): List<ListingViewerInteractionDto> =
        if (sessionState.hasCurrentSession()) {
            delegate.listListingViewerInteractions(listingIds)
        } else {
            emptyList()
        }
}
