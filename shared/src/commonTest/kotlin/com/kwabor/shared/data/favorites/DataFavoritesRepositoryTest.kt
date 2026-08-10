package com.kwabor.shared.data.favorites

import com.kwabor.shared.domain.catalog.ListingPageRequest
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.favorites.FavoriteListingPage
import com.kwabor.shared.domain.favorites.FavoriteMutation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DataFavoritesRepositoryTest {
    @Test
    fun listFavorites_forwardsValidatedFilterAndPage() = runTest {
        val dataSource = FakeFavoritesDataSource()
        val repository = DataFavoritesRepository(dataSource)
        val page = ListingPageRequest(cursor = "opaque-cursor", limit = 12)

        val result = repository.listFavorites(filter = ListingType.Establishment, page = page)

        val favorites = assertIs<DomainResult.Success<FavoriteListingPage>>(result).value
        assertEquals(FAVORITE_LISTING_ID_ONE, favorites.items.single().id)
        assertEquals(ListingType.Establishment, dataSource.lastFilter)
        assertEquals(page, dataSource.lastPage)
        assertEquals(1, dataSource.listCallCount)
    }

    @Test
    fun listFavorites_rejectsMalformedCursorWithoutCallingTransport() = runTest {
        val dataSource = FakeFavoritesDataSource()
        val repository = DataFavoritesRepository(dataSource)

        val result = repository.listFavorites(page = ListingPageRequest(cursor = "cursor with spaces"))

        val failure = assertIs<DomainResult.Failure>(result)
        assertIs<DomainError.Validation>(failure.error)
        assertEquals(0, dataSource.listCallCount)
    }

    @Test
    fun setFavorite_canonicalizesIdAndReturnsTheRequestedState() = runTest {
        val dataSource = FakeFavoritesDataSource()
        val repository = DataFavoritesRepository(dataSource)

        val result = repository.setFavorite(" ${FAVORITE_LISTING_ID_ONE.uppercase()} ", favorited = true)

        val mutation = assertIs<DomainResult.Success<FavoriteMutation>>(result).value
        assertEquals(FAVORITE_LISTING_ID_ONE, dataSource.lastListingId)
        assertEquals(true, dataSource.lastFavorited)
        assertEquals(FAVORITE_LISTING_ID_ONE, mutation.listingId)
        assertEquals(true, mutation.favorited)
        assertEquals(1L, mutation.clientMutationSequence)
    }

    @Test
    fun accountScopedSetFavorite_canonicalizesExpectedAccountAndUsesDedicatedTransport() = runTest {
        val dataSource = FakeFavoritesDataSource()
        val repository = DataFavoritesRepository(dataSource)

        val result = repository.setFavorite(
            expectedAccountId = " ${FAVORITE_ACCOUNT_ID.uppercase()} ",
            listingId = " ${FAVORITE_LISTING_ID_ONE.uppercase()} ",
            favorited = true,
        )

        val mutation = assertIs<DomainResult.Success<FavoriteMutation>>(result).value
        assertEquals(FAVORITE_ACCOUNT_ID, dataSource.lastExpectedAccountId)
        assertEquals(FAVORITE_LISTING_ID_ONE, dataSource.lastListingId)
        assertEquals(true, mutation.favorited)
        assertEquals(1L, mutation.clientMutationSequence)
    }

    @Test
    fun setFavorite_rejectsMalformedIdWithoutCallingTransport() = runTest {
        val dataSource = FakeFavoritesDataSource()
        val repository = DataFavoritesRepository(dataSource)

        val result = repository.setFavorite("not-a-uuid", favorited = false)

        val failure = assertIs<DomainResult.Failure>(result)
        assertIs<DomainError.Validation>(failure.error)
        assertEquals(0, dataSource.setCallCount)
    }

    @Test
    fun setFavoriteSerializesCompetingSurfaceWritesInInvocationOrder() = runTest {
        val firstMutationGate = CompletableDeferred<Unit>()
        val firstMutationStarted = CompletableDeferred<Unit>()
        val dataSource = SequencedFavoritesDataSource(firstMutationGate, firstMutationStarted)
        val repository = DataFavoritesRepository(dataSource)

        val removal = async { repository.setFavorite(FAVORITE_LISTING_ID_ONE, favorited = false) }
        firstMutationStarted.await()
        val addition = async { repository.setFavorite(FAVORITE_LISTING_ID_ONE, favorited = true) }
        yield()

        assertEquals(listOf(false), dataSource.requestedStates)
        firstMutationGate.complete(Unit)
        val removed = assertIs<DomainResult.Success<FavoriteMutation>>(removal.await()).value
        val added = assertIs<DomainResult.Success<FavoriteMutation>>(addition.await()).value
        assertEquals(listOf(false, true), dataSource.requestedStates)
        assertEquals(1L, removed.clientMutationSequence)
        assertEquals(2L, added.clientMutationSequence)
        assertTrue(dataSource.serverFavorited)
    }

    @Test
    fun cancellingAnActiveCallerCannotLetANewerWriteOvertakeItsTransport() = runTest {
        val firstMutationGate = CompletableDeferred<Unit>()
        val firstMutationStarted = CompletableDeferred<Unit>()
        val dataSource = SequencedFavoritesDataSource(firstMutationGate, firstMutationStarted)
        val repository = DataFavoritesRepository(dataSource)

        val removal = async { repository.setFavorite(FAVORITE_LISTING_ID_ONE, favorited = false) }
        firstMutationStarted.await()
        removal.cancel()
        val addition = async { repository.setFavorite(FAVORITE_LISTING_ID_ONE, favorited = true) }
        yield()

        assertEquals(listOf(false), dataSource.requestedStates)
        firstMutationGate.complete(Unit)
        removal.join()
        val added = assertIs<DomainResult.Success<FavoriteMutation>>(addition.await()).value
        assertEquals(listOf(false, true), dataSource.requestedStates)
        assertEquals(2L, added.clientMutationSequence)
        assertTrue(dataSource.serverFavorited)
    }

    @Test
    fun repository_mapsExpectedTransportFailuresToDomainFailures() = runTest {
        val failures = listOf(
            FavoritesDataException.AuthenticationRequired() to DomainError.AuthenticationRequired::class,
            FavoritesDataException.PermissionDenied() to DomainError.PermissionDenied::class,
            FavoritesDataException.NotFound() to DomainError.NotFound::class,
            FavoritesDataException.NetworkUnavailable() to DomainError.NetworkUnavailable::class,
        )

        failures.forEach { (transportFailure, expectedType) ->
            val repository = DataFavoritesRepository(FakeFavoritesDataSource(failure = transportFailure))
            val result = assertIs<DomainResult.Failure>(repository.setFavorite(FAVORITE_LISTING_ID_ONE, true))

            assertEquals(expectedType, result.error::class)
        }
    }
}

