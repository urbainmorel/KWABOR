package com.kwabor.shared.data.config

import io.github.jan.supabase.logging.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals

class SupabaseClientFactoryTest {
    @Test
    fun clientUsesWarningLogLevelToKeepAuthCredentialsOutOfDebugLogs() {
        val client = createKwaborSupabaseClient(
            environment = KwaborEnvironment(
                tier = KwaborEnvironmentTier.Development,
                supabaseUrl = "https://kwabor.test",
                supabasePublishableKey = "publishable-test-key",
            ),
        )

        assertEquals(LogLevel.WARNING, client.config.loggingConfig.defaultLogLevel)
    }
}
