package com.kwabor.shared.data.observability

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.kwabor.shared.domain.observability.ObservedAppSessionTimeMark
import com.kwabor.shared.domain.observability.ObservedAppSessionTimeRead
import com.kwabor.shared.domain.observability.ObservedAppSessionTimeSource

internal fun createAndroidObservedAppSessionTimeSource(context: Context): ObservedAppSessionTimeSource {
    val applicationContext = context.applicationContext
    val monotonicMilliseconds = SystemClock.elapsedRealtime()
    val wallEpochMilliseconds = System.currentTimeMillis()
    val bootCount = Settings.Global.getInt(
        applicationContext.contentResolver,
        Settings.Global.BOOT_COUNT,
        BOOT_COUNT_UNAVAILABLE,
    )
    return AndroidObservedAppSessionTimeSource(
        bootIdentifier = bootCount.takeIf { it >= 0 }?.toLong(),
        bootAnchorEpochMilliseconds = wallEpochMilliseconds - monotonicMilliseconds,
    )
}

private class AndroidObservedAppSessionTimeSource(
    private val bootIdentifier: Long?,
    private val bootAnchorEpochMilliseconds: Long,
    private val wallClockMilliseconds: () -> Long = System::currentTimeMillis,
    private val monotonicClockMilliseconds: () -> Long = SystemClock::elapsedRealtime,
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
                bootIdentifier = bootIdentifier,
                bootAnchorEpochMilliseconds = bootAnchorEpochMilliseconds,
            ),
        )
    }
}

private const val BOOT_COUNT_UNAVAILABLE = -1
