package com.kwabor.android.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegalDocumentUrlPolicyTest {
    @Test
    fun policyRejectsNonHttpsCredentialedAndMalformedUrls() {
        assertFalse("http://kwabor.test/legal".isSafeHttpsLegalDocumentUrl())
        assertFalse("https://user@kwabor.test/legal".isSafeHttpsLegalDocumentUrl())
        assertFalse("https://kwabor.test/legal path".isSafeHttpsLegalDocumentUrl())
        assertFalse("https:///legal".isSafeHttpsLegalDocumentUrl())
    }

    @Test
    fun policyAcceptsAnHttpsUrlWithAHost() {
        assertTrue("https://kwabor.test/legal/privacy".isSafeHttpsLegalDocumentUrl())
    }
}
