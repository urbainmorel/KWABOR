package com.kwabor.shared.presentation.guide

import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.guide.GuideDiscoveryFilters
import com.kwabor.shared.domain.guide.GuideDiscoveryRepository
import com.kwabor.shared.domain.guide.GuideFacet
import com.kwabor.shared.domain.guide.GuidePageRequest
import com.kwabor.shared.domain.guide.GuideSummaryPage
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.stringsFor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GuideDiscoveryRuntimeTest {
    private val strings = stringsFor(AppLocale.French).guideDiscovery

    @Test
    fun startIsExplicitAndIdempotent() = runTest {
        val repository = RecordingGuideRepository()
        val runtime = GuideDiscoveryRuntime(GuideDiscoveryPresenter(repository), strings, this)
        advanceUntilIdle()

        assertTrue(repository.requests.isEmpty())
        assertTrue(runtime.state.value.isLoading)

        runtime.dispatch(GuideDiscoveryIntent.Start)
        runtime.dispatch(GuideDiscoveryIntent.Start)
        advanceUntilIdle()

        assertEquals(1, repository.requests.size)
        runtime.close()
    }

    @Test
    fun selectingAFilterReloadsTheFirstPageWithTheCanonicalFacetId() = runTest {
        val repository = RecordingGuideRepository()
        val runtime = GuideDiscoveryRuntime(GuideDiscoveryPresenter(repository), strings, this)
        runtime.dispatch(GuideDiscoveryIntent.Start)
        advanceUntilIdle()

        runtime.dispatch(GuideDiscoveryIntent.SelectLanguage("francais"))
        advanceUntilIdle()

        assertEquals(2, repository.requests.size)
        assertNull(repository.requests.first().first.languageId)
        assertEquals("francais", repository.requests.last().first.languageId)
        assertNull(repository.requests.last().second.cursor)
        assertEquals("francais", runtime.state.value.filters.languageId)
        runtime.close()
    }

    @Test
    fun openGuidePublishesOnlyAnIdentifierPresentInTheCurrentPage() = runTest {
        val runtime = GuideDiscoveryRuntime(
            presenter = GuideDiscoveryPresenter(RecordingGuideRepository()),
            strings = strings,
            coroutineScope = this,
        )
        runtime.dispatch(GuideDiscoveryIntent.Start)
        advanceUntilIdle()
        val effect = async { runtime.effects.first() }

        runtime.dispatch(GuideDiscoveryIntent.OpenGuide("unknown"))
        runtime.dispatch(GuideDiscoveryIntent.OpenGuide(GUIDE_ID))
        advanceUntilIdle()

        assertEquals(GuideDiscoveryEffect.OpenCatalogDetail(GUIDE_ID), effect.await())
        runtime.close()
    }

    @Test
    fun repeatedLoadNextWhileAppendingStartsOnlyOneCursorRequest() = runTest {
        val repository = RecordingGuideRepository(nextCursor = "next")
        val runtime = GuideDiscoveryRuntime(GuideDiscoveryPresenter(repository), strings, this)
        runtime.dispatch(GuideDiscoveryIntent.Start)
        advanceUntilIdle()

        runtime.dispatch(GuideDiscoveryIntent.LoadNext)
        runtime.dispatch(GuideDiscoveryIntent.LoadNext)
        advanceUntilIdle()

        assertEquals(2, repository.requests.size)
        assertEquals(listOf(null, "next"), repository.requests.map { request -> request.second.cursor })
        runtime.close()
    }

    @Test
    fun aCancelledOlderRequestCannotReplaceTheStateOfANewerFilter() = runTest {
        val repository = OutOfOrderGuideRepository()
        val runtime = GuideDiscoveryRuntime(GuideDiscoveryPresenter(repository), strings, this)

        runtime.dispatch(GuideDiscoveryIntent.Start)
        repository.initialRequestStarted.await()
        runtime.dispatch(GuideDiscoveryIntent.SelectLanguage("francais"))
        advanceUntilIdle()

        assertEquals("francais", runtime.state.value.filters.languageId)
        assertEquals(NEW_GUIDE_ID, runtime.state.value.guides.single().id)

        repository.completeInitialRequest()
        advanceUntilIdle()

        assertEquals("francais", runtime.state.value.filters.languageId)
        assertEquals(NEW_GUIDE_ID, runtime.state.value.guides.single().id)
        runtime.close()
    }
}

private class OutOfOrderGuideRepository : GuideDiscoveryRepository {
    val initialRequestStarted = CompletableDeferred<Unit>()
    private val initialPage = CompletableDeferred<GuideSummaryPage>()

    override suspend fun listFacets(): DomainResult<List<GuideFacet>> = DomainResult.Success(guideFacets())

    override suspend fun listServices(
        filters: GuideDiscoveryFilters,
        page: GuidePageRequest,
    ): DomainResult<GuideSummaryPage> {
        if (filters.languageId == null) {
            initialRequestStarted.complete(Unit)
            return withContext(NonCancellable) {
                DomainResult.Success(initialPage.await())
            }
        }
        return DomainResult.Success(GuideSummaryPage(listOf(guideSummary(NEW_GUIDE_ID)), null))
    }

    fun completeInitialRequest() {
        initialPage.complete(GuideSummaryPage(listOf(guideSummary(OLD_GUIDE_ID)), null))
    }
}

private class RecordingGuideRepository(
    private val nextCursor: String? = null,
) : GuideDiscoveryRepository {
    val requests = mutableListOf<Pair<GuideDiscoveryFilters, GuidePageRequest>>()

    override suspend fun listFacets(): DomainResult<List<GuideFacet>> = DomainResult.Success(guideFacets())

    override suspend fun listServices(
        filters: GuideDiscoveryFilters,
        page: GuidePageRequest,
    ): DomainResult<GuideSummaryPage> {
        requests += filters to page
        val items = if (page.cursor == null) listOf(guideSummary(GUIDE_ID)) else emptyList()
        return DomainResult.Success(
            GuideSummaryPage(
                items = items,
                nextCursor = if (page.cursor == null) nextCursor else null,
            ),
        )
    }
}

private const val GUIDE_ID = "a1000000-0000-4000-8000-000000000001"
private const val OLD_GUIDE_ID = "b2000000-0000-4000-8000-000000000002"
private const val NEW_GUIDE_ID = "c3000000-0000-4000-8000-000000000003"
