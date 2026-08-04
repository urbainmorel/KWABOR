package com.kwabor.shared.presentation.explore

import com.kwabor.shared.domain.observability.AnalyticsContext
import com.kwabor.shared.domain.observability.AnalyticsEntityType
import com.kwabor.shared.domain.observability.AnalyticsEvent
import com.kwabor.shared.domain.observability.AnalyticsEventName
import com.kwabor.shared.domain.observability.AnalyticsSessionSource
import com.kwabor.shared.domain.observability.isAnalyticsSafeIdentifierOrNull

internal fun ExploreUiState.protectedActionReplayedAnalyticsEvent(listingId: String): AnalyticsEvent? {
    val listing = listings.firstOrNull { item -> item.id == listingId } ?: return null
    if (!listing.id.isAnalyticsSafeIdentifierOrNull() || !listing.cityId.isAnalyticsSafeIdentifierOrNull()) {
        return null
    }
    return AnalyticsEvent(
        name = AnalyticsEventName.ProtectedActionReplayed,
        context = AnalyticsContext(
            cityId = listing.cityId,
            entityType = selectedTab.toAnalyticsEntityType(),
            entityId = listing.id,
            sessionSource = if (listing.sponsored) {
                AnalyticsSessionSource.Sponsored
            } else {
                AnalyticsSessionSource.Organic
            },
            displayCurrency = currency,
        ),
    )
}

private fun ExploreTab.toAnalyticsEntityType(): AnalyticsEntityType = when (this) {
    ExploreTab.Places -> AnalyticsEntityType.Place
    ExploreTab.Events -> AnalyticsEntityType.Event
    ExploreTab.HotelsRestaurants -> AnalyticsEntityType.Establishment
}
