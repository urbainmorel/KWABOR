@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

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
import com.kwabor.shared.domain.interaction.InteractionAccountScope
import com.kwabor.shared.domain.interaction.InteractionCommand
import com.kwabor.shared.domain.interaction.InteractionConfirmation
import com.kwabor.shared.domain.interaction.InteractionDrainOutcome
import com.kwabor.shared.domain.interaction.InteractionKind
import com.kwabor.shared.domain.interaction.InteractionOperationOutcome
import com.kwabor.shared.domain.interaction.InteractionRejectionReason
import com.kwabor.shared.domain.interaction.InteractionRepository
import com.kwabor.shared.domain.interaction.InteractionSubmitOutcome
import com.kwabor.shared.domain.interaction.PendingInteraction
import com.kwabor.shared.domain.interaction.PendingInteractionStatus
import com.kwabor.shared.domain.money.KwaborCurrency
import com.kwabor.shared.domain.observability.AnalyticsEntityType
import com.kwabor.shared.domain.observability.AnalyticsSessionSource
import com.kwabor.shared.domain.preferences.AppPreferences
import com.kwabor.shared.domain.preferences.AppPreferencesRepository
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.interaction.InteractionCoordinator
import com.kwabor.shared.presentation.interaction.commitAccountDeletionBlock
import com.kwabor.shared.presentation.interaction.InteractionReconciliationConsumer
import com.kwabor.shared.presentation.interaction.terminalWatermark
import com.kwabor.shared.presentation.session.ViewerSessionScope
import com.kwabor.shared.presentation.session.ViewerSessionScopeTracker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
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
}

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreRuntimeDurableInteractionTest {
    @Test
    fun durableWriteFailureDoesNotApplyOptimismOrCallLegacyTransport() = runTest {
        val tracker = authenticatedRuntimeTracker()
        val durableRepository = RuntimeDurableInteractionRepository().apply {
            submitFailure = DomainError.LocalStorageUnavailable()
        }
        val legacyInteractions = RuntimeInteractionRepository()
        val coordinator = InteractionCoordinator(durableRepository, tracker, RuntimeClock, backgroundScope)
        val runtime = runtime(
            interactions = legacyInteractions,
            interactionCoordinator = coordinator,
        )
        runtime.dispatch(ExploreIntent.ViewerContextChanged(tracker.currentScope))
        advanceUntilIdle()

        runtime.dispatch(ExploreIntent.ToggleFavorite(RUNTIME_LISTING_ID))
        advanceUntilIdle()

        assertFalse(runtime.state.value.listings.single().favorited)
        assertEquals(emptyList(), runtime.state.value.queuedInteractions)
        assertEquals(strings.interactionFailed, runtime.state.value.interactionMessage)
        assertEquals(0, legacyInteractions.favoriteCalls)
        assertEquals(0, legacyInteractions.likeCalls)
        runtime.close()
    }

    @Test
    fun freshDurableEnqueueIsOptimisticWithoutClaimingOffline() = runTest {
        val tracker = authenticatedRuntimeTracker()
        val durableRepository = RuntimeDurableInteractionRepository()
        val legacyInteractions = RuntimeInteractionRepository()
        val coordinator = InteractionCoordinator(durableRepository, tracker, RuntimeClock, backgroundScope)
        val runtime = runtime(
            interactions = legacyInteractions,
            interactionCoordinator = coordinator,
        )
        runtime.dispatch(ExploreIntent.ViewerContextChanged(tracker.currentScope))
        advanceUntilIdle()

        runtime.dispatch(ExploreIntent.ToggleLike(RUNTIME_LISTING_ID))
        advanceUntilIdle()

        val queued = runtime.state.value.queuedInteractions.single()
        assertTrue(runtime.state.value.listings.single().liked)
        assertEquals(1, runtime.state.value.listings.single().likesCount)
        assertEquals(0, queued.attemptCount)
        assertFalse(runtime.state.value.isOffline)
        assertNull(runtime.state.value.interactionMessage)
        assertEquals(0, legacyInteractions.likeCalls)
        runtime.close()
    }

    @Test
    fun directFreshDurableCommitStaysOnlineWithoutDependingOnQueuedEventDelivery() = runTest {
        val initial = runtimeExploreUiState(
            nextCursor = "cursor-1",
            viewerScope = AUTHENTICATED_SCOPE,
        )
        val stateStore = ExploreStateStore(initial)
        val baseline = stateStore.interactionBaseline(RUNTIME_LISTING_ID, ExploreInteractionKind.Like)
        val pending = runtimeLikePending()
        val result = initial.applyDurablePending(pending, strings)

        val committed = requireNotNull(
            stateStore.commitInteraction(
                ExploreInteractionCommitRequest(
                    result = result,
                    baseline = baseline,
                    listingId = RUNTIME_LISTING_ID,
                    kind = ExploreInteractionKind.Like,
                    clientMutationSequence = null,
                    canCommit = { true },
                ),
            ),
        )

        assertTrue(committed.listings.single().liked)
        assertEquals(0, committed.queuedInteractions.single().attemptCount)
        assertFalse(committed.isOffline)
        assertTrue(committed.canLoadMore)
    }

    @Test
    fun directSubmitResultCapturedBeforePurgeCannotCommitAfterSameScopeResumeAndSignalAck() = runTest {
        val tracker = authenticatedRuntimeTracker()
        val durableRepository = RuntimeDurableInteractionRepository()
        val coordinator = InteractionCoordinator(durableRepository, tracker, RuntimeClock, backgroundScope)
        val initial = runtimeExploreUiState(viewerScope = tracker.currentScope)
        val stateStore = ExploreStateStore(initial)
        val fence = coordinator.captureExploreCommitFence(tracker.currentScope)
        assertIs<ExploreDirectCommitFence.Captured>(fence)
        val queued = submitQueuedLike(
            coordinator = coordinator,
            scope = tracker.currentScope.toInteractionAccountScope(),
            listingId = RUNTIME_LISTING_ID,
        )
        val toggle = ExploreToggleRequest(
            listingId = RUNTIME_LISTING_ID,
            kind = ExploreInteractionKind.Like,
            viewerScopeAtRequest = tracker.currentScope,
            viewerAtRequest = 1L,
            contextAtRequest = 1L,
            replay = null,
        )
        val prepared = ExplorePreparedToggle(
            expectedScope = tracker.currentScope,
            baseline = stateStore.interactionBaseline(RUNTIME_LISTING_ID, ExploreInteractionKind.Like),
        )
        val staleExecution = ExploreInteractionExecution(initial.applyDurablePending(queued.pending, strings))

        coordinator.purgeResumeAndAcknowledge(RUNTIME_ACCOUNT_ID)

        val committed = commitExploreExecutionIfCurrent(
            coordinator = coordinator,
            fence = fence,
            interactionMutex = Mutex(),
            stateStore = stateStore,
            request = ExploreDirectCommitRequest(toggle, prepared, staleExecution) { _, _ -> true },
        )

        assertNull(committed)
        assertFalse(stateStore.value.listings.single().liked)
        assertTrue(stateStore.value.queuedInteractions.isEmpty())
    }

    @Test
    fun networkRetrySurvivesRuntimeRecreationAndSameAccountEpochChange() = runTest {
        val tracker = authenticatedRuntimeTracker()
        val durableRepository = RuntimeDurableInteractionRepository()
        val coordinator = InteractionCoordinator(durableRepository, tracker, RuntimeClock, backgroundScope)
        val firstRuntime = runtime(interactionCoordinator = coordinator)
        firstRuntime.dispatch(ExploreIntent.ViewerContextChanged(tracker.currentScope))
        advanceUntilIdle()
        firstRuntime.dispatch(ExploreIntent.ToggleFavorite(RUNTIME_LISTING_ID))
        advanceUntilIdle()
        val command = durableRepository.submittedCommands.single()
        val pending = durableRepository.pending.single().copy(
            attemptCount = 1,
            status = PendingInteractionStatus.Scheduled(RUNTIME_NOW_EPOCH_MILLISECONDS + 1_000L),
        )
        durableRepository.enqueueDrain(
            InteractionOperationOutcome.Retrying(command = command, pending = pending),
        )
        coordinator.onForeground()
        settleCoordinatorBackgroundWork()
        assertTrue(firstRuntime.state.value.isOffline)
        firstRuntime.close()

        tracker.update(accountId = null, accountSetupComplete = false)
        val restoredScope = tracker.update(accountId = RUNTIME_ACCOUNT_ID, accountSetupComplete = true)
        val restoredRuntime = runtime(interactionCoordinator = coordinator)
        restoredRuntime.dispatch(ExploreIntent.ViewerContextChanged(restoredScope))
        advanceUntilIdle()

        val restored = restoredRuntime.state.value
        assertTrue(restored.listings.single().favorited)
        assertTrue(restored.isOffline)
        assertEquals(1, restored.queuedInteractions.single().attemptCount)
        assertEquals(strings.interactionQueuedOffline, restored.interactionMessage)
        restoredRuntime.close()
    }

    @Test
    fun sessionSuspensionKeepsOptimismWithoutClaimingNetworkOffline() = runTest {
        val tracker = authenticatedRuntimeTracker()
        val durableRepository = RuntimeDurableInteractionRepository()
        val coordinator = InteractionCoordinator(durableRepository, tracker, RuntimeClock, backgroundScope)
        val runtime = runtime(interactionCoordinator = coordinator)
        runtime.dispatch(ExploreIntent.ViewerContextChanged(tracker.currentScope))
        advanceUntilIdle()
        runtime.dispatch(ExploreIntent.ToggleLike(RUNTIME_LISTING_ID))
        advanceUntilIdle()
        val command = durableRepository.submittedCommands.single()
        val suspended = durableRepository.pending.single().copy(
            attemptCount = 1,
            status = PendingInteractionStatus.SuspendedForSession,
        )

        durableRepository.enqueueDrain(
            InteractionOperationOutcome.Retrying(command = command, pending = suspended),
        )
        coordinator.onForeground()
        advanceUntilIdle()

        assertTrue(runtime.state.value.listings.single().liked)
        assertEquals(suspended.operationId, runtime.state.value.queuedInteractions.single().operationId)
        assertFalse(runtime.state.value.queuedInteractions.single().isNetworkRetry)
        assertFalse(runtime.state.value.isOffline)
        assertNull(runtime.state.value.interactionMessage)
        runtime.close()
    }

    @Test
    fun sessionSuspensionIsMaskedForAnotherAccountAndRestoredForANewEpoch() = runTest {
        val tracker = authenticatedRuntimeTracker()
        val durableRepository = RuntimeDurableInteractionRepository()
        val coordinator = InteractionCoordinator(durableRepository, tracker, RuntimeClock, backgroundScope)
        val runtime = runtime(interactionCoordinator = coordinator)
        runtime.dispatch(ExploreIntent.ViewerContextChanged(tracker.currentScope))
        advanceUntilIdle()
        runtime.dispatch(ExploreIntent.ToggleFavorite(RUNTIME_LISTING_ID))
        advanceUntilIdle()
        val command = durableRepository.submittedCommands.single()
        val suspended = durableRepository.pending.single().copy(
            attemptCount = 1,
            status = PendingInteractionStatus.SuspendedForSession,
        )
        durableRepository.enqueueDrain(InteractionOperationOutcome.Retrying(command, suspended))
        coordinator.onForeground()
        advanceUntilIdle()

        val otherAccount = tracker.update(accountId = "viewer-2", accountSetupComplete = true)
        runtime.dispatch(ExploreIntent.ViewerContextChanged(otherAccount))
        advanceUntilIdle()
        assertFalse(runtime.state.value.listings.single().favorited)
        assertEquals(emptyList(), runtime.state.value.queuedInteractions)

        val restoredScope = tracker.update(accountId = RUNTIME_ACCOUNT_ID, accountSetupComplete = true)
        runtime.dispatch(ExploreIntent.ViewerContextChanged(restoredScope))
        advanceUntilIdle()
        assertTrue(runtime.state.value.listings.single().favorited)
        assertFalse(runtime.state.value.isOffline)
        assertEquals(suspended.operationId, runtime.state.value.queuedInteractions.single().operationId)
        runtime.close()
    }

    @Test
    fun durableLikeConfirmationWithNullCountPreservesOptimisticCounter() = runTest {
        val tracker = authenticatedRuntimeTracker()
        val durableRepository = RuntimeDurableInteractionRepository()
        val coordinator = InteractionCoordinator(durableRepository, tracker, RuntimeClock, backgroundScope)
        val runtime = runtime(interactionCoordinator = coordinator)
        runtime.dispatch(ExploreIntent.ViewerContextChanged(tracker.currentScope))
        advanceUntilIdle()
        runtime.dispatch(ExploreIntent.ToggleLike(RUNTIME_LISTING_ID))
        advanceUntilIdle()
        val command = durableRepository.submittedCommands.single()
        val operationId = durableRepository.pending.single().operationId

        durableRepository.enqueueDrain(
            InteractionOperationOutcome.Confirmed(
                command = command,
                confirmation = InteractionConfirmation.Like(
                    operationId = operationId,
                    scope = command.scope,
                    listingId = command.listingId,
                    liked = true,
                    likesCount = null,
                    mutatedAtEpochMilliseconds = RUNTIME_NOW_EPOCH_MILLISECONDS,
                ),
            ),
        )
        coordinator.onForeground()
        settleCoordinatorBackgroundWork()

        assertTrue(runtime.state.value.listings.single().liked)
        assertEquals(1, runtime.state.value.listings.single().likesCount)
        assertEquals(emptyList(), runtime.state.value.queuedInteractions)
        runtime.close()
    }
}

