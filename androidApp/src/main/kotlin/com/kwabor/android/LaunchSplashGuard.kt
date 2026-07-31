package com.kwabor.android

internal class LaunchSplashGuard(
    private val nowMillis: () -> Long,
    private val minimumVisibleDurationMillis: Long,
) {
    private val startedAtMillis = nowMillis()

    init {
        require(minimumVisibleDurationMillis >= 0L) {
            "The minimum splash duration cannot be negative."
        }
    }

    fun shouldKeepOnScreen(): Boolean = nowMillis() - startedAtMillis < minimumVisibleDurationMillis
}

internal const val COLD_START_MINIMUM_SPLASH_MILLIS = 1_000L

internal fun launchSplashMinimumVisibleDurationMillis(isFirstActivityInProcess: Boolean): Long =
    if (isFirstActivityInProcess) COLD_START_MINIMUM_SPLASH_MILLIS else 0L

internal class LaunchProcessState {
    private var hasCreatedActivity = false

    fun consumeIsFirstActivityInProcess(): Boolean {
        val isFirstActivityInProcess = !hasCreatedActivity
        hasCreatedActivity = true
        return isFirstActivityInProcess
    }
}
