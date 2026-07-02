package com.kwabor.shared.domain.core

interface ClockProvider {
    fun nowEpochMilliseconds(): Long
}
