package com.kwabor.android.ui.screens.detail

import com.kwabor.android.detail.DetailExternalActionResult
import com.kwabor.shared.presentation.detail.CatalogDetailContactUiModel
import com.kwabor.shared.presentation.detail.CatalogDetailContentUiModel
import com.kwabor.shared.presentation.detail.CatalogDetailDirectionsUiModel
import com.kwabor.shared.presentation.detail.CatalogDetailLocationUiModel
import com.kwabor.shared.presentation.detail.CatalogDetailMetricsUiModel
import com.kwabor.shared.presentation.detail.CatalogDetailPriceUiModel
import com.kwabor.shared.presentation.detail.CatalogDetailTicketingUiModel
import com.kwabor.shared.presentation.detail.CatalogDetailUiModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogDetailExternalActionPolicyTest {
    @Test
    fun place_exposesOnlyAValidatedPrimaryDirectionsAction() {
        val actions = detailModel(
            content = placeContent(),
            directions = validDirections(),
            contact = validContact(),
        ).toExternalActionUiModel()

        val primary = assertIs<CatalogDetailPrimaryExternalAction.Directions>(actions.primary)
        assertEquals("Porte du non-retour", primary.action.label)
        assertNull(actions.secondaryDirections)
        assertNull(actions.contact)
        assertNull(actions.menu)
    }

    @Test
    fun place_hidesDirectionsWhenTheLocalLauncherPolicyRejectsTheTarget() {
        val actions = detailModel(
            content = placeContent(),
            directions = validDirections().copy(latitude = Double.NaN),
        ).toExternalActionUiModel()

        assertNull(actions.primary)
        assertNull(actions.secondaryDirections)
    }

    @Test
    fun everyEstablishmentVariant_usesContactAsPrimaryAndDirectionsAsSecondary() {
        establishmentContents().forEach { content ->
            val actions = detailModel(
                content = content,
                directions = validDirections(),
                contact = validContact(),
            ).toExternalActionUiModel()

            assertIs<CatalogDetailPrimaryExternalAction.Contact>(actions.primary)
            assertNotNull(actions.contact)
            assertNotNull(actions.secondaryDirections)
        }
    }

    @Test
    fun contact_hidesEveryRejectedChannelWithoutDroppingAcceptedChannels() {
        val actions = detailModel(
            content = foodContent(menuUrl = "https://kwabor.bj/menu"),
            contact = CatalogDetailContactUiModel(
                phoneNumber = "+2290197000000",
                whatsappNumber = "+22897000000",
                websiteUrl = "http://kwabor.bj",
                emailAddress = "bonjour@kwabor.bj",
            ),
        ).toExternalActionUiModel()

        val contact = assertNotNull(actions.contact)
        assertNotNull(contact.phone)
        assertNull(contact.whatsapp)
        assertNull(contact.website)
        assertNotNull(contact.email)
        assertEquals("https://kwabor.bj/menu", assertNotNull(actions.menu).url)
    }

    @Test
    fun establishment_hidesContactCtaWhenAllChannelsAreRejected() {
        val actions = detailModel(
            content = foodContent(menuUrl = null),
            directions = validDirections(),
            contact = CatalogDetailContactUiModel(
                phoneNumber = "0197000000",
                whatsappNumber = "+22897000000",
                websiteUrl = "file:///data/private",
                emailAddress = "not-an-email",
            ),
        ).toExternalActionUiModel()

        assertNull(actions.primary)
        assertNull(actions.contact)
        assertNotNull(actions.secondaryDirections)
    }

    @Test
    fun activeEvent_exposesRedTicketContractAndSecondaryDirectionsWithoutContact() {
        val actions = detailModel(
            content = eventContent(isEnded = false),
            directions = validDirections(),
            contact = validContact(),
        ).toExternalActionUiModel()

        val ticket = assertIs<CatalogDetailPrimaryExternalAction.Ticket>(actions.primary)
        assertTrue(ticket.enabled)
        assertEquals("https://tickets.kwabor.bj/event", ticket.action.url)
        assertNotNull(actions.secondaryDirections)
        assertNull(actions.contact)
    }

    @Test
    fun endedEvent_keepsSafeTicketVisibleButDisabledAndKeepsDirections() {
        val actions = detailModel(
            content = eventContent(isEnded = true),
            directions = validDirections(),
        ).toExternalActionUiModel()

        val ticket = assertIs<CatalogDetailPrimaryExternalAction.Ticket>(actions.primary)
        assertFalse(ticket.enabled)
        assertNotNull(actions.secondaryDirections)
    }

    @Test
    fun event_hidesTicketWhenNoSafeExternalUrlExists() {
        val actions = detailModel(
            content = eventContent(
                isEnded = false,
                ticketing = CatalogDetailTicketingUiModel.Free(externalUrl = null),
            ),
            directions = validDirections(),
        ).toExternalActionUiModel()

        assertNull(actions.primary)
        assertNotNull(actions.secondaryDirections)
    }

    @Test
    fun event_hidesTicketWhenTheExternalUrlIsNotPublicHttps() {
        val actions = detailModel(
            content = eventContent(
                isEnded = false,
                ticketing = CatalogDetailTicketingUiModel.Free(externalUrl = "http://tickets.kwabor.bj/event"),
            ),
            directions = validDirections(),
        ).toExternalActionUiModel()

        assertNull(actions.primary)
        assertNotNull(actions.secondaryDirections)
    }

    @Test
    fun rejectedAndUnavailableLaunches_shareTheSameGenericErrorContract() {
        assertFalse(DetailExternalActionResult.Opened.shouldShowGenericError)
        assertTrue(DetailExternalActionResult.Rejected.shouldShowGenericError)
        assertTrue(DetailExternalActionResult.Unavailable.shouldShowGenericError)
    }
}

