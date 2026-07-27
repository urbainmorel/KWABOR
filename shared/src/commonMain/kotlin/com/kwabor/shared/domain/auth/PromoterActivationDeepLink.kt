package com.kwabor.shared.domain.auth

private const val PROMOTER_TOKEN_LENGTH = 64
private const val MAX_CALLBACK_URL_LENGTH = 12_288
private const val MAX_CALLBACK_PARAMETER_LENGTH = 8_192
private const val MIN_AUTHORIZATION_CODE_LENGTH = 20
private const val MAX_AUTHORIZATION_CODE_LENGTH = 512
private const val HEX_RADIX = 16
private const val PERCENT_ENCODING_SEQUENCE_LENGTH = 3
private const val PERCENT_ENCODING_VALUE_OFFSET = 1
private const val SINGLE_CHARACTER_LENGTH = 1
private const val EXPECTED_SCHEME = "kwabor"
private const val EXPECTED_AUTHORITY_PATH = "auth/promoter-activate"
private const val EXPECTED_AUTHORITY = "auth"
private const val TOKEN_PARAMETER = "token"
private const val CODE_PARAMETER = "code"
private const val PERCENT_CHARACTER = '%'
private val PROMOTER_TOKEN_PATTERN = Regex("^[a-f0-9]{$PROMOTER_TOKEN_LENGTH}$")
private val ALLOWED_QUERY_PARAMETERS = setOf(TOKEN_PARAMETER, CODE_PARAMETER)

sealed interface PromoterActivationDeepLinkResult {
    class Accepted(
        val inviteToken: String,
        val sessionProof: PromoterActivationSessionProof,
    ) : PromoterActivationDeepLinkResult {
        override fun toString(): String = "Accepted(inviteToken=<redacted>, sessionProof=<redacted>)"
    }

    data class Rejected(val reason: PromoterActivationDeepLinkRejection) : PromoterActivationDeepLinkResult
}

sealed interface PromoterActivationSessionProof {
    class PkceCode(val code: String) : PromoterActivationSessionProof {
        override fun toString(): String = "PkceCode(code=<redacted>)"
    }

    data object ExistingSession : PromoterActivationSessionProof
}

enum class PromoterActivationDeepLinkRejection {
    Malformed,
    UnsupportedScheme,
    UnsupportedHost,
    UnsupportedPath,
    MissingToken,
    DuplicateParameter,
    UnknownParameter,
    InvalidToken,
}

object PromoterActivationDeepLinkParser {
    fun parse(rawUrl: String): PromoterActivationDeepLinkResult = when (val parsedUrl = parseActivationUrl(rawUrl)) {
        is ParsedActivationUrl.Accepted -> parseQuery(parsedUrl.rawQuery)
        is ParsedActivationUrl.Rejected -> rejected(parsedUrl.reason)
    }
}

private fun parseActivationUrl(rawUrl: String): ParsedActivationUrl {
    if (rawUrl.isMalformedCallbackUrl()) {
        return ParsedActivationUrl.Rejected(PromoterActivationDeepLinkRejection.Malformed)
    }
    val schemeParts = rawUrl.split("://", limit = 2)
    return when {
        schemeParts.size != 2 ->
            ParsedActivationUrl.Rejected(PromoterActivationDeepLinkRejection.Malformed)
        !schemeParts.first().equals(EXPECTED_SCHEME, ignoreCase = true) ->
            ParsedActivationUrl.Rejected(PromoterActivationDeepLinkRejection.UnsupportedScheme)
        else -> parseAuthorityAndQuery(schemeParts.last())
    }
}

