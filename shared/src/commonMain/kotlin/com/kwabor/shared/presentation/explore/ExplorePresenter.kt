package com.kwabor.shared.presentation.explore

import com.kwabor.shared.domain.catalog.CatalogRepository
import com.kwabor.shared.domain.catalog.Category
import com.kwabor.shared.domain.catalog.City
import com.kwabor.shared.domain.catalog.ListingFilters
import com.kwabor.shared.domain.catalog.ListingSummary
import com.kwabor.shared.domain.catalog.ListingViewerInteraction
import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.core.PageRequest
import com.kwabor.shared.i18n.KwaborStrings
import kotlin.math.max
import kotlin.math.roundToInt

private const val EXPLORE_PAGE_SIZE = 20
private const val RATING_DECIMAL_SCALE = 10
private const val RATING_DECIMAL_DIVISOR = 10.0

private val CATEGORY_IDS_BY_CHIP = mapOf(
    "history" to listOf("heritage-historique"),
    "nature" to listOf("heritage-nature"),
    "markets" to listOf("commercial-marche"),
    "hotels" to listOf("commercial-hotel"),
    "restaurants" to listOf("commercial-restaurant"),
)

class ExplorePresenter(
    private val catalogRepository: CatalogRepository,
    private val clockProvider: ClockProvider,
) {
    suspend fun load(request: ExploreLoadRequest, strings: KwaborStrings): ExploreUiState {
        val cities = when (val result = catalogRepository.listCities()) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return request.errorState(strings, result.error)
        }
        val categories = when (val result = catalogRepository.listCategories()) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return request.errorState(strings, result.error)
        }
        val listings = when (
            val result = catalogRepository.listListings(
                filters = request.toFilters(categories),
                page = PageRequest(limit = EXPLORE_PAGE_SIZE),
            )
        ) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return request.errorState(strings, result.error)
        }

        val nowEpochMilliseconds = clockProvider.nowEpochMilliseconds()
        val cityNamesById = cities.associate { city -> city.id to city.name }
        val viewerInteractions = loadViewerInteractions(listings.items.map { listing -> listing.id })

        return initialExploreUiState(strings = strings, request = request).copy(
            cityLabel = cities.homeCityLabel(strings),
            isOffline = viewerInteractions.isOffline,
            interactionMessage = viewerInteractions.message,
            listings = listings.items.map { listing ->
                listing.toExploreListingItem(
                    cityNamesById = cityNamesById,
                    nowEpochMilliseconds = nowEpochMilliseconds,
                    interaction = viewerInteractions.byListingId[listing.id],
                )
            },
        )
    }

    suspend fun toggleLike(state: ExploreUiState, listingId: String, strings: KwaborStrings): ExploreUiState =
        toggleInteraction(
            state = state,
            listingId = listingId,
            strings = strings,
            kind = ExploreInteractionKind.Like,
        )

    suspend fun toggleFavorite(state: ExploreUiState, listingId: String, strings: KwaborStrings): ExploreUiState =
        toggleInteraction(
            state = state,
            listingId = listingId,
            strings = strings,
            kind = ExploreInteractionKind.Favorite,
        )

    private suspend fun toggleInteraction(
        state: ExploreUiState,
        listingId: String,
        strings: KwaborStrings,
        kind: ExploreInteractionKind,
    ): ExploreUiState {
        val listing = state.listings.firstOrNull { item -> item.id == listingId } ?: return state
        val selected = when (kind) {
            ExploreInteractionKind.Like -> !listing.liked
            ExploreInteractionKind.Favorite -> !listing.favorited
        }

        return when (val result = runInteraction(kind = kind, listingId = listingId, selected = selected)) {
            is DomainResult.Success -> state.applyInteraction(kind = kind, interaction = result.value)
            is DomainResult.Failure -> state.handleInteractionFailure(
                strings = strings,
                failure = ExploreInteractionFailure(
                    listingId = listingId,
                    kind = kind,
                    selected = selected,
                    error = result.error,
                    queuedAtEpochMilliseconds = clockProvider.nowEpochMilliseconds(),
                ),
            )
        }
    }

    private suspend fun runInteraction(
        kind: ExploreInteractionKind,
        listingId: String,
        selected: Boolean,
    ): DomainResult<ListingViewerInteraction> = when (kind) {
        ExploreInteractionKind.Like -> if (selected) {
            catalogRepository.likeListing(listingId)
        } else {
            catalogRepository.unlikeListing(listingId)
        }
        ExploreInteractionKind.Favorite -> if (selected) {
            catalogRepository.favoriteListing(listingId)
        } else {
            catalogRepository.unfavoriteListing(listingId)
        }
    }

    private suspend fun loadViewerInteractions(listingIds: List<String>): ViewerInteractionsState {
        if (listingIds.isEmpty()) {
            return ViewerInteractionsState()
        }

        return when (val result = catalogRepository.listListingViewerInteractions(listingIds)) {
            is DomainResult.Success -> ViewerInteractionsState(
                byListingId = result.value.associateBy { interaction -> interaction.listingId },
            )
            is DomainResult.Failure -> when (result.error) {
                is DomainError.AuthenticationRequired,
                is DomainError.PermissionDenied,
                -> ViewerInteractionsState()
                is DomainError.NetworkUnavailable -> ViewerInteractionsState(isOffline = true)
                is DomainError.NotFound,
                is DomainError.Unexpected,
                is DomainError.Validation,
                -> ViewerInteractionsState()
            }
        }
    }
}

