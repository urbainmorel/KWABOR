package com.kwabor.shared.data.guide

import io.ktor.http.URLDecodeException
import io.ktor.http.URLParserException
import io.ktor.http.URLProtocol
import io.ktor.http.Url

private const val MAXIMUM_URL_UTF8_BYTES = 2_048
private const val HTTPS_PORT = 443
private const val HTTPS_PREFIX = "https://"
private const val MAXIMUM_HOST_LENGTH = 253
private const val MAXIMUM_HOST_LABEL_LENGTH = 63

internal fun String.requireGuideHttpsUrl(fieldName: String): String {
    requireGuideUrlShape(fieldName)
    val rawAuthority = rawAuthority()
    if (rawAuthority != rawAuthority.lowercase()) {
        invalidGuideDiscoveryValue(fieldName, this)
    }
    val parsed = parseGuideUrl(fieldName)
    val canonicalAuthority = parsed.host
    if (rawAuthority != canonicalAuthority && rawAuthority != "$canonicalAuthority:$HTTPS_PORT") {
        invalidGuideDiscoveryValue(fieldName, this)
    }
    parsed.requireCanonicalGuideHttpsUrl(fieldName, this)
    return this
}

private fun String.requireGuideUrlShape(fieldName: String) {
    if (encodeToByteArray().size !in HTTPS_PREFIX.length..MAXIMUM_URL_UTF8_BYTES) {
        invalidGuideDiscoveryValue(fieldName, "invalid length")
    }
    if (trim() != this) {
        invalidGuideDiscoveryValue(fieldName, this)
    }
    if (any(Char::isWhitespace)) {
        invalidGuideDiscoveryValue(fieldName, this)
    }
    if (!startsWith(HTTPS_PREFIX)) {
        invalidGuideDiscoveryValue(fieldName, this)
    }
    if ('\\' in this) {
        invalidGuideDiscoveryValue(fieldName, this)
    }
    if ('#' in this) {
        invalidGuideDiscoveryValue(fieldName, this)
    }
}

private fun String.parseGuideUrl(fieldName: String): Url = try {
    Url(this)
} catch (exception: URLParserException) {
    invalidGuideDiscoveryValue(fieldName, this, exception)
} catch (exception: URLDecodeException) {
    invalidGuideDiscoveryValue(fieldName, this, exception)
}

private fun Url.requireCanonicalGuideHttpsUrl(fieldName: String, rawValue: String) {
    if (protocol != URLProtocol.HTTPS) {
        invalidGuideDiscoveryValue(fieldName, rawValue)
    }
    if (port != HTTPS_PORT) {
        invalidGuideDiscoveryValue(fieldName, rawValue)
    }
    if (user?.isNotEmpty() == true) {
        invalidGuideDiscoveryValue(fieldName, rawValue)
    }
    if (password?.isNotEmpty() == true) {
        invalidGuideDiscoveryValue(fieldName, rawValue)
    }
    if (fragment.isNotEmpty()) {
        invalidGuideDiscoveryValue(fieldName, rawValue)
    }
    if (!host.isCanonicalHost()) {
        invalidGuideDiscoveryValue(fieldName, rawValue)
    }
}

private fun String.rawAuthority(): String {
    val authorityEnd = indexOfAny(charArrayOf('/', '?'), startIndex = HTTPS_PREFIX.length)
        .takeIf { index -> index >= 0 }
        ?: length
    return substring(HTTPS_PREFIX.length, authorityEnd)
}

private fun String.isCanonicalHost(): Boolean {
    if (this != lowercase()) {
        return false
    }
    if (length > MAXIMUM_HOST_LENGTH) {
        return false
    }
    if (!contains('.')) {
        return false
    }
    if (':' in this) {
        return false
    }
    if (isNumericHost()) {
        return false
    }
    if (hasForbiddenHostSuffix()) {
        return false
    }
    return split('.').all(String::isCanonicalHostLabel)
}

private fun String.isNumericHost(): Boolean = all { character -> character in '0'..'9' || character == '.' }

private fun String.hasForbiddenHostSuffix(): Boolean =
    FORBIDDEN_HOST_SUFFIXES.any { suffix -> this == suffix || endsWith(".$suffix") }

private fun String.isCanonicalHostLabel(): Boolean {
    if (length !in 1..MAXIMUM_HOST_LABEL_LENGTH) {
        return false
    }
    if (!first().isAsciiLetterOrDigit()) {
        return false
    }
    if (!last().isAsciiLetterOrDigit()) {
        return false
    }
    return all { character -> character.isAsciiLetterOrDigit() || character == '-' }
}

private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in '0'..'9'

private val FORBIDDEN_HOST_SUFFIXES = setOf("localhost", "local", "internal", "lan", "home.arpa")
