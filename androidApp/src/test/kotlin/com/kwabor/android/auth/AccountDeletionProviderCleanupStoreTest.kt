package com.kwabor.android.auth

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [TEST_SDK], manifest = Config.NONE)
class AccountDeletionProviderCleanupStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearBeforeTest() {
        assertTrue(SharedPreferencesAccountDeletionProviderCleanupStore(context).clear())
    }

    @After
    fun clearAfterTest() {
        assertTrue(SharedPreferencesAccountDeletionProviderCleanupStore(context).clear())
    }

    @Test
    fun committedMarkerSurvivesStoreRecreationUntilVerifiedClear() {
        val initialStore = SharedPreferencesAccountDeletionProviderCleanupStore(context)

        assertTrue(initialStore.markPending())

        val recreatedStore = SharedPreferencesAccountDeletionProviderCleanupStore(context)
        assertTrue(recreatedStore.hasPendingCleanup())
        assertTrue(recreatedStore.clear())
        assertFalse(initialStore.hasPendingCleanup())
    }

    @Test
    fun malformedMarkerFailsClosedAndCanBeRemovedSynchronously() {
        assertTrue(
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PENDING_CLEANUP, "malformed")
                .commit(),
        )
        val store = SharedPreferencesAccountDeletionProviderCleanupStore(context)

        assertTrue(store.hasPendingCleanup())
        assertFalse(store.markPending())
        assertTrue(store.clear())
        assertFalse(store.hasPendingCleanup())
    }
}

private const val PREFERENCES_NAME = "kwabor_account_deletion_provider_cleanup"
private const val KEY_PENDING_CLEANUP = "pending_cleanup"
private const val TEST_SDK = 35
