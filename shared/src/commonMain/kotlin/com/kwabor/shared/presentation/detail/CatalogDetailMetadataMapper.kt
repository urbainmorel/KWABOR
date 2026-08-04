package com.kwabor.shared.presentation.detail

import com.kwabor.shared.domain.catalog.CatalogDetail
import com.kwabor.shared.domain.catalog.CatalogDetailCommon
import com.kwabor.shared.domain.catalog.CatalogEventVenue
import com.kwabor.shared.domain.catalog.ListingContact
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

internal fun CatalogDetail.toDetailDirectionsUiModel(): CatalogDetailDirectionsUiModel? = when (this) {
    is CatalogDetail.Place -> common.toDirectionsUiModel()
    is CatalogDetail.Establishment -> common.toDirectionsUiModel()
    is CatalogDetail.Event -> venue?.toDirectionsUiModel() ?: common.toDirectionsUiModel()
}

internal fun CatalogDetail.toDetailContactUiModel(): CatalogDetailContactUiModel? = when (this) {
    is CatalogDetail.Place -> null
    is CatalogDetail.Establishment.Lodging,
    is CatalogDetail.Establishment.Food,
    is CatalogDetail.Establishment.Nightlife,
    is CatalogDetail.Establishment.Guide,
    -> common.contact.toUiModel()
    is CatalogDetail.Event -> null
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

private fun CatalogDetailCommon.toDirectionsUiModel(): CatalogDetailDirectionsUiModel? =
    location.geoPoint?.let { point ->
        CatalogDetailDirectionsUiModel(
            latitude = point.latitude,
            longitude = point.longitude,
            label = name,
        )
    }

private fun CatalogEventVenue.toDirectionsUiModel(): CatalogDetailDirectionsUiModel? = location.geoPoint?.let { point ->
    CatalogDetailDirectionsUiModel(
        latitude = point.latitude,
        longitude = point.longitude,
        label = name,
    )
}

private fun ListingContact.toUiModel(): CatalogDetailContactUiModel? {
    if (listOfNotNull(phone, whatsapp, externalUrl, email).isEmpty()) {
        return null
    }
    return CatalogDetailContactUiModel(
        phoneNumber = phone,
        whatsappNumber = whatsapp,
        websiteUrl = externalUrl,
        emailAddress = email,
    )
}