private fun detailModel(
    content: CatalogDetailContentUiModel,
    directions: CatalogDetailDirectionsUiModel? = null,
    contact: CatalogDetailContactUiModel? = null,
): CatalogDetailUiModel = CatalogDetailUiModel(
    id = "detail-actions",
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
    directions = directions,
    contact = contact,
    tags = emptyList(),
    content = content,
)

private fun validDirections(): CatalogDetailDirectionsUiModel = CatalogDetailDirectionsUiModel(
    latitude = 6.370293,
    longitude = 2.391236,
    label = "Porte du non-retour",
)

private fun validContact(): CatalogDetailContactUiModel = CatalogDetailContactUiModel(
    phoneNumber = "+2290197000000",
    whatsappNumber = "+2290197000000",
    websiteUrl = "https://kwabor.bj",
    emailAddress = "bonjour@kwabor.bj",
)

private fun establishmentContents(): List<CatalogDetailContentUiModel> = listOf(
    CatalogDetailContentUiModel.Lodging("Hébergement", emptyList(), emptyList()),
    foodContent(menuUrl = null),
    CatalogDetailContentUiModel.Nightlife("Vie nocturne", emptyList()),
    CatalogDetailContentUiModel.Guide(
        heading = "Guide",
        languages = emptyList(),
        zones = emptyList(),
        specialties = emptyList(),
        facts = emptyList(),
        indicativePrice = null,
    ),
)

private fun placeContent(): CatalogDetailContentUiModel.Place = CatalogDetailContentUiModel.Place(
    heading = "Lieu",
    placeCategoryLabel = "Historique",
    entryFee = null,
    feeNote = null,
)

private fun foodContent(menuUrl: String?): CatalogDetailContentUiModel.Food = CatalogDetailContentUiModel.Food(
    heading = "Restaurant",
    cuisines = emptyList(),
    meals = emptyList(),
    reservationLabel = "Réservations acceptées",
    menuUrl = menuUrl,
)

private fun eventContent(
    isEnded: Boolean,
    ticketing: CatalogDetailTicketingUiModel = CatalogDetailTicketingUiModel.Paid(
        externalUrl = "https://tickets.kwabor.bj/event",
        tiers = emptyList(),
    ),
): CatalogDetailContentUiModel.Event = CatalogDetailContentUiModel.Event(
    heading = "Événement",
    startsAtLabel = "03/08/2026 · 18:00",
    endsAtLabel = "03/08/2026 · 20:00",
    venueLabel = "Cotonou",
    organizerLabel = "Kwabor",
    capacityLabel = null,
    ticketing = ticketing,
    isEnded = isEnded,
)
