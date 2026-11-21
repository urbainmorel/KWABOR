package com.kwabor.shared.data.catalog

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.sqlite.SQLiteException
import com.kwabor.shared.domain.catalog.CatalogDetail
import com.kwabor.shared.domain.core.ClockProvider
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.hours

@Entity(tableName = "demo_catalog_detail_cache", primaryKeys = ["project_url", "listing_id"])
internal data class DemoCatalogDetailRecord(
    @ColumnInfo(name = "project_url") val projectUrl: String,
    @ColumnInfo(name = "listing_id") val listingId: String,
    @ColumnInfo(name = "cached_at") val cachedAt: Long,
    val payload: String,
)

@Dao
internal interface DemoCatalogDetailDao {
    @Query("SELECT * FROM demo_catalog_detail_cache WHERE project_url = :projectUrl AND listing_id = :listingId")
    suspend fun find(projectUrl: String, listingId: String): DemoCatalogDetailRecord?

    @Query("DELETE FROM demo_catalog_detail_cache WHERE project_url = :projectUrl AND listing_id = :listingId")
    suspend fun delete(projectUrl: String, listingId: String)

    @Upsert
    suspend fun upsert(record: DemoCatalogDetailRecord)

    @Query(
        """
        DELETE FROM demo_catalog_detail_cache
        WHERE cached_at < :oldest OR rowid NOT IN (
            SELECT rowid FROM demo_catalog_detail_cache ORDER BY cached_at DESC, rowid DESC LIMIT 60
        )
        """,
    )
    suspend fun prune(oldest: Long)

    @Transaction
    suspend fun save(record: DemoCatalogDetailRecord, oldest: Long) {
        upsert(record)
        prune(oldest)
    }
}

internal class DemoCatalogDetailCache(
    private val dao: DemoCatalogDetailDao,
    private val projectUrl: String,
    private val clock: ClockProvider,
) {
    suspend fun read(listingId: String): CatalogDetail? = cacheResult {
        val record = dao.find(projectUrl, listingId) ?: return@cacheResult null
        val now = clock.nowEpochMilliseconds()
        if (record.cachedAt !in (now - CACHE_MAX_AGE)..now) return@cacheResult null
        Json.decodeFromString<CatalogDetailPayloadDto>(record.payload)
            .takeIf { payload -> payload.id == listingId && payload.isDemo }
            ?.toDomain()
    }.getOrNull()

    suspend fun save(listingId: String, payload: CatalogDetailPayloadDto) {
        if (!payload.isDemo) {
            forget(listingId)
            return
        }
        val now = clock.nowEpochMilliseconds()
        cacheResult {
            dao.save(
                record = DemoCatalogDetailRecord(projectUrl, listingId, now, Json.encodeToString(payload)),
                oldest = now - CACHE_MAX_AGE,
            )
        }
    }

    suspend fun forget(listingId: String) {
        cacheResult { dao.delete(projectUrl, listingId) }
    }
}

private val CatalogDetailPayloadDto.isDemo: Boolean
    get() = "demo-kwabor" in tags && "contenu-fictif" in tags

// A broken local cache must not mask the server response or the original network error.
private suspend fun <T> cacheResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (exception: SQLiteException) {
    Result.failure(exception)
} catch (exception: SerializationException) {
    Result.failure(exception)
} catch (exception: CatalogDataException) {
    Result.failure(exception)
}

private val CACHE_MAX_AGE = 24.hours.inWholeMilliseconds
