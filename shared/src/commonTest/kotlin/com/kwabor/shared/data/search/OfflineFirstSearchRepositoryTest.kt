@file:OptIn(ExperimentalCoroutinesApi::class)

package com.kwabor.shared.data.search

import androidx.sqlite.SQLiteException
import com.kwabor.shared.data.local.SearchCacheCandidate
import com.kwabor.shared.domain.catalog.CatalogDetail
import com.kwabor.shared.domain.catalog.CatalogRepository
import com.kwabor.shared.domain.catalog.Category
import com.kwabor.shared.domain.catalog.City
import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingFilters
import com.kwabor.shared.domain.catalog.ListingPageRequest
import com.kwabor.shared.domain.catalog.ListingSearchQuery
import com.kwabor.shared.domain.catalog.ListingStatus
import com.kwabor.shared.domain.catalog.ListingSummary
import com.kwabor.shared.domain.catalog.ListingSummaryPage
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.catalog.ListingViewerInteraction
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.search.SearchPageRequest
import com.kwabor.shared.domain.search.SearchQuery
import com.kwabor.shared.domain.search.SearchResult
import com.kwabor.shared.domain.search.SearchResultSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class OfflineFirstSearchRepositoryTest {
    @Test
    fun networkSuccessIsCanonicalizedWithoutReadingLocalCache() = runTest {
        val duplicate = listing(id = "listing-network")
        val catalog = FakeSearchCatalogRepository(
            DomainResult.Success(
                ListingSummaryPage(
                    items = listOf(duplicate, duplicate.copy(name = "Doublon")),
                    nextCursor = "remote-next",
                ),
            ),
        )
        val local = FakeSearchLocalCache()
        val filters = ListingFilters(cityId = "cotonou", listingType = ListingType.Establishment)
        val query = validQuery("Kwabor", filters)

        val result = searchRepository(catalog, local).search(
            query = query,
            page = SearchPageRequest(limit = 20, excludedListingIds = setOf("listing-seen")),
        )

        val search = assertSuccess(result)
        assertEquals(SearchResultSource.Network, search.source)
        assertEquals(listOf("listing-network"), search.items.map(ListingSummary::id))
        assertEquals("remote-next", search.nextCursor)
        assertEquals(ListingSearchQuery("Kwabor", filters), catalog.requests.single().first)
        assertEquals(ListingPageRequest(limit = 20), catalog.requests.single().second)
        assertEquals(0, local.readCount)
    }

    @Test
    fun onlyNetworkUnavailableCanActivateTheLocalFallback() = runTest {
        val local = FakeSearchLocalCache(candidates = listOf(candidate(listing())))
        val catalog = FakeSearchCatalogRepository(
            DomainResult.Failure(DomainError.PermissionDenied("error.denied")),
        )

        val result = searchRepository(catalog, local).search(validQuery("Kwabor"))

        assertEquals(
            DomainError.PermissionDenied("error.denied"),
            assertIs<DomainResult.Failure>(result).error,
        )
        assertEquals(0, local.readCount)
    }

    @Test
    fun localFallbackMatchesNameCityAndCategoryWithoutCallingTheNetworkAgain() = runTest {
        val catalog = FakeSearchCatalogRepository(DomainResult.Failure(DomainError.NetworkUnavailable()))
        val local = FakeSearchLocalCache(
            candidates = listOf(
                candidate(
                    listing(
                        id = "by-name",
                        name = "Musée Kwabor",
                        categoryId = "heritage-historique",
                    ),
                ),
                candidate(
                    listing(
                        id = "by-city",
                        name = "Escale",
                        categoryId = "heritage-nature",
                    ),
                    cityName = "Ouidah",
                ),
                candidate(
                    listing(
                        id = "by-category",
                        name = "Chez Mina",
                        categoryId = "commercial-restaurant",
                    ),
                    categoryNameKey = null,
                ),
            ),
        )
        val repository = searchRepository(catalog, local)

        val nameResult = repository.search(validQuery("kwabor"))
        val cityResult = repository.search(validQuery("ouidah"))
        val categoryResult = repository.search(validQuery("restaurant"))

        assertEquals(listOf("by-name"), assertSuccess(nameResult).items.map(ListingSummary::id))
        assertEquals(listOf("by-city"), assertSuccess(cityResult).items.map(ListingSummary::id))
        assertEquals(listOf("by-category"), assertSuccess(categoryResult).items.map(ListingSummary::id))
        assertEquals(3, catalog.requests.size)
        assertEquals(3, local.readCount)
    }

    @Test
    fun localFallbackMatchesComposedAndDecomposedDiacritics() = runTest {
        val catalog = FakeSearchCatalogRepository(DomainResult.Failure(DomainError.NetworkUnavailable()))
        val local = FakeSearchLocalCache(
            candidates = listOf(
                candidate(listing(id = "accented", name = "Musée de la Mémoire")),
                candidate(listing(id = "plain", name = "Marche artisanal")),
            ),
        )
        val repository = searchRepository(catalog, local)

        val plainQuery = repository.search(validQuery("musee memoire"))
        val composedQuery = repository.search(validQuery("marché"))
        val decomposedQuery = repository.search(validQuery("muse\u0301e"))

        assertEquals(listOf("accented"), assertSuccess(plainQuery).items.map(ListingSummary::id))
        assertEquals(listOf("plain"), assertSuccess(composedQuery).items.map(ListingSummary::id))
        assertEquals(listOf("accented"), assertSuccess(decomposedQuery).items.map(ListingSummary::id))
    }

    @Test
    fun localFallbackTreatsPunctuationAsTokenBoundaries() = runTest {
        val catalog = FakeSearchCatalogRepository(DomainResult.Failure(DomainError.NetworkUnavailable()))
        val local = FakeSearchLocalCache(
            candidates = listOf(
                candidate(listing(id = "complete", name = "Musée de la Mémoire")),
                candidate(listing(id = "partial", name = "Musée maritime")),
            ),
        )
        val repository = searchRepository(catalog, local)

        val punctuated = repository.search(validQuery("musée-mémoire"))
        val punctuationOnly = repository.search(validQuery("---"))

        assertEquals(listOf("complete"), assertSuccess(punctuated).items.map(ListingSummary::id))
        assertEquals(emptyList(), assertSuccess(punctuationOnly).items)
    }

    @Test
    fun localFallbackRequiresEveryTokenAcrossNameCityAndCategory() = runTest {
        val catalog = FakeSearchCatalogRepository(DomainResult.Failure(DomainError.NetworkUnavailable()))
        val local = FakeSearchLocalCache(
            candidates = listOf(
                candidate(
                    listing(
                        id = "cross-field",
                        name = "Palais royal",
                        categoryId = "heritage-musee",
                    ),
                    cityName = "Porto-Novo",
                ),
                candidate(
                    listing(
                        id = "missing-category",
                        name = "Palais royal",
                        categoryId = "heritage-monument",
                    ),
                    cityName = "Porto-Novo",
                ),
            ),
        )

        val result = searchRepository(catalog, local).search(validQuery("royal porto musée"))

        assertEquals(listOf("cross-field"), assertSuccess(result).items.map(ListingSummary::id))
    }

    @Test
    fun networkToLocalTransitionExcludesDisplayedIdsAndPaginatesWithOpaqueCursors() = runTest {
        val catalog = FakeSearchCatalogRepository(DomainResult.Failure(DomainError.NetworkUnavailable()))
        val local = FakeSearchLocalCache(
            candidates = listOf(
                candidate(listing(id = "listing-1", name = "Restaurant Un")),
                candidate(listing(id = "listing-2", name = "Restaurant Deux")),
                candidate(listing(id = "listing-3", name = "Restaurant Trois")),
            ),
        )
        val repository = searchRepository(catalog, local)
        val query = validQuery("restaurant")

        val transition = assertSuccess(
            repository.search(
                query = query,
                page = SearchPageRequest(
                    cursor = "remote-page-2",
                    limit = 1,
                    excludedListingIds = setOf("listing-1"),
                ),
            ),
        )
        val localCursor = requireNotNull(transition.nextCursor)
        val continuation = assertSuccess(
            repository.search(
                query = query,
                page = SearchPageRequest(
                    cursor = localCursor,
                    limit = 1,
                    excludedListingIds = setOf("listing-1", "listing-2"),
                ),
            ),
        )

        assertEquals(SearchResultSource.LocalCache, transition.source)
        assertEquals(listOf("listing-2"), transition.items.map(ListingSummary::id))
        assertEquals("search-local:v1:1", localCursor)
        assertEquals(listOf("listing-3"), continuation.items.map(ListingSummary::id))
        assertNull(continuation.nextCursor)
        assertEquals(1, catalog.requests.size)
        assertEquals(2, local.readCount)
    }

    @Test
    fun unsafeRemoteContinuationDoesNotFallbackWithoutDisplayedIds() = runTest {
        val networkFailure = DomainError.NetworkUnavailable("error.synthetic.network")
        val catalog = FakeSearchCatalogRepository(DomainResult.Failure(networkFailure))
        val local = FakeSearchLocalCache(candidates = listOf(candidate(listing())))

        val result = searchRepository(catalog, local).search(
            query = validQuery("kwabor"),
            page = SearchPageRequest(cursor = "remote-next"),
        )

        assertEquals(networkFailure, assertIs<DomainResult.Failure>(result).error)
        assertEquals(0, local.readCount)
    }

    @Test
    fun fallbackPreservesNetworkFailureWhenLocalStorageIsUnavailable() = runTest {
        val networkFailure = DomainError.NetworkUnavailable("error.synthetic.network")
        val catalog = FakeSearchCatalogRepository(DomainResult.Failure(networkFailure))
        val local = FakeSearchLocalCache(failure = SQLiteException("Synthetic local failure."))

        val result = searchRepository(catalog, local).search(validQuery("kwabor"))

        assertEquals(networkFailure, assertIs<DomainResult.Failure>(result).error)
    }

    @Test
    fun localContinuationMapsStorageFailureAndPropagatesCancellation() = runTest {
        val catalog = FakeSearchCatalogRepository(DomainResult.Failure(DomainError.Unexpected()))
        val storageFailure = FakeSearchLocalCache(failure = SQLiteException("Synthetic local failure."))
        val page = SearchPageRequest(
            cursor = "search-local:v1:1",
            excludedListingIds = setOf("listing-seen"),
        )

        val result = searchRepository(catalog, storageFailure).search(validQuery("kwabor"), page)

        assertEquals(
            DomainError.LocalStorageUnavailable("error.search.local_cache_unavailable"),
            assertIs<DomainResult.Failure>(result).error,
        )
        assertEquals(0, catalog.requests.size)

        val cancellation = FakeSearchLocalCache(failure = CancellationException("cancelled"))
        assertFailsWith<CancellationException> {
            searchRepository(catalog, cancellation).search(validQuery("kwabor"), page)
        }
    }

    @Test
    fun malformedNetworkPageIsRejected() = runTest {
        val catalog = FakeSearchCatalogRepository(
            DomainResult.Success(
                ListingSummaryPage(
                    items = listOf(listing()),
                    nextCursor = "same-cursor",
                ),
            ),
        )

        val result = searchRepository(catalog, null).search(
            query = validQuery("kwabor"),
            page = SearchPageRequest(cursor = "same-cursor", excludedListingIds = setOf("listing-seen")),
        )

        assertEquals(
            DomainError.Unexpected("error.search.payload_invalid"),
            assertIs<DomainResult.Failure>(result).error,
        )
    }
}

