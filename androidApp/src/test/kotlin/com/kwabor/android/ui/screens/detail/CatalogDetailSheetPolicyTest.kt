package com.kwabor.android.ui.screens.detail

import androidx.compose.ui.unit.dp
import com.kwabor.android.media.PublicHttpsListingMediaUrlPolicy
import com.kwabor.shared.presentation.detail.CatalogDetailContentUiModel
import com.kwabor.shared.presentation.detail.CatalogDetailLocationUiModel
import com.kwabor.shared.presentation.detail.CatalogDetailMediaUiModel
import com.kwabor.shared.presentation.detail.CatalogDetailMetricsUiModel
import com.kwabor.shared.presentation.detail.CatalogDetailPriceUiModel
import com.kwabor.shared.presentation.detail.CatalogDetailTicketingUiModel
import com.kwabor.shared.presentation.detail.CatalogDetailUiModel
import com.kwabor.shared.presentation.detail.CatalogDetailUiState
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogDetailSheetPolicyTest {
    @Test
    fun sheetAndHeroFractions_matchTheMobileAndTabletDesignContract() {
        assertEquals(DETAIL_MOBILE_SHEET_HEIGHT_FRACTION, detailSheetHeightFraction(maxWidthDp = 599f))
        assertEquals(DETAIL_TABLET_SHEET_HEIGHT_FRACTION, detailSheetHeightFraction(maxWidthDp = 600f))
        assertTrue(DETAIL_HERO_HEIGHT_FRACTION in 0.55f..0.60f)
        assertEquals(320.dp, detailHeroHeight(400.dp))
        assertEquals(464.dp, detailHeroHeight(800.dp))
    }

    @Test
    fun descriptionPreview_countsUnicodeCodePointsWithoutSplittingEmoji() {
        val prefix = "a".repeat(DETAIL_DESCRIPTION_PREVIEW_CODE_POINTS - 1)
        val description = prefix + "🐕" + "fin"

        assertTrue(shouldOfferDescriptionExpansion(description))
        assertEquals(prefix + "🐕…", detailDescription(description, expanded = false))
        assertEquals(description, detailDescription(description, expanded = true))
        assertFalse(shouldOfferDescriptionExpansion("Description courte"))
    }

    @Test
    fun descriptionPreview_doesNotSplitAJoinedEmojiGrapheme() {
        val prefix = "mot ".repeat(37)
        val description = prefix + "👩‍👩‍👧‍👦 puis une fin suffisamment longue"

        assertEquals(prefix.trimEnd() + "…", detailDescription(description, expanded = false))
    }

    @Test
    fun countLabels_useTheFrenchSingularOnlyForOne() {
        assertEquals("0 vues", detailCountLabel(0, "vue", "vues"))
        assertEquals("1 vue", detailCountLabel(1, "vue", "vues"))
        assertEquals("2 vues", detailCountLabel(2, "vue", "vues"))
    }

    @Test
    fun officialMediaPolicy_filtersUnsafeUrlsAndPreservesSourceSelection() {
        val duplicateSafeUrl = "https://cdn.kwabor.test/detail.jpg"
        val media = listOf(
            detailMedia(duplicateSafeUrl),
            detailMedia("http://cdn.kwabor.test/rejected.jpg"),
            detailMedia(duplicateSafeUrl),
        )

        val visible = visibleOfficialImages(media, PublicHttpsListingMediaUrlPolicy)

        assertEquals(listOf(0, 2), visible.map(VisibleCatalogDetailMedia::sourceIndex))
        assertEquals(1, visibleMediaSelectionIndex(visible, requestedSourceIndex = 2))
        assertEquals(0, visibleMediaSelectionIndex(visible, requestedSourceIndex = 1))
    }

    @Test
    fun announcements_coverEveryAsynchronousStateAndEndedEvents() {
        assertNull(CatalogDetailUiState.Closed.announcementOrNull())
        assertEquals(
            CatalogDetailAnnouncement.Loading,
            CatalogDetailUiState.Loading(TEST_LISTING_ID).announcementOrNull(),
        )
        assertEquals(
            CatalogDetailAnnouncement.NotFound,
            CatalogDetailUiState.NotFound(TEST_LISTING_ID, "indisponible").announcementOrNull(),
        )
        assertEquals(
            CatalogDetailAnnouncement.Offline,
            CatalogDetailUiState.OfflineFailure(TEST_LISTING_ID, "hors ligne").announcementOrNull(),
        )
        assertEquals(
            CatalogDetailAnnouncement.Failure,
            CatalogDetailUiState.Failure(TEST_LISTING_ID, "échec").announcementOrNull(),
        )
        assertEquals(
            CatalogDetailAnnouncement.EventEnded,
            detailContentState(endedEventContent()).announcementOrNull(),
        )
        assertNull(detailContentState(placeContent()).announcementOrNull())
    }

    @Test
    fun actionContract_exposesOnlyInteractionsImplementedByThisDetailSlice() {
        val instanceFields = CatalogDetailSheetActions::class.java.declaredFields
            .filterNot { field -> field.isSynthetic || Modifier.isStatic(field.modifiers) }
            .map { field -> field.name }
            .toSet()

        assertEquals(
            setOf("onDismiss", "onRetry", "onMediaSelected", "onDescriptionToggle"),
            instanceFields,
        )
    }
}

private fun detailMedia(url: String): CatalogDetailMediaUiModel = CatalogDetailMediaUiModel(
    url = url,
    alt = "Photo officielle",
    isCover = false,
)

private fun detailContentState(content: CatalogDetailContentUiModel): CatalogDetailUiState.Content =
    CatalogDetailUiState.Content(
        model = CatalogDetailUiModel(
            id = TEST_LISTING_ID,
            title = "Fiche",
            contextLabel = "Cotonou · Culture",
            description = "Description",
            verified = true,
            isClaimable = false,
            media = emptyList(),
            metrics = CatalogDetailMetricsUiModel(null, 0, 0, 0),
            price = CatalogDetailPriceUiModel(null, null, null),
            openingStatusLabel = null,
            openingHours = emptyList(),
            amenities = emptyList(),
            location = CatalogDetailLocationUiModel("Cotonou", null, null, null, null),
            tags = emptyList(),
            content = content,
        ),
        selectedMediaIndex = 0,
    )

private fun endedEventContent(): CatalogDetailContentUiModel.Event = CatalogDetailContentUiModel.Event(
    heading = "Événement",
    startsAtLabel = "02/08/2026 · 18:00",
    endsAtLabel = "02/08/2026 · 20:00",
    venueLabel = "Cotonou",
    organizerLabel = "Kwabor",
    capacityLabel = null,
    ticketing = CatalogDetailTicketingUiModel.Free(registrationAvailable = false),
    isEnded = true,
)

private fun placeContent(): CatalogDetailContentUiModel.Place = CatalogDetailContentUiModel.Place(
    heading = "Lieu",
    placeCategoryLabel = "Historique",
    entryFee = null,
    feeNote = null,
)

private const val TEST_LISTING_ID = "00000000-0000-4000-8000-000000000001"
