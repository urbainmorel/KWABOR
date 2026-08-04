package com.kwabor.shared.data.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.money.KwaborCurrency
import com.kwabor.shared.domain.preferences.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AppPreferencesPersistenceTest {
    @Test
    fun preferencesSurviveDataStoreClosureAndReopening() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferencesFile = context.preferencesFile()
        preferencesFile.deleteIfPresent()

        try {
            withRepository(context, coroutineContext) { repository ->
                assertIs<DomainResult.Success<AppPreferences>>(repository.setExploreCity("ouidah"))
                assertIs<DomainResult.Success<AppPreferences>>(
                    repository.setDisplayCurrency(KwaborCurrency.Eur),
                )
            }

            withRepository(context, coroutineContext) { repository ->
                val preferences = assertIs<DomainResult.Success<AppPreferences>>(repository.get()).value
                assertEquals("ouidah", preferences.exploreCityId)
                assertEquals(KwaborCurrency.Eur, preferences.displayCurrency)
            }
        } finally {
            preferencesFile.deleteIfPresent()
        }
    }

    @Test
    fun corruptPreferencesFileIsReplacedWithSafeDefaults() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferencesFile = context.preferencesFile()
        preferencesFile.deleteIfPresent()
        preferencesFile.parentFile?.mkdirs()
        preferencesFile.writeBytes(CORRUPT_PREFERENCES_CONTENT.encodeToByteArray())

        try {
            withRepository(context, coroutineContext) { repository ->
                val preferences = assertIs<DomainResult.Success<AppPreferences>>(repository.get()).value
                assertNull(preferences.exploreCityId)
                assertEquals(KwaborCurrency.Xof, preferences.displayCurrency)
            }
        } finally {
            preferencesFile.deleteIfPresent()
        }
    }
}

private const val CORRUPT_PREFERENCES_CONTENT = "not-a-preferences-protobuf"

private suspend fun withRepository(
    context: Context,
    testCoroutineContext: CoroutineContext,
    block: suspend (DataStoreAppPreferencesRepository) -> Unit,
) {
    val scope = CoroutineScope(SupervisorJob() + testCoroutineContext.minusKey(Job))
    val repository = DataStoreAppPreferencesRepository(
        dataStore = createAppPreferencesDataStore(
            storage = createAndroidAppPreferencesStorage(context),
            coroutineScope = scope,
        ),
    )
    try {
        block(repository)
    } finally {
        scope.coroutineContext.job.cancelAndJoin()
    }
}

private fun Context.preferencesFile(): File = filesDir.resolve(APP_PREFERENCES_FILE_NAME)

private fun File.deleteIfPresent() {
    assertTrue(!exists() || delete(), "Unable to delete the test preferences file.")
}
