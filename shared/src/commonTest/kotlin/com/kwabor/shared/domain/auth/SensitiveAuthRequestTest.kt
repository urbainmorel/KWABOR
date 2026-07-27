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
            rawNonce = "abcdefghijklmnopqrstuvwxyzABCDEF",
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
                rawNonce = "abcdefghijklmnopqrstuvwxyzABCDEF",
            ),
        )
        val representation = request.toString()

        assertFalse(representation.contains(inviteToken))
        assertFalse(representation.contains(password))
        assertFalse(representation.contains(idToken))
    }

    @Test
    fun promoterActivationContextDoesNotExposeTokenIdentifiersOrBusinessName() {
        val context = PromoterActivationContext(
            inviteToken = "invite-secret",
            organizationId = "organization-secret",
            listingId = "listing-secret",
            businessName = "Entreprise individuelle sensible",
        )
        val representation = context.toString()

        assertFalse(representation.contains(context.inviteToken))
        assertFalse(representation.contains(context.organizationId))
        assertFalse(representation.contains(context.listingId))
        assertFalse(representation.contains(context.businessName))
    }

    @Test
    fun accountDeletionRequestDoesNotExposeIdempotencyOrPassword() {
        val request = AccountDeletionRequest(
            idempotencyKey = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            credential = AccountDeletionCredential.Password("password-secret"),
        )
        val representation = request.toString()

        assertFalse(representation.contains(request.idempotencyKey))
        assertFalse(representation.contains("password-secret"))
    }
}
