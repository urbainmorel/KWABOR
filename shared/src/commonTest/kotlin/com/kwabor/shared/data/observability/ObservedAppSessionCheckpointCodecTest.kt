package com.kwabor.shared.data.observability

import com.kwabor.shared.domain.observability.ObservedAppSessionCheckpointRead
import com.kwabor.shared.domain.observability.ObservedAppSessionTimeMark
import kotlin.test.Test
import kotlin.test.assertEquals

class ObservedAppSessionCheckpointCodecTest {
    @Test
    fun backgroundCheckpointRoundTripsEveryContinuityField() {
        val expected = ObservedAppSessionCheckpointRead.BackgroundedAt(
            ObservedAppSessionTimeMark(
                wallEpochMilliseconds = 1_700_000_000_000L,
                monotonicMilliseconds = 42_000L,
                bootIdentifier = 42L,
                bootAnchorEpochMilliseconds = 1_699_999_958_000L,
            ),
        )

        val encoded = ObservedAppSessionCheckpointCodec.encodeBackgroundedAt(expected.timeMark)

        assertEquals(expected, ObservedAppSessionCheckpointCodec.decode(encoded))
    }

    @Test
    fun malformedAndPiiLikeBootIdentifiersFailClosed() {
        assertEquals(
            ObservedAppSessionCheckpointRead.Failure,
            ObservedAppSessionCheckpointCodec.decode(
                "v1|background|1700000000000|42000|1699999958000|name@example.com",
            ),
        )
        assertEquals(
            ObservedAppSessionCheckpointRead.Failure,
            ObservedAppSessionCheckpointCodec.decode("v1|background|invalid"),
        )
    }

    @Test
    fun legacyEpochCheckpointMigratesConservativelyToForeground() {
        assertEquals(
            ObservedAppSessionCheckpointRead.Foreground,
            ObservedAppSessionCheckpointCodec.decode("backgrounded_at:1700000000000"),
        )
    }
}
