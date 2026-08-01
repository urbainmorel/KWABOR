package com.kwabor.shared.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase

internal fun createAndroidKwaborDatabaseBuilder(context: Context): RoomDatabase.Builder<KwaborDatabase> =
    Room.databaseBuilder<KwaborDatabase>(
        context = context.applicationContext,
        name = KWABOR_DATABASE_FILENAME,
        factory = KwaborDatabaseConstructor::initialize,
    )
