package com.kwabor.android.media

import java.net.URI
import java.net.URISyntaxException

fun interface ListingMediaUrlPolicy {
    fun safeUrlOrNull(candidate: String?): String?
}

object PublicHttpsListingMediaUrlPolicy : ListingMediaUrlPolicy {
    override fun safeUrlOrNull(candidate: String?): String? = candidate?.takeIf { value ->
        value.isSafePublicHttpsListingMediaUrl()
    }
}

private fun String.isSafePublicHttpsListingMediaUrl(): Boolean {
    if (!hasSafeRawUrlShape()) {
        return false
    }
    val rawAuthority = rawAuthority()
    val uri = toUriOrNull() ?: return false
    return uri.isAllowedListingMediaUri(rawAuthority)
}

private fun String.hasSafeRawUrlShape(): Boolean {
    if (encodeToByteArray().size !in MINIMUM_HTTPS_URL_BYTES..MAXIMUM_HTTPS_URL_BYTES) {
        return false
    }
    if (!startsWith(HTTPS_PREFIX)) {
        return false
    }
    return none(Char::isWhitespace) && '\\' !in this
}

private fun String.rawAuthority(): String {
    val authorityEnd = indexOfAny(charArrayOf('/', '?'), startIndex = HTTPS_PREFIX.length)
        .takeIf { index -> index >= 0 }
        ?: length
    return substring(HTTPS_PREFIX.length, authorityEnd)
}

private fun URI.isAllowedListingMediaUri(rawAuthority: String): Boolean {
    val canonicalHost = host ?: return false
    if (scheme != HTTPS_SCHEME || canonicalHost != canonicalHost.lowercase()) {
        return false
    }
    if (!hasCanonicalAuthority(rawAuthority, canonicalHost) || !hasAllowedHttpsPort()) {
        return false
    }
    return userInfo == null && fragment == null && canonicalHost.isCanonicalPublicDnsHost()
}

private fun hasCanonicalAuthority(rawAuthority: String, canonicalHost: String): Boolean =
    rawAuthority == canonicalHost || rawAuthority == "$canonicalHost:$HTTPS_PORT"

private fun URI.hasAllowedHttpsPort(): Boolean = port == DEFAULT_PORT || port == HTTPS_PORT

private fun String.toUriOrNull(): URI? = try {
    URI(this)
} catch (_: URISyntaxException) {
    null
}

private fun String.isCanonicalPublicDnsHost(): Boolean = length <= MAXIMUM_HOST_LENGTH &&
    contains('.') &&
    all { character -> character in DNS_HOST_CHARACTERS } &&
    any { character -> character !in '0'..'9' && character != '.' } &&
    FORBIDDEN_HOST_SUFFIXES.none { suffix -> this == suffix || endsWith(".$suffix") } &&
    split('.').all { label ->
        label.length in 1..MAXIMUM_HOST_LABEL_LENGTH &&
            label.first() in ASCII_ALPHANUMERIC &&
            label.last() in ASCII_ALPHANUMERIC
    }

private const val HTTPS_SCHEME = "https"
private const val HTTPS_PREFIX = "https://"
private const val DEFAULT_PORT = -1
private const val HTTPS_PORT = 443
private const val MINIMUM_HTTPS_URL_BYTES = 9
private const val MAXIMUM_HTTPS_URL_BYTES = 2_048
private const val MAXIMUM_HOST_LENGTH = 253
private const val MAXIMUM_HOST_LABEL_LENGTH = 63
private val ASCII_ALPHANUMERIC = ('a'..'z') + ('0'..'9')
private val DNS_HOST_CHARACTERS = ASCII_ALPHANUMERIC + '-' + '.'
private val FORBIDDEN_HOST_SUFFIXES = setOf("localhost", "local", "internal", "lan", "home.arpa")
