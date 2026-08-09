package com.kwabor.shared.presentation.favorites

import app.cash.turbine.test
import com.kwabor.shared.domain.catalog.ListingPageRequest
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.favorites.FavoriteListingPage
import com.kwabor.shared.domain.favorites.FavoriteMutation
import com.kwabor.shared.domain.favorites.FavoritesRepository
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.session.ViewerSessionScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val strings = stringsFor(AppLocale.French).favorites

@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesRuntimeTest {
    @Test
    fun emptyStateIsTerminalOnlyWhenNoNextPageExists() {
        val nonTerminal = FavoritesUiState(
            isAccountReady = true,
            nextCursor = "cursor-2",
        )

        assertFalse(nonTerminal.isEmpty)
        assertTrue(nonTerminal.copy(nextCursor = null).isEmpty)
    }

    @Test
    fun screenAppearanceNeverLoadsWithoutACompletedAccount() = runTest {
        val repository = RuntimeFavoritesRepository()
        val runtime = runtime(repository)

        runtime.dispatch(FavoritesIntent.ScreenAppeared)
        runtime.dispatch(FavoritesIntent.ScreenAppeared)
        runtime.dispatch(FavoritesIntent.ViewerContextChanged(ViewerSessionScope.InitialGuest))
        advanceUntilIdle()

        assertTrue(repository.pageRequests.isEmpty())
        assertFalse(runtime.state.value.isAccountReady)
        assertFalse(runtime.state.value.isLoading)

        runtime.dispatch(FavoritesIntent.ViewerContextChanged(ACCOUNT_A_SCOPE))
        assertTrue(runtime.state.value.items.isEmpty())
        assertTrue(runtime.state.value.isAccountReady)
        advanceUntilIdle()

        assertEquals(1, repository.pageRequests.size)
        runtime.close()
    }

    @Test
    fun retryKeepsContentOfflineVisibleUntilTheNewLoadCompletes() = runTest {
        val retryGate = CompletableDeferred<Unit>()
        val repository = RuntimeFavoritesRepository(
            pageResponses = mutableListOf(
                RuntimePageResponse(result = DomainResult.Failure(DomainError.NetworkUnavailable())),
                RuntimePageResponse.success(favoritePage(), gate = retryGate),
            ),
        )
        val runtime = activeRuntime(repository)
        assertTrue(runtime.state.value.isOffline)

        runtime.dispatch(FavoritesIntent.Retry)
        runCurrent()

        assertTrue(runtime.state.value.isLoading)
        assertTrue(runtime.state.value.isOffline)
        retryGate.complete(Unit)
        advanceUntilIdle()
        assertFalse(runtime.state.value.isOffline)
        runtime.close()
    }

    @Test
    fun accountSwitchPurgesSynchronouslyAndRejectsTheLatePreviousPage() = runTest {
        val staleGate = CompletableDeferred<Unit>()
        val repository = RuntimeFavoritesRepository(
            pageResponses = mutableListOf(
                RuntimePageResponse.success(favoritePage(id = "account-a-item")),
                RuntimePageResponse.success(
                    favoritePage(id = "stale-account-a-item"),
                    gate = staleGate,
                    nonCancellable = true,
                ),
                RuntimePageResponse.success(favoritePage(id = "account-b-item")),
            ),
        )
        val runtime = runtime(repository)
        runtime.dispatch(FavoritesIntent.ViewerContextChanged(ACCOUNT_A_SCOPE))
        runtime.dispatch(FavoritesIntent.ScreenAppeared)
        advanceUntilIdle()
        assertEquals(listOf("account-a-item"), runtime.state.value.items.map { item -> item.id })

        runtime.dispatch(FavoritesIntent.Refresh)
        runCurrent()
        runtime.dispatch(FavoritesIntent.ViewerContextChanged(ACCOUNT_B_SCOPE))

        assertTrue(runtime.state.value.items.isEmpty())
        assertTrue(runtime.state.value.isAccountReady)
        runCurrent()
        assertEquals(listOf("account-b-item"), runtime.state.value.items.map { item -> item.id })

        staleGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("account-b-item"), runtime.state.value.items.map { item -> item.id })

        runtime.dispatch(FavoritesIntent.ViewerContextChanged(LOGGED_OUT_SCOPE))
        assertTrue(runtime.state.value.items.isEmpty())
        assertFalse(runtime.state.value.isAccountReady)
        advanceUntilIdle()
        assertEquals(3, repository.pageRequests.size)
        runtime.close()
    }

    @Test
    fun newerFilterGenerationWinsAgainstANonCancellableOlderRequest() = runTest {
        val staleGate = CompletableDeferred<Unit>()
        val repository = RuntimeFavoritesRepository(
            pageResponses = mutableListOf(
                RuntimePageResponse.success(
                    favoritePage(id = "old-place"),
                    gate = staleGate,
                    nonCancellable = true,
                ),
                RuntimePageResponse.success(
                    favoritePage(id = "event", type = ListingType.Event),
                ),
            ),
        )
        val runtime = runtime(repository)
        runtime.dispatch(FavoritesIntent.ViewerContextChanged(ACCOUNT_A_SCOPE))
        runtime.dispatch(FavoritesIntent.ScreenAppeared)
        runCurrent()

        runtime.dispatch(FavoritesIntent.SelectFilter(FavoritesFilter.Events))
        runCurrent()

        assertEquals(FavoritesFilter.Events, runtime.state.value.selectedFilter)
        assertEquals(listOf("event"), runtime.state.value.items.map { item -> item.id })
        staleGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("event"), runtime.state.value.items.map { item -> item.id })
        runtime.close()
    }

    @Test
    fun repeatedLoadNextStartsOnlyOneCursorRequest() = runTest {
        val appendGate = CompletableDeferred<Unit>()
        val repository = RuntimeFavoritesRepository(
            pageResponses = mutableListOf(
                RuntimePageResponse.success(favoritePage(nextCursor = "next")),
                RuntimePageResponse.success(
                    FavoriteListingPage(
                        items = listOf(favoriteListing(id = "second-item")),
                        nextCursor = null,
                    ),
                    gate = appendGate,
                ),
            ),
        )
        val runtime = activeRuntime(repository)

        runtime.dispatch(FavoritesIntent.LoadNext)
        runtime.dispatch(FavoritesIntent.LoadNext)
        runCurrent()

        assertEquals(2, repository.pageRequests.size)
        assertEquals(listOf(null, "next"), repository.pageRequests.map { request -> request.second.cursor })
        appendGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, runtime.state.value.items.size)
        runtime.close()
    }

    @Test
    fun screenReentryDuringAppendRefreshesAfterTheAppendCompletes() = runTest {
        val appendGate = CompletableDeferred<Unit>()
        val appendedListingId = "22222222-2222-4222-8222-222222222222"
        val externallyAddedListingId = "33333333-3333-4333-8333-333333333333"
        val repository = RuntimeFavoritesRepository(
            pageResponses = mutableListOf(
                RuntimePageResponse.success(favoritePage(nextCursor = "cursor-2")),
                RuntimePageResponse.success(
                    favoritePage(id = appendedListingId),
                    gate = appendGate,
                ),
                RuntimePageResponse.success(favoritePage(id = externallyAddedListingId)),
            ),
        )
        val runtime = activeRuntime(repository)
        runtime.dispatch(FavoritesIntent.LoadNext)
        runCurrent()

        runtime.dispatch(FavoritesIntent.ScreenDisappeared)
        runtime.dispatch(
            FavoritesIntent.ExternalFavoriteStateChanged(
                listingId = externallyAddedListingId,
                favorited = true,
                scope = ACCOUNT_A_SCOPE,
            ),
        )
        runtime.dispatch(FavoritesIntent.ScreenAppeared)
        runCurrent()
        assertEquals(2, repository.pageRequests.size)

        appendGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            listOf(null, "cursor-2", null),
            repository.pageRequests.map { request -> request.second.cursor },
        )
        assertEquals(listOf(externallyAddedListingId), runtime.state.value.items.map { item -> item.id })
        runtime.close()
    }

    @Test
    fun confirmedRemovalAutomaticallyLoadsTheNextPageWhenItEmptiesTheVisiblePage() = runTest {
        val nextListingId = "22222222-2222-4222-8222-222222222222"
        val repository = RuntimeFavoritesRepository(
            pageResponses = mutableListOf(
                RuntimePageResponse.success(favoritePage(nextCursor = "cursor-2")),
                RuntimePageResponse.success(favoritePage(id = nextListingId)),
            ),
        )
        val runtime = activeRuntime(repository)

        runtime.dispatch(FavoritesIntent.RemoveFavorite(FAVORITE_ID))
        advanceUntilIdle()

        assertEquals(listOf(null, "cursor-2"), repository.pageRequests.map { request -> request.second.cursor })
        assertEquals(listOf(nextListingId), runtime.state.value.items.map { item -> item.id })
        runtime.close()
    }

    @Test
    fun externalRemovalAutomaticallyLoadsTheNextPageWhenItEmptiesTheVisiblePage() = runTest {
        val nextListingId = "22222222-2222-4222-8222-222222222222"
        val repository = RuntimeFavoritesRepository(
            pageResponses = mutableListOf(
                RuntimePageResponse.success(favoritePage(nextCursor = "cursor-2")),
                RuntimePageResponse.success(favoritePage(id = nextListingId)),
            ),
        )
        val runtime = activeRuntime(repository)

        runtime.dispatch(
            FavoritesIntent.ExternalFavoriteStateChanged(
                listingId = FAVORITE_ID,
                favorited = false,
                scope = ACCOUNT_A_SCOPE,
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf(null, "cursor-2"), repository.pageRequests.map { request -> request.second.cursor })
        assertEquals(listOf(nextListingId), runtime.state.value.items.map { item -> item.id })
        assertTrue(repository.mutationRequests.isEmpty())
        runtime.close()
    }

    @Test
    fun failedAutomaticAppendIsNotRetriedByAnotherRemovalEvent() = runTest {
        val repository = RuntimeFavoritesRepository(
            pageResponses = mutableListOf(
                RuntimePageResponse.success(favoritePage(nextCursor = "cursor-2")),
                RuntimePageResponse(result = DomainResult.Failure(DomainError.NetworkUnavailable())),
            ),
        )
        val runtime = activeRuntime(repository)

        val removal = FavoritesIntent.ExternalFavoriteStateChanged(
            listingId = FAVORITE_ID,
            favorited = false,
            scope = ACCOUNT_A_SCOPE,
        )
        runtime.dispatch(removal)
        advanceUntilIdle()

        assertEquals(2, repository.pageRequests.size)
        assertTrue(runtime.state.value.items.isEmpty())
        assertEquals(strings.loadMoreFailed, runtime.state.value.appendErrorMessage)
        assertEquals("cursor-2", runtime.state.value.nextCursor)
        assertFalse(runtime.state.value.isAppending)

        runtime.dispatch(removal)
        advanceUntilIdle()

        assertEquals(2, repository.pageRequests.size)
        runtime.close()
    }

    @Test
    fun externalRemovalDuringInitialLoadBackfillsAfterTheLatePageIsTombstoned() = runTest {
        val initialLoadGate = CompletableDeferred<Unit>()
        val nextListingId = "22222222-2222-4222-8222-222222222222"
        val repository = RuntimeFavoritesRepository(
            pageResponses = mutableListOf(
                RuntimePageResponse.success(
                    favoritePage(nextCursor = "cursor-2"),
                    gate = initialLoadGate,
                    nonCancellable = true,
                ),
                RuntimePageResponse.success(favoritePage(id = nextListingId)),
            ),
        )
        val runtime = runtime(repository)
        runtime.dispatch(FavoritesIntent.ViewerContextChanged(ACCOUNT_A_SCOPE))
        runtime.dispatch(FavoritesIntent.ScreenAppeared)
        runCurrent()

        runtime.dispatch(
            FavoritesIntent.ExternalFavoriteStateChanged(
                listingId = FAVORITE_ID,
                favorited = false,
                scope = ACCOUNT_A_SCOPE,
            ),
        )
        runCurrent()
        assertEquals(1, repository.pageRequests.size)

        initialLoadGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(null, "cursor-2"), repository.pageRequests.map { request -> request.second.cursor })
        assertEquals(listOf(nextListingId), runtime.state.value.items.map { item -> item.id })
        runtime.close()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesRuntimeMutationTest {
    @Test
    fun confirmedRemovalDuringRefreshBackfillsAfterTheLatePageIsTombstoned() = runTest {
        val refreshGate = CompletableDeferred<Unit>()
        val nextListingId = "22222222-2222-4222-8222-222222222222"
        val repository = RuntimeFavoritesRepository(
            pageResponses = mutableListOf(
                RuntimePageResponse.success(favoritePage(nextCursor = "cursor-2")),
                RuntimePageResponse.success(
                    favoritePage(nextCursor = "cursor-2"),
                    gate = refreshGate,
                    nonCancellable = true,
                ),
                RuntimePageResponse.success(favoritePage(id = nextListingId)),
            ),
        )
        val runtime = activeRuntime(repository)

        runtime.dispatch(FavoritesIntent.Refresh)
        runCurrent()
        runtime.dispatch(FavoritesIntent.RemoveFavorite(FAVORITE_ID))
        runCurrent()

        assertTrue(runtime.state.value.items.isEmpty())
        assertEquals(2, repository.pageRequests.size)

        refreshGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            listOf(null, null, "cursor-2"),
            repository.pageRequests.map { request -> request.second.cursor },
        )
        assertEquals(listOf(nextListingId), runtime.state.value.items.map { item -> item.id })
        runtime.close()
    }

    @Test
    fun removalRegistersBackfillWhenAnotherCardRemainsDuringRefresh() = runTest {
        val refreshGate = CompletableDeferred<Unit>()
        val remainingListingId = "22222222-2222-4222-8222-222222222222"
        val nextListingId = "33333333-3333-4333-8333-333333333333"
        val repository = RuntimeFavoritesRepository(
            pageResponses = mutableListOf(
                RuntimePageResponse.success(
                    FavoriteListingPage(
                        items = listOf(
                            favoriteListing(id = FAVORITE_ID),
                            favoriteListing(id = remainingListingId),
                        ),
                        nextCursor = "cursor-2",
                    ),
                ),
                RuntimePageResponse.success(
                    favoritePage(nextCursor = "cursor-2"),
                    gate = refreshGate,
                    nonCancellable = true,
                ),
                RuntimePageResponse.success(favoritePage(id = nextListingId)),
            ),
        )
        val runtime = activeRuntime(repository)

        runtime.dispatch(FavoritesIntent.Refresh)
        runCurrent()
        runtime.dispatch(FavoritesIntent.RemoveFavorite(FAVORITE_ID))
        runCurrent()

        assertEquals(listOf(remainingListingId), runtime.state.value.items.map { item -> item.id })
        refreshGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            listOf(null, null, "cursor-2"),
            repository.pageRequests.map { request -> request.second.cursor },
        )
        assertEquals(listOf(nextListingId), runtime.state.value.items.map { item -> item.id })
        runtime.close()
    }

    @Test
    fun removalsDuringAppendBackfillPastAResponseContainingOnlyTombstones() = runTest {
        val appendGate = CompletableDeferred<Unit>()
        val appendedListingId = "22222222-2222-4222-8222-222222222222"
        val nextListingId = "33333333-3333-4333-8333-333333333333"
        val repository = RuntimeFavoritesRepository(
            pageResponses = mutableListOf(
                RuntimePageResponse.success(favoritePage(nextCursor = "cursor-2")),
                RuntimePageResponse.success(
                    favoritePage(id = appendedListingId, nextCursor = "cursor-3"),
                    gate = appendGate,
                    nonCancellable = true,
                ),
                RuntimePageResponse.success(favoritePage(id = nextListingId)),
            ),
        )
        val runtime = activeRuntime(repository)

        runtime.dispatch(FavoritesIntent.LoadNext)
        runCurrent()
        listOf(FAVORITE_ID, appendedListingId).forEach { listingId ->
            runtime.dispatch(
                FavoritesIntent.ExternalFavoriteStateChanged(
                    listingId = listingId,
                    favorited = false,
                    scope = ACCOUNT_A_SCOPE,
                ),
            )
        }
        runCurrent()

        assertTrue(runtime.state.value.items.isEmpty())
        assertEquals(2, repository.pageRequests.size)

        appendGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            listOf(null, "cursor-2", "cursor-3"),
            repository.pageRequests.map { request -> request.second.cursor },
        )
        assertEquals(listOf(nextListingId), runtime.state.value.items.map { item -> item.id })
        runtime.close()
    }

    @Test
    fun repeatedRemovalStartsOnlyOneMutationForTheListing() = runTest {
        val mutationGate = CompletableDeferred<Unit>()
        val repository = RuntimeFavoritesRepository(
            mutationResponses = mutableListOf(
                RuntimeMutationResponse.success(FAVORITE_ID, gate = mutationGate),
            ),
        )
        val runtime = activeRuntime(repository)

        runtime.dispatch(FavoritesIntent.RemoveFavorite(FAVORITE_ID))
        runtime.dispatch(FavoritesIntent.RemoveFavorite(FAVORITE_ID))
        runCurrent()

        assertEquals(listOf(FAVORITE_ID to false), repository.mutationRequests)
        mutationGate.complete(Unit)
        advanceUntilIdle()
        runtime.close()
    }

    @Test
    fun confirmedRemovalWinsAgainstAPageThatStartedEarlier() = runTest {
        val refreshGate = CompletableDeferred<Unit>()
        val repository = RuntimeFavoritesRepository(
            pageResponses = mutableListOf(
                RuntimePageResponse.success(favoritePage()),
                RuntimePageResponse.success(
                    favoritePage(),
                    gate = refreshGate,
                    nonCancellable = true,
                ),
            ),
        )
        val runtime = activeRuntime(repository)
        val changed = async { runtime.effects.first() }

        runtime.dispatch(FavoritesIntent.Refresh)
        runCurrent()
        runtime.dispatch(FavoritesIntent.RemoveFavorite(FAVORITE_ID))
        runCurrent()

        assertIs<FavoritesEffect.FavoriteChanged>(changed.await())
        assertTrue(runtime.state.value.items.isEmpty())
        refreshGate.complete(Unit)
        advanceUntilIdle()
        assertTrue(runtime.state.value.items.isEmpty())
        runtime.close()
    }

    @Test
    fun failedRemovalKeepsTheCardAndExposesAnIndependentMessage() = runTest {
        val repository = RuntimeFavoritesRepository(
            mutationResponses = mutableListOf(
                RuntimeMutationResponse(
                    result = DomainResult.Failure(DomainError.NetworkUnavailable()),
                ),
            ),
        )
        val runtime = activeRuntime(repository)

        runtime.dispatch(FavoritesIntent.RemoveFavorite(FAVORITE_ID))
        advanceUntilIdle()

        assertEquals(listOf(FAVORITE_ID), runtime.state.value.items.map { item -> item.id })
        assertEquals(strings.removeFailed, runtime.state.value.mutationMessage)
        assertTrue(runtime.state.value.isOffline)
        assertTrue(runtime.state.value.removingListingIds.isEmpty())
        runtime.close()
    }

    @Test
    fun externalChangeForAnotherListingPreservesTheRemovalFailureMessage() = runTest {
        val otherListingId = "22222222-2222-4222-8222-222222222222"
        val repository = RuntimeFavoritesRepository(
            pageResponses = mutableListOf(
                RuntimePageResponse.success(
                    FavoriteListingPage(
                        items = listOf(
                            favoriteListing(id = FAVORITE_ID),
                            favoriteListing(id = otherListingId),
                        ),
                        nextCursor = null,
                    ),
                ),
            ),
            mutationResponses = mutableListOf(
                RuntimeMutationResponse(
                    result = DomainResult.Failure(DomainError.NetworkUnavailable()),
                ),
            ),
        )
        val runtime = activeRuntime(repository)
        runtime.dispatch(FavoritesIntent.RemoveFavorite(FAVORITE_ID))
        advanceUntilIdle()

        runtime.dispatch(
            FavoritesIntent.ExternalFavoriteStateChanged(
                listingId = otherListingId,
                favorited = false,
                scope = ACCOUNT_A_SCOPE,
            ),
        )
        advanceUntilIdle()

        assertEquals(strings.removeFailed, runtime.state.value.mutationMessage)
        assertEquals(listOf(FAVORITE_ID), runtime.state.value.items.map { item -> item.id })
        runtime.close()
    }

    @Test
    fun successfulRemovalForOneListingPreservesAnotherConcurrentOfflineFailure() = runTest {
        val successfulListingId = FAVORITE_ID
        val failingListingId = "22222222-2222-4222-8222-222222222222"
        val successGate = CompletableDeferred<Unit>()
        val failureGate = CompletableDeferred<Unit>()
        val repository = concurrentRemovalRepository(
            successfulListingId = successfulListingId,
            failingListingId = failingListingId,
            successGate = successGate,
            failureGate = failureGate,
        )
        val runtime = activeRuntime(repository)

        runtime.dispatch(FavoritesIntent.RemoveFavorite(successfulListingId))
        runtime.dispatch(FavoritesIntent.RemoveFavorite(failingListingId))
        runCurrent()
        failureGate.complete(Unit)
        runCurrent()

        assertEquals(strings.removeFailed, runtime.state.value.mutationMessage)
        assertTrue(runtime.state.value.isOffline)
        successGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(failingListingId), runtime.state.value.items.map { item -> item.id })
        assertEquals(strings.removeFailed, runtime.state.value.mutationMessage)
        assertTrue(runtime.state.value.isOffline)
        runtime.close()
    }

    @Test
    fun authoritativeRefreshCanShowAnExternallyReaddedFavorite() = runTest {
        val repository = RuntimeFavoritesRepository(
            pageResponses = mutableListOf(
                RuntimePageResponse.success(favoritePage()),
                RuntimePageResponse.success(favoritePage()),
            ),
        )
        val runtime = activeRuntime(repository)
        runtime.dispatch(FavoritesIntent.RemoveFavorite(FAVORITE_ID))
        advanceUntilIdle()
        assertTrue(runtime.state.value.items.isEmpty())

        runtime.dispatch(FavoritesIntent.Refresh)
        advanceUntilIdle()

        assertEquals(listOf(FAVORITE_ID), runtime.state.value.items.map { item -> item.id })
        runtime.close()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesRuntimeExternalSyncTest {
    @Test
    fun externalRemovalWinsAgainstAnOlderPageStillInFlight() = runTest {
        val refreshGate = CompletableDeferred<Unit>()
        val repository = RuntimeFavoritesRepository(
            pageResponses = mutableListOf(
                RuntimePageResponse.success(favoritePage()),
                RuntimePageResponse.success(favoritePage(), gate = refreshGate, nonCancellable = true),
            ),
        )
        val runtime = activeRuntime(repository)

        runtime.dispatch(FavoritesIntent.Refresh)
        runCurrent()
        runtime.dispatch(
            FavoritesIntent.ExternalFavoriteStateChanged(
                listingId = FAVORITE_ID,
                favorited = false,
                scope = ACCOUNT_A_SCOPE,
            ),
        )
        runCurrent()
        assertTrue(runtime.state.value.items.isEmpty())

        refreshGate.complete(Unit)
        advanceUntilIdle()
        assertTrue(runtime.state.value.items.isEmpty())
        runtime.close()
    }

    @Test
    fun confirmedExternalAdditionReloadsAVisibleScreenAuthoritatively() = runTest {
        val repository = RuntimeFavoritesRepository(
            pageResponses = mutableListOf(
                RuntimePageResponse.success(FavoriteListingPage(emptyList(), nextCursor = null)),
                RuntimePageResponse.success(favoritePage()),
            ),
        )
        val runtime = activeRuntime(repository)

        runtime.dispatch(
            FavoritesIntent.ExternalFavoriteStateChanged(
                listingId = FAVORITE_ID,
                favorited = true,
                scope = ACCOUNT_A_SCOPE,
            ),
        )
        advanceUntilIdle()

        assertEquals(2, repository.pageRequests.size)
        assertEquals(listOf(FAVORITE_ID), runtime.state.value.items.map { item -> item.id })
        runtime.close()
    }

    @Test
    fun externalAdditionInvalidatesAnOlderLocalRemovalStillInFlight() = runTest {
        val mutationGate = CompletableDeferred<Unit>()
        val repository = externallyInvalidatedRemovalRepository(mutationGate)
        val runtime = activeRuntime(repository)

        runtime.effects.test {
            runtime.dispatch(FavoritesIntent.RemoveFavorite(FAVORITE_ID))
            runCurrent()
            assertEquals(setOf(FAVORITE_ID), runtime.state.value.removingListingIds)

            runtime.dispatch(
                FavoritesIntent.ExternalFavoriteStateChanged(
                    listingId = FAVORITE_ID,
                    favorited = true,
                    scope = ACCOUNT_A_SCOPE,
                ),
            )
            runCurrent()
            assertTrue(runtime.state.value.removingListingIds.isEmpty())

            mutationGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf(FAVORITE_ID), runtime.state.value.items.map { item -> item.id })
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        runtime.close()
    }

    @Test
    fun externalAdditionWhileHiddenIsReloadedOnTheNextScreenAppearance() = runTest {
        val repository = RuntimeFavoritesRepository(
            pageResponses = mutableListOf(
                RuntimePageResponse.success(FavoriteListingPage(emptyList(), nextCursor = null)),
                RuntimePageResponse.success(favoritePage()),
            ),
        )
        val runtime = activeRuntime(repository)
        runtime.dispatch(FavoritesIntent.ScreenDisappeared)
        runtime.dispatch(
            FavoritesIntent.ExternalFavoriteStateChanged(
                listingId = FAVORITE_ID,
                favorited = true,
                scope = ACCOUNT_A_SCOPE,
            ),
        )
        advanceUntilIdle()
        assertEquals(1, repository.pageRequests.size)

        runtime.dispatch(FavoritesIntent.ScreenAppeared)
        advanceUntilIdle()

        assertEquals(2, repository.pageRequests.size)
        assertEquals(listOf(FAVORITE_ID), runtime.state.value.items.map { item -> item.id })
        runtime.close()
    }

    @Test
    fun externalEventFromAnOlderLoginOfTheSameAccountIsIgnored() = runTest {
        val reconnectedScope = ViewerSessionScope(accountId = "account-a", epoch = 3L)
        val repository = RuntimeFavoritesRepository(
            pageResponses = mutableListOf(
                RuntimePageResponse.success(favoritePage()),
                RuntimePageResponse.success(favoritePage()),
            ),
        )
        val runtime = activeRuntime(repository)
        runtime.dispatch(FavoritesIntent.ViewerContextChanged(ViewerSessionScope(accountId = null, epoch = 2L)))
        runtime.dispatch(FavoritesIntent.ViewerContextChanged(reconnectedScope))
        advanceUntilIdle()

        runtime.dispatch(
            FavoritesIntent.ExternalFavoriteStateChanged(
                listingId = FAVORITE_ID,
                favorited = false,
                scope = ACCOUNT_A_SCOPE,
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf(FAVORITE_ID), runtime.state.value.items.map { item -> item.id })
        runtime.close()
    }

    @Test
    fun returningToTheScreenRefreshesPreviouslyLoadedFavorites() = runTest {
        val repository = RuntimeFavoritesRepository(
            pageResponses = mutableListOf(
                RuntimePageResponse.success(favoritePage(id = "old-item")),
                RuntimePageResponse.success(favoritePage(id = "new-item")),
            ),
        )
        val runtime = activeRuntime(repository)

        runtime.dispatch(FavoritesIntent.ScreenDisappeared)
        runtime.dispatch(FavoritesIntent.ScreenAppeared)
        advanceUntilIdle()

        assertEquals(listOf("new-item"), runtime.state.value.items.map { item -> item.id })
        runtime.close()
    }

    @Test
    fun mutationCompletingAfterAccountSwitchCannotPublishStateOrEffect() = runTest {
        val mutationGate = CompletableDeferred<Unit>()
        val repository = RuntimeFavoritesRepository(
            pageResponses = mutableListOf(
                RuntimePageResponse.success(favoritePage(id = "account-a-item")),
                RuntimePageResponse.success(favoritePage(id = "account-b-item")),
            ),
            mutationResponses = mutableListOf(
                RuntimeMutationResponse(
                    result = DomainResult.Success(
                        FavoriteMutation(
                            listingId = "account-a-item",
                            favorited = false,
                            favoritedAtEpochMilliseconds = null,
                        ),
                    ),
                    gate = mutationGate,
                    nonCancellable = true,
                ),
            ),
        )
        val runtime = runtime(repository)
        runtime.dispatch(FavoritesIntent.ViewerContextChanged(ACCOUNT_A_SCOPE))
        runtime.dispatch(FavoritesIntent.ScreenAppeared)
        advanceUntilIdle()

        runtime.effects.test {
            runtime.dispatch(FavoritesIntent.RemoveFavorite("account-a-item"))
            runCurrent()
            runtime.dispatch(FavoritesIntent.ViewerContextChanged(ACCOUNT_B_SCOPE))
            assertTrue(runtime.state.value.items.isEmpty())
            runCurrent()
            mutationGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("account-b-item"), runtime.state.value.items.map { item -> item.id })
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        runtime.close()
    }

    @Test
    fun removalQueuedForThePreviousScopeNeverCallsTheRepositoryForTheNextAccount() = runTest {
        val repository = RuntimeFavoritesRepository(
            pageResponses = mutableListOf(
                RuntimePageResponse.success(favoritePage()),
                RuntimePageResponse.success(favoritePage(id = "account-b-item")),
            ),
        )
        val runtime = activeRuntime(repository)

        runtime.dispatch(FavoritesIntent.RemoveFavorite(FAVORITE_ID))
        runtime.dispatch(FavoritesIntent.ViewerContextChanged(ACCOUNT_B_SCOPE))
        advanceUntilIdle()

        assertTrue(repository.mutationRequests.isEmpty())
        assertEquals(listOf("account-b-item"), runtime.state.value.items.map { item -> item.id })
        runtime.close()
    }
}

private fun TestScope.runtime(repository: FavoritesRepository): FavoritesRuntime = FavoritesRuntime(
    presenter = FavoritesPresenter(repository),
    strings = strings,
    coroutineScope = this,
)

private suspend fun TestScope.activeRuntime(repository: FavoritesRepository): FavoritesRuntime =
    runtime(repository).also { runtime ->
        runtime.dispatch(FavoritesIntent.ViewerContextChanged(ACCOUNT_A_SCOPE))
        runtime.dispatch(FavoritesIntent.ScreenAppeared)
        advanceUntilIdle()
    }

private fun concurrentRemovalRepository(
    successfulListingId: String,
    failingListingId: String,
    successGate: CompletableDeferred<Unit>,
    failureGate: CompletableDeferred<Unit>,
): RuntimeFavoritesRepository = RuntimeFavoritesRepository(
    pageResponses = mutableListOf(
        RuntimePageResponse.success(
            FavoriteListingPage(
                items = listOf(
                    favoriteListing(id = successfulListingId),
                    favoriteListing(id = failingListingId),
                ),
                nextCursor = null,
            ),
        ),
    ),
    mutationResponses = mutableListOf(
        RuntimeMutationResponse.success(successfulListingId, gate = successGate),
        RuntimeMutationResponse(
            result = DomainResult.Failure(DomainError.NetworkUnavailable()),
            gate = failureGate,
        ),
    ),
)

private fun externallyInvalidatedRemovalRepository(
    mutationGate: CompletableDeferred<Unit>,
): RuntimeFavoritesRepository = RuntimeFavoritesRepository(
    pageResponses = mutableListOf(
        RuntimePageResponse.success(favoritePage()),
        RuntimePageResponse.success(favoritePage()),
    ),
    mutationResponses = mutableListOf(
        RuntimeMutationResponse(
            result = DomainResult.Success(
                FavoriteMutation(
                    listingId = FAVORITE_ID,
                    favorited = false,
                    favoritedAtEpochMilliseconds = null,
                ),
            ),
            gate = mutationGate,
            nonCancellable = true,
        ),
    ),
)

private class RuntimeFavoritesRepository(
    private val pageResponses: MutableList<RuntimePageResponse> = mutableListOf(
        RuntimePageResponse.success(favoritePage()),
    ),
    private val mutationResponses: MutableList<RuntimeMutationResponse> = mutableListOf(
        RuntimeMutationResponse.success(FAVORITE_ID),
    ),
) : FavoritesRepository {
    val pageRequests = mutableListOf<Pair<ListingType?, ListingPageRequest>>()
    val mutationRequests = mutableListOf<Pair<String, Boolean>>()

    override suspend fun listFavorites(
        filter: ListingType?,
        page: ListingPageRequest,
    ): DomainResult<FavoriteListingPage> {
        pageRequests += filter to page
        val response = pageResponses.removeFirst()
        return response.await()
    }

    override suspend fun setFavorite(listingId: String, favorited: Boolean): DomainResult<FavoriteMutation> {
        mutationRequests += listingId to favorited
        val response = mutationResponses.removeFirst()
        return response.await()
    }
}

private data class RuntimePageResponse(
    val result: DomainResult<FavoriteListingPage>,
    val gate: CompletableDeferred<Unit>? = null,
    val nonCancellable: Boolean = false,
) {
    suspend fun await(): DomainResult<FavoriteListingPage> = awaitResponse(result, gate, nonCancellable)

    companion object {
        fun success(
            page: FavoriteListingPage,
            gate: CompletableDeferred<Unit>? = null,
            nonCancellable: Boolean = false,
        ): RuntimePageResponse = RuntimePageResponse(DomainResult.Success(page), gate, nonCancellable)
    }
}

private data class RuntimeMutationResponse(
    val result: DomainResult<FavoriteMutation>,
    val gate: CompletableDeferred<Unit>? = null,
    val nonCancellable: Boolean = false,
) {
    suspend fun await(): DomainResult<FavoriteMutation> = awaitResponse(result, gate, nonCancellable)

    companion object {
        fun success(listingId: String, gate: CompletableDeferred<Unit>? = null): RuntimeMutationResponse =
            RuntimeMutationResponse(
                result = DomainResult.Success(
                    FavoriteMutation(
                        listingId = listingId,
                        favorited = false,
                        favoritedAtEpochMilliseconds = null,
                    ),
                ),
                gate = gate,
            )
    }
}

private suspend fun <T> awaitResponse(result: T, gate: CompletableDeferred<Unit>?, nonCancellable: Boolean): T {
    if (gate == null) return result
    return if (nonCancellable) {
        withContext(NonCancellable) {
            gate.await()
            result
        }
    } else {
        gate.await()
        result
    }
}

private fun favoritePage(
    id: String = FAVORITE_ID,
    type: ListingType = ListingType.Place,
    nextCursor: String? = null,
): FavoriteListingPage = FavoriteListingPage(
    items = listOf(favoriteListing(id = id, type = type)),
    nextCursor = nextCursor,
)

private val ACCOUNT_A_SCOPE = ViewerSessionScope(accountId = "account-a", epoch = 1L)
private val ACCOUNT_B_SCOPE = ViewerSessionScope(accountId = "account-b", epoch = 2L)
private val LOGGED_OUT_SCOPE = ViewerSessionScope(accountId = null, epoch = 3L)
