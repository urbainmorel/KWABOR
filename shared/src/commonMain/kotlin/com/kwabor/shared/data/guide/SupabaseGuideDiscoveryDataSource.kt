package com.kwabor.shared.data.guide

import com.kwabor.shared.domain.guide.GuideDiscoveryFilters
import com.kwabor.shared.domain.guide.GuidePageRequest
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.rpc
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_CONFLICT = 409
private const val HTTP_UNPROCESSABLE_CONTENT = 422
private const val POSTGREST_SCHEMA_CACHE_ERROR_PREFIX = "PGRST2"
private const val LIST_GUIDE_FACETS = "list_guide_facets_v1"
private const val LIST_GUIDE_SERVICES = "list_guide_services_v1"

internal class SupabaseGuideDiscoveryDataSource(
    private val postgrest: Postgrest,
) : GuideDiscoveryDataSource {
    override suspend fun listFacets(): List<GuideFacetRowDto> = runGuidePostgrest {
        postgrest.rpc(function = LIST_GUIDE_FACETS)
            .decodeList<JsonObject>()
            .map { row -> strictGuideDiscoveryJson.decodeFromJsonElement<GuideFacetRowDto>(row) }
    }

    override suspend fun listServices(filters: GuideDiscoveryFilters, page: GuidePageRequest): GuideSummaryPageDto =
        runGuidePostgrest {
            postgrest.rpc(
                function = LIST_GUIDE_SERVICES,
                parameters = GuideServicesRpcParametersDto(
                    cityId = filters.cityId,
                    languageId = filters.languageId,
                    specialtyId = filters.specialtyId,
                    cursor = page.cursor,
                    limit = page.limit,
                ),
            ).decodeList<JsonObject>()
                .map { row -> strictGuideDiscoveryJson.decodeFromJsonElement<GuideSummaryRowDto>(row) }
                .toGuideSummaryPage(page.limit)
        }
}

internal fun List<GuideSummaryRowDto>.toGuideSummaryPage(limit: Int): GuideSummaryPageDto {
    if (size > limit + 1) {
        throw GuideDiscoveryDataException.Unexpected(
            IllegalStateException("Guide discovery RPC returned more than one sentinel row."),
        )
    }
    forEach { row -> row.toDomain() }
    if (distinctBy(GuideSummaryRowDto::id).size != size) {
        throw GuideDiscoveryDataException.Unexpected(
            IllegalStateException("Guide discovery RPC returned duplicate guide IDs."),
        )
    }
    val items = take(limit)
    val nextCursor = if (size > limit) {
        items.last().rowCursor.requireGuideCursor("row_cursor")
    } else {
        null
    }
    return GuideSummaryPageDto(items = items, nextCursor = nextCursor)
}

private val strictGuideDiscoveryJson = Json

private suspend fun <T> runGuidePostgrest(block: suspend () -> T): T = try {
    block()
} catch (exception: PostgrestRestException) {
    throw exception.toGuideDiscoveryDataException()
} catch (exception: RestException) {
    throw exception.toGuideDiscoveryDataException()
} catch (exception: HttpRequestTimeoutException) {
    throw GuideDiscoveryDataException.NetworkUnavailable(exception)
} catch (exception: HttpRequestException) {
    throw GuideDiscoveryDataException.NetworkUnavailable(exception)
} catch (exception: SerializationException) {
    throw GuideDiscoveryDataException.Unexpected(exception)
}

private fun RestException.toGuideDiscoveryDataException(): GuideDiscoveryDataException {
    val codeMappedException = (this as? PostgrestRestException)?.toCodeMappedGuideDiscoveryDataException()
    return codeMappedException ?: when (statusCode) {
        HTTP_UNAUTHORIZED,
        HTTP_FORBIDDEN,
        -> GuideDiscoveryDataException.PermissionDenied(this)
        HTTP_BAD_REQUEST,
        HTTP_CONFLICT,
        HTTP_UNPROCESSABLE_CONTENT,
        -> GuideDiscoveryDataException.Validation(cause = this)
        else -> GuideDiscoveryDataException.Unexpected(this)
    }
}

private fun PostgrestRestException.toCodeMappedGuideDiscoveryDataException(): GuideDiscoveryDataException? = when {
    code?.startsWith(POSTGREST_SCHEMA_CACHE_ERROR_PREFIX) == true -> GuideDiscoveryDataException.Unexpected(this)
    else -> when (code) {
        "42501" -> GuideDiscoveryDataException.PermissionDenied(this)
        "22023" -> GuideDiscoveryDataException.Validation(cause = this)
        else -> null
    }
}
