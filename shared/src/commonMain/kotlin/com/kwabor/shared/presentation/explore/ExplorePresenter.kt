package com.kwabor.shared.presentation.explore

import com.kwabor.shared.domain.catalog.CatalogInteractionRepository
import com.kwabor.shared.domain.catalog.Category
import com.kwabor.shared.domain.catalog.ListingFilters
import com.kwabor.shared.domain.catalog.ListingSummary
import com.kwabor.shared.domain.catalog.ListingViewerInteraction
import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.explore.ExploreFeedQuery
import com.kwabor.shared.domain.explore.ExploreFeedRepository
import com.kwabor.shared.domain.explore.ExploreFeedSnapshot
import com.kwabor.shared.domain.explore.ExploreFeedSource
import com.kwabor.shared.domain.explore.ExploreFeedWarning
import com.kwabor.shared.domain.favorites.FavoriteMutation
import com.kwabor.shared.domain.favorites.FavoritesRepository
import com.kwabor.shared.domain.preferences.AppPreferences
import com.kwabor.shared.domain.preferences.AppPreferencesRepository
import com.kwabor.shared.i18n.KwaborStrings
import kotlin.math.max
import kotlin.math.roundToInt

private const val EXPLORE_PAGE_SIZE = 20
private const val DEFAULT_EXPLORE_CITY_ID = "cotonou"
private const val RATING_DECIMAL_SCALE = 10
private const val RATING_DECIMAL_DIVISOR = 10.0
private const val CITY_UNAVAILABLE_ERROR_KEY = "error.explore.city_unavailable"
private const val CATEGORY_UNAVAILABLE_ERROR_KEY = "error.explore.category_unavailable"
private const val APPEND_REVALIDATION_REQUIRED_ERROR_KEY = "error.explore.revalidation_required"