private class SequencedFavoritesDataSource(
    private val firstMutationGate: CompletableDeferred<Unit>,
    private val firstMutationStarted: CompletableDeferred<Unit>,
) : FavoritesDataSource {
    val requestedStates = mutableListOf<Boolean>()
    var serverFavorited: Boolean = false
        private set

    override suspend fun listFavorites(filter: ListingType?, page: ListingPageRequest): FavoriteListingPageDto =
        FavoriteListingPageDto(items = emptyList(), nextCursor = null)

    override suspend fun setFavorite(listingId: String, favorited: Boolean): FavoriteMutationRowDto {
        requestedStates += favorited
        if (requestedStates.size == 1) {
            firstMutationStarted.complete(Unit)
            firstMutationGate.await()
        }
        serverFavorited = favorited
        return validFavoriteMutationRow(listingId = listingId, favorited = favorited)
    }

    override suspend fun setFavoriteForAccount(
        expectedAccountId: String,
        listingId: String,
        favorited: Boolean,
    ): FavoriteMutationRowDto = setFavorite(listingId = listingId, favorited = favorited)
}

private class FakeFavoritesDataSource(
    private val failure: FavoritesDataException? = null,
) : FavoritesDataSource {
    var lastFilter: ListingType? = null
        private set
    var lastPage: ListingPageRequest? = null
        private set
    var lastListingId: String? = null
        private set
    var lastExpectedAccountId: String? = null
        private set
    var lastFavorited: Boolean? = null
        private set
    var listCallCount: Int = 0
        private set
    var setCallCount: Int = 0
        private set

    override suspend fun listFavorites(filter: ListingType?, page: ListingPageRequest): FavoriteListingPageDto {
        failure?.let { throw it }
        listCallCount += 1
        lastFilter = filter
        lastPage = page
        return FavoriteListingPageDto(
            items = listOf(validFavoriteListingRow()),
            nextCursor = null,
        )
    }

    override suspend fun setFavorite(listingId: String, favorited: Boolean): FavoriteMutationRowDto {
        failure?.let { throw it }
        setCallCount += 1
        lastListingId = listingId
        lastFavorited = favorited
        return validFavoriteMutationRow(listingId = listingId, favorited = favorited)
    }

    override suspend fun setFavoriteForAccount(
        expectedAccountId: String,
        listingId: String,
        favorited: Boolean,
    ): FavoriteMutationRowDto {
        lastExpectedAccountId = expectedAccountId
        return setFavorite(listingId = listingId, favorited = favorited)
    }
}

private const val FAVORITE_ACCOUNT_ID = "99999999-9999-4999-8999-999999999999"