class ExploreRuntimeDurableReconciliationTest {
    @Test
    fun overflowedLastRejectionRetriesFailedHydrationThenReloadsAuthoritativeFeed() = runTest {
        val harness = durableExploreHarness()
        harness.runtime.dispatch(ExploreIntent.ToggleLike(RUNTIME_LISTING_ID))
        advanceUntilIdle()
        val command = harness.durableRepository.submittedCommands.single()
        val operationId = harness.durableRepository.pending.single().operationId
        val refreshCallsBeforeOverflow = harness.feedRepository.refreshCalls
        harness.durableRepository.loadPendingFailuresRemaining = 1
        val slowCollector = blockExploreEventCollector(harness.coordinator)
        val targetRejection = InteractionOperationOutcome.Rejected(
            command = command,
            operationId = operationId,
            reason = InteractionRejectionReason.PermissionDenied,
        )
        harness.durableRepository.enqueueDrain(
            *(overflowExploreConfirmations(command.scope, count = 101) + targetRejection).toTypedArray(),
        )

        harness.coordinator.onForeground()
        settleCoordinatorBackgroundWork()

        assertEquals(emptyList(), harness.runtime.state.value.queuedInteractions)
        assertTrue(harness.runtime.state.value.listings.single().liked)
        assertEquals(refreshCallsBeforeOverflow, harness.feedRepository.refreshCalls)

        harness.coordinator.onForeground()
        settleCoordinatorBackgroundWork()

        assertFalse(harness.runtime.state.value.listings.single().liked)
        assertEquals(0, harness.runtime.state.value.listings.single().likesCount)
        assertTrue(harness.feedRepository.refreshCalls > refreshCallsBeforeOverflow)
        slowCollector.close()
        harness.close()
    }

