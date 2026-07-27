package com.kwabor.shared.domain.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PromoterActivationDeepLinkTest {
    @Test
    fun parse_acceptsPkceAuthorizationCodeWithoutExposingIt() {
        val result = PromoterActivationDeepLinkParser.parse(
            "kwabor://auth/promoter-activate?token=$VALID_TOKEN&code=$VALID_CODE",
        )

        val accepted = assertIs<PromoterActivationDeepLinkResult.Accepted>(result)
        val proof = assertIs<PromoterActivationSessionProof.PkceCode>(accepted.sessionProof)
        assertEquals(VALID_TOKEN, accepted.inviteToken)
        assertEquals(VALID_CODE, proof.code)
    }

    @Test
    fun parse_rejectsImplicitAccessAndRefreshTokenFragment() {
        val result = PromoterActivationDeepLinkParser.parse(
            "kwabor://auth/promoter-activate?token=$VALID_TOKEN" +
                "#access_token=access-token&refresh_token=refresh-token&type=signup",
        )

        val rejected = assertIs<PromoterActivationDeepLinkResult.Rejected>(result)
        assertEquals(PromoterActivationDeepLinkRejection.Malformed, rejected.reason)
    }

    @Test
    fun parse_rejectsAccessTokensSmuggledAsQueryParameters() {
        val result = PromoterActivationDeepLinkParser.parse(
            "kwabor://auth/promoter-activate?token=$VALID_TOKEN&access_token=access-token",
        )

        val rejected = assertIs<PromoterActivationDeepLinkResult.Rejected>(result)
        assertEquals(PromoterActivationDeepLinkRejection.UnknownParameter, rejected.reason)
    }

    @Test
    fun parse_keepsTokenOnlyLinksForAnAlreadyEstablishedSession() {
        val result = PromoterActivationDeepLinkParser.parse(
            "kwabor://auth/promoter-activate?token=$VALID_TOKEN",
        )

        val accepted = assertIs<PromoterActivationDeepLinkResult.Accepted>(result)
        assertIs<PromoterActivationSessionProof.ExistingSession>(accepted.sessionProof)
    }

    @Test
    fun parse_decodesOnlyUnreservedPkceCharacters() {
        val result = PromoterActivationDeepLinkParser.parse(
            "kwabor://auth/promoter-activate?token=$VALID_TOKEN&code=${"b".repeat(20)}%2Dsafe",
        )

        val accepted = assertIs<PromoterActivationDeepLinkResult.Accepted>(result)
        val proof = assertIs<PromoterActivationSessionProof.PkceCode>(accepted.sessionProof)
        assertEquals("${"b".repeat(20)}-safe", proof.code)
    }

    @Test
    fun parse_rejectsEncodedReservedPkceCharacters() {
        val result = PromoterActivationDeepLinkParser.parse(
            "kwabor://auth/promoter-activate?token=$VALID_TOKEN&code=${"b".repeat(20)}%2Funsafe",
        )

        val rejected = assertIs<PromoterActivationDeepLinkResult.Rejected>(result)
        assertEquals(PromoterActivationDeepLinkRejection.Malformed, rejected.reason)
    }

    @Test
    fun parse_rejectsDuplicateParameters() {
        val result = PromoterActivationDeepLinkParser.parse(
            "kwabor://auth/promoter-activate?token=$VALID_TOKEN&token=$VALID_TOKEN",
        )

        val rejected = assertIs<PromoterActivationDeepLinkResult.Rejected>(result)
        assertEquals(PromoterActivationDeepLinkRejection.DuplicateParameter, rejected.reason)
    }

    @Test
    fun parse_preservesHostAndPathRejectionReasons() {
        val unsupportedHost = PromoterActivationDeepLinkParser.parse(
            "kwabor://attacker/promoter-activate?token=$VALID_TOKEN",
        )
        val unsupportedPath = PromoterActivationDeepLinkParser.parse(
            "kwabor://auth/other?token=$VALID_TOKEN",
        )

        assertEquals(
            PromoterActivationDeepLinkRejection.UnsupportedHost,
            assertIs<PromoterActivationDeepLinkResult.Rejected>(unsupportedHost).reason,
        )
        assertEquals(
            PromoterActivationDeepLinkRejection.UnsupportedPath,
            assertIs<PromoterActivationDeepLinkResult.Rejected>(unsupportedPath).reason,
        )
    }
}

private val VALID_TOKEN = "a".repeat(64)
private val VALID_CODE = "b".repeat(32)
