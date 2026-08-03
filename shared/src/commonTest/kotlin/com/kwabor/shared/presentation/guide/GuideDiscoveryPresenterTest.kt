package com.kwabor.shared.presentation.guide

import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.guide.GuideDiscoveryFilters
import com.kwabor.shared.domain.guide.GuideDiscoveryRepository
import com.kwabor.shared.domain.guide.GuideFacet
import com.kwabor.shared.domain.guide.GuideFacetType
import com.kwabor.shared.domain.guide.GuidePageRequest
import com.kwabor.shared.domain.guide.GuideSummary
import com.kwabor.shared.domain.guide.GuideSummaryPage
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.money.MoneyXof
import com.kwabor.shared.i18n.stringsFor
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuideDiscoveryPresenterTest {
    private val strings = stringsFor(AppLocale.French).guideDiscovery

    @Test
    fun loadMapsFacetsAndGuideCardWithoutLosingFilterIdentifiers() = runTest {
        val repository = FakeGuideDiscoveryRepository(
            facetsResult = DomainResult.Success(guideFacets()),
            pageResults = mutableListOf(DomainResult.Success(GuideSummaryPage(listOf(guideSummary()), "next"))),
        )
        val presenter = GuideDiscoveryPresenter(repository)

        val state = presenter.load(
            filters = GuideDiscoveryFilters(
                cityId = "ouidah",
                languageId = "francais",
                specialtyId = "histoire",
            ),
            strings = strings,
        )

        assertEquals("ouidah", state.filters.cityId)
        assertEquals(listOf("Ouidah"), state.cityOptions.map(GuideFilterOptionUiModel::label))
        assertEquals(listOf("Français"), state.languageOptions.map(GuideFilterOptionUiModel::label))
        assertEquals(listOf("Histoire"), state.specialtyOptions.map(GuideFilterOptionUiModel::label))
        assertEquals(listOf("Français"), state.guides.single().languages)
        assertEquals(listOf("Ouidah"), state.guides.single().coverageCities)
        assertEquals("4,8", state.guides.single().ratingLabel)
        assertEquals("1 guide affiché", state.resultCountLabel)
        assertEquals("next", state.nextCursor)
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        assertEquals(state.filters, repository.requests.single().first)
    }

    @Test
    fun unavailableFacetSelectionIsRemovedBeforeTheServiceRequest() = runTest {
        val repository = FakeGuideDiscoveryRepository(
            facetsResult = DomainResult.Success(guideFacets()),
            pageResults = mutableListOf(DomainResult.Success(GuideSummaryPage(emptyList(), null))),
        )
        val presenter = GuideDiscoveryPresenter(repository)

        val state = presenter.load(
            GuideDiscoveryFilters(languageId = "unknown-language"),
            strings,
        )

        assertNull(state.filters.languageId)
        assertNull(repository.requests.single().first.languageId)
        assertTrue(state.isEmpty)
        assertEquals("0 guides affichés", state.resultCountLabel)
    }

    @Test
    fun offlineInitialFailureIsExplicitAndDoesNotInventCachedResults() = runTest {
        val repository = FakeGuideDiscoveryRepository(
            facetsResult = DomainResult.Failure(DomainError.NetworkUnavailable()),
        )
        val state = GuideDiscoveryPresenter(repository).load(GuideDiscoveryFilters(), strings)

        assertTrue(state.isOffline)
        assertEquals(strings.loadFailed, state.errorMessage)
        assertTrue(state.guides.isEmpty())
        assertFalse(state.isLoading)
    }

    @Test
    fun refreshFailureKeepsPreviouslyRenderedGuides() = runTest {
        val repository = FakeGuideDiscoveryRepository(
            facetsResult = DomainResult.Failure(DomainError.NetworkUnavailable()),
        )
        val previous = GuideDiscoveryUiState(
            guides = listOf(guideSummary().toTestUiModel()),
            resultCountLabel = "1 guide affiché",
            isLoading = false,
            isRefreshing = true,
        )

        val state = GuideDiscoveryPresenter(repository).refresh(previous, strings)

        assertEquals(previous.guides, state.guides)
        assertTrue(state.isOffline)
        assertEquals(strings.refreshFailed, state.refreshMessage)
        assertNull(state.errorMessage)
        assertFalse(state.isRefreshing)
    }

    @Test
    fun appendRefusesCrossPageDuplicatesInsteadOfSilentlyReorderingThem() = runTest {
        val duplicate = guideSummary()
        val repository = FakeGuideDiscoveryRepository(
            facetsResult = DomainResult.Success(guideFacets()),
            pageResults = mutableListOf(DomainResult.Success(GuideSummaryPage(listOf(duplicate), "repeated"))),
        )
        val previous = GuideDiscoveryUiState(
            guides = listOf(duplicate.toTestUiModel()),
            nextCursor = "next",
            isLoading = false,
            isAppending = true,
        )

        val state = GuideDiscoveryPresenter(repository).append(previous, strings)

        assertEquals(previous.guides, state.guides)
        assertEquals(strings.loadMoreFailed, state.appendErrorMessage)
        assertFalse(state.isAppending)
    }

    @Test
    fun appendRefusesACursorThatDoesNotAdvance() = runTest {
        val repository = FakeGuideDiscoveryRepository(
            facetsResult = DomainResult.Success(guideFacets()),
            pageResults = mutableListOf(
                DomainResult.Success(
                    GuideSummaryPage(
                        items = listOf(guideSummary("b2000000-0000-4000-8000-000000000002")),
                        nextCursor = "next",
                    ),
                ),
            ),
        )
        val previous = GuideDiscoveryUiState(
            guides = listOf(guideSummary().toTestUiModel()),
            nextCursor = "next",
            isLoading = false,
            isAppending = true,
        )

        val state = GuideDiscoveryPresenter(repository).append(previous, strings)

        assertEquals(previous.guides, state.guides)
        assertEquals("next", state.nextCursor)
        assertEquals(strings.loadMoreFailed, state.appendErrorMessage)
        assertFalse(state.isAppending)
    }
}

