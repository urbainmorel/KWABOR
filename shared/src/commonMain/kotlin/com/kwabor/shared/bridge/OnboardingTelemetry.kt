package com.kwabor.shared.bridge

import com.kwabor.shared.domain.observability.AnalyticsAuthMethod
import com.kwabor.shared.domain.observability.AnalyticsEvent
import com.kwabor.shared.domain.observability.AnalyticsEventName

data class OnboardingTelemetry(
    val shownEvent: AnalyticsEvent = AnalyticsEvent(name = AnalyticsEventName.IntroVideoShown),
    val skippedEvent: AnalyticsEvent = AnalyticsEvent(name = AnalyticsEventName.IntroVideoSkipped),
    val registrationEmailMethodEvent: AnalyticsEvent = AnalyticsEvent(
        name = AnalyticsEventName.AuthMethod,
        authMethod = AnalyticsAuthMethod.Email,
    ),
    val registrationGoogleMethodEvent: AnalyticsEvent = AnalyticsEvent(
        name = AnalyticsEventName.AuthMethod,
        authMethod = AnalyticsAuthMethod.Google,
    ),
    val registrationAppleMethodEvent: AnalyticsEvent = AnalyticsEvent(
        name = AnalyticsEventName.AuthMethod,
        authMethod = AnalyticsAuthMethod.Apple,
    ),
    val registrationOtpValidatedEvent: AnalyticsEvent = AnalyticsEvent(
        name = AnalyticsEventName.RegistrationOtpValidated,
    ),
    val registrationProfileSucceededEvent: AnalyticsEvent = AnalyticsEvent(
        name = AnalyticsEventName.RegistrationProfileSucceeded,
    ),
    val registrationProfileFailedEvent: AnalyticsEvent = AnalyticsEvent(
        name = AnalyticsEventName.RegistrationProfileFailed,
    ),
)
