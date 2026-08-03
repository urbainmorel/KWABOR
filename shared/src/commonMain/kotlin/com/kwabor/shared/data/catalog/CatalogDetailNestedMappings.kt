package com.kwabor.shared.data.catalog

import com.kwabor.shared.domain.catalog.CatalogAmenity
import com.kwabor.shared.domain.catalog.CatalogCityReference
import com.kwabor.shared.domain.catalog.CatalogDayHours
import com.kwabor.shared.domain.catalog.CatalogEventTicketing
import com.kwabor.shared.domain.catalog.CatalogEventVenue
import com.kwabor.shared.domain.catalog.CatalogLocation
import com.kwabor.shared.domain.catalog.CatalogMedia
import com.kwabor.shared.domain.catalog.CatalogMediaKind
import com.kwabor.shared.domain.catalog.CatalogMetrics
import com.kwabor.shared.domain.catalog.CatalogOpeningDay
import com.kwabor.shared.domain.catalog.CatalogOpeningHours
import com.kwabor.shared.domain.catalog.CatalogOpeningPeriod
import com.kwabor.shared.domain.catalog.CatalogPrice
import com.kwabor.shared.domain.catalog.CatalogRoomType
import com.kwabor.shared.domain.catalog.CatalogSocialLink
import com.kwabor.shared.domain.catalog.CatalogSocialPlatform
import com.kwabor.shared.domain.catalog.CatalogTicketTier
import com.kwabor.shared.domain.catalog.GeoPoint
import com.kwabor.shared.domain.catalog.ListingContact
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.catalog.PriceUnit
import com.kwabor.shared.domain.catalog.Weekday
import com.kwabor.shared.domain.catalog.isWithinBeninBounds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val MINUTE_OF_DAY_MINIMUM = 0
private const val MINUTE_OF_DAY_MAXIMUM = 1_439
private const val MAXIMUM_RATING_AVERAGE = 5.0
private const val MINIMUM_PRICE_TIER = 1
private const val MAXIMUM_PRICE_TIER = 4

internal fun CatalogDetailContactDto.toDomain(): ListingContact = ListingContact(
    phone = phone?.requireCatalogText("contact.phone"),
    whatsapp = whatsapp?.requireCatalogText("contact.whatsapp"),
    externalUrl = externalUrl?.requireCatalogHttpsUrl("contact.external_url"),
    email = email?.requireCatalogText("contact.email"),
)

internal fun CatalogDetailPriceDto.toDomain(): CatalogPrice {
    val priceUnit = unit.toCatalogPriceUnit()
    if ((fromXof == null) != (priceUnit == PriceUnit.None)) {
        invalidCatalogDetail("price", toString())
    }
    if (tier?.let { it !in MINIMUM_PRICE_TIER..MAXIMUM_PRICE_TIER } == true) {
        invalidCatalogDetail("price", toString())
    }
    return CatalogPrice(
        from = fromXof?.toNonNegativeMoney("listings.price_from_xof"),
        unit = priceUnit,
        tier = tier,
    )
}

internal fun CatalogDetailMetricsDto.toDomain(): CatalogMetrics {
    if (ratingAverage?.let { !it.isFinite() || it !in 0.0..MAXIMUM_RATING_AVERAGE } == true) {
        invalidCatalogDetail("metrics.rating_average", ratingAverage.toString())
    }
    return CatalogMetrics(
        ratingAverage = ratingAverage,
        ratingCount = ratingCount.toNonNegativeCount("listings.rating_count"),
        viewsCount = viewsCount.toNonNegativeCount("listings.views_count"),
        likesCount = likesCount.toNonNegativeCount("listings.likes_count"),
    )
}

internal fun CatalogDetailMediaDto.toDomain(): CatalogMedia = CatalogMedia(
    kind = when (kind) {
        "image" -> CatalogMediaKind.Image
        "video" -> CatalogMediaKind.Video
        else -> invalidCatalogDetail("media.kind", kind)
    },
    url = url.requireCatalogHttpsUrl("media.url"),
    alt = alt.requireCatalogText("media.alt"),
    order = displayOrder.requireNonNegative("media.display_order"),
    isCover = isCover,
)

internal fun CatalogDetailAmenityDto.toDomain(): CatalogAmenity = CatalogAmenity(
    id = id.requireCatalogText("amenities.id"),
    labelKey = labelKey.requireCatalogText("amenities.label_key"),
    order = displayOrder.requireNonNegative("amenities.display_order"),
)

