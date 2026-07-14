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
    IntroVideoIntegrityFailed(wireName = "intro_video_integrity_failed"),
    UnexpectedApplicationState(wireName = "unexpected_application_state"),
}

enum class PerformanceTraceName(val wireName: String) {
    ExploreInitialLoad(wireName = "explore_initial_load"),
    AuthSessionRestore(wireName = "auth_session_restore"),
    IntroVideoReady(wireName = "intro_video_ready"),
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
        require(cityId.isSafeIdentifierOrNull()) { "Analytics city IDs must be opaque identifiers." }
        require(entityId.isSafeIdentifierOrNull()) { "Analytics entity IDs must be opaque identifiers." }
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

data class RemoteFeatureConfiguration(
    val introVideo: RemoteIntroVideo? = null,
) {
    companion object {
        val SafeDefaults = RemoteFeatureConfiguration()
    }
}

data class RemoteIntroVideo(
    val url: String,
    val sha256: String,
    val revision: Long,
)

fun createRemoteFeatureConfiguration(
    introVideoEnabled: Boolean,
    introVideoUrl: String?,
    introVideoSha256: String?,
    introVideoRevision: Long,
): RemoteFeatureConfiguration {
    if (!introVideoEnabled) {
        return RemoteFeatureConfiguration.SafeDefaults
    }
    val url = introVideoUrl?.trim().orEmpty()
    val sha256 = introVideoSha256?.trim()?.lowercase().orEmpty()
    if (!url.isSafeHttpsUrl() || !SHA256_PATTERN.matches(sha256) || introVideoRevision <= 0) {
        return RemoteFeatureConfiguration.SafeDefaults
    }
    return RemoteFeatureConfiguration(
        introVideo = RemoteIntroVideo(
            url = url,
            sha256 = sha256,
            revision = introVideoRevision,
        ),
    )
}

private fun String?.isSafeIdentifierOrNull(): Boolean = this == null || SAFE_IDENTIFIER_PATTERN.matches(this)

private fun String.isSafeHttpsUrl(): Boolean {
    if (length !in MIN_REMOTE_URL_LENGTH..MAX_REMOTE_URL_LENGTH || any(Char::isWhitespace)) {
        return false
    }
    if (!startsWith(prefix = "https://", ignoreCase = true)) {
        return false
    }
    val authority = substringAfter(delimiter = "://").substringBefore(delimiter = "/")
    return authority.isNotBlank() && '@' !in authority && '.' in authority
}

private const val MIN_REMOTE_URL_LENGTH = 9
private const val MAX_REMOTE_URL_LENGTH = 2_048
private val SAFE_IDENTIFIER_PATTERN = Regex(pattern = "^[A-Za-z0-9_-]{1,64}$")
private val SHA256_PATTERN = Regex(pattern = "^[a-f0-9]{64}$")
