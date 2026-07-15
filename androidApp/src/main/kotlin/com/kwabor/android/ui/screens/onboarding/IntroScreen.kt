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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.kwabor.android.R
import com.kwabor.android.design.KwaborColors
import com.kwabor.android.design.KwaborSpacing
import com.kwabor.android.onboarding.IntroMediaSource
import com.kwabor.shared.i18n.KwaborStrings

@Composable
internal fun IntroScreen(
    strings: KwaborStrings,
    mediaSource: IntroMediaSource,
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
            mediaSource = mediaSource,
            reducedMotion = reducedMotion,
            onCompleted = actions.onCompleted,
        )
        IntroSkipButton(label = strings.introSkip, onSkipped = actions.onSkipped)
    }
}

@Composable
private fun BoxScope.IntroPrimaryContent(
    strings: KwaborStrings,
    mediaSource: IntroMediaSource,
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
            mediaSource = mediaSource,
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
    mediaSource: IntroMediaSource,
    @RawRes bundledVideoResource: Int,
    onCompleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val bundledMediaUri = Uri.Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(context.packageName)
        .appendPath(bundledVideoResource.toString())
        .build()
    var playbackSource by remember(mediaSource) { mutableStateOf(mediaSource) }
    val sourceForPlayer = playbackSource
    val mediaUri = when (sourceForPlayer) {
        IntroMediaSource.Bundled -> bundledMediaUri
        is IntroMediaSource.Remote -> Uri.fromFile(sourceForPlayer.file)
    }
    val player = rememberIntroPlayer(mediaUri)
    BindIntroPlayerLifecycle(
        player = player,
        mediaUri = mediaUri,
        onCompleted = onCompleted,
        onFailure = {
            when (sourceForPlayer.failureAction()) {
                IntroPlaybackFailureAction.UseBundled -> playbackSource = IntroMediaSource.Bundled
                IntroPlaybackFailureAction.CompleteIntro -> onCompleted()
            }
        },
    )
    IntroPlayerSurface(player = player, modifier = modifier)
}

@Composable
private fun rememberIntroPlayer(mediaUri: Uri): ExoPlayer {
    val context = LocalContext.current
    return remember(context, mediaUri) { ExoPlayer.Builder(context).build() }
}

@Composable
private fun BindIntroPlayerLifecycle(
    player: ExoPlayer,
    mediaUri: Uri,
    onCompleted: () -> Unit,
    onFailure: () -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnCompleted by rememberUpdatedState(onCompleted)
    val currentOnFailure by rememberUpdatedState(onFailure)
    DisposableEffect(player, mediaUri, lifecycle) {
        val binding = IntroPlayerLifecycleBinding(
            player = player,
            lifecycle = lifecycle,
            onCompleted = { currentOnCompleted() },
            onFailure = { currentOnFailure() },
        )
        binding.start(mediaUri)
        onDispose {
            binding.close()
        }
    }
}

internal enum class IntroPlaybackFailureAction {
    UseBundled,
    CompleteIntro,
}

internal fun IntroMediaSource.failureAction(): IntroPlaybackFailureAction = when (this) {
    IntroMediaSource.Bundled -> IntroPlaybackFailureAction.CompleteIntro
    is IntroMediaSource.Remote -> IntroPlaybackFailureAction.UseBundled
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
        update = { playerView -> playerView.player = player },
        modifier = modifier,
    )
}