    @Test
    fun overflowSignalOvertakesBufferedQueuedButWatermarkPreventsStaleOverlay() = runTest {
        val authoritativeItems = listOf(
            runtimeListing(),
            runtimeListing(RUNTIME_OVERTAKE_LISTING_ID),
        )
        val feedRepository = RuntimeFeedRepository(
            refreshSnapshot = runtimeSnapshot(items = authoritativeItems),
        )
        val harness = durableExploreHarness(feedRepository = feedRepository)
        val slowCollector = blockExploreEventCollector(harness.coordinator)
        val blocker = blockExploreHydration(harness)
        val refreshCallsBeforeOverflow = triggerDroppedExploreRejection(harness, blocker.command.scope)

        slowCollector.release()
        runCurrent()
        blocker.gate.complete(Unit)
        settleCoordinatorBackgroundWork()

        assertTrue(harness.coordinator.reconciliationSignals.value != null)
        harness.assertNoQueuedInteraction(RUNTIME_OVERTAKE_LISTING_ID)
        val refreshCallsAfterRelease = feedRepository.refreshCalls
        assertTrue(refreshCallsAfterRelease >= refreshCallsBeforeOverflow)

        harness.coordinator.onForeground()
        settleCoordinatorBackgroundWork()

        val target = harness.runtime.state.value.listings.first { listing ->
            listing.id == RUNTIME_OVERTAKE_LISTING_ID
        }
        assertFalse(target.liked)
        assertEquals(0, target.likesCount)
        harness.assertNoQueuedInteraction(RUNTIME_OVERTAKE_LISTING_ID)
        assertTrue(feedRepository.refreshCalls >= refreshCallsAfterRelease)
        assertTrue(feedRepository.refreshCalls > refreshCallsBeforeOverflow)
        slowCollector.close()
        harness.close()
    }

    @Test
    fun queuedAfterAcknowledgedDeliveryWatermarkDoesNotHydrateOrGetLost() = runTest {
        val harness = durableExploreHarness()
        val slowCollector = blockExploreEventCollector(harness.coordinator)
        harness.durableRepository.enqueueDrain(
            *overflowExploreConfirmations(harness.interactionScope, count = 102)
                .toTypedArray(),
        )
        harness.coordinator.onForeground()
        settleCoordinatorBackgroundWork()
        harness.coordinator.deliveryCommitGate.acknowledgeReconciliation(
            requireNotNull(harness.coordinator.reconciliationSignals.value),
            InteractionReconciliationConsumer.Favorites,
        )
        advanceUntilIdle()
        assertNull(harness.coordinator.reconciliationSignals.value)
        slowCollector.release()
        settleCoordinatorBackgroundWork()

        harness.durableRepository.loadPendingFailuresRemaining = 1
        submitQueuedLike(
            coordinator = harness.coordinator,
            scope = harness.interactionScope,
            listingId = RUNTIME_LISTING_ID,
        )
        advanceUntilIdle()

        assertTrue(harness.runtime.state.value.listings.single().liked)
        assertTrue(
            harness.runtime.state.value.queuedInteractions.any { queued ->
                queued.listingId == RUNTIME_LISTING_ID
            },
        )
        assertEquals(1, harness.durableRepository.loadPendingFailuresRemaining)
        slowCollector.close()
        harness.close()
    }

    @Test
    fun staleEmptyHydrationCannotRemoveQueuedOperationCommittedWhileRoomReadWasBlocked() = runTest {
        val harness = durableExploreHarness()
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        harness.durableRepository.captureLoadPendingBeforeGate = true
        harness.durableRepository.loadPendingGate = gate
        harness.durableRepository.loadPendingStarted = started

        harness.runtime.dispatch(ExploreIntent.Refresh)
        runCurrent()
        assertTrue(started.isCompleted)
        harness.runtime.dispatch(ExploreIntent.ToggleLike(RUNTIME_LISTING_ID))
        runCurrent()
        val queued = harness.durableRepository.pending.single()
        assertEquals(queued.operationId, harness.runtime.state.value.queuedInteractions.single().operationId)

        gate.complete(Unit)
        settleCoordinatorBackgroundWork()

        assertTrue(harness.runtime.state.value.listings.single().liked)
        assertEquals(queued.operationId, harness.runtime.state.value.queuedInteractions.single().operationId)
        harness.durableRepository.enqueueDrain(
            InteractionOperationOutcome.Retrying(
                command = harness.durableRepository.lastSubmittedOutcome.command,
                pending = queued.copy(attemptCount = 1),
            ),
        )
        harness.coordinator.onForeground()
        settleCoordinatorBackgroundWork()
        assertEquals(1, harness.runtime.state.value.queuedInteractions.single().attemptCount)
        harness.close()
    }
}

