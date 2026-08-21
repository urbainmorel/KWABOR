package com.kwabor.shared.domain.observability

import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.money.KwaborCurrency

data class ObservabilityConsent(
    val analyticsAllowed: Boolean = false,
    val diagnosticsAllowed: Boolean = false,
    val remoteConfigurationAllowed: Boolean = false,
)

enum class DiagnosticCode(val wireName: String) {
    RemoteConfigurationFetchFailed(wireName = "remote_config_fetch_failed"),
    UnexpectedApplicationState(wireName = "unexpected_application_state"),
}

enum class PerformanceTraceName(val wireName: String) {
    ExploreInitialLoad(wireName = "explore_initial_load"),
    AuthSessionRestore(wireName = "auth_session_restore"),
    IntroVideoReady(wireName = "intro_video_ready"),
}

enum class PerformanceMetricName(val wireName: String) {
    FirstUsableViewportMicroseconds(wireName = "first_usable_viewport_us"),
}

enum class PerformanceSampleKind(val wireName: String) {
    Cold(wireName = "cold"),
    Warm(wireName = "warm"),
}

enum class PerformanceViewportState(val wireName: String) {
    Content(wireName = "content"),
    Empty(wireName = "empty"),
    Offline(wireName = "offline"),
    Error(wireName = "error"),
}

data class PerformanceMeasurement(
    val traceName: PerformanceTraceName,
    val metricName: PerformanceMetricName,
    val metricValue: Long,
    val sampleKind: PerformanceSampleKind,
    val viewportState: PerformanceViewportState,
) {
    init {
        require(metricValue >= 0L) { "Performance metric values must be non-negative." }
    }
}

enum class AnalyticsEventName(val wireName: String) {
    ViewCard(wireName = "view_card"),
    Like(wireName = "like"),
    FavoriteAdd(wireName = "favorite_add"),
    Share(wireName = "share"),
    SearchQuery(wireName = "search_query"),
    FilterApplied(wireName = "filter_applied"),
    SubcategorySelected(wireName = "subcategory_selected"),
    AiAssistantQuery(wireName = "ai_assistant_query"),
    AiAssistantResultClick(wireName = "ai_assistant_result_click"),
    NotificationReceived(wireName = "notification_received"),
    NotificationOpened(wireName = "notification_opened"),
    ReviewSubmitted(wireName = "review_submitted"),
    ReportSubmitted(wireName = "report_submitted"),
    IntroVideoShown(wireName = "intro_video_shown"),
    IntroVideoSkipped(wireName = "intro_video_skipped"),
    SoftwallHit(wireName = "softwall_hit"),
    SoftwallSignupStarted(wireName = "softwall_signup_started"),
    CurrencyChangeAttempt(wireName = "currency_change_attempt"),
    SignupStarted(wireName = "signup_started"),
    SignupCompleted(wireName = "signup_completed"),
    LoginCompleted(wireName = "login_completed"),
    AuthMethod(wireName = "auth_method"),
    RegistrationOtpValidated(wireName = "registration_otp_validated"),
    RegistrationProfileSucceeded(wireName = "registration_profile_succeeded"),
    RegistrationProfileFailed(wireName = "registration_profile_failed"),
    ProtectedActionReplayed(wireName = "protected_action_replayed"),
    SocialPostCreated(wireName = "social_post_created"),
    EntityTagSelected(wireName = "entity_tag_selected"),
    MentionPreviewOpened(wireName = "mention_preview_opened"),
    Follow(wireName = "follow"),
    MissingPlaceReported(wireName = "missing_place_reported"),
    GuideServiceCreated(wireName = "guide_service_created"),
    ListingCreated(wireName = "listing_created"),
    ListingUpdated(wireName = "listing_updated"),
    ClaimSubmitted(wireName = "claim_submitted"),
    PromoterActivated(wireName = "promoter_activated"),
    PromoterVerified(wireName = "promoter_verified"),
    PromoterCampaignCreated(wireName = "promoter_campaign_created"),
    PromoterCampaignPaid(wireName = "promoter_campaign_paid"),
    DirectionsClick(wireName = "directions_click"),
    ContactClick(wireName = "contact_click"),
}

enum class AnalyticsEntityType(val wireName: String) {
    NotApplicable(wireName = "not_applicable"),
    Place(wireName = "place"),
    Establishment(wireName = "establishment"),
    Event(wireName = "event"),
    Review(wireName = "review"),
    SocialPost(wireName = "social_post"),
    Organization(wireName = "organization"),
    Campaign(wireName = "campaign"),
    Notification(wireName = "notification"),
}

enum class AnalyticsSessionSource(val wireName: String) {
    Organic(wireName = "organic"),
    Sponsored(wireName = "sponsored"),
}

enum class AnalyticsAuthMethod(val wireName: String) {
    Email(wireName = "email"),
    Google(wireName = "google"),
    Apple(wireName = "apple"),
}

enum class AnalyticsSocialPostType(val wireName: String) {
    Photo(wireName = "photo"),
    Slideshow(wireName = "slideshow"),
}

data class AnalyticsContext(
    val cityId: String? = null,
    val entityType: AnalyticsEntityType = AnalyticsEntityType.NotApplicable,
    val entityId: String? = null,
    val sessionSource: AnalyticsSessionSource = AnalyticsSessionSource.Organic,
    val locale: AppLocale = AppLocale.French,
    val displayCurrency: KwaborCurrency = KwaborCurrency.Xof,
) {
    init {
        require(cityId.isAnalyticsSafeIdentifierOrNull()) { "Analytics city IDs must be opaque identifiers." }
        require(entityId.isAnalyticsSafeIdentifierOrNull()) { "Analytics entity IDs must be opaque identifiers." }
        require(entityType != AnalyticsEntityType.NotApplicable || entityId == null) {
            "An entity ID requires a concrete analytics entity type."
        }
    }
}

data class AnalyticsEvent(
    val name: AnalyticsEventName,
    val context: AnalyticsContext = AnalyticsContext(),
    val authMethod: AnalyticsAuthMethod? = null,
    val socialPostType: AnalyticsSocialPostType? = null,
) {
    init {
        require((name == AnalyticsEventName.AuthMethod) == (authMethod != null)) {
            "Only auth_method events carry an authentication method."
        }
        require((name == AnalyticsEventName.SocialPostCreated) == (socialPostType != null)) {
            "Only social_post_created events carry a social post type."
        }
    }
}

internal fun String?.isAnalyticsSafeIdentifierOrNull(): Boolean = this == null || SAFE_IDENTIFIER_PATTERN.matches(this)

private val SAFE_IDENTIFIER_PATTERN = Regex(pattern = "^[A-Za-z0-9_-]{1,64}$")
