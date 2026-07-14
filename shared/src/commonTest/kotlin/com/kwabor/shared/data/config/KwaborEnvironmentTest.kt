package com.kwabor.shared.data.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class KwaborEnvironmentTest {
    @Test
    fun fromConfiguration_acceptsOnlyKnownEnvironmentNames() {
        assertEquals(
            KwaborEnvironmentTier.Development,
            KwaborEnvironmentTier.fromConfiguration("development"),
        )
        assertEquals(
            KwaborEnvironmentTier.Staging,
            KwaborEnvironmentTier.fromConfiguration(" STAGING "),
        )
        assertEquals(
            KwaborEnvironmentTier.Production,
            KwaborEnvironmentTier.fromConfiguration("production"),
        )
        assertNull(KwaborEnvironmentTier.fromConfiguration(null))
        assertNull(KwaborEnvironmentTier.fromConfiguration("preview"))
    }

    @Test
    fun createEnvironment_trimsPublicConfigurationAndKeepsTier() {
        val environment = assertNotNull(
            createKwaborEnvironmentOrNull(
                environmentName = "staging",
                supabaseUrl = " https://example.invalid ",
                supabasePublishableKey = " publishable-key ",
            ),
        )

        assertEquals(KwaborEnvironmentTier.Staging, environment.tier)
        assertEquals("https://example.invalid", environment.supabaseUrl)
        assertEquals("publishable-key", environment.supabasePublishableKey)
    }
}
