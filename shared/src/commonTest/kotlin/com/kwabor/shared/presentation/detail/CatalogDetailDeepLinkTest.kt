package com.kwabor.shared.presentation.detail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class CatalogDetailDeepLinkTest {
    @Test
    fun parse_acceptsRfcUuidVersionsOneThroughFive() {
        VALID_UUIDS.forEach { listingId ->
            val result = CatalogDetailDeepLinkParser.parse("kwabor://listing/$listingId")

            assertEquals(
                listingId,
                assertIs<CatalogDetailDeepLinkResult.Accepted>(result).listingId,
            )
        }
    }

    @Test
    fun parse_normalizesCaseInsensitiveSchemeHostAndUuid() {
        val result = CatalogDetailDeepLinkParser.parse("KWABOR://LISTING/${VALID_UUID_V4.uppercase()}")

        assertEquals(
            VALID_UUID_V4,
            assertIs<CatalogDetailDeepLinkResult.Accepted>(result).listingId,
        )
    }

    @Test
    fun parse_rejectsUnsupportedSchemeAndHost() {
        assertRejected(
            rawUrl = "https://listing/$VALID_UUID_V4",
            expectedReason = CatalogDetailDeepLinkRejection.UnsupportedScheme,
        )
        assertRejected(
            rawUrl = "kwabor://app/$VALID_UUID_V4",
            expectedReason = CatalogDetailDeepLinkRejection.UnsupportedHost,
        )
    }

    @Test
    fun parse_rejectsUserInfoAndPort() {
        listOf(
            "kwabor://user@listing/$VALID_UUID_V4",
            "kwabor://listing:443/$VALID_UUID_V4",
        ).forEach { rawUrl ->
            assertRejected(
                rawUrl = rawUrl,
                expectedReason = CatalogDetailDeepLinkRejection.UnsupportedHost,
            )
        }
    }

    @Test
    fun parse_rejectsQueryFragmentExtraPathAndWhitespace() {
        listOf(
            "kwabor://listing/$VALID_UUID_V4?source=share",
            "kwabor://listing/$VALID_UUID_V4#section",
            "kwabor://listing/$VALID_UUID_V4/extra",
            " kwabor://listing/$VALID_UUID_V4",
            "kwabor://listing/$VALID_UUID_V4 ",
            "kwabor://listing/${VALID_UUID_V4.substring(0, 18)} ${VALID_UUID_V4.substring(19)}",
        ).forEach { rawUrl ->
            val result = CatalogDetailDeepLinkParser.parse(rawUrl)

            assertIs<CatalogDetailDeepLinkResult.Rejected>(result)
        }
    }

    @Test
    fun parse_rejectsMalformedAndOverlongUrls() {
        listOf(
            "",
            "kwabor:/listing/$VALID_UUID_V4",
            "kwabor://listing/",
            "kwabor://listing/$VALID_UUID_V4${"a".repeat(2_048)}",
        ).forEach { rawUrl ->
            assertRejected(
                rawUrl = rawUrl,
                expectedReason = CatalogDetailDeepLinkRejection.Malformed,
            )
        }
    }

    @Test
    fun parse_rejectsNonRfcOrNonCanonicalUuids() {
        listOf(
            "00000000-0000-0000-0000-000000000000",
            "123e4567-e89b-02d3-a456-426614174000",
            "123e4567-e89b-62d3-a456-426614174000",
            "123e4567-e89b-42d3-7456-426614174000",
            "123e4567-e89b-42d3-c456-426614174000",
            "123e4567e89b42d3a456426614174000",
            "{123e4567-e89b-42d3-a456-426614174000}",
        ).forEach { listingId ->
            assertRejected(
                rawUrl = "kwabor://listing/$listingId",
                expectedReason = CatalogDetailDeepLinkRejection.InvalidListingId,
            )
        }
    }

    @Test
    fun generate_returnsOnlyCanonicalRoundTrippableLinks() {
        val generated = CatalogDetailDeepLinkGenerator.generate(VALID_UUID_V4.uppercase())

        assertEquals("kwabor://listing/$VALID_UUID_V4", generated)
        assertEquals(
            VALID_UUID_V4,
            assertIs<CatalogDetailDeepLinkResult.Accepted>(
                CatalogDetailDeepLinkParser.parse(requireNotNull(generated)),
            ).listingId,
        )
    }

    @Test
    fun generate_failsClosedForInvalidIdentifiers() {
        assertNull(CatalogDetailDeepLinkGenerator.generate(" $VALID_UUID_V4"))
        assertNull(CatalogDetailDeepLinkGenerator.generate("not-a-uuid"))
        assertNull(CatalogDetailDeepLinkGenerator.generate("123e4567-e89b-62d3-a456-426614174000"))
    }

    private fun assertRejected(rawUrl: String, expectedReason: CatalogDetailDeepLinkRejection) {
        val result = assertIs<CatalogDetailDeepLinkResult.Rejected>(CatalogDetailDeepLinkParser.parse(rawUrl))

        assertEquals(expectedReason, result.reason)
    }
}

private const val VALID_UUID_V4 = "123e4567-e89b-42d3-a456-426614174000"
private val VALID_UUIDS = listOf(
    "123e4567-e89b-12d3-a456-426614174000",
    "123e4567-e89b-22d3-a456-426614174000",
    "123e4567-e89b-32d3-a456-426614174000",
    VALID_UUID_V4,
    "123e4567-e89b-52d3-a456-426614174000",
)
