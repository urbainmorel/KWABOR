package com.kwabor.shared.presentation.search

import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingStatus
import com.kwabor.shared.domain.catalog.ListingSummary
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.search.SearchPageRequest
import com.kwabor.shared.domain.search.SearchQuery
import com.kwabor.shared.domain.search.SearchRepository
import com.kwabor.shared.domain.search.SearchResult
import com.kwabor.shared.domain.search.SearchResultSource
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.explore.ExploreCityOption
import com.kwabor.shared.presentation.explore.ExploreTab
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchPresenterTest {
    private val strings = stringsFor(AppLocale.French).search

    @Test
    fun submitCanonicalizesQueryAndKeepsTheActiveExploreScope() = runTest {
        val repository = RecordingSearchRepository(
            results = mutableListOf(networkResult(nextCursor = "next")),
        )
        val state = SearchPresenter(repository).submit(
            state = activeState(queryText = "  Ouidah  "),
            strings = strings,
        )

        val request = repository.requests.single()
        assertEquals("Ouidah", request.first.text)
        assertEquals("cotonou", request.first.filters.cityId)
        assertEquals("heritage-historique", request.first.filters.categoryId)
        assertEquals(ListingType.Place, request.first.filters.listingType)
        assertEquals("Ouidah", state.submittedQueryText)
        assertEquals(SEARCH_LISTING_ID, state.listings.single().id)
        assertEquals("Ouidah", state.listings.single().cityLabel)
        assertEquals("4,8", state.listings.single().ratingLabel)
        assertEquals("1 résultat", state.resultCountLabel)
        assertEquals("next", state.nextCursor)
        assertFalse(state.isOffline)
    }

    @Test
    fun allScopeDropsTabAndChipButKeepsTheSelectedCity() = runTest {
        val repository = RecordingSearchRepository(mutableListOf(networkResult()))
        SearchPresenter(repository).submit(
            activeState(queryText = "musée").copy(scope = SearchScope.All),
            strings,
        )

        val filters = repository.requests.single().first.filters
        assertEquals("cotonou", filters.cityId)
        assertNull(filters.categoryId)
        assertNull(filters.listingType)
    }

    @Test
    fun invalidQueryFailsLocallyWithoutCallingTheRepository() = runTest {
        val repository = RecordingSearchRepository()
        val state = SearchPresenter(repository).submit(activeState(queryText = "   "), strings)

        assertTrue(repository.requests.isEmpty())
        assertEquals(strings.invalidQuery, state.queryErrorMessage)
        assertNull(state.submittedQueryText)
        assertFalse(state.isLoading)
    }

    @Test
    fun localFallbackIsRenderedAsOfflineWithoutChangingTheQuery() = runTest {
        val repository = RecordingSearchRepository(
            mutableListOf(networkResult(source = SearchResultSource.LocalCache)),
        )
        val state = SearchPresenter(repository).submit(activeState(queryText = "Ganvié"), strings)

        assertTrue(state.isOffline)
        assertEquals("Ganvié", state.submittedQueryText)
        assertNull(state.errorMessage)
    }

    @Test
    fun networkFailureIsExplicitWhenNoLocalFallbackExists() = runTest {
        val repository = RecordingSearchRepository(
            mutableListOf(DomainResult.Failure(DomainError.NetworkUnavailable())),
        )
        val state = SearchPresenter(repository).submit(activeState(queryText = "Ganvié"), strings)

        assertTrue(state.isOffline)
        assertEquals(strings.loadFailed, state.errorMessage)
        assertTrue(state.listings.isEmpty())
        assertEquals("Ganvié", state.submittedQueryText)
    }

    @Test
    fun refreshFailureKeepsVisibleResults() = runTest {
        val repository = RecordingSearchRepository(
            mutableListOf(DomainResult.Failure(DomainError.NetworkUnavailable())),
        )
        val previous = loadedState().copy(isRefreshing = true)
        val state = SearchPresenter(repository).refresh(previous, strings)

        assertEquals(previous.listings, state.listings)
        assertEquals(strings.refreshFailed, state.refreshMessage)
        assertNull(state.errorMessage)
        assertFalse(state.isRefreshing)
    }

    @Test
    fun appendPassesVisibleIdsAndRejectsACursorThatDoesNotAdvance() = runTest {
        val repository = RecordingSearchRepository(
            mutableListOf(networkResult(id = SEARCH_SECOND_LISTING_ID, nextCursor = "next")),
        )
        val previous = loadedState().copy(nextCursor = "next", isAppending = true)
        val state = SearchPresenter(repository).append(previous, strings)

        assertEquals(setOf(SEARCH_LISTING_ID), repository.requests.single().second.excludedListingIds)
        assertEquals("next", repository.requests.single().second.cursor)
        assertEquals(previous.listings, state.listings)
        assertEquals(strings.loadMoreFailed, state.appendErrorMessage)
        assertFalse(state.isAppending)
    }

    @Test
    fun remoteAppendContinuesPastTheBoundedOfflineExclusionCapacity() = runTest {
        val repository = RecordingSearchRepository(
            mutableListOf(networkResult(id = "remote-listing-new", nextCursor = "remote-next")),
        )
        val listing = loadedState().listings.single()
        val previous = loadedState().copy(
            listings = List(SearchPageRequest.MAX_EXCLUDED_LISTING_IDS + 1) { index ->
                listing.copy(id = "listing-$index")
            },
            nextCursor = "next",
            isAppending = true,
        )

        val state = SearchPresenter(repository).append(previous, strings)

        assertTrue(repository.requests.single().second.excludedListingIds.isEmpty())
        assertEquals(SearchPageRequest.MAX_EXCLUDED_LISTING_IDS + 2, state.listings.size)
        assertEquals("remote-next", state.nextCursor)
        assertFalse(state.isAppending)
        assertNull(state.appendErrorMessage)
    }
}

