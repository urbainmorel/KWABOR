package com.kwabor.shared.data.explore

import kotlin.time.Instant

private const val MAXIMUM_IDENTIFIER_LENGTH = 100
private const val MINIMUM_RATING = 0.0
private const val MAXIMUM_RATING = 5.0
private const val MICROSECONDS_PER_SECOND = 1_000_000L
private const val NANOSECONDS_PER_MICROSECOND = 1_000
private val IDENTIFIER_PATTERN = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
private val MINIMUM_MOBILE_INSTANT = Instant.parse("0001-01-01T00:00:00Z")
private val EXCLUSIVE_MAXIMUM_MOBILE_INSTANT = Instant.parse("+10000-01-01T00:00:00Z")

internal fun requireExploreUuid(value: String) {
    if (!value.isValidExploreUuidValue()) {
        invalidExploreCatalogValue("id", value)
    }
}

internal fun requireExploreIdentifier(value: String, fieldName: String) {
    if (value.length !in 1..MAXIMUM_IDENTIFIER_LENGTH || !IDENTIFIER_PATTERN.matches(value)) {
        invalidExploreCatalogValue(fieldName, value)
    }
}

internal fun requireExploreCanonicalText(value: String, fieldName: String, length: IntRange? = null) {
    if (!value.isValidExploreCanonicalTextValue(length)) {
        invalidExploreCatalogValue(fieldName, value)
    }
}

internal fun requireExploreCursor(value: String) {
    if (!value.isValidExploreCursorValue()) {
        invalidExploreCatalogValue("row_cursor", value)
    }
}

internal fun requireExploreRating(value: Double?) {
    if (value != null && (!value.isFinite() || value !in MINIMUM_RATING..MAXIMUM_RATING)) {
        invalidExploreCatalogValue("rating_avg", value.toString())
    }
}

internal fun requireExplorePrice(value: Long) {
    if (value !in 0..Int.MAX_VALUE.toLong()) {
        invalidExploreCatalogValue("price_from_xof", value.toString())
    }
}

internal fun requireExploreNonNegative(value: Long, fieldName: String) {
    if (value < 0) {
        invalidExploreCatalogValue(fieldName, value.toString())
    }
}

internal fun String.toExploreInstant(fieldName: String): Instant {
    val instant = try {
        if (isBlank() || trim() != this || any(Char::isWhitespace)) {
            invalidExploreCatalogValue(fieldName, this)
        }
        Instant.parse(this)
    } catch (exception: IllegalArgumentException) {
        invalidExploreCatalogValue(fieldName, this, exception)
    }
    if (instant < MINIMUM_MOBILE_INSTANT || instant >= EXCLUSIVE_MAXIMUM_MOBILE_INSTANT) {
        invalidExploreCatalogValue(fieldName, this)
    }
    if (instant.nanosecondsOfSecond % NANOSECONDS_PER_MICROSECOND != 0) {
        invalidExploreCatalogValue(fieldName, this)
    }
    return instant
}

internal fun Instant.toEpochMicroseconds(fieldName: String): Long {
    if (this < MINIMUM_MOBILE_INSTANT || this >= EXCLUSIVE_MAXIMUM_MOBILE_INSTANT) {
        invalidExploreCatalogValue(fieldName, toString())
    }
    if (nanosecondsOfSecond % NANOSECONDS_PER_MICROSECOND != 0) {
        invalidExploreCatalogValue(fieldName, toString())
    }
    if (epochSeconds !in Long.MIN_VALUE / MICROSECONDS_PER_SECOND..Long.MAX_VALUE / MICROSECONDS_PER_SECOND) {
        invalidExploreCatalogValue(fieldName, toString())
    }
    val secondsAsMicroseconds = epochSeconds * MICROSECONDS_PER_SECOND
    val fractionalMicroseconds = nanosecondsOfSecond / NANOSECONDS_PER_MICROSECOND
    if (secondsAsMicroseconds > Long.MAX_VALUE - fractionalMicroseconds) {
        invalidExploreCatalogValue(fieldName, toString())
    }
    return secondsAsMicroseconds + fractionalMicroseconds
}