private class FakeGuideDiscoveryRepository(
    private val facetsResult: DomainResult<List<GuideFacet>>,
    private val pageResults: MutableList<DomainResult<GuideSummaryPage>> = mutableListOf(),
) : GuideDiscoveryRepository {
    val requests = mutableListOf<Pair<GuideDiscoveryFilters, GuidePageRequest>>()

    override suspend fun listFacets(): DomainResult<List<GuideFacet>> = facetsResult

    override suspend fun listServices(
        filters: GuideDiscoveryFilters,
        page: GuidePageRequest,
    ): DomainResult<GuideSummaryPage> {
        requests += filters to page
        return pageResults.removeFirst()
    }
}

internal fun guideFacets(): List<GuideFacet> = listOf(
    GuideFacet(GuideFacetType.City, "ouidah", "Ouidah"),
    GuideFacet(GuideFacetType.Language, "francais", "Français"),
    GuideFacet(GuideFacetType.Specialty, "histoire", "Histoire"),
)

internal fun guideSummary(id: String = "a1000000-0000-4000-8000-000000000001"): GuideSummary = GuideSummary(
    id = id,
    name = "Awa, guide du patrimoine",
    baseCityId = "ouidah",
    baseCityName = "Ouidah",
    coverImageUrl = "https://media.kwabor.test/guide.jpg",
    coverImageAlt = "Portrait officiel de la guide Awa",
    languages = listOf(GuideFacet(GuideFacetType.Language, "francais", "Français")),
    coverageCities = listOf(GuideFacet(GuideFacetType.City, "ouidah", "Ouidah")),
    specialties = listOf(GuideFacet(GuideFacetType.Specialty, "histoire", "Histoire")),
    indicativePriceXof = money(12_000),
    ratingAverage = 4.8,
    ratingCount = 18,
    verified = true,
)

private fun GuideSummary.toTestUiModel(): GuideSummaryUiModel = GuideSummaryUiModel(
    id = id,
    title = name,
    baseCityLabel = baseCityName,
    coverImageUrl = coverImageUrl,
    coverImageAlt = coverImageAlt,
    languages = languages.map(GuideFacet::label),
    coverageCities = coverageCities.map(GuideFacet::label),
    specialties = specialties.map(GuideFacet::label),
    indicativePrice = indicativePriceXof,
    ratingLabel = "4,8",
    ratingCount = ratingCount,
    verified = verified,
)

private fun money(amount: Long): MoneyXof = when (val result = MoneyXof.fromAmount(amount)) {
    is DomainResult.Success -> result.value
    is DomainResult.Failure -> error("Invalid guide test amount")
}
