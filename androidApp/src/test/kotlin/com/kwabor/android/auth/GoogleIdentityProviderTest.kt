package com.kwabor.android.auth

import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GoogleIdentityProviderTest {
    @Test
    fun secureNonceIsUrlSafeUniqueAndContainsThirtyTwoRandomBytes() {
        val generator = SecureGoogleNonceGenerator()

        val first = generator.generate()
        val second = generator.generate()

        assertNotEquals(first, second)
        assertEquals(EXPECTED_NONCE_BYTES, Base64.getUrlDecoder().decode(first).size)
        assertTrue(first.matches(URL_SAFE_NONCE_PATTERN))
    }

    @Test
    fun sha256HexMatchesKnownVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            "abc".sha256Hex(),
        )
    }

    @Test
    fun webClientIdValidationFailsClosed() {
        assertTrue(isValidGoogleWebClientId("123-example.apps.googleusercontent.com"))
        assertFalse(isValidGoogleWebClientId(""))
        assertFalse(isValidGoogleWebClientId("android-client-id"))
        assertFalse(isValidGoogleWebClientId("123-example.apps.googleusercontent.com.attacker.test"))
    }

    @Test
    fun successfulCredentialStringRedactsTokenAndNonce() {
        val result = GoogleIdentityResult.Success(
            idToken = "sensitive-id-token",
            nonce = "sensitive-raw-nonce",
            profileHint = GoogleProfileHint(firstName = "Afi", lastName = "Soglo"),
        )

        assertFalse(result.toString().contains("sensitive-id-token"))
        assertFalse(result.toString().contains("sensitive-raw-nonce"))
        assertFalse(result.toString().contains("Afi"))
        assertFalse(result.toString().contains("Soglo"))
    }

    @Test
    fun explicitButtonUsesSignInWithGoogleCredentialOption() {
        assertEquals(GetSignInWithGoogleOption::class, explicitGoogleCredentialOptionType)
    }

    @Test
    fun profileHintUsesStructuredNamesThenFallsBackToDisplayName() {
        val structured = googleProfileHint(" Afi ", " Soglo ", "Ignored Name")
        val fallback = googleProfileHint(null, null, "Afi Soglo")

        assertEquals("Afi", structured.firstName)
        assertEquals("Soglo", structured.lastName)
        assertEquals("Afi", fallback.firstName)
        assertEquals("Soglo", fallback.lastName)
        assertFalse(fallback.toString().contains("Afi"))
    }

    @Test
    fun lifecycleReferenceReleasesDestroyedHostWithoutDetachingReplacement() {
        val reference = LifecycleBoundReference<Any>()
        val destroyedActivity = Any()
        val currentActivity = Any()

        reference.attach(destroyedActivity)
        reference.attach(currentActivity)
        reference.detach(destroyedActivity)

        assertTrue(reference.current() === currentActivity)

        reference.detach(currentActivity)

        assertEquals(null, reference.current())
    }
}

private const val EXPECTED_NONCE_BYTES = 32
private val URL_SAFE_NONCE_PATTERN = Regex("[A-Za-z0-9_-]+")
