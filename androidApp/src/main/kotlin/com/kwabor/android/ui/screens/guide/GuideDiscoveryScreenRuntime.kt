package com.kwabor.android.ui.screens.guide

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import com.kwabor.android.design.KwaborSizing
import com.kwabor.android.ui.components.PriceTagMode
import com.kwabor.android.ui.components.PriceTagOptions
import com.kwabor.android.ui.components.formatPriceTag
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.guide.GuideDiscoveryUiState
import com.kwabor.shared.presentation.guide.GuideSummaryUiModel

internal fun guideDiscoveryLiveRegionStatus(state: GuideDiscoveryUiState, strings: KwaborStrings): String? = when {
    state.errorMessage != null -> listOf(strings.errorStateTitle, state.errorMessage)
        .joinToString(separator = ACCESSIBILITY_SENTENCE_SEPARATOR)
    state.appendErrorMessage != null -> listOf(strings.errorStateTitle, state.appendErrorMessage)
        .joinToString(separator = ACCESSIBILITY_SENTENCE_SEPARATOR)
    state.isLoading || state.isRefreshing || state.isAppending -> strings.loading
    else -> null
}

internal fun Modifier.guideDiscoveryLiveRegion(status: String?): Modifier = if (status == null) {
    this
} else {
    semantics {
        liveRegion = LiveRegionMode.Polite
        stateDescription = status
    }
}

internal fun LazyGridState.hasReachedGuidePaginationThreshold(): Boolean {
    val totalItemsCount = layoutInfo.totalItemsCount
    val lastVisibleItemIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return false
    return totalItemsCount > 0 &&
        lastVisibleItemIndex >= totalItemsCount - KwaborSizing.GUIDE_LOAD_MORE_THRESHOLD
}

internal fun guideDiscoveryColumnCount(maxWidth: Dp): Int = if (maxWidth < KwaborSizing.ExploreTabletBreakpoint) {
    KwaborSizing.GUIDE_MOBILE_GRID_COLUMNS
} else {
    KwaborSizing.GUIDE_TABLET_GRID_COLUMNS
}

internal fun guideCardAccessibilityDescription(model: GuideSummaryUiModel, strings: KwaborStrings): String {
    val guideStrings = strings.guideDiscovery
    val descriptions = buildList {
        add(guideStrings.openGuideLabel)
        add(model.title)
        add(model.baseCityLabel)
        add(model.coverImageAlt)
        if (model.verified) add(strings.detail.verified)
        addAttribute(guideStrings.languagesLabel, model.languages)
        addAttribute(guideStrings.coveredCitiesLabel, model.coverageCities)
        addAttribute(guideStrings.specialtiesLabel, model.specialties)
        model.ratingLabel?.let { rating ->
            add(
                listOf(
                    strings.detail.rating,
                    rating,
                    strings.detail.ratingOutOfFive,
                    model.ratingCount.toString(),
                    if (model.ratingCount == 1) strings.detail.review else strings.detail.reviews,
                ).joinToString(separator = ACCESSIBILITY_WORD_SEPARATOR),
            )
        }
        add(
            listOf(
                guideStrings.indicativePriceLabel,
                formatPriceTag(
                    price = model.indicativePrice,
                    strings = strings,
                    options = PriceTagOptions(mode = PriceTagMode.Full),
                ),
            ).joinToString(separator = ACCESSIBILITY_WORD_SEPARATOR),
        )
    }
    return descriptions.joinToString(separator = ACCESSIBILITY_SENTENCE_SEPARATOR)
}

private fun MutableList<String>.addAttribute(label: String, values: List<String>) {
    if (values.isNotEmpty()) {
        add(
            listOf(label, values.joinToString(separator = ACCESSIBILITY_VALUE_SEPARATOR))
                .joinToString(separator = ACCESSIBILITY_WORD_SEPARATOR),
        )
    }
}

private const val ACCESSIBILITY_WORD_SEPARATOR = " "
private const val ACCESSIBILITY_VALUE_SEPARATOR = ", "
private const val ACCESSIBILITY_SENTENCE_SEPARATOR = ". "
