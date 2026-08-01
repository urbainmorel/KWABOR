package com.kwabor.shared.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kwabor.shared.data.local.KwaborDatabase
import com.kwabor.shared.data.local.KwaborDatabaseConstructor
import com.kwabor.shared.data.preferences.createAndroidAppPreferencesStorage
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
            assertEquals(0, requests.preferencesStorage)

            val preferencesRepository = assertNotNull(root.appPreferencesRepository)
            assertSame(preferencesRepository, root.appPreferencesRepository)
            assertEquals(0, requests.databaseBuilder)
            assertEquals(1, requests.preferencesStorage)

            val exploreCacheStore = assertNotNull(root.exploreCacheStore)
            assertSame(exploreCacheStore, root.exploreCacheStore)
            assertEquals(1, requests.databaseBuilder)
            assertEquals(1, requests.preferencesStorage)
        } finally {
            root.close()
        }
    }
}

private data class PersistenceFactoryRequests(
    var databaseBuilder: Int = 0,
    var preferencesStorage: Int = 0,
)

private fun createPersistenceRoot(context: Context, requests: PersistenceFactoryRequests): KwaborCompositionRoot =
    assertNotNull(
        createKwaborCompositionRootOrNull(
            supabaseUrl = "https://example.invalid",
            supabasePublishableKey = "publishable-key",
            persistenceConfigurationProvider = {
                KwaborPersistenceConfiguration(
                    databaseBuilderFactory = {
                        requests.databaseBuilder += 1
                        Room.inMemoryDatabaseBuilder<KwaborDatabase>(
                            context = context,
                            factory = KwaborDatabaseConstructor::initialize,
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
