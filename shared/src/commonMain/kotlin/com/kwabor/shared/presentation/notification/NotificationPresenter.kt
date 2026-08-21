package com.kwabor.shared.presentation.notification

import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.notification.NotificationContent
import com.kwabor.shared.domain.notification.NotificationInboxItem
import com.kwabor.shared.domain.notification.NotificationPreferenceFamily
import com.kwabor.shared.domain.notification.NotificationPreferences
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

class NotificationPresenter(
    private val clockProvider: ClockProvider,
    private val timeZone: TimeZone = TimeZone.of(BENIN_TIME_ZONE_ID),
) {
    fun present(
        items: List<NotificationInboxItem>,
        strings: NotificationStrings = frenchNotificationStrings,
    ): NotificationPresentationResult {
        val visibleItems = items.filter { item -> item.hiddenAtEpochMilliseconds == null }
        if (!visibleItems.hasSafePresentationPayload()) return NotificationPresentationResult.InvalidPayload
        val nowEpochMilliseconds = clockProvider.nowEpochMilliseconds()
        if (nowEpochMilliseconds < 0L) return NotificationPresentationResult.InvalidPayload
        val todayEpochDay = nowEpochMilliseconds.toLocalEpochDay(timeZone)
        val context =
            NotificationPresentationTimeContext(
                nowEpochMilliseconds = nowEpochMilliseconds,
                todayEpochDay = todayEpochDay,
                weekStartEpochDay = todayEpochDay.startOfWeekEpochDay(),
                timeZone = timeZone,
            )
        return NotificationPresentationResult.Content(visibleItems.toSections(context, strings))
    }

    fun presentPreferences(
        preferences: NotificationPreferences,
        strings: NotificationStrings = frenchNotificationStrings,
    ): List<NotificationPreferenceUiModel> =
        NotificationPreferenceFamily.entries.map { family ->
            NotificationPreferenceUiModel(
                family = family,
                title = family.title(strings),
                enabled = preferences.preferenceFor(family).enabled,
            )
        }
}

private data class NotificationPresentationTimeContext(
    val nowEpochMilliseconds: Long,
    val todayEpochDay: Long,
    val weekStartEpochDay: Long,
    val timeZone: TimeZone,
)

private fun List<NotificationInboxItem>.toSections(
    context: NotificationPresentationTimeContext,
    strings: NotificationStrings,
): List<NotificationSectionUiModel> {
    val presentedItems =
        sortedWith(
            compareByDescending<NotificationInboxItem>(NotificationInboxItem::sequence)
                .thenByDescending(NotificationInboxItem::createdAtEpochMilliseconds)
                .thenBy(NotificationInboxItem::id),
        ).map { item ->
            PresentedNotificationItem(
                group =
                    item.createdAtEpochMilliseconds.toTemporalGroup(
                        todayEpochDay = context.todayEpochDay,
                        weekStartEpochDay = context.weekStartEpochDay,
                        timeZone = context.timeZone,
                    ),
                item = item.toUiModel(context = context, strings = strings),
            )
        }
    return NotificationTemporalGroup.entries.mapNotNull { group ->
        val groupItems =
            presentedItems
                .filter { presented -> presented.group == group }
                .map(PresentedNotificationItem::item)
        groupItems.takeIf { items -> items.isNotEmpty() }?.let { nonEmptyItems ->
            NotificationSectionUiModel(
                group = group,
                title = group.title(strings),
                items = nonEmptyItems,
            )
        }
    }
}

private data class PresentedNotificationItem(
    val group: NotificationTemporalGroup,
    val item: NotificationItemUiModel,
)

private data class RenderedNotificationCopy(
    val title: String,
    val body: String,
)

private fun List<NotificationInboxItem>.hasSafePresentationPayload(): Boolean {
    if (any { item -> item.id.isBlank() || item.id != item.id.trim() || !item.content.hasAllowedTemplate() }) {
        return false
    }
    if (map(NotificationInboxItem::id).distinct().size != size) return false
    return map(NotificationInboxItem::sequence).distinct().size == size
}

