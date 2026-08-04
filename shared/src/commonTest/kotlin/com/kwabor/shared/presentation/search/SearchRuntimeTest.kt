package com.kwabor.shared.presentation.search

import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.money.KwaborCurrency
import com.kwabor.shared.domain.search.SearchPageRequest
import com.kwabor.shared.domain.search.SearchQuery
import com.kwabor.shared.domain.search.SearchRepository
import com.kwabor.shared.domain.search.SearchResult
import com.kwabor.shared.i18n.stringsFor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SearchRuntimeTest {
    private val strings = stringsFor(AppLocale.French).search

    @Test
    fun submitIsExplicitAndPublishesNoRawQueryEffect() = runTest {
        val repository = RecordingSearchRepository(mutableListOf(networkResult()))
        val runtime = SearchRuntime(SearchPresenter(repository), strings, this)
        runtime.dispatch(
            SearchIntent.Activate(activeState("x").context.copy(currency = KwaborCurrency.Eur)),
        )
        runtime.dispatch(SearchIntent.QueryChanged("  Ouidah  "))
        val effect = async { runtime.effects.first() }

        runtime.dispatch(SearchIntent.Submit)
        advanceUntilIdle()

        assertEquals(SearchEffect.QuerySubmitted(KwaborCurrency.Eur), effect.await())
        assertEquals("Ouidah", runtime.state.value.submittedQueryText)
        assertEquals(SEARCH_LISTING_ID, runtime.state.value.listings.single().id)
        runtime.close()
    }

    @Test
    fun blankSubmitShowsValidationWithoutPublishingSubmission() = runTest {
        val repository = RecordingSearchRepository()
        val runtime = SearchRuntime(SearchPresenter(repository), strings, this)
        runtime.dispatch(SearchIntent.Activate(activeState("x").context))
        advanceUntilIdle()

        runtime.dispatch(SearchIntent.Submit)
        advanceUntilIdle()

        assertEquals(strings.invalidQuery, runtime.state.value.queryErrorMessage)
        assertTrue(repository.requests.isEmpty())
        runtime.close()
    }

    @Test
    fun editingCancelsAnOlderResultEvenWhenTheRepositoryIgnoresCancellation() = runTest {
        val repository = OutOfOrderSearchRepository()
        val runtime = SearchRuntime(SearchPresenter(repository), strings, this)
        runtime.dispatch(SearchIntent.Activate(activeState("x").context))
        runtime.dispatch(SearchIntent.QueryChanged("ancienne"))
        runtime.dispatch(SearchIntent.Submit)
        repository.firstRequestStarted.await()

        runtime.dispatch(SearchIntent.QueryChanged("nouvelle"))
        runtime.dispatch(SearchIntent.Submit)
        advanceUntilIdle()

        assertEquals("nouvelle", runtime.state.value.submittedQueryText)
        assertEquals(SEARCH_SECOND_LISTING_ID, runtime.state.value.listings.single().id)

        repository.completeFirstRequest()
        advanceUntilIdle()

        assertEquals("nouvelle", runtime.state.value.submittedQueryText)
        assertEquals(SEARCH_SECOND_LISTING_ID, runtime.state.value.listings.single().id)
        runtime.close()
    }

    @Test
    fun switchingToAllScopeReloadsWithoutTabOrChipFilters() = runTest {
        val repository = RecordingSearchRepository(
            mutableListOf(networkResult(), networkResult()),
        )
        val runtime = SearchRuntime(SearchPresenter(repository), strings, this)
        runtime.dispatch(SearchIntent.Activate(activeState("x").context))
        runtime.dispatch(SearchIntent.QueryChanged("Ouidah"))
        runtime.dispatch(SearchIntent.Submit)
        advanceUntilIdle()

        runtime.dispatch(SearchIntent.SelectScope(SearchScope.All))
        advanceUntilIdle()

        assertEquals(2, repository.requests.size)
        assertNull(repository.requests.last().first.filters.listingType)
        assertNull(repository.requests.last().first.filters.categoryId)
        assertEquals(SearchScope.All, runtime.state.value.scope)
        runtime.close()
    }

    @Test
    fun contextChangeDuringFirstLoadCancelsTheStaleScopeAndReloads() = runTest {
        val repository = ContextSwitchSearchRepository()
        val runtime = SearchRuntime(SearchPresenter(repository), strings, this)
        val initialContext = activeState("x").context
        val updatedContext = initialContext.copy(selectedCityId = "ouidah")
        runtime.dispatch(SearchIntent.Activate(initialContext))
        runtime.dispatch(SearchIntent.QueryChanged("musée"))
        runtime.dispatch(SearchIntent.Submit)
        repository.firstRequestStarted.await()

        runtime.dispatch(SearchIntent.UpdateContext(updatedContext))
        advanceUntilIdle()

        assertEquals(2, repository.requestCount)
        assertEquals(updatedContext, runtime.state.value.context)
        assertEquals(SEARCH_SECOND_LISTING_ID, runtime.state.value.listings.single().id)

        repository.completeFirstRequest()
        advanceUntilIdle()

        assertEquals(updatedContext, runtime.state.value.context)
        assertEquals(SEARCH_SECOND_LISTING_ID, runtime.state.value.listings.single().id)
        runtime.close()
    }

    @Test
    fun openListingPublishesOnlyAnIdentifierPresentInResults() = runTest {
        val repository = RecordingSearchRepository(mutableListOf(networkResult()))
        val runtime = SearchRuntime(SearchPresenter(repository), strings, this)
        runtime.dispatch(SearchIntent.Activate(activeState("x").context))
        runtime.dispatch(SearchIntent.QueryChanged("Ouidah"))
        runtime.dispatch(SearchIntent.Submit)
        advanceUntilIdle()
        val effect = async { runtime.effects.first { it is SearchEffect.OpenCatalogDetail } }

        runtime.dispatch(SearchIntent.OpenListing("unknown"))
        runtime.dispatch(SearchIntent.OpenListing(SEARCH_LISTING_ID))
        advanceUntilIdle()

        assertEquals(SearchEffect.OpenCatalogDetail(SEARCH_LISTING_ID), effect.await())
        runtime.close()
    }

    @Test
    fun clearCancelsWorkAndKeepsSearchActiveWithoutStaleResults() = runTest {
        val repository = RecordingSearchRepository(mutableListOf(networkResult()))
        val runtime = SearchRuntime(SearchPresenter(repository), strings, this)
        runtime.dispatch(SearchIntent.Activate(activeState("x").context))
        runtime.dispatch(SearchIntent.QueryChanged("Ouidah"))
        runtime.dispatch(SearchIntent.Submit)
        advanceUntilIdle()

        runtime.dispatch(SearchIntent.Clear)
        runCurrent()

        assertTrue(runtime.state.value.isActive)
        assertEquals("", runtime.state.value.queryText)
        assertTrue(runtime.state.value.listings.isEmpty())
        assertFalse(runtime.state.value.hasSubmittedQuery)
        runtime.close()
    }
}

