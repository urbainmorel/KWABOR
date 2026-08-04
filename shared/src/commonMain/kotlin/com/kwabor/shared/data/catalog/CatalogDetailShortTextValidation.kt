package com.kwabor.shared.data.catalog

private const val MAXIMUM_CATALOG_TAG_COUNT = 10
private const val MAXIMUM_CATALOG_TAG_CODE_POINTS = 24
private const val MAXIMUM_CATALOG_NESTED_ITEM_COUNT = 20
private const val MAXIMUM_CATALOG_SHORT_TEXT_CODE_POINTS = 80
private const val HIGH_SURROGATE_START = 0xD800
private const val HIGH_SURROGATE_END = 0xDBFF
private const val LOW_SURROGATE_START = 0xDC00
private const val LOW_SURROGATE_END = 0xDFFF

internal fun List<String>.requireCatalogTags(fieldName: String): List<String> = requireCatalogTextValues(
    fieldName = fieldName,
    maximumCount = MAXIMUM_CATALOG_TAG_COUNT,
    maximumValueCodePoints = MAXIMUM_CATALOG_TAG_CODE_POINTS,
)

internal fun List<String>.requireCatalogTypedTextValues(fieldName: String): List<String> = requireCatalogTextValues(
    fieldName = fieldName,
    maximumCount = MAXIMUM_CATALOG_NESTED_ITEM_COUNT,
    maximumValueCodePoints = MAXIMUM_CATALOG_SHORT_TEXT_CODE_POINTS,
)

internal fun String.requireCatalogShortText(fieldName: String): String {
    requireCatalogText(fieldName)
    if (hasMoreThanCodePoints(MAXIMUM_CATALOG_SHORT_TEXT_CODE_POINTS) || any(Char::isISOControl)) {
        invalidCatalogDetail(fieldName, this)
    }
    return this
}

internal fun <T> List<T>.requireCatalogNestedItemCount(fieldName: String): List<T> {
    if (size > MAXIMUM_CATALOG_NESTED_ITEM_COUNT) {
        invalidCatalogDetail(fieldName, "too many values")
    }
    return this
}

internal fun Int.requireCatalogNestedDisplayOrder(fieldName: String): Int {
    if (this !in 0 until MAXIMUM_CATALOG_NESTED_ITEM_COUNT) {
        invalidCatalogDetail(fieldName, toString())
    }
    return this
}

private fun List<String>.requireCatalogTextValues(
    fieldName: String,
    maximumCount: Int,
    maximumValueCodePoints: Int,
): List<String> {
    requireCatalogNestedItemCount(fieldName)
    if (size > maximumCount) {
        invalidCatalogDetail(fieldName, "too many values")
    }
    forEachIndexed { index, value ->
        value.requireCatalogText("$fieldName[$index]")
        if (value.hasMoreThanCodePoints(maximumValueCodePoints) || value.any(Char::isISOControl)) {
            invalidCatalogDetail("$fieldName[$index]", value)
        }
    }
    if (distinct().size != size) {
        invalidCatalogDetail(fieldName, "duplicate")
    }
    return toList()
}

private fun String.hasMoreThanCodePoints(limit: Int): Boolean {
    var index = 0
    var count = 0
    while (index < length) {
        count += 1
        if (count > limit) return true
        val current = this[index].code
        val nextIsLowSurrogate = index + 1 < length && this[index + 1].code.isLowSurrogateCode()
        index += if (current.isHighSurrogateCode() && nextIsLowSurrogate) 2 else 1
    }
    return false
}

private fun Int.isHighSurrogateCode(): Boolean = this >= HIGH_SURROGATE_START && this <= HIGH_SURROGATE_END

private fun Int.isLowSurrogateCode(): Boolean = this >= LOW_SURROGATE_START && this <= LOW_SURROGATE_END