class ExplorePresenter(
    private val exploreFeedRepository: ExploreFeedRepository,
    private val catalogInteractionRepository: CatalogInteractionRepository,
    private val favoritesRepository: FavoritesRepository,
    private val appPreferencesRepository: AppPreferencesRepository?,
    private val clockProvider: ClockProvider,
) {
    suspend fun load(request: ExploreLoadRequest, strings: KwaborStrings): ExploreUiState {
        val preparedState = prepareInitialState(request, strings)
        val cachedState = loadCached(preparedState, strings)
        return refresh(cachedState, strings)
    }

    suspend fun prepareInitialState(request: ExploreLoadRequest, strings: KwaborStrings): ExploreUiState {
        val preferencesResult = appPreferencesRepository?.get()
        val preferences = when (preferencesResult) {
            is DomainResult.Success -> preferencesResult.value
            is DomainResult.Failure,
            null,
            -> AppPreferences.Default
        }
        return initialExploreUiState(strings = strings, request = request).copy(
            selectedCityId = request.selectedCityId ?: preferences.exploreCityId ?: DEFAULT_EXPLORE_CITY_ID,
            currency = preferences.displayCurrency,
            isLocalCacheUnavailable = preferencesResult is DomainResult.Failure || preferencesResult == null,
        )
    }

    suspend fun loadCached(state: ExploreUiState, strings: KwaborStrings): ExploreUiState =
        when (val result = exploreFeedRepository.readCached(state.toFeedQuery())) {
            is DomainResult.Success -> result.value?.let { snapshot ->
                state.applySnapshot(
                    snapshot = snapshot,
                    strings = strings,
                    interactionsByListingId = state.viewerInteractionsByListingId(),
                )
            } ?: state
            is DomainResult.Failure -> state.copy(isLocalCacheUnavailable = true)
        }

    suspend fun refresh(state: ExploreUiState, strings: KwaborStrings): ExploreUiState {
        val result = exploreFeedRepository.refresh(state.toFeedQuery())
        return when (result) {
            is DomainResult.Success -> applyNetworkSnapshot(
                state = state,
                snapshot = result.value,
                strings = strings,
                interactionListingIds = result.value.items.map(ListingSummary::id),
            )
            is DomainResult.Failure -> refreshAfterFilterValidationOrFail(state, strings, result.error)
        }
    }

    suspend fun append(state: ExploreUiState, strings: KwaborStrings): ExploreUiState {
        val currentSnapshot = state.feedSnapshot
            ?: return state.appendFailure(strings, DomainError.Unexpected())
        if (currentSnapshot.source != ExploreFeedSource.Network) {
            return state.appendFailure(
                strings = strings,
                error = DomainError.Validation(APPEND_REVALIDATION_REQUIRED_ERROR_KEY),
            )
        }
        val currentListingIds = currentSnapshot.items.mapTo(mutableSetOf(), ListingSummary::id)
        return when (
            val result = exploreFeedRepository.append(
                query = state.toFeedQuery(),
                currentSnapshot = currentSnapshot,
            )
        ) {
            is DomainResult.Success -> applyNetworkSnapshot(
                state = state,
                snapshot = result.value,
                strings = strings,
                interactionListingIds = result.value.items
                    .map(ListingSummary::id)
                    .filterNot(currentListingIds::contains),
            )
            is DomainResult.Failure -> state.appendFailure(strings, result.error)
        }
    }

    suspend fun selectCity(state: ExploreUiState, cityId: String, strings: KwaborStrings): ExploreUiState {
        val city = state.availableCities.firstOrNull { option -> option.id == cityId } ?: return state
        val persistenceFailed = when (val repository = appPreferencesRepository) {
            null -> true
            else -> repository.setExploreCity(city.id) is DomainResult.Failure
        }
        return state.copy(
            cityLabel = city.label,
            selectedCityId = city.id,
            isCitySelectorOpen = false,
            locationMessage = if (persistenceFailed) strings.exploreCityPersistenceError else null,
            isLocalCacheUnavailable = state.isLocalCacheUnavailable || persistenceFailed,
        )
    }

    suspend fun toggleLike(state: ExploreUiState, listingId: String, strings: KwaborStrings): ExploreUiState =
        executeInteraction(
            state = state,
            listingId = listingId,
            strings = strings,
            kind = ExploreInteractionKind.Like,
        ).state

    suspend fun toggleFavorite(state: ExploreUiState, listingId: String, strings: KwaborStrings): ExploreUiState =
        executeFavoriteToggle(state, listingId, strings).state

    internal suspend fun executeFavoriteToggle(
        state: ExploreUiState,
        listingId: String,
        strings: KwaborStrings,
    ): ExploreInteractionExecution = executeInteraction(
        state = state,
        listingId = listingId,
        strings = strings,
        kind = ExploreInteractionKind.Favorite,
    )

    private suspend fun refreshAfterFilterValidationOrFail(
        state: ExploreUiState,
        strings: KwaborStrings,
        error: DomainError,
    ): ExploreUiState {
        val fallbackState = when {
            error.messageKey == CITY_UNAVAILABLE_ERROR_KEY -> state.copy(
                selectedCityId = state.fallbackCityId(),
            )
            error.messageKey == CATEGORY_UNAVAILABLE_ERROR_KEY -> state.copy(selectedChipId = null)
            else -> return state.refreshFailure(strings, error)
        }
        if (fallbackState.toFeedQuery() == state.toFeedQuery()) {
            return state.refreshFailure(strings, error)
        }
        return when (val retry = exploreFeedRepository.refresh(fallbackState.toFeedQuery())) {
            is DomainResult.Success -> {
                val persistenceFailed = fallbackState.selectedCityId != state.selectedCityId &&
                    appPreferencesRepository.persistExploreCity(fallbackState.selectedCityId)
                applyNetworkSnapshot(
                    state = fallbackState,
                    snapshot = retry.value,
                    strings = strings,
                    interactionListingIds = retry.value.items.map(ListingSummary::id),
                ).copy(
                    isLocalCacheUnavailable = fallbackState.isLocalCacheUnavailable || persistenceFailed,
                )
            }
            is DomainResult.Failure -> state.refreshFailure(strings, retry.error)
        }
    }

    private suspend fun applyNetworkSnapshot(
        state: ExploreUiState,
        snapshot: ExploreFeedSnapshot,
        strings: KwaborStrings,
        interactionListingIds: List<String>,
    ): ExploreUiState {
        val knownInteractions = state.viewerInteractionsByListingId()
        val interactions = loadViewerInteractions(
            listingIds = interactionListingIds,
        )
        return state.applySnapshot(
            snapshot = snapshot,
            strings = strings,
            interactionsByListingId = knownInteractions + interactions.byListingId,
        ).copy(
            isLoading = false,
            isRefreshing = false,
            isAppending = false,
            isOffline = interactions.isOffline || state.queuedInteractions.hasNetworkRetry(),
            contentIsOffline = interactions.isOffline,
            errorMessage = null,
            refreshMessage = null,
            appendErrorMessage = null,
            interactionMessage = interactions.message ?: state.interactionMessage,
        )
    }

    private suspend fun executeInteraction(
        state: ExploreUiState,
        listingId: String,
        strings: KwaborStrings,
        kind: ExploreInteractionKind,
    ): ExploreInteractionExecution {
        val listing = state.listings.firstOrNull { item -> item.id == listingId }
            ?: return ExploreInteractionExecution(state)
        val selected = when (kind) {
            ExploreInteractionKind.Like -> !listing.liked
            ExploreInteractionKind.Favorite -> !listing.favorited
        }
        return when (val result = runInteraction(kind, listingId, selected)) {
            is DomainResult.Success -> ExploreInteractionExecution(
                state = state.applyInteraction(result.value),
                clientMutationSequence = result.value.clientMutationSequence,
            )
            is DomainResult.Failure -> ExploreInteractionExecution(
                state = state.handleInteractionFailure(
                    strings = strings,
                    failure = ExploreInteractionFailure(
                        listingId = listingId,
                        kind = kind,
                        selected = selected,
                        error = result.error,
                        queuedAtEpochMilliseconds = clockProvider.nowEpochMilliseconds(),
                    ),
                ),
            )
        }
    }

    private suspend fun runInteraction(
        kind: ExploreInteractionKind,
        listingId: String,
        selected: Boolean,
    ): DomainResult<ExploreInteractionResult> = when (kind) {
        ExploreInteractionKind.Like -> {
            val result = if (selected) {
                catalogInteractionRepository.likeListing(listingId)
            } else {
                catalogInteractionRepository.unlikeListing(listingId)
            }
            when (result) {
                is DomainResult.Success -> DomainResult.Success(ExploreInteractionResult.Like(result.value))
                is DomainResult.Failure -> result
            }
        }
        ExploreInteractionKind.Favorite -> when (
            val result = favoritesRepository.setFavorite(listingId = listingId, favorited = selected)
        ) {
            is DomainResult.Success -> if (
                result.value.listingId == listingId &&
                result.value.favorited == selected &&
                result.value.clientMutationSequence > 0L
            ) {
                DomainResult.Success(ExploreInteractionResult.Favorite(result.value))
            } else {
                DomainResult.Failure(DomainError.Unexpected())
            }
            is DomainResult.Failure -> result
        }
    }

    private suspend fun loadViewerInteractions(listingIds: List<String>): ViewerInteractionsState {
        if (listingIds.isEmpty()) {
            return ViewerInteractionsState()
        }
        return when (val result = catalogInteractionRepository.listListingViewerInteractions(listingIds)) {
            is DomainResult.Success -> ViewerInteractionsState(
                byListingId = result.value.associateBy(ListingViewerInteraction::listingId),
            )
            is DomainResult.Failure -> when (result.error) {
                is DomainError.AuthenticationRequired,
                is DomainError.PermissionDenied,
                -> ViewerInteractionsState()
                is DomainError.NetworkUnavailable -> ViewerInteractionsState(isOffline = true)
                is DomainError.LocalStorageUnavailable,
                is DomainError.NotFound,
                is DomainError.Unexpected,
                is DomainError.Validation,
                -> ViewerInteractionsState()
            }
        }
    }
}

