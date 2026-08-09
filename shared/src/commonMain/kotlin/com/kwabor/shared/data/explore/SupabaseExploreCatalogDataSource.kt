package com.kwabor.shared.data.explore

import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.explore.ExploreCatalogRequest
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
import kotlin.time.Instant

internal class SupabaseExploreCatalogDataSource(
    private val postgrest: Postgrest,
) : ExploreCatalogDataSource {
    override suspend fun listCatalog(request: ExploreCatalogRequest): ExploreCatalogPageDto = runExplorePostgrest {
        postgrest.rpc(
            function = LIST_CATALOG_SUMMARIES_V2,
            parameters = request.toRpcParametersDto(),
        ).decodeList<JsonObject>()
            .map { row -> strictExploreCatalogJson.decodeFromJsonElement<ExploreCatalogRowDto>(row) }
            .toExploreCatalogPage(request)
    }
}

internal fun List<ExploreCatalogRowDto>.toExploreCatalogPage(request: ExploreCatalogRequest): ExploreCatalogPageDto {
    if (size > request.limit + 1) {
        invalidExploreCatalogValue("items", "more than one sentinel row")
    }
    if (distinctBy(ExploreCatalogRowDto::id).size != size) {
        invalidExploreCatalogValue("items", "duplicate listing ids")
    }
    if (distinctBy(ExploreCatalogRowDto::rowCursor).size != size) {
        invalidExploreCatalogValue("items", "duplicate row cursors")
    }

    val snapshots = map { row -> row.requireExploreContract(request) }
    if (snapshots.distinct().size > 1) {
        invalidExploreCatalogValue("snapshot_at", "heterogeneous page snapshots")
    }
    if (request.cursor != null && any { row -> row.rowCursor == request.cursor }) {
        invalidExploreCatalogValue("row_cursor", "request cursor returned by keyset page")
    }
    requireSponsorPrefix()

    val items = take(request.limit)
    val nextCursor = if (size > request.limit) {
        items.last().rowCursor
    } else {
        null
    }
    return ExploreCatalogPageDto(
        items = items,
        nextCursor = nextCursor,
        snapshotAtEpochMicroseconds = snapshots.firstOrNull(),
    )
}

private fun ExploreCatalogRowDto.requireExploreContract(request: ExploreCatalogRequest): Long {
    requireExploreUuid(id)
    requireExploreIdentifier(cityId, "city_id")
    requireExploreIdentifier(categoryId, "category_id")
    requireExploreCanonicalText(name, "name", MINIMUM_LISTING_NAME_LENGTH..MAXIMUM_LISTING_NAME_LENGTH)
    requireExploreCursor(rowCursor)
    requireExploreCover()
    requireExploreRating(ratingAverage)
    requireExploreNonNegative(viewsCount, "views_count")
    requireExploreNonNegative(likesCount.toLong(), "likes_count")
    priceFromXof?.let { price -> requireExplorePrice(price) }

    val mappedType = type.toDomainType()
    val mappedClass = listingClass.toDomainClass()
    if (status != PUBLISHED_STATUS || mappedType != request.listingType) {
        invalidExploreCatalogValue("type/status", "$type/$status")
    }
    if (request.cityId != null && cityId != request.cityId) {
        invalidExploreCatalogValue("city_id", cityId)
    }
    if (request.categoryId != null && categoryId != request.categoryId) {
        invalidExploreCatalogValue("category_id", categoryId)
    }
    if (request.listingClass != null && mappedClass != request.listingClass) {
        invalidExploreCatalogValue("listing_class", listingClass)
    }
    requireExplorePriceFilter(request)

    val snapshot = snapshotAt.toExploreInstant("snapshot_at")
    val snapshotMicros = snapshot.toEpochMicroseconds("snapshot_at")
    if (snapshotMicros < 0) {
        invalidExploreCatalogValue("snapshot_at", snapshotAt)
    }
    requireExploreTemporalContract(mappedType, snapshot, request)
    requireExploreSponsorContract(mappedType, mappedClass, snapshot)
    return snapshotMicros
}

