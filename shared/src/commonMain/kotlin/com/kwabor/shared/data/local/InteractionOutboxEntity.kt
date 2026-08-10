package com.kwabor.shared.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "interaction_outbox_operations",
    indices = [
        Index(
            value = ["account_id", "listing_id", "kind"],
            unique = true,
        ),
        Index(
            value = [
                "account_id",
                "terminal_error_code",
                "next_attempt_at_epoch_milliseconds",
                "enqueued_at_epoch_milliseconds",
            ],
        ),
        Index(
            value = ["account_id", "enqueued_at_epoch_milliseconds", "operation_id"],
        ),
    ],
)
internal data class InteractionOutboxEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "operation_id")
    val operationId: Long = 0,
    @ColumnInfo(name = "account_id")
    val accountId: String,
    @ColumnInfo(name = "listing_id")
    val listingId: String,
    @ColumnInfo(name = "kind")
    val kind: String,
    @ColumnInfo(name = "desired_selected")
    val desiredSelectedRaw: Long,
    @ColumnInfo(name = "enqueued_at_epoch_milliseconds")
    val enqueuedAtEpochMilliseconds: Long,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Long,
    @ColumnInfo(name = "next_attempt_at_epoch_milliseconds")
    val nextAttemptAtEpochMilliseconds: Long,
    @ColumnInfo(name = "terminal_error_code")
    val terminalErrorCode: String?,
)
