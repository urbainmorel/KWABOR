package com.kwabor.shared.data.explore

import com.kwabor.shared.data.core.isCanonicalPublicHttpsUrl
import com.kwabor.shared.data.core.isValidUuid

internal fun String.isValidExploreUuidValue(): Boolean = isValidUuid() && this == lowercase()

internal fun String.isValidExploreCanonicalTextValue(length: IntRange? = null): Boolean {
    val codePointCount = validExploreUnicodeCodePointCount() ?: return false
    return isNotBlank() &&
        trim() == this &&
        none(Char::isISOControl) &&
        (length == null || codePointCount in length)
}

internal fun String.isValidExploreCursorValue(): Boolean =
    length in 1..MAXIMUM_EXPLORE_CURSOR_LENGTH && none(Char::isWhitespace)

internal fun String.isValidExploreHttpsUrlValue(): Boolean = isCanonicalPublicHttpsUrl()

private fun String.validExploreUnicodeCodePointCount(): Int? {
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

private const val MAXIMUM_EXPLORE_CURSOR_LENGTH = 4_096
private const val HIGH_SURROGATE_START = '\uD800'
private const val HIGH_SURROGATE_END = '\uDBFF'
private const val LOW_SURROGATE_START = '\uDC00'
private const val LOW_SURROGATE_END = '\uDFFF'
