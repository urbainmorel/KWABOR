package com.kwabor.shared.app

import kotlin.test.Test
import kotlin.test.assertFalse

class IosRegistrationIntentTest {
    @Test
    fun secretIntentsDoNotExposeValuesThroughToString() {
        val otp = "123456"
        val password = "very-secret-password"

        assertFalse(IosRegistrationVerifyOtpIntent(otp).toString().contains(otp))
        assertFalse(
            IosRegistrationSetInitialPasswordIntent(password, password).toString().contains(password),
        )
    }
}
