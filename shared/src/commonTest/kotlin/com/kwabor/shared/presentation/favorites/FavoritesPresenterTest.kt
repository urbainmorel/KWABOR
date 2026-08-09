package com.kwabor.shared.presentation.favorites

import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingPageRequest
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.favorites.FavoriteListing
import com.kwabor.shared.domain.favorites.FavoriteListingPage
import com.kwabor.shared.domain.favorites.FavoriteMutation
import com.kwabor.shared.domain.favorites.FavoritesRepository
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.money.MoneyXof
import com.kwabor.shared.i18n.stringsFor
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FavoritesPresenterTest {
    private val strings = stringsFor(AppLocale.French).favorites

    @Test
    fun loadMapsTheRequestedFilterAndKeepsEndedEventsVisible() = runTest {
        val repository = RecordingFavoritesRepository(
            pageResults = mutableListOf(
                DomainResult.Success(
                    FavoriteListingPage(
                        items = listOf(favoriteListing(type = ListingType.Event, isEventEnded = true)),
                        nextCursor = "next",
                    ),
                ),
            ),
        )

        val state = FavoritesPresenter(repository).load(FavoritesFilter.Events, strings)

        val request = repository.pageRequests.single()
        assertEquals(ListingType.Event, request.first)
        assertEquals(ListingPageRequest(cursor = null, limit = 20), request.second)
        assertEquals("Porte du non-retour", state.items.single().title)
        assertEquals("4,8", state.items.single().ratingLabel)
        assertTrue(state.items.single().isEventEnded)
        assertEquals("next", state.nextCursor)
        assertTrue(state.isAccountReady)
        assertFalse(state.isLoading)
    }

    @Test
    fun filtersMapExactlyToTheBackendListingFamilies() = runTest {
        val expectedTypes = mapOf(
            FavoritesFilter.All to null,
            FavoritesFilter.Places to ListingType.Place,
            FavoritesFilter.Events to ListingType.Event,
            FavoritesFilter.HotelsRestaurants to ListingType.Establishment,
        )

        expectedTypes.forEach { (filter, expectedType) ->
            val itemType = expectedType ?: ListingType.Place
            val repository = RecordingFavoritesRepository(
                pageResults = mutableListOf(
                    DomainResult.Success(
                        FavoriteListingPage(listOf(favoriteListing(type = itemType)), null),
                    ),
                ),
            )

            FavoritesPresenter(repository).load(filter, strings)

            assertEquals(expectedType, repository.pageRequests.single().first)
        }
    }

    @Test
    fun malformedInitialPageFailsClosed() = runTest {
        val duplicate = favoriteListing()
        val repository = RecordingFavoritesRepository(
            pageResults = mutableListOf(
                DomainResult.Success(FavoriteListingPage(listOf(duplicate, duplicate), null)),
            ),
        )

        val state = FavoritesPresenter(repository).load(FavoritesFilter.All, strings)

        assertEquals(strings.loadFailed, state.errorMessage)
        assertTrue(state.items.isEmpty())
    }

    @Test
    fun refreshFailureKeepsCardsAndUsesItsOwnMessage() = runTest {
        val repository = RecordingFavoritesRepository(
            pageResults = mutableListOf(DomainResult.Failure(DomainError.NetworkUnavailable())),
        )
        val previous = favoritesState(
            items = listOf(favoriteListing().toTestItem()),
            isRefreshing = true,
        )

        val state = FavoritesPresenter(repository).refresh(previous, strings)

        assertEquals(previous.items, state.items)
        assertEquals(strings.refreshFailed, state.refreshMessage)
        assertNull(state.errorMessage)
        assertTrue(state.isOffline)
        assertFalse(state.isRefreshing)
    }

    @Test
    fun appendRejectsDuplicateOrNonAdvancingPagesWithoutReplacingCards() = runTest {
        val existing = favoriteListing()
        val repository = RecordingFavoritesRepository(
            pageResults = mutableListOf(
                DomainResult.Success(FavoriteListingPage(listOf(existing), "cursor-1")),
            ),
        )
        val previous = favoritesState(
            items = listOf(existing.toTestItem()),
            nextCursor = "cursor-1",
            isAppending = true,
        )

        val state = FavoritesPresenter(repository).append(previous, strings)

        assertEquals(previous.items, state.items)
        assertEquals("cursor-1", state.nextCursor)
        assertEquals(strings.loadMoreFailed, state.appendErrorMessage)
        assertFalse(state.isAppending)
    }

    @Test
    fun appendFailureDoesNotPolluteRefreshOrInitialErrors() = runTest {
        val repository = RecordingFavoritesRepository(
            pageResults = mutableListOf(DomainResult.Failure(DomainError.NetworkUnavailable())),
        )
        val previous = favoritesState(
            items = listOf(favoriteListing().toTestItem()),
            nextCursor = "cursor-1",
            isAppending = true,
        )

        val state = FavoritesPresenter(repository).append(previous, strings)

        assertEquals(strings.loadMoreFailed, state.appendErrorMessage)
        assertNull(state.refreshMessage)
        assertNull(state.errorMessage)
        assertTrue(state.isOffline)
    }

    @Test
    fun removeFavoriteRequiresTheExactConfirmedAbsentMutation() = runTest {
        val repository = RecordingFavoritesRepository(
            mutationResults = mutableListOf(
                DomainResult.Success(
                    FavoriteMutation(FAVORITE_ID, favorited = false, favoritedAtEpochMilliseconds = null),
                ),
                DomainResult.Success(
                    FavoriteMutation(FAVORITE_ID, favorited = true, favoritedAtEpochMilliseconds = 1_000L),
                ),
            ),
        )
        val presenter = FavoritesPresenter(repository)

        val removed = presenter.removeFavorite(FAVORITE_ID, strings)
        val malformed = presenter.removeFavorite(FAVORITE_ID, strings)

        assertIs<FavoriteRemovalOutcome.Removed>(removed)
        assertIs<FavoriteRemovalOutcome.Failed>(malformed)
        assertEquals(listOf(FAVORITE_ID to false, FAVORITE_ID to false), repository.mutations)
    }
}