internal class RecordingSearchRepository(
    private val results: MutableList<DomainResult<SearchResult>> = mutableListOf(),
) : SearchRepository {
    val requests = mutableListOf<Pair<SearchQuery, SearchPageRequest>>()

    override suspend fun search(query: SearchQuery, page: SearchPageRequest): DomainResult<SearchResult> {
        requests += query to page
        return results.removeFirst()
    }
}

internal fun activeState(queryText: String): SearchUiState = SearchUiState(
    context = SearchContext(
        selectedTab = ExploreTab.Places,
        selectedChipId = "heritage-historique",
        selectedCityId = "cotonou",
        availableCities = listOf(
            ExploreCityOption("cotonou", "Cotonou"),
            ExploreCityOption("ouidah", "Ouidah"),
        ),
    ),
    queryText = queryText,
    isActive = true,
    isLoading = true,
)

internal fun loadedState(): SearchUiState = activeState("Ouidah").copy(
    queryText = "Ouidah",
    submittedQueryText = "Ouidah",
    listings = listOf(
        com.kwabor.shared.presentation.explore.ExploreListingItem(
            id = SEARCH_LISTING_ID,
            title = "Musée de Ouidah",
            cityLabel = "Ouidah",
            cityId = "ouidah",
            coverImageUrl = null,
            price = null,
        ),
    ),
    resultSource = SearchResultSource.Network,
    resultCountLabel = "1 résultat",
    isLoading = false,
)

internal fun networkResult(
    id: String = SEARCH_LISTING_ID,
    nextCursor: String? = null,
    source: SearchResultSource = SearchResultSource.Network,
): DomainResult<SearchResult> = DomainResult.Success(
    SearchResult(
        items = listOf(searchListing(id)),
        nextCursor = nextCursor,
        source = source,
    ),
)

private fun searchListing(id: String): ListingSummary = ListingSummary(
    id = id,
    type = ListingType.Place,
    listingClass = ListingClass.Heritage,
    status = ListingStatus.Published,
    name = "Musée de Ouidah",
    cityId = "ouidah",
    categoryId = "heritage-historique",
    coverImageUrl = null,
    priceFromXof = null,
    ratingAverage = 4.8,
    likesCount = 12,
    verified = true,
    sponsoredUntilEpochMilliseconds = null,
)

internal const val SEARCH_LISTING_ID = "a1000000-0000-4000-8000-000000000001"
internal const val SEARCH_SECOND_LISTING_ID = "b2000000-0000-4000-8000-000000000002"
