package com.kwabor.shared.domain.auth

import kotlin.test.Test
import kotlin.test.assertFalse

class SensitiveAuthRequestTest {
    @Test
    fun socialSignInRequestDoesNotExposeIdTokenThroughToString() {
        val idToken = "header.payload.signature"
        val request = SocialSignInRequest(
            provider = SocialAuthProvider.Google,
            idToken = idToken,
        )

        assertFalse(request.toString().contains(idToken))
    }

    @Test
    fun promoterActivationRequestDoesNotExposeAnyCredentialThroughToString() {
        val inviteToken = "invite-secret"
        val password = "password-secret"
        val idToken = "social-secret"
        val request = PromoterActivationRequest(
            inviteToken = inviteToken,
            password = password,
            socialSignInRequest = SocialSignInRequest(
                provider = SocialAuthProvider.Apple,
                idToken = idToken,
            ),
        )
        val representation = request.toString()

        assertFalse(representation.contains(inviteToken))
        assertFalse(representation.contains(password))
        assertFalse(representation.contains(idToken))
    }
}
