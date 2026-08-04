package com.kwabor.shared.presentation.detail

import com.kwabor.shared.domain.catalog.CatalogDayHours
import com.kwabor.shared.domain.catalog.CatalogOpeningDay
import com.kwabor.shared.domain.catalog.CatalogOpeningHours
import com.kwabor.shared.domain.catalog.CatalogOpeningPeriod
import com.kwabor.shared.domain.catalog.Weekday
import com.kwabor.shared.i18n.CatalogDetailStrings
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

internal fun CatalogOpeningHours.toDetailOpeningDayUiModels(
    strings: CatalogDetailStrings,
): List<CatalogDetailOpeningDayUiModel> = when (this) {
    CatalogOpeningHours.Unspecified -> emptyList()
    is CatalogOpeningHours.Weekly -> days.map { day -> day.toUiModel(strings) }
}

internal fun CatalogOpeningHours.toCurrentStatusLabel(
    nowEpochMilliseconds: Long,
    strings: CatalogDetailStrings,
): String? = when (this) {
    CatalogOpeningHours.Unspecified -> null
    is CatalogOpeningHours.Weekly -> if (isOpenAt(nowEpochMilliseconds)) strings.openNow else strings.closedNow
}

internal fun Int.toDetailClockLabel(): String {
    val hour = this / MINUTES_PER_HOUR
    val minute = this % MINUTES_PER_HOUR
    return "${hour.toTwoDigits()}:${minute.toTwoDigits()}"
}

private fun CatalogOpeningDay.toUiModel(strings: CatalogDetailStrings): CatalogDetailOpeningDayUiModel =
    CatalogDetailOpeningDayUiModel(
        dayLabel = weekday.toLabel(strings),
        hoursLabel = hours.toLabel(strings),
    )

private fun CatalogDayHours.toLabel(strings: CatalogDetailStrings): String = when (this) {
    CatalogDayHours.Closed -> strings.closed
    CatalogDayHours.Open24Hours -> strings.open24Hours
    is CatalogDayHours.Periods -> periods.joinToString(separator = ", ") { period -> period.toLabel(strings) }
}

private fun CatalogOpeningPeriod.toLabel(strings: CatalogDetailStrings): String = buildString {
    append(opensMinute.toDetailClockLabel())
    append('–')
    append(closesMinute.toDetailClockLabel())
    if (closesNextDay) {
        append(" (${strings.nextDay})")
    }
}

private fun CatalogOpeningHours.Weekly.isOpenAt(nowEpochMilliseconds: Long): Boolean {
    val local = Instant.fromEpochMilliseconds(nowEpochMilliseconds).toLocalDateTime(DETAIL_BENIN_TIME_ZONE)
    val currentWeekday = local.date.dayOfWeek.name.toCatalogWeekdayOrNull() ?: return false
    val minute = local.time.hour * MINUTES_PER_HOUR + local.time.minute
    val todayHours = days.firstOrNull { day -> day.weekday == currentWeekday }?.hours
    if (todayHours == CatalogDayHours.Open24Hours) return true
    if (todayHours is CatalogDayHours.Periods && todayHours.periods.any { period -> period.isOpenToday(minute) }) {
        return true
    }
    val previousWeekday = Weekday.entries[(currentWeekday.ordinal + Weekday.entries.size - 1) % Weekday.entries.size]
    val previousHours = days.firstOrNull { day -> day.weekday == previousWeekday }?.hours
    return previousHours is CatalogDayHours.Periods &&
        previousHours.periods.any { period -> period.closesNextDay && minute < period.closesMinute }
}

private fun CatalogOpeningPeriod.isOpenToday(minute: Int): Boolean = if (closesNextDay) {
    minute >= opensMinute
} else {
    minute in opensMinute until closesMinute
}

private fun String.toCatalogWeekdayOrNull(): Weekday? = when (this) {
    "MONDAY" -> Weekday.Monday
    "TUESDAY" -> Weekday.Tuesday
    "WEDNESDAY" -> Weekday.Wednesday
    "THURSDAY" -> Weekday.Thursday
    "FRIDAY" -> Weekday.Friday
    "SATURDAY" -> Weekday.Saturday
    "SUNDAY" -> Weekday.Sunday
    else -> null
}

private fun Weekday.toLabel(strings: CatalogDetailStrings): String = when (this) {
    Weekday.Monday -> strings.monday
    Weekday.Tuesday -> strings.tuesday
    Weekday.Wednesday -> strings.wednesday
    Weekday.Thursday -> strings.thursday
    Weekday.Friday -> strings.friday
    Weekday.Saturday -> strings.saturday
    Weekday.Sunday -> strings.sunday
}

private fun Int.toTwoDigits(): String = toString().padStart(length = 2, padChar = '0')

private const val MINUTES_PER_HOUR = 60
