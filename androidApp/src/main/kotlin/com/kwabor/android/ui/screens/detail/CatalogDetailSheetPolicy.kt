package com.kwabor.android.ui.screens.detail

import androidx.compose.ui.unit.Dp
import com.kwabor.android.design.KwaborSizing
import com.kwabor.android.media.ListingMediaUrlPolicy
import com.kwabor.shared.presentation.detail.CatalogDetailContentUiModel
import com.kwabor.shared.presentation.detail.CatalogDetailMediaUiModel
import com.kwabor.shared.presentation.detail.CatalogDetailUiState
import java.text.BreakIterator
import java.util.Locale

internal const val DETAIL_MOBILE_SHEET_HEIGHT_FRACTION = 0.92f
internal const val DETAIL_TABLET_SHEET_HEIGHT_FRACTION = 0.85f
internal const val DETAIL_HERO_HEIGHT_FRACTION = 0.58f
internal const val DETAIL_DESCRIPTION_PREVIEW_CODE_POINTS = 150

internal fun detailSheetHeightFraction(maxWidthDp: Float): Float =
    if (maxWidthDp < KwaborSizing.ExploreTabletBreakpoint.value) {
        DETAIL_MOBILE_SHEET_HEIGHT_FRACTION
    } else {
        DETAIL_TABLET_SHEET_HEIGHT_FRACTION
    }

internal fun shouldOfferDescriptionExpansion(description: String): Boolean =
    description.codePointCount(0, description.length) > DETAIL_DESCRIPTION_PREVIEW_CODE_POINTS

internal fun detailHeroHeight(sheetHeight: Dp): Dp =
    maxOf(KwaborSizing.DetailHeroMinimumHeight, sheetHeight * DETAIL_HERO_HEIGHT_FRACTION)

internal fun detailCountLabel(count: Int, singular: String, plural: String): String =
    "$count ${if (count == 1) singular else plural}"

internal fun detailDescription(description: String, expanded: Boolean): String {
    if (expanded || !shouldOfferDescriptionExpansion(description)) {
        return description
    }
    val requestedEnd = description.offsetByCodePoints(0, DETAIL_DESCRIPTION_PREVIEW_CODE_POINTS)
    val previewEnd = description.safeCharacterBoundary(requestedEnd)
    return description.substring(0, previewEnd).trimEnd() + ELLIPSIS
}

private fun String.safeCharacterBoundary(requestedEnd: Int): Int {
    val minimumWordBoundary = offsetByCodePoints(0, DETAIL_DESCRIPTION_MINIMUM_PREVIEW_CODE_POINTS)
    val precedingWhitespace = (requestedEnd - 1 downTo minimumWordBoundary)
        .firstOrNull { index -> this[index].isWhitespace() }
    if (precedingWhitespace != null) return precedingWhitespace

    val iterator = BreakIterator.getCharacterInstance(Locale.FRENCH)
    iterator.setText(this)
    if (iterator.isBoundary(requestedEnd)) return requestedEnd
    return iterator.preceding(requestedEnd).takeIf { boundary -> boundary > 0 } ?: requestedEnd
}

internal data class VisibleCatalogDetailMedia(
    val sourceIndex: Int,
    val media: CatalogDetailMediaUiModel,
)

internal fun visibleOfficialImages(
    media: List<CatalogDetailMediaUiModel>,
    mediaUrlPolicy: ListingMediaUrlPolicy,
): List<VisibleCatalogDetailMedia> = media.mapIndexedNotNull { index, item ->
    item.takeIf { mediaUrlPolicy.safeUrlOrNull(item.url) != null }
        ?.let { safeItem -> VisibleCatalogDetailMedia(sourceIndex = index, media = safeItem) }
}

internal fun visibleMediaSelectionIndex(media: List<VisibleCatalogDetailMedia>, requestedSourceIndex: Int): Int =
    media.indexOfFirst { item -> item.sourceIndex == requestedSourceIndex }
        .takeIf { index -> index >= 0 }
        ?: 0

internal enum class CatalogDetailAnnouncement {
    Loading,
    NotFound,
    Offline,
    Failure,
    EventEnded,
}

internal fun CatalogDetailUiState.announcementOrNull(): CatalogDetailAnnouncement? = when (this) {
    CatalogDetailUiState.Closed -> null
    is CatalogDetailUiState.Loading -> CatalogDetailAnnouncement.Loading
    is CatalogDetailUiState.NotFound -> CatalogDetailAnnouncement.NotFound
    is CatalogDetailUiState.OfflineFailure -> CatalogDetailAnnouncement.Offline
    is CatalogDetailUiState.Failure -> CatalogDetailAnnouncement.Failure
    is CatalogDetailUiState.Content -> {
        val content = model.content
        if (content is CatalogDetailContentUiModel.Event && content.isEnded) {
            CatalogDetailAnnouncement.EventEnded
        } else {
            null
        }
    }
}

private const val ELLIPSIS = "…"
private const val DETAIL_DESCRIPTION_MINIMUM_PREVIEW_CODE_POINTS = 120
