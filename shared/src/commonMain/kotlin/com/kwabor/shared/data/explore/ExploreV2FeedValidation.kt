package com.kwabor.shared.data.explore

import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingSummary
import com.kwabor.shared.domain.catalog.ListingType

internal fun ListingSummary.hasValidExploreV2NetworkMetadata(): Boolean = when {
    (coverImageUrl == null) != (coverImageAlt == null) -> false
    viewsCount == null -> false
    viewsCount < 0 -> false
    isSponsoredPlacement == null -> false
    isEventEnded == null -> false
    !hasValidExploreEventMetadata() -> false
    else -> hasValidExploreSponsorPlacement()
}

private fun ListingSummary.hasValidExploreEventMetadata(): Boolean = when (type) {
    ListingType.Event ->
        eventStartAtEpochMilliseconds != null &&
            (eventEndAtEpochMilliseconds == null || eventEndAtEpochMilliseconds >= eventStartAtEpochMilliseconds)
    ListingType.Place,
    ListingType.Establishment,
    -> eventStartAtEpochMilliseconds == null && eventEndAtEpochMilliseconds == null && isEventEnded == false
}

private fun ListingSummary.hasValidExploreSponsorPlacement(): Boolean = isSponsoredPlacement != true || (
    type == ListingType.Establishment &&
        listingClass == ListingClass.Commercial &&
        sponsoredUntilEpochMilliseconds != null
    )
