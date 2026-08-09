package com.kwabor.shared.domain.catalog

import com.kwabor.shared.domain.money.MoneyXof
import kotlin.math.abs

enum class ListingType {
    Place,
    Establishment,
    Event,
}

enum class ListingClass {
    Heritage,
    Commercial,
    Event,
}

val ListingClass.canBeClaimed: Boolean
    get() = this == ListingClass.Commercial || this == ListingClass.Event

enum class ListingStatus {
    Draft,
    PendingReview,
    Published,
    Rejected,
    Archived,
}

enum class PriceUnit {
    PerNight,
    PerPerson,
    Consumption,
    PerEntry,
    None,
}

data class City(
    val id: String,
    val name: String,
    val countryCode: String = "BJ",
    val latitude: Double? = null,
    val longitude: Double? = null,
)

data class Category(
    val id: String,
    val nameKey: String,
    val listingType: ListingType,
    val defaultListingClass: ListingClass,
)

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
)

val GeoPoint.isWithinBeninBounds: Boolean
    get() = latitude.isFinite() && longitude.isFinite() && BENIN_BOUNDARY.containsPoint(this)

fun nearestCity(cities: List<City>, location: GeoPoint): City? {
    if (!location.isWithinBeninBounds) {
        return null
    }
    return cities
        .asSequence()
        .filter { city ->
            city.countryCode == "BJ" &&
                city.latitude != null &&
                city.longitude != null &&
                GeoPoint(latitude = city.latitude, longitude = city.longitude).isWithinBeninBounds
        }
        .minByOrNull { city ->
            val latitudeDelta = requireNotNull(city.latitude) - location.latitude
            val longitudeDelta = requireNotNull(city.longitude) - location.longitude
            latitudeDelta * latitudeDelta + longitudeDelta * longitudeDelta
        }
}

private fun List<GeoPoint>.containsPoint(point: GeoPoint): Boolean {
    var inside = false
    var previous = last()
    for (current in this) {
        if (point.isOnSegment(previous, current)) {
            return true
        }
        val crossesLatitude = (current.latitude > point.latitude) != (previous.latitude > point.latitude)
        if (crossesLatitude) {
            val crossingLongitude = current.longitude +
                (point.latitude - current.latitude) *
                (previous.longitude - current.longitude) /
                (previous.latitude - current.latitude)
            if (point.longitude < crossingLongitude) {
                inside = !inside
            }
        }
        previous = current
    }
    return inside
}

private fun GeoPoint.isOnSegment(start: GeoPoint, end: GeoPoint): Boolean {
    val latitudeDelta = end.latitude - start.latitude
    val longitudeDelta = end.longitude - start.longitude
    val crossProduct = (latitude - start.latitude) * longitudeDelta -
        (longitude - start.longitude) * latitudeDelta
    if (abs(crossProduct) > BENIN_BOUNDARY_EPSILON) {
        return false
    }
    val withinLatitude = latitude >= minOf(start.latitude, end.latitude) - BENIN_BOUNDARY_EPSILON &&
        latitude <= maxOf(start.latitude, end.latitude) + BENIN_BOUNDARY_EPSILON
    val withinLongitude = longitude >= minOf(start.longitude, end.longitude) - BENIN_BOUNDARY_EPSILON &&
        longitude <= maxOf(start.longitude, end.longitude) + BENIN_BOUNDARY_EPSILON
    return withinLatitude && withinLongitude
}

private fun String.toBoundaryPoint(): GeoPoint {
    val separator = indexOf(',')
    check(separator > 0 && separator < lastIndex)
    return GeoPoint(
        latitude = substring(0, separator).toDouble(),
        longitude = substring(separator + 1).toDouble(),
    )
}