private fun NotificationContent.hasAllowedTemplate(): Boolean =
    when (this) {
        is NotificationContent.Suggestion ->
            titleKey == SUGGESTION_TITLE_KEY && bodyKey == SUGGESTION_BODY_KEY
        is NotificationContent.Sponsored ->
            titleKey == SPONSORED_TITLE_KEY && bodyKey == SPONSORED_BODY_KEY
        is NotificationContent.NewListing ->
            titleKey == NEW_LISTING_TITLE_KEY && bodyKey == NEW_LISTING_BODY_KEY
        is NotificationContent.EventAlert ->
            titleKey == EVENT_ALERT_TITLE_KEY && bodyKey == EVENT_ALERT_BODY_KEY
    }

private fun NotificationInboxItem.toUiModel(
    context: NotificationPresentationTimeContext,
    strings: NotificationStrings,
): NotificationItemUiModel {
    val renderedCopy = content.render(strings.templates)
    return NotificationItemUiModel(
        id = id,
        sequence = sequence,
        kind = kind,
        text =
            NotificationItemTextUiModel(
                title = renderedCopy.title,
                excerpt = renderedCopy.body,
            ),
        metadata = toMetadata(context, strings),
        image =
            target?.coverImage?.let { image ->
                NotificationItemImageUiModel(url = image.url, alt = image.alt)
            },
        target =
            target?.let { availableTarget ->
                NotificationTargetUiModel(
                    listingId = availableTarget.listingId,
                    listingType = availableTarget.listingType,
                    listingName = availableTarget.listingName,
                    cityId = availableTarget.cityId.toAnalyticsSafeNotificationCityId(),
                )
            },
    )
}

private fun NotificationInboxItem.toMetadata(
    context: NotificationPresentationTimeContext,
    strings: NotificationStrings,
): NotificationItemMetadataUiModel =
    NotificationItemMetadataUiModel(
        relativeTime =
            createdAtEpochMilliseconds.toRelativeTime(
                nowEpochMilliseconds = context.nowEpochMilliseconds,
                todayEpochDay = context.todayEpochDay,
                timeZone = context.timeZone,
                strings = strings.relativeTime,
            ),
        eventDateLabel =
            (content as? NotificationContent.EventAlert)
                ?.eventStartAtEpochMilliseconds
                ?.toEventDateLabel(context.timeZone, strings.abbreviatedMonthNames),
        sponsoredBadge =
            (content as? NotificationContent.Sponsored)?.let {
                NotificationSponsoredBadgeUiModel(strings.templates.sponsoredBadge)
            },
        isUnread = readAtEpochMilliseconds == null,
    )

private fun NotificationContent.render(strings: NotificationTemplateStrings): RenderedNotificationCopy =
    when (this) {
        is NotificationContent.Suggestion ->
            strings.suggestion.render(
                replacements = mapOf(LISTING_NAME_TOKEN to listingName),
            )
        is NotificationContent.Sponsored ->
            strings.sponsored.render(
                replacements = mapOf(LISTING_NAME_TOKEN to listingName),
            )
        is NotificationContent.NewListing ->
            strings.newListing.render(
                replacements =
                    mapOf(
                        LISTING_NAME_TOKEN to listingName,
                        CITY_NAME_TOKEN to cityName,
                    ),
            )
        is NotificationContent.EventAlert ->
            strings.eventAlert.render(
                replacements = mapOf(LISTING_NAME_TOKEN to listingName),
            )
    }

private fun NotificationTemplateCopy.render(replacements: Map<String, String>): RenderedNotificationCopy =
    RenderedNotificationCopy(
        title = title.renderLocalTemplate(replacements),
        body = body.renderLocalTemplate(replacements),
    )

private fun String.renderLocalTemplate(replacements: Map<String, String>): String =
    LOCAL_TEMPLATE_TOKEN_PATTERN.replace(this) { match -> replacements[match.value] ?: match.value }

private fun Long.toTemporalGroup(
    todayEpochDay: Long,
    weekStartEpochDay: Long,
    timeZone: TimeZone,
): NotificationTemporalGroup {
    val createdEpochDay = toLocalEpochDay(timeZone)
    return when {
        createdEpochDay >= todayEpochDay -> NotificationTemporalGroup.Today
        createdEpochDay >= weekStartEpochDay -> NotificationTemporalGroup.ThisWeek
        else -> NotificationTemporalGroup.Earlier
    }
}

