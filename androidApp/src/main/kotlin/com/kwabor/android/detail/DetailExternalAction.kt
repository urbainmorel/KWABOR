package com.kwabor.android.detail

import java.math.BigDecimal
import java.net.URI
import java.net.URISyntaxException

internal sealed interface DetailExternalAction {
    data class Directions(
        val latitude: Double,
        val longitude: Double,
        val label: String,
    ) : DetailExternalAction

    data class Phone(val number: String) : DetailExternalAction

    data class WhatsApp(val number: String) : DetailExternalAction

    data class Email(val address: String) : DetailExternalAction

    data class Https(val url: String) : DetailExternalAction
}

internal enum class DetailExternalActionResult {
    Opened,
    Rejected,
    Unavailable,
}

internal fun interface DetailExternalActionLauncher {
    fun launch(action: DetailExternalAction): DetailExternalActionResult
}

internal enum class DetailExternalIntentAction {
    Dial,
    View,
}

internal data class DetailExternalIntentSpec(
    val action: DetailExternalIntentAction,
    val uri: String,
)

internal fun DetailExternalAction.toIntentSpecOrNull(): DetailExternalIntentSpec? = when (this) {
    is DetailExternalAction.Directions -> directionsIntentSpecOrNull()
    is DetailExternalAction.Phone -> phoneIntentSpecOrNull()
    is DetailExternalAction.WhatsApp -> whatsappIntentSpecOrNull()
    is DetailExternalAction.Email -> emailIntentSpecOrNull()
    is DetailExternalAction.Https -> httpsIntentSpecOrNull()
}

private fun DetailExternalAction.Directions.directionsIntentSpecOrNull(): DetailExternalIntentSpec? {
    if (!latitude.isFinite() || latitude !in MINIMUM_LATITUDE..MAXIMUM_LATITUDE) return null
    if (!longitude.isFinite() || longitude !in MINIMUM_LONGITUDE..MAXIMUM_LONGITUDE) return null
    if (label.codePointCount(0, label.length) !in 1..MAXIMUM_DIRECTIONS_LABEL_CODE_POINTS) return null
    if (label.isBlank() || label != label.trim() || label.hasControlCharacters()) return null

    val destination = "${latitude.toCanonicalCoordinate()}%2C${longitude.toCanonicalCoordinate()}"
    return DetailExternalIntentSpec(
        action = DetailExternalIntentAction.View,
        uri = "$GOOGLE_MAPS_DIRECTIONS_URL$destination",
    )
}

private fun DetailExternalAction.Phone.phoneIntentSpecOrNull(): DetailExternalIntentSpec? =
    number.takeIf(BENIN_PHONE_PATTERN::matches)?.let { phone ->
        DetailExternalIntentSpec(
            action = DetailExternalIntentAction.Dial,
            uri = "$PHONE_SCHEME$phone",
        )
    }

private fun DetailExternalAction.WhatsApp.whatsappIntentSpecOrNull(): DetailExternalIntentSpec? =
    number.takeIf(BENIN_PHONE_PATTERN::matches)?.let { phone ->
        DetailExternalIntentSpec(
            action = DetailExternalIntentAction.View,
            uri = "$WHATSAPP_URL${phone.removePrefix(PLUS_SIGN)}",
        )
    }

private fun DetailExternalAction.Email.emailIntentSpecOrNull(): DetailExternalIntentSpec? =
    address.takeIf(String::isSafeEmailAddress)?.let { email ->
        DetailExternalIntentSpec(
            action = DetailExternalIntentAction.View,
            uri = "$EMAIL_SCHEME$email",
        )
    }

private fun DetailExternalAction.Https.httpsIntentSpecOrNull(): DetailExternalIntentSpec? =
    url.takeIf(String::isSafeHttpsUrl)?.let { safeUrl ->
        DetailExternalIntentSpec(
            action = DetailExternalIntentAction.View,
            uri = safeUrl,
        )
    }

private fun String.isSafeEmailAddress(): Boolean {
    if (length !in MINIMUM_EMAIL_LENGTH..MAXIMUM_EMAIL_LENGTH || hasUnsafeCharacters()) return false
    if (!EMAIL_PATTERN.matches(this)) return false

    val localPart = substringBefore(EMAIL_SEPARATOR)
    val domain = substringAfterLast(EMAIL_SEPARATOR)
    return !localPart.startsWith(DOT) &&
        !localPart.endsWith(DOT) &&
        DOUBLE_DOT !in localPart &&
        domain.isSafePublicHost()
}

