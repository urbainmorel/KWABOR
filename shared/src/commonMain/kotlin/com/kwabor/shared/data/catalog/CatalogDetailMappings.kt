package com.kwabor.shared.data.catalog

import com.kwabor.shared.domain.catalog.CatalogCategoryReference
import com.kwabor.shared.domain.catalog.CatalogCityReference
import com.kwabor.shared.domain.catalog.CatalogDetail
import com.kwabor.shared.domain.catalog.CatalogDetailCommon
import com.kwabor.shared.domain.catalog.CatalogEventOrganizer
import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.catalog.PriceUnit
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonPrimitive

private const val CATALOG_DETAIL_SCHEMA_VERSION = 1

internal fun CatalogDetailPayloadDto.toDomain(): CatalogDetail = try {
    toDomainUnchecked()
} catch (exception: CatalogDataException) {
    throw exception
} catch (exception: SerializationException) {
    throw CatalogDataException.Unexpected(exception)
} catch (exception: IllegalArgumentException) {
    throw CatalogDataException.Unexpected(exception)
} catch (exception: IllegalStateException) {
    throw CatalogDataException.Unexpected(exception)
} catch (exception: NoSuchElementException) {
    throw CatalogDataException.Unexpected(exception)
}

private fun CatalogDetailPayloadDto.toDomainUnchecked(): CatalogDetail {
    if (schemaVersion != CATALOG_DETAIL_SCHEMA_VERSION) {
        invalidCatalogDetail("schema_version", schemaVersion.toString())
    }

    val common = toCommonDomain()
    return when (val variant = detail.getValue("variant").jsonPrimitive.content) {
        "place" -> toPlaceDomain(common)
        "lodging" -> toLodgingDomain(common)
        "food" -> toFoodDomain(common)
        "nightlife" -> toNightlifeDomain(common)
        "guide" -> toGuideDomain(common)
        "event" -> toEventDomain(common)
        else -> invalidCatalogDetail("detail.variant", variant)
    }
}

private fun CatalogDetailPayloadDto.toCommonDomain(): CatalogDetailCommon {
    val listingType = type.toListingType()
    val catalogMedia = media.sortedBy(CatalogDetailMediaDto::displayOrder).map(CatalogDetailMediaDto::toDomain)
    val catalogAmenities = amenities.sortedBy(CatalogDetailAmenityDto::displayOrder)
        .map(CatalogDetailAmenityDto::toDomain)
    validateOrderedCatalogCollections(catalogMedia, catalogAmenities)

    return CatalogDetailCommon(
        id = id.requireCatalogText("id"),
        type = listingType,
        subtype = subtype.requireCatalogText("subtype"),
        listingClass = listingClass.toListingClass(),
        name = name.requireCatalogText("name"),
        slug = slug.requireCatalogText("slug"),
        description = description.requireCatalogText("description"),
        contentLocale = contentLang.toCatalogLocale(),
        city = CatalogCityReference(
            id = city.id.requireCatalogText("city.id"),
            name = city.name.requireCatalogText("city.name"),
        ),
        category = CatalogCategoryReference(
            id = category.id.requireCatalogText("category.id"),
            labelKey = category.labelKey.requireCatalogText("category.label_key"),
        ),
        location = location.toDomain("location"),
        price = price.toDomain(),
        openingHours = openingHours.toOpeningHoursDomain(),
        contact = contact.toDomain(),
        socialLinks = socials.toSocialLinksDomain(),
        tags = tags.requireCatalogTextValues("tags"),
        verified = verified,
        isClaimable = isClaimable,
        metrics = metrics.toDomain(),
        publishedAtEpochMilliseconds = publishedAt.toEpochMilliseconds(),
        media = catalogMedia,
        amenities = catalogAmenities,
    )
}

private fun CatalogDetailPayloadDto.toPlaceDomain(common: CatalogDetailCommon): CatalogDetail.Place {
    common.requireValidPlaceCommon()
    val payload = detail.decodeCatalogPlaceDetail()
    payload.requireValidPlaceIdentity(common)
    val entryFee = payload.entryFeeXof?.toNonNegativeMoney("place_details.entry_fee_xof")
    payload.requireValidPlacePrice(common.price, entryFee)

    return CatalogDetail.Place(
        common = common,
        placeCategory = payload.placeCategory.requireCatalogText("detail.place_category"),
        isFree = payload.isFree,
        entryFee = entryFee,
        feeNote = payload.feeNote?.requireCatalogText("detail.fee_note"),
    )
}

