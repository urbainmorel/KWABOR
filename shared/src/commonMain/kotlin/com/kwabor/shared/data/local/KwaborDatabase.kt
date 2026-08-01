package com.kwabor.shared.data.local

import androidx.room.AutoMigration
import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.coroutines.CoroutineContext

internal const val KWABOR_DATABASE_FILENAME = "kwabor.db"

@Database(
    entities = [
        ExploreCacheSnapshotEntity::class,
        ExploreCachedListingEntity::class,
        ExploreCacheSnapshotItemEntity::class,
        ExploreReferenceSnapshotEntity::class,
        ExploreReferenceCityEntity::class,
        ExploreReferenceCategoryEntity::class,
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
@ConstructedBy(KwaborDatabaseConstructor::class)
internal abstract class KwaborDatabase : RoomDatabase() {
    internal abstract fun exploreCacheDao(): ExploreCacheDao

    internal abstract fun exploreReferenceDao(): ExploreReferenceDao

    internal abstract fun exploreFeedPersistenceDao(): ExploreFeedPersistenceDao

    internal abstract fun explorePersistenceWatermarkDao(): ExplorePersistenceWatermarkDao
}

internal expect object KwaborDatabaseConstructor : RoomDatabaseConstructor<KwaborDatabase>

internal fun buildKwaborDatabase(
    builder: RoomDatabase.Builder<KwaborDatabase>,
    queryCoroutineContext: CoroutineContext,
    driver: SQLiteDriver = BundledSQLiteDriver(),
): KwaborDatabase = builder
    .setDriver(driver)
    .setQueryCoroutineContext(queryCoroutineContext)
    .build()
