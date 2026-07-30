package com.kwabor.shared.bridge

import com.kwabor.shared.domain.observability.AnalyticsAuthMethod
import com.kwabor.shared.domain.observability.AnalyticsEvent
import com.kwabor.shared.domain.observability.AnalyticsEventName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OnboardingTelemetryTest {
    private val telemetry = OnboardingTelemetry()

    @Test
    fun `registration auth events preserve their method`() {
        assertAuthMethod(
            expected = AnalyticsAuthMethod.Email,
            event = telemetry.registrationEmailMethodEvent,
        )
        assertAuthMethod(
            expected = AnalyticsAuthMethod.Google,
            event = telemetry.registrationGoogleMethodEvent,
        )
        assertAuthMethod(
            expected = AnalyticsAuthMethod.Apple,
            event = telemetry.registrationAppleMethodEvent,
        )
    }

    @Test
    fun `registration lifecycle events expose no authentication method`() {
        val expectedNames = listOf(
            AnalyticsEventName.RegistrationOtpValidated,
            AnalyticsEventName.RegistrationProfileSucceeded,
            AnalyticsEventName.RegistrationProfileFailed,
        )
        val events = listOf(
            telemetry.registrationOtpValidatedEvent,
            telemetry.registrationProfileSucceededEvent,
            telemetry.registrationProfileFailedEvent,
        )

        assertEquals(expectedNames, events.map { it.name })
        events.forEach { assertNull(it.authMethod) }
    }

    private fun assertAuthMethod(expected: AnalyticsAuthMethod, event: AnalyticsEvent) {
        assertEquals(AnalyticsEventName.AuthMethod, event.name)
        assertEquals(expected, event.authMethod)
    }
}
