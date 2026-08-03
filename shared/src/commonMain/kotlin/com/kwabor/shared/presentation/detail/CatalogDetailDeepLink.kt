package com.kwabor.shared.presentation.detail

sealed interface CatalogDetailDeepLinkResult {
    data class Accepted(val listingId: String) : CatalogDetailDeepLinkResult

    data class Rejected(val reason: CatalogDetailDeepLinkRejection) : CatalogDetailDeepLinkResult
}

enum class CatalogDetailDeepLinkRejection {
    Malformed,
    UnsupportedScheme,
    UnsupportedHost,
    InvalidListingId,
}

object CatalogDetailDeepLinkParser {
    fun parse(rawUrl: String): CatalogDetailDeepLinkResult {
        if (rawUrl.isMalformedDeepLink()) {
            return rejected(CatalogDetailDeepLinkRejection.Malformed)
        }

        val schemeParts = rawUrl.split(SCHEME_SEPARATOR, limit = 2)
        return when {
            schemeParts.size != 2 || schemeParts.first().isEmpty() -> {
                rejected(CatalogDetailDeepLinkRejection.Malformed)
            }
            !schemeParts.first().equals(EXPECTED_SCHEME, ignoreCase = true) -> {
                rejected(CatalogDetailDeepLinkRejection.UnsupportedScheme)
            }
            else -> parseAuthorityAndListingId(schemeParts.last())
        }
    }
}

object CatalogDetailDeepLinkGenerator {
    fun generate(listingId: String): String? = listingId
        .takeIf(UUID_PATTERN::matches)
        ?.lowercase()
        ?.let { normalizedListingId -> "$CANONICAL_PREFIX$normalizedListingId" }
}

private fun parseAuthorityAndListingId(authorityAndPath: String): CatalogDetailDeepLinkResult {
    val pathStart = authorityAndPath.indexOf(PATH_SEPARATOR)
    if (pathStart <= 0 || pathStart == authorityAndPath.lastIndex) {
        return rejected(CatalogDetailDeepLinkRejection.Malformed)
    }

    val authority = authorityAndPath.substring(startIndex = 0, endIndex = pathStart)
    if (!authority.equals(EXPECTED_HOST, ignoreCase = true)) {
        return rejected(CatalogDetailDeepLinkRejection.UnsupportedHost)
    }

    val listingId = authorityAndPath.substring(startIndex = pathStart + 1)
    return if (UUID_PATTERN.matches(listingId)) {
        CatalogDetailDeepLinkResult.Accepted(listingId.lowercase())
    } else {
        rejected(CatalogDetailDeepLinkRejection.InvalidListingId)
    }
}

private fun String.isMalformedDeepLink(): Boolean = isBlank() ||
    length > MAX_DEEP_LINK_LENGTH ||
    this != trim() ||
    any { character -> character.isWhitespace() || character.isISOControl() } ||
    contains(QUERY_SEPARATOR) ||
    contains(FRAGMENT_SEPARATOR)

private fun rejected(reason: CatalogDetailDeepLinkRejection): CatalogDetailDeepLinkResult =
    CatalogDetailDeepLinkResult.Rejected(reason)

private const val EXPECTED_SCHEME = "kwabor"
private const val EXPECTED_HOST = "listing"
private const val SCHEME_SEPARATOR = "://"
private const val PATH_SEPARATOR = '/'
private const val QUERY_SEPARATOR = '?'
private const val FRAGMENT_SEPARATOR = '#'
private const val MAX_DEEP_LINK_LENGTH = 2_048
private const val CANONICAL_PREFIX = "$EXPECTED_SCHEME$SCHEME_SEPARATOR$EXPECTED_HOST$PATH_SEPARATOR"
private val UUID_PATTERN = Regex(
    pattern = "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
    option = RegexOption.IGNORE_CASE,
)
