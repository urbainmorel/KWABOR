@file:androidx.media3.common.util.UnstableApi

package com.kwabor.android.ui.screens.onboarding

import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

internal class IntroPlayerLifecycleBinding(
    private val player: ExoPlayer,
    private val lifecycle: Lifecycle,
    private val onCompleted: () -> Unit,
    private val onFirstFrameRendered: () -> Unit,
    private val onFailure: () -> Unit,
) {
    private val playbackReducer = IntroPlaybackReducer(
        initiallyForeground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
    )
    private var isStarted = false
    private var isClosed = false

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                applyPlaybackEffects(playbackReducer.onPlaybackEnded())
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            applyPlaybackEffects(playbackReducer.onPlaybackFailed())
        }

        override fun onRenderedFirstFrame() {
            applyPlaybackEffects(playbackReducer.onFirstFrameRendered())
        }
    }

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        applyPlaybackEffects(playbackReducer.onLifecycleEvent(event))
    }

    fun start(mediaUri: Uri) {
        check(!isStarted && !isClosed) {
            "The intro player lifecycle binding can only be started once."
        }
        player.volume = 0f
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.pause()
        player.setMediaItem(MediaItem.fromUri(mediaUri), true)
        player.seekTo(0L)
        player.addListener(playerListener)
        lifecycle.addObserver(lifecycleObserver)
        player.prepare()
        isStarted = true
        applyPlaybackEffects(playbackReducer.onStarted())
    }

    fun onPlayerSurfaceAttached() = applyPlaybackEffects(playbackReducer.onPlayerSurfaceAttached())

    fun onPlayerSurfaceUnavailable() = applyPlaybackEffects(playbackReducer.onPlayerSurfaceUnavailable())

    fun onContinuityPresented() = applyPlaybackEffects(playbackReducer.onContinuityPresented())

    fun close() {
        if (isClosed) return
        isClosed = true
        applyPlaybackEffects(playbackReducer.onClosed())
        if (isStarted) {
            lifecycle.removeObserver(lifecycleObserver)
            player.removeListener(playerListener)
        }
        player.pause()
        player.release()
    }

    private fun applyPlaybackEffects(effects: List<IntroPlaybackEffect>) {
        effects.forEach { effect ->
            when (effect) {
                IntroPlaybackEffect.SeekToStart -> player.seekTo(0L)
                IntroPlaybackEffect.Play -> player.play()
                IntroPlaybackEffect.Pause -> player.pause()
                IntroPlaybackEffect.RevealVideo -> onFirstFrameRendered()
                IntroPlaybackEffect.Complete -> onCompleted()
                IntroPlaybackEffect.Fail -> onFailure()
            }
        }
    }
}

internal enum class IntroPlaybackEffect {
    SeekToStart,
    Play,
    Pause,
    RevealVideo,
    Complete,
    Fail,
}

private enum class IntroPendingTerminal {
    Complete,
    Fail,
}

internal class IntroPlaybackReducer(initiallyForeground: Boolean) {
    var isForeground: Boolean = initiallyForeground
        private set
    var isStarted: Boolean = false
        private set
    var isPlayerSurfaceAttached: Boolean = false
        private set
    var isContinuityPresented: Boolean = false
        private set
    var isVideoRevealed: Boolean = false
        private set
    var isClosed: Boolean = false
        private set
    private var hasRenderedFirstFrame = false
    private var hasSeekedToStart = false
    private var isPlayRequested = false
    private var terminalDelivered = false
    private var pendingTerminal: IntroPendingTerminal? = null

    fun onStarted(): List<IntroPlaybackEffect> {
        if (isClosed || isStarted) return emptyList()
        isStarted = true
        return reduceReadiness()
    }

    fun onPlayerSurfaceAttached(): List<IntroPlaybackEffect> {
        if (isClosed || isPlayerSurfaceAttached) return emptyList()
        isPlayerSurfaceAttached = true
        return reduceReadiness()
    }

