package com.kwabor.shared.presentation.explore

import app.cash.turbine.test
import com.kwabor.shared.domain.catalog.CatalogInteractionRepository
import com.kwabor.shared.domain.catalog.Category
import com.kwabor.shared.domain.catalog.City
import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingPageRequest
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
import com.kwabor.shared.domain.favorites.FavoriteListingPage
import com.kwabor.shared.domain.favorites.FavoriteMutation
import com.kwabor.shared.domain.favorites.FavoritesRepository
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.money.KwaborCurrency
import com.kwabor.shared.domain.observability.AnalyticsEntityType
import com.kwabor.shared.domain.observability.AnalyticsSessionSource
import com.kwabor.shared.domain.preferences.AppPreferences
import com.kwabor.shared.domain.preferences.AppPreferencesRepository
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.session.ViewerSessionScope
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

private val strings = stringsFor(AppLocale.French)

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreRuntimeTest {
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
    fun loadNextKeepsOfflineVisibleWhileAFavoriteMutationIsQueued() = runTest {
        val appendGate = CompletableDeferred<Unit>()
        val feedRepository = RuntimeFeedRepository(
            refreshSnapshot = runtimeSnapshot(nextCursor = "cursor-1"),
        )
        val interactions = RuntimeInteractionRepository(favoriteNetworkUnavailable = true)
        val runtime = runtime(feedRepository = feedRepository, interactions = interactions)
        runtime.dispatch(ExploreIntent.ViewerContextChanged(AUTHENTICATED_SCOPE))
        advanceUntilIdle()
        runtime.dispatch(ExploreIntent.ToggleFavorite(RUNTIME_LISTING_ID))
        advanceUntilIdle()
        assertTrue(runtime.state.value.queuedInteractions.isNotEmpty())

        feedRepository.appendGate = appendGate
        runtime.dispatch(ExploreIntent.LoadNext)
        runCurrent()

        assertTrue(runtime.state.value.isAppending)
        assertTrue(runtime.state.value.isOffline)
        appendGate.complete(Unit)
        advanceUntilIdle()
        runtime.close()
    }

    @Test
    fun loadNextKeepsOfflineVisibleWhileOfflineContentIsBeingRevalidated() = runTest {
        val appendGate = CompletableDeferred<Unit>()
        val feedRepository = RuntimeFeedRepository(
            refreshSnapshot = runtimeSnapshot(nextCursor = "cursor-1"),
        )
        val interactions = RuntimeInteractionRepository(viewerInteractionsNetworkUnavailable = true)
        val runtime = runtime(feedRepository = feedRepository, interactions = interactions)
        runtime.dispatch(ExploreIntent.ViewerContextChanged(AUTHENTICATED_SCOPE))
        advanceUntilIdle()
        assertTrue(runtime.state.value.isOffline)

        feedRepository.appendGate = appendGate
        runtime.dispatch(ExploreIntent.LoadNext)
        runCurrent()

        assertTrue(runtime.state.value.isAppending)
        assertTrue(runtime.state.value.isOffline)
        appendGate.complete(Unit)
        advanceUntilIdle()
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
    fun restoredAuthenticatedScopeReloadsAFeedInitiallyHydratedAsGuest() = runTest {
        val interactions = RuntimeInteractionRepository()
        val runtime = runtime(interactions = interactions)
        advanceUntilIdle()
        assertFalse(runtime.state.value.listings.single().favorited)

        interactions.viewerInteractions = listOf(
            ListingViewerInteraction(
                listingId = RUNTIME_LISTING_ID,
                likedByViewer = true,
                favoritedByViewer = true,
                likesCount = 1,
            ),
        )
        runtime.dispatch(ExploreIntent.ViewerContextChanged(AUTHENTICATED_SCOPE))
        advanceUntilIdle()

        val listing = runtime.state.value.listings.single()
        assertTrue(listing.liked)
        assertTrue(listing.favorited)
        assertEquals(1, listing.likesCount)
        runtime.close()
    }

    @Test
    fun viewerScopeChangePurgesPrivateExploreStateSynchronously() = runTest {
        val interactions = RuntimeInteractionRepository()
        interactions.viewerInteractions = listOf(
            ListingViewerInteraction(
                listingId = RUNTIME_LISTING_ID,
                likedByViewer = true,
                favoritedByViewer = true,
                likesCount = 1,
            ),
        )
        val runtime = runtime(interactions = interactions)
        runtime.dispatch(ExploreIntent.ViewerContextChanged(AUTHENTICATED_SCOPE))
        advanceUntilIdle()
        assertTrue(runtime.state.value.listings.single().liked)
        assertTrue(runtime.state.value.listings.single().favorited)
        assertTrue(runtime.state.value.feedSnapshot != null)

        val nextScope = ViewerSessionScope(accountId = "viewer-2", epoch = 2L)
        runtime.dispatch(ExploreIntent.ViewerContextChanged(nextScope))

        val purged = runtime.state.value
        assertEquals(nextScope, purged.viewerScope)
        assertFalse(purged.listings.single().liked)
        assertFalse(purged.listings.single().favorited)
        assertNull(purged.feedSnapshot)
        assertNull(purged.pendingAuthInteraction)
        assertTrue(purged.queuedInteractions.isEmpty())
        assertTrue(purged.isLoading)
        runtime.close()
    }

    @Test
    fun viewerLogoutPurgesPrivateExploreStateIntoLoadingSynchronously() = runTest {
        val interactions = RuntimeInteractionRepository().apply {
            viewerInteractions = listOf(
                ListingViewerInteraction(
                    listingId = RUNTIME_LISTING_ID,
                    likedByViewer = true,
                    favoritedByViewer = true,
                    likesCount = 1,
                ),
            )
        }
        val runtime = runtime(interactions = interactions)
        runtime.dispatch(ExploreIntent.ViewerContextChanged(AUTHENTICATED_SCOPE))
        advanceUntilIdle()

        val guestScope = ViewerSessionScope(accountId = null, epoch = 2L)
        runtime.dispatch(ExploreIntent.ViewerContextChanged(guestScope))

        val purged = runtime.state.value
        assertEquals(guestScope, purged.viewerScope)
        assertFalse(purged.listings.single().liked)
        assertFalse(purged.listings.single().favorited)
        assertNull(purged.feedSnapshot)
        assertTrue(purged.isLoading)
        runtime.close()
    }

    @Test
    fun protectedIntentQueuedForThePreviousScopeNeverMutatesTheNextAccount() = runTest {
        val interactionGate = CompletableDeferred<Unit>()
        val interactions = RuntimeInteractionRepository(interactionGate = interactionGate)
        val runtime = runtime(interactions = interactions)
        runtime.dispatch(ExploreIntent.ViewerContextChanged(AUTHENTICATED_SCOPE))
        advanceUntilIdle()

        runtime.dispatch(ExploreIntent.ToggleLike(RUNTIME_LISTING_ID))
        runCurrent()
        runtime.dispatch(ExploreIntent.ToggleFavorite(RUNTIME_LISTING_ID))
        runCurrent()
        val nextScope = ViewerSessionScope(accountId = "viewer-2", epoch = 2L)
        runtime.dispatch(ExploreIntent.ViewerContextChanged(nextScope))
        interactionGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(0, interactions.favoriteCalls)
        assertEquals(nextScope, runtime.state.value.viewerScope)
        assertFalse(runtime.state.value.listings.single().favorited)
        runtime.close()
    }

    @Test
    fun favoriteStateBridgePreservesLikeAndWinsAgainstLateFeed() = runTest {
        val refreshGate = CompletableDeferred<Unit>()
        val feedRepository = RuntimeFeedRepository()
        val interactions = RuntimeInteractionRepository()
        val runtime = runtime(feedRepository, interactions)
        runtime.dispatch(ExploreIntent.ViewerContextChanged(AUTHENTICATED_SCOPE))
        advanceUntilIdle()
        runtime.dispatch(ExploreIntent.ToggleLike(RUNTIME_LISTING_ID))
        advanceUntilIdle()
        assertTrue(runtime.state.value.listings.single().liked)

        feedRepository.refreshGate = refreshGate
        runtime.dispatch(ExploreIntent.Refresh)
        runCurrent()
        runtime.dispatch(
            ExploreIntent.FavoriteStateChanged(
                listingId = RUNTIME_LISTING_ID,
                favorited = true,
                clientMutationSequence = 1L,
                scope = AUTHENTICATED_SCOPE,
            ),
        )
        runCurrent()
        interactions.viewerInteractions = listOf(runtimeViewerInteraction(liked = true, favorited = false, likes = 11))
        refreshGate.complete(Unit)
        advanceUntilIdle()

        val listing = runtime.state.value.listings.single()
        assertTrue(listing.favorited)
        assertTrue(listing.liked)
        assertEquals(11, listing.likesCount)
        runtime.close()
    }

    @Test
    fun offlineFavoriteQueueOverridesAnOlderConfirmationDuringRefresh() = runTest {
        val refreshGate = CompletableDeferred<Unit>()
        val feedRepository = RuntimeFeedRepository()
        val interactions = RuntimeInteractionRepository(favoriteNetworkUnavailable = true)
        val runtime = runtime(feedRepository, interactions)
        runtime.dispatch(ExploreIntent.ViewerContextChanged(AUTHENTICATED_SCOPE))
        advanceUntilIdle()
        runtime.dispatch(favoriteStateChanged(favorited = true, clientMutationSequence = 1L))
        advanceUntilIdle()
        assertTrue(runtime.state.value.listings.single().favorited)
        feedRepository.refreshGate = refreshGate
        runtime.dispatch(ExploreIntent.Refresh)
        runCurrent()

        runtime.dispatch(ExploreIntent.ToggleFavorite(RUNTIME_LISTING_ID))
        runCurrent()
        assertFalse(runtime.state.value.listings.single().favorited)
        refreshGate.complete(Unit)
        advanceUntilIdle()

        assertFalse(runtime.state.value.listings.single().favorited)
        assertEquals(ExploreInteractionKind.Favorite, runtime.state.value.queuedInteractions.single().kind)
        runtime.close()
    }

    @Test
    fun likeChangedDuringRefreshDoesNotOverwriteAFreshFavoriteValue() = runTest {
        val refreshGate = CompletableDeferred<Unit>()
        val feedRepository = RuntimeFeedRepository()
        val interactions = RuntimeInteractionRepository()
        val runtime = runtime(feedRepository, interactions)
        runtime.dispatch(ExploreIntent.ViewerContextChanged(AUTHENTICATED_SCOPE))
        advanceUntilIdle()
        feedRepository.refreshGate = refreshGate
        runtime.dispatch(ExploreIntent.Refresh)
        runCurrent()

        runtime.dispatch(ExploreIntent.ToggleLike(RUNTIME_LISTING_ID))
        runCurrent()
        interactions.viewerInteractions = listOf(runtimeViewerInteraction(liked = true, favorited = true, likes = 1))
        refreshGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(runtime.state.value.listings.single().liked)
        assertTrue(runtime.state.value.listings.single().favorited)
        runtime.close()
    }

    @Test
    fun staleFavoriteBridgeFromAnOlderLoginOfTheSameAccountIsIgnored() = runTest {
        val reconnectedScope = ViewerSessionScope(accountId = "viewer-1", epoch = 3L)
        val runtime = runtime()
        runtime.dispatch(ExploreIntent.ViewerContextChanged(AUTHENTICATED_SCOPE))
        advanceUntilIdle()
        runtime.dispatch(ExploreIntent.ViewerContextChanged(ViewerSessionScope(accountId = null, epoch = 2L)))
        runtime.dispatch(ExploreIntent.ViewerContextChanged(reconnectedScope))
        advanceUntilIdle()

        runtime.dispatch(
            ExploreIntent.FavoriteStateChanged(
                listingId = RUNTIME_LISTING_ID,
                favorited = true,
                clientMutationSequence = 1L,
                scope = AUTHENTICATED_SCOPE,
            ),
        )
        advanceUntilIdle()

        assertFalse(runtime.state.value.listings.single().favorited)
        runtime.close()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreRuntimeInteractionTest {
    @Test
    fun likeCompletionCannotRestoreFavoriteOrQueueClearedByTheBridge() = runTest {
        val likeGate = CompletableDeferred<Unit>()
        val interactions = RuntimeInteractionRepository(favoriteNetworkUnavailable = true)
        val runtime = runtime(interactions = interactions)
        runtime.dispatch(ExploreIntent.ViewerContextChanged(AUTHENTICATED_SCOPE))
        advanceUntilIdle()

        runtime.dispatch(ExploreIntent.ToggleFavorite(RUNTIME_LISTING_ID))
        advanceUntilIdle()
        assertTrue(runtime.state.value.listings.single().favorited)
        assertEquals(ExploreInteractionKind.Favorite, runtime.state.value.queuedInteractions.single().kind)
        assertTrue(runtime.state.value.isOffline)

        interactions.interactionGate = likeGate
        runtime.dispatch(ExploreIntent.ToggleLike(RUNTIME_LISTING_ID))
        runCurrent()
        runtime.dispatch(
            ExploreIntent.FavoriteStateChanged(
                listingId = RUNTIME_LISTING_ID,
                favorited = false,
                clientMutationSequence = 1L,
                scope = AUTHENTICATED_SCOPE,
            ),
        )
        runCurrent()
        assertFalse(runtime.state.value.listings.single().favorited)
        assertTrue(runtime.state.value.queuedInteractions.isEmpty())
        assertFalse(runtime.state.value.isOffline)

        likeGate.complete(Unit)
        advanceUntilIdle()

        val listing = runtime.state.value.listings.single()
        assertTrue(listing.liked)
        assertEquals(1, listing.likesCount)
        assertFalse(listing.favorited)
        assertTrue(runtime.state.value.queuedInteractions.isEmpty())
        assertFalse(runtime.state.value.isOffline)
        runtime.close()
    }

    @Test
    fun favoriteBridgeClearsItsQueueWithoutHidingAContentNetworkFailure() = runTest {
        val interactions = RuntimeInteractionRepository(
            favoriteNetworkUnavailable = true,
            viewerInteractionsNetworkUnavailable = true,
        )
        val runtime = runtime(interactions = interactions)
        runtime.dispatch(ExploreIntent.ViewerContextChanged(AUTHENTICATED_SCOPE))
        advanceUntilIdle()
        assertTrue(runtime.state.value.isOffline)

        runtime.dispatch(ExploreIntent.ToggleFavorite(RUNTIME_LISTING_ID))
        advanceUntilIdle()
        assertEquals(ExploreInteractionKind.Favorite, runtime.state.value.queuedInteractions.single().kind)

        runtime.dispatch(
            ExploreIntent.FavoriteStateChanged(
                listingId = RUNTIME_LISTING_ID,
                favorited = false,
                clientMutationSequence = 1L,
                scope = AUTHENTICATED_SCOPE,
            ),
        )
        advanceUntilIdle()

        assertTrue(runtime.state.value.queuedInteractions.isEmpty())
        assertTrue(runtime.state.value.isOffline)
        runtime.close()
    }

    @Test
    fun favoriteBridgeInvalidatesAnOlderFavoriteToggleStillInFlight() = runTest {
        val favoriteGate = CompletableDeferred<Unit>()
        val interactions = RuntimeInteractionRepository(interactionGate = favoriteGate).apply {
            viewerInteractions = listOf(
                ListingViewerInteraction(
                    listingId = RUNTIME_LISTING_ID,
                    likedByViewer = false,
                    favoritedByViewer = true,
                    likesCount = 0,
                ),
            )
        }
        val runtime = runtime(interactions = interactions)
        runtime.dispatch(ExploreIntent.ViewerContextChanged(AUTHENTICATED_SCOPE))
        advanceUntilIdle()
        assertTrue(runtime.state.value.listings.single().favorited)

        runtime.effects.test {
            runtime.dispatch(ExploreIntent.ToggleFavorite(RUNTIME_LISTING_ID))
            runCurrent()
            runtime.dispatch(
                ExploreIntent.FavoriteStateChanged(
                    listingId = RUNTIME_LISTING_ID,
                    favorited = true,
                    clientMutationSequence = 2L,
                    scope = AUTHENTICATED_SCOPE,
                ),
            )
            runCurrent()

            favoriteGate.complete(Unit)
            advanceUntilIdle()

            assertTrue(runtime.state.value.listings.single().favorited)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        runtime.close()
    }

    @Test
    fun newerLocalFavoriteConfirmationWinsAgainstAnOlderBridgeWhileInFlight() = runTest {
        val favoriteGate = CompletableDeferred<Unit>()
        val interactions = RuntimeInteractionRepository(
            interactionGate = favoriteGate,
            nextClientMutationSequence = 2L,
        )
        val runtime = runtime(interactions = interactions)
        runtime.dispatch(ExploreIntent.ViewerContextChanged(AUTHENTICATED_SCOPE))
        advanceUntilIdle()

        runtime.effects.test {
            runtime.dispatch(ExploreIntent.ToggleFavorite(RUNTIME_LISTING_ID))
            runCurrent()
            runtime.dispatch(
                ExploreIntent.FavoriteStateChanged(
                    listingId = RUNTIME_LISTING_ID,
                    favorited = false,
                    clientMutationSequence = 1L,
                    scope = AUTHENTICATED_SCOPE,
                ),
            )
            runCurrent()

            favoriteGate.complete(Unit)
            advanceUntilIdle()

            val changed = assertIs<ExploreEffect.FavoriteChanged>(awaitItem())
            assertTrue(runtime.state.value.listings.single().favorited)
            assertTrue(changed.favorited)
            assertEquals(2L, changed.clientMutationSequence)
            cancelAndIgnoreRemainingEvents()
        }
        runtime.close()
    }

    @Test
    fun duplicateAndOlderFavoriteConfirmationsAreIgnoredWithoutInvalidatingLike() = runTest {
        val runtime = runtime()
        runtime.dispatch(ExploreIntent.ViewerContextChanged(AUTHENTICATED_SCOPE))
        advanceUntilIdle()

        runtime.dispatch(favoriteStateChanged(favorited = true, clientMutationSequence = 2L))
        runtime.dispatch(favoriteStateChanged(favorited = false, clientMutationSequence = 1L))
        runtime.dispatch(favoriteStateChanged(favorited = false, clientMutationSequence = 2L))
        advanceUntilIdle()
        runtime.dispatch(ExploreIntent.ToggleLike(RUNTIME_LISTING_ID))
        advanceUntilIdle()

        assertTrue(runtime.state.value.listings.single().favorited)
        assertTrue(runtime.state.value.listings.single().liked)
        runtime.close()
    }

    @Test
    fun newerBridgeWhileListingIsHiddenRejectsAnOlderLocalConfirmation() = runTest {
        val favoriteGate = CompletableDeferred<Unit>()
        val feedRepository = RuntimeFeedRepository()
        val interactions = RuntimeInteractionRepository(interactionGate = favoriteGate)
        val runtime = runtime(feedRepository, interactions)
        runtime.dispatch(ExploreIntent.ViewerContextChanged(AUTHENTICATED_SCOPE))
        advanceUntilIdle()

        runtime.effects.test {
            runtime.dispatch(ExploreIntent.ToggleFavorite(RUNTIME_LISTING_ID))
            runCurrent()
            feedRepository.refreshSnapshot = runtimeSnapshot(items = emptyList())
            runtime.dispatch(ExploreIntent.SelectTab(ExploreTab.Events))
            runCurrent()
            assertTrue(runtime.state.value.listings.isEmpty())

            runtime.dispatch(favoriteStateChanged(favorited = false, clientMutationSequence = 2L))
            runCurrent()
            favoriteGate.complete(Unit)
            advanceUntilIdle()

            assertTrue(runtime.state.value.listings.isEmpty())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        runtime.close()
    }

    @Test
    fun hiddenFavoriteBridgeOverlaysAFeedResponseThatStartedEarlier() = runTest {
        val refreshGate = CompletableDeferred<Unit>()
        val feedRepository = RuntimeFeedRepository()
        val runtime = runtime(feedRepository = feedRepository)
        runtime.dispatch(ExploreIntent.ViewerContextChanged(AUTHENTICATED_SCOPE))
        advanceUntilIdle()
        feedRepository.refreshSnapshot = runtimeSnapshot(items = emptyList())
        runtime.dispatch(ExploreIntent.SelectTab(ExploreTab.Events))
        advanceUntilIdle()
        assertTrue(runtime.state.value.listings.isEmpty())

        feedRepository.refreshSnapshot = runtimeSnapshot()
        feedRepository.refreshGate = refreshGate
        runtime.dispatch(ExploreIntent.SelectTab(ExploreTab.Places))
        runCurrent()
        runtime.dispatch(favoriteStateChanged(favorited = true, clientMutationSequence = 2L))
        runCurrent()
        assertTrue(runtime.state.value.listings.isEmpty())

        refreshGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(runtime.state.value.listings.single().favorited)
        runtime.close()
    }

    @Test
    fun newerHiddenConfirmationUpdatesThePriorVisibleOverride() = runTest {
        val refreshGate = CompletableDeferred<Unit>()
        val feedRepository = RuntimeFeedRepository()
        val runtime = runtime(feedRepository = feedRepository)
        runtime.dispatch(ExploreIntent.ViewerContextChanged(AUTHENTICATED_SCOPE))
        advanceUntilIdle()
        runtime.dispatch(favoriteStateChanged(favorited = true, clientMutationSequence = 1L))
        advanceUntilIdle()
        feedRepository.refreshSnapshot = runtimeSnapshot(items = emptyList())
        runtime.dispatch(ExploreIntent.SelectTab(ExploreTab.Events))
        advanceUntilIdle()

        feedRepository.refreshSnapshot = runtimeSnapshot()
        feedRepository.refreshGate = refreshGate
        runtime.dispatch(ExploreIntent.SelectTab(ExploreTab.Places))
        runCurrent()
        runtime.dispatch(favoriteStateChanged(favorited = false, clientMutationSequence = 2L))
        runCurrent()
        refreshGate.complete(Unit)
        advanceUntilIdle()

        assertFalse(runtime.state.value.listings.single().favorited)
        runtime.close()
    }

    @Test
    fun hiddenLocalFavoriteConfirmationDoesNotOverrideFresherLikeData() = runTest {
        val favoriteGate = CompletableDeferred<Unit>()
        val refreshGate = CompletableDeferred<Unit>()
        val feedRepository = RuntimeFeedRepository()
        val interactions = RuntimeInteractionRepository(interactionGate = favoriteGate)
        val runtime = runtime(feedRepository, interactions)
        runtime.dispatch(ExploreIntent.ViewerContextChanged(AUTHENTICATED_SCOPE))
        advanceUntilIdle()
        runtime.dispatch(ExploreIntent.ToggleFavorite(RUNTIME_LISTING_ID))
        runCurrent()
        feedRepository.refreshSnapshot = runtimeSnapshot(items = emptyList())
        runtime.dispatch(ExploreIntent.SelectTab(ExploreTab.Events))
        runCurrent()
        feedRepository.refreshSnapshot = runtimeSnapshot()
        feedRepository.refreshGate = refreshGate
        runtime.dispatch(ExploreIntent.SelectTab(ExploreTab.Places))
        runCurrent()

        favoriteGate.complete(Unit)
        runCurrent()
        interactions.viewerInteractions = listOf(runtimeViewerInteraction(liked = true, favorited = true, likes = 7))
        refreshGate.complete(Unit)
        advanceUntilIdle()

        val listing = runtime.state.value.listings.single()
        assertTrue(listing.favorited)
        assertTrue(listing.liked)
        assertEquals(7, listing.likesCount)
        runtime.close()
    }

    @Test
    fun confirmedFavoriteMutationPublishesItsExactViewerScope() = runTest {
        val runtime = runtime()
        runtime.dispatch(ExploreIntent.ViewerContextChanged(AUTHENTICATED_SCOPE))
        advanceUntilIdle()

        runtime.effects.test {
            runtime.dispatch(ExploreIntent.ToggleFavorite(RUNTIME_LISTING_ID))
            val changed = assertIs<ExploreEffect.FavoriteChanged>(awaitItem())

            assertEquals(RUNTIME_LISTING_ID, changed.listingId)
            assertTrue(changed.favorited)
            assertEquals(1L, changed.clientMutationSequence)
            assertEquals(AUTHENTICATED_SCOPE, changed.scope)
            cancelAndIgnoreRemainingEvents()
        }
        runtime.close()
    }

    @Test
    fun favoriteBridgePreservesAnAuthenticationMessageForALike() = runTest {
        val interactions = RuntimeInteractionRepository(requiresAuthentication = true)
        val runtime = runtime(interactions = interactions)
        runtime.dispatch(ExploreIntent.ViewerContextChanged(AUTHENTICATED_SCOPE))
        advanceUntilIdle()

        runtime.effects.test {
            runtime.dispatch(ExploreIntent.ToggleLike(RUNTIME_LISTING_ID))
            assertIs<ExploreEffect.AuthenticationRequired>(awaitItem())
            val message = runtime.state.value.interactionMessage

            runtime.dispatch(
                ExploreIntent.FavoriteStateChanged(
                    listingId = RUNTIME_LISTING_ID,
                    favorited = true,
                    clientMutationSequence = 1L,
                    scope = AUTHENTICATED_SCOPE,
                ),
            )
            advanceUntilIdle()

            assertEquals(message, runtime.state.value.interactionMessage)
            assertEquals(ExploreInteractionKind.Like, runtime.state.value.pendingAuthInteraction?.kind)
            assertTrue(runtime.state.value.listings.single().favorited)
            cancelAndIgnoreRemainingEvents()
        }
        runtime.close()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreRuntimeInteractionContextTest {
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
        runtime.dispatch(ExploreIntent.ViewerContextChanged(ViewerSessionScope.InitialGuest))
        advanceUntilIdle()

        runtime.effects.test {
            runtime.dispatch(ExploreIntent.ToggleFavorite(RUNTIME_LISTING_ID))
            val effect = assertIs<ExploreEffect.AuthenticationRequired>(awaitItem())
            assertEquals(ExploreInteractionKind.Favorite, effect.kind)
            assertEquals("cotonou", effect.suggestedCityId)
            assertEquals(ViewerSessionScope.InitialGuest, effect.scope)
            assertEquals(RUNTIME_LISTING_ID, runtime.state.value.pendingAuthInteraction?.listingId)

            runtime.dispatch(ExploreIntent.ViewerContextChanged(ViewerSessionScope.InitialGuest))
            advanceUntilIdle()
            assertEquals(RUNTIME_LISTING_ID, runtime.state.value.pendingAuthInteraction?.listingId)

            runtime.dispatch(ExploreIntent.ClearPendingAuthentication)
            advanceUntilIdle()

            assertNull(runtime.state.value.pendingAuthInteraction)
            assertNull(runtime.state.value.interactionMessage)
            cancelAndIgnoreRemainingEvents()
        }
        runtime.close()
    }

    @Test
    fun latestProtectedActionReplacesThePreviousPendingAuthentication() = runTest {
        val secondListingId = "listing-2"
        val feedRepository = RuntimeFeedRepository(
            refreshSnapshot = runtimeSnapshot(
                items = listOf(runtimeListing(), runtimeListing(id = secondListingId)),
            ),
        )
        val interactions = RuntimeInteractionRepository(requiresAuthentication = true)
        val runtime = runtime(feedRepository = feedRepository, interactions = interactions)
        runtime.dispatch(ExploreIntent.ViewerContextChanged(ViewerSessionScope.InitialGuest))
        advanceUntilIdle()

        runtime.effects.test {
            runtime.dispatch(ExploreIntent.ToggleFavorite(RUNTIME_LISTING_ID))
            val first = assertIs<ExploreEffect.AuthenticationRequired>(awaitItem())
            assertEquals(ExploreInteractionKind.Favorite, first.kind)

            runtime.dispatch(ExploreIntent.ToggleLike(secondListingId))
            val second = assertIs<ExploreEffect.AuthenticationRequired>(awaitItem())
            assertEquals(ExploreInteractionKind.Like, second.kind)
            assertEquals(ViewerSessionScope.InitialGuest, second.scope)
            assertEquals(
                PendingExploreAuthInteraction(
                    listingId = secondListingId,
                    kind = ExploreInteractionKind.Like,
                    suggestedCityId = "cotonou",
                ),
                runtime.state.value.pendingAuthInteraction,
            )
            cancelAndIgnoreRemainingEvents()
        }
        runtime.close()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreRuntimeReplayAndLocationTest {
    @Test
    fun staleClearAndReplayCommandsCannotTouchANewerLoginOfTheSameAccount() = runTest {
        val interactions = RuntimeInteractionRepository(requiresAuthentication = true)
        val runtime = runtime(interactions = interactions)
        runtime.dispatch(ExploreIntent.ViewerContextChanged(ViewerSessionScope.InitialGuest))
        advanceUntilIdle()
        runtime.dispatch(ExploreIntent.ToggleFavorite(RUNTIME_LISTING_ID))
        advanceUntilIdle()
        assertEquals(RUNTIME_LISTING_ID, runtime.state.value.pendingAuthInteraction?.listingId)

        runtime.dispatch(ExploreIntent.ClearPendingAuthentication)
        runtime.dispatch(ExploreIntent.ReplayPendingInteraction)
        runtime.dispatch(ExploreIntent.ViewerContextChanged(AUTHENTICATED_SCOPE))
        runtime.dispatch(ExploreIntent.ViewerContextChanged(ViewerSessionScope(accountId = null, epoch = 2L)))
        val reconnectedScope = ViewerSessionScope(accountId = "viewer-1", epoch = 3L)
        runtime.dispatch(ExploreIntent.ViewerContextChanged(reconnectedScope))
        runCurrent()
        runtime.dispatch(ExploreIntent.ToggleLike(RUNTIME_LISTING_ID))
        advanceUntilIdle()

        assertEquals(1, interactions.favoriteCalls)
        assertEquals(reconnectedScope, runtime.state.value.viewerScope)
        assertEquals(ExploreInteractionKind.Like, runtime.state.value.pendingAuthInteraction?.kind)
        runtime.close()
    }

    @Test
    fun authenticatedTransitionReplaysPendingActionOnceAndPublishesOnlyItsSuccessfulReplay() = runTest {
        val interactions = RuntimeInteractionRepository(requiresAuthentication = true)
        val runtime = runtime(interactions = interactions)
        runtime.dispatch(ExploreIntent.ViewerContextChanged(ViewerSessionScope.InitialGuest))
        advanceUntilIdle()

        runtime.effects.test {
            runtime.dispatch(ExploreIntent.ToggleFavorite(RUNTIME_LISTING_ID))
            assertIs<ExploreEffect.AuthenticationRequired>(awaitItem())
            interactions.requiresAuthentication = false

            runtime.dispatch(ExploreIntent.ViewerContextChanged(AUTHENTICATED_SCOPE))
            val replayed = assertIs<ExploreEffect.ProtectedActionReplayed>(awaitItem())
            advanceUntilIdle()

            assertEquals(ExploreInteractionKind.Favorite, replayed.kind)
            assertEquals(RUNTIME_LISTING_ID, replayed.listingId)
            assertEquals(AUTHENTICATED_SCOPE, replayed.scope)
            val analyticsContext = requireNotNull(replayed.analyticsEvent).context
            assertEquals(AnalyticsEntityType.Place, analyticsContext.entityType)
            assertEquals(RUNTIME_LISTING_ID, analyticsContext.entityId)
            assertEquals("cotonou", analyticsContext.cityId)
            assertEquals(AnalyticsSessionSource.Organic, analyticsContext.sessionSource)
            assertEquals(KwaborCurrency.Xof, analyticsContext.displayCurrency)
            assertEquals(2, interactions.favoriteCalls)
            assertTrue(runtime.state.value.listings.single().favorited)
            assertNull(runtime.state.value.pendingAuthInteraction)
            val favoriteChanged = assertIs<ExploreEffect.FavoriteChanged>(awaitItem())
            assertEquals(AUTHENTICATED_SCOPE, favoriteChanged.scope)
            assertTrue(favoriteChanged.favorited)
            assertEquals(2L, favoriteChanged.clientMutationSequence)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        runtime.close()
    }

    @Test
    fun failedReplayPublishesAuthenticationAgainButNeverReplaySuccess() = runTest {
        val interactions = RuntimeInteractionRepository(requiresAuthentication = true)
        val runtime = runtime(interactions = interactions)
        runtime.dispatch(ExploreIntent.ViewerContextChanged(ViewerSessionScope.InitialGuest))
        advanceUntilIdle()

        runtime.effects.test {
            runtime.dispatch(ExploreIntent.ToggleFavorite(RUNTIME_LISTING_ID))
            assertIs<ExploreEffect.AuthenticationRequired>(awaitItem())

            runtime.dispatch(ExploreIntent.ViewerContextChanged(AUTHENTICATED_SCOPE))
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
}

private fun kotlinx.coroutines.test.TestScope.runtime(
    feedRepository: RuntimeFeedRepository = RuntimeFeedRepository(),
    interactions: RuntimeInteractionRepository = RuntimeInteractionRepository(),
    preferences: AppPreferencesRepository? = null,
): ExploreRuntime = ExploreRuntime(
    presenter = ExplorePresenter(
        exploreFeedRepository = feedRepository,
        catalogInteractionRepository = interactions,
        favoritesRepository = interactions,
        appPreferencesRepository = preferences,
        clockProvider = RuntimeClock,
    ),
    strings = strings,
    coroutineScope = this,
)

private fun favoriteStateChanged(
    favorited: Boolean,
    clientMutationSequence: Long,
): ExploreIntent.FavoriteStateChanged = ExploreIntent.FavoriteStateChanged(
    listingId = RUNTIME_LISTING_ID,
    favorited = favorited,
    clientMutationSequence = clientMutationSequence,
    scope = AUTHENTICATED_SCOPE,
)

private fun runtimeViewerInteraction(liked: Boolean, favorited: Boolean, likes: Int): ListingViewerInteraction =
    ListingViewerInteraction(
        listingId = RUNTIME_LISTING_ID,
        likedByViewer = liked,
        favoritedByViewer = favorited,
        likesCount = likes,
    )

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
    var appendGate: CompletableDeferred<Unit>? = null

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
    ): DomainResult<ExploreFeedSnapshot> {
        appendGate?.also { gate ->
            appendGate = null
            gate.await()
        }
        return DomainResult.Success(appendSnapshot)
    }
}

private class RuntimeInteractionRepository(
    var requiresAuthentication: Boolean = false,
    var interactionGate: CompletableDeferred<Unit>? = null,
    var favoriteNetworkUnavailable: Boolean = false,
    var viewerInteractionsNetworkUnavailable: Boolean = false,
    var nextClientMutationSequence: Long = 1L,
) : CatalogInteractionRepository, FavoritesRepository {
    var viewerInteractions: List<ListingViewerInteraction> = emptyList()
    var favoriteCalls: Int = 0
        private set

    override suspend fun getListingViewerInteraction(listingId: String): DomainResult<ListingViewerInteraction> =
        selectedInteraction(listingId = listingId, liked = true, favorited = false)

    override suspend fun listListingViewerInteractions(
        listingIds: List<String>,
    ): DomainResult<List<ListingViewerInteraction>> = when {
        viewerInteractionsNetworkUnavailable -> DomainResult.Failure(DomainError.NetworkUnavailable())
        requiresAuthentication -> authenticationFailure()
        else -> DomainResult.Success(viewerInteractions.filter { interaction -> interaction.listingId in listingIds })
    }

    override suspend fun likeListing(listingId: String): DomainResult<ListingViewerInteraction> =
        selectedInteraction(listingId = listingId, liked = true, favorited = false, persist = true)

    override suspend fun unlikeListing(listingId: String): DomainResult<ListingViewerInteraction> =
        selectedInteraction(listingId = listingId, liked = false, favorited = false, persist = true)

    override suspend fun listFavorites(
        filter: ListingType?,
        page: ListingPageRequest,
    ): DomainResult<FavoriteListingPage> = DomainResult.Success(FavoriteListingPage(emptyList(), null))

    override suspend fun setFavorite(listingId: String, favorited: Boolean): DomainResult<FavoriteMutation> {
        favoriteCalls += 1
        val clientMutationSequence = nextClientMutationSequence++
        interactionGate?.also { gate ->
            interactionGate = null
            gate.await()
        }
        return when {
            requiresAuthentication -> authenticationFailure()
            favoriteNetworkUnavailable -> DomainResult.Failure(DomainError.NetworkUnavailable())
            else -> {
                val current = viewerInteractions.firstOrNull { interaction -> interaction.listingId == listingId }
                val otherInteractions = viewerInteractions.filterNot { interaction ->
                    interaction.listingId == listingId
                }
                viewerInteractions = otherInteractions + ListingViewerInteraction(
                    listingId = listingId,
                    likedByViewer = current?.likedByViewer ?: false,
                    favoritedByViewer = favorited,
                    likesCount = current?.likesCount ?: 0,
                )
                DomainResult.Success(
                    FavoriteMutation(
                        listingId = listingId,
                        favorited = favorited,
                        favoritedAtEpochMilliseconds = if (favorited) RUNTIME_NOW_EPOCH_MILLISECONDS else null,
                        clientMutationSequence = clientMutationSequence,
                    ),
                )
            }
        }
    }

    private suspend fun selectedInteraction(
        listingId: String,
        liked: Boolean,
        favorited: Boolean,
        persist: Boolean = false,
    ): DomainResult<ListingViewerInteraction> {
        interactionGate?.also { gate ->
            interactionGate = null
            gate.await()
        }
        if (requiresAuthentication) return authenticationFailure()
        val interaction = ListingViewerInteraction(
            listingId = listingId,
            likedByViewer = liked,
            favoritedByViewer = favorited,
            likesCount = if (liked) 1 else 0,
        )
        if (persist) {
            viewerInteractions = viewerInteractions
                .filterNot { current -> current.listingId == listingId } + interaction
        }
        return DomainResult.Success(interaction)
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

private val AUTHENTICATED_SCOPE = ViewerSessionScope(accountId = "viewer-1", epoch = 1L)
