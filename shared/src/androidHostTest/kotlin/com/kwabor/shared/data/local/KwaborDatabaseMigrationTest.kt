package com.kwabor.shared.data.local

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class KwaborDatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = context.getDatabasePath(MIGRATION_DATABASE_NAME),
        driver = AndroidSQLiteDriver(),
        databaseClass = KwaborDatabase::class,
        databaseFactory = KwaborDatabaseConstructor::initialize,
        autoMigrationSpecs = emptyList(),
    )

    @Test
    fun autoMigrationFromOneToTwoPreservesListingCacheAndCreatesReferenceTables() {
        context.deleteDatabase(MIGRATION_DATABASE_NAME)
        try {
            migrationHelper.createDatabase(1).use(SQLiteConnection::seedExploreCache)

            migrationHelper.runMigrationsAndValidate(version = 2, migrations = emptyList()).use { database ->
                database.assertExploreMigrationPreservedCache()
            }
        } finally {
            context.deleteDatabase(MIGRATION_DATABASE_NAME)
        }
    }

    @Test
    fun autoMigrationFromTwoToThreePreservesLegacyCacheAndAddsNullableExploreV2Columns() {
        context.deleteDatabase(MIGRATION_DATABASE_NAME)
        try {
            migrationHelper.createDatabase(2).use(SQLiteConnection::seedExploreCache)

            migrationHelper.runMigrationsAndValidate(version = 3, migrations = emptyList()).use { database ->
                database.assertExploreMigrationPreservedCache()
                database.assertExploreV2ColumnsAreNullForLegacyRows()
            }
        } finally {
            context.deleteDatabase(MIGRATION_DATABASE_NAME)
        }
    }

    @Test
    fun autoMigrationFromOneToThreeIsNonDestructive() {
        context.deleteDatabase(MIGRATION_DATABASE_NAME)
        try {
            migrationHelper.createDatabase(1).use(SQLiteConnection::seedExploreCache)

            migrationHelper.runMigrationsAndValidate(version = 3, migrations = emptyList()).use { database ->
                database.assertExploreMigrationPreservedCache()
                database.assertExploreV2ColumnsAreNullForLegacyRows()
            }
        } finally {
            context.deleteDatabase(MIGRATION_DATABASE_NAME)
        }
    }

    @Test
    fun autoMigrationFromThreeToFourPreservesCacheAndCreatesEmptyInteractionOutbox() {
        context.deleteDatabase(MIGRATION_DATABASE_NAME)
        try {
            migrationHelper.createDatabase(INTERACTION_OUTBOX_PREVIOUS_DATABASE_VERSION)
                .use(SQLiteConnection::seedExploreCache)

            migrationHelper.runMigrationsAndValidate(version = 4, migrations = emptyList()).use { database ->
                database.assertExploreMigrationPreservedCache()
                database.assertExploreV2ColumnsAreNullForLegacyRows()
                database.assertInteractionOutboxIsEmpty()
            }
        } finally {
            context.deleteDatabase(MIGRATION_DATABASE_NAME)
        }
    }

    @Test
    fun autoMigrationFromTwoToFourIsNonDestructive() {
        context.deleteDatabase(MIGRATION_DATABASE_NAME)
        try {
            migrationHelper.createDatabase(2).use(SQLiteConnection::seedExploreCache)

            migrationHelper.runMigrationsAndValidate(version = 4, migrations = emptyList()).use { database ->
                database.assertExploreMigrationPreservedCache()
                database.assertExploreV2ColumnsAreNullForLegacyRows()
                database.assertInteractionOutboxIsEmpty()
            }
        } finally {
            context.deleteDatabase(MIGRATION_DATABASE_NAME)
        }
    }

    @Test
    fun autoMigrationFromOneToFourIsNonDestructive() {
        context.deleteDatabase(MIGRATION_DATABASE_NAME)
        try {
            migrationHelper.createDatabase(1).use(SQLiteConnection::seedExploreCache)

            migrationHelper.runMigrationsAndValidate(version = 4, migrations = emptyList()).use { database ->
                database.assertExploreMigrationPreservedCache()
                database.assertExploreV2ColumnsAreNullForLegacyRows()
                database.assertInteractionOutboxIsEmpty()
            }
        } finally {
            context.deleteDatabase(MIGRATION_DATABASE_NAME)
        }
    }

    @Test
    fun autoMigrationFromFourToFivePreservesExploreAndInteractionDataAndCreatesEmptyNotificationTables() {
        context.deleteDatabase(MIGRATION_DATABASE_NAME)
        try {
            migrationHelper.createDatabase(NOTIFICATION_PREVIOUS_DATABASE_VERSION).use { database ->
                database.seedExploreCache()
                database.seedInteractionOutbox()
            }

            migrationHelper.runMigrationsAndValidate(version = 5, migrations = emptyList()).use { database ->
                database.assertExploreMigrationPreservedCache()
                database.assertExploreV2ColumnsAreNullForLegacyRows()
                database.assertInteractionOutboxIsPreserved()
                database.assertNotificationTablesAreEmpty()
            }
        } finally {
            context.deleteDatabase(MIGRATION_DATABASE_NAME)
        }
    }

    @Test
    fun autoMigrationFromOneToFiveIsNonDestructive() {
        context.deleteDatabase(MIGRATION_DATABASE_NAME)
        try {
            migrationHelper.createDatabase(1).use(SQLiteConnection::seedExploreCache)

            migrationHelper.runMigrationsAndValidate(version = 5, migrations = emptyList()).use { database ->
                database.assertExploreMigrationPreservedCache()
                database.assertExploreV2ColumnsAreNullForLegacyRows()
                database.assertInteractionOutboxIsEmpty()
                database.assertNotificationTablesAreEmpty()
            }
        } finally {
            context.deleteDatabase(MIGRATION_DATABASE_NAME)
        }
    }
}

