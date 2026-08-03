package com.kwabor.shared.data.catalog

import com.kwabor.shared.domain.catalog.CatalogDetailCommon
import com.kwabor.shared.domain.catalog.CatalogPrice
import com.kwabor.shared.domain.catalog.CatalogRoomType
import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.catalog.PriceUnit
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.money.MoneyXof
import io.ktor.http.URLDecodeException
import io.ktor.http.URLParserException
import io.ktor.http.URLProtocol
import io.ktor.http.Url

private const val MINIMUM_STAR_RATING = 0
private const val MAXIMUM_STAR_RATING = 5
private const val MINIMUM_HOTEL_STAR_RATING = 1
private const val MINIMUM_NIGHTLIFE_AGE = 16
private const val MAXIMUM_NIGHTLIFE_AGE = 25
private const val MAXIMUM_GUIDE_EXPERIENCE_YEARS = 80
private const val MINIMUM_TIME_COMPONENT_COUNT = 2
private const val MAXIMUM_TIME_COMPONENT_COUNT = 3
private const val TIME_COMPONENT_WIDTH = 2
private const val MAXIMUM_HOUR_OF_DAY = 23
private const val MAXIMUM_MINUTE_OR_SECOND = 59
private const val MINUTES_PER_HOUR = 60

internal fun CatalogDetailCommon.requireVariant(expectedType: ListingType) {
    if (type != expectedType) {
        invalidCatalogDetail("type", type.name)
    }
}

internal fun CatalogDetailCommon.requireEstablishmentVariant() {
    requireVariant(ListingType.Establishment)
    val hasContact = contact.phone != null || contact.whatsapp != null || contact.externalUrl != null ||
        contact.email != null
    if (listingClass != ListingClass.Commercial || location.address == null || location.geoPoint == null) {
        invalidCatalogDetail("establishment.common", toString())
    }
    if (openingHours == com.kwabor.shared.domain.catalog.CatalogOpeningHours.Unspecified) {
        invalidCatalogDetail("establishment.common", toString())
    }
    if (!hasContact || amenities.isEmpty()) {
        invalidCatalogDetail("establishment.common", toString())
    }
}

internal fun CatalogDetailCommon.requireValidPlaceCommon() {
    requireVariant(ListingType.Place)
    if (listingClass == ListingClass.Event) {
        invalidCatalogDetail("listing_class", listingClass.name)
    }
    if (location.address == null || location.geoPoint == null) {
        invalidCatalogDetail("listing_class", listingClass.name)
    }
    if (listingClass == ListingClass.Heritage && isClaimable) {
        invalidCatalogDetail("listing_class", listingClass.name)
    }
}

internal fun CatalogPlaceDetailDto.requireValidPlaceIdentity(common: CatalogDetailCommon) {
    if (variant != "place" || placeCategory != common.subtype) {
        invalidCatalogDetail("detail.place", toString())
    }
    if (isFree != (entryFeeXof == null)) {
        invalidCatalogDetail("detail.place", toString())
    }
}

internal fun CatalogPlaceDetailDto.requireValidPlacePrice(price: CatalogPrice, entryFee: MoneyXof?) {
    if (!isFree && entryFee?.amount == 0L) {
        invalidCatalogDetail("detail.entry_fee_xof", "0")
    }
    if (isFree && (price.unit != PriceUnit.None || price.from != null)) {
        invalidCatalogDetail("detail.place.price", toString())
    }
    if (!isFree && (price.unit != PriceUnit.PerEntry || price.from != entryFee)) {
        invalidCatalogDetail("detail.place.price", toString())
    }
}

internal fun CatalogLodgingDetailDto.requireValidLodging(common: CatalogDetailCommon) {
    if (variant != "lodging") {
        invalidCatalogDetail("detail.lodging", toString())
    }
    if (starRating?.let { it !in MINIMUM_STAR_RATING..MAXIMUM_STAR_RATING } == true) {
        invalidCatalogDetail("detail.lodging", toString())
    }
    if (common.subtype == "hotel" &&
        (starRating == null || starRating !in MINIMUM_HOTEL_STAR_RATING..MAXIMUM_STAR_RATING)
    ) {
        invalidCatalogDetail("detail.lodging", toString())
    }
    if (roomCount?.let { it <= 0 } == true) {
        invalidCatalogDetail("detail.lodging", toString())
    }
}

