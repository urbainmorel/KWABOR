package com.kwabor.shared.data.core

import com.kwabor.shared.domain.core.ClockProvider
import kotlin.time.Clock

class SystemClockProvider : ClockProvider {
    override fun nowEpochMilliseconds(): Long = Clock.System.now().toEpochMilliseconds()
}
