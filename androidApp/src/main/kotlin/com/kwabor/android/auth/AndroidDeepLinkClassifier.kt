package com.kwabor.android.auth

import com.kwabor.shared.domain.auth.PromoterActivationDeepLinkParser
import com.kwabor.shared.domain.auth.PromoterActivationDeepLinkResult
import java.net.URI
import java.net.URISyntaxException

internal enum class AndroidDeepLinkDestination {
    RootNavigation,
    PromoterActivation,
    Rejected,
}

internal object AndroidDeepLinkClassifier {
    fun classify(rawUrl: String): AndroidDeepLinkDestination {
        val uri = rawUrl.parseKwaborUriOrNull() ?: return AndroidDeepLinkDestination.Rejected
        return when {
            uri.isPromoterActivationUri() -> rawUrl.promoterActivationDestination()
            uri.isRootNavigationUri() -> AndroidDeepLinkDestination.RootNavigation
            else -> AndroidDeepLinkDestination.Rejected
        }
    }
}

private fun String.parseKwaborUriOrNull(): URI? {
    if (length > MAXIMUM_DEEP_LINK_LENGTH) return null
    val uri = try {
        URI(this)
    } catch (_: URISyntaxException) {
        return null
    }
    if (!uri.scheme.equals(KWABOR_SCHEME, ignoreCase = true)) return null
    return uri.takeIf { it.port == NO_PORT }
}

private fun URI.isPromoterActivationUri(): Boolean {
    if (!host.equals(KWABOR_AUTH_HOST, ignoreCase = true)) return false
    if (userInfo != null) return false
    if (fragment != null) return false
    return path == PROMOTER_ACTIVATION_PATH
}

private fun URI.isRootNavigationUri(): Boolean {
    if (!host.equals(KWABOR_APP_HOST, ignoreCase = true)) return false
    if (userInfo != null) return false
    if (query != null) return false
    return fragment == null
}

private fun String.promoterActivationDestination(): AndroidDeepLinkDestination =
    when (PromoterActivationDeepLinkParser.parse(this)) {
        is PromoterActivationDeepLinkResult.Accepted -> AndroidDeepLinkDestination.PromoterActivation
        is PromoterActivationDeepLinkResult.Rejected -> AndroidDeepLinkDestination.Rejected
    }

private const val KWABOR_SCHEME = "kwabor"
private const val KWABOR_AUTH_HOST = "auth"
private const val KWABOR_APP_HOST = "app"
private const val PROMOTER_ACTIVATION_PATH = "/promoter-activate"
private const val NO_PORT = -1
private const val MAXIMUM_DEEP_LINK_LENGTH = 12_288
