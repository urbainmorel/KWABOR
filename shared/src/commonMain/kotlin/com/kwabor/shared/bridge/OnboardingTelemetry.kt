package com.kwabor.shared.bridge

import com.kwabor.shared.domain.observability.AnalyticsEvent
import com.kwabor.shared.domain.observability.AnalyticsEventName

data class OnboardingTelemetry(
    val shownEvent: AnalyticsEvent = AnalyticsEvent(name = AnalyticsEventName.IntroVideoShown),
    val skippedEvent: AnalyticsEvent = AnalyticsEvent(name = AnalyticsEventName.IntroVideoSkipped),
)