private fun ExploreCatalogRowDto.requireExploreTemporalContract(
    mappedType: ListingType,
    snapshot: Instant,
    request: ExploreCatalogRequest,
) {
    if (mappedType == ListingType.Event) {
        requireExploreEventContract(snapshot, request)
    } else {
        requireExploreNonEventContract()
    }
}

private fun ExploreCatalogRowDto.requireExploreNonEventContract() {
    if (eventStartAt != null || eventEndAt != null || isEventEnded) {
        invalidExploreCatalogValue("event fields", "non-event row")
    }
}

private fun ExploreCatalogRowDto.requireExploreEventContract(snapshot: Instant, request: ExploreCatalogRequest) {
    val start = eventStartAt?.toExploreInstant("event_start_at")
        ?: invalidExploreCatalogValue("event_start_at", "null")
    val end = eventEndAt?.toExploreInstant("event_end_at")
    if (end != null && end < start) {
        invalidExploreCatalogValue("event dates", "$eventStartAt/$eventEndAt")
    }
    val effectiveEnd = end ?: start
    if (isEventEnded != (snapshot >= effectiveEnd)) {
        invalidExploreCatalogValue("is_event_ended", isEventEnded.toString())
    }
    request.eventWindow?.let { window ->
        val windowStart = Instant.fromEpochMilliseconds(window.startAtEpochMilliseconds)
        val windowEnd = Instant.fromEpochMilliseconds(window.endExclusiveAtEpochMilliseconds)
        if (!eventIntersectsWindow(start, effectiveEnd, windowStart, windowEnd)) {
            invalidExploreCatalogValue("event window", "$eventStartAt/$eventEndAt")
        }
    }
}

private fun eventIntersectsWindow(
    start: Instant,
    effectiveEnd: Instant,
    windowStart: Instant,
    windowEnd: Instant,
): Boolean = if (effectiveEnd == start) {
    start >= windowStart && start < windowEnd
} else {
    start < windowEnd && effectiveEnd > windowStart
}

private fun ExploreCatalogRowDto.requireExploreSponsorContract(
    mappedType: ListingType,
    mappedClass: ListingClass,
    snapshot: Instant,
) {
    val sponsoredUntilInstant = sponsoredUntil?.toExploreInstant("sponsored_until")
    if (!isSponsoredPlacement) {
        return
    }
    if (sponsoredUntilInstant == null) {
        invalidExploreCatalogValue("sponsored_until", "null for sponsored placement")
    }
    if (mappedType != ListingType.Establishment) {
        invalidExploreCatalogValue("is_sponsored_placement", isSponsoredPlacement.toString())
    }
    if (mappedClass != ListingClass.Commercial) {
        invalidExploreCatalogValue("is_sponsored_placement", isSponsoredPlacement.toString())
    }
    if (sponsoredUntilInstant <= snapshot) {
        invalidExploreCatalogValue("is_sponsored_placement", isSponsoredPlacement.toString())
    }
}

private fun ExploreCatalogRowDto.requireExplorePriceFilter(request: ExploreCatalogRequest) {
    if (request.priceMinXof == null && request.priceMaxXof == null) {
        return
    }
    val price = priceFromXof ?: invalidExploreCatalogValue("price_from_xof", "null with active filter")
    if (request.priceMinXof != null && price < request.priceMinXof) {
        invalidExploreCatalogValue("price_from_xof", price.toString())
    }
    if (request.priceMaxXof != null && price > request.priceMaxXof) {
        invalidExploreCatalogValue("price_from_xof", price.toString())
    }
}

private fun ExploreCatalogRowDto.requireExploreCover() {
    if ((coverImageUrl == null) != (coverImageAlt == null)) {
        invalidExploreCatalogValue("cover", "$coverImageUrl/$coverImageAlt")
    }
    coverImageUrl?.let { value ->
        if (!value.isValidExploreHttpsUrlValue()) {
            invalidExploreCatalogValue("cover_image_url", value)
        }
    }
    coverImageAlt?.let { value ->
        requireExploreCanonicalText(value, "cover_image_alt")
    }
}

