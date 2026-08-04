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

private fun String.isValidSearchText(): Boolean {
    var codePointCount = 0
    var index = 0

    while (index < length && codePointCount <= MAX_SEARCH_QUERY_LENGTH) {
        val codePointWidth = validCodePointWidthAt(index) ?: return false
        index += codePointWidth
        codePointCount += 1
    }

    return index == length && codePointCount in MIN_SEARCH_QUERY_LENGTH..MAX_SEARCH_QUERY_LENGTH
}

private fun String.validCodePointWidthAt(index: Int): Int? {
    val character = this[index]
    return when {
        character.isISOControl() -> null
        character.isUnicodeHighSurrogate() ->
            UTF_16_SURROGATE_PAIR_SIZE.takeIf {
                index + 1 < length && this[index + 1].isUnicodeLowSurrogate()
            }

        character.isUnicodeLowSurrogate() -> null
        else -> 1
    }
}

private fun Char.isUnicodeHighSurrogate(): Boolean = this in HIGH_SURROGATE_START..HIGH_SURROGATE_END

private fun Char.isUnicodeLowSurrogate(): Boolean = this in LOW_SURROGATE_START..LOW_SURROGATE_END

private const val MIN_SEARCH_QUERY_LENGTH = 1
private const val MAX_SEARCH_QUERY_LENGTH = 120
private const val UTF_16_SURROGATE_PAIR_SIZE = 2
private const val HIGH_SURROGATE_START = '\uD800'
private const val HIGH_SURROGATE_END = '\uDBFF'
private const val LOW_SURROGATE_START = '\uDC00'
private const val LOW_SURROGATE_END = '\uDFFF'