private fun String.isSafeHttpsUrl(): Boolean {
    if (!hasSafeHttpsLexicalForm()) return false

    val parsed = toUriOrNull() ?: return false
    val host = parsed.host?.takeIf(String::isSafePublicHost) ?: return false
    return parsed.hasSafeHttpsStructure(host)
}

private fun String.hasSafeHttpsLexicalForm(): Boolean {
    if (length !in MINIMUM_HTTPS_URL_LENGTH..MAXIMUM_HTTPS_URL_LENGTH) return false
    if (hasUnsafeCharacters() || '\\' in this || '#' in this) return false
    return !ENCODED_UNSAFE_PATTERN.containsMatchIn(this)
}

private fun URI.hasSafeHttpsStructure(host: String): Boolean = scheme == HTTPS_SCHEME &&
    !isOpaque &&
    userInfo == null &&
    port in ALLOWED_HTTPS_PORTS &&
    host == host.lowercase()

private fun String.toUriOrNull(): URI? = try {
    URI(this)
} catch (_: URISyntaxException) {
    null
}

private fun String.isSafePublicHost(): Boolean {
    if (equals(LOCALHOST, ignoreCase = true) || ':' in this || isNumericHostNotation()) return false
    if (!contains(DOT) || length > MAXIMUM_HOST_LENGTH) return false
    val labels = split(DOT)
    if (labels.last().lowercase() in LOCAL_HOST_SUFFIXES) return false
    return labels.all { label -> HOST_LABEL_PATTERN.matches(label) }
}

private fun String.isNumericHostNotation(): Boolean {
    if (NUMERIC_HOST_PATTERN.matches(this)) return true
    return split(DOT).all { label -> NUMERIC_HOST_LABEL_PATTERN.matches(label) }
}

private fun String.hasUnsafeCharacters(): Boolean = any { character ->
    character.isWhitespace() || character.isISOControl()
}

private fun String.hasControlCharacters(): Boolean = any { character -> character.isISOControl() }

private fun Double.toCanonicalCoordinate(): String = BigDecimal.valueOf(this).stripTrailingZeros().toPlainString()

private const val MINIMUM_LATITUDE = -90.0
private const val MAXIMUM_LATITUDE = 90.0
private const val MINIMUM_LONGITUDE = -180.0
private const val MAXIMUM_LONGITUDE = 180.0
private const val MAXIMUM_DIRECTIONS_LABEL_CODE_POINTS = 80
private const val GOOGLE_MAPS_DIRECTIONS_URL = "https://www.google.com/maps/dir/?api=1&destination="
private const val PHONE_SCHEME = "tel:"
private const val WHATSAPP_URL = "https://wa.me/"
private const val EMAIL_SCHEME = "mailto:"
private const val HTTPS_SCHEME = "https"
private const val PLUS_SIGN = "+"
private const val EMAIL_SEPARATOR = '@'
private const val DOT = "."
private const val DOUBLE_DOT = ".."
private const val MINIMUM_EMAIL_LENGTH = 6
private const val MAXIMUM_EMAIL_LENGTH = 254
private const val MINIMUM_HTTPS_URL_LENGTH = 9
private const val MAXIMUM_HTTPS_URL_LENGTH = 2_048
private const val MAXIMUM_HOST_LENGTH = 253
private const val LOCALHOST = "localhost"
private const val NO_PORT = -1
private const val STANDARD_HTTPS_PORT = 443

private val ALLOWED_HTTPS_PORTS = setOf(NO_PORT, STANDARD_HTTPS_PORT)
private val LOCAL_HOST_SUFFIXES = setOf("arpa", "home", "internal", "lan", "local", "localhost")
private val BENIN_PHONE_PATTERN = Regex(pattern = "^\\+229[0-9]{5,12}$")
private val ENCODED_UNSAFE_PATTERN = Regex(pattern = "%(?:[01][0-9A-Fa-f]|7[Ff]|5[Cc])")
private val NUMERIC_HOST_PATTERN = Regex(pattern = "^[0-9.]+$")
private val NUMERIC_HOST_LABEL_PATTERN = Regex(pattern = "^(?:[0-9]+|0[xX][0-9A-Fa-f]+)$")
private val HOST_LABEL_PATTERN = Regex(pattern = "^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")
private val EMAIL_PATTERN = Regex(
    pattern = "^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}@" +
        "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?" +
        "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$",
)
