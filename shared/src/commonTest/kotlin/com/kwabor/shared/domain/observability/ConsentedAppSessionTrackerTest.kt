package com.kwabor.shared.domain.observability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConsentedAppSessionTrackerTest {
    @Test
    fun restoredConsentAtInitialForegroundEmitsExactlyOnceWithoutLifecycleDuplicate() {
        val fixture = SessionTrackerFixture()
        fixture.tracker.updateMeasurementEligibility(allowed = true)

        val first = fixture.tracker.onForeground()
        val duplicate = fixture.tracker.onForeground()

        assertNotNull(first)
        assertEquals("observed_session_started", first.eventName)
        assertNull(duplicate)
        assertEquals(1, fixture.store.writeCount)
    }

    @Test
    fun newOptInAfterInitialForegroundEmitsExactlyOnceWithoutLifecycleDuplicate() {
        val fixture = SessionTrackerFixture()

        assertNull(fixture.tracker.onForeground())
        val first = fixture.tracker.updateMeasurementEligibility(allowed = true)
        val duplicateForeground = fixture.tracker.onForeground()
        val duplicateEligibility = fixture.tracker.updateMeasurementEligibility(allowed = true)

        assertNotNull(first)
        assertEquals("observed_session_started", first.eventName)
        assertNull(duplicateForeground)
        assertNull(duplicateEligibility)
        assertEquals(1, fixture.store.writeCount)
    }

    @Test
    fun foregroundAtTwentyNineMinutesFiftyNineSecondsResumesExistingSession() {
        val fixture = SessionTrackerFixture()
        fixture.startFirstSessionAndBackground()
        fixture.time.advance(monotonicDelta = SESSION_INACTIVITY_MILLISECONDS - ONE_SECOND_MILLISECONDS)

        assertNull(fixture.tracker.onForeground())
    }

    @Test
    fun foregroundAtExactlyThirtyMinutesStartsNewSession() {
        val fixture = SessionTrackerFixture()
        fixture.startFirstSessionAndBackground()
        fixture.time.advance(monotonicDelta = SESSION_INACTIVITY_MILLISECONDS)

        assertNotNull(fixture.tracker.onForeground())
    }

    @Test
    fun forwardWallClockJumpCannotCreateSessionBeforeMonotonicThreshold() {
        val fixture = SessionTrackerFixture()
        fixture.startFirstSessionAndBackground()
        fixture.time.advance(
            monotonicDelta = SESSION_INACTIVITY_MILLISECONDS - ONE_SECOND_MILLISECONDS,
            wallDelta = FORWARD_WALL_CLOCK_JUMP_MILLISECONDS,
        )

        assertNull(fixture.tracker.onForeground())
    }

    @Test
    fun backwardWallClockJumpCannotHideReachedMonotonicThreshold() {
        val fixture = SessionTrackerFixture()
        fixture.startFirstSessionAndBackground()
        fixture.time.advance(
            monotonicDelta = SESSION_INACTIVITY_MILLISECONDS,
            wallDelta = -BACKWARD_WALL_CLOCK_JUMP_MILLISECONDS,
        )

        assertNotNull(fixture.tracker.onForeground())
    }

    @Test
    fun relaunchOnSameBootUsesPersistedMonotonicCheckpoint() {
        val store = FakeObservedAppSessionStore()
        val firstTime = FakeObservedAppSessionTimeSource(bootIdentifier = null)
        val firstTracker = ConsentedAppSessionTracker(firstTime, store)
        firstTracker.updateMeasurementEligibility(allowed = true)
        assertNotNull(firstTracker.onForeground())
        firstTracker.onBackground()

        val relaunchedTime = FakeObservedAppSessionTimeSource(
            wallEpochMilliseconds = firstTime.mark.wallEpochMilliseconds + SESSION_INACTIVITY_MILLISECONDS,
            monotonicMilliseconds = firstTime.mark.monotonicMilliseconds + SESSION_INACTIVITY_MILLISECONDS,
            bootIdentifier = null,
            bootAnchorEpochMilliseconds = firstTime.mark.bootAnchorEpochMilliseconds,
        )
        val relaunchedTracker = ConsentedAppSessionTracker(relaunchedTime, store)
        relaunchedTracker.updateMeasurementEligibility(allowed = true)

        assertNotNull(relaunchedTracker.onForeground())
    }

    @Test
    fun rebootNeverUsesEpochDifferenceAsSessionEvidence() {
        val fixture = SessionTrackerFixture()
        fixture.startFirstSessionAndBackground()
        fixture.time.mark = fixture.time.mark.copy(
            wallEpochMilliseconds = fixture.time.mark.wallEpochMilliseconds + FORWARD_WALL_CLOCK_JUMP_MILLISECONDS,
            monotonicMilliseconds = fixture.time.mark.monotonicMilliseconds + SESSION_INACTIVITY_MILLISECONDS,
            bootIdentifier = 2L,
            bootAnchorEpochMilliseconds = fixture.time.mark.bootAnchorEpochMilliseconds + ONE_SECOND_MILLISECONDS,
        )

        assertNull(fixture.tracker.onForeground())
    }

    @Test
    fun uncertainBootAnchorFailsClosedAcrossRelaunch() {
        val store = FakeObservedAppSessionStore()
        val firstTime = FakeObservedAppSessionTimeSource(bootIdentifier = null)
        val firstTracker = ConsentedAppSessionTracker(firstTime, store)
        firstTracker.updateMeasurementEligibility(allowed = true)
        assertNotNull(firstTracker.onForeground())
        firstTracker.onBackground()

        val uncertainTime = FakeObservedAppSessionTimeSource(
            wallEpochMilliseconds = firstTime.mark.wallEpochMilliseconds + SESSION_INACTIVITY_MILLISECONDS,
            monotonicMilliseconds = firstTime.mark.monotonicMilliseconds + SESSION_INACTIVITY_MILLISECONDS,
            bootIdentifier = null,
            bootAnchorEpochMilliseconds = firstTime.mark.bootAnchorEpochMilliseconds +
                UNCERTAIN_ANCHOR_SHIFT_MILLISECONDS,
        )
        val relaunchedTracker = ConsentedAppSessionTracker(uncertainTime, store)
        relaunchedTracker.updateMeasurementEligibility(allowed = true)

        assertNull(relaunchedTracker.onForeground())
    }

    @Test
    fun monotonicRegressionFailsClosedEvenWhenBootIdentifierMatches() {
        val fixture = SessionTrackerFixture()
        fixture.startFirstSessionAndBackground()
        fixture.time.mark = fixture.time.mark.copy(
            wallEpochMilliseconds = fixture.time.mark.wallEpochMilliseconds + SESSION_INACTIVITY_MILLISECONDS,
            monotonicMilliseconds = fixture.time.mark.monotonicMilliseconds - ONE_SECOND_MILLISECONDS,
        )

        assertNull(fixture.tracker.onForeground())
    }

    @Test
    fun revocationClearsCheckpointAndStopsFutureMeasurement() {
        val fixture = SessionTrackerFixture()
        fixture.tracker.updateMeasurementEligibility(allowed = true)
        assertNotNull(fixture.tracker.onForeground())

        assertTrue(fixture.tracker.revoke())
        fixture.tracker.onBackground()
        fixture.time.advance(monotonicDelta = SESSION_INACTIVITY_MILLISECONDS)

        assertNull(fixture.tracker.onForeground())
        assertEquals(1, fixture.store.clearCount)
        assertNull(fixture.store.checkpoint)
    }

    @Test
    fun failedRevocationClearBlocksRegrantUntilDurableClearSucceeds() {
        val fixture = SessionTrackerFixture()
        fixture.tracker.updateMeasurementEligibility(allowed = true)
        assertNotNull(fixture.tracker.onForeground())
        fixture.store.clearsSucceed = false

        assertFalse(fixture.tracker.revoke())
        assertNull(fixture.tracker.updateMeasurementEligibility(allowed = true))
        fixture.store.clearsSucceed = true

        assertNotNull(fixture.tracker.onForeground())
        assertEquals(3, fixture.store.clearCount)
    }

    @Test
    fun relaunchAfterLongForegroundDoesNotInventUnobservedInactivity() {
        val store = FakeObservedAppSessionStore()
        val firstTime = FakeObservedAppSessionTimeSource()
        val firstTracker = ConsentedAppSessionTracker(firstTime, store)
        firstTracker.updateMeasurementEligibility(allowed = true)
        assertNotNull(firstTracker.onForeground())

        val relaunchedTime = FakeObservedAppSessionTimeSource(
            wallEpochMilliseconds = firstTime.mark.wallEpochMilliseconds + SESSION_INACTIVITY_MILLISECONDS,
            monotonicMilliseconds = firstTime.mark.monotonicMilliseconds + SESSION_INACTIVITY_MILLISECONDS,
        )
        val relaunchedTracker = ConsentedAppSessionTracker(relaunchedTime, store)
        relaunchedTracker.updateMeasurementEligibility(allowed = true)

        assertNull(relaunchedTracker.onForeground())
    }

    @Test
    fun failedCheckpointWriteNeverEmitsAnUnrecoverableSession() {
        val fixture = SessionTrackerFixture()
        fixture.store.writesSucceed = false
        fixture.tracker.updateMeasurementEligibility(allowed = true)

        assertNull(fixture.tracker.onForeground())
        assertNull(fixture.store.checkpoint)
    }
}

