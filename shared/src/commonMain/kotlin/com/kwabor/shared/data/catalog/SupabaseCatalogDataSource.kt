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
            searchQuery = null,
            page = page,
        )

    override suspend fun searchListings(query: ListingSearchQuery, page: ListingPageRequest): ListingSummaryPageDto =
        loadListingSummaryPage(
            filters = query.filters,
            searchQuery = query.text,
            page = page,
        )

    override suspend fun getListingDetail(listingId: String): ListingDetailDto = runPostgrest {
        val listing = postgrest.from(LISTINGS)
            .select {
                filter {
                    eq("id", listingId)
                }
                limit(1)
            }
            .decodeSingle<ListingDto>()

        ListingDetailDto(
            listing = listing,
            media = listMedia(listingId),
        )
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

    override suspend fun favoriteListing(listingId: String): ListingViewerInteractionDto = runPostgrest {
        postgrest.rpc(
            function = "add_listing_to_favorites",
            parameters = ListingInteractionRpcDto(listingId = listingId),
        ).decodeSingle()
    }

    override suspend fun unfavoriteListing(listingId: String): ListingViewerInteractionDto = runPostgrest {
        postgrest.rpc(
            function = "remove_listing_from_favorites",
            parameters = ListingInteractionRpcDto(listingId = listingId),
        ).decodeSingle()
    }

    private suspend fun loadListingSummaryPage(
        filters: ListingFilters,
        searchQuery: String?,
        page: ListingPageRequest,
    ): ListingSummaryPageDto = runPostgrest {
        postgrest.rpc(
            function = LIST_CATALOG_SUMMARIES,
            parameters = filters.toSummaryPageRpcDto(searchQuery = searchQuery, page = page),
        ).decodeList<ListingSummaryDto>()
            .toSummaryPage(page.limit)
    }

    private suspend fun listMedia(listingId: String): List<ListingMediaDto> = postgrest.from(LISTING_MEDIA)
        .select {
            filter {
                eq("listing_id", listingId)
            }
            order("display_order", Order.ASCENDING)
        }
        .decodeList()
}

private const val CITIES = "cities"
private const val CATEGORIES = "categories"
private const val LISTINGS = "listings"
private const val LISTING_MEDIA = "listing_media"
private const val LIST_CATALOG_SUMMARIES = "list_catalog_summaries"

private fun ListingFilters.toSummaryPageRpcDto(
    searchQuery: String?,
    page: ListingPageRequest,
): ListingSummaryPageRpcDto = ListingSummaryPageRpcDto(
    cityId = cityId,
    categoryId = categoryId,
    listingType = listingType?.toDatabaseValue(),
    listingClass = listingClass?.toDatabaseValue(),
    searchQuery = searchQuery,
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
    if (this is PostgrestRestException) {
        when (code) {
            "P0002", "PGRST116" -> return CatalogDataException.NotFound(cause = this)
            "42501" -> return CatalogDataException.AuthenticationRequired(this)
            "22023", "23503", "23505", "23514" -> return CatalogDataException.Validation(cause = this)
        }
    }

    return when (statusCode) {
        HTTP_UNAUTHORIZED -> CatalogDataException.AuthenticationRequired(this)
        HTTP_FORBIDDEN -> CatalogDataException.PermissionDenied(cause = this)
        HTTP_NOT_FOUND -> CatalogDataException.NotFound(cause = this)
        HTTP_BAD_REQUEST,
        HTTP_CONFLICT,
        HTTP_UNPROCESSABLE_CONTENT,
        -> CatalogDataException.Validation(cause = this)
        else -> CatalogDataException.Unexpected(this)
    }
}
