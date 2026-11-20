package com.kwabor.shared.data.observability

import com.kwabor.shared.domain.observability.ObservedAppSessionTimeMark
import com.kwabor.shared.domain.observability.ObservedAppSessionTimeRead
import com.kwabor.shared.domain.observability.ObservedAppSessionTimeSource
import platform.Foundation.NSProcessInfo
import kotlin.time.Clock

internal fun createIosObservedAppSessionTimeSource(): ObservedAppSessionTimeSource {
    val monotonicMilliseconds = currentMonotonicMilliseconds()
    val wallEpochMilliseconds = currentWallEpochMilliseconds()
    return IosObservedAppSessionTimeSource(
        bootAnchorEpochMilliseconds = wallEpochMilliseconds - monotonicMilliseconds,
    )
}

private class IosObservedAppSessionTimeSource(
    private val bootAnchorEpochMilliseconds: Long,
    private val wallClockMilliseconds: () -> Long = ::currentWallEpochMilliseconds,
    private val monotonicClockMilliseconds: () -> Long = ::currentMonotonicMilliseconds,
) : ObservedAppSessionTimeSource {
    override fun read(): ObservedAppSessionTimeRead {
        val monotonicMilliseconds = monotonicClockMilliseconds()
        val wallEpochMilliseconds = wallClockMilliseconds()
        if (
            wallEpochMilliseconds < 0L ||
            monotonicMilliseconds < 0L ||
            bootAnchorEpochMilliseconds < 0L
        ) {
            return ObservedAppSessionTimeRead.Failure
        }
        return ObservedAppSessionTimeRead.Available(
            ObservedAppSessionTimeMark(
                wallEpochMilliseconds = wallEpochMilliseconds,
                monotonicMilliseconds = monotonicMilliseconds,
                bootIdentifier = null,
                bootAnchorEpochMilliseconds = bootAnchorEpochMilliseconds,
            ),
        )
    }
}

private fun currentWallEpochMilliseconds(): Long = Clock.System.now().toEpochMilliseconds()

private fun currentMonotonicMilliseconds(): Long =
    (NSProcessInfo.processInfo.systemUptime * MILLISECONDS_PER_SECOND).toLong()

private const val MILLISECONDS_PER_SECOND = 1_000.0
