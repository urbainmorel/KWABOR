package com.kwabor.shared.presentation.explore

import com.kwabor.shared.i18n.ExploreDateStrings
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

internal fun Long.toExploreDateLabel(strings: ExploreDateStrings): String {
    val localDate = Instant.fromEpochMilliseconds(this)
        .toLocalDateTime(EXPLORE_BENIN_TIME_ZONE)
        .date
        .toString()
    val day = localDate.substring(ISO_DAY_START_INDEX, ISO_DAY_END_INDEX).toInt()
    val monthIndex = localDate.substring(ISO_MONTH_START_INDEX, ISO_MONTH_END_INDEX).toInt() - 1
    return "$day ${strings.monthNames[monthIndex]} ${strings.forwardIndicator}"
}

private val EXPLORE_BENIN_TIME_ZONE = TimeZone.of("Africa/Porto-Novo")
private const val ISO_MONTH_START_INDEX = 5
private const val ISO_MONTH_END_INDEX = 7
private const val ISO_DAY_START_INDEX = 8
private const val ISO_DAY_END_INDEX = 10
