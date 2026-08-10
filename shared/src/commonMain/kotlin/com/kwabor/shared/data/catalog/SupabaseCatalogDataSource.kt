package com.kwabor.shared.data.catalog

import com.kwabor.shared.domain.catalog.ListingFilters
import com.kwabor.shared.domain.catalog.ListingPageRequest
import com.kwabor.shared.domain.catalog.ListingSearchQuery
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.serialization.SerializationException

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

internal class SupabaseCatalogDataSource(
    private val postgrest: Postgrest,
) : CatalogDataSource {
    override suspend fun listCities(): List<CityDto> = runPostgrest {
        postgrest.from(CITIES)
            .select {
                order("name", Order.ASCENDING)
            }
            .decodeList()
    }

    override suspend fun listCategories(): List<CategoryDto> = runPostgrest {
        postgrest.from(CATEGORIES)
            .select {
                order("sort_order", Order.ASCENDING)
                order("name_key", Order.ASCENDING)
            }
            .decodeList()
    }

    override suspend fun listListings(filters: ListingFilters, page: ListingPageRequest): ListingSummaryPageDto =
        loadListingSummaryPage(
            filters = filters,
            page = page,
        )

    override suspend fun searchListings(query: ListingSearchQuery, page: ListingPageRequest): ListingSummaryPageDto =
        runPostgrest {
            postgrest.rpc(
                function = SEARCH_CATALOG_SUMMARIES,
                parameters = query.toSearchSummaryPageRpcDto(page),
            ).decodeList<ListingSummaryDto>()
                .toSummaryPage(page.limit)
        }

    override suspend fun getListingDetail(listingId: String): CatalogDetailPayloadDto = runPostgrest {
        postgrest.rpc(
            function = GET_CATALOG_DETAIL,
            parameters = CatalogDetailRpcParametersDto(listingId = listingId),
        ).decodeSingleOrNull<CatalogDetailRpcRowDto>()?.payload?.decodeStrictCatalogDetailPayload()
            ?: throw CatalogDataException.NotFound()
    }

    override suspend fun getListingViewerInteraction(listingId: String): ListingViewerInteractionDto = runPostgrest {
        postgrest.rpc(
            function = "get_listing_viewer_interaction",
            parameters = ListingInteractionRpcDto(listingId = listingId),
        ).decodeSingle()
    }

    override suspend fun listListingViewerInteractions(listingIds: List<String>): List<ListingViewerInteractionDto> =
        runPostgrest {
            postgrest.rpc(
                function = "list_listing_viewer_interactions",
                parameters = ListingInteractionsRpcDto(listingIds = listingIds),
            ).decodeList()
        }

    override suspend fun likeListing(listingId: String): ListingViewerInteractionDto = runPostgrest {
        postgrest.rpc(
            function = "like_listing",
            parameters = ListingInteractionRpcDto(listingId = listingId),
        ).decodeSingle()
    }

    override suspend fun unlikeListing(listingId: String): ListingViewerInteractionDto = runPostgrest {
        postgrest.rpc(
            function = "unlike_listing",
            parameters = ListingInteractionRpcDto(listingId = listingId),
        ).decodeSingle()
    }

    override suspend fun setListingLike(
        expectedAccountId: String,
        listingId: String,
        liked: Boolean,
    ): ListingLikeMutationDto = runPostgrest {
        val rows = postgrest.rpc(
            function = SET_LISTING_LIKE,
            parameters = SetListingLikeRpcDto(
                expectedAccountId = expectedAccountId,
                listingId = listingId,
                liked = liked,
            ),
        ).decodeList<ListingLikeMutationDto>()
        if (rows.size != 1) {
            throw CatalogDataException.Unexpected(
                IllegalStateException("Catalog like mutation RPC must return exactly one row."),
            )
        }
        rows.single().also { row ->
            row.toDomain(expectedListingId = listingId, expectedLiked = liked)
        }
    }

    private suspend fun loadListingSummaryPage(
        filters: ListingFilters,
        page: ListingPageRequest,
    ): ListingSummaryPageDto = runPostgrest {
        postgrest.rpc(
            function = LIST_CATALOG_SUMMARIES,
            parameters = filters.toSummaryPageRpcDto(page),
        ).decodeList<ListingSummaryDto>()
            .toSummaryPage(page.limit)
    }
}