class ExploreRuntimeDurablePurgeTest {
    @Test
    fun viewerIntentQueuedBeforePurgeCannotSubmitAfterSameScopeResume() = runTest {
        val feedRepository = RuntimeFeedRepository(
            refreshSnapshot = runtimeSnapshot(
                items = listOf(runtimeListing(), runtimeListing(RUNTIME_OVERTAKE_LISTING_ID)),
            ),
        )
        val harness = durableExploreHarness(feedRepository = feedRepository)
        val submitGate = CompletableDeferred<Unit>()
        val submitStarted = CompletableDeferred<Unit>()
        harness.durableRepository.submitGate = submitGate
        harness.durableRepository.submitStarted = submitStarted

        harness.runtime.dispatch(ExploreIntent.ToggleFavorite(RUNTIME_LISTING_ID))
        runCurrent()
        assertTrue(submitStarted.isCompleted)
        harness.runtime.dispatch(ExploreIntent.ToggleLike(RUNTIME_OVERTAKE_LISTING_ID))
        val purge = async { harness.coordinator.commitAccountDeletionBlock(RUNTIME_ACCOUNT_ID) }
        runCurrent()
        assertFalse(purge.isCompleted)

        submitGate.complete(Unit)
        purge.await()
        assertTrue(harness.coordinator.resumeAfterAccountDeletionFailure(RUNTIME_ACCOUNT_ID))
        advanceUntilIdle()

        assertEquals(
            listOf(RUNTIME_LISTING_ID to InteractionKind.Favorite),
            harness.durableRepository.submittedCommands.map { command -> command.listingId to command.kind },
        )
        harness.close()
    }

    @Test
    fun terminalBeyondWatermarkCapacityForcesAuthoritativeVisibleReload() = runTest {
        val feedRepository = RuntimeFeedRepository()
        val viewerInteractions = RuntimeInteractionRepository()
        val harness = durableExploreHarness(feedRepository, viewerInteractions)
        feedRepository.refreshSnapshot = runtimeSnapshot(items = listOf(runtimeListing(likesCount = 7)))
        viewerInteractions.viewerInteractions = listOf(
            runtimeViewerInteraction(liked = true, favorited = false, likes = 7),
        )
        val slowCollector = blockExploreEventCollector(harness.coordinator)
        val targetCommand = runtimeLikeCommand(harness.interactionScope)
        val targetConfirmation = likeConfirmationOutcome(
            command = targetCommand,
            operationId = 10_000L,
            liked = true,
            likesCount = 7,
        )
        val refreshCallsBeforeOverflow = feedRepository.refreshCalls
        harness.durableRepository.enqueueDrain(
            *(overflowExploreConfirmations(targetCommand.scope, count = 1_101) + targetConfirmation).toTypedArray(),
        )

        harness.coordinator.onForeground()
        settleCoordinatorBackgroundWork()

        val signal = requireNotNull(harness.coordinator.reconciliationSignals.value)
        assertEquals(1_000, signal.terminalWatermarks.size)
        assertTrue(signal.requiresPendingValidation)
        assertNull(signal.terminalWatermark(RUNTIME_LISTING_ID, InteractionKind.Like))
        assertTrue(harness.runtime.state.value.listings.single().liked)
        assertEquals(7, harness.runtime.state.value.listings.single().likesCount)
        assertTrue(feedRepository.refreshCalls > refreshCallsBeforeOverflow)
        slowCollector.close()
        harness.close()
    }

    @Test
    fun bufferedQueuedBeforePurgeIsIgnoredAfterResumeOfTheSameScope() = runTest {
        val feedRepository = RuntimeFeedRepository(
            refreshSnapshot = runtimeSnapshot(
                items = listOf(runtimeListing(), runtimeListing(RUNTIME_OVERTAKE_LISTING_ID)),
            ),
        )
        val harness = durableExploreHarness(feedRepository = feedRepository)
        val originalScope = harness.tracker.currentScope
        val blocker = blockExploreHydration(harness)
        submitQueuedLike(
            coordinator = harness.coordinator,
            scope = originalScope.toInteractionAccountScope(),
            listingId = RUNTIME_OVERTAKE_LISTING_ID,
        )
        runCurrent()
        val refreshCallsBeforePurge = feedRepository.refreshCalls

        val purge = async { harness.coordinator.commitAccountDeletionBlock(RUNTIME_ACCOUNT_ID) }
        runCurrent()
        assertTrue(purge.isCompleted)
        purge.await()
        assertEquals(originalScope, harness.tracker.currentScope)
        harness.durableRepository.loadPendingFailuresRemaining = 1
        harness.coordinator.resumeAfterAccountDeletionFailure(RUNTIME_ACCOUNT_ID)
        runCurrent()
        blocker.gate.complete(Unit)
        settleCoordinatorBackgroundWork()

        assertTrue(harness.coordinator.reconciliationSignals.value != null)
        harness.coordinator.onForeground()
        settleCoordinatorBackgroundWork()

        assertEquals(originalScope, harness.runtime.state.value.viewerScope)
        assertTrue(harness.runtime.state.value.queuedInteractions.isEmpty())
        assertTrue(harness.runtime.state.value.listings.none(ExploreListingItem::liked))
        assertTrue(feedRepository.refreshCalls > refreshCallsBeforePurge)
        harness.close()
    }

    @Test
    fun hydrationWindowsOneThousandAndOneVisibleListingsAndAppliesTheLastPendingLike() = runTest {
        val tracker = authenticatedRuntimeTracker()
        val listingIds = (1..1_001).map { index -> "windowed-listing-$index" }
        val lastListingId = listingIds.last()
        val durableRepository = RuntimeDurableInteractionRepository().apply {
            putPending(
                PendingInteraction(
                    operationId = 1L,
                    accountId = RUNTIME_ACCOUNT_ID,
                    listingId = lastListingId,
                    kind = InteractionKind.Like,
                    desiredSelected = true,
                    enqueuedAtEpochMilliseconds = RUNTIME_NOW_EPOCH_MILLISECONDS,
                    attemptCount = 0,
                    status = PendingInteractionStatus.Scheduled(RUNTIME_NOW_EPOCH_MILLISECONDS),
                ),
            )
        }
        val feedRepository = RuntimeFeedRepository(
            refreshSnapshot = runtimeSnapshot(items = listingIds.map { listingId -> runtimeListing(listingId) }),
        )
        val coordinator = InteractionCoordinator(durableRepository, tracker, RuntimeClock, backgroundScope)
        val runtime = runtime(
            feedRepository = feedRepository,
            interactionCoordinator = coordinator,
        )

        runtime.dispatch(ExploreIntent.ViewerContextChanged(tracker.currentScope))
        advanceUntilIdle()

        assertTrue(durableRepository.loadPendingRequests.all { request -> request.size <= 1_000 })
        assertTrue(durableRepository.loadPendingRequests.flatten().contains(lastListingId))
        assertTrue(runtime.state.value.listings.first { listing -> listing.id == lastListingId }.liked)
        assertTrue(runtime.state.value.queuedInteractions.any { queued -> queued.listingId == lastListingId })
        runtime.close()
    }

