package com.kwabor.android.ui.screens.guide

import androidx.compose.ui.unit.dp
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.money.MoneyXof
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.guide.GuideDiscoveryUiState
import com.kwabor.shared.presentation.guide.GuideSummaryUiModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuideDiscoveryScreenPolicyTest {
    private val strings = stringsFor(AppLocale.French)

    @Test
    fun liveRegionStatus_exposesOnlyActionableAsynchronousStates() {
        val idle = GuideDiscoveryUiState(isLoading = false)

        assertNull(guideDiscoveryLiveRegionStatus(idle, strings))
        assertEquals(
            strings.loading,
            guideDiscoveryLiveRegionStatus(idle.copy(isLoading = true), strings),
        )
        assertEquals(
            "${strings.errorStateTitle}. ${strings.guideDiscovery.loadFailed}",
            guideDiscoveryLiveRegionStatus(
                idle.copy(errorMessage = strings.guideDiscovery.loadFailed),
                strings,
            ),
        )
        assertEquals(
            strings.loading,
            guideDiscoveryLiveRegionStatus(idle.copy(isAppending = true), strings),
        )
        assertEquals(
            "${strings.errorStateTitle}. ${strings.guideDiscovery.loadMoreFailed}",
            guideDiscoveryLiveRegionStatus(
                idle.copy(appendErrorMessage = strings.guideDiscovery.loadMoreFailed),
                strings,
            ),
        )
    }

    @Test
    fun guideCardDescription_exposesPhotoFacetsRatingPriceAndAction() {
        val model = guideSummary()

        val description = guideCardAccessibilityDescription(model, strings)

        listOf(
            strings.guideDiscovery.openGuideLabel,
            model.title,
            model.coverImageAlt,
            strings.detail.verified,
            strings.guideDiscovery.languagesLabel,
            model.languages.single(),
            strings.guideDiscovery.coveredCitiesLabel,
            model.coverageCities.single(),
            strings.guideDiscovery.specialtiesLabel,
            model.specialties.single(),
            strings.detail.rating,
            strings.guideDiscovery.indicativePriceLabel,
        ).forEach { expected ->
            assertTrue(expected in description)
        }
    }

    @Test
    fun columnPolicy_keepsOneMobileCardAndTwoTabletCards() {
        assertEquals(1, guideDiscoveryColumnCount(599.dp))
        assertEquals(2, guideDiscoveryColumnCount(600.dp))
    }
}

private fun guideSummary(): GuideSummaryUiModel = GuideSummaryUiModel(
    id = "00000000-0000-4000-8000-000000000001",
    title = "Afi, guide à Ouidah",
    baseCityLabel = "Cotonou",
    coverImageUrl = "https://cdn.kwabor.example/guides/afi.jpg",
    coverImageAlt = "Afi devant la Porte du non-retour",
    languages = listOf("Français"),
    coverageCities = listOf("Ouidah"),
    specialties = listOf("Histoire"),
    indicativePrice = testMoney(),
    ratingLabel = "4,8",
    ratingCount = 12,
    verified = true,
)

private fun testMoney(): MoneyXof = when (val result = MoneyXof.fromAmount(TEST_PRICE_XOF)) {
    is DomainResult.Success -> result.value
    is DomainResult.Failure -> error("The fixed non-negative test price must be valid.")
}

private const val TEST_PRICE_XOF = 15_000L