private fun parseAuthorityAndQuery(rawAuthorityAndQuery: String): ParsedActivationUrl {
    val authorityQueryParts = rawAuthorityAndQuery.split('?', limit = 2)
    val authorityPath = authorityQueryParts.first()
    val rejection = when {
        authorityPath.equals(EXPECTED_AUTHORITY_PATH, ignoreCase = true) -> null
        !authorityPath.substringBefore('/').equals(EXPECTED_AUTHORITY, ignoreCase = true) ->
            PromoterActivationDeepLinkRejection.UnsupportedHost
        else -> PromoterActivationDeepLinkRejection.UnsupportedPath
    }
    return if (rejection == null) {
        ParsedActivationUrl.Accepted(authorityQueryParts.getOrNull(1))
    } else {
        ParsedActivationUrl.Rejected(rejection)
    }
}

private fun parseQuery(rawQuery: String?): PromoterActivationDeepLinkResult =
    when (val parameters = parseParameters(rawQuery)) {
        is ParsedParameters.Accepted -> parameters.values.toDeepLinkResult()
        is ParsedParameters.Rejected -> rejected(parameters.reason)
    }

private fun Map<String, String>.toDeepLinkResult(): PromoterActivationDeepLinkResult {
    val token = get(TOKEN_PARAMETER)
    return when {
        token == null -> rejected(PromoterActivationDeepLinkRejection.MissingToken)
        !PROMOTER_TOKEN_PATTERN.matches(token) ->
            rejected(PromoterActivationDeepLinkRejection.InvalidToken)
        else -> acceptedDeepLink(token = token, rawAuthorizationCode = get(CODE_PARAMETER))
    }
}

private fun acceptedDeepLink(token: String, rawAuthorizationCode: String?): PromoterActivationDeepLinkResult {
    val decodedAuthorizationCode = rawAuthorizationCode?.let(::decodeAuthorizationCode)
    return when {
        rawAuthorizationCode == null -> accepted(token, PromoterActivationSessionProof.ExistingSession)
        decodedAuthorizationCode == null -> rejected(PromoterActivationDeepLinkRejection.Malformed)
        else -> accepted(token, PromoterActivationSessionProof.PkceCode(decodedAuthorizationCode))
    }
}

private fun decodeAuthorizationCode(rawCode: String): String? {
    val decoded = StringBuilder(rawCode.length)
    var index = 0
    var isValid = true
    while (index < rawCode.length && isValid) {
        when (val decodedCharacter = decodeCharacter(rawCode, index)) {
            DecodedCharacter.Invalid -> isValid = false
            is DecodedCharacter.Valid -> {
                decoded.append(decodedCharacter.value)
                index += decodedCharacter.consumedCharacters
            }
        }
    }
    return decoded.toString().takeIf { code ->
        isValid && code.length in MIN_AUTHORIZATION_CODE_LENGTH..MAX_AUTHORIZATION_CODE_LENGTH
    }
}

private fun decodeCharacter(rawCode: String, index: Int): DecodedCharacter {
    val current = rawCode[index]
    return if (current == PERCENT_CHARACTER) {
        decodePercentEncodedCharacter(rawCode, index)
    } else if (current.isUnreservedAuthCharacter()) {
        DecodedCharacter.Valid(current, SINGLE_CHARACTER_LENGTH)
    } else {
        DecodedCharacter.Invalid
    }
}

private fun decodePercentEncodedCharacter(rawCode: String, index: Int): DecodedCharacter {
    val encodedEndIndex = index + PERCENT_ENCODING_SEQUENCE_LENGTH
    if (encodedEndIndex > rawCode.length) return DecodedCharacter.Invalid
    val value = rawCode.substring(index + PERCENT_ENCODING_VALUE_OFFSET, encodedEndIndex)
        .toIntOrNull(HEX_RADIX)
    val decodedCharacter = value?.toChar()
    return if (decodedCharacter?.isUnreservedAuthCharacter() == true) {
        DecodedCharacter.Valid(decodedCharacter, PERCENT_ENCODING_SEQUENCE_LENGTH)
    } else {
        DecodedCharacter.Invalid
    }
}

