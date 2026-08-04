package com.kwabor.shared.i18n

import com.kwabor.shared.domain.i18n.AppLocale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GuideDiscoveryStringsTest {
    @Test
    fun frenchCatalogExposesUserFacingGuideDiscoveryCopy() {
        val strings = stringsFor(AppLocale.French).guideDiscovery

        assertEquals("Trouver un guide", strings.title)
        assertEquals("Ville", strings.cityFilter)
        assertEquals("Langue", strings.languageFilter)
        assertEquals("Spécialité", strings.specialtyFilter)
        assertEquals("Aucun guide pour ces critères", strings.emptyTitle)
        assertEquals("Voir le service de guide", strings.openGuideLabel)
        assertTrue("{count}" in strings.manyResults)
    }
}