internal data class ExploreInteractionExecution(
    val state: ExploreUiState,
    val clientMutationSequence: Long? = null,
)

private suspend fun AppPreferencesRepository?.persistExploreCity(cityId: String?): Boolean {
    val repository = this ?: return true
    return repository.setExploreCity(cityId) is DomainResult.Failure
}

private fun ExploreUiState.applySnapshot(
    snapshot: ExploreFeedSnapshot,
    strings: KwaborStrings,
    interactionsByListingId: Map<String, ListingViewerInteraction>,
): ExploreUiState {
    val cityNamesById = snapshot.cities.associate { city -> city.id to city.name }
    val availableChips = snapshot.categories.toExploreChips(selectedTab, strings)
    val selectedCategory = selectedChipId?.takeIf { chipId -> availableChips.any { chip -> chip.id == chipId } }
    val items = snapshot.items.map { listing ->
        listing.toExploreListingItem(
            cityNamesById = cityNamesById,
            interaction = interactionsByListingId[listing.id],
            strings = strings,
        )
    }.applyQueuedInteractions(queuedInteractions)
    return copy(
        cityLabel = cityNamesById[selectedCityId] ?: cityLabel,
        availableCities = snapshot.cities.map { city -> ExploreCityOption(city.id, city.name) },
        selectedChipId = selectedCategory,
        chips = availableChips,
        listings = items,
        nextCursor = snapshot.nextCursor,
        feedSnapshot = snapshot,
        isLocalCacheUnavailable = isLocalCacheUnavailable ||
            snapshot.warning is ExploreFeedWarning.LocalPersistenceUnavailable,
    )
}