private fun parseParameters(raw: String?): ParsedParameters {
    if (raw.isNullOrEmpty()) return ParsedParameters.Accepted(emptyMap())
    val values = mutableMapOf<String, String>()
    var rejection: PromoterActivationDeepLinkRejection? = null
    raw.split('&').forEach { pair ->
        if (rejection == null) {
            rejection = parseParameter(pair).appendTo(values)
        }
    }
    return rejection?.let(ParsedParameters::Rejected) ?: ParsedParameters.Accepted(values)
}

private fun parseParameter(rawParameter: String): ParsedParameter {
    val parts = rawParameter.split('=', limit = 2)
    if (parts.size != 2) return ParsedParameter.Rejected
    val name = parts.first()
    val value = parts.last()
    val isMalformed = name.isEmptyOrValueIsEmpty(value) ||
        name.exceedsParameterLengthWith(value) ||
        value.containsQueryDelimiter()
    return if (isMalformed) {
        ParsedParameter.Rejected
    } else {
        ParsedParameter.Accepted(name = name, value = value)
    }
}

private fun ParsedParameter.appendTo(values: MutableMap<String, String>): PromoterActivationDeepLinkRejection? =
    when (this) {
        ParsedParameter.Rejected -> PromoterActivationDeepLinkRejection.Malformed
        is ParsedParameter.Accepted -> {
            val rejection = rejectionAgainst(values)
            if (rejection == null) {
                values[name] = value
            }
            rejection
        }
    }

private fun ParsedParameter.Accepted.rejectionAgainst(
    existingValues: Map<String, String>,
): PromoterActivationDeepLinkRejection? = when {
    name !in ALLOWED_QUERY_PARAMETERS -> PromoterActivationDeepLinkRejection.UnknownParameter
    name in existingValues -> PromoterActivationDeepLinkRejection.DuplicateParameter
    else -> null
}

private fun String.isMalformedCallbackUrl(): Boolean {
    val hasInvalidShape = isBlank() || length > MAX_CALLBACK_URL_LENGTH || this != trim()
    val hasUnsafeContent = any(Char::isISOControl) || '#' in this
    return hasInvalidShape || hasUnsafeContent
}

private fun String.isEmptyOrValueIsEmpty(value: String): Boolean = isEmpty() || value.isEmpty()

private fun String.exceedsParameterLengthWith(value: String): Boolean =
    length > MAX_CALLBACK_PARAMETER_LENGTH || value.length > MAX_CALLBACK_PARAMETER_LENGTH

private fun String.containsQueryDelimiter(): Boolean = contains('?') || contains('#')

private fun Char.isUnreservedAuthCharacter(): Boolean = isLetterOrDigit() || this == '-' || this == '_' || this == '.'

private sealed interface ParsedActivationUrl {
    data class Accepted(val rawQuery: String?) : ParsedActivationUrl
    data class Rejected(val reason: PromoterActivationDeepLinkRejection) : ParsedActivationUrl
}

private sealed interface ParsedParameters {
    data class Accepted(val values: Map<String, String>) : ParsedParameters
    data class Rejected(val reason: PromoterActivationDeepLinkRejection) : ParsedParameters
}

private sealed interface ParsedParameter {
    data class Accepted(val name: String, val value: String) : ParsedParameter
    data object Rejected : ParsedParameter
}

private sealed interface DecodedCharacter {
    data class Valid(val value: Char, val consumedCharacters: Int) : DecodedCharacter
    data object Invalid : DecodedCharacter
}

private fun accepted(
    inviteToken: String,
    sessionProof: PromoterActivationSessionProof,
): PromoterActivationDeepLinkResult = PromoterActivationDeepLinkResult.Accepted(
    inviteToken = inviteToken,
    sessionProof = sessionProof,
)

private fun rejected(reason: PromoterActivationDeepLinkRejection): PromoterActivationDeepLinkResult =
    PromoterActivationDeepLinkResult.Rejected(reason)
