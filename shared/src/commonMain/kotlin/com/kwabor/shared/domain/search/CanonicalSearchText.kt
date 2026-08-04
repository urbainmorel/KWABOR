package com.kwabor.shared.domain.search

internal class CanonicalSearchText private constructor(
    val value: String,
) {
    companion object {
        fun from(text: String): CanonicalSearchText? {
            val canonicalText = text.trim()
            return canonicalText
                .takeIf(String::isValidSearchText)
                ?.let(::CanonicalSearchText)
        }
    }

    override fun equals(other: Any?): Boolean = other is CanonicalSearchText && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "CanonicalSearchText(value=<redacted>)"
}

private fun String.isValidSearchText(): Boolean =
    length in MIN_SEARCH_QUERY_LENGTH..MAX_SEARCH_QUERY_LENGTH && none(Char::isISOControl)

private const val MIN_SEARCH_QUERY_LENGTH = 1
private const val MAX_SEARCH_QUERY_LENGTH = 120
