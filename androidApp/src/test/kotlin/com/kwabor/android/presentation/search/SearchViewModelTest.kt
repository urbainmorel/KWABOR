package com.kwabor.android.presentation.search

import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingStatus
import com.kwabor.shared.domain.catalog.ListingSummary
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.money.KwaborCurrency
import com.kwabor.shared.domain.observability.AnalyticsEvent
import com.kwabor.shared.domain.observability.AnalyticsEventName
import com.kwabor.shared.domain.search.SearchPageRequest
import com.kwabor.shared.domain.search.SearchQuery
import com.kwabor.shared.domain.search.SearchRepository
import com.kwabor.shared.domain.search.SearchResult
import com.kwabor.shared.domain.search.SearchResultSource
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.explore.initialExploreUiState
import com.kwabor.shared.presentation.search.SearchPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val strings = stringsFor(AppLocale.French)

    @Test
    fun validSubmissionTracksMetadataWithoutFreeText() = runTest {
        val trackedEvents = mutableListOf<AnalyticsEvent>()
        val viewModelScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val viewModel = createViewModel(viewModelScope, trackedEvents::add)
        viewModelScope.launch { viewModel.effects.collect() }

        viewModel.onIntent(
            SearchIntent.Activate(initialExploreUiState(strings).copy(currency = KwaborCurrency.Eur)),
        )
        viewModel.onIntent(SearchIntent.QueryChanged("  Ouidah  "))
        viewModel.onIntent(SearchIntent.Submit)
        advanceUntilIdle()

        val event = trackedEvents.single()
        assertEquals(AnalyticsEventName.SearchQuery, event.name)
        assertNull(event.context.entityId)
        assertNull(event.context.cityId)
        assertEquals(KwaborCurrency.Eur, event.context.displayCurrency)
        assertEquals("Ouidah", viewModel.state.value.submittedQueryText)
        viewModelScope.cancel()
    }

    @Test
    fun invalidSubmissionDoesNotTrackOrCallRepository() = runTest {
        val repository = RecordingSearchRepository()
        val trackedEvents = mutableListOf<AnalyticsEvent>()
        val viewModelScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val viewModel = createViewModel(viewModelScope, trackedEvents::add, repository)
        viewModelScope.launch { viewModel.effects.collect() }

        viewModel.onIntent(SearchIntent.Activate(initialExploreUiState(strings)))
        viewModel.onIntent(SearchIntent.QueryChanged("   "))
        viewModel.onIntent(SearchIntent.Submit)
        advanceUntilIdle()

        assertTrue(trackedEvents.isEmpty())
        assertEquals(0, repository.requestCount)
        assertEquals(strings.search.invalidQuery, viewModel.state.value.queryErrorMessage)
        viewModelScope.cancel()
    }

    @Test
    fun listingEffectIsForwardedOnlyForAnItemInCurrentResults() = runTest {
        val viewModelScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val viewModel = createViewModel(viewModelScope)

        viewModel.onIntent(SearchIntent.Activate(initialExploreUiState(strings)))
        viewModel.onIntent(SearchIntent.QueryChanged("Ouidah"))
        viewModel.onIntent(SearchIntent.Submit)
        advanceUntilIdle()
        val effect = async { viewModel.effects.first() }

        viewModel.onIntent(SearchIntent.OpenListing("unknown"))
        advanceUntilIdle()
        assertFalse(effect.isCompleted)

        viewModel.onIntent(SearchIntent.OpenListing(TEST_LISTING_ID))
        advanceUntilIdle()

        assertEquals(SearchEffect.OpenCatalogDetail(TEST_LISTING_ID), effect.await())
        viewModelScope.cancel()
    }

    private fun createViewModel(
        coroutineScope: CoroutineScope,
        track: (AnalyticsEvent) -> Unit = {},
        repository: SearchRepository = RecordingSearchRepository(),
    ): SearchViewModel = SearchViewModel(
        presenter = SearchPresenter(repository),
        strings = strings.search,
        coroutineScope = coroutineScope,
        track = track,
    )
}

private class RecordingSearchRepository : SearchRepository {
    var requestCount = 0
        private set

    override suspend fun search(query: SearchQuery, page: SearchPageRequest): DomainResult<SearchResult> {
        requestCount += 1
        return DomainResult.Success(
            SearchResult(
                items = listOf(testListing),
                nextCursor = null,
                source = SearchResultSource.Network,
            ),
        )
    }
}

private val testListing = ListingSummary(
    id = TEST_LISTING_ID,
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

private const val TEST_LISTING_ID = "listing-search"
