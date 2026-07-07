package com.kwabor.shared.app

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KwaborRuntimeDependenciesTest {
    @Test
    fun createOrNull_returnsNullWhenConfigurationIsMissing() {
        assertNull(
            KwaborRuntimeDependencies.createOrNull(
                supabaseUrl = "",
                supabasePublishableKey = "publishable-key",
            ),
        )
        assertNull(
            KwaborRuntimeDependencies.createOrNull(
                supabaseUrl = "https://example.invalid",
                supabasePublishableKey = "",
            ),
        )
    }

    @Test
    fun createOrNull_returnsNullWhenUrlIsNotHttps() {
        assertNull(
            KwaborRuntimeDependencies.createOrNull(
                supabaseUrl = "http://example.invalid",
                supabasePublishableKey = "publishable-key",
            ),
        )
    }

    @Test
    fun createOrNull_createsCatalogDependenciesForValidPublicConfiguration() {
        val dependencies = KwaborRuntimeDependencies.createOrNull(
            supabaseUrl = "https://example.invalid",
            supabasePublishableKey = "publishable-key",
        )

        assertNotNull(dependencies)
        assertNull(dependencies.authRepository)
        assertTrue(dependencies.clockProvider.nowEpochMilliseconds() > 0L)
    }
}