private fun ExploreUiState.refreshFailure(strings: KwaborStrings, error: DomainError): ExploreUiState {
    val networkUnavailable = error is DomainError.NetworkUnavailable
    val offline = networkUnavailable || queuedInteractions.hasNetworkRetry()
    return if (listings.isEmpty()) {
        copy(
            isLoading = false,
            isRefreshing = false,
            isAppending = false,
            isOffline = offline,
            contentIsOffline = networkUnavailable,
            errorMessage = error.toExploreMessage(strings),
            refreshMessage = null,
        )
    } else {
        copy(
            isLoading = false,
            isRefreshing = false,
            isAppending = false,
            isOffline = offline,
            contentIsOffline = networkUnavailable,
            errorMessage = null,
            refreshMessage = strings.exploreRefreshError,
        )
    }
}

private fun ExploreUiState.appendFailure(strings: KwaborStrings, error: DomainError): ExploreUiState {
    val networkUnavailable = error is DomainError.NetworkUnavailable
    return copy(
        isAppending = false,
        isOffline = networkUnavailable || queuedInteractions.hasNetworkRetry(),
        contentIsOffline = networkUnavailable,
        appendErrorMessage = strings.exploreLoadMoreError,
    )
}

private fun ExploreUiState.toFeedQuery(): ExploreFeedQuery = ExploreFeedQuery(
    filters = ListingFilters(
        cityId = selectedCityId,
        categoryId = selectedChipId,
        listingType = selectedTab.toListingType(),
        onlyPublished = true,
    ),
    pageSize = EXPLORE_PAGE_SIZE,
)

private fun ExploreUiState.viewerInteractionsByListingId(): Map<String, ListingViewerInteraction> =
    listings.associate { listing ->
        listing.id to ListingViewerInteraction(
            listingId = listing.id,
            likedByViewer = listing.liked,
            favoritedByViewer = listing.favorited,
            likesCount = listing.likesCount,
        )
    }

private fun ExploreUiState.fallbackCityId(): String =
    availableCities.firstOrNull { city -> city.id == DEFAULT_EXPLORE_CITY_ID }?.id
        ?: availableCities.firstOrNull()?.id
        ?: DEFAULT_EXPLORE_CITY_ID

private fun List<Category>.toExploreChips(tab: ExploreTab, strings: KwaborStrings): List<ExploreChip> = asSequence()
    .filter { category -> category.listingType == tab.toListingType() }
    .mapNotNull { category -> category.toExploreChip(strings) }
    .toList()

private fun Category.toExploreChip(strings: KwaborStrings): ExploreChip? {
    val label = when (nameKey) {
        "category.heritage.historique" -> strings.history
        "category.heritage.nature" -> strings.nature
        "category.commercial.marche" -> strings.markets
        "category.commercial.restaurant" -> strings.restaurants
        "category.commercial.hotel" -> strings.hotels
        "category.commercial.guide" -> strings.touristGuides
        "category.event.culture" -> strings.culture
        else -> return null
    }
    return ExploreChip(id = id, label = label)
}

