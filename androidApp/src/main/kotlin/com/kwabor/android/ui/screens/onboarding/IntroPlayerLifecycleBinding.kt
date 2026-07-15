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
    private val onFailure: () -> Unit,
) {
    private var isForeground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    private var hasDeferredFailure = false

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && isForeground) {
                onCompleted()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (isForeground) {
                onFailure()
            } else {
                hasDeferredFailure = true
            }
        }
    }

    private val lifecycleObserver = LifecycleEventObserver { _, event -> onLifecycleEvent(event) }

    fun start(mediaUri: Uri) {
        player.volume = 0f
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.setMediaItem(MediaItem.fromUri(mediaUri))
        player.addListener(playerListener)
        lifecycle.addObserver(lifecycleObserver)
        player.prepare()
        if (isForeground) player.play() else player.pause()
    }

    fun close() {
        lifecycle.removeObserver(lifecycleObserver)
        player.removeListener(playerListener)
        player.pause()
        player.release()
    }

    private fun onLifecycleEvent(event: Lifecycle.Event) {
        if (event.isStarting() && hasDeferredFailure) {
            isForeground = true
            hasDeferredFailure = false
            onFailure()
            return
        }
        applyLifecycleAction(event.lifecycleAction(player.playbackState == Player.STATE_ENDED))
    }

    private fun applyLifecycleAction(action: IntroPlayerLifecycleAction) {
        when (action) {
            IntroPlayerLifecycleAction.Play -> {
                isForeground = true
                player.play()
            }
            IntroPlayerLifecycleAction.Pause -> {
                isForeground = false
                player.pause()
            }
            IntroPlayerLifecycleAction.Complete -> {
                isForeground = true
                onCompleted()
            }
            IntroPlayerLifecycleAction.None -> Unit
        }
    }
}

internal enum class IntroPlayerLifecycleAction {
    Play,
    Pause,
    Complete,
    None,
}

internal fun Lifecycle.Event.lifecycleAction(playbackEnded: Boolean): IntroPlayerLifecycleAction = when (this) {
    Lifecycle.Event.ON_START,
    Lifecycle.Event.ON_RESUME,
    -> if (playbackEnded) IntroPlayerLifecycleAction.Complete else IntroPlayerLifecycleAction.Play
    Lifecycle.Event.ON_PAUSE,
    Lifecycle.Event.ON_STOP,
    -> IntroPlayerLifecycleAction.Pause
    Lifecycle.Event.ON_CREATE,
    Lifecycle.Event.ON_DESTROY,
    Lifecycle.Event.ON_ANY,
    -> IntroPlayerLifecycleAction.None
}

private fun Lifecycle.Event.isStarting(): Boolean =
    this == Lifecycle.Event.ON_START || this == Lifecycle.Event.ON_RESUME
