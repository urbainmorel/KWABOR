package com.kwabor.shared.data.observability

import com.kwabor.shared.domain.observability.ObservedAppSessionCheckpointRead
import com.kwabor.shared.domain.observability.ObservedAppSessionTimeMark

internal object ObservedAppSessionCheckpointCodec {
    fun encodeForeground(): String = FOREGROUND_CHECKPOINT

    fun encodeBackgroundedAt(timeMark: ObservedAppSessionTimeMark): String = listOf(
        CHECKPOINT_VERSION,
        BACKGROUND_CHECKPOINT_TYPE,
        timeMark.wallEpochMilliseconds,
        timeMark.monotonicMilliseconds,
        timeMark.bootAnchorEpochMilliseconds,
        timeMark.bootIdentifier ?: MISSING_BOOT_IDENTIFIER,
    ).joinToString(separator = CHECKPOINT_SEPARATOR)

    fun decode(value: String): ObservedAppSessionCheckpointRead {
        return when {
            value == LEGACY_FOREGROUND_CHECKPOINT || value.startsWith(LEGACY_BACKGROUND_PREFIX) -> {
                ObservedAppSessionCheckpointRead.Foreground
            }
            value == FOREGROUND_CHECKPOINT -> ObservedAppSessionCheckpointRead.Foreground
            else -> decodeBackgroundedAt(value)
        }
    }

    private fun decodeBackgroundedAt(value: String): ObservedAppSessionCheckpointRead {
        val fields = value.split(CHECKPOINT_SEPARATOR)
        if (
            fields.size != BACKGROUND_FIELD_COUNT ||
            fields[VERSION_FIELD_INDEX] != CHECKPOINT_VERSION ||
            fields[TYPE_FIELD_INDEX] != BACKGROUND_CHECKPOINT_TYPE
        ) {
            return ObservedAppSessionCheckpointRead.Failure
        }
        val timeMark = fields.toObservedAppSessionTimeMarkOrNull()
            ?: return ObservedAppSessionCheckpointRead.Failure
        return ObservedAppSessionCheckpointRead.BackgroundedAt(timeMark)
    }

    private fun List<String>.toObservedAppSessionTimeMarkOrNull(): ObservedAppSessionTimeMark? {
        val wallEpochMilliseconds = get(WALL_TIME_FIELD_INDEX).toLongOrNull() ?: return null
        val monotonicMilliseconds = get(MONOTONIC_TIME_FIELD_INDEX).toLongOrNull() ?: return null
        val bootAnchorEpochMilliseconds = get(BOOT_ANCHOR_FIELD_INDEX).toLongOrNull() ?: return null
        val bootIdentifierField = get(BOOT_IDENTIFIER_FIELD_INDEX)
        val bootIdentifier = bootIdentifierField
            .takeUnless { it == MISSING_BOOT_IDENTIFIER }
            ?.toLongOrNull()
        if (bootIdentifierField != MISSING_BOOT_IDENTIFIER && bootIdentifier == null) return null
        return runCatching {
            ObservedAppSessionTimeMark(
                wallEpochMilliseconds = wallEpochMilliseconds,
                monotonicMilliseconds = monotonicMilliseconds,
                bootIdentifier = bootIdentifier,
                bootAnchorEpochMilliseconds = bootAnchorEpochMilliseconds,
            )
        }.getOrNull()
    }
}

private const val CHECKPOINT_VERSION = "v1"
private const val CHECKPOINT_SEPARATOR = "|"
private const val BACKGROUND_CHECKPOINT_TYPE = "background"
private const val BACKGROUND_FIELD_COUNT = 6
private const val VERSION_FIELD_INDEX = 0
private const val TYPE_FIELD_INDEX = 1
private const val WALL_TIME_FIELD_INDEX = 2
private const val MONOTONIC_TIME_FIELD_INDEX = 3
private const val BOOT_ANCHOR_FIELD_INDEX = 4
private const val BOOT_IDENTIFIER_FIELD_INDEX = 5
private const val MISSING_BOOT_IDENTIFIER = "~"
private const val FOREGROUND_CHECKPOINT = "$CHECKPOINT_VERSION${CHECKPOINT_SEPARATOR}foreground"
private const val LEGACY_FOREGROUND_CHECKPOINT = "foreground"
private const val LEGACY_BACKGROUND_PREFIX = "backgrounded_at:"