private fun SQLiteConnection.seedExploreCache() {
    execSQL(INSERT_EXPLORE_SNAPSHOT)
    execSQL(INSERT_EXPLORE_LISTING)
    execSQL(INSERT_EXPLORE_SNAPSHOT_ITEM)
}

private fun SQLiteConnection.assertExploreMigrationPreservedCache() {
    assertEquals(1L, singleLong("SELECT COUNT(*) FROM explore_cache_snapshot_items"))
    assertEquals(0L, singleLong("SELECT COUNT(*) FROM explore_reference_snapshots"))
}

private fun SQLiteConnection.assertExploreV2ColumnsAreNullForLegacyRows() {
    assertEquals(
        1L,
        singleLong(
            """
            SELECT COUNT(*)
            FROM explore_cache_snapshots
            WHERE server_snapshot_at_epoch_microseconds IS NULL
            """.trimIndent(),
        ),
    )
    assertEquals(
        1L,
        singleLong(
            """
            SELECT COUNT(*)
            FROM explore_cached_listings
            WHERE cover_image_alt IS NULL
              AND views_count IS NULL
              AND event_start_at_epoch_milliseconds IS NULL
              AND event_end_at_epoch_milliseconds IS NULL
            """.trimIndent(),
        ),
    )
    assertEquals(
        1L,
        singleLong(
            """
            SELECT COUNT(*)
            FROM explore_cache_snapshot_items
            WHERE is_event_ended IS NULL
            """.trimIndent(),
        ),
    )
}

private fun SQLiteConnection.assertInteractionOutboxIsEmpty() {
    assertEquals(0L, singleLong("SELECT COUNT(*) FROM interaction_outbox_operations"))
}

private fun SQLiteConnection.seedInteractionOutbox() {
    execSQL(INSERT_INTERACTION_OUTBOX_OPERATION)
}

private fun SQLiteConnection.assertInteractionOutboxIsPreserved() {
    assertEquals(1L, singleLong("SELECT COUNT(*) FROM interaction_outbox_operations"))
    assertEquals(
        1L,
        singleLong(
            """
            SELECT COUNT(*)
            FROM interaction_outbox_operations
            WHERE account_id = '10000000-0000-4000-8000-000000000001'
              AND listing_id = '20000000-0000-4000-8000-000000000001'
              AND kind = 'favorite'
              AND desired_selected = 1
              AND attempt_count = 1
              AND next_attempt_at_epoch_milliseconds = 2000
            """.trimIndent(),
        ),
    )
}

private fun SQLiteConnection.assertNotificationTablesAreEmpty() {
    listOf(
        "notification_inbox_snapshots",
        "notification_inbox_items",
        "notification_sync_operations",
        "notification_preferences_cache",
    ).forEach { table ->
        assertEquals(0L, singleLong("SELECT COUNT(*) FROM $table"), table)
    }
}

private fun SQLiteConnection.singleLong(query: String): Long = prepare(query).use { statement ->
    check(statement.step()) { "Migration verification query returned no row." }
    statement.getLong(0)
}

private const val MIGRATION_DATABASE_NAME = "kwabor-room-migration"
private const val INTERACTION_OUTBOX_PREVIOUS_DATABASE_VERSION = 3
private const val NOTIFICATION_PREVIOUS_DATABASE_VERSION = 4

private val INSERT_EXPLORE_SNAPSHOT =
    """
    INSERT INTO explore_cache_snapshots (
        snapshot_key,
        next_cursor,
        cached_at_epoch_milliseconds,
        item_count
    ) VALUES ('explore:migration', NULL, 1000, 1)
    """.trimIndent()

private val INSERT_EXPLORE_LISTING =
    """
    INSERT INTO explore_cached_listings (
        listing_id,
        listing_type,
        listing_class,
        status,
        name,
        city_id,
        category_id,
        cover_image_url,
        price_from_xof,
        rating_average,
        likes_count,
        verified,
        sponsored_until_epoch_milliseconds,
        content_cached_at_epoch_milliseconds
    ) VALUES (
        'listing-migration',
        'establishment',
        'commercial',
        'published',
        'Migration',
        'cotonou',
        'restaurants',
        NULL,
        NULL,
        NULL,
        0,
        0,
        NULL,
        1000
    )
    """.trimIndent()

private val INSERT_EXPLORE_SNAPSHOT_ITEM =
    """
    INSERT INTO explore_cache_snapshot_items (
        snapshot_key,
        listing_id,
        position,
        is_sponsored_placement
    ) VALUES ('explore:migration', 'listing-migration', 0, NULL)
    """.trimIndent()

private val INSERT_INTERACTION_OUTBOX_OPERATION =
    """
    INSERT INTO interaction_outbox_operations (
        account_id,
        listing_id,
        kind,
        desired_selected,
        enqueued_at_epoch_milliseconds,
        attempt_count,
        next_attempt_at_epoch_milliseconds,
        terminal_error_code
    ) VALUES (
        '10000000-0000-4000-8000-000000000001',
        '20000000-0000-4000-8000-000000000001',
        'favorite',
        1,
        1000,
        1,
        2000,
        NULL
    )
    """.trimIndent()