    @Test
    fun durableFavoriteConfirmationsUseSequenceAndDoNotPublishLegacyBridgeEffects() = runTest {
        val tracker = authenticatedRuntimeTracker()
        val durableRepository = RuntimeDurableInteractionRepository()
        val coordinator = InteractionCoordinator(durableRepository, tracker, RuntimeClock, backgroundScope)
        val runtime = runtime(interactionCoordinator = coordinator)
        runtime.dispatch(ExploreIntent.ViewerContextChanged(tracker.currentScope))
        advanceUntilIdle()
        val scope = tracker.currentScope.toInteractionAccountScope()

        runtime.effects.test {
            durableRepository.enqueueDrain(
                favoriteConfirmationOutcome(
                    scope = scope,
                    operationId = 10L,
                    favorited = true,
                    clientMutationSequence = 2L,
                ),
            )
            coordinator.onForeground()
            settleCoordinatorBackgroundWork()
            assertTrue(runtime.state.value.listings.single().favorited)
            expectNoEvents()

            durableRepository.enqueueDrain(
                favoriteConfirmationOutcome(
                    scope = scope,
                    operationId = 11L,
                    favorited = false,
                    clientMutationSequence = 1L,
                ),
            )
            coordinator.onForeground()
            settleCoordinatorBackgroundWork()
            assertTrue(runtime.state.value.listings.single().favorited)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        runtime.close()
    }

    @Test
    fun durableRejectionRemovesOptimismAndReloadsAuthority() = runTest {
        val tracker = authenticatedRuntimeTracker()
        val durableRepository = RuntimeDurableInteractionRepository()
        val feedRepository = RuntimeFeedRepository()
        val coordinator = InteractionCoordinator(durableRepository, tracker, RuntimeClock, backgroundScope)
        val runtime = runtime(
            feedRepository = feedRepository,
            interactionCoordinator = coordinator,
        )
        runtime.dispatch(ExploreIntent.ViewerContextChanged(tracker.currentScope))
        advanceUntilIdle()
        runtime.dispatch(ExploreIntent.ToggleFavorite(RUNTIME_LISTING_ID))
        advanceUntilIdle()
        val command = durableRepository.submittedCommands.single()
        val operationId = durableRepository.pending.single().operationId
        val refreshCountBeforeRejection = feedRepository.refreshCalls

        durableRepository.enqueueDrain(
            InteractionOperationOutcome.Rejected(
                command = command,
                operationId = operationId,
                reason = InteractionRejectionReason.PermissionDenied,
            ),
        )
        coordinator.onForeground()
        settleCoordinatorBackgroundWork()

        assertFalse(runtime.state.value.listings.single().favorited)
        assertEquals(emptyList(), runtime.state.value.queuedInteractions)
        assertEquals(strings.interactionFailed, runtime.state.value.interactionMessage)
        assertTrue(feedRepository.refreshCalls > refreshCountBeforeRejection)
        runtime.close()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreRuntimeDurableConcurrencyTest {
    @Test
    fun staleAccountCompletionCannotCrossViewerScope() = runTest {
        val tracker = authenticatedRuntimeTracker()
        val durableRepository = RuntimeDurableInteractionRepository()
        val coordinator = InteractionCoordinator(durableRepository, tracker, RuntimeClock, backgroundScope)
        val runtime = runtime(interactionCoordinator = coordinator)
        runtime.dispatch(ExploreIntent.ViewerContextChanged(tracker.currentScope))
        advanceUntilIdle()
        runtime.dispatch(ExploreIntent.ToggleLike(RUNTIME_LISTING_ID))
        advanceUntilIdle()
        val command = durableRepository.submittedCommands.single()
        val operationId = durableRepository.pending.single().operationId

        val nextScope = tracker.update(accountId = "viewer-2", accountSetupComplete = true)
        runtime.dispatch(ExploreIntent.ViewerContextChanged(nextScope))
        durableRepository.enqueueDrain(
            InteractionOperationOutcome.Confirmed(
                command = command,
                confirmation = InteractionConfirmation.Like(
                    operationId = operationId,
                    scope = command.scope,
                    listingId = command.listingId,
                    liked = true,
                    likesCount = 99,
                    mutatedAtEpochMilliseconds = RUNTIME_NOW_EPOCH_MILLISECONDS,
                ),
            ),
        )
        coordinator.onForeground()
        advanceUntilIdle()

        assertEquals(nextScope, runtime.state.value.viewerScope)
        assertFalse(runtime.state.value.listings.single().liked)
        assertEquals(0, runtime.state.value.listings.single().likesCount)
        assertEquals(emptyList(), runtime.state.value.queuedInteractions)
        runtime.close()
    }

    @Test
    fun feedRetryUsesItsCapturedScopeAndCannotRetryAnotherAccountOutbox() = runTest {
        val tracker = authenticatedRuntimeTracker()
        val durableRepository = RuntimeDurableInteractionRepository()
        val coordinator = InteractionCoordinator(durableRepository, tracker, RuntimeClock, backgroundScope)
        val runtime = runtime(interactionCoordinator = coordinator)
        runtime.dispatch(ExploreIntent.ViewerContextChanged(tracker.currentScope))
        advanceUntilIdle()
        durableRepository.retryCalls.clear()

        runtime.dispatch(ExploreIntent.Retry)
        val nextScope = tracker.update(accountId = "viewer-2", accountSetupComplete = true)
        runtime.dispatch(ExploreIntent.ViewerContextChanged(nextScope))
        advanceUntilIdle()

        assertFalse(durableRepository.retryCalls.any { (_, includeManual) -> includeManual })
        durableRepository.retryCalls.clear()
        runtime.dispatch(ExploreIntent.Retry)
        advanceUntilIdle()

        assertEquals(
            listOf(nextScope.toInteractionAccountScope() to true),
            durableRepository.retryCalls.filter { (_, includeManual) -> includeManual },
        )
        runtime.close()
    }

    @Test
    fun gatedFeedCannotOverwriteIndependentDurableLikeAndFavoriteOverlays() = runTest {
        val tracker = authenticatedRuntimeTracker()
        val durableRepository = RuntimeDurableInteractionRepository()
        val feedRepository = RuntimeFeedRepository()
        val coordinator = InteractionCoordinator(durableRepository, tracker, RuntimeClock, backgroundScope)
        val runtime = runtime(
            feedRepository = feedRepository,
            interactionCoordinator = coordinator,
        )
        runtime.dispatch(ExploreIntent.ViewerContextChanged(tracker.currentScope))
        advanceUntilIdle()
        val refreshGate = CompletableDeferred<Unit>()
        feedRepository.refreshGate = refreshGate
        runtime.dispatch(ExploreIntent.Refresh)
        runCurrent()

        runtime.dispatch(ExploreIntent.ToggleLike(RUNTIME_LISTING_ID))
        runtime.dispatch(ExploreIntent.ToggleFavorite(RUNTIME_LISTING_ID))
        runCurrent()
        refreshGate.complete(Unit)
        advanceUntilIdle()

        val listing = runtime.state.value.listings.single()
        assertTrue(listing.liked)
        assertTrue(listing.favorited)
        assertEquals(
            setOf(ExploreInteractionKind.Like, ExploreInteractionKind.Favorite),
            runtime.state.value.queuedInteractions.map(QueuedExploreInteraction::kind).toSet(),
        )
        runtime.close()
    }

    @Test
    fun supersededRapidToggleCannotRemoveTheNewerDurableIntent() = runTest {
        val tracker = authenticatedRuntimeTracker()
        val durableRepository = RuntimeDurableInteractionRepository()
        val coordinator = InteractionCoordinator(durableRepository, tracker, RuntimeClock, backgroundScope)
        val runtime = runtime(interactionCoordinator = coordinator)
        runtime.dispatch(ExploreIntent.ViewerContextChanged(tracker.currentScope))
        advanceUntilIdle()
        runtime.dispatch(ExploreIntent.ToggleLike(RUNTIME_LISTING_ID))
        advanceUntilIdle()
        val firstCommand = durableRepository.submittedCommands.single()
        val firstOperationId = durableRepository.lastSubmittedOutcome.pending.operationId

        runtime.dispatch(ExploreIntent.ToggleLike(RUNTIME_LISTING_ID))
        advanceUntilIdle()
        val latest = durableRepository.pending.single()
        assertTrue(latest.operationId > firstOperationId)
        assertFalse(latest.desiredSelected)

        durableRepository.enqueueDrain(
            InteractionOperationOutcome.Superseded(
                command = firstCommand,
                operationId = firstOperationId,
            ),
        )
        coordinator.onForeground()
        advanceUntilIdle()

        val queued = runtime.state.value.queuedInteractions.single()
        assertEquals(latest.operationId, queued.operationId)
        assertFalse(queued.selected)
        assertFalse(runtime.state.value.listings.single().liked)
        runtime.close()
    }

    @Test
    fun lateConfirmationCannotOverwriteANewerRetryingIntent() = runTest {
        val tracker = authenticatedRuntimeTracker()
        val durableRepository = RuntimeDurableInteractionRepository()
        val coordinator = InteractionCoordinator(durableRepository, tracker, RuntimeClock, backgroundScope)
        val runtime = runtime(interactionCoordinator = coordinator)
        runtime.dispatch(ExploreIntent.ViewerContextChanged(tracker.currentScope))
        advanceUntilIdle()
        runtime.dispatch(ExploreIntent.ToggleLike(RUNTIME_LISTING_ID))
        advanceUntilIdle()
        val firstCommand = durableRepository.submittedCommands.single()
        val firstOperationId = durableRepository.lastSubmittedOutcome.pending.operationId

        runtime.dispatch(ExploreIntent.ToggleLike(RUNTIME_LISTING_ID))
        advanceUntilIdle()
        val secondCommand = durableRepository.submittedCommands.last()
        val secondPending = durableRepository.pending.single().copy(
            attemptCount = 1,
            status = PendingInteractionStatus.Scheduled(RUNTIME_NOW_EPOCH_MILLISECONDS + 1_000L),
        )
        durableRepository.enqueueDrain(
            InteractionOperationOutcome.Retrying(command = secondCommand, pending = secondPending),
        )
        coordinator.onForeground()
        settleCoordinatorBackgroundWork()

        durableRepository.enqueueDrain(likeConfirmationOutcome(firstCommand, firstOperationId, true, 99))
        coordinator.onForeground()
        settleCoordinatorBackgroundWork()

        val queued = runtime.state.value.queuedInteractions.single()
        assertEquals(secondPending.operationId, queued.operationId)
        assertEquals(1, queued.attemptCount)
        assertFalse(runtime.state.value.listings.single().liked)
        assertEquals(0, runtime.state.value.listings.single().likesCount)
        assertTrue(runtime.state.value.isOffline)
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

private data class DurableExploreHarness(
    val tracker: ViewerSessionScopeTracker,
    val durableRepository: RuntimeDurableInteractionRepository,
    val feedRepository: RuntimeFeedRepository,
    val coordinator: InteractionCoordinator,
    val runtime: ExploreRuntime,
) {
    val interactionScope: InteractionAccountScope
        get() = tracker.currentScope.toInteractionAccountScope()

    fun close() = runtime.close()

    fun assertNoQueuedInteraction(listingId: String) {
        assertTrue(runtime.state.value.queuedInteractions.none { queued -> queued.listingId == listingId })
    }
}

private fun TestScope.settleCoordinatorBackgroundWork() {
    runCurrent()
    advanceUntilIdle()
}

private suspend fun TestScope.durableExploreHarness(
    feedRepository: RuntimeFeedRepository = RuntimeFeedRepository(),
    interactions: RuntimeInteractionRepository = RuntimeInteractionRepository(),
): DurableExploreHarness {
    val tracker = authenticatedRuntimeTracker()
    val durableRepository = RuntimeDurableInteractionRepository()
    val coordinator = InteractionCoordinator(durableRepository, tracker, RuntimeClock, backgroundScope)
    val runtime = runtime(feedRepository, interactions, interactionCoordinator = coordinator)
    runtime.dispatch(ExploreIntent.ViewerContextChanged(tracker.currentScope))
    advanceUntilIdle()
    return DurableExploreHarness(tracker, durableRepository, feedRepository, coordinator, runtime)
}

private data class BlockedExploreEventCollector(
    private val releaseGate: CompletableDeferred<Unit>,
    private val job: Job,
) {
    fun release() {
        releaseGate.complete(Unit)
    }

    fun close() {
        release()
        job.cancel()
    }
}

private suspend fun TestScope.blockExploreEventCollector(
    coordinator: InteractionCoordinator,
): BlockedExploreEventCollector {
    val release = CompletableDeferred<Unit>()
    val job = backgroundScope.launch {
        coordinator.events.collect { release.await() }
    }
    runCurrent()
    return BlockedExploreEventCollector(release, job)
}

private data class BlockedExploreHydration(
    val command: InteractionCommand,
    val gate: CompletableDeferred<Unit>,
)

private suspend fun TestScope.blockExploreHydration(harness: DurableExploreHarness): BlockedExploreHydration {
    harness.runtime.dispatch(ExploreIntent.ToggleLike(RUNTIME_LISTING_ID))
    advanceUntilIdle()
    val command = harness.durableRepository.submittedCommands.single()
    val operationId = harness.durableRepository.pending.single().operationId
    val gate = CompletableDeferred<Unit>()
    val started = CompletableDeferred<Unit>()
    harness.durableRepository.loadPendingGate = gate
    harness.durableRepository.loadPendingStarted = started
    harness.durableRepository.enqueueDrain(
        InteractionOperationOutcome.Superseded(command, operationId),
    )
    harness.coordinator.onForeground()
    runCurrent()
    assertTrue(started.isCompleted)
    return BlockedExploreHydration(command, gate)
}

private suspend fun TestScope.triggerDroppedExploreRejection(
    harness: DurableExploreHarness,
    scope: InteractionAccountScope,
): Int {
    val target = submitQueuedLike(harness.coordinator, scope, RUNTIME_OVERTAKE_LISTING_ID)
    runCurrent()
    val rejection = InteractionOperationOutcome.Rejected(
        command = target.command,
        operationId = target.pending.operationId,
        reason = InteractionRejectionReason.PermissionDenied,
    )
    harness.durableRepository.enqueueDrain(
        *(overflowExploreConfirmations(scope, count = 101) + rejection).toTypedArray(),
    )
    val refreshCalls = harness.feedRepository.refreshCalls
    harness.durableRepository.loadPendingFailuresRemaining = 3
    harness.coordinator.onForeground()
    runCurrent()
    assertTrue(harness.coordinator.reconciliationSignals.value != null)
    return refreshCalls
}

private suspend fun submitQueuedLike(
    coordinator: InteractionCoordinator,
    scope: InteractionAccountScope,
    listingId: String,
): InteractionSubmitOutcome.Queued = assertIs(
    assertIs<DomainResult.Success<InteractionSubmitOutcome>>(
        coordinator.submit(scope, listingId, InteractionKind.Like, desiredSelected = true),
    ).value,
)

private suspend fun InteractionCoordinator.purgeResumeAndAcknowledge(accountId: String) {
    commitAccountDeletionBlock(accountId)
    resumeAfterAccountDeletionFailure(accountId)
    deliveryCommitGate.acknowledgeReconciliation(
        requireNotNull(reconciliationSignals.value),
        InteractionReconciliationConsumer.Explore,
    )
    deliveryCommitGate.acknowledgeReconciliation(
        requireNotNull(reconciliationSignals.value),
        InteractionReconciliationConsumer.Favorites,
    )
    assertNull(reconciliationSignals.value)
}

private fun runtimeExploreUiState(viewerScope: ViewerSessionScope, nextCursor: String? = null): ExploreUiState =
    initialExploreUiState(strings).copy(
        listings = listOf(runtimeExploreItem()),
        nextCursor = nextCursor,
        viewerScope = viewerScope,
    )

private fun runtimeExploreItem(id: String = RUNTIME_LISTING_ID): ExploreListingItem = ExploreListingItem(
    id = id,
    title = "Porte du non-retour",
    cityLabel = "Ouidah",
    coverImageUrl = null,
    price = null,
)

private fun runtimeLikePending(): PendingInteraction = PendingInteraction(
    operationId = 1L,
    accountId = RUNTIME_ACCOUNT_ID,
    listingId = RUNTIME_LISTING_ID,
    kind = InteractionKind.Like,
    desiredSelected = true,
    enqueuedAtEpochMilliseconds = RUNTIME_NOW_EPOCH_MILLISECONDS,
    attemptCount = 0,
    status = PendingInteractionStatus.Scheduled(RUNTIME_NOW_EPOCH_MILLISECONDS),
)

private fun runtimeLikeCommand(scope: InteractionAccountScope): InteractionCommand = InteractionCommand(
    scope = scope,
    listingId = RUNTIME_LISTING_ID,
    kind = InteractionKind.Like,
    desiredSelected = true,
)

private fun TestScope.runtime(
    feedRepository: RuntimeFeedRepository = RuntimeFeedRepository(),
    interactions: RuntimeInteractionRepository = RuntimeInteractionRepository(),
    preferences: AppPreferencesRepository? = null,
    interactionCoordinator: InteractionCoordinator? = null,
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
    interactionCoordinator = interactionCoordinator,
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
    var refreshCalls: Int = 0
        private set

    override suspend fun readCached(query: ExploreFeedQuery): DomainResult<ExploreFeedSnapshot?> =
        DomainResult.Success(null)

    override suspend fun refresh(query: ExploreFeedQuery): DomainResult<ExploreFeedSnapshot> {
        refreshCalls += 1
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
    var likeCalls: Int = 0
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

    override suspend fun likeListing(listingId: String): DomainResult<ListingViewerInteraction> {
        likeCalls += 1
        return selectedInteraction(listingId = listingId, liked = true, favorited = false, persist = true)
    }

    override suspend fun unlikeListing(listingId: String): DomainResult<ListingViewerInteraction> {
        likeCalls += 1
        return selectedInteraction(listingId = listingId, liked = false, favorited = false, persist = true)
    }

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

private class RuntimeDurableInteractionRepository : InteractionRepository {
    val submittedCommands = mutableListOf<InteractionCommand>()
    val retryCalls = mutableListOf<Pair<InteractionAccountScope, Boolean>>()
    val loadPendingRequests = mutableListOf<List<String>>()
    var submitGate: CompletableDeferred<Unit>? = null
    var submitStarted: CompletableDeferred<Unit>? = null
    var pending: List<PendingInteraction> = emptyList()
        private set
    var submitFailure: DomainError? = null
    var loadPendingFailuresRemaining: Int = 0
    var loadPendingGate: CompletableDeferred<Unit>? = null
    var loadPendingStarted: CompletableDeferred<Unit>? = null
    var captureLoadPendingBeforeGate: Boolean = false
    lateinit var lastSubmittedOutcome: InteractionSubmitOutcome.Queued
        private set
    private val drainOutcomes = ArrayDeque<List<InteractionOperationOutcome>>()
    private var nextOperationId = 0L

    override suspend fun submit(command: InteractionCommand): DomainResult<InteractionSubmitOutcome> {
        submittedCommands += command
        submitGate?.also { gate ->
            submitGate = null
            submitStarted?.complete(Unit)
            submitStarted = null
            gate.await()
        }
        submitFailure?.let { error -> return DomainResult.Failure(error) }
        val existing = pending.firstOrNull { interaction ->
            interaction.accountId == command.scope.accountId &&
                interaction.listingId == command.listingId &&
                interaction.kind == command.kind
        }
        val operationId = if (existing?.desiredSelected == command.desiredSelected) {
            existing.operationId
        } else {
            ++nextOperationId
        }
        val queued = PendingInteraction(
            operationId = operationId,
            accountId = command.scope.accountId,
            listingId = command.listingId,
            kind = command.kind,
            desiredSelected = command.desiredSelected,
            enqueuedAtEpochMilliseconds = RUNTIME_NOW_EPOCH_MILLISECONDS,
            attemptCount = 0,
            status = PendingInteractionStatus.Scheduled(RUNTIME_NOW_EPOCH_MILLISECONDS),
        )
        pending = pending.filterNot { interaction ->
            interaction.accountId == command.scope.accountId &&
                interaction.listingId == command.listingId &&
                interaction.kind == command.kind
        } + queued
        lastSubmittedOutcome = InteractionSubmitOutcome.Queued(command = command, pending = queued)
        return DomainResult.Success(lastSubmittedOutcome)
    }

    override suspend fun loadPending(
        accountId: String,
        listingIds: List<String>,
    ): DomainResult<List<PendingInteraction>> {
        loadPendingRequests += listingIds
        val captured = pendingFor(accountId, listingIds).takeIf { captureLoadPendingBeforeGate }
        loadPendingGate?.also { gate ->
            loadPendingGate = null
            loadPendingStarted?.complete(Unit)
            loadPendingStarted = null
            gate.await()
        }
        if (loadPendingFailuresRemaining > 0) {
            loadPendingFailuresRemaining -= 1
            return DomainResult.Failure(DomainError.LocalStorageUnavailable())
        }
        return DomainResult.Success(captured ?: pendingFor(accountId, listingIds))
    }

    private fun pendingFor(accountId: String, listingIds: List<String>): List<PendingInteraction> =
        pending.filter { interaction ->
            interaction.accountId == accountId &&
                (listingIds.isEmpty() || interaction.listingId in listingIds)
        }

    override suspend fun drainDue(scope: InteractionAccountScope): DomainResult<InteractionDrainOutcome> {
        val outcomes = drainOutcomes.removeFirstOrNull().orEmpty()
        outcomes.forEach(::applyOutcome)
        return DomainResult.Success(InteractionDrainOutcome(scope = scope, operations = outcomes))
    }

    override suspend fun nextAttemptAt(accountId: String): DomainResult<Long?> = DomainResult.Success(null)

    override suspend fun retryAccount(
        scope: InteractionAccountScope,
        includeManualFailures: Boolean,
    ): DomainResult<Int> {
        retryCalls += scope to includeManualFailures
        return DomainResult.Success(0)
    }

    fun enqueueDrain(vararg outcomes: InteractionOperationOutcome) {
        drainOutcomes += outcomes.toList()
    }

    fun putPending(interaction: PendingInteraction) {
        nextOperationId = maxOf(nextOperationId, interaction.operationId)
        pending = pending.filterNot { current ->
            current.accountId == interaction.accountId &&
                current.listingId == interaction.listingId &&
                current.kind == interaction.kind
        } + interaction
    }

    private fun applyOutcome(outcome: InteractionOperationOutcome) {
        when (outcome) {
            is InteractionOperationOutcome.Retrying -> replacePending(outcome.pending)
            is InteractionOperationOutcome.Confirmed -> removePending(
                listingId = outcome.command.listingId,
                kind = outcome.command.kind,
                operationId = outcome.confirmation.operationId,
            )
            is InteractionOperationOutcome.Rejected -> removePending(
                listingId = outcome.command.listingId,
                kind = outcome.command.kind,
                operationId = outcome.operationId,
            )
            is InteractionOperationOutcome.Superseded -> removePending(
                listingId = outcome.command.listingId,
                kind = outcome.command.kind,
                operationId = outcome.operationId,
            )
        }
    }

    private fun replacePending(replacement: PendingInteraction) {
        val hasMatchingOperation = pending.any { interaction ->
            interaction.operationId == replacement.operationId
        }
        if (!hasMatchingOperation) return
        pending = pending.map { interaction ->
            if (interaction.operationId == replacement.operationId) replacement else interaction
        }
    }

    private fun removePending(listingId: String, kind: InteractionKind, operationId: Long) {
        pending = pending.filterNot { interaction ->
            interaction.listingId == listingId &&
                interaction.kind == kind &&
                interaction.operationId == operationId
        }
    }
}

private fun authenticatedRuntimeTracker(): ViewerSessionScopeTracker = ViewerSessionScopeTracker().apply {
    update(accountId = RUNTIME_ACCOUNT_ID, accountSetupComplete = true)
}

private fun ViewerSessionScope.toInteractionAccountScope(): InteractionAccountScope = InteractionAccountScope(
    accountId = accountId ?: error("Authenticated scope required by test fixture."),
    epoch = epoch,
)

private fun favoriteConfirmationOutcome(
    scope: InteractionAccountScope,
    operationId: Long,
    favorited: Boolean,
    clientMutationSequence: Long,
): InteractionOperationOutcome.Confirmed {
    val command = InteractionCommand(
        scope = scope,
        listingId = RUNTIME_LISTING_ID,
        kind = InteractionKind.Favorite,
        desiredSelected = favorited,
    )
    return InteractionOperationOutcome.Confirmed(
        command = command,
        confirmation = InteractionConfirmation.Favorite(
            operationId = operationId,
            scope = scope,
            listingId = RUNTIME_LISTING_ID,
            favorited = favorited,
            favoritedAtEpochMilliseconds = if (favorited) RUNTIME_NOW_EPOCH_MILLISECONDS else null,
            clientMutationSequence = clientMutationSequence,
        ),
    )
}

private fun likeConfirmationOutcome(
    command: InteractionCommand,
    operationId: Long,
    liked: Boolean,
    likesCount: Int?,
): InteractionOperationOutcome.Confirmed = InteractionOperationOutcome.Confirmed(
    command = command,
    confirmation = InteractionConfirmation.Like(
        operationId = operationId,
        scope = command.scope,
        listingId = command.listingId,
        liked = liked,
        likesCount = likesCount,
        mutatedAtEpochMilliseconds = RUNTIME_NOW_EPOCH_MILLISECONDS,
    ),
)

private fun overflowExploreConfirmations(
    scope: InteractionAccountScope,
    count: Int,
): List<InteractionOperationOutcome> = (1..count).map { index ->
    val listingId = "overflow-explore-$index"
    val command = InteractionCommand(
        scope = scope,
        listingId = listingId,
        kind = InteractionKind.Like,
        desiredSelected = true,
    )
    InteractionOperationOutcome.Confirmed(
        command = command,
        confirmation = InteractionConfirmation.Like(
            operationId = 1_000L + index,
            scope = scope,
            listingId = listingId,
            liked = true,
            likesCount = index,
            mutatedAtEpochMilliseconds = RUNTIME_NOW_EPOCH_MILLISECONDS,
        ),
    )
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

private fun runtimeListing(id: String = RUNTIME_LISTING_ID, likesCount: Int = 0): ListingSummary = ListingSummary(
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
    likesCount = likesCount,
    verified = true,
    sponsoredUntilEpochMilliseconds = null,
)

private const val RUNTIME_LISTING_ID = "ouidah-gate"
private const val RUNTIME_OVERTAKE_LISTING_ID = "overtake-listing"
private const val RUNTIME_NOW_EPOCH_MILLISECONDS = 1_000L
private const val RUNTIME_COTONOU_LATITUDE = 6.3703
private const val RUNTIME_COTONOU_LONGITUDE = 2.3912
private const val RUNTIME_OUIDAH_LATITUDE = 6.3631
private const val RUNTIME_OUIDAH_LONGITUDE = 2.0851
private const val RUNTIME_OUTSIDE_BENIN_LATITUDE = 48.8566
private const val RUNTIME_OUTSIDE_BENIN_LONGITUDE = 2.3522

private const val RUNTIME_ACCOUNT_ID = "viewer-1"
private val AUTHENTICATED_SCOPE = ViewerSessionScope(accountId = RUNTIME_ACCOUNT_ID, epoch = 1L)