private fun ListingSummary.toExploreListingItem(
    cityNamesById: Map<String, String>,
    interaction: ListingViewerInteraction?,
    strings: KwaborStrings,
): ExploreListingItem = ExploreListingItem(
    id = id,
    title = name,
    cityLabel = cityNamesById[cityId] ?: cityId,
    coverImageUrl = coverImageUrl,
    price = priceFromXof,
    ratingLabel = ratingAverage?.toRatingLabel(),
    likesCount = interaction?.likesCount ?: likesCount,
    sponsored = isSponsoredPlacement == true,
    liked = interaction?.likedByViewer ?: false,
    favorited = interaction?.favoritedByViewer ?: false,
    cityId = cityId,
    coverImageAlt = coverImageAlt,
    eventDateLabel = eventStartAtEpochMilliseconds?.toExploreDateLabel(strings.exploreDate),
    isEventEnded = isEventEnded == true,
)

private fun ExploreUiState.applyInteraction(result: ExploreInteractionResult): ExploreUiState {
    val remainingQueuedInteractions = queuedInteractions.filterNot { queued ->
        queued.listingId == result.listingId && queued.kind == result.kind
    }
    return copy(
        isOffline = contentIsOffline || remainingQueuedInteractions.hasNetworkRetry(),
        interactionMessage = null,
        pendingAuthInteraction = null,
        listings = listings.map { listing -> listing.applyInteractionResult(result) },
        queuedInteractions = remainingQueuedInteractions,
    )
}

private fun ExploreListingItem.applyInteractionResult(result: ExploreInteractionResult): ExploreListingItem =
    when (result) {
        is ExploreInteractionResult.Like -> if (id == result.interaction.listingId) {
            copy(
                liked = result.interaction.likedByViewer,
                likesCount = result.interaction.likesCount,
            )
        } else {
            this
        }
        is ExploreInteractionResult.Favorite -> if (id == result.mutation.listingId) {
            copy(favorited = result.mutation.favorited)
        } else {
            this
        }
    }

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
            suggestedCityId = listings.firstOrNull { listing -> listing.id == failure.listingId }?.cityId,
        ),
    )
    is DomainError.NetworkUnavailable -> queueOfflineInteraction(
        listingId = failure.listingId,
        kind = failure.kind,
        selected = failure.selected,
        message = strings.interactionQueuedOffline,
        queuedAtEpochMilliseconds = failure.queuedAtEpochMilliseconds,
    )
    is DomainError.LocalStorageUnavailable,
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

private fun List<ExploreListingItem>.applyQueuedInteractions(
    interactions: List<QueuedExploreInteraction>,
): List<ExploreListingItem> = interactions.fold(this) { items, interaction ->
    items.map { listing ->
        if (listing.id == interaction.listingId) {
            listing.applyOptimisticInteraction(interaction.kind, interaction.selected)
        } else {
            listing
        }
    }
}

private fun ExploreListingItem.applyOptimisticInteraction(
    kind: ExploreInteractionKind,
    selected: Boolean,
): ExploreListingItem = when (kind) {
    ExploreInteractionKind.Like -> if (liked == selected) {
        this
    } else {
        copy(
            liked = selected,
            likesCount = if (selected) likesCount + 1 else max(likesCount - 1, 0),
        )
    }
    ExploreInteractionKind.Favorite -> copy(favorited = selected)
}

private fun List<QueuedExploreInteraction>.upsert(
    interaction: QueuedExploreInteraction,
): List<QueuedExploreInteraction> = filterNot { queued ->
    queued.listingId == interaction.listingId && queued.kind == interaction.kind
} + interaction

private fun DomainError.toExploreMessage(strings: KwaborStrings): String = when (this) {
    is DomainError.NetworkUnavailable -> strings.offlineBanner
    is DomainError.AuthenticationRequired,
    is DomainError.LocalStorageUnavailable,
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

private sealed interface ExploreInteractionResult {
    val listingId: String
    val kind: ExploreInteractionKind
    val clientMutationSequence: Long?

    data class Like(val interaction: ListingViewerInteraction) : ExploreInteractionResult {
        override val listingId: String = interaction.listingId
        override val kind: ExploreInteractionKind = ExploreInteractionKind.Like
        override val clientMutationSequence: Long? = null
    }

    data class Favorite(val mutation: FavoriteMutation) : ExploreInteractionResult {
        override val listingId: String = mutation.listingId
        override val kind: ExploreInteractionKind = ExploreInteractionKind.Favorite
        override val clientMutationSequence: Long = mutation.clientMutationSequence
    }
}
