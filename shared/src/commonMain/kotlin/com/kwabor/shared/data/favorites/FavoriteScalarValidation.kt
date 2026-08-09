package com.kwabor.shared.data.favorites

import com.kwabor.shared.data.core.isValidUuid
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.money.MoneyXof
import io.ktor.http.URLDecodeException
import io.ktor.http.URLParserException
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlin.time.Instant

private const val MAXIMUM_IDENTIFIER_LENGTH = 100
private const val MAXIMUM_URL_UTF8_BYTES = 2_048
private const val MAXIMUM_RATING = 5.0
private const val HTTPS_PORT = 443
private const val HTTPS_PREFIX = "https://"
private const val MAXIMUM_HOST_LENGTH = 253
private const val MAXIMUM_HOST_LABEL_LENGTH = 63
private const val HIGH_SURROGATE_START = '\uD800'
private const val HIGH_SURROGATE_END = '\uDBFF'
private const val LOW_SURROGATE_START = '\uDC00'
private const val LOW_SURROGATE_END = '\uDFFF'

internal fun String.requireFavoriteUuid(fieldName: String): String {
    if (!isValidUuid() || this != lowercase()) {
        invalidFavoriteValue(fieldName, this)
    }
    return this
}

internal fun String.requireFavoriteIdentifier(fieldName: String): String {
    if (length !in 1..MAXIMUM_IDENTIFIER_LENGTH || !CANONICAL_IDENTIFIER.matches(this)) {
        invalidFavoriteValue(fieldName, this)
    }
    return this
}

internal fun String.requireFavoriteText(
    fieldName: String,
    allowedCodePointCount: IntRange? = null,
): String {
    val codePointCount = validUnicodeCodePointCount()
    if (isBlank() || trim() != this) invalidFavoriteValue(fieldName, this)
    if (codePointCount == null) invalidFavoriteValue(fieldName, this)
    if (allowedCodePointCount != null && codePointCount !in allowedCodePointCount) {
        invalidFavoriteValue(fieldName, this)
    }
    if (any(Char::isISOControl)) invalidFavoriteValue(fieldName, this)
    return this
}

private fun String.validUnicodeCodePointCount(): Int? {
    var index = 0
    var count = 0
    while (index < length) {
        val character = this[index]
        val width = when {
            character.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate() -> 2
            character.isHighSurrogate() || character.isLowSurrogate() -> return null
            else -> 1
        }
        index += width
        count += 1
    }
    return count
}

private fun Char.isHighSurrogate(): Boolean = this in HIGH_SURROGATE_START..HIGH_SURROGATE_END

private fun Char.isLowSurrogate(): Boolean = this in LOW_SURROGATE_START..LOW_SURROGATE_END

internal fun String.requireFavoriteInstant(fieldName: String): Instant {
    if (isBlank() || trim() != this) invalidFavoriteValue(fieldName, this)
    if (any(Char::isWhitespace) || any(Char::isISOControl)) invalidFavoriteValue(fieldName, this)
    val instant = try {
        Instant.parse(this)
    } catch (exception: IllegalArgumentException) {
        invalidFavoriteValue(fieldName, this, exception)
    }
    if (instant < MINIMUM_FAVORITE_INSTANT || instant >= EXCLUSIVE_MAXIMUM_FAVORITE_INSTANT) {
        invalidFavoriteValue(fieldName, this)
    }
    return instant
}

internal fun String.requireFavoriteTimestamp(fieldName: String): Long =
    requireFavoriteInstant(fieldName).toEpochMilliseconds()

internal fun Long.requireFavoriteMoney(fieldName: String): MoneyXof = when (val result = MoneyXof.fromAmount(this)) {
    is DomainResult.Success -> result.value
    is DomainResult.Failure -> invalidFavoriteValue(fieldName, toString())
}

internal fun Double?.requireFavoriteRating(fieldName: String): Double? {
    if (this != null && (!isFinite() || this !in 0.0..MAXIMUM_RATING)) {
        invalidFavoriteValue(fieldName, toString())
    }
    return this
}

internal fun Int.requireNonNegative(fieldName: String): Int {
    if (this < 0) {
        invalidFavoriteValue(fieldName, toString())
    }
    return this
}

internal fun String.requireFavoriteHttpsUrl(fieldName: String): String {
    requireFavoriteUrlSyntax(fieldName)
    val parsed = parseFavoriteUrl(fieldName)
    if (!parsed.isCanonicalPublicHttpsUrl(rawAuthority())) {
        invalidFavoriteValue(fieldName, this)
    }
    return this
}

private fun String.requireFavoriteUrlSyntax(fieldName: String) {
    if (encodeToByteArray().size !in 1..MAXIMUM_URL_UTF8_BYTES || trim() != this) {
        invalidFavoriteValue(fieldName, this)
    }
    if (any(Char::isWhitespace) || '\\' in this || '#' in this) invalidFavoriteValue(fieldName, this)
    if (!startsWith(HTTPS_PREFIX)) invalidFavoriteValue(fieldName, this)
}

private fun String.parseFavoriteUrl(fieldName: String): Url =
    try {
        Url(this)
    } catch (exception: URLParserException) {
        invalidFavoriteValue(fieldName, this, exception)
    } catch (exception: URLDecodeException) {
        invalidFavoriteValue(fieldName, this, exception)
    }

private fun Url.isCanonicalPublicHttpsUrl(rawAuthority: String): Boolean =
    protocol == URLProtocol.HTTPS &&
        port == HTTPS_PORT &&
        rawAuthority in setOf(host, "$host:$HTTPS_PORT") &&
        host.isCanonicalPublicDnsHost() &&
        user?.isNotEmpty() != true &&
        password?.isNotEmpty() != true

private fun String.rawAuthority(): String {
    val authorityEnd = indexOfAny(charArrayOf('/', '?'), startIndex = HTTPS_PREFIX.length)
        .takeIf { index -> index >= 0 }
        ?: length
    return substring(HTTPS_PREFIX.length, authorityEnd)
}

private fun String.isCanonicalPublicDnsHost(): Boolean =
    this == lowercase() &&
        length <= MAXIMUM_HOST_LENGTH &&
        contains('.') &&
        ':' !in this &&
        any { character -> character !in '0'..'9' && character != '.' } &&
        FORBIDDEN_HOST_SUFFIXES.none { suffix -> this == suffix || endsWith(".$suffix") } &&
        split('.').all { label -> label.isCanonicalDnsLabel() }

private fun String.isCanonicalDnsLabel(): Boolean =
    length in 1..MAXIMUM_HOST_LABEL_LENGTH &&
        first().isAsciiLetterOrDigit() &&
        last().isAsciiLetterOrDigit() &&
        all { character -> character.isAsciiLetterOrDigit() || character == '-' }

private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in '0'..'9'

private val CANONICAL_IDENTIFIER = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
private val MINIMUM_FAVORITE_INSTANT = Instant.parse("0001-01-01T00:00:00Z")
private val EXCLUSIVE_MAXIMUM_FAVORITE_INSTANT = Instant.parse("+10000-01-01T00:00:00Z")
private val FORBIDDEN_HOST_SUFFIXES = setOf("localhost", "local", "internal", "lan", "home.arpa")
