package com.kwabor.shared.data.favorites

import com.kwabor.shared.domain.catalog.ListingPageRequest
import com.kwabor.shared.domain.catalog.ListingType
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.rpc
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_CONFLICT = 409
private const val HTTP_UNPROCESSABLE_CONTENT = 422
private const val HTTP_BAD_GATEWAY = 502
private const val HTTP_SERVICE_UNAVAILABLE = 503
private const val HTTP_GATEWAY_TIMEOUT = 504
private const val POSTGREST_SCHEMA_CACHE_ERROR_PREFIX = "PGRST2"
private const val LIST_FAVORITES = "list_favorite_listing_summaries_v1"
private const val SET_FAVORITE = "set_listing_favorite_v1"
private const val SET_ACCOUNT_SCOPED_FAVORITE = "set_listing_favorite_v2"

internal class SupabaseFavoritesDataSource(
    private val postgrest: Postgrest,
) : FavoritesDataSource {
    override suspend fun listFavorites(filter: ListingType?, page: ListingPageRequest): FavoriteListingPageDto =
        runFavoritesPostgrest {
            postgrest.rpc(
                function = LIST_FAVORITES,
                parameters = ListFavoritesRpcParametersDto(
                    listingType = filter?.toFavoriteDatabaseValue(),
                    cursor = page.cursor,
                    limit = page.limit,
                ),
            ).decodeList<JsonObject>()
                .map { row -> strictFavoritesJson.decodeFromJsonElement<FavoriteListingRowDto>(row) }
                .toFavoriteListingPageDto(limit = page.limit, expectedType = filter)
        }

    override suspend fun setFavorite(listingId: String, favorited: Boolean): FavoriteMutationRowDto =
        runFavoritesPostgrest {
            val rows = postgrest.rpc(
                function = SET_FAVORITE,
                parameters = SetFavoriteRpcParametersDto(
                    listingId = listingId,
                    favorited = favorited,
                ),
            ).decodeList<JsonObject>()
                .map { row -> strictFavoritesJson.decodeFromJsonElement<FavoriteMutationRowDto>(row) }
            if (rows.size != 1) {
                throw FavoritesDataException.Unexpected(
                    IllegalStateException("Favorite mutation RPC must return exactly one row."),
                )
            }
            rows.single().also { row ->
                row.toDomain(
                    expectedListingId = listingId,
                    expectedFavorited = favorited,
                    clientMutationSequence = 1L,
                )
            }
        }

    override suspend fun setFavoriteForAccount(
        expectedAccountId: String,
        listingId: String,
        favorited: Boolean,
    ): FavoriteMutationRowDto = runFavoritesPostgrest {
        val rows = postgrest.rpc(
            function = SET_ACCOUNT_SCOPED_FAVORITE,
            parameters = SetAccountScopedFavoriteRpcParametersDto(
                expectedAccountId = expectedAccountId,
                listingId = listingId,
                favorited = favorited,
            ),
        ).decodeList<JsonObject>()
            .map { row -> strictFavoritesJson.decodeFromJsonElement<FavoriteMutationRowDto>(row) }
        if (rows.size != 1) {
            throw FavoritesDataException.Unexpected(
                IllegalStateException("Account-scoped favorite mutation RPC must return exactly one row."),
            )
        }
        rows.single().also { row ->
            row.toDomain(
                expectedListingId = listingId,
                expectedFavorited = favorited,
                clientMutationSequence = 1L,
            )
        }
    }
}

internal fun List<FavoriteListingRowDto>.toFavoriteListingPageDto(
    limit: Int,
    expectedType: ListingType? = null,
): FavoriteListingPageDto {
    requireFavoritePageContract(limit = limit, expectedType = expectedType)
    val items = take(limit)
    val nextCursor = if (size > limit) {
        items.last().rowCursor.requireFavoriteCursor("row_cursor")
    } else {
        null
    }
    return FavoriteListingPageDto(items = items, nextCursor = nextCursor)
}

private fun List<FavoriteListingRowDto>.requireFavoritePageContract(limit: Int, expectedType: ListingType?) {
    if (limit !in 1..ListingPageRequest.MAX_LIMIT) {
        invalidFavoriteValue("page_limit", "outside the supported contract")
    }
    if (size > limit + 1) {
        invalidFavoriteValue("items", "more than one sentinel row")
    }
    forEach { row -> row.toDomain(expectedType) }
    if (distinctBy { row -> row.id.lowercase() }.size != size) {
        invalidFavoriteValue("items", "duplicate listing IDs")
    }
    if (distinctBy(FavoriteListingRowDto::rowCursor).size != size) {
        invalidFavoriteValue("items", "duplicate row cursors")
    }
    requireStrictNewestFirst()
}

private fun List<FavoriteListingRowDto>.requireStrictNewestFirst() {
    zipWithNext().forEach { (newer, older) ->
        val newerInstant = newer.favoritedAt.requireFavoriteInstant("favorited_at")
        val olderInstant = older.favoritedAt.requireFavoriteInstant("favorited_at")
        val isStrictlyDescending = newerInstant > olderInstant ||
            (newerInstant == olderInstant && newer.id > older.id)
        if (!isStrictlyDescending) {
            invalidFavoriteValue("items", "rows are not in strict newest-first order")
        }
    }
}

private val strictFavoritesJson = Json

private suspend fun <T> runFavoritesPostgrest(block: suspend () -> T): T = try {
    block()
} catch (exception: PostgrestRestException) {
    throw exception.toFavoritesDataException()
} catch (exception: RestException) {
    throw exception.toFavoritesDataException()
} catch (exception: HttpRequestTimeoutException) {
    throw FavoritesDataException.NetworkUnavailable(exception)
} catch (exception: HttpRequestException) {
    throw FavoritesDataException.NetworkUnavailable(exception)
} catch (exception: SerializationException) {
    throw FavoritesDataException.Unexpected(exception)
}

private fun RestException.toFavoritesDataException(): FavoritesDataException {
    val codeMappedException = (this as? PostgrestRestException)?.toCodeMappedFavoritesDataException()
    return codeMappedException ?: toStatusMappedFavoritesDataException()
}

private fun PostgrestRestException.toCodeMappedFavoritesDataException(): FavoritesDataException? = when {
    code?.startsWith(POSTGREST_SCHEMA_CACHE_ERROR_PREFIX) == true -> FavoritesDataException.Unexpected(this)
    else -> when (code) {
        "P0002", "PGRST116" -> FavoritesDataException.NotFound(this)
        "42501" -> FavoritesDataException.AuthenticationRequired(this)
        "22023", "23503", "23505", "23514" -> FavoritesDataException.Validation(cause = this)
        else -> null
    }
}

private fun RestException.toStatusMappedFavoritesDataException(): FavoritesDataException = when (statusCode) {
    HTTP_UNAUTHORIZED -> FavoritesDataException.AuthenticationRequired(this)
    HTTP_FORBIDDEN -> FavoritesDataException.PermissionDenied(this)
    HTTP_NOT_FOUND -> FavoritesDataException.NotFound(this)
    HTTP_BAD_REQUEST,
    HTTP_CONFLICT,
    HTTP_UNPROCESSABLE_CONTENT,
    -> FavoritesDataException.Validation(cause = this)
    HTTP_BAD_GATEWAY,
    HTTP_SERVICE_UNAVAILABLE,
    HTTP_GATEWAY_TIMEOUT,
    -> FavoritesDataException.NetworkUnavailable(this)
    else -> FavoritesDataException.Unexpected(this)
}