internal fun CatalogPrice.requireValidLodgingPrice(roomTypes: List<CatalogRoomType>) {
    val referencePrice = from
    if (unit != PriceUnit.PerNight || referencePrice == null) {
        invalidCatalogDetail("detail.lodging.price", toString())
    }
    if (roomTypes.isNotEmpty() && roomTypes.minOf { room -> room.price.amount } != referencePrice.amount) {
        invalidCatalogDetail("detail.lodging.price", toString())
    }
}

internal fun CatalogNightlifeDetailDto.requireValidNightlife(common: CatalogDetailCommon) {
    if (variant != "nightlife" || venueKind != common.subtype) {
        invalidCatalogDetail("detail.nightlife", toString())
    }
    if (minimumAge?.let { it !in MINIMUM_NIGHTLIFE_AGE..MAXIMUM_NIGHTLIFE_AGE } == true) {
        invalidCatalogDetail("detail.nightlife", toString())
    }
    if (common.subtype == "club" && minimumAge == null) {
        invalidCatalogDetail("detail.nightlife", toString())
    }
    if (common.price.from == null || common.price.unit !in setOf(PriceUnit.Consumption, PriceUnit.PerEntry)) {
        invalidCatalogDetail("detail.nightlife.price", common.price.toString())
    }
}

internal fun CatalogGuideDetailDto.requireValidGuide(common: CatalogDetailCommon) {
    if (variant != "guide" || languages.isEmpty() || zones.isEmpty()) {
        invalidCatalogDetail("detail.guide", toString())
    }
    if (specialties.isEmpty() || experienceYears?.let { it !in 0..MAXIMUM_GUIDE_EXPERIENCE_YEARS } == true) {
        invalidCatalogDetail("detail.guide", toString())
    }
    val indicativePrice = indicativePriceXof?.toNonNegativeMoney("guide_details.indicative_price_xof")
    if (indicativePrice == null || common.price.unit != PriceUnit.PerPerson || common.price.from != indicativePrice) {
        invalidCatalogDetail("detail.guide.price", common.price.toString())
    }
}

internal fun CatalogPrice.requirePresentUnit(expectedUnit: PriceUnit, fieldName: String) {
    if (unit != expectedUnit || from == null) {
        invalidCatalogDetail(fieldName, toString())
    }
}

internal fun String.toCatalogLocale(): AppLocale = AppLocale.entries.firstOrNull { locale -> locale.tag == this }
    ?: invalidCatalogDetail("content_lang", this)

internal fun String.toCatalogPriceUnit(): PriceUnit = when (this) {
    "par_nuit" -> PriceUnit.PerNight
    "par_personne" -> PriceUnit.PerPerson
    "consommation" -> PriceUnit.Consumption
    "par_entree" -> PriceUnit.PerEntry
    "aucune" -> PriceUnit.None
    else -> invalidCatalogDetail("price.unit", this)
}

internal fun String.toMinuteOfDay(fieldName: String): Int {
    val parts = split(':')
    if (parts.size !in MINIMUM_TIME_COMPONENT_COUNT..MAXIMUM_TIME_COMPONENT_COUNT) {
        invalidCatalogDetail(fieldName, this)
    }
    if (parts.any { part -> part.length != TIME_COMPONENT_WIDTH || part.any { !it.isDigit() } }) {
        invalidCatalogDetail(fieldName, this)
    }
    val hour = parts[0].toInt()
    val minute = parts[1].toInt()
    val second = parts.getOrNull(2)?.toInt() ?: 0
    if (hour !in 0..MAXIMUM_HOUR_OF_DAY || minute !in 0..MAXIMUM_MINUTE_OR_SECOND || second != 0) {
        invalidCatalogDetail(fieldName, this)
    }
    return hour * MINUTES_PER_HOUR + minute
}

internal fun String.requireCatalogText(fieldName: String): String {
    if (isBlank() || trim() != this) {
        invalidCatalogDetail(fieldName, this)
    }
    return this
}

internal fun String.requireCatalogHttpsUrl(fieldName: String): String =
    CatalogUrlValidator.requireHttps(this, fieldName)

internal fun Int.requireNonNegative(fieldName: String): Int {
    if (this < 0) {
        invalidCatalogDetail(fieldName, toString())
    }
    return this
}

