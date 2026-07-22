@file:androidx.media3.common.util.UnstableApi

package com.kwabor.android.ui.screens.onboarding

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
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
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.kwabor.android.R
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
            .background(colorResource(R.color.kwabor_wordmark_background))
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
    when (introPrimaryMode(reducedMotion)) {
        IntroPrimaryMode.StaticFallback -> {
            Image(
                painter = painterResource(R.drawable.kwabor_intro_fallback),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Button(
                onClick = onCompleted,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(KwaborSpacing.Xxl),
            ) {
                Text(strings.introContinue)
            }
        }
        IntroPrimaryMode.VideoWithContinuity -> {
            IntroVideo(
                mediaSource = mediaSource,
                bundledVideoResource = R.raw.kwabor_intro,
                onCompleted = onCompleted,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

internal enum class IntroPrimaryMode {
    StaticFallback,
    VideoWithContinuity,
}

internal fun introPrimaryMode(reducedMotion: Boolean): IntroPrimaryMode = if (reducedMotion) {
    IntroPrimaryMode.StaticFallback
} else {
    IntroPrimaryMode.VideoWithContinuity
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
    var continuityVisibility by remember(mediaUri) {
        mutableStateOf(IntroContinuityVisibility.Visible)
    }
    val player = rememberIntroPlayer(mediaUri)
    BindIntroPlayerLifecycle(
        player = player,
        mediaUri = mediaUri,
        onCompleted = onCompleted,
        onFirstFrameRendered = {
            continuityVisibility = continuityVisibility.afterFirstFrameRendered()
        },
        onFailure = {
            when (sourceForPlayer.failureAction()) {
                IntroPlaybackFailureAction.UseBundled -> playbackSource = IntroMediaSource.Bundled
                IntroPlaybackFailureAction.CompleteIntro -> onCompleted()
            }
        },
    )
    IntroPlayerSurface(
        player = player,
        continuityVisibility = continuityVisibility,
        modifier = modifier,
    )
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
    onFirstFrameRendered: () -> Unit,
    onFailure: () -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnCompleted by rememberUpdatedState(onCompleted)
    val currentOnFirstFrameRendered by rememberUpdatedState(onFirstFrameRendered)
    val currentOnFailure by rememberUpdatedState(onFailure)
    DisposableEffect(player, mediaUri, lifecycle) {
        val binding = IntroPlayerLifecycleBinding(
            player = player,
            lifecycle = lifecycle,
            onCompleted = { currentOnCompleted() },
            onFirstFrameRendered = { currentOnFirstFrameRendered() },
            onFailure = { currentOnFailure() },
        )
        binding.start(mediaUri)
        onDispose {
            binding.close()
        }
    }
}

internal enum class IntroContinuityVisibility {
    Visible,
    Hidden,
}

internal fun IntroContinuityVisibility.afterFirstFrameRendered(): IntroContinuityVisibility =
    IntroContinuityVisibility.Hidden

internal enum class IntroPlaybackFailureAction {
    UseBundled,
    CompleteIntro,
}

internal fun IntroMediaSource.failureAction(): IntroPlaybackFailureAction = when (this) {
    IntroMediaSource.Bundled -> IntroPlaybackFailureAction.CompleteIntro
    is IntroMediaSource.Remote -> IntroPlaybackFailureAction.UseBundled
}

@Composable
private fun IntroPlayerSurface(
    player: ExoPlayer,
    continuityVisibility: IntroContinuityVisibility,
    modifier: Modifier,
) {
    AndroidView(
        factory = ::IntroPlayerView,
        update = { playerView -> playerView.bind(player, continuityVisibility) },
        modifier = modifier,
    )
}

private class IntroPlayerView(context: Context) : FrameLayout(context) {
    private val playerView = PlayerView(context).apply {
        useController = false
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        setShutterBackgroundColor(context.getColor(R.color.kwabor_wordmark_background))
    }
    private val continuityView = ImageView(context).apply {
        setImageResource(R.drawable.kwabor_launch_wordmark)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setBackgroundColor(context.getColor(R.color.kwabor_wordmark_background))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    init {
        val matchParent = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        addView(playerView, matchParent)
        addView(continuityView, LayoutParams(matchParent))
    }

    fun bind(player: ExoPlayer, continuityVisibility: IntroContinuityVisibility) {
        playerView.player = player
        continuityView.visibility = when (continuityVisibility) {
            IntroContinuityVisibility.Visible -> View.VISIBLE
            IntroContinuityVisibility.Hidden -> View.GONE
        }
    }
}
