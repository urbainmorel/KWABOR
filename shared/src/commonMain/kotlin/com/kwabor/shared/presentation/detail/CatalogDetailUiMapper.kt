package com.kwabor.shared.presentation.detail

import com.kwabor.shared.domain.catalog.CatalogDetail
import com.kwabor.shared.domain.catalog.CatalogDetailCommon
import com.kwabor.shared.domain.catalog.CatalogMediaKind
import com.kwabor.shared.domain.catalog.CatalogMetrics
import com.kwabor.shared.i18n.KwaborStrings

internal fun CatalogDetail.toCatalogDetailUiModel(
    strings: KwaborStrings,
    nowEpochMilliseconds: Long,
): CatalogDetailUiModel {
    val detailStrings = strings.detail
    val commonModel = common
    return CatalogDetailUiModel(
        id = commonModel.id,
        title = commonModel.name,
        contextLabel = toDetailContextLabel(detailStrings, nowEpochMilliseconds),
        description = commonModel.description,
        verified = commonModel.verified,
        isClaimable = commonModel.isClaimable,
        media = commonModel.officialImageUiModels(),
        metrics = commonModel.metrics.toUiModel(),
        price = commonModel.toDetailPriceUiModel(detailStrings),
        openingStatusLabel = commonModel.openingHours.toCurrentStatusLabel(nowEpochMilliseconds, detailStrings),
        openingHours = commonModel.openingHours.toDetailOpeningDayUiModels(detailStrings),
        amenities = commonModel.amenities
            .map { amenity -> amenity.labelKey.toCatalogLabel(detailStrings) }
            .distinct(),
        location = toDetailLocationUiModel(),
        directions = toDetailDirectionsUiModel(),
        contact = toDetailContactUiModel(),
        tags = commonModel.tags.map(String::toDisplayWords).distinct(),
        content = toCatalogDetailVariantUiModel(detailStrings, nowEpochMilliseconds),
    )
}

private fun CatalogDetailCommon.officialImageUiModels(): List<CatalogDetailMediaUiModel> = media
    .asSequence()
    .filter { item -> item.kind == CatalogMediaKind.Image }
    .map { item ->
        CatalogDetailMediaUiModel(
            url = item.url,
            alt = item.alt,
            isCover = item.isCover,
        )
    }
    .toList()

private fun CatalogMetrics.toUiModel(): CatalogDetailMetricsUiModel = CatalogDetailMetricsUiModel(
    ratingLabel = ratingAverage?.toRatingLabel(),
    ratingCount = ratingCount,
    viewsCount = viewsCount,
    likesCount = likesCount,
)
