package com.kwabor.shared.data.local

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
    ],
    version = 1,
    exportSchema = true,
)
@ConstructedBy(KwaborDatabaseConstructor::class)
internal abstract class KwaborDatabase : RoomDatabase() {
    internal abstract fun exploreCacheDao(): ExploreCacheDao
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
