package com.kwabor.android.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ListingMediaUrlPolicyTest {
    private val policy = PublicHttpsListingMediaUrlPolicy

    @Test
    fun policyAcceptsCanonicalPublicHttpsHostsAndEffectivePort() {
        assertEquals(
            "https://cdn.kwabor.test/media/card.jpg?width=720",
            policy.safeUrlOrNull("https://cdn.kwabor.test/media/card.jpg?width=720"),
        )
        assertEquals(
            "https://cdn.kwabor.test:443/media/card.jpg",
            policy.safeUrlOrNull("https://cdn.kwabor.test:443/media/card.jpg"),
        )
        assertEquals(
            "https://media.partner.test/card.jpg",
            policy.safeUrlOrNull("https://media.partner.test/card.jpg"),
        )
        assertNull(policy.safeUrlOrNull("https://cdn.kwabor.test:8443/media/card.jpg"))
        assertNull(policy.safeUrlOrNull("https://cdn.kwabor.test:0443/media/card.jpg"))
    }

    @Test
    fun policyRejectsNonHttpsCredentialedFragmentedAndMalformedUrls() {
        assertNull(policy.safeUrlOrNull("http://cdn.kwabor.test/media/card.jpg"))
        assertNull(policy.safeUrlOrNull("https://user@cdn.kwabor.test/media/card.jpg"))
        assertNull(policy.safeUrlOrNull("https://cdn.kwabor.test/media/card.jpg#fragment"))
        assertNull(policy.safeUrlOrNull("https://cdn.kwabor.test/media/card image.jpg"))
        assertNull(policy.safeUrlOrNull("https://cdn.kwabor.test\\media/card.jpg"))
        assertNull(policy.safeUrlOrNull("https:///media/card.jpg"))
        assertNull(policy.safeUrlOrNull("https://[::::]/media/card.jpg"))
        assertNull(policy.safeUrlOrNull("https://cdn.kwabor.test/media/%ZZ.jpg"))
        assertNull(policy.safeUrlOrNull("HTTPS://cdn.kwabor.test/media/card.jpg"))
    }

    @Test
    fun policyUsesTheServerUtf8ByteLimit() {
        val prefix = "https://cdn.kwabor.test/"
        val remainingBytes = MAXIMUM_URL_BYTES - prefix.encodeToByteArray().size
        val exactLimitUrl = prefix + "a".repeat(remainingBytes % EMOJI_UTF8_BYTES) +
            "🐕".repeat(remainingBytes / EMOJI_UTF8_BYTES)

        assertEquals(exactLimitUrl, policy.safeUrlOrNull(exactLimitUrl))
        assertNull(policy.safeUrlOrNull(exactLimitUrl + "🐕"))
        assertNull(policy.safeUrlOrNull("https://cdn.kwabor.test/" + "a".repeat(2_048)))
    }

    @Test
    fun policyRejectsPrivateIpUppercaseUnicodeAndNonCanonicalHosts() {
        val untrustedUrls = setOf(
            "https://localhost/card.jpg",
            "https://storage.internal/card.jpg",
            "https://127.0.0.1/card.jpg",
            "https://10.0.0.1/card.jpg",
            "https://media.foo.lan/card.jpg",
            "https://media.home.arpa/card.jpg",
            "https://home.arpa/card.jpg",
            "https://CDN.kwabor.test/card.jpg",
            "https://média.kwabor.test/card.jpg",
            "https://-cdn.kwabor.test/card.jpg",
            "https://cdn-.kwabor.test/card.jpg",
        )

        untrustedUrls.forEach { url ->
            assertNull(policy.safeUrlOrNull(url), url)
        }
    }

    @Test
    fun policyRejectsAbsentOrEmptyValues() {
        assertNull(policy.safeUrlOrNull(null))
        assertNull(policy.safeUrlOrNull(""))
    }
}

private const val MAXIMUM_URL_BYTES = 2_048
private const val EMOJI_UTF8_BYTES = 4
