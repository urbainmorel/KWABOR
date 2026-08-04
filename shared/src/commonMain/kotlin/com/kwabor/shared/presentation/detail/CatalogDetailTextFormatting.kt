package com.kwabor.shared.presentation.detail

import com.kwabor.shared.domain.catalog.PriceUnit
import com.kwabor.shared.i18n.CatalogDetailStrings
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Instant

internal val DETAIL_BENIN_TIME_ZONE: TimeZone = TimeZone.of("Africa/Porto-Novo")

internal fun PriceUnit.toDetailLabel(strings: CatalogDetailStrings): String? = when (this) {
    PriceUnit.PerNight -> strings.perNight
    PriceUnit.PerPerson -> strings.perPerson
    PriceUnit.Consumption -> strings.perConsumption
    PriceUnit.PerEntry -> strings.perEntry
    PriceUnit.None -> null
}

internal fun String.toCatalogLabel(strings: CatalogDetailStrings): String = knownCategoryLabelOrNull(strings)
    ?: knownAmenityLabelOrNull(strings)
    ?: substringAfterLast('.').toDisplayWords()

internal fun String.toLanguageLabel(): String = when (lowercase()) {
    "fr", "fra", "francais", "français" -> "Français"
    "fon" -> "Fon"
    "en", "eng", "anglais" -> "Anglais"
    "pt", "por", "portugais" -> "Portugais"
    "es", "spa", "espagnol" -> "Espagnol"
    "de", "deu", "allemand" -> "Allemand"
    "it", "ita", "italien" -> "Italien"
    else -> toDisplayWords()
}

internal fun String.toDisplayWords(): String {
    val words = trim()
        .substringAfterLast('.')
        .split('-', '_', ' ')
        .filter(String::isNotBlank)
    return words.joinToString(separator = " ") { word -> word.lowercase() }
        .replaceFirstChar { character -> character.uppercase() }
}

internal fun Double.toRatingLabel(): String {
    val tenths = (this * RATING_SCALE).roundToInt()
    val whole = tenths / RATING_SCALE
    val decimal = tenths % RATING_SCALE
    return if (decimal == 0) whole.toString() else "$whole,$decimal"
}

internal fun Long.toBeninDateLabel(): String = toBeninDateTimeParts().first

internal fun Long.toBeninDateTimeLabel(): String {
    val (date, time) = toBeninDateTimeParts()
    return "$date · $time"
}

private fun String.knownCategoryLabelOrNull(strings: CatalogDetailStrings): String? = when (this) {
    "category.heritage.historique", "heritage-historique" -> strings.history
    "category.heritage.nature", "heritage-nature" -> strings.nature
    "category.commercial.marche", "commercial-marche" -> strings.market
    "category.commercial.restaurant", "commercial-restaurant" -> strings.restaurant
    "category.commercial.hotel", "commercial-hotel" -> strings.hotel
    "category.commercial.guide", "guide-touristique" -> strings.touristGuide
    "category.event.culture", "event-culture" -> strings.culture
    else -> null
}

private fun String.knownAmenityLabelOrNull(strings: CatalogDetailStrings): String? = when (this) {
    "amenity.parking" -> strings.parking
    "amenity.wifi" -> strings.wifi
    "amenity.accessible_pmr" -> strings.accessiblePmr
    else -> null
}

private fun Long.toBeninDateTimeParts(): Pair<String, String> {
    val local = Instant.fromEpochMilliseconds(this).toLocalDateTime(DETAIL_BENIN_TIME_ZONE)
    val isoDate = local.date.toString()
    val localizedDate = buildString {
        append(isoDate.substring(ISO_DAY_START_INDEX, ISO_DAY_END_INDEX))
        append('/')
        append(isoDate.substring(ISO_MONTH_START_INDEX, ISO_MONTH_END_INDEX))
        append('/')
        append(isoDate.substring(ISO_YEAR_START_INDEX, ISO_YEAR_END_INDEX))
    }
    val time = "${local.time.hour.toTwoDigits()}:${local.time.minute.toTwoDigits()}"
    return localizedDate to time
}

private fun Int.toTwoDigits(): String = toString().padStart(length = 2, padChar = '0')

private const val RATING_SCALE = 10
private const val ISO_YEAR_START_INDEX = 0
private const val ISO_YEAR_END_INDEX = 4
private const val ISO_MONTH_START_INDEX = 5
private const val ISO_MONTH_END_INDEX = 7
private const val ISO_DAY_START_INDEX = 8
private const val ISO_DAY_END_INDEX = 10