// Sampled from geoBoundaries BEN ADM0, commit 9469f09 (CC BY 4.0).
// https://www.geoboundaries.org/
private const val BENIN_BOUNDARY_COORDINATES = """
6.466417,2.704951
6.776321,2.762828
7.052194,2.769495
7.410584,2.721967
7.761267,2.729674
8.307426,2.699164
8.751015,2.739710
9.038032,2.781859
9.317639,3.167440
9.658551,3.291024
9.855088,3.528666
10.180234,3.686358
10.437187,3.712871
10.677744,3.845650
11.107366,3.697103
11.774246,3.521014
11.917360,3.288455
12.353014,2.884896
12.386787,2.689956
12.282378,2.558418
12.240450,2.461408
12.021768,2.467163
11.863922,2.412082
11.595094,2.203811
11.457502,1.509006
11.370687,1.348529
11.285154,1.271810
11.175813,1.143560
11.021256,1.110676
10.904647,0.893165
9.940176,1.339223
9.560360,1.330180
8.895270,1.618742
8.383093,1.612165
7.418291,1.649847
6.842812,1.623674
6.648433,1.609699
6.554712,1.689444
6.396044,1.793030
6.268617,1.663136
6.259573,1.631896
6.215779,1.649596
6.240868,1.743680
6.253413,1.806402
6.333301,2.535530
"""

private val BENIN_BOUNDARY: List<GeoPoint> = BENIN_BOUNDARY_COORDINATES
    .lineSequence()
    .map(String::trim)
    .filter(String::isNotEmpty)
    .map(String::toBoundaryPoint)
    .toList()

private const val BENIN_BOUNDARY_EPSILON = 0.000_001

data class ListingSummary(
    val id: String,
    val type: ListingType,
    val listingClass: ListingClass,
    val status: ListingStatus,
    val name: String,
    val cityId: String,
    val categoryId: String,
    val coverImageUrl: String?,
    val priceFromXof: MoneyXof?,
    val ratingAverage: Double?,
    val likesCount: Int,
    val verified: Boolean,
    val sponsoredUntilEpochMilliseconds: Long?,
    val isSponsoredPlacement: Boolean? = null,
    val coverImageAlt: String? = null,
    val viewsCount: Long? = null,
    val eventStartAtEpochMilliseconds: Long? = null,
    val eventEndAtEpochMilliseconds: Long? = null,
    val isEventEnded: Boolean? = null,
)

data class ListingViewerInteraction(
    val listingId: String,
    val likedByViewer: Boolean,
    val favoritedByViewer: Boolean,
    val likesCount: Int,
)

data class ListingContact(
    val phone: String?,
    val whatsapp: String?,
    val externalUrl: String?,
    val email: String?,
)

data class ListingFilters(
    val cityId: String? = null,
    val categoryId: String? = null,
    val listingType: ListingType? = null,
    val listingClass: ListingClass? = null,
    val onlyPublished: Boolean = true,
)

data class ListingSearchQuery(
    val text: String,
    val filters: ListingFilters = ListingFilters(),
) {
    init {
        require(text == text.trim()) { "Listing search query must be canonical." }
        require(text.length in 1..MAX_LISTING_SEARCH_QUERY_LENGTH) {
            "Listing search query must contain between 1 and $MAX_LISTING_SEARCH_QUERY_LENGTH characters."
        }
        require(text.none(Char::isISOControl)) { "Listing search query contains control characters." }
        require(filters.onlyPublished) { "Listing search only supports published listings." }
        require(filters.cityId.isValidListingSearchFilterId()) { "Listing search city id is invalid." }
        require(filters.categoryId.isValidListingSearchFilterId()) { "Listing search category id is invalid." }
    }
}

private fun String?.isValidListingSearchFilterId(): Boolean =
    this == null || (isNotBlank() && length <= MAX_LISTING_SEARCH_FILTER_ID_LENGTH && none(Char::isISOControl))

private const val MAX_LISTING_SEARCH_QUERY_LENGTH = 120
private const val MAX_LISTING_SEARCH_FILTER_ID_LENGTH = 100
