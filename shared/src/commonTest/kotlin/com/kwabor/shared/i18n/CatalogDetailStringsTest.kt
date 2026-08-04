package com.kwabor.shared.i18n

import com.kwabor.shared.domain.i18n.AppLocale
import kotlin.test.Test
import kotlin.test.assertEquals

class CatalogDetailStringsTest {
    @Test
    fun frenchCatalog_exposesNonTechnicalExternalActionLabelsAndMessages() {
        val detail = stringsFor(AppLocale.French).detail

        assertEquals("Itinéraire", detail.directions)
        assertEquals("Contacter", detail.contact)
        assertEquals("Appeler", detail.call)
        assertEquals("WhatsApp", detail.whatsapp)
        assertEquals("E-mail", detail.email)
        assertEquals("Site web", detail.website)
        assertEquals("Voir le menu", detail.menu)
        assertEquals("Acheter un billet", detail.ticket)
        assertEquals("Ouvre une application externe", detail.opensExternally)
        assertEquals("Impossible d’ouvrir cette action pour le moment.", detail.externalActionFailed)
        assertEquals("Aucun lien d'inscription disponible", detail.registrationUnavailable)
        assertEquals("Fermer", detail.dismiss)
    }
}