    fun onPlayerSurfaceUnavailable(): List<IntroPlaybackEffect> {
        if (isClosed || !isPlayerSurfaceAttached) return emptyList()
        isPlayerSurfaceAttached = false
        if (!isVideoRevealed) {
            hasRenderedFirstFrame = false
            hasSeekedToStart = false
        }
        return pauseIfNeeded()
    }

    fun onContinuityPresented(): List<IntroPlaybackEffect> {
        if (isClosed || isContinuityPresented) return emptyList()
        isContinuityPresented = true
        val terminal = pendingTerminal
        return if (terminal != null && isForeground) {
            deliverTerminal(terminal)
        } else if (terminal == null) {
            reduceReadiness()
        } else {
            emptyList()
        }
    }

    fun onFirstFrameRendered(): List<IntroPlaybackEffect> {
        if (isClosed || terminalDelivered) return emptyList()
        if (!isPlayerSurfaceAttached || hasRenderedFirstFrame) return emptyList()
        hasRenderedFirstFrame = true
        return reduceReadiness()
    }

    fun onPlaybackEnded(): List<IntroPlaybackEffect> = acceptTerminal(IntroPendingTerminal.Complete)

    fun onPlaybackFailed(): List<IntroPlaybackEffect> = acceptTerminal(IntroPendingTerminal.Fail)

    fun onClosed(): List<IntroPlaybackEffect> {
        if (isClosed) return emptyList()
        isClosed = true
        pendingTerminal = null
        return pauseIfNeeded()
    }

    fun onLifecycleEvent(event: Lifecycle.Event): List<IntroPlaybackEffect> {
        if (isClosed) return emptyList()
        return when (event) {
            Lifecycle.Event.ON_RESUME,
            -> {
                isForeground = true
                val terminal = pendingTerminal
                if (terminal == null) {
                    reduceReadiness()
                } else if (isContinuityPresented) {
                    deliverTerminal(terminal)
                } else {
                    emptyList()
                }
            }
            Lifecycle.Event.ON_PAUSE,
            Lifecycle.Event.ON_STOP,
            -> {
                isForeground = false
                pauseIfNeeded()
            }
            Lifecycle.Event.ON_CREATE,
            Lifecycle.Event.ON_START,
            Lifecycle.Event.ON_DESTROY,
            Lifecycle.Event.ON_ANY,
            -> emptyList()
        }
    }

    private fun reduceReadiness(): List<IntroPlaybackEffect> {
        if (isClosed || terminalDelivered) return emptyList()
        if (!isStarted || !isPlayerSurfaceAttached || !isContinuityPresented) return emptyList()
        val effects = mutableListOf<IntroPlaybackEffect>()
        if (!hasSeekedToStart) {
            hasSeekedToStart = true
            effects += IntroPlaybackEffect.SeekToStart
        }
        if (isForeground && !isPlayRequested) {
            isPlayRequested = true
            effects += IntroPlaybackEffect.Play
        }
        if (hasRenderedFirstFrame && !isVideoRevealed) {
            isVideoRevealed = true
            effects += IntroPlaybackEffect.RevealVideo
        }
        return effects
    }

    private fun acceptTerminal(terminal: IntroPendingTerminal): List<IntroPlaybackEffect> {
        if (isClosed || terminalDelivered || pendingTerminal != null) return emptyList()
        pendingTerminal = terminal
        return if (isForeground && isContinuityPresented) {
            deliverTerminal(terminal)
        } else {
            pauseIfNeeded()
        }
    }

    private fun deliverTerminal(terminal: IntroPendingTerminal): List<IntroPlaybackEffect> {
        terminalDelivered = true
        pendingTerminal = null
        val effects = pauseIfNeeded().toMutableList()
        effects += when (terminal) {
            IntroPendingTerminal.Complete -> IntroPlaybackEffect.Complete
            IntroPendingTerminal.Fail -> IntroPlaybackEffect.Fail
        }
        return effects
    }

    private fun pauseIfNeeded(): List<IntroPlaybackEffect> {
        if (!isPlayRequested) return emptyList()
        isPlayRequested = false
        return listOf(IntroPlaybackEffect.Pause)
    }
}
