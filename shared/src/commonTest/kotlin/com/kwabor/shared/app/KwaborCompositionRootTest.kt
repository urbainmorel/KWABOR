package com.kwabor.shared.app

import com.kwabor.shared.data.auth.DataAuthRepository
import com.kwabor.shared.data.auth.KwaborSessionManager
import com.kwabor.shared.data.auth.SecureStringStore
import com.kwabor.shared.data.catalog.DataCatalogRepository
import com.kwabor.shared.data.favorites.DataFavoritesRepository
import com.kwabor.shared.data.guide.DataGuideDiscoveryRepository
import com.kwabor.shared.data.organization.DataOrganizationRepository
import com.kwabor.shared.presentation.auth.AuthPresenter
import com.kwabor.shared.presentation.explore.ExplorePresenter
import com.kwabor.shared.presentation.favorites.FavoritesPresenter
import com.kwabor.shared.presentation.guide.GuideDiscoveryPresenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertNull(
            createKwaborCompositionRootOrNull(
                supabaseUrl = "https://example.invalid",
                supabasePublishableKey = "publishable-key",
                environmentName = "preview",
            ),
        )
    }

    @Test
    fun create_doesNotResolvePlatformPersistenceWhenEnvironmentIsInvalid() {
        var persistenceConfigurationRequests = 0

        assertNull(
            createKwaborCompositionRootOrNull(
                supabaseUrl = "http://example.invalid",
                supabasePublishableKey = "publishable-key",
                persistenceConfigurationProvider = {
                    persistenceConfigurationRequests += 1
                    error("Persistence must remain lazy until the environment is valid.")
                },
            ),
        )

        assertEquals(0, persistenceConfigurationRequests)
    }

    @Test
    fun create_keepsPlatformPersistenceLazyWhenEnvironmentIsValid() {
        var persistenceConfigurationRequests = 0
        var databaseBuilderRequests = 0
        var preferencesStorageRequests = 0
        val root = assertNotNull(
            createKwaborCompositionRootOrNull(
                supabaseUrl = "https://example.invalid",
                supabasePublishableKey = "publishable-key",
                persistenceConfigurationProvider = {
                    persistenceConfigurationRequests += 1
                    KwaborPersistenceConfiguration(
                        databaseBuilderFactory = {
                            databaseBuilderRequests += 1
                            error("The database must not open before its first use.")
                        },
                        preferencesStorageFactory = {
                            preferencesStorageRequests += 1
                            error("DataStore must not open before its first use.")
                        },
                    )
                },
            ),
        )

        try {
            assertEquals(1, persistenceConfigurationRequests)
            assertEquals(0, databaseBuilderRequests)
            assertEquals(0, preferencesStorageRequests)
        } finally {
            root.close()
        }
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
            assertIs<DataFavoritesRepository>(root.favoritesRepository)
            assertIs<DefaultDispatcherProvider>(root.dispatcherProvider)
            assertIs<DataGuideDiscoveryRepository>(root.guideDiscoveryRepository)
            assertIs<DataOrganizationRepository>(root.organizationRepository)
            assertIs<ExplorePresenter>(root.explorePresenter)
            assertIs<FavoritesPresenter>(root.favoritesPresenter)
            assertIs<GuideDiscoveryPresenter>(root.guideDiscoveryPresenter)
            assertNull(root.appPreferencesRepository)
            assertNull(root.exploreCacheStore)
            assertNull(root.authRepository)
            assertNull(root.authPresenter)
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
            assertIs<AuthPresenter>(root.authPresenter)
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