private class OutOfOrderSearchRepository : SearchRepository {
    val firstRequestStarted = CompletableDeferred<Unit>()
    private val firstResult = CompletableDeferred<SearchResult>()

    override suspend fun search(query: SearchQuery, page: SearchPageRequest): DomainResult<SearchResult> {
        if (query.text == "ancienne") {
            firstRequestStarted.complete(Unit)
            return withContext(NonCancellable) {
                DomainResult.Success(firstResult.await())
            }
        }
        return DomainResult.Success(
            requireNotNull(networkResult(id = SEARCH_SECOND_LISTING_ID).valueOrNull()),
        )
    }

    fun completeFirstRequest() {
        firstResult.complete(requireNotNull(networkResult().valueOrNull()))
    }
}

private class ContextSwitchSearchRepository : SearchRepository {
    val firstRequestStarted = CompletableDeferred<Unit>()
    private val firstResult = CompletableDeferred<SearchResult>()
    var requestCount = 0
        private set

    override suspend fun search(query: SearchQuery, page: SearchPageRequest): DomainResult<SearchResult> {
        requestCount += 1
        if (requestCount == 1) {
            firstRequestStarted.complete(Unit)
            return withContext(NonCancellable) {
                DomainResult.Success(firstResult.await())
            }
        }
        return DomainResult.Success(
            requireNotNull(networkResult(id = SEARCH_SECOND_LISTING_ID).valueOrNull()),
        )
    }

    fun completeFirstRequest() {
        firstResult.complete(requireNotNull(networkResult().valueOrNull()))
    }
}

private fun DomainResult<SearchResult>.valueOrNull(): SearchResult? = when (this) {
    is DomainResult.Success -> value
    is DomainResult.Failure -> null
}