internal fun List<CatalogRoomTypeDto>.toDomainRoomTypes(): List<CatalogRoomType> {
    val roomTypes = requireCatalogNestedItemCount("detail.room_types")
        .sortedBy(CatalogRoomTypeDto::displayOrder)
        .map { room ->
            CatalogRoomType(
                name = room.name.requireCatalogShortText("detail.room_types.name"),
                price = room.priceXof.toNonNegativeMoney("room_types.price_xof"),
                order = room.displayOrder.requireCatalogNestedDisplayOrder("room_types.display_order"),
            )
        }
    val hasDuplicateOrder = roomTypes.map(CatalogRoomType::order).distinct().size != roomTypes.size
    val hasDuplicateName = roomTypes.map(CatalogRoomType::name).distinct().size != roomTypes.size
    if (hasDuplicateOrder || hasDuplicateName) {
        invalidCatalogDetail("detail.room_types", "duplicate")
    }
    return roomTypes
}

internal fun validateOrderedCatalogCollections(media: List<CatalogMedia>, amenities: List<CatalogAmenity>) {
    val covers = media.filter(CatalogMedia::isCover)
    if (media.isEmpty() || covers.size != 1 || covers.single().kind != CatalogMediaKind.Image) {
        invalidCatalogDetail("collections", "invalid official media or amenities")
    }
    if (media.map(CatalogMedia::order).distinct().size != media.size) {
        invalidCatalogDetail("collections", "invalid official media or amenities")
    }
    if (amenities.map(CatalogAmenity::order).distinct().size != amenities.size) {
        invalidCatalogDetail("collections", "invalid official media or amenities")
    }
    if (amenities.map(CatalogAmenity::id).distinct().size != amenities.size) {
        invalidCatalogDetail("collections", "invalid official media or amenities")
    }
}

internal fun CatalogDetailLocationDto.toDomain(fieldName: String): CatalogLocation {
    if ((latitude == null) != (longitude == null)) {
        invalidCatalogDetail(fieldName, "partial coordinates")
    }
    val point = latitude?.let { latitude ->
        GeoPoint(latitude = latitude, longitude = longitude ?: return@let null)
    }
    if (point != null && !point.isWithinBeninBounds) {
        invalidCatalogDetail(fieldName, "coordinates outside Benin")
    }
    return CatalogLocation(
        district = district?.requireCatalogText("$fieldName.district"),
        address = address?.requireCatalogText("$fieldName.address"),
        geoPoint = point,
    )
}

internal fun CatalogEventVenueDto.toDomain(): CatalogEventVenue {
    val venueType = type.toListingType()
    if (venueType == ListingType.Event) {
        invalidCatalogDetail("detail.venue_listing.type", type)
    }
    return CatalogEventVenue(
        id = id.requireCatalogText("detail.venue_listing.id"),
        type = venueType,
        subtype = subtype.requireCatalogText("detail.venue_listing.subtype"),
        name = name.requireCatalogText("detail.venue_listing.name"),
        city = CatalogCityReference(
            id = city.id.requireCatalogText("detail.venue_listing.city.id"),
            name = city.name.requireCatalogText("detail.venue_listing.city.name"),
        ),
        location = CatalogDetailLocationDto(
            address = address,
            latitude = latitude,
            longitude = longitude,
        ).toDomain("detail.venue_listing.location"),
    )
}

internal fun CatalogEventTicketingDto.toDomain(price: CatalogPrice): CatalogEventTicketing = when (type) {
    "gratuit" -> toFreeDomain(price)
    "payant" -> toPaidDomain(price)
    else -> invalidCatalogDetail("detail.ticketing.type", type)
}

private fun CatalogEventTicketingDto.toFreeDomain(price: CatalogPrice): CatalogEventTicketing.Free {
    if (tiers.isNotEmpty() || price.unit != PriceUnit.None || price.from != null) {
        invalidCatalogDetail("detail.ticketing.tiers", "free event with tiers")
    }
    return CatalogEventTicketing.Free(externalUrl = url?.requireCatalogHttpsUrl("detail.ticketing.url"))
}

private fun CatalogEventTicketingDto.toPaidDomain(price: CatalogPrice): CatalogEventTicketing.Paid {
    val ticketUrl = url?.requireCatalogHttpsUrl("detail.ticketing.url")
        ?: invalidCatalogDetail("detail.ticketing.url", "missing")
    if (tiers.isEmpty()) {
        invalidCatalogDetail("detail.ticketing.tiers", "missing")
    }
    val mappedTiers = tiers.requireCatalogNestedItemCount("detail.ticketing.tiers")
        .sortedBy(CatalogTicketTierDto::displayOrder)
        .map(CatalogTicketTierDto::toDomain)
    mappedTiers.requireValidTicketTiers(price)
    return CatalogEventTicketing.Paid(externalUrl = ticketUrl, tiers = mappedTiers)
}

private fun CatalogTicketTierDto.toDomain(): CatalogTicketTier {
    val mappedPrice = priceXof.toNonNegativeMoney("ticket_tiers.price_xof")
    if (mappedPrice.amount == 0L) {
        invalidCatalogDetail("ticket_tiers.price_xof", "0")
    }
    return CatalogTicketTier(
        label = label.requireCatalogShortText("ticket_tiers.label"),
        price = mappedPrice,
        order = displayOrder.requireCatalogNestedDisplayOrder("ticket_tiers.display_order"),
    )
}