internal fun invalidCatalogDetail(fieldName: String, value: String, cause: Throwable? = null): Nothing {
    throw CatalogDataException.Unexpected(
        IllegalStateException("Invalid catalog detail value for $fieldName: $value", cause),
    )
}

private object CatalogUrlValidator {
    fun requireHttps(value: String, fieldName: String): String {
        requireLexicalForm(value, fieldName)
        val rawAuthority = value.rawAuthority()
        if (rawAuthority != rawAuthority.lowercase()) {
            invalidCatalogDetail(fieldName, value)
        }
        val parsed = parse(value, fieldName)
        requireCanonicalAuthority(value, fieldName, rawAuthority, parsed)
        requireCanonicalUrl(value, fieldName, parsed)
        return value
    }

    private fun requireLexicalForm(value: String, fieldName: String) {
        val encodedSize = value.encodeToByteArray().size
        if (encodedSize !in MINIMUM_CATALOG_URL_UTF8_BYTES..MAXIMUM_CATALOG_URL_UTF8_BYTES) {
            invalidCatalogDetail(fieldName, value)
        }
        if (value.trim() != value || value.any(Char::isWhitespace)) {
            invalidCatalogDetail(fieldName, value)
        }
        if (!value.startsWith(HTTPS_PREFIX) || '\\' in value || '#' in value) {
            invalidCatalogDetail(fieldName, value)
        }
    }

    private fun String.rawAuthority(): String {
        val authorityEnd = indexOfAny(charArrayOf('/', '?'), startIndex = HTTPS_PREFIX.length)
            .takeIf { index -> index >= 0 }
            ?: length
        return substring(HTTPS_PREFIX.length, authorityEnd)
    }

    private fun parse(value: String, fieldName: String): Url = try {
        Url(value)
    } catch (exception: URLParserException) {
        invalidCatalogDetail(fieldName, value, exception)
    } catch (exception: URLDecodeException) {
        invalidCatalogDetail(fieldName, value, exception)
    }

    private fun requireCanonicalAuthority(value: String, fieldName: String, rawAuthority: String, parsed: Url) {
        val canonicalAuthority = parsed.host
        val canonicalAuthorityWithPort = "${parsed.host}:$HTTPS_PORT"
        if (rawAuthority != canonicalAuthority && rawAuthority != canonicalAuthorityWithPort) {
            invalidCatalogDetail(fieldName, value)
        }
    }

    private fun requireCanonicalUrl(value: String, fieldName: String, parsed: Url) {
        if (parsed.protocol != URLProtocol.HTTPS || parsed.port != HTTPS_PORT) {
            invalidCatalogDetail(fieldName, value)
        }
        if (parsed.user?.isNotEmpty() == true ||
            parsed.password?.isNotEmpty() == true ||
            parsed.fragment.isNotEmpty()
        ) {
            invalidCatalogDetail(fieldName, value)
        }
        if (!parsed.host.isCanonicalHost()) {
            invalidCatalogDetail(fieldName, value)
        }
    }

    private fun String.isCanonicalHost(): Boolean {
        if (this != lowercase() || length > MAXIMUM_HOST_LENGTH) {
            return false
        }
        if (!contains('.') || ':' in this) {
            return false
        }
        if (all { character -> character in '0'..'9' || character == '.' }) {
            return false
        }
        if (FORBIDDEN_HOST_SUFFIXES.any { suffix -> this == suffix || endsWith(".$suffix") }) {
            return false
        }
        return split('.').all { label -> label.isCanonicalHostLabel() }
    }

    private fun String.isCanonicalHostLabel(): Boolean = length in 1..MAXIMUM_HOST_LABEL_LENGTH &&
        first().isAsciiLetterOrDigit() &&
        last().isAsciiLetterOrDigit() &&
        all { character -> character.isAsciiLetterOrDigit() || character == '-' }

    private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in '0'..'9'

    private const val HTTPS_PORT = 443
    private const val HTTPS_PREFIX = "https://"
    private const val MINIMUM_CATALOG_URL_UTF8_BYTES = 9
    private const val MAXIMUM_CATALOG_URL_UTF8_BYTES = 2_048
    private const val MAXIMUM_HOST_LENGTH = 253
    private const val MAXIMUM_HOST_LABEL_LENGTH = 63
    private val FORBIDDEN_HOST_SUFFIXES = setOf(
        "localhost",
        "local",
        "internal",
        "lan",
        "home.arpa",
    )
}