private class SessionTrackerFixture {
    val time = FakeObservedAppSessionTimeSource()
    val store = FakeObservedAppSessionStore()
    val tracker = ConsentedAppSessionTracker(time, store)

    fun startFirstSessionAndBackground() {
        tracker.updateMeasurementEligibility(allowed = true)
        assertNotNull(tracker.onForeground())
        tracker.onBackground()
    }
}

private class FakeObservedAppSessionTimeSource(
    wallEpochMilliseconds: Long = INITIAL_WALL_TIME_MILLISECONDS,
    monotonicMilliseconds: Long = INITIAL_MONOTONIC_TIME_MILLISECONDS,
    bootIdentifier: Long? = 1L,
    bootAnchorEpochMilliseconds: Long = INITIAL_BOOT_ANCHOR_MILLISECONDS,
) : ObservedAppSessionTimeSource {
    var mark = ObservedAppSessionTimeMark(
        wallEpochMilliseconds = wallEpochMilliseconds,
        monotonicMilliseconds = monotonicMilliseconds,
        bootIdentifier = bootIdentifier,
        bootAnchorEpochMilliseconds = bootAnchorEpochMilliseconds,
    )

    override fun read(): ObservedAppSessionTimeRead = ObservedAppSessionTimeRead.Available(mark)

    fun advance(monotonicDelta: Long, wallDelta: Long = monotonicDelta) {
        mark = mark.copy(
            wallEpochMilliseconds = mark.wallEpochMilliseconds + wallDelta,
            monotonicMilliseconds = mark.monotonicMilliseconds + monotonicDelta,
        )
    }
}

