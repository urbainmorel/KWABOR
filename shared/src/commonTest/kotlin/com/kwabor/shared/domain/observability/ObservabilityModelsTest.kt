package com.kwabor.shared.domain.observability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ObservabilityModelsTest {
    @Test
    fun analyticsCatalog_matchesThePrdEventContract() {
        assertEquals(EXPECTED_EVENT_NAMES, AnalyticsEventName.entries.map(AnalyticsEventName::wireName).toSet())
    }

    @Test
    fun analyticsContext_acceptsOpaqueIdentifiersAndRejectsFreeText() {
        val context = AnalyticsContext(
            cityId = "cotonou_01",
            entityType = AnalyticsEntityType.Place,
            entityId = "550e8400-e29b-41d4-a716-446655440000",
        )

        assertEquals("cotonou_01", context.cityId)
        assertFailsWith<IllegalArgumentException> {
            AnalyticsContext(cityId = "afi@example.com")
        }
        assertFailsWith<IllegalArgumentException> {
            AnalyticsContext(
                entityType = AnalyticsEntityType.Place,
                entityId = "Nom complet",
            )
        }
    }

    @Test
    fun analyticsEvent_requiresOnlyItsTypedOptionalDimension() {
        assertFailsWith<IllegalArgumentException> {
            AnalyticsEvent(name = AnalyticsEventName.AuthMethod)
        }
        assertFailsWith<IllegalArgumentException> {
            AnalyticsEvent(
                name = AnalyticsEventName.ViewCard,
                authMethod = AnalyticsAuthMethod.Email,
            )
        }

        val event = AnalyticsEvent(
            name = AnalyticsEventName.AuthMethod,
            authMethod = AnalyticsAuthMethod.Apple,
        )
        assertEquals(AnalyticsAuthMethod.Apple, event.authMethod)
    }

    @Test
    fun observabilityConsent_deniesEveryCapabilityByDefault() {
        val consent = ObservabilityConsent()

        assertFalse(consent.analyticsAllowed)
        assertFalse(consent.diagnosticsAllowed)
        assertFalse(consent.remoteConfigurationAllowed)
    }
}

private val EXPECTED_EVENT_NAMES = setOf(
    "view_card",
    "like",
    "favorite_add",
    "share",
    "search_query",
    "filter_applied",
    "subcategory_selected",
    "ai_assistant_query",
    "ai_assistant_result_click",
    "notification_received",
    "notification_opened",
    "review_submitted",
    "report_submitted",
    "intro_video_shown",
    "intro_video_skipped",
    "softwall_hit",
    "softwall_signup_started",
    "currency_change_attempt",
    "signup_started",
    "signup_completed",
    "login_completed",
    "auth_method",
    "registration_otp_validated",
    "registration_profile_succeeded",
    "registration_profile_failed",
    "protected_action_replayed",
    "social_post_created",
    "entity_tag_selected",
    "mention_preview_opened",
    "follow",
    "missing_place_reported",
    "guide_service_created",
    "listing_created",
    "listing_updated",
    "claim_submitted",
    "promoter_activated",
    "promoter_verified",
    "promoter_campaign_created",
    "promoter_campaign_paid",
    "directions_click",
    "contact_click",
)