private fun TestScope.searchRepository(
    catalog: CatalogRepository,
    localCache: SearchLocalCache?,
): OfflineFirstSearchRepository = OfflineFirstSearchRepository(
    catalogRepository = catalog,
    localCache = localCache,
    localSearchDispatcher = UnconfinedTestDispatcher(testScheduler),
)

private class FakeSearchLocalCache(
    private val candidates: List<SearchCacheCandidate> = emptyList(),
    private val failure: Throwable? = null,
) : SearchLocalCache {
    var readCount = 0
        private set

    override suspend fun readCandidates(filters: ListingFilters): List<SearchCacheCandidate> {
        readCount += 1
        failure?.let { throwable -> throw throwable }
        return candidates
    }
}

private class FakeSearchCatalogRepository(
    private val searchResult: DomainResult<ListingSummaryPage>,
) : CatalogRepository {
    val requests = mutableListOf<Pair<ListingSearchQuery, ListingPageRequest>>()

    override suspend fun searchListings(
        query: ListingSearchQuery,
        page: ListingPageRequest,
    ): DomainResult<ListingSummaryPage> {
        requests += query to page
        return searchResult
    }

    override suspend fun listCities(): DomainResult<List<City>> = unused()

    override suspend fun listCategories(): DomainResult<List<Category>> = unused()

    override suspend fun listListings(
        filters: ListingFilters,
        page: ListingPageRequest,
    ): DomainResult<ListingSummaryPage> = unused()

    override suspend fun getListingDetail(listingId: String): DomainResult<CatalogDetail> = unused()

    override suspend fun getListingViewerInteraction(listingId: String): DomainResult<ListingViewerInteraction> =
        unused()

    override suspend fun listListingViewerInteractions(
        listingIds: List<String>,
    ): DomainResult<List<ListingViewerInteraction>> = unused()

    override suspend fun likeListing(listingId: String): DomainResult<ListingViewerInteraction> = unused()

    override suspend fun unlikeListing(listingId: String): DomainResult<ListingViewerInteraction> = unused()

    override suspend fun favoriteListing(listingId: String): DomainResult<ListingViewerInteraction> = unused()

    override suspend fun unfavoriteListing(listingId: String): DomainResult<ListingViewerInteraction> = unused()

    private fun <T> unused(): DomainResult<T> = DomainResult.Failure(DomainError.Unexpected("unused"))
}

private fun validQuery(text: String, filters: ListingFilters = ListingFilters()): SearchQuery =
    assertIs<DomainResult.Success<SearchQuery>>(SearchQuery.from(text, filters)).value

private fun assertSuccess(result: DomainResult<SearchResult>): SearchResult =
    assertIs<DomainResult.Success<SearchResult>>(result).value

private fun candidate(
    listing: ListingSummary,
    cityName: String? = "Cotonou",
    categoryNameKey: String? = "category.other",
): SearchCacheCandidate = SearchCacheCandidate(
    listing = listing,
    cityName = cityName,
    categoryNameKey = categoryNameKey,
)

private fun listing(
    id: String = "listing-1",
    name: String = "Kwabor",
    categoryId: String = "restaurants",
): ListingSummary = ListingSummary(
    id = id,
    type = ListingType.Establishment,
    listingClass = ListingClass.Commercial,
    status = ListingStatus.Published,
    name = name,
    cityId = "cotonou",
    categoryId = categoryId,
    coverImageUrl = null,
    priceFromXof = null,
    ratingAverage = null,
    likesCount = 0,
    verified = false,
    sponsoredUntilEpochMilliseconds = null,
)
