package com.kwabor.shared.data.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

private val strictCatalogDetailJson = Json

internal fun JsonObject.decodeStrictCatalogDetailPayload(): CatalogDetailPayloadDto = decodeCatalogDetailJson()

internal fun JsonObject.decodeCatalogPlaceDetail(): CatalogPlaceDetailDto = decodeCatalogDetailJson()

internal fun JsonObject.decodeCatalogLodgingDetail(): CatalogLodgingDetailDto = decodeCatalogDetailJson()

internal fun JsonObject.decodeCatalogFoodDetail(): CatalogFoodDetailDto = decodeCatalogDetailJson()

internal fun JsonObject.decodeCatalogNightlifeDetail(): CatalogNightlifeDetailDto = decodeCatalogDetailJson()

internal fun JsonObject.decodeCatalogGuideDetail(): CatalogGuideDetailDto = decodeCatalogDetailJson()

internal fun JsonObject.decodeCatalogEventDetail(): CatalogEventDetailDto = decodeCatalogDetailJson()

internal fun JsonElement.decodeCatalogOpeningDay(): CatalogDetailOpeningDayDto = decodeCatalogDetailJson()

private inline fun <reified T> JsonElement.decodeCatalogDetailJson(): T =
    strictCatalogDetailJson.decodeFromJsonElement(this)