private fun List<CatalogTicketTier>.requireValidTicketTiers(price: CatalogPrice) {
    val hasDuplicateOrder = map(CatalogTicketTier::order).distinct().size != size
    val hasDuplicateLabel = map(CatalogTicketTier::label).distinct().size != size
    if (hasDuplicateOrder || hasDuplicateLabel) {
        invalidCatalogDetail("detail.ticketing.tiers", "duplicate")
    }
    if (price.unit != PriceUnit.PerEntry || price.from?.amount != minOf { tier -> tier.price.amount }) {
        invalidCatalogDetail("detail.ticketing.price", price.toString())
    }
}

internal fun JsonObject.toOpeningHoursDomain(): CatalogOpeningHours {
    if (isEmpty()) {
        return CatalogOpeningHours.Unspecified
    }
    val expectedDays = linkedMapOf(
        "monday" to Weekday.Monday,
        "tuesday" to Weekday.Tuesday,
        "wednesday" to Weekday.Wednesday,
        "thursday" to Weekday.Thursday,
        "friday" to Weekday.Friday,
        "saturday" to Weekday.Saturday,
        "sunday" to Weekday.Sunday,
    )
    if (keys != expectedDays.keys) {
        invalidCatalogDetail("opening_hours", "invalid weekdays")
    }
    return CatalogOpeningHours.Weekly(
        days = expectedDays.map { (key, weekday) ->
            val day = getValue(key).decodeCatalogOpeningDay()
            CatalogOpeningDay(weekday = weekday, hours = day.toDomain(key))
        },
    )
}

private fun CatalogDetailOpeningDayDto.toDomain(day: String): CatalogDayHours = when (status) {
    "closed" -> {
        if (periods.isNotEmpty()) {
            invalidCatalogDetail("opening_hours.$day.periods", "closed day with periods")
        }
        CatalogDayHours.Closed
    }

    "open_24_hours" -> {
        if (periods.isNotEmpty()) {
            invalidCatalogDetail("opening_hours.$day.periods", "open day with periods")
        }
        CatalogDayHours.Open24Hours
    }

    "periods" -> {
        if (periods.isEmpty()) {
            invalidCatalogDetail("opening_hours.$day.periods", "missing")
        }
        val mappedPeriods = periods.mapIndexed { index, period -> period.toDomain(day, index) }
        mappedPeriods.zipWithNext().forEachIndexed { index, (current, next) ->
            if (current.closesNextDay || next.opensMinute < current.closesMinute) {
                invalidCatalogDetail("opening_hours.$day.periods[$index]", "overlap or overnight period not last")
            }
        }
        CatalogDayHours.Periods(periods = mappedPeriods)
    }

    else -> invalidCatalogDetail("opening_hours.$day.status", status)
}

private fun CatalogDetailOpeningPeriodDto.toDomain(day: String, index: Int): CatalogOpeningPeriod {
    if (opensMinute !in MINUTE_OF_DAY_MINIMUM..MINUTE_OF_DAY_MAXIMUM) {
        invalidCatalogDetail("opening_hours.$day.periods[$index]", toString())
    }
    if (closesMinute !in MINUTE_OF_DAY_MINIMUM..MINUTE_OF_DAY_MAXIMUM) {
        invalidCatalogDetail("opening_hours.$day.periods[$index]", toString())
    }
    if (!closesNextDay && closesMinute <= opensMinute) {
        invalidCatalogDetail("opening_hours.$day.periods[$index]", toString())
    }
    if (closesNextDay && closesMinute > opensMinute) {
        invalidCatalogDetail("opening_hours.$day.periods[$index]", toString())
    }
    return CatalogOpeningPeriod(
        opensMinute = opensMinute,
        closesMinute = closesMinute,
        closesNextDay = closesNextDay,
    )
}

internal fun JsonObject.toSocialLinksDomain(): List<CatalogSocialLink> {
    val platforms = linkedMapOf(
        "instagram" to CatalogSocialPlatform.Instagram,
        "facebook" to CatalogSocialPlatform.Facebook,
        "tiktok" to CatalogSocialPlatform.TikTok,
        "youtube" to CatalogSocialPlatform.YouTube,
        "x" to CatalogSocialPlatform.X,
        "linkedin" to CatalogSocialPlatform.LinkedIn,
    )
    if (!platforms.keys.containsAll(keys)) {
        invalidCatalogDetail("socials", "unsupported platform")
    }
    return platforms.mapNotNull { (key, platform) ->
        val value = this[key] ?: return@mapNotNull null
        if (!value.jsonPrimitive.isString) {
            invalidCatalogDetail("socials.$key", value.toString())
        }
        CatalogSocialLink(
            platform = platform,
            url = value.jsonPrimitive.content.requireCatalogHttpsUrl("socials.$key"),
        )
    }
}