private fun List<ExploreCatalogRowDto>.requireSponsorPrefix() {
    if (count(ExploreCatalogRowDto::isSponsoredPlacement) > MAXIMUM_SPONSORED_PLACEMENTS) {
        invalidExploreCatalogValue("is_sponsored_placement", "more than two placements")
    }
    var organicSeen = false
    forEach { row ->
        if (!row.isSponsoredPlacement) {
            organicSeen = true
        } else if (organicSeen) {
            invalidExploreCatalogValue("is_sponsored_placement", "placement after organic row")
        }
    }
}

private fun String.toDomainType(): ListingType = when (this) {
    "lieu" -> ListingType.Place
    "etablissement" -> ListingType.Establishment
    "evenement" -> ListingType.Event
    else -> invalidExploreCatalogValue("type", this)
}

private fun String.toDomainClass(): ListingClass = when (this) {
    "patrimonial" -> ListingClass.Heritage
    "commercial" -> ListingClass.Commercial
    "evenementiel" -> ListingClass.Event
    else -> invalidExploreCatalogValue("listing_class", this)
}

private val strictExploreCatalogJson = Json
private const val LIST_CATALOG_SUMMARIES_V2 = "list_catalog_summaries_v2"
private const val PUBLISHED_STATUS = "publie"
private const val MINIMUM_LISTING_NAME_LENGTH = 3
private const val MAXIMUM_LISTING_NAME_LENGTH = 80
private const val MAXIMUM_SPONSORED_PLACEMENTS = 2

private suspend fun <T> runExplorePostgrest(block: suspend () -> T): T = try {
    block()
} catch (exception: PostgrestRestException) {
    throw exception.toExploreCatalogDataException()
} catch (exception: RestException) {
    throw exception.toExploreCatalogDataException()
} catch (exception: HttpRequestTimeoutException) {
    throw ExploreCatalogDataException.NetworkUnavailable(exception)
} catch (exception: HttpRequestException) {
    throw ExploreCatalogDataException.NetworkUnavailable(exception)
} catch (exception: SerializationException) {
    throw ExploreCatalogDataException.Unexpected(exception)
}

private fun RestException.toExploreCatalogDataException(): ExploreCatalogDataException {
    val codeMapped = (this as? PostgrestRestException)?.toCodeMappedExploreCatalogDataException()
    return codeMapped ?: when (statusCode) {
        HTTP_UNAUTHORIZED -> ExploreCatalogDataException.AuthenticationRequired(this)
        HTTP_FORBIDDEN -> ExploreCatalogDataException.PermissionDenied(this)
        HTTP_BAD_REQUEST,
        HTTP_CONFLICT,
        HTTP_UNPROCESSABLE_CONTENT,
        -> ExploreCatalogDataException.Validation(cause = this)
        HTTP_BAD_GATEWAY,
        HTTP_SERVICE_UNAVAILABLE,
        HTTP_GATEWAY_TIMEOUT,
        -> ExploreCatalogDataException.NetworkUnavailable(this)
        else -> ExploreCatalogDataException.Unexpected(this)
    }
}

private fun PostgrestRestException.toCodeMappedExploreCatalogDataException(): ExploreCatalogDataException? = when {
    code?.startsWith(POSTGREST_SCHEMA_CACHE_ERROR_PREFIX) == true -> ExploreCatalogDataException.Unexpected(this)
    else -> when (code) {
        "42501" -> ExploreCatalogDataException.AuthenticationRequired(this)
        "22023", "23503", "23505", "23514" -> ExploreCatalogDataException.Validation(cause = this)
        else -> null
    }
}

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_CONFLICT = 409
private const val HTTP_UNPROCESSABLE_CONTENT = 422
private const val HTTP_BAD_GATEWAY = 502
private const val HTTP_SERVICE_UNAVAILABLE = 503
private const val HTTP_GATEWAY_TIMEOUT = 504
private const val POSTGREST_SCHEMA_CACHE_ERROR_PREFIX = "PGRST2"
