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

internal enum class KwaborDatabaseStorageMode {
    Durable,
    MemoryOnly,
}

internal data class KwaborDatabaseBuilderResult(
    val storageMode: KwaborDatabaseStorageMode,
    val builderFactory: () -> RoomDatabase.Builder<KwaborDatabase>,
) {
    val supportsDurableInteractionOutbox: Boolean
        get() = storageMode == KwaborDatabaseStorageMode.Durable

    val supportsDurableNotificationStorage: Boolean
        get() = storageMode == KwaborDatabaseStorageMode.Durable

    fun createBuilder(): RoomDatabase.Builder<KwaborDatabase> = builderFactory()
}

@Database(
    entities = [
        ExploreCacheSnapshotEntity::class,
        ExploreCachedListingEntity::class,
        ExploreCacheSnapshotItemEntity::class,
        ExploreReferenceSnapshotEntity::class,
        ExploreReferenceCityEntity::class,
        ExploreReferenceCategoryEntity::class,
        InteractionOutboxEntity::class,
        NotificationInboxSnapshotEntity::class,
        NotificationInboxItemEntity::class,
        NotificationSyncOperationEntity::class,
        NotificationPreferenceEntity::class,
    ],
    version = 5,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
    ],
)
@ConstructedBy(KwaborDatabaseConstructor::class)
internal abstract class KwaborDatabase : RoomDatabase() {
    internal abstract fun exploreCacheDao(): ExploreCacheDao

    internal abstract fun exploreReferenceDao(): ExploreReferenceDao

    internal abstract fun exploreFeedPersistenceDao(): ExploreFeedPersistenceDao

    internal abstract fun explorePersistenceWatermarkDao(): ExplorePersistenceWatermarkDao

    internal abstract fun searchCacheDao(): SearchCacheDao

    internal abstract fun interactionOutboxDao(): InteractionOutboxDao

    internal abstract fun notificationInboxDao(): NotificationInboxDao

    internal abstract fun notificationPreferencesDao(): NotificationPreferencesDao

    internal abstract fun notificationOutboxDao(): NotificationOutboxDao

    internal abstract fun notificationConfirmationSettlementDao(): NotificationConfirmationSettlementDao

    internal abstract fun accountPrivateDataPurgeDao(): AccountPrivateDataPurgeDao
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