private fun CatalogDetailPayloadDto.toLodgingDomain(common: CatalogDetailCommon): CatalogDetail.Establishment.Lodging {
    common.requireEstablishmentVariant()
    val payload = detail.decodeCatalogLodgingDetail()
    payload.requireValidLodging(common)
    val roomTypes = payload.roomTypes.toDomainRoomTypes()
    common.price.requireValidLodgingPrice(roomTypes)

    return CatalogDetail.Establishment.Lodging(
        common = common,
        starRating = payload.starRating,
        roomCount = payload.roomCount,
        checkInMinute = payload.checkinTime?.toMinuteOfDay("detail.checkin_time"),
        checkOutMinute = payload.checkoutTime?.toMinuteOfDay("detail.checkout_time"),
        roomTypes = roomTypes,
    )
}

private fun CatalogDetailPayloadDto.toFoodDomain(common: CatalogDetailCommon): CatalogDetail.Establishment.Food {
    common.requireEstablishmentVariant()
    val payload = detail.decodeCatalogFoodDetail()
    if (payload.variant != "food" || payload.cuisines.isEmpty()) {
        invalidCatalogDetail("detail.variant", payload.variant)
    }
    common.price.requirePresentUnit(PriceUnit.PerPerson, "detail.food.price")

    return CatalogDetail.Establishment.Food(
        common = common,
        cuisines = payload.cuisines.requireCatalogTextValues("detail.cuisines"),
        meals = payload.meals.requireCatalogTextValues("detail.meals"),
        acceptsReservations = payload.reservation,
        menuUrl = payload.menuUrl?.requireCatalogHttpsUrl("detail.menu_url"),
    )
}

private fun CatalogDetailPayloadDto.toNightlifeDomain(
    common: CatalogDetailCommon,
): CatalogDetail.Establishment.Nightlife {
    common.requireEstablishmentVariant()
    val payload = detail.decodeCatalogNightlifeDetail()
    payload.requireValidNightlife(common)

    return CatalogDetail.Establishment.Nightlife(
        common = common,
        venueKind = payload.venueKind.requireCatalogText("detail.venue_kind"),
        minimumAge = payload.minimumAge,
    )
}

private fun CatalogDetailPayloadDto.toGuideDomain(common: CatalogDetailCommon): CatalogDetail.Establishment.Guide {
    common.requireEstablishmentVariant()
    val payload = detail.decodeCatalogGuideDetail()
    payload.requireValidGuide(common)
    val indicativePrice = payload.indicativePriceXof?.toNonNegativeMoney("guide_details.indicative_price_xof")

    return CatalogDetail.Establishment.Guide(
        common = common,
        languages = payload.languages.requireCatalogTextValues("detail.languages"),
        zones = payload.zones.requireCatalogTextValues("detail.zones"),
        specialties = payload.specialties.requireCatalogTextValues("detail.specialties"),
        indicativePrice = indicativePrice ?: invalidCatalogDetail("detail.guide.price", common.price.toString()),
        accreditation = payload.accreditation?.requireCatalogText("detail.accreditation"),
        experienceYears = payload.experienceYears,
    )
}

private fun CatalogDetailPayloadDto.toEventDomain(common: CatalogDetailCommon): CatalogDetail.Event {
    common.requireVariant(ListingType.Event)
    if (common.listingClass != ListingClass.Event) {
        invalidCatalogDetail("listing_class", common.listingClass.name)
    }
    val payload = detail.decodeCatalogEventDetail()
    if (payload.variant != "event" || payload.category != common.subtype || payload.capacity?.let { it <= 0 } == true) {
        invalidCatalogDetail("detail.event", payload.toString())
    }
    val startsAt = payload.startAt.toEpochMilliseconds()
    val endsAt = payload.endAt?.toEpochMilliseconds()
    if (endsAt != null && endsAt < startsAt) {
        invalidCatalogDetail("detail.end_at", payload.endAt)
    }
    val venue = payload.venueListing?.toDomain()
    if (venue == null && (common.location.address == null || common.location.geoPoint == null)) {
        invalidCatalogDetail("detail.event.location", "missing venue and coordinates")
    }

    return CatalogDetail.Event(
        common = common,
        category = payload.category.requireCatalogText("detail.category"),
        startsAtEpochMilliseconds = startsAt,
        endsAtEpochMilliseconds = endsAt,
        venue = venue,
        organizer = CatalogEventOrganizer(
            name = payload.organizer.name.requireCatalogText("detail.organizer.name"),
            contact = payload.organizer.contact.requireCatalogText("detail.organizer.contact"),
        ),
        ticketing = payload.ticketing.toDomain(common.price),
        capacity = payload.capacity,
    )
}
