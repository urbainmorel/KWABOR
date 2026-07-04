package com.kwabor.shared.data.catalog

import com.kwabor.shared.domain.catalog.ListingFilters
import com.kwabor.shared.domain.catalog.ListingSearchQuery
import com.kwabor.shared.domain.core.PageRequest
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.query.Order
import io.ktor.client.plugins.HttpRequestTimeoutException

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

    override suspend fun listListings(filters: ListingFilters, page: PageRequest): List<ListingSummaryDto> =
        runPostgrest {
            val listings = postgrest.from(LISTINGS)
                .select {
                    applyListingFilters(filters)
                    order("sponsored_until", Order.DESCENDING)
                    order("rating_avg", Order.DESCENDING)
                    order("likes_count", Order.DESCENDING)
                    applyPage(page)
                }
                .decodeList<ListingDto>()

            listings.map { listing ->
                ListingSummaryDto(
                    listing = listing,
                    coverImageUrl = findCoverImageUrl(listing.id),
                )
            }
        }

    override suspend fun searchListings(query: ListingSearchQuery, page: PageRequest): List<ListingSummaryDto> =
        runPostgrest {
            val listings = postgrest.from(LISTINGS)
                .select {
                    applyListingFilters(query.filters)
                    filter {
                        ilike("name", query.text.toIlikePattern())
                    }
                    order("sponsored_until", Order.DESCENDING)
                    order("rating_avg", Order.DESCENDING)
                    order("likes_count", Order.DESCENDING)
                    applyPage(page)
                }
                .decodeList<ListingDto>()

            listings.map { listing ->
                ListingSummaryDto(
                    listing = listing,
                    coverImageUrl = findCoverImageUrl(listing.id),
                )
            }
        }

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

    private suspend fun findCoverImageUrl(listingId: String): String? {
        val media = postgrest.from(LISTING_MEDIA)
            .select {
                filter {
                    eq("listing_id", listingId)
                }
                order("is_cover", Order.DESCENDING)
                order("display_order", Order.ASCENDING)
                limit(1)
            }
            .decodeList<ListingMediaDto>()

        return media.firstOrNull()?.url
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

private fun io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder.applyPage(page: PageRequest) {
    range(
        from = page.offset.toLong(),
        to = (page.offset + page.limit - 1).toLong(),
    )
}

private fun io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder.applyListingFilters(
    filters: ListingFilters,
) {
    filter {
        if (filters.onlyPublished) {
            eq("status", "publie")
        }
        filters.cityId?.let { cityId -> eq("city_id", cityId) }
        filters.categoryId?.let { categoryId -> eq("category_id", categoryId) }
        filters.listingType?.let { listingType -> eq("type", listingType.toDatabaseValue()) }
        filters.listingClass?.let { listingClass -> eq("listing_class", listingClass.toDatabaseValue()) }
    }
}

private suspend fun <T> runPostgrest(block: suspend () -> T): T = try {
    block()
} catch (exception: PostgrestRestException) {
    throw exception.toCatalogDataException()
} catch (exception: RestException) {
    throw exception.toCatalogDataException()
} catch (exception: HttpRequestTimeoutException) {
    throw CatalogDataException.NetworkUnavailable()
} catch (exception: HttpRequestException) {
    throw CatalogDataException.NetworkUnavailable()
}

private fun RestException.toCatalogDataException(): CatalogDataException {
    if (this is PostgrestRestException) {
        when (code) {
            "P0002", "PGRST116" -> return CatalogDataException.NotFound()
            "42501" -> return CatalogDataException.PermissionDenied()
            "22023", "23503", "23505", "23514" -> return CatalogDataException.Validation()
        }
    }

    return when (statusCode) {
        401, 403 -> CatalogDataException.PermissionDenied()
        404 -> CatalogDataException.NotFound()
        400, 409, 422 -> CatalogDataException.Validation()
        else -> CatalogDataException.Unexpected()
    }
}

private fun String.toIlikePattern(): String = "%${trim().replace("%", "").replace("_", "")}%"
