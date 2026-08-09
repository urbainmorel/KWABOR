package com.kwabor.android.ui.components

internal fun ListingCardState.imageAccessibilityDescription(exposeImageSemantics: Boolean): String? {
    if (!exposeImageSemantics) return null
    return coverImageAlt?.takeIf(String::isNotBlank)
}
