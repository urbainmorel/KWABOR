package com.kwabor.shared.presentation.explore

import app.cash.turbine.test
import com.kwabor.shared.domain.catalog.CatalogInteractionRepository
import com.kwabor.shared.domain.catalog.Category
import com.kwabor.shared.domain.catalog.City
import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingStatus
import com.kwabor.shared.domain.catalog.ListingSummary
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.catalog.ListingViewerInteraction
import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.explore.ExploreFeedQuery
import com.kwabor.shared.domain.explore.ExploreFeedRepository
import com.kwabor.shared.domain.explore.ExploreFeedSnapshot
import com.kwabor.shared.domain.explore.ExploreFeedSource
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.money.KwaborCurrency
import com.kwabor.shared.domain.preferences.AppPreferences
import com.kwabor.shared.domain.preferences.AppPreferencesRepository
import com.kwabor.shared.i18n.stringsFor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreRuntimeTest {
    private val strings = stringsFor(AppLocale.French)

    @Test
    fun loadNext_appendsWithoutReplacingVisibleItems() = runTest {
        val feedRepository = RuntimeFeedRepository(
            refreshSnapshot = runtimeSnapshot(nextCursor = "cursor-1"),
            appendSnapshot = runtimeSnapshot(
                items = listOf(runtimeListing(), runtimeListing(id = "listing-2")),
            ),
        )
        val runtime = runtime(feedRepository = feedRepository)
        advanceUntilIdle()

        runtime.dispatch(ExploreIntent.LoadNext)
        advanceUntilIdle()

        assertEquals(listOf(RUNTIME_LISTING_ID, "listing-2"), runtime.state.value.listings.map { it.id })
        assertNull(runtime.state.value.nextCursor)
        assertFalse(runtime.state.value.isAppending)
        runtime.close()
    }

    @Test
    fun refresh_preservesInteractionCompletedWhileNetworkIsInFlight() = runTest {
        val refreshGate = CompletableDeferred<Unit>()
        val feedRepository = RuntimeFeedRepository()
        val interactions = RuntimeInteractionRepository()
        val runtime = runtime(feedRepository, interactions)
        advanceUntilIdle()
        feedRepository.refreshGate = refreshGate

        runtime.dispatch(ExploreIntent.Refresh)
        runCurrent()
        assertTrue(runtime.state.value.isRefreshing)

        runtime.dispatch(ExploreIntent.ToggleLike(RUNTIME_LISTING_ID))
        runCurrent()
        assertTrue(runtime.state.value.listings.single().liked)

        refreshGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(runtime.state.value.listings.single().liked)
        assertEquals(1, runtime.state.value.listings.single().likesCount)
        assertFalse(runtime.state.value.isRefreshing)
        runtime.close()
    }

    @Test
    fun authenticationFailure_afterFeedContextChangeIsDiscarded() = runTest {
        val interactionGate = CompletableDeferred<Unit>()
        val interactions = RuntimeInteractionRepository(
            requiresAuthentication = true,
            interactionGate = interactionGate,
        )
        val runtime = runtime(interactions = interactions)
        advanceUntilIdle()

        runtime.effects.test {
            runtime.dispatch(ExploreIntent.ToggleLike(RUNTIME_LISTING_ID))
            runCurrent()
            runtime.dispatch(ExploreIntent.SelectTab(ExploreTab.Events))
            runCurrent()
            interactionGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(ExploreTab.Events, runtime.state.value.selectedTab)
            assertNull(runtime.state.value.pendingAuthInteraction)
            assertNull(runtime.state.value.interactionMessage)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        runtime.close()
    }

    @Test
    fun consecutiveTabSelections_applyTheLatestIntent() = runTest {
        val runtime = runtime()
        advanceUntilIdle()

        runtime.dispatch(ExploreIntent.SelectTab(ExploreTab.Events))
        runtime.dispatch(ExploreIntent.SelectTab(ExploreTab.Places))
        advanceUntilIdle()

        assertEquals(ExploreTab.Places, runtime.state.value.selectedTab)
        runtime.close()
    }

    @Test
    fun consecutiveChipSelections_applyTheLatestToggle() = runTest {
        val category = Category(
            id = "heritage-historique",
            nameKey = "category.heritage.historique",
            listingType = ListingType.Place,
            defaultListingClass = ListingClass.Heritage,
        )
        val runtime = runtime(
            feedRepository = RuntimeFeedRepository(
                refreshSnapshot = runtimeSnapshot(categories = listOf(category)),
            ),
        )
        advanceUntilIdle()

        runtime.dispatch(ExploreIntent.SelectChip(category.id))
        runtime.dispatch(ExploreIntent.SelectChip(category.id))
        advanceUntilIdle()

        assertNull(runtime.state.value.selectedChipId)
        runtime.close()
    }

    @Test
    fun pendingTabPreparation_keepsThePublishedFeedConsistentAndCanBeRetriedAfterRefresh() = runTest {
        val preferences = RuntimePreferencesRepository()
        val runtime = runtime(preferences = preferences)
        advanceUntilIdle()
        val preparationGate = CompletableDeferred<Unit>()
        preferences.getGate = preparationGate

        runtime.dispatch(ExploreIntent.SelectTab(ExploreTab.Events))
        runCurrent()

        assertEquals(ExploreTab.Places, runtime.state.value.selectedTab)
        assertEquals(listOf(RUNTIME_LISTING_ID), runtime.state.value.listings.map { it.id })

        runtime.dispatch(ExploreIntent.Refresh)
        runCurrent()
        runtime.dispatch(ExploreIntent.SelectTab(ExploreTab.Events))
        advanceUntilIdle()

        assertEquals(ExploreTab.Events, runtime.state.value.selectedTab)
        preparationGate.complete(Unit)
        runtime.close()
    }

    @Test
    fun authenticationEffectAndPendingActionAreClearedForGuest() = runTest {
        val interactions = RuntimeInteractionRepository(requiresAuthentication = true)
        val runtime = runtime(interactions = interactions)
        runtime.dispatch(ExploreIntent.ViewerContextChanged(viewerId = null))
        advanceUntilIdle()

        runtime.effects.test {
            runtime.dispatch(ExploreIntent.ToggleFavorite(RUNTIME_LISTING_ID))
            val effect = assertIs<ExploreEffect.AuthenticationRequired>(awaitItem())
            assertEquals(ExploreInteractionKind.Favorite, effect.kind)
            assertEquals("cotonou", effect.suggestedCityId)
            assertEquals(RUNTIME_LISTING_ID, runtime.state.value.pendingAuthInteraction?.listingId)

            runtime.dispatch(ExploreIntent.ViewerContextChanged(viewerId = null))
            advanceUntilIdle()

            assertNull(runtime.state.value.pendingAuthInteraction)
            assertNull(runtime.state.value.interactionMessage)
            cancelAndIgnoreRemainingEvents()
        }
        runtime.close()
    }

    @Test
    fun authenticatedTransitionReplaysPendingActionOnceAndPublishesOnlyItsSuccessfulReplay() = runTest {
        val interactions = RuntimeInteractionRepository(requiresAuthentication = true)
        val runtime = runtime(interactions = interactions)
        runtime.dispatch(ExploreIntent.ViewerContextChanged(viewerId = null))
        advanceUntilIdle()

        runtime.effects.test {
            runtime.dispatch(ExploreIntent.ToggleFavorite(RUNTIME_LISTING_ID))
            assertIs<ExploreEffect.AuthenticationRequired>(awaitItem())
            interactions.requiresAuthentication = false

            runtime.dispatch(ExploreIntent.ViewerContextChanged(viewerId = "viewer-1"))
            runtime.dispatch(ExploreIntent.ReplayPendingInteraction)
            val replayed = assertIs<ExploreEffect.ProtectedActionReplayed>(awaitItem())
            advanceUntilIdle()

            assertEquals(ExploreInteractionKind.Favorite, replayed.kind)
            assertEquals(RUNTIME_LISTING_ID, replayed.listingId)
            assertEquals(2, interactions.favoriteCalls)
            assertTrue(runtime.state.value.listings.single().favorited)
            assertNull(runtime.state.value.pendingAuthInteraction)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        runtime.close()
    }

    @Test
    fun failedReplayPublishesAuthenticationAgainButNeverReplaySuccess() = runTest {
        val interactions = RuntimeInteractionRepository(requiresAuthentication = true)
        val runtime = runtime(interactions = interactions)
        runtime.dispatch(ExploreIntent.ViewerContextChanged(viewerId = null))
        advanceUntilIdle()

        runtime.effects.test {
            runtime.dispatch(ExploreIntent.ToggleFavorite(RUNTIME_LISTING_ID))
            assertIs<ExploreEffect.AuthenticationRequired>(awaitItem())

            runtime.dispatch(ExploreIntent.ViewerContextChanged(viewerId = "viewer-1"))
            val retryFailure = assertIs<ExploreEffect.AuthenticationRequired>(awaitItem())
            advanceUntilIdle()

            assertEquals(ExploreInteractionKind.Favorite, retryFailure.kind)
            assertEquals(2, interactions.favoriteCalls)
            assertFalse(runtime.state.value.listings.single().favorited)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        runtime.close()
    }

    @Test
    fun locationEffectAndCoordinatesSelectNearestBeninCity() = runTest {
        val runtime = runtime()
        advanceUntilIdle()

        runtime.effects.test {
            runtime.dispatch(ExploreIntent.OpenCitySelector)
            runtime.dispatch(ExploreIntent.RequestLocation)
            assertIs<ExploreEffect.RequestLocation>(awaitItem())

            runtime.dispatch(
                ExploreIntent.LocationCoordinates(
                    latitude = RUNTIME_OUIDAH_LATITUDE,
                    longitude = RUNTIME_OUIDAH_LONGITUDE,
                ),
            )
            advanceUntilIdle()

            assertEquals("ouidah", runtime.state.value.selectedCityId)
            assertFalse(runtime.state.value.isLocating)
            cancelAndIgnoreRemainingEvents()
        }
        runtime.close()
    }

    @Test
    fun locationOutsideBeninUsesLocalizedFailure() = runTest {
        val runtime = runtime()
        advanceUntilIdle()

        runtime.dispatch(ExploreIntent.OpenCitySelector)
        runtime.dispatch(ExploreIntent.RequestLocation)
        runCurrent()
        runtime.dispatch(
            ExploreIntent.LocationCoordinates(
                latitude = RUNTIME_OUTSIDE_BENIN_LATITUDE,
                longitude = RUNTIME_OUTSIDE_BENIN_LONGITUDE,
            ),
        )
        advanceUntilIdle()

        assertEquals(strings.exploreLocationOutsideBenin, runtime.state.value.locationMessage)
        assertFalse(runtime.state.value.isLocating)
        runtime.close()
    }

    @Test
    fun close_isIdempotentAndRejectsFurtherIntents() = runTest {
        val runtime = runtime()
        advanceUntilIdle()
        val stateBeforeClose = runtime.state.value

        runtime.effects.test {
            runtime.close()
            runtime.close()
            awaitComplete()
        }
        runtime.dispatch(ExploreIntent.SelectTab(ExploreTab.Events))
        advanceUntilIdle()

        assertEquals(stateBeforeClose, runtime.state.value)
    }

    private fun kotlinx.coroutines.test.TestScope.runtime(
        feedRepository: RuntimeFeedRepository = RuntimeFeedRepository(),
        interactions: RuntimeInteractionRepository = RuntimeInteractionRepository(),
        preferences: AppPreferencesRepository? = null,
    ): ExploreRuntime = ExploreRuntime(
        presenter = ExplorePresenter(
            exploreFeedRepository = feedRepository,
            catalogInteractionRepository = interactions,
            appPreferencesRepository = preferences,
            clockProvider = RuntimeClock,
        ),
        strings = strings,
        coroutineScope = this,
    )
}

private class RuntimePreferencesRepository : AppPreferencesRepository {
    var getGate: CompletableDeferred<Unit>? = null

    override suspend fun get(): DomainResult<AppPreferences> {
        getGate?.also { gate ->
            getGate = null
            gate.await()
        }
        return DomainResult.Success(AppPreferences.Default)
    }

    override suspend fun setExploreCity(cityId: String?): DomainResult<AppPreferences> =
        DomainResult.Success(AppPreferences.Default)

    override suspend fun setLocale(locale: AppLocale): DomainResult<AppPreferences> =
        DomainResult.Success(AppPreferences.Default)

    override suspend fun setDisplayCurrency(currency: KwaborCurrency): DomainResult<AppPreferences> =
        DomainResult.Success(AppPreferences.Default)
}

private class RuntimeFeedRepository(
    var refreshSnapshot: ExploreFeedSnapshot = runtimeSnapshot(),
    private val appendSnapshot: ExploreFeedSnapshot = runtimeSnapshot(),
) : ExploreFeedRepository {
    var refreshGate: CompletableDeferred<Unit>? = null

    override suspend fun readCached(query: ExploreFeedQuery): DomainResult<ExploreFeedSnapshot?> =
        DomainResult.Success(null)

    override suspend fun refresh(query: ExploreFeedQuery): DomainResult<ExploreFeedSnapshot> {
        refreshGate?.also { gate ->
            refreshGate = null
            gate.await()
        }
        return DomainResult.Success(refreshSnapshot)
    }

    override suspend fun append(
        query: ExploreFeedQuery,
        currentSnapshot: ExploreFeedSnapshot,
    ): DomainResult<ExploreFeedSnapshot> = DomainResult.Success(appendSnapshot)
}

private class RuntimeInteractionRepository(
    var requiresAuthentication: Boolean = false,
    var interactionGate: CompletableDeferred<Unit>? = null,
) : CatalogInteractionRepository {
    var viewerInteractions: List<ListingViewerInteraction> = emptyList()
    var favoriteCalls: Int = 0
        private set

    override suspend fun getListingViewerInteraction(listingId: String): DomainResult<ListingViewerInteraction> =
        selectedInteraction(listingId = listingId, liked = true, favorited = false)

    override suspend fun listListingViewerInteractions(
        listingIds: List<String>,
    ): DomainResult<List<ListingViewerInteraction>> = if (requiresAuthentication) {
        authenticationFailure()
    } else {
        DomainResult.Success(viewerInteractions.filter { interaction -> interaction.listingId in listingIds })
    }

    override suspend fun likeListing(listingId: String): DomainResult<ListingViewerInteraction> =
        selectedInteraction(listingId = listingId, liked = true, favorited = false)

    override suspend fun unlikeListing(listingId: String): DomainResult<ListingViewerInteraction> =
        selectedInteraction(listingId = listingId, liked = false, favorited = false)

    override suspend fun favoriteListing(listingId: String): DomainResult<ListingViewerInteraction> {
        favoriteCalls += 1
        return selectedInteraction(listingId = listingId, liked = false, favorited = true)
    }

    override suspend fun unfavoriteListing(listingId: String): DomainResult<ListingViewerInteraction> =
        selectedInteraction(listingId = listingId, liked = false, favorited = false)

    private suspend fun selectedInteraction(
        listingId: String,
        liked: Boolean,
        favorited: Boolean,
    ): DomainResult<ListingViewerInteraction> {
        interactionGate?.also { gate ->
            interactionGate = null
            gate.await()
        }
        if (requiresAuthentication) return authenticationFailure()
        return DomainResult.Success(
            ListingViewerInteraction(
                listingId = listingId,
                likedByViewer = liked,
                favoritedByViewer = favorited,
                likesCount = if (liked) 1 else 0,
            ),
        )
    }

    private fun <T> authenticationFailure(): DomainResult<T> =
        DomainResult.Failure(DomainError.AuthenticationRequired("error.auth.required"))
}

private object RuntimeClock : ClockProvider {
    override fun nowEpochMilliseconds(): Long = RUNTIME_NOW_EPOCH_MILLISECONDS
}

private fun runtimeSnapshot(
    items: List<ListingSummary> = listOf(runtimeListing()),
    nextCursor: String? = null,
    categories: List<Category> = emptyList(),
): ExploreFeedSnapshot = ExploreFeedSnapshot(
    cities = listOf(
        City(
            id = "cotonou",
            name = "Cotonou",
            latitude = RUNTIME_COTONOU_LATITUDE,
            longitude = RUNTIME_COTONOU_LONGITUDE,
        ),
        City(
            id = "ouidah",
            name = "Ouidah",
            latitude = RUNTIME_OUIDAH_LATITUDE,
            longitude = RUNTIME_OUIDAH_LONGITUDE,
        ),
    ),
    categories = categories,
    items = items,
    nextCursor = nextCursor,
    cachedAtEpochMilliseconds = RuntimeClock.nowEpochMilliseconds(),
    source = ExploreFeedSource.Network,
)

private fun runtimeListing(id: String = RUNTIME_LISTING_ID): ListingSummary = ListingSummary(
    id = id,
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

private const val RUNTIME_LISTING_ID = "ouidah-gate"
private const val RUNTIME_NOW_EPOCH_MILLISECONDS = 1_000L
private const val RUNTIME_COTONOU_LATITUDE = 6.3703
private const val RUNTIME_COTONOU_LONGITUDE = 2.3912
private const val RUNTIME_OUIDAH_LATITUDE = 6.3631
private const val RUNTIME_OUIDAH_LONGITUDE = 2.0851
private const val RUNTIME_OUTSIDE_BENIN_LATITUDE = 48.8566
private const val RUNTIME_OUTSIDE_BENIN_LONGITUDE = 2.3522
