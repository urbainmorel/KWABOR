package com.kwabor.android.ui.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.kwabor.android.design.KwaborTheme
import com.kwabor.android.media.ListingMediaUrlPolicy
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.stringsFor
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
@Config(application = Application::class, sdk = [TEST_SDK], manifest = Config.NONE)
class ListingCardSemanticsTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private var activityController: ActivityController<ComponentActivity>? = null
    private val strings = stringsFor(AppLocale.French)

    @After
    fun tearDown() {
        activityController?.pause()?.stop()?.destroy()
    }

    @Test
    fun favoriteCard_exposesOpenEndedAndRemoveAsOrderedDistinctNodes() {
        var openCount = 0
        var removeCount = 0
        setFavoriteCardContent(
            onOpen = { openCount += 1 },
            onRemove = { removeCount += 1 },
        )

        composeRule.onAllNodesWithContentDescription(OPEN_DESCRIPTION).assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription(strings.favorites.eventEndedAccessibility).assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription(strings.favorites.removeFavorite).assertCountEquals(1)
        composeRule.onAllNodesWithText(CARD_TITLE).assertCountEquals(0)
        composeRule.onAllNodesWithText(CARD_TITLE, useUnmergedTree = true).assertCountEquals(1)
        composeRule.onNodeWithContentDescription(OPEN_DESCRIPTION).assertTraversalIndex(OPEN_TRAVERSAL_INDEX)
        composeRule.onNodeWithContentDescription(strings.favorites.eventEndedAccessibility)
            .assertTraversalIndex(ENDED_TRAVERSAL_INDEX)
        composeRule.onNodeWithContentDescription(strings.favorites.removeFavorite)
            .assertTraversalIndex(REMOVE_TRAVERSAL_INDEX)

        composeRule.onNodeWithContentDescription(OPEN_DESCRIPTION).performClick()
        composeRule.onNodeWithContentDescription(strings.favorites.removeFavorite).performClick()

        assertEquals(1, openCount)
        assertEquals(1, removeCount)
        assertEquals(EXPECTED_RIBBON_ROTATION_DEGREES, EVENT_ENDED_RIBBON_ROTATION_DEGREES)
    }

    @Test
    fun exploreCard_aggregatesVisualContentWithoutDuplicatingImageOrTitleSemantics() {
        setExploreCardContent()

        composeRule.onAllNodesWithContentDescription(EXPLORE_OPEN_DESCRIPTION).assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription(CARD_IMAGE_ALT).assertCountEquals(0)
        composeRule.onAllNodesWithText(CARD_TITLE).assertCountEquals(0)
        composeRule.onAllNodesWithText(CARD_TITLE, useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription(strings.favorite).assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription(strings.like).assertCountEquals(1)
    }

    private fun setFavoriteCardContent(onOpen: () -> Unit, onRemove: () -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activityController = controller
        controller.get().setContent {
            KwaborTheme {
                ListingCard(
                    state = ListingCardState(
                        title = CARD_TITLE,
                        cityLabel = CARD_CITY,
                        price = null,
                        favorited = true,
                        eventEnded = true,
                    ),
                    strings = strings,
                    mediaUrlPolicy = ListingMediaUrlPolicy { null },
                    actions = ListingCardActions(
                        onClick = onOpen,
                        onFavoriteClick = onRemove,
                        favoriteLabel = strings.favorites.removeFavorite,
                        openAccessibilityDescription = OPEN_DESCRIPTION,
                    ),
                )
            }
        }
    }

    private fun setExploreCardContent() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activityController = controller
        controller.get().setContent {
            KwaborTheme {
                ListingCard(
                    state = ListingCardState(
                        title = CARD_TITLE,
                        cityLabel = CARD_CITY,
                        coverImageUrl = CARD_IMAGE_URL,
                        coverImageAlt = CARD_IMAGE_ALT,
                        price = null,
                    ),
                    strings = strings,
                    mediaUrlPolicy = ListingMediaUrlPolicy { candidate -> candidate },
                    actions = ListingCardActions(
                        onClick = {},
                        onLikeClick = {},
                        onFavoriteClick = {},
                        openAccessibilityDescription = EXPLORE_OPEN_DESCRIPTION,
                    ),
                )
            }
        }
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertTraversalIndex(
    expected: Float,
): androidx.compose.ui.test.SemanticsNodeInteraction = assert(
    SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, expected),
)

private const val TEST_SDK = 35
private const val OPEN_TRAVERSAL_INDEX = 0f
private const val ENDED_TRAVERSAL_INDEX = 0.5f
private const val REMOVE_TRAVERSAL_INDEX = 1f
private const val EXPECTED_RIBBON_ROTATION_DEGREES = 45f
private const val CARD_TITLE = "Festival des masques"
private const val CARD_CITY = "Porto-Novo"
private const val OPEN_DESCRIPTION = "Ouvrir la fiche. Festival des masques. Porto-Novo"
private const val EXPLORE_OPEN_DESCRIPTION =
    "Festival des masques. Danseurs masqués sur la place. Porto-Novo. Gratuit"
private const val CARD_IMAGE_URL = "https://cdn.kwabor.example/festival.jpg"
private const val CARD_IMAGE_ALT = "Danseurs masqués sur la place"
