package com.kwabor.shared.presentation.detail

import com.kwabor.shared.domain.catalog.CatalogDetail
import com.kwabor.shared.domain.catalog.CatalogDetailCommon
import com.kwabor.shared.domain.catalog.CatalogEventVenue
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.i18n.CatalogDetailStrings

internal fun CatalogDetailCommon.toDetailPriceUiModel(strings: CatalogDetailStrings): CatalogDetailPriceUiModel =
    CatalogDetailPriceUiModel(
        amount = price.from,
        prefixLabel = strings.fromPrice.takeIf { price.from != null && type == ListingType.Establishment },
        unitLabel = price.unit.toDetailLabel(strings),
    )

internal fun CatalogDetail.toDetailLocationUiModel(): CatalogDetailLocationUiModel = when (this) {
    is CatalogDetail.Place -> common.toLocationUiModel()
    is CatalogDetail.Establishment -> common.toLocationUiModel()
    is CatalogDetail.Event -> venue?.toLocationUiModel() ?: common.toLocationUiModel()
}

internal fun CatalogDetail.toDetailContextLabel(strings: CatalogDetailStrings, nowEpochMilliseconds: Long): String =
    buildList {
        add(common.city.name)
        add(common.category.labelKey.toCatalogLabel(strings))
        if (this@toDetailContextLabel is CatalogDetail.Event) {
            add(startsAtEpochMilliseconds.toBeninDateLabel())
            if ((endsAtEpochMilliseconds ?: startsAtEpochMilliseconds) <= nowEpochMilliseconds) {
                add(strings.eventEnded)
            }
        }
    }.joinToString(separator = " · ")

private fun CatalogDetailCommon.toLocationUiModel(): CatalogDetailLocationUiModel = CatalogDetailLocationUiModel(
    cityLabel = city.name,
    districtLabel = location.district,
    addressLabel = location.address,
    latitude = location.geoPoint?.latitude,
    longitude = location.geoPoint?.longitude,
)

private fun CatalogEventVenue.toLocationUiModel(): CatalogDetailLocationUiModel = CatalogDetailLocationUiModel(
    cityLabel = city.name,
    districtLabel = location.district,
    addressLabel = location.address,
    latitude = location.geoPoint?.latitude,
    longitude = location.geoPoint?.longitude,
)
