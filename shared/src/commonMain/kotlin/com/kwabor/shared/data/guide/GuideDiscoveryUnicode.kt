package com.kwabor.shared.data.guide

private const val HIGH_SURROGATE_START = 0xD800
private const val HIGH_SURROGATE_END = 0xDBFF
private const val LOW_SURROGATE_START = 0xDC00
private const val LOW_SURROGATE_END = 0xDFFF

internal fun String.hasMoreThanCodePoints(limit: Int): Boolean = codePointCount() > limit

private fun String.codePointCount(): Int {
    var index = 0
    var count = 0
    while (index < length) {
        count += 1
        val current = this[index]
        val consumesPair = current.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()
        index += if (consumesPair) 2 else 1
    }
    return count
}

private fun Char.isHighSurrogate(): Boolean = code in HIGH_SURROGATE_START..HIGH_SURROGATE_END

private fun Char.isLowSurrogate(): Boolean = code in LOW_SURROGATE_START..LOW_SURROGATE_END
