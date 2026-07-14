package com.kwabor.shared.data.config

enum class KwaborEnvironmentTier(val configurationValue: String) {
    Development("development"),
    Staging("staging"),
    Production("production"),
    ;

    companion object {
        fun fromConfiguration(value: String?): KwaborEnvironmentTier? {
            val normalizedValue = value?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { tier -> tier.configurationValue == normalizedValue }
        }
    }
}

data class KwaborEnvironment(
    val tier: KwaborEnvironmentTier,
    val supabaseUrl: String,
    val supabasePublishableKey: String,
) {
    init {
        require(supabaseUrl.startsWith(prefix = "https://")) { "Supabase URL must use HTTPS." }
        require(supabasePublishableKey.isNotBlank()) { "Supabase publishable key is required." }
    }
}

internal fun createKwaborEnvironmentOrNull(
    environmentName: String?,
    supabaseUrl: String?,
    supabasePublishableKey: String?,
): KwaborEnvironment? {
    val tier = KwaborEnvironmentTier.fromConfiguration(environmentName) ?: return null
    val safeUrl = supabaseUrl?.trim().orEmpty()
    val safePublishableKey = supabasePublishableKey?.trim().orEmpty()
    if (safeUrl.isBlank() || safePublishableKey.isBlank() || !safeUrl.startsWith(HTTPS_PREFIX)) {
        return null
    }
    return KwaborEnvironment(
        tier = tier,
        supabaseUrl = safeUrl,
        supabasePublishableKey = safePublishableKey,
    )
}

private const val HTTPS_PREFIX = "https://"