private class FakeObservedAppSessionStore : ObservedAppSessionStore {
    var checkpoint: ObservedAppSessionCheckpointRead? = null
        private set
    var writesSucceed = true
    var clearsSucceed = true
    var writeCount = 0
        private set
    var clearCount = 0
        private set

    override fun read(): ObservedAppSessionCheckpointRead = checkpoint ?: ObservedAppSessionCheckpointRead.Missing

    override fun writeForeground(): Boolean {
        writeCount += 1
        if (!writesSucceed) return false
        checkpoint = ObservedAppSessionCheckpointRead.Foreground
        return true
    }

    override fun writeBackgroundedAt(timeMark: ObservedAppSessionTimeMark): Boolean {
        writeCount += 1
        if (!writesSucceed) return false
        checkpoint = ObservedAppSessionCheckpointRead.BackgroundedAt(timeMark)
        return true
    }

    override fun clear(): Boolean {
        clearCount += 1
        if (!clearsSucceed) return false
        checkpoint = null
        return true
    }
}

private const val ONE_SECOND_MILLISECONDS = 1_000L
private const val SESSION_INACTIVITY_MILLISECONDS = 30L * 60L * ONE_SECOND_MILLISECONDS
private const val INITIAL_WALL_TIME_MILLISECONDS = 1_700_000_000_000L
private const val INITIAL_MONOTONIC_TIME_MILLISECONDS = 60_000L
private const val INITIAL_BOOT_ANCHOR_MILLISECONDS =
    INITIAL_WALL_TIME_MILLISECONDS - INITIAL_MONOTONIC_TIME_MILLISECONDS
private const val FORWARD_WALL_CLOCK_JUMP_MILLISECONDS = 2L * 60L * 60L * ONE_SECOND_MILLISECONDS
private const val BACKWARD_WALL_CLOCK_JUMP_MILLISECONDS = 60L * 60L * ONE_SECOND_MILLISECONDS
private const val UNCERTAIN_ANCHOR_SHIFT_MILLISECONDS = 10L * ONE_SECOND_MILLISECONDS
