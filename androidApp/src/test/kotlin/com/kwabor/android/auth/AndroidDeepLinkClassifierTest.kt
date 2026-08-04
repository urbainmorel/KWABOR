package com.kwabor.android.auth

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidDeepLinkClassifierTest {
    @Test
    fun promoterPkceCallbackIsSeparatedFromObservableRootNavigation() {
        assertEquals(
            AndroidDeepLinkDestination.PromoterActivation,
            AndroidDeepLinkClassifier.classify(
                "kwabor://auth/promoter-activate?token=$VALID_PROMOTER_TOKEN&code=$VALID_PKCE_CODE",
            ),
        )
    }

    @Test
    fun promoterCallbackAcceptsExistingSessionWithoutSessionProof() {
        assertEquals(
            AndroidDeepLinkDestination.PromoterActivation,
            AndroidDeepLinkClassifier.classify(
                "kwabor://auth/promoter-activate?token=$VALID_PROMOTER_TOKEN",
            ),
        )
    }

    @Test
    fun promoterCallbackRejectsImplicitAccessAndRefreshTokenFragment() {
        assertEquals(
            AndroidDeepLinkDestination.Rejected,
            AndroidDeepLinkClassifier.classify(
                "kwabor://auth/promoter-activate?token=$VALID_PROMOTER_TOKEN" +
                    "#access_token=access-token&refresh_token=refresh-token",
            ),
        )
    }

    @Test
    fun rootNavigationRejectsQueryAndFragmentPayloads() {
        assertEquals(
            AndroidDeepLinkDestination.RootNavigation,
            AndroidDeepLinkClassifier.classify("kwabor://app/profile"),
        )
        assertEquals(
            AndroidDeepLinkDestination.Rejected,
            AndroidDeepLinkClassifier.classify("kwabor://app/profile?access_token=secret"),
        )
        assertEquals(
            AndroidDeepLinkDestination.Rejected,
            AndroidDeepLinkClassifier.classify("kwabor://app/profile#access_token=secret"),
        )
        assertEquals(
            AndroidDeepLinkDestination.Rejected,
            AndroidDeepLinkClassifier.classify("kwabor://access_token@app/profile"),
        )
    }

    @Test
    fun catalogDetailAcceptsCanonicalManifestContractAndRejectsAmbiguousPayloads() {
        assertEquals(
            AndroidDeepLinkDestination.CatalogDetail,
            AndroidDeepLinkClassifier.classify("kwabor://listing/$VALID_LISTING_ID"),
        )
        listOf(
            "kwabor://listing/not-a-uuid",
            "kwabor://listing/$VALID_LISTING_ID?source=share",
            "kwabor://listing/$VALID_LISTING_ID#section",
            "kwabor://user@listing/$VALID_LISTING_ID",
            "kwabor://listing:443/$VALID_LISTING_ID",
            "kwabor://listing/$VALID_LISTING_ID/extra",
        ).forEach { rawUrl ->
            assertEquals(
                AndroidDeepLinkDestination.Rejected,
                AndroidDeepLinkClassifier.classify(rawUrl),
            )
        }
    }

    @Test
    fun catalogDetailClassifierNormalizesCaseAfterSystemDelivery() {
        assertEquals(
            AndroidDeepLinkDestination.CatalogDetail,
            AndroidDeepLinkClassifier.classify("KWABOR://LISTING/${VALID_LISTING_ID.uppercase()}"),
        )
    }

    @Test
    fun promoterCallbackRejectsUserInfoAndOversizedPayload() {
        assertEquals(
            AndroidDeepLinkDestination.Rejected,
            AndroidDeepLinkClassifier.classify(
                "kwabor://access-token@auth/promoter-activate?token=$VALID_PROMOTER_TOKEN",
            ),
        )
        assertEquals(
            AndroidDeepLinkDestination.Rejected,
            AndroidDeepLinkClassifier.classify(
                "kwabor://auth/promoter-activate?token=${"a".repeat(12_288)}",
            ),
        )
        assertEquals(
            AndroidDeepLinkDestination.Rejected,
            AndroidDeepLinkClassifier.classify(
                "kwabor://auth/promoter-activate?token=$VALID_PROMOTER_TOKEN&token=$VALID_PROMOTER_TOKEN",
            ),
        )
    }
}

private val VALID_PROMOTER_TOKEN = "a".repeat(64)
private val VALID_PKCE_CODE = "b".repeat(32)
private const val VALID_LISTING_ID = "123e4567-e89b-42d3-a456-426614174000"
