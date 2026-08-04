package com.kwabor.android.detail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DetailExternalActionPolicyTest {
    @Test
    fun directions_buildsTheOfficialGoogleMapsHttpsUrlWithoutLeakingTheLabel() {
        val action = DetailExternalAction.Directions(
            latitude = 6.370293,
            longitude = 2.391236,
            label = "Fondation Zinsou",
        )

        assertEquals(
            DetailExternalIntentSpec(
                action = DetailExternalIntentAction.View,
                uri = "https://www.google.com/maps/dir/?api=1&destination=6.370293%2C2.391236",
            ),
            action.toIntentSpecOrNull(),
        )
    }

    @Test
    fun directions_acceptsWorldBoundsAndRejectsInvalidCoordinatesOrLabels() {
        assertTrue(directions(latitude = -90.0, longitude = -180.0).isAccepted())
        assertTrue(directions(latitude = 90.0, longitude = 180.0).isAccepted())

        listOf(
            directions(latitude = Double.NaN),
            directions(latitude = Double.POSITIVE_INFINITY),
            directions(latitude = 90.000_001),
            directions(longitude = -180.000_001),
            directions(label = ""),
            directions(label = " adresse"),
            directions(label = "adresse\nsecrète"),
            directions(label = "a".repeat(81)),
        ).forEach { action -> assertFalse(action.isAccepted()) }
    }

    @Test
    fun phone_usesActionDialAndAcceptsOnlyCanonicalBeninNumbers() {
        assertEquals(
            DetailExternalIntentSpec(DetailExternalIntentAction.Dial, "tel:+22912345"),
            DetailExternalAction.Phone("+22912345").toIntentSpecOrNull(),
        )
        assertTrue(DetailExternalAction.Phone("+229${"1".repeat(12)}").isAccepted())

        listOf(
            "+2291234",
            "+229${"1".repeat(13)}",
            "+22812345678",
            "+229 12345678",
            "+22912345;ext=1",
            "tel:+22912345678",
        ).forEach { number -> assertFalse(DetailExternalAction.Phone(number).isAccepted()) }
    }

    @Test
    fun whatsapp_usesWaMeHttpsAndRejectsInjectedNumbers() {
        assertEquals(
            DetailExternalIntentSpec(DetailExternalIntentAction.View, "https://wa.me/22997000000"),
            DetailExternalAction.WhatsApp("+22997000000").toIntentSpecOrNull(),
        )

        listOf(
            "+22997000000?text=secret",
            "+22997000000#fragment",
            "+22997000000\nhttps://evil.test",
            "22997000000",
        ).forEach { number -> assertFalse(DetailExternalAction.WhatsApp(number).isAccepted()) }
    }

    @Test
    fun email_usesActionViewMailtoAndRejectsHeaderOrAuthorityInjection() {
        assertEquals(
            DetailExternalIntentSpec(DetailExternalIntentAction.View, "mailto:bonjour+guide@kwabor.bj"),
            DetailExternalAction.Email("bonjour+guide@kwabor.bj").toIntentSpecOrNull(),
        )

        listOf(
            "victime@kwabor.bj?bcc=evil@example.com",
            "victime@kwabor.bj%0abcc=evil@example.com",
            "victime@kwabor.bj@evil.test",
            ".victime@kwabor.bj",
            "victime..test@kwabor.bj",
            "victime@localhost",
            "victime@service.internal",
            "victime @kwabor.bj",
        ).forEach { address -> assertFalse(DetailExternalAction.Email(address).isAccepted()) }
    }

    @Test
    fun https_acceptsPublicLinksAndRejectsUnsafeSchemesAuthoritiesAndPorts() {
        listOf(
            "https://kwabor.bj",
            "https://tickets.kwabor.bj/event?id=42",
            "https://menu.kwabor.bj:443/restaurant/menu.pdf",
        ).forEach { url ->
            assertEquals(
                DetailExternalIntentSpec(DetailExternalIntentAction.View, url),
                DetailExternalAction.Https(url).toIntentSpecOrNull(),
            )
        }

        listOf(
            "http://kwabor.bj",
            "javascript:alert(1)",
            "HTTPS://kwabor.bj",
            "https://user@kwabor.bj",
            "https://kwabor.bj:444/path",
            "https://KWABOR.bj/path",
            "https://localhost/path",
            "https://127.0.0.1/path",
            "https://127.1/path",
            "https://0x7f.0x0.0x0.0x1/path",
            "https://[::1]/path",
            "https://router.local/path",
            "https://service.internal/path",
            "https://kwabor.bj/path#fragment",
            "https://kwabor.bj\\@evil.test",
            "https://kwabor.bj/%0aevil",
            "https://kwabor.bj/%5Cevil",
            "https://",
        ).forEach { url -> assertFalse(DetailExternalAction.Https(url).isAccepted()) }
    }

    @Test
    fun launcher_returnsTypedResultsAndNeverDispatchesRejectedActions() {
        var dispatchCount = 0
        var dispatchedSpec: DetailExternalIntentSpec? = null
        val openedLauncher = AndroidDetailExternalActionLauncher { spec ->
            dispatchCount += 1
            dispatchedSpec = spec
            true
        }

        assertEquals(
            DetailExternalActionResult.Opened,
            openedLauncher.launch(DetailExternalAction.Https("https://kwabor.bj/menu")),
        )
        assertEquals(1, dispatchCount)
        assertEquals("https://kwabor.bj/menu", dispatchedSpec?.uri)

        assertEquals(
            DetailExternalActionResult.Rejected,
            openedLauncher.launch(DetailExternalAction.Https("file:///data/local/private")),
        )
        assertEquals(1, dispatchCount)
    }

    @Test
    fun launcher_mapsAHandlerFailureToUnavailableWithoutAnExceptionPayload() {
        val launcher = AndroidDetailExternalActionLauncher { false }

        val result = launcher.launch(DetailExternalAction.Email("bonjour@kwabor.bj"))

        assertEquals(DetailExternalActionResult.Unavailable, result)
        assertNull(result::class.java.declaredFields.firstOrNull { field -> field.name == "cause" })
    }
}

private fun directions(
    latitude: Double = 6.370293,
    longitude: Double = 2.391236,
    label: String = "Destination",
): DetailExternalAction.Directions = DetailExternalAction.Directions(
    latitude = latitude,
    longitude = longitude,
    label = label,
)

private fun DetailExternalAction.isAccepted(): Boolean = toIntentSpecOrNull() != null