private const val CITIES = "cities"
private const val CATEGORIES = "categories"
private const val LIST_CATALOG_SUMMARIES = "list_catalog_summaries"
private const val SEARCH_CATALOG_SUMMARIES = "search_catalog_summaries_v1"
private const val GET_CATALOG_DETAIL = "get_catalog_detail_v1"
private const val SET_LISTING_LIKE = "set_listing_like_v2"

private fun ListingFilters.toSummaryPageRpcDto(page: ListingPageRequest): ListingSummaryPageRpcDto =
    ListingSummaryPageRpcDto(
        cityId = cityId,
        categoryId = categoryId,
        listingType = listingType?.toDatabaseValue(),
        listingClass = listingClass?.toDatabaseValue(),
        searchQuery = null,
        cursor = page.cursor,
        limit = page.limit,
    )

private fun ListingSearchQuery.toSearchSummaryPageRpcDto(page: ListingPageRequest): CatalogSearchSummaryPageRpcDto =
    CatalogSearchSummaryPageRpcDto(
        searchQuery = text,
        cityId = filters.cityId,
        categoryId = filters.categoryId,
        listingType = filters.listingType?.toDatabaseValue(),
        listingClass = filters.listingClass?.toDatabaseValue(),
        cursor = page.cursor,
        limit = page.limit,
    )

internal fun List<ListingSummaryDto>.toSummaryPage(limit: Int): ListingSummaryPageDto {
    val items = take(limit)
    val nextCursor = if (size > limit) {
        items.lastOrNull()
            ?.rowCursor
            ?.takeIf { cursor -> cursor.isNotBlank() }
            ?: throw CatalogDataException.Unexpected(
                IllegalStateException("Catalog summary RPC returned an invalid page cursor."),
            )
    } else {
        null
    }

    return ListingSummaryPageDto(items = items, nextCursor = nextCursor)
}

private suspend fun <T> runPostgrest(block: suspend () -> T): T = try {
    block()
} catch (exception: PostgrestRestException) {
    throw exception.toCatalogDataException()
} catch (exception: RestException) {
    throw exception.toCatalogDataException()
} catch (exception: HttpRequestTimeoutException) {
    throw CatalogDataException.NetworkUnavailable(exception)
} catch (exception: HttpRequestException) {
    throw CatalogDataException.NetworkUnavailable(exception)
} catch (exception: SerializationException) {
    throw CatalogDataException.Unexpected(exception)
}

private fun RestException.toCatalogDataException(): CatalogDataException {
    val codeMappedException = (this as? PostgrestRestException)?.toCodeMappedCatalogDataException()
    return codeMappedException ?: toStatusMappedCatalogDataException()
}

private fun PostgrestRestException.toCodeMappedCatalogDataException(): CatalogDataException? = when {
    code?.startsWith(POSTGREST_SCHEMA_CACHE_ERROR_PREFIX) == true -> CatalogDataException.Unexpected(this)
    else -> when (code) {
        "P0002", "PGRST116" -> CatalogDataException.NotFound(cause = this)
        "42501" -> CatalogDataException.AuthenticationRequired(this)
        "22023", "23503", "23505", "23514" -> CatalogDataException.Validation(cause = this)
        else -> null
    }
}

private fun RestException.toStatusMappedCatalogDataException(): CatalogDataException = when (statusCode) {
    HTTP_UNAUTHORIZED -> CatalogDataException.AuthenticationRequired(this)
    HTTP_FORBIDDEN -> CatalogDataException.PermissionDenied(cause = this)
    HTTP_NOT_FOUND -> CatalogDataException.NotFound(cause = this)
    HTTP_BAD_REQUEST,
    HTTP_CONFLICT,
    HTTP_UNPROCESSABLE_CONTENT,
    -> CatalogDataException.Validation(cause = this)
    HTTP_BAD_GATEWAY,
    HTTP_SERVICE_UNAVAILABLE,
    HTTP_GATEWAY_TIMEOUT,
    -> CatalogDataException.NetworkUnavailable(this)
    else -> CatalogDataException.Unexpected(this)
}