private fun ExploreUiState.applyInteraction(
    kind: ExploreInteractionKind,
    interaction: ListingViewerInteraction,
): ExploreUiState = copy(
    isOffline = false,
    interactionMessage = null,
    pendingAuthInteraction = null,
    listings = listings.map { listing ->
        if (listing.id == interaction.listingId) {
            listing.copy(
                liked = interaction.likedByViewer,
                favorited = interaction.favoritedByViewer,
                likesCount = interaction.likesCount,
            )
        } else {
            listing
        }
    },
    queuedInteractions = queuedInteractions.filterNot { queued ->
        queued.listingId == interaction.listingId && queued.kind == kind
    },
)

private fun ExploreUiState.handleInteractionFailure(
    strings: KwaborStrings,
    failure: ExploreInteractionFailure,
): ExploreUiState = when (failure.error) {
    is DomainError.AuthenticationRequired,
    is DomainError.PermissionDenied,
    -> copy(
        interactionMessage = strings.signInRequiredForInteraction,
        pendingAuthInteraction = PendingExploreAuthInteraction(
            listingId = failure.listingId,
            kind = failure.kind,
        ),
    )
    is DomainError.NetworkUnavailable -> queueOfflineInteraction(
        listingId = failure.listingId,
        kind = failure.kind,
        selected = failure.selected,
        message = strings.interactionQueuedOffline,
        queuedAtEpochMilliseconds = failure.queuedAtEpochMilliseconds,
    )
    is DomainError.NotFound,
    is DomainError.Unexpected,
    is DomainError.Validation,
    -> copy(interactionMessage = strings.interactionFailed, pendingAuthInteraction = null)
}

private fun ExploreUiState.queueOfflineInteraction(
    listingId: String,
    kind: ExploreInteractionKind,
    selected: Boolean,
    message: String,
    queuedAtEpochMilliseconds: Long,
): ExploreUiState = copy(
    isOffline = true,
    interactionMessage = message,
    pendingAuthInteraction = null,
    listings = listings.map { listing ->
        if (listing.id == listingId) listing.applyOptimisticInteraction(kind, selected) else listing
    },
    queuedInteractions = queuedInteractions.upsert(
        QueuedExploreInteraction(listingId, kind, selected, queuedAtEpochMilliseconds),
    ),
)

private fun ExploreListingItem.applyOptimisticInteraction(
    kind: ExploreInteractionKind,
    selected: Boolean,
): ExploreListingItem = when (kind) {
    ExploreInteractionKind.Like -> copy(
        liked = selected,
        likesCount = if (selected) likesCount + 1 else max(likesCount - 1, 0),
    )
    ExploreInteractionKind.Favorite -> copy(favorited = selected)
}

private fun List<QueuedExploreInteraction>.upsert(
    interaction: QueuedExploreInteraction,
): List<QueuedExploreInteraction> = filterNot { queued ->
    queued.listingId == interaction.listingId && queued.kind == interaction.kind
} + interaction

private fun ExploreLoadRequest.toFilters(categories: List<Category>): ListingFilters = ListingFilters(
    categoryId = selectedChipId?.let { chipId -> chipId.toKnownCategoryId(categories) },
    listingType = selectedTab.toListingType(),
    onlyPublished = true,
)

private fun ListingSummary.toExploreListingItem(
    cityNamesById: Map<String, String>,
    nowEpochMilliseconds: Long,
    interaction: ListingViewerInteraction?,
): ExploreListingItem = ExploreListingItem(
    id = id,
    title = name,
    cityLabel = cityNamesById[cityId] ?: cityId,
    coverImageUrl = coverImageUrl,
    price = priceFromXof,
    ratingLabel = ratingAverage?.toRatingLabel(),
    likesCount = interaction?.likesCount ?: likesCount,
    sponsored = sponsoredUntilEpochMilliseconds?.let { it > nowEpochMilliseconds } ?: false,
    liked = interaction?.likedByViewer ?: false,
    favorited = interaction?.favoritedByViewer ?: false,
)

private fun ExploreLoadRequest.errorState(strings: KwaborStrings, error: DomainError): ExploreUiState =
    initialExploreUiState(strings = strings, request = this).copy(
        isOffline = error is DomainError.NetworkUnavailable,
        errorMessage = error.toExploreMessage(strings),
    )

private fun List<City>.homeCityLabel(strings: KwaborStrings): String =
    firstOrNull { city -> city.name.equals(strings.currentCity, ignoreCase = true) }?.name
        ?: firstOrNull()?.name
        ?: strings.currentCity

private fun String.toKnownCategoryId(categories: List<Category>): String? {
    val knownCategoryIds = categories.mapTo(mutableSetOf()) { category -> category.id }
    return CATEGORY_IDS_BY_CHIP[this]?.firstOrNull { categoryId -> categoryId in knownCategoryIds }
}

private fun DomainError.toExploreMessage(strings: KwaborStrings): String = when (this) {
    is DomainError.NetworkUnavailable -> strings.offlineBanner
    is DomainError.AuthenticationRequired,
    is DomainError.NotFound,
    is DomainError.PermissionDenied,
    is DomainError.Unexpected,
    is DomainError.Validation,
    -> strings.errorStateTitle
}

private fun Double.toRatingLabel(): String {
    val rounded = (this * RATING_DECIMAL_SCALE).roundToInt() / RATING_DECIMAL_DIVISOR
    return rounded.toString().replace(oldChar = '.', newChar = ',')
}

private data class ViewerInteractionsState(
    val byListingId: Map<String, ListingViewerInteraction> = emptyMap(),
    val isOffline: Boolean = false,
    val message: String? = null,
)

private data class ExploreInteractionFailure(
    val listingId: String,
    val kind: ExploreInteractionKind,
    val selected: Boolean,
    val error: DomainError,
    val queuedAtEpochMilliseconds: Long,
)
