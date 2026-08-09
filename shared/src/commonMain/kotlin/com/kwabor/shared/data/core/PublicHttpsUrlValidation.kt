package com.kwabor.shared.data.core

import io.ktor.http.URLDecodeException
import io.ktor.http.URLParserException
import io.ktor.http.URLProtocol
import io.ktor.http.Url

internal fun String.isCanonicalPublicHttpsUrl(): Boolean {
    if (!hasCanonicalPublicHttpsLexicalForm()) {
        return false
    }
    val rawAuthority = rawHttpsAuthority()
    if (rawAuthority != rawAuthority.lowercase()) {
        return false
    }
    val parsed = parsePublicHttpsUrlOrNull() ?: return false
    return rawAuthority.matchesCanonicalAuthority(parsed) && parsed.isCanonicalPublicHttpsUrl()
}

private fun String.hasCanonicalPublicHttpsLexicalForm(): Boolean =
    encodeToByteArray().size in MINIMUM_PUBLIC_URL_UTF8_BYTES..MAXIMUM_PUBLIC_URL_UTF8_BYTES &&
        trim() == this &&
        none(Char::isWhitespace) &&
        startsWith(HTTPS_PREFIX) &&
        '\\' !in this &&
        '#' !in this

private fun String.rawHttpsAuthority(): String {
    val authorityEnd = indexOfAny(charArrayOf('/', '?'), startIndex = HTTPS_PREFIX.length)
        .takeIf { index -> index >= 0 }
        ?: length
    return substring(HTTPS_PREFIX.length, authorityEnd)
}

private fun String.parsePublicHttpsUrlOrNull(): Url? = try {
    Url(this)
} catch (_: URLParserException) {
    null
} catch (_: URLDecodeException) {
    null
}

private fun String.matchesCanonicalAuthority(parsed: Url): Boolean =
    this == parsed.host || this == "${parsed.host}:$HTTPS_PORT"

private fun Url.isCanonicalPublicHttpsUrl(): Boolean = protocol == URLProtocol.HTTPS &&
    port == HTTPS_PORT &&
    user.isNullOrEmpty() &&
    password.isNullOrEmpty() &&
    fragment.isEmpty() &&
    host.isCanonicalPublicDnsHost()

private fun String.isCanonicalPublicDnsHost(): Boolean = this == lowercase() &&
    length <= MAXIMUM_HOST_LENGTH &&
    contains('.') &&
    ':' !in this &&
    any { character -> character !in '0'..'9' && character != '.' } &&
    FORBIDDEN_HOST_SUFFIXES.none { suffix -> this == suffix || endsWith(".$suffix") } &&
    split('.').all(String::isCanonicalDnsLabel)

private fun String.isCanonicalDnsLabel(): Boolean = length in 1..MAXIMUM_HOST_LABEL_LENGTH &&
    first().isAsciiLetterOrDigit() &&
    last().isAsciiLetterOrDigit() &&
    all { character -> character.isAsciiLetterOrDigit() || character == '-' }

private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in '0'..'9'

private const val HTTPS_PORT = 443
private const val HTTPS_PREFIX = "https://"
private const val MINIMUM_PUBLIC_URL_UTF8_BYTES = 9
private const val MAXIMUM_PUBLIC_URL_UTF8_BYTES = 2_048
private const val MAXIMUM_HOST_LENGTH = 253
private const val MAXIMUM_HOST_LABEL_LENGTH = 63
private val FORBIDDEN_HOST_SUFFIXES = setOf(
    "localhost",
    "local",
    "internal",
    "lan",
    "home.arpa",
)
