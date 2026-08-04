package com.kwabor.shared.presentation.detail

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

fun catalogDetailMinuteTicks(): Flow<Unit> = flow {
    while (true) {
        delay(CATALOG_DETAIL_TEMPORAL_REFRESH_MILLISECONDS)
        emit(Unit)
    }
}

private const val CATALOG_DETAIL_TEMPORAL_REFRESH_MILLISECONDS = 60_000L
