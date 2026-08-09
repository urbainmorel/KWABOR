package com.kwabor.android.presentation.favorites

import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingPageRequest
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.favorites.FavoriteListing
import com.kwabor.shared.domain.favorites.FavoriteListingPage
import com.kwabor.shared.domain.favorites.FavoriteMutation
import com.kwabor.shared.domain.favorites.FavoritesRepository
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.favorites.FavoritesIntent
import com.kwabor.shared.presentation.favorites.FavoritesPresenter
import com.kwabor.shared.presentation.session.ViewerSessionScope
import com.kwabor.shared.presentation.session.ViewerSessionScopeTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesViewModelTest {
    private val strings = stringsFor(AppLocale.French)
    private val viewerSessionScopeTracker = ViewerSessionScopeTracker()

    @Test
    fun viewerContextChange_purgesPreviousAccountStateSynchronously() = runTest {
        val viewModel = viewModel(FakeFavoritesRepository())
        viewModel.onIntent(FavoritesIntent.ViewerContextChanged(authenticatedScope(TEST_ACCOUNT_A)))
        viewModel.onIntent(FavoritesIntent.ScreenAppeared)
        advanceUntilIdle()
        assertEquals(listOf(TEST_LISTING_ID), viewModel.state.value.items.map { item -> item.id })

        viewModel.onIntent(FavoritesIntent.ViewerContextChanged(authenticatedScope(TEST_ACCOUNT_B)))

        assertTrue(viewModel.state.value.items.isEmpty())
        assertTrue(viewModel.state.value.isAccountReady)
    }

    @Test
    fun removeAndOpen_mapRuntimeEffectsForApplicationBindings() = runTest {
        val viewModel = viewModel(FakeFavoritesRepository())
        val scope = authenticatedScope(TEST_ACCOUNT_A)
        viewModel.onIntent(FavoritesIntent.ViewerContextChanged(scope))
        viewModel.onIntent(FavoritesIntent.ScreenAppeared)
        advanceUntilIdle()

        viewModel.onIntent(FavoritesIntent.OpenListing(TEST_LISTING_ID))
        advanceUntilIdle()
        val open = assertIs<FavoritesEffect.OpenCatalogDetail>(viewModel.effects.first())
        assertEquals(TEST_LISTING_ID, open.listingId)
        assertEquals(scope, open.scope)

        viewModel.onIntent(FavoritesIntent.RemoveFavorite(TEST_LISTING_ID))
        advanceUntilIdle()
        val changed = assertIs<FavoritesEffect.FavoriteChanged>(viewModel.effects.first())
        assertEquals(TEST_LISTING_ID, changed.listingId)
        assertFalse(changed.favorited)
        assertEquals(TEST_CLIENT_MUTATION_SEQUENCE, changed.clientMutationSequence)
        assertEquals(scope, changed.scope)
        assertTrue(viewModel.state.value.items.isEmpty())
    }

    @Test
    fun routeReentry_refreshesAlreadyLoadedFavorites() = runTest {
        val repository = FakeFavoritesRepository()
        val viewModel = viewModel(repository)
        viewModel.onIntent(FavoritesIntent.ViewerContextChanged(authenticatedScope(TEST_ACCOUNT_A)))
        viewModel.onIntent(FavoritesIntent.ScreenAppeared)
        advanceUntilIdle()
        assertEquals(1, repository.listRequestCount)

        viewModel.onIntent(FavoritesIntent.ScreenDisappeared)
        viewModel.onIntent(FavoritesIntent.ScreenAppeared)
        advanceUntilIdle()

        assertEquals(2, repository.listRequestCount)
    }

    @Test
    fun bufferedPreviousAccountEffect_isDroppedAfterScopeChanges() = runTest {
        val viewModel = viewModel(FakeFavoritesRepository())
        viewModel.onIntent(FavoritesIntent.ViewerContextChanged(authenticatedScope(TEST_ACCOUNT_A)))
        viewModel.onIntent(FavoritesIntent.ScreenAppeared)
        advanceUntilIdle()
        viewModel.onIntent(FavoritesIntent.OpenListing(TEST_LISTING_ID))
        advanceUntilIdle()

        viewModel.onIntent(FavoritesIntent.ViewerContextChanged(authenticatedScope(TEST_ACCOUNT_B)))
        val observed = mutableListOf<FavoritesEffect>()
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.effects.toList(observed)
        }
        advanceUntilIdle()

        assertTrue(observed.isEmpty())
        collection.cancel()
    }

    private fun authenticatedScope(accountId: String): ViewerSessionScope =
        viewerSessionScopeTracker.update(accountId, accountSetupComplete = true)

    private fun kotlinx.coroutines.test.TestScope.viewModel(repository: FavoritesRepository): FavoritesViewModel {
        val viewModelScope = CoroutineScope(SupervisorJob() + coroutineContext.minusKey(Job))
        requireNotNull(coroutineContext[Job]).invokeOnCompletion { viewModelScope.cancel() }
        return FavoritesViewModel(
            presenter = FavoritesPresenter(repository),
            strings = strings.favorites,
            coroutineScope = viewModelScope,
            viewerSessionScopeTracker = viewerSessionScopeTracker,
        )
    }
}

private class FakeFavoritesRepository : FavoritesRepository {
    var listRequestCount: Int = 0
        private set
    private var clientMutationSequence = TEST_CLIENT_MUTATION_SEQUENCE - 1L

    override suspend fun listFavorites(
        filter: ListingType?,
        page: ListingPageRequest,
    ): DomainResult<FavoriteListingPage> {
        listRequestCount += 1
        return DomainResult.Success(
            FavoriteListingPage(
                items = listOf(testFavoriteListing()).filter { listing -> filter == null || listing.type == filter },
                nextCursor = null,
            ),
        )
    }

    override suspend fun setFavorite(listingId: String, favorited: Boolean): DomainResult<FavoriteMutation> {
        val sequence = ++clientMutationSequence
        return DomainResult.Success(
            FavoriteMutation(
                listingId = listingId,
                favorited = favorited,
                clientMutationSequence = sequence,
                favoritedAtEpochMilliseconds = if (favorited) TEST_FAVORITED_AT else null,
            ),
        )
    }
}

private fun testFavoriteListing(): FavoriteListing = FavoriteListing(
    id = TEST_LISTING_ID,
    type = ListingType.Place,
    listingClass = ListingClass.Heritage,
    name = "Porte du non-retour",
    cityId = "ouidah",
    cityName = "Ouidah",
    categoryId = "heritage",
    coverImageUrl = null,
    coverImageAlt = null,
    priceFromXof = null,
    ratingAverage = null,
    likesCount = 7,
    verified = true,
    likedByViewer = true,
    favoritedAtEpochMilliseconds = TEST_FAVORITED_AT,
    eventStartAtEpochMilliseconds = null,
    eventEndAtEpochMilliseconds = null,
    isEventEnded = false,
)

private const val TEST_ACCOUNT_A = "00000000-0000-4000-8000-000000000001"
private const val TEST_ACCOUNT_B = "00000000-0000-4000-8000-000000000002"
private const val TEST_LISTING_ID = "00000000-0000-4000-8000-000000000003"
private const val TEST_FAVORITED_AT = 1_000L
private const val TEST_CLIENT_MUTATION_SEQUENCE = 4_294_967_297L
