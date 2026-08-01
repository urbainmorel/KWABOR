package com.kwabor.shared.data.local

import com.kwabor.shared.domain.catalog.Category
import com.kwabor.shared.domain.catalog.City
import com.kwabor.shared.domain.catalog.GeoPoint
import com.kwabor.shared.domain.catalog.isWithinBeninBounds

internal data class ExploreReferenceSnapshot(
    val cities: List<City>,
    val categories: List<Category>,
    val cachedAtEpochMilliseconds: Long,
)

internal data class ExploreReferenceWrite(
    val snapshot: ExploreReferenceSnapshotEntity,
    val cities: List<ExploreReferenceCityEntity>,
    val categories: List<ExploreReferenceCategoryEntity>,
)

internal class ExploreReferenceStore(
    private val dao: ExploreReferenceDao,
) {
    suspend fun read(): ExploreReferenceSnapshot? {
        val record = dao.readReference(EXPLORE_REFERENCE_SNAPSHOT_KEY) ?: return null
        return try {
            record.toSnapshot()
        } catch (_: CorruptExploreReferenceException) {
            dao.clearReferenceIfTimestampMatches(
                snapshotKey = EXPLORE_REFERENCE_SNAPSHOT_KEY,
                expectedCachedAtEpochMilliseconds = record.snapshot.cachedAtEpochMilliseconds,
            )
            null
        }
    }

    suspend fun replace(snapshot: ExploreReferenceSnapshot) {
        val write = snapshot.toReferenceWrite()
        dao.replaceReference(
            snapshot = write.snapshot,
            cities = write.cities,
            categories = write.categories,
        )
    }
}

internal fun ExploreReferenceSnapshot.toReferenceWrite(): ExploreReferenceWrite {
    requireValidForWrite()
    return ExploreReferenceWrite(
        snapshot = ExploreReferenceSnapshotEntity(
            snapshotKey = EXPLORE_REFERENCE_SNAPSHOT_KEY,
            cachedAtEpochMilliseconds = cachedAtEpochMilliseconds,
            cityCount = cities.size,
            categoryCount = categories.size,
        ),
        cities = cities.mapIndexed { position, city -> city.toEntity(position) },
        categories = categories.mapIndexed { position, category -> category.toEntity(position) },
    )
}

private fun ExploreReferenceRecord.toSnapshot(): ExploreReferenceSnapshot {
    requireConsistent()
    return ExploreReferenceSnapshot(
        cities = cities.map(ExploreReferenceCityEntity::toDomain),
        categories = categories.map(ExploreReferenceCategoryEntity::toDomain),
        cachedAtEpochMilliseconds = snapshot.cachedAtEpochMilliseconds,
    )
}

private fun ExploreReferenceSnapshot.requireValidForWrite() {
    require(cachedAtEpochMilliseconds >= 0) { "Explore reference timestamp must not be negative." }
    require(cities.size <= MAX_EXPLORE_REFERENCE_CITIES) {
        "Explore references must contain at most $MAX_EXPLORE_REFERENCE_CITIES cities."
    }
    require(categories.size <= MAX_EXPLORE_REFERENCE_CATEGORIES) {
        "Explore references must contain at most $MAX_EXPLORE_REFERENCE_CATEGORIES categories."
    }
    cities.forEach { city ->
        val invalidField = city.invalidReferenceFieldOrNull()
        require(invalidField == null) { "Invalid Explore reference city field: $invalidField" }
    }
    categories.forEach { category ->
        val invalidField = category.invalidReferenceFieldOrNull()
        require(invalidField == null) { "Invalid Explore reference category field: $invalidField" }
    }
    require(cities.map(City::id).distinct().size == cities.size) {
        "Explore reference cities must have unique ids."
    }
    require(categories.map(Category::id).distinct().size == categories.size) {
        "Explore reference categories must have unique ids."
    }
}

private fun ExploreReferenceRecord.requireConsistent() {
    val invalidField = snapshot.invalidMetadataFieldOrNull(
        actualCityCount = cities.size,
        actualCategoryCount = categories.size,
    ) ?: invalidRowsFieldOrNull()
    if (invalidField != null) {
        invalidReferenceValue(invalidField)
    }
}

private fun ExploreReferenceSnapshotEntity.invalidMetadataFieldOrNull(
    actualCityCount: Int,
    actualCategoryCount: Int,
): String? = when {
    snapshotKey != EXPLORE_REFERENCE_SNAPSHOT_KEY -> "snapshot_key"
    cachedAtEpochMilliseconds < 0 -> "cached_at_epoch_milliseconds"
    cityCount !in 0..MAX_EXPLORE_REFERENCE_CITIES -> "city_count"
    categoryCount !in 0..MAX_EXPLORE_REFERENCE_CATEGORIES -> "category_count"
    cityCount != actualCityCount -> "city_count"
    categoryCount != actualCategoryCount -> "category_count"
    else -> null
}

private fun ExploreReferenceRecord.invalidRowsFieldOrNull(): String? = when {
    cities.map(ExploreReferenceCityEntity::position) != cities.indices.toList() -> "city_position"
    categories.map(ExploreReferenceCategoryEntity::position) != categories.indices.toList() -> "category_position"
    cities.any { city -> city.snapshotKey != snapshot.snapshotKey } -> "city_snapshot_key"
    categories.any { category -> category.snapshotKey != snapshot.snapshotKey } -> "category_snapshot_key"
    else -> null
}

internal fun City.invalidReferenceFieldOrNull(): String? = when {
    id.isInvalidReferenceText(MAX_EXPLORE_REFERENCE_ID_LENGTH) -> "city_id"
    name.isInvalidReferenceText(MAX_EXPLORE_REFERENCE_NAME_LENGTH) -> "city_name"
    countryCode != BENIN_COUNTRY_CODE -> "country_code"
    else -> invalidCoordinateFieldOrNull()
}

private fun City.invalidCoordinateFieldOrNull(): String? {
    val cityLatitude = latitude
    val cityLongitude = longitude
    return when {
        (cityLatitude == null) != (cityLongitude == null) -> "coordinates"
        cityLatitude == null || cityLongitude == null -> null
        GeoPoint(latitude = cityLatitude, longitude = cityLongitude).isWithinBeninBounds -> null
        else -> "coordinates"
    }
}

internal fun Category.invalidReferenceFieldOrNull(): String? = when {
    id.isInvalidReferenceText(MAX_EXPLORE_REFERENCE_ID_LENGTH) -> "category_id"
    nameKey.isInvalidReferenceText(MAX_EXPLORE_REFERENCE_NAME_KEY_LENGTH) -> "name_key"
    else -> null
}

private fun String.isInvalidReferenceText(maximumLength: Int): Boolean = isBlank() || length > maximumLength

internal fun invalidReferenceValue(fieldName: String): Nothing = throw CorruptExploreReferenceException(fieldName)

internal class CorruptExploreReferenceException(fieldName: String) :
    IllegalStateException("Invalid persisted Explore reference field: $fieldName")

internal const val EXPLORE_REFERENCE_SNAPSHOT_KEY = "explore"
private const val BENIN_COUNTRY_CODE = "BJ"
private const val MAX_EXPLORE_REFERENCE_CITIES = 256
private const val MAX_EXPLORE_REFERENCE_CATEGORIES = 512
private const val MAX_EXPLORE_REFERENCE_ID_LENGTH = 128
private const val MAX_EXPLORE_REFERENCE_NAME_LENGTH = 120
private const val MAX_EXPLORE_REFERENCE_NAME_KEY_LENGTH = 160
