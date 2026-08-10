package com.kwabor.shared.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kwabor.shared.data.auth.KwaborSessionManager
import com.kwabor.shared.data.auth.SecureStringStore
import com.kwabor.shared.data.local.KwaborDatabase
import com.kwabor.shared.data.local.KwaborDatabaseBuilderResult
import com.kwabor.shared.data.local.KwaborDatabaseConstructor
import com.kwabor.shared.data.local.KwaborDatabaseStorageMode
import com.kwabor.shared.data.preferences.createAndroidAppPreferencesStorage
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.interaction.InteractionAccountScope
import com.kwabor.shared.domain.interaction.InteractionKind
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class PersistenceModuleIntegrationTest {
    @Test
    fun compositionRootResolvesEachPersistenceResourceLazilyOnce() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val requests = PersistenceFactoryRequests()
        val root = createPersistenceRoot(context, requests)

        try {
            assertEquals(0, requests.databaseBuilder)
            assertEquals(0, requests.databaseConfiguration)
            assertEquals(0, requests.preferencesStorage)
            assertNull(root.interactionCoordinator)

            val preferencesRepository = assertNotNull(root.appPreferencesRepository)
            assertSame(preferencesRepository, root.appPreferencesRepository)
            assertEquals(0, requests.databaseBuilder)
            assertEquals(0, requests.databaseConfiguration)
            assertEquals(1, requests.preferencesStorage)

            val exploreCacheStore = assertNotNull(root.exploreCacheStore)
            assertSame(exploreCacheStore, root.exploreCacheStore)
            assertEquals(1, requests.databaseBuilder)
            assertEquals(1, requests.databaseConfiguration)
            assertEquals(1, requests.preferencesStorage)
        } finally {
            root.close()
        }
    }

    @Test
    fun compositionRootCreatesDurableInteractionGraphWithoutOpeningRoom() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val requests = PersistenceFactoryRequests()
        val root = createPersistenceRoot(context, requests, withAuthentication = true)

        try {
            assertNotNull(root.interactionCoordinator)
            assertEquals(1, requests.databaseConfiguration)
            assertEquals(0, requests.databaseBuilder)
            assertEquals(0, requests.preferencesStorage)
        } finally {
            root.close()
        }
    }

    @Test
    fun memoryOnlyFallbackKeepsCoordinatorFailClosed() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val requests = PersistenceFactoryRequests()
        val root = createPersistenceRoot(
            context = context,
            requests = requests,
            withAuthentication = true,
            storageMode = KwaborDatabaseStorageMode.MemoryOnly,
        )

        try {
            val coordinator = assertNotNull(root.interactionCoordinator)
            val viewerScope = root.viewerSessionScopeTracker.update(
                accountId = TEST_ACCOUNT_ID,
                accountSetupComplete = true,
            )
            val interactionScope = InteractionAccountScope(
                accountId = TEST_ACCOUNT_ID,
                epoch = viewerScope.epoch,
            )

            val submit = coordinator.submit(
                expectedScope = interactionScope,
                listingId = TEST_LISTING_ID,
                kind = InteractionKind.Like,
                desiredSelected = true,
            )
            val hydrate = coordinator.hydrate(interactionScope, listOf(TEST_LISTING_ID))
            val purge = coordinator.purgeForAccountDeletion(TEST_ACCOUNT_ID)

            assertIs<DomainError.LocalStorageUnavailable>(assertIs<DomainResult.Failure>(submit).error)
            assertIs<DomainError.LocalStorageUnavailable>(assertIs<DomainResult.Failure>(hydrate).error)
            assertIs<DomainError.LocalStorageUnavailable>(assertIs<DomainResult.Failure>(purge).error)
            assertEquals(1, requests.databaseConfiguration)
            assertEquals(0, requests.databaseBuilder)
        } finally {
            root.close()
        }
    }
}

private data class PersistenceFactoryRequests(
    var databaseConfiguration: Int = 0,
    var databaseBuilder: Int = 0,
    var preferencesStorage: Int = 0,
)

private fun createPersistenceRoot(
    context: Context,
    requests: PersistenceFactoryRequests,
    withAuthentication: Boolean = false,
    storageMode: KwaborDatabaseStorageMode = KwaborDatabaseStorageMode.Durable,
): KwaborCompositionRoot = assertNotNull(
    createKwaborCompositionRootOrNull(
        supabaseUrl = "https://example.invalid",
        supabasePublishableKey = "publishable-key",
        authSessionManager = if (withAuthentication) {
            KwaborSessionManager(AndroidHostSecureStringStore())
        } else {
            null
        },
        persistenceConfigurationProvider = {
            KwaborPersistenceConfiguration(
                databaseBuilderFactory = {
                    requests.databaseConfiguration += 1
                    KwaborDatabaseBuilderResult(
                        storageMode = storageMode,
                        builderFactory = {
                            requests.databaseBuilder += 1
                            Room.inMemoryDatabaseBuilder<KwaborDatabase>(
                                context = context,
                                factory = KwaborDatabaseConstructor::initialize,
                            )
                        },
                    )
                },
                preferencesStorageFactory = {
                    requests.preferencesStorage += 1
                    createAndroidAppPreferencesStorage(context)
                },
            )
        },
    ),
)

private class AndroidHostSecureStringStore : SecureStringStore {
    private val values = mutableMapOf<String, String>()

    override suspend fun putString(key: String, value: String) {
        values[key] = value
    }

    override suspend fun getStringOrNull(key: String): String? = values[key]

    override suspend fun remove(key: String) {
        values.remove(key)
    }
}

private const val TEST_ACCOUNT_ID = "11111111-1111-4111-8111-111111111111"
private const val TEST_LISTING_ID = "33333333-3333-4333-8333-333333333333"
