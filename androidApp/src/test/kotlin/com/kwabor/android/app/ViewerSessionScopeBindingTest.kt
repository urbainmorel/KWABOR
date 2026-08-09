package com.kwabor.android.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.After
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [TEST_SDK], manifest = Config.NONE)
class ViewerSessionScopeBindingTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private var activityController: ActivityController<ComponentActivity>? = null

    @After
    fun tearDown() {
        activityController?.pause()?.stop()?.destroy()
    }

    @Test
    fun guestRecomposition_doesNotRepublishUnchangedScope() {
        var recompositionToken by mutableIntStateOf(0)
        var accountId by mutableStateOf<String?>(null)
        var accountSetupComplete by mutableStateOf(false)
        val publications = mutableListOf<PublishedViewerContext>()
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activityController = controller
        controller.get().setContent {
            Text(recompositionToken.toString())
            ViewerSessionScopeHandler(accountId, accountSetupComplete) { publishedAccountId, setupComplete ->
                publications += PublishedViewerContext(publishedAccountId, setupComplete)
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle { recompositionToken += 1 }
        composeRule.waitForIdle()

        assertEquals(listOf(PublishedViewerContext(null, accountSetupComplete = false)), publications)

        composeRule.runOnIdle {
            accountId = ACCOUNT_ID
            accountSetupComplete = true
        }
        composeRule.waitForIdle()

        assertEquals(
            listOf(
                PublishedViewerContext(null, accountSetupComplete = false),
                PublishedViewerContext(ACCOUNT_ID, accountSetupComplete = true),
            ),
            publications,
        )
    }
}

private data class PublishedViewerContext(
    val accountId: String?,
    val accountSetupComplete: Boolean,
)

private const val TEST_SDK = 35
private const val ACCOUNT_ID = "00000000-0000-4000-8000-000000000001"
