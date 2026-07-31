package com.kwabor.android.ui.screens.onboarding

import androidx.lifecycle.Lifecycle
import com.kwabor.android.onboarding.IntroMediaSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntroPlaybackPolicyTest {
    @Test
    fun remotePlayerFailureFallsBackToBundledMedia() {
        val source = IntroMediaSource.Remote(file = File("remote-intro.mp4"), revision = 2)

        assertEquals(IntroPlaybackFailureAction.UseBundled, source.failureAction())
    }

    @Test
    fun bundledPlayerFailureCompletesIntroWithoutBlockingNavigation() {
        assertEquals(IntroPlaybackFailureAction.CompleteIntro, IntroMediaSource.Bundled.failureAction())
    }

    @Test
    fun continuityWordmarkStaysVisibleUntilTheFirstRenderedFrame() {
        val initialVisibility = IntroContinuityVisibility.Visible

        assertEquals(IntroContinuityVisibility.Visible, initialVisibility)
        assertEquals(
            IntroContinuityVisibility.Hidden,
            initialVisibility.afterFirstFrameRendered(),
        )
    }

    @Test
    fun firstFrameTransitionIsIdempotent() {
        assertEquals(
            IntroContinuityVisibility.Hidden,
            IntroContinuityVisibility.Hidden.afterFirstFrameRendered(),
        )
    }

    @Test
    fun continuityRequiresFiveHundredVisibleMillisecondsAndDistinctFrames() {
        val barrier = IntroContinuityBarrier()

        assertEquals(500L, INTRO_WORDMARK_MINIMUM_VISIBLE_MILLIS)
        assertFalse(barrier.onVisibleFrame(frameTimeMillis = 1_000L))
        assertFalse(barrier.onVisibleFrame(frameTimeMillis = 1_000L))
        assertFalse(barrier.onVisibleFrame(frameTimeMillis = 1_499L))
        assertTrue(barrier.onVisibleFrame(frameTimeMillis = 1_500L))
        assertEquals(IntroPlayerAttachment.Ready, barrier.state)
        assertFalse(barrier.onVisibleFrame(frameTimeMillis = 2_000L))
    }

    @Test
    fun regressedFrameClockRestartsTheContinuityHoldFailClosed() {
        val barrier = IntroContinuityBarrier()

        assertFalse(barrier.onVisibleFrame(frameTimeMillis = 1_000L))
        assertFalse(barrier.onVisibleFrame(frameTimeMillis = 900L))
        assertFalse(barrier.onVisibleFrame(frameTimeMillis = 1_399L))
        assertTrue(barrier.onVisibleFrame(frameTimeMillis = 1_400L))
    }

    @Test
    fun hiddenWindowResetRequiresANewCompleteContinuityHold() {
        val barrier = IntroContinuityBarrier()

        assertFalse(barrier.onVisibleFrame(frameTimeMillis = 1_000L))
        assertFalse(barrier.onVisibleFrame(frameTimeMillis = 1_400L))
        barrier.reset()

        assertFalse(barrier.onVisibleFrame(frameTimeMillis = 2_000L))
        assertFalse(barrier.onVisibleFrame(frameTimeMillis = 2_499L))
        assertTrue(barrier.onVisibleFrame(frameTimeMillis = 2_500L))
    }

    @Test
    fun invalidContinuityDurationsAndFrameTimesAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            IntroContinuityBarrier(minimumVisibleMillis = -1L)
        }
        assertFailsWith<IllegalArgumentException> {
            IntroContinuityBarrier().onVisibleFrame(frameTimeMillis = -1L)
        }
    }

    @Test
    fun reducedMotionUsesStaticFallbackWhileDefaultModeUsesVideoContinuity() {
        assertEquals(IntroPrimaryMode.StaticFallback, introPrimaryMode(reducedMotion = true))
        assertEquals(IntroPrimaryMode.VideoWithContinuity, introPrimaryMode(reducedMotion = false))
    }

    @Test
    fun foregroundLifecycleCannotStartPlaybackBeforeTheSurfaceBarrier() {
        val reducer = IntroPlaybackReducer(initiallyForeground = true)

        assertEquals(emptyList(), reducer.onStarted())
        assertEquals(emptyList(), reducer.onPlayerSurfaceAttached())
        assertEquals(emptyList(), reducer.onFirstFrameRendered())
        assertEquals(false, reducer.isContinuityPresented)
    }

    @Test
    fun completedContinuityStartsAtZeroAndRevealsAnAlreadyRenderedFrame() {
        val reducer = IntroPlaybackReducer(initiallyForeground = true)
        reducer.onStarted()
        reducer.onPlayerSurfaceAttached()
        reducer.onFirstFrameRendered()

        assertEquals(
            listOf(
                IntroPlaybackEffect.SeekToStart,
                IntroPlaybackEffect.Play,
                IntroPlaybackEffect.RevealVideo,
            ),
            reducer.onContinuityPresented(),
        )
        assertEquals(emptyList(), reducer.onContinuityPresented())
        assertTrue(reducer.isVideoRevealed)
    }

    @Test
    fun backgroundReadinessSeeksAndRevealsButWaitsForForegroundToPlay() {
        val reducer = IntroPlaybackReducer(initiallyForeground = false)
        reducer.onStarted()
        reducer.onPlayerSurfaceAttached()
        reducer.onFirstFrameRendered()

        assertEquals(
            listOf(
                IntroPlaybackEffect.SeekToStart,
                IntroPlaybackEffect.RevealVideo,
            ),
            reducer.onContinuityPresented(),
        )
        assertEquals(emptyList(), reducer.onLifecycleEvent(Lifecycle.Event.ON_START))
        assertEquals(
            listOf(IntroPlaybackEffect.Play),
            reducer.onLifecycleEvent(Lifecycle.Event.ON_RESUME),
        )
    }

    @Test
    fun pauseAndResumeNeverSeekAnAlreadyStartedVideoAgain() {
        val reducer = readyForegroundReducer()

        assertEquals(
            listOf(IntroPlaybackEffect.Pause),
            reducer.onLifecycleEvent(Lifecycle.Event.ON_PAUSE),
        )
        assertEquals(
            listOf(IntroPlaybackEffect.Play),
            reducer.onLifecycleEvent(Lifecycle.Event.ON_RESUME),
        )
    }

    @Test
    fun surfaceDetachmentBeforeRevealRearmsTheSeekAndIgnoresLateFrames() {
        val reducer = readyForegroundReducer()

        assertEquals(
            listOf(IntroPlaybackEffect.Pause),
            reducer.onPlayerSurfaceUnavailable(),
        )
        assertEquals(emptyList(), reducer.onFirstFrameRendered())
        assertEquals(
            listOf(IntroPlaybackEffect.SeekToStart, IntroPlaybackEffect.Play),
            reducer.onPlayerSurfaceAttached(),
        )
    }

    @Test
    fun surfaceDetachmentAfterRevealResumesWithoutRewinding() {
        val reducer = readyForegroundReducer()
        assertEquals(
            listOf(IntroPlaybackEffect.RevealVideo),
            reducer.onFirstFrameRendered(),
        )

        assertEquals(
            listOf(IntroPlaybackEffect.Pause),
            reducer.onPlayerSurfaceUnavailable(),
        )
        assertEquals(
            listOf(IntroPlaybackEffect.Play),
            reducer.onPlayerSurfaceAttached(),
        )
    }

    @Test
    fun backgroundCompletionIsDeferredAndDeliveredExactlyOnce() {
        val reducer = readyForegroundReducer()
        reducer.onLifecycleEvent(Lifecycle.Event.ON_STOP)

        assertEquals(emptyList(), reducer.onPlaybackEnded())
        assertEquals(
            listOf(IntroPlaybackEffect.Complete),
            reducer.onLifecycleEvent(Lifecycle.Event.ON_RESUME),
        )
        assertEquals(emptyList(), reducer.onPlaybackEnded())
        assertEquals(emptyList(), reducer.onLifecycleEvent(Lifecycle.Event.ON_RESUME))
    }

    @Test
    fun failureIsTerminalAndCannotRaceWithCompletion() {
        val reducer = readyForegroundReducer()

        assertEquals(
            listOf(IntroPlaybackEffect.Pause, IntroPlaybackEffect.Fail),
            reducer.onPlaybackFailed(),
        )
        assertEquals(emptyList(), reducer.onPlaybackFailed())
        assertEquals(emptyList(), reducer.onPlaybackEnded())
    }

    @Test
    fun foregroundFailureBeforeContinuityWaitsForTheWordmarkBarrier() {
        val reducer = IntroPlaybackReducer(initiallyForeground = true)
        reducer.onStarted()
        reducer.onPlayerSurfaceAttached()

        assertEquals(emptyList(), reducer.onPlaybackFailed())
        assertEquals(
            listOf(IntroPlaybackEffect.Fail),
            reducer.onContinuityPresented(),
        )
        assertEquals(emptyList(), reducer.onPlaybackFailed())
    }

    @Test
    fun resumeCannotDeliverAnEarlyFailureBeforeContinuity() {
        val reducer = IntroPlaybackReducer(initiallyForeground = false)
        reducer.onStarted()
        reducer.onPlayerSurfaceAttached()
        reducer.onPlaybackFailed()

        assertEquals(emptyList(), reducer.onLifecycleEvent(Lifecycle.Event.ON_RESUME))
        assertEquals(
            listOf(IntroPlaybackEffect.Fail),
            reducer.onContinuityPresented(),
        )
    }

    @Test
    fun closeIsIdempotentAndRejectsEveryLateEvent() {
        val reducer = readyForegroundReducer()

        assertEquals(listOf(IntroPlaybackEffect.Pause), reducer.onClosed())
        assertEquals(emptyList(), reducer.onClosed())
        assertEquals(emptyList(), reducer.onPlaybackFailed())
        assertEquals(emptyList(), reducer.onFirstFrameRendered())
        assertEquals(emptyList(), reducer.onPlayerSurfaceAttached())
        assertEquals(emptyList(), reducer.onLifecycleEvent(Lifecycle.Event.ON_RESUME))
        assertTrue(reducer.isClosed)
    }

    private fun readyForegroundReducer(): IntroPlaybackReducer =
        IntroPlaybackReducer(initiallyForeground = true).apply {
            onStarted()
            onPlayerSurfaceAttached()
            onContinuityPresented()
        }
}
