package com.kwabor.shared.presentation.detail

import com.kwabor.shared.domain.catalog.CatalogDetail
import com.kwabor.shared.domain.catalog.CatalogEventTicketing
import com.kwabor.shared.i18n.CatalogDetailStrings

internal fun CatalogDetail.toCatalogDetailVariantUiModel(
    strings: CatalogDetailStrings,
    nowEpochMilliseconds: Long,
): CatalogDetailContentUiModel = when (this) {
    is CatalogDetail.Place -> toPlaceUiModel(strings)
    is CatalogDetail.Establishment.Lodging -> toLodgingUiModel(strings)
    is CatalogDetail.Establishment.Food -> toFoodUiModel(strings)
    is CatalogDetail.Establishment.Nightlife -> toNightlifeUiModel(strings)
    is CatalogDetail.Establishment.Guide -> toGuideUiModel(strings)
    is CatalogDetail.Event -> toEventUiModel(strings, nowEpochMilliseconds)
}

private fun CatalogDetail.Place.toPlaceUiModel(strings: CatalogDetailStrings): CatalogDetailContentUiModel.Place =
    CatalogDetailContentUiModel.Place(
        heading = strings.place,
        placeCategoryLabel = placeCategory.toCatalogLabel(strings),
        entryFee = entryFee.takeUnless { isFree },
        feeNote = feeNote,
    )

private fun CatalogDetail.Establishment.Lodging.toLodgingUiModel(
    strings: CatalogDetailStrings,
): CatalogDetailContentUiModel.Lodging = CatalogDetailContentUiModel.Lodging(
    heading = strings.lodging,
    facts = buildList {
        starRating?.let { rating -> add(CatalogDetailFactUiModel(strings.starRating, "$rating ★")) }
        roomCount?.let { count -> add(CatalogDetailFactUiModel(strings.roomCount, count.toString())) }
        checkInMinute?.let { minute -> add(CatalogDetailFactUiModel(strings.checkIn, minute.toDetailClockLabel())) }
        checkOutMinute?.let { minute -> add(CatalogDetailFactUiModel(strings.checkOut, minute.toDetailClockLabel())) }
    },
    roomTypes = roomTypes.map { room -> CatalogDetailPricedItemUiModel(room.name, room.price) },
)

private fun CatalogDetail.Establishment.Food.toFoodUiModel(
    strings: CatalogDetailStrings,
): CatalogDetailContentUiModel.Food = CatalogDetailContentUiModel.Food(
    heading = strings.food,
    cuisines = cuisines.map(String::toDisplayWords).distinct(),
    meals = meals.map(String::toDisplayWords).distinct(),
    reservationLabel = if (acceptsReservations) strings.reservationsAccepted else strings.reservationsNotAccepted,
    menuUrl = menuUrl,
)

private fun CatalogDetail.Establishment.Nightlife.toNightlifeUiModel(
    strings: CatalogDetailStrings,
): CatalogDetailContentUiModel.Nightlife = CatalogDetailContentUiModel.Nightlife(
    heading = strings.nightlife,
    facts = buildList {
        add(CatalogDetailFactUiModel(strings.venueKind, venueKind.toDisplayWords()))
        minimumAge?.let { age -> add(CatalogDetailFactUiModel(strings.minimumAge, age.toString())) }
    },
)

private fun CatalogDetail.Establishment.Guide.toGuideUiModel(
    strings: CatalogDetailStrings,
): CatalogDetailContentUiModel.Guide = CatalogDetailContentUiModel.Guide(
    heading = strings.guide,
    languages = languages.map { language -> language.toLanguageLabel() }.distinct(),
    zones = zones.distinctBy { zone -> zone.lowercase() },
    specialties = specialties.map(String::toDisplayWords).distinct(),
    facts = buildList {
        accreditation?.let { value -> add(CatalogDetailFactUiModel(strings.accreditation, value)) }
        experienceYears?.let { years ->
            add(CatalogDetailFactUiModel(strings.experience, years.toCountLabel(strings.year, strings.years)))
        }
    },
    indicativePrice = indicativePrice,
)

private fun CatalogDetail.Event.toEventUiModel(
    strings: CatalogDetailStrings,
    nowEpochMilliseconds: Long,
): CatalogDetailContentUiModel.Event = CatalogDetailContentUiModel.Event(
    heading = strings.event,
    startsAtLabel = startsAtEpochMilliseconds.toBeninDateTimeLabel(),
    endsAtLabel = endsAtEpochMilliseconds?.toBeninDateTimeLabel(),
    venueLabel = eventVenueLabel(strings),
    organizerLabel = listOf(organizer.name, organizer.contact).joinToString(separator = " · "),
    capacityLabel = capacity?.let { value -> value.toCountLabel(strings.person, strings.people) },
    ticketing = ticketing.toUiModel(),
    isEnded = (endsAtEpochMilliseconds ?: startsAtEpochMilliseconds) <= nowEpochMilliseconds,
)

private fun CatalogDetail.Event.eventVenueLabel(strings: CatalogDetailStrings): String {
    val linkedVenue = venue
    if (linkedVenue != null) {
        return listOf(linkedVenue.name, linkedVenue.city.name).joinToString(separator = " · ")
    }
    return listOfNotNull(common.location.address, common.location.district, common.city.name)
        .distinct()
        .joinToString(separator = " · ")
        .ifBlank { strings.addressUnavailable }
}

private fun CatalogEventTicketing.toUiModel(): CatalogDetailTicketingUiModel = when (this) {
    is CatalogEventTicketing.Free -> CatalogDetailTicketingUiModel.Free(externalUrl = externalUrl)
    is CatalogEventTicketing.Paid -> CatalogDetailTicketingUiModel.Paid(
        externalUrl = externalUrl,
        tiers = tiers.map { tier -> CatalogDetailPricedItemUiModel(tier.label, tier.price) },
    )
}

private fun Int.toCountLabel(singular: String, plural: String): String = "$this ${if (this == 1) singular else plural}"
