@file:androidx.media3.common.util.UnstableApi

package com.kwabor.android.ui.screens.onboarding

import android.content.ContentResolver
import android.graphics.Color.TRANSPARENT
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.RawRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.kwabor.android.R
import com.kwabor.android.design.KwaborColors
import com.kwabor.android.design.KwaborSpacing
import com.kwabor.shared.i18n.KwaborStrings

@Composable
internal fun IntroScreen(
    strings: KwaborStrings,
    remoteVideoUri: Uri?,
    reducedMotion: Boolean,
    actions: IntroScreenActions,
) {
    DisposableEffect(Unit) {
        actions.onDisplayed()
        onDispose {}
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KwaborColors.Ink950)
            .semantics { contentDescription = strings.introAccessibilityLabel },
    ) {
        IntroPrimaryContent(
            strings = strings,
            remoteVideoUri = remoteVideoUri,
            reducedMotion = reducedMotion,
            onCompleted = actions.onCompleted,
        )
        IntroSkipButton(label = strings.introSkip, onSkipped = actions.onSkipped)
    }
}

@Composable
private fun BoxScope.IntroPrimaryContent(
    strings: KwaborStrings,
    remoteVideoUri: Uri?,
    reducedMotion: Boolean,
    onCompleted: () -> Unit,
) {
    Image(
        painter = painterResource(R.drawable.kwabor_intro_fallback),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
    if (reducedMotion) {
        Button(
            onClick = onCompleted,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(KwaborSpacing.Xxl),
        ) {
            Text(strings.introContinue)
        }
    } else {
        IntroVideo(
            remoteVideoUri = remoteVideoUri,
            bundledVideoResource = R.raw.kwabor_intro,
            onCompleted = onCompleted,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun BoxScope.IntroSkipButton(label: String, onSkipped: () -> Unit) {
    Button(
        onClick = onSkipped,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(KwaborSpacing.Xl),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

internal data class IntroScreenActions(
    val onDisplayed: () -> Unit,
    val onCompleted: () -> Unit,
    val onSkipped: () -> Unit,
)

@Composable
private fun IntroVideo(
    remoteVideoUri: Uri?,
    @RawRes bundledVideoResource: Int,
    onCompleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mediaUri = remoteVideoUri ?: Uri.Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(context.packageName)
        .appendPath(bundledVideoResource.toString())
        .build()
    val player = rememberIntroPlayer(mediaUri)
    BindIntroPlayerLifecycle(player = player, onCompleted = onCompleted)
    IntroPlayerSurface(player = player, modifier = modifier)
}

@Composable
private fun rememberIntroPlayer(mediaUri: Uri): ExoPlayer {
    val context = LocalContext.current
    return remember(mediaUri) {
        ExoPlayer.Builder(context).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_OFF
            setMediaItem(MediaItem.fromUri(mediaUri))
            prepare()
            playWhenReady = true
        }
    }
}

@Composable
private fun BindIntroPlayerLifecycle(player: ExoPlayer, onCompleted: () -> Unit) {
    val currentOnCompleted by rememberUpdatedState(onCompleted)
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    currentOnCompleted()
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
}

@Composable
private fun IntroPlayerSurface(player: ExoPlayer, modifier: Modifier) {
    AndroidView(
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShutterBackgroundColor(TRANSPARENT)
                this.player = player
            }
        },
        modifier = modifier,
    )
}