private fun Long.toRelativeTime(
    nowEpochMilliseconds: Long,
    todayEpochDay: Long,
    timeZone: TimeZone,
    strings: NotificationRelativeTimeStrings,
): String {
    val elapsedMilliseconds = (nowEpochMilliseconds - this).coerceAtLeast(0L)
    if (elapsedMilliseconds < MILLISECONDS_PER_MINUTE) return strings.now
    if (elapsedMilliseconds < MILLISECONDS_PER_HOUR) {
        val minutes = elapsedMilliseconds / MILLISECONDS_PER_MINUTE
        return if (minutes == 1L) strings.oneMinuteAgo else strings.minutesAgo.withCount(minutes)
    }
    if (elapsedMilliseconds < MILLISECONDS_PER_DAY) {
        val hours = elapsedMilliseconds / MILLISECONDS_PER_HOUR
        return if (hours == 1L) strings.oneHourAgo else strings.hoursAgo.withCount(hours)
    }
    val elapsedDays = (todayEpochDay - toLocalEpochDay(timeZone)).coerceAtLeast(0L)
    return if (elapsedDays == 1L) strings.yesterday else strings.daysAgo.withCount(elapsedDays)
}

private fun String.withCount(count: Long): String = replace(COUNT_TOKEN, count.toString())

private fun Long.toEventDateLabel(
    timeZone: TimeZone,
    monthNames: List<String>,
): String {
    val isoDate = Instant.fromEpochMilliseconds(this).toLocalDateTime(timeZone).date.toString()
    val day = isoDate.substring(ISO_DAY_START_INDEX, ISO_DAY_END_INDEX).toInt()
    val monthIndex = isoDate.substring(ISO_MONTH_START_INDEX, ISO_MONTH_END_INDEX).toInt() - 1
    return "$day ${monthNames[monthIndex]}"
}

private fun Long.toLocalEpochDay(timeZone: TimeZone): Long =
    Instant.fromEpochMilliseconds(this).toLocalDateTime(timeZone).date.toEpochDays()

private fun Long.startOfWeekEpochDay(): Long {
    val mondayBasedDayIndex = floorMod(this + UNIX_EPOCH_THURSDAY_INDEX, DAYS_PER_WEEK)
    return this - mondayBasedDayIndex
}

private fun floorMod(
    value: Long,
    divisor: Long,
): Long = ((value % divisor) + divisor) % divisor

private fun NotificationTemporalGroup.title(strings: NotificationStrings): String =
    when (this) {
        NotificationTemporalGroup.Today -> strings.screen.sections.today
        NotificationTemporalGroup.ThisWeek -> strings.screen.sections.thisWeek
        NotificationTemporalGroup.Earlier -> strings.screen.sections.earlier
    }

private fun NotificationPreferenceFamily.title(strings: NotificationStrings): String =
    when (this) {
        NotificationPreferenceFamily.Suggestion -> strings.preferences.suggestion
        NotificationPreferenceFamily.Sponsored -> strings.preferences.sponsored
        NotificationPreferenceFamily.NewListing -> strings.preferences.newListing
        NotificationPreferenceFamily.EventAlert -> strings.preferences.eventAlert
    }

private const val BENIN_TIME_ZONE_ID = "Africa/Porto-Novo"
private const val SUGGESTION_TITLE_KEY = "notification.suggestion.title"
private const val SUGGESTION_BODY_KEY = "notification.suggestion.body"
private const val SPONSORED_TITLE_KEY = "notification.sponsored.title"
private const val SPONSORED_BODY_KEY = "notification.sponsored.body"
private const val NEW_LISTING_TITLE_KEY = "notification.new_listing.title"
private const val NEW_LISTING_BODY_KEY = "notification.new_listing.body"
private const val EVENT_ALERT_TITLE_KEY = "notification.event_alert.title"
private const val EVENT_ALERT_BODY_KEY = "notification.event_alert.body"
private const val LISTING_NAME_TOKEN = "{listingName}"
private const val CITY_NAME_TOKEN = "{cityName}"
private const val COUNT_TOKEN = "{count}"
private val LOCAL_TEMPLATE_TOKEN_PATTERN = Regex("\\{listingName}|\\{cityName}")
private const val MILLISECONDS_PER_MINUTE = 60_000L
private const val MILLISECONDS_PER_HOUR = 3_600_000L
private const val MILLISECONDS_PER_DAY = 86_400_000L
private const val DAYS_PER_WEEK = 7L
private const val UNIX_EPOCH_THURSDAY_INDEX = 3L
private const val ISO_MONTH_START_INDEX = 5
private const val ISO_MONTH_END_INDEX = 7
private const val ISO_DAY_START_INDEX = 8
private const val ISO_DAY_END_INDEX = 10