internal class RecordingFavoritesRepository(
    val pageResults: MutableList<DomainResult<FavoriteListingPage>> = mutableListOf(
        DomainResult.Success(FavoriteListingPage(listOf(favoriteListing()), null)),
    ),
    val mutationResults: MutableList<DomainResult<FavoriteMutation>> = mutableListOf(
        DomainResult.Success(
            FavoriteMutation(FAVORITE_ID, favorited = false, favoritedAtEpochMilliseconds = null),
        ),
    ),
) : FavoritesRepository {
    val pageRequests = mutableListOf<Pair<ListingType?, ListingPageRequest>>()
    val mutations = mutableListOf<Pair<String, Boolean>>()

    override suspend fun listFavorites(
        filter: ListingType?,
        page: ListingPageRequest,
    ): DomainResult<FavoriteListingPage> {
        pageRequests += filter to page
        return pageResults.removeFirst()
    }

    override suspend fun setFavorite(listingId: String, favorited: Boolean): DomainResult<FavoriteMutation> {
        mutations += listingId to favorited
        return mutationResults.removeFirst()
    }
}

internal fun favoriteListing(
    id: String = FAVORITE_ID,
    type: ListingType = ListingType.Place,
    isEventEnded: Boolean = false,
): FavoriteListing = FavoriteListing(
    id = id,
    type = type,
    listingClass = if (type == ListingType.Event) ListingClass.Event else ListingClass.Heritage,
    name = "Porte du non-retour",
    cityId = "ouidah",
    cityName = "Ouidah",
    categoryId = "heritage-historique",
    coverImageUrl = "https://media.kwabor.test/favorite.jpg",
    coverImageAlt = "Porte du non-retour à Ouidah",
    priceFromXof = money(2_500),
    ratingAverage = 4.75,
    likesCount = 12,
    verified = true,
    likedByViewer = true,
    favoritedAtEpochMilliseconds = 1_000L,
    eventStartAtEpochMilliseconds = if (type == ListingType.Event) 500L else null,
    eventEndAtEpochMilliseconds = if (type == ListingType.Event) 900L else null,
    isEventEnded = isEventEnded,
)

internal fun favoritesState(
    items: List<FavoriteListingItem> = emptyList(),
    nextCursor: String? = null,
    isRefreshing: Boolean = false,
    isAppending: Boolean = false,
): FavoritesUiState = FavoritesUiState(
    items = items,
    nextCursor = nextCursor,
    isAccountReady = true,
    isRefreshing = isRefreshing,
    isAppending = isAppending,
)

internal fun FavoriteListing.toTestItem(): FavoriteListingItem = FavoriteListingItem(
    id = id,
    type = type,
    listingClass = listingClass,
    title = name,
    cityLabel = cityName,
    coverImageUrl = coverImageUrl,
    coverImageAlt = coverImageAlt,
    price = priceFromXof,
    ratingLabel = "4,8",
    likesCount = likesCount,
    verified = verified,
    liked = likedByViewer,
    favoritedAtEpochMilliseconds = favoritedAtEpochMilliseconds,
    eventStartAtEpochMilliseconds = eventStartAtEpochMilliseconds,
    eventEndAtEpochMilliseconds = eventEndAtEpochMilliseconds,
    isEventEnded = isEventEnded,
)

private fun money(amount: Long): MoneyXof = when (val result = MoneyXof.fromAmount(amount)) {
    is DomainResult.Success -> result.value
    is DomainResult.Failure -> error("Invalid favorite test price")
}

internal const val FAVORITE_ID = "a1000000-0000-4000-8000-000000000001"
