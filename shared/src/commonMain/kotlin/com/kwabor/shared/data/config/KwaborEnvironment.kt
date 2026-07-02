package com.kwabor.shared.data.config

data class KwaborEnvironment(
    val supabaseUrl: String,
    val supabasePublishableKey: String,
) {
    init {
        require(supabaseUrl.startsWith(prefix = "https://")) { "Supabase URL must use HTTPS." }
        require(supabasePublishableKey.isNotBlank()) { "Supabase publishable key is required." }
    }
}
