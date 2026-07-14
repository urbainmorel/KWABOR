package com.kwabor.android.presentation.explore

import com.kwabor.shared.domain.catalog.CatalogRepository
import com.kwabor.shared.domain.catalog.Category
import com.kwabor.shared.domain.catalog.City
import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingDetail
import com.kwabor.shared.domain.catalog.ListingFilters
import com.kwabor.shared.domain.catalog.ListingSearchQuery
import com.kwabor.shared.domain.catalog.ListingStatus
import com.kwabor.shared.domain.catalog.ListingSummary
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.catalog.ListingViewerInteraction
import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.core.PageRequest
import com.kwabor.shared.domain.core.PageResult
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.explore.ExplorePresenter
import com.kwabor.shared.presentation.explore.ExploreTab
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreViewModelTest {
    private val strings = stringsFor(AppLocale.French)

    @Test
    fun selectTab_reloadsExploreAndReducesImmutableState() = runTest {
        val repository = ViewModelCatalogRepository()
        val viewModel = viewModel(repository)
        advanceUntilIdle()

        viewModel.onIntent(ExploreIntent.SelectTab(ExploreTab.Events))
        advanceUntilIdle()

        assertEquals(ExploreTab.Events, viewModel.state.value.selectedTab)
        assertEquals(ListingType.Event, repository.lastFilters?.listingType)
        assertFalse(viewModel.state.value.isLoading)
    }

    @Test
    fun authRequired_emitsEffectAndCanBeClearedForGuest() = runTest {
        val repository = ViewModelCatalogRepository(requiresAuthentication = true)
        val viewModel = viewModel(repository)
        advanceUntilIdle()

        viewModel.onIntent(ExploreIntent.ToggleLike(TEST_LISTING_ID))
        advanceUntilIdle()

        assertIs<ExploreEffect.AuthenticationRequired>(viewModel.effects.first())
        assertEquals(TEST_LISTING_ID, viewModel.state.value.pendingAuthInteraction?.listingId)

        viewModel.onIntent(ExploreIntent.ContinueAsGuest)

        assertNull(viewModel.state.value.pendingAuthInteraction)
        assertNull(viewModel.state.value.interactionMessage)
    }

    @Test
    fun replayPendingInteraction_appliesAuthenticatedResult() = runTest {
        val repository = ViewModelCatalogRepository(requiresAuthentication = true)
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        viewModel.onIntent(ExploreIntent.ToggleLike(TEST_LISTING_ID))
        advanceUntilIdle()
        viewModel.effects.first()

        repository.requiresAuthentication = false
        viewModel.onIntent(ExploreIntent.ReplayPendingInteraction)
        advanceUntilIdle()

        assertNull(viewModel.state.value.pendingAuthInteraction)
        assertTrue(viewModel.state.value.listings.single().liked)
        assertEquals(1, viewModel.state.value.listings.single().likesCount)
    }

    private fun kotlinx.coroutines.test.TestScope.viewModel(repository: ViewModelCatalogRepository): ExploreViewModel =
        ExploreViewModel(
            presenter = ExplorePresenter(repository, FixedViewModelClock),
            strings = strings,
            coroutineScope = this,
        )
}

private class ViewModelCatalogRepository(
    var requiresAuthentication: Boolean = false,
) : CatalogRepository {
    var lastFilters: ListingFilters? = null
        private set

    override suspend fun listCities(): DomainResult<List<City>> = DomainResult.Success(
        listOf(City(id = "cotonou", name = "Cotonou")),
    )

    override suspend fun listCategories(): DomainResult<List<Category>> = DomainResult.Success(emptyList())

    override suspend fun listListings(
        filters: ListingFilters,
        page: PageRequest,
    ): DomainResult<PageResult<ListingSummary>> {
        lastFilters = filters
        return DomainResult.Success(PageResult(items = listOf(testListing()), nextOffset = null))
    }

    override suspend fun searchListings(
        query: ListingSearchQuery,
        page: PageRequest,
    ): DomainResult<PageResult<ListingSummary>> = DomainResult.Success(PageResult(emptyList(), null))

    override suspend fun getListingDetail(listingId: String): DomainResult<ListingDetail> =
        DomainResult.Failure(DomainError.NotFound("error.catalog.not_found"))

    override suspend fun getListingViewerInteraction(listingId: String): DomainResult<ListingViewerInteraction> =
        interaction(listingId)

    override suspend fun listListingViewerInteractions(
        listingIds: List<String>,
    ): DomainResult<List<ListingViewerInteraction>> = if (requiresAuthentication) {
        DomainResult.Failure(DomainError.AuthenticationRequired("error.auth.required"))
    } else {
        DomainResult.Success(emptyList())
    }

    override suspend fun likeListing(listingId: String): DomainResult<ListingViewerInteraction> = interaction(listingId)

    override suspend fun unlikeListing(listingId: String): DomainResult<ListingViewerInteraction> = interaction(
        listingId,
    )

    override suspend fun favoriteListing(listingId: String): DomainResult<ListingViewerInteraction> =
        interaction(listingId)

    override suspend fun unfavoriteListing(listingId: String): DomainResult<ListingViewerInteraction> =
        interaction(listingId)

    private fun interaction(listingId: String): DomainResult<ListingViewerInteraction> = if (requiresAuthentication) {
        DomainResult.Failure(DomainError.AuthenticationRequired("error.auth.required"))
    } else {
        DomainResult.Success(
            ListingViewerInteraction(
                listingId = listingId,
                likedByViewer = true,
                favoritedByViewer = false,
                likesCount = 1,
            ),
        )
    }
}

private object FixedViewModelClock : ClockProvider {
    override fun nowEpochMilliseconds(): Long = 1_000L
}

private fun testListing(): ListingSummary = ListingSummary(
    id = TEST_LISTING_ID,
    type = ListingType.Place,
    listingClass = ListingClass.Heritage,
    status = ListingStatus.Published,
    name = "Porte du non-retour",
    cityId = "cotonou",
    categoryId = "heritage-historique",
    coverImageUrl = null,
    priceFromXof = null,
    ratingAverage = null,
    likesCount = 0,
    verified = true,
    sponsoredUntilEpochMilliseconds = null,
)

private const val TEST_LISTING_ID = "ouidah-gate"
