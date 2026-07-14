package com.kwabor.shared.app

import com.kwabor.shared.data.auth.DataAuthRepository
import com.kwabor.shared.data.auth.KwaborSessionManager
import com.kwabor.shared.data.auth.SecureStringStore
import com.kwabor.shared.data.catalog.DataCatalogRepository
import com.kwabor.shared.data.organization.DataOrganizationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class KwaborCompositionRootTest {
    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun create_returnsNullWhenConfigurationIsMissingOrInsecure() {
        assertNull(
            createKwaborCompositionRootOrNull(
                supabaseUrl = "",
                supabasePublishableKey = "publishable-key",
            ),
        )
        assertNull(
            createKwaborCompositionRootOrNull(
                supabaseUrl = "https://example.invalid",
                supabasePublishableKey = "",
            ),
        )
        assertNull(
            createKwaborCompositionRootOrNull(
                supabaseUrl = "http://example.invalid",
                supabasePublishableKey = "publishable-key",
            ),
        )
    }

    @Test
    fun create_buildsPublicFeatureGraphWithoutAuth() {
        val root = assertNotNull(
            createKwaborCompositionRootOrNull(
                supabaseUrl = "https://example.invalid",
                supabasePublishableKey = "publishable-key",
            ),
        )

        try {
            assertIs<DataCatalogRepository>(root.catalogRepository)
            assertIs<DataOrganizationRepository>(root.organizationRepository)
            assertNull(root.authRepository)
            assertTrue(root.clockProvider.nowEpochMilliseconds() > 0L)
        } finally {
            root.close()
        }
    }

    @Test
    fun create_buildsAuthFeatureWhenSecureSessionManagerIsProvided() {
        val root = assertNotNull(
            createKwaborCompositionRootOrNull(
                supabaseUrl = "https://example.invalid",
                supabasePublishableKey = "publishable-key",
                authSessionManager = KwaborSessionManager(InMemorySecureStringStore()),
            ),
        )

        try {
            assertIs<DataAuthRepository>(root.authRepository)
        } finally {
            root.close()
        }
    }
}

private class InMemorySecureStringStore : SecureStringStore {
    private val values = mutableMapOf<String, String>()

    override suspend fun putString(key: String, value: String) {
        values[key] = value
    }

    override suspend fun getStringOrNull(key: String): String? = values[key]

    override suspend fun remove(key: String) {
        values.remove(key)
    }
}
