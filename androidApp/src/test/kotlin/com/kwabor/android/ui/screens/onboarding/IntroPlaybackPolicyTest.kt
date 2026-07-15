package com.kwabor.android.ui.screens.onboarding

import androidx.lifecycle.Lifecycle
import com.kwabor.android.onboarding.IntroMediaSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun foregroundLifecycleStartsPlaybackOrCompletesAnAlreadyEndedVideo() {
        assertEquals(
            IntroPlayerLifecycleAction.Play,
            Lifecycle.Event.ON_START.lifecycleAction(playbackEnded = false),
        )
        assertEquals(
            IntroPlayerLifecycleAction.Complete,
            Lifecycle.Event.ON_RESUME.lifecycleAction(playbackEnded = true),
        )
    }

    @Test
    fun backgroundLifecycleAlwaysPausesPlayback() {
        assertEquals(
            IntroPlayerLifecycleAction.Pause,
            Lifecycle.Event.ON_PAUSE.lifecycleAction(playbackEnded = false),
        )
        assertEquals(
            IntroPlayerLifecycleAction.Pause,
            Lifecycle.Event.ON_STOP.lifecycleAction(playbackEnded = true),
        )
    }
}
