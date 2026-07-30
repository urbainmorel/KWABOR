@file:androidx.media3.common.util.UnstableApi

package com.kwabor.android.ui.screens.onboarding

import android.content.ContentResolver
import android.content.Context
import android.graphics.Canvas
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
import com.kwabor.android.design.KwaborAlpha
import com.kwabor.android.design.KwaborColors
import com.kwabor.android.design.KwaborSpacing
import com.kwabor.shared.i18n.KwaborStrings

@Composable
internal fun IntroScreen(
    strings: KwaborStrings,
    state: IntroScreenState,
    actions: IntroScreenActions,
    landingActions: OnboardingLandingActions,
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
            state = state,
            actions = actions,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(KwaborColors.Ink950.copy(alpha = KwaborAlpha.SCRIM_HIGH)),
        )
        OnboardingLandingContent(strings = strings, actions = landingActions)
        IntroSkipButton(label = strings.introSkip, onSkipped = actions.onSkipped)
    }
    if (state.isGuestDisclosureVisible) {
        GuestDisclosureDialog(strings = strings, actions = landingActions)
    }
}

@Composable
private fun BoxScope.IntroPrimaryContent(
    state: IntroScreenState,
    actions: IntroScreenActions,
) {
    when (introPrimaryMode(state.reducedMotion, state.staticFallbackRequired)) {
        IntroPrimaryMode.StaticFallback -> {
            Image(
                painter = painterResource(R.drawable.kwabor_intro_fallback),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        IntroPrimaryMode.VideoWithContinuity -> {
            IntroVideo(
                launchSplashExited = state.launchSplashExited,
                actions = actions,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

internal enum class IntroPrimaryMode {
    StaticFallback,
    VideoWithContinuity,
}

internal fun introPrimaryMode(reducedMotion: Boolean, staticFallbackRequired: Boolean): IntroPrimaryMode =
    if (reducedMotion || staticFallbackRequired) {
        IntroPrimaryMode.StaticFallback
    } else {
        IntroPrimaryMode.VideoWithContinuity
    }

@Composable
private fun BoxScope.IntroSkipButton(label: String, onSkipped: () -> Unit) {
    Button(
        onClick = onSkipped,
        modifier = Modifier
            .align(Alignment.TopStart)
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
    val onPlaybackFailed: () -> Unit,
)

internal data class IntroScreenState(
    val reducedMotion: Boolean,
    val staticFallbackRequired: Boolean,
    val launchSplashExited: Boolean,
    val isGuestDisclosureVisible: Boolean,
)

@Composable
private fun IntroVideo(launchSplashExited: Boolean, actions: IntroScreenActions, modifier: Modifier = Modifier) {
    IntroVideoPlayback(
        launchSplashExited = launchSplashExited,
        onCompleted = actions.onCompleted,
        onFailure = actions.onPlaybackFailed,
        modifier = modifier,
    )
}

@Composable
private fun IntroVideoPlayback(
    launchSplashExited: Boolean,
    onCompleted: () -> Unit,
    onFailure: () -> Unit,
    modifier: Modifier,
) {
    val mediaUri = rememberBundledMediaUri(R.raw.kwabor_intro)
    var continuityVisibility by remember(mediaUri) {
        mutableStateOf(IntroContinuityVisibility.Visible)
    }
    val player = rememberIntroPlayer(mediaUri)
    val lifecycleBinding = rememberIntroPlayerLifecycleBinding(
        player = player,
        mediaUri = mediaUri,
        onCompleted = onCompleted,
        onFirstFrameRendered = {
            continuityVisibility = continuityVisibility.afterFirstFrameRendered()
        },
        onFailure = onFailure,
    )
    IntroPlayerSurface(
        player = player,
        continuityVisibility = continuityVisibility,
        launchSplashExited = launchSplashExited,
        lifecycleBinding = lifecycleBinding,
        modifier = modifier,
    )
}

@Composable
private fun rememberBundledMediaUri(@RawRes bundledVideoResource: Int): Uri {
    val context = LocalContext.current
    return remember(context, bundledVideoResource) {
        Uri.Builder()
            .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
            .authority(context.packageName)
            .appendPath(bundledVideoResource.toString())
            .build()
    }
}

@Composable
private fun rememberIntroPlayer(mediaUri: Uri): ExoPlayer {
    val context = LocalContext.current
    return remember(context, mediaUri) { ExoPlayer.Builder(context).build() }
}

@Composable
private fun rememberIntroPlayerLifecycleBinding(
    player: ExoPlayer,
    mediaUri: Uri,
    onCompleted: () -> Unit,
    onFirstFrameRendered: () -> Unit,
    onFailure: () -> Unit,
): IntroPlayerLifecycleBinding {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnCompleted by rememberUpdatedState(onCompleted)
    val currentOnFirstFrameRendered by rememberUpdatedState(onFirstFrameRendered)
    val currentOnFailure by rememberUpdatedState(onFailure)
    val binding = remember(player, lifecycle) {
        IntroPlayerLifecycleBinding(
            player = player,
            lifecycle = lifecycle,
            onCompleted = { currentOnCompleted() },
            onFirstFrameRendered = { currentOnFirstFrameRendered() },
            onFailure = { currentOnFailure() },
        )
    }
    DisposableEffect(binding, mediaUri) {
        binding.start(mediaUri)
        onDispose {
            binding.close()
        }
    }
    return binding
}

internal enum class IntroContinuityVisibility {
    Visible,
    Hidden,
}

internal fun IntroContinuityVisibility.afterFirstFrameRendered(): IntroContinuityVisibility =
    IntroContinuityVisibility.Hidden

internal enum class IntroPlayerAttachment {
    AwaitingContinuityFrame,
    HoldingContinuityFrame,
    Ready,
}

internal const val INTRO_WORDMARK_MINIMUM_VISIBLE_MILLIS = 500L

internal class IntroContinuityBarrier(
    private val minimumVisibleMillis: Long = INTRO_WORDMARK_MINIMUM_VISIBLE_MILLIS,
) {
    var state: IntroPlayerAttachment = IntroPlayerAttachment.AwaitingContinuityFrame
        private set
    private var startedAtMillis = 0L
    private var lastFrameAtMillis = 0L
    private var distinctFrameCount = 0

    init {
        require(minimumVisibleMillis >= 0L) {
            "The minimum wordmark duration cannot be negative."
        }
    }

    fun onVisibleFrame(frameTimeMillis: Long): Boolean {
        require(frameTimeMillis >= 0L) {
            "The frame time cannot be negative."
        }
        return when (state) {
            IntroPlayerAttachment.AwaitingContinuityFrame -> {
                beginHoldingAt(frameTimeMillis)
                false
            }
            IntroPlayerAttachment.HoldingContinuityFrame -> continueHoldingAt(frameTimeMillis)
            IntroPlayerAttachment.Ready -> false
        }
    }

    fun reset() {
        state = IntroPlayerAttachment.AwaitingContinuityFrame
        startedAtMillis = 0L
        lastFrameAtMillis = 0L
        distinctFrameCount = 0
    }

    private fun beginHoldingAt(frameTimeMillis: Long) {
        state = IntroPlayerAttachment.HoldingContinuityFrame
        startedAtMillis = frameTimeMillis
        lastFrameAtMillis = frameTimeMillis
        distinctFrameCount = 1
    }

    private fun continueHoldingAt(frameTimeMillis: Long): Boolean {
        if (frameTimeMillis < lastFrameAtMillis) {
            reset()
            beginHoldingAt(frameTimeMillis)
            return false
        }
        if (frameTimeMillis == lastFrameAtMillis) return false
        lastFrameAtMillis = frameTimeMillis
        distinctFrameCount += 1
        val durationSatisfied = frameTimeMillis - startedAtMillis >= minimumVisibleMillis
        if (!durationSatisfied || distinctFrameCount < MINIMUM_DISTINCT_FRAME_COUNT) return false
        state = IntroPlayerAttachment.Ready
        return true
    }

    private companion object {
        const val MINIMUM_DISTINCT_FRAME_COUNT = 2
    }
}

@Composable
private fun IntroPlayerSurface(
    player: ExoPlayer,
    continuityVisibility: IntroContinuityVisibility,
    launchSplashExited: Boolean,
    lifecycleBinding: IntroPlayerLifecycleBinding,
    modifier: Modifier,
) {
    AndroidView(
        factory = ::IntroPlayerView,
        update = { playerView ->
            playerView.bind(
                player = player,
                continuityVisibility = continuityVisibility,
                launchSplashExited = launchSplashExited,
                lifecycleBinding = lifecycleBinding,
            )
        },
        onRelease = { playerView -> playerView.release() },
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
    private val continuityBarrier = IntroContinuityBarrier()
    private var pendingPlayer: ExoPlayer? = null
    private var notifiedPlayer: ExoPlayer? = null
    private var launchSplashExited = false
    private var lifecycleBinding: IntroPlayerLifecycleBinding? = null

    init {
        val matchParent = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        addView(playerView, matchParent)
        addView(continuityView, LayoutParams(matchParent))
    }

    fun bind(
        player: ExoPlayer,
        continuityVisibility: IntroContinuityVisibility,
        launchSplashExited: Boolean,
        lifecycleBinding: IntroPlayerLifecycleBinding,
    ) {
        if (pendingPlayer !== player) {
            resetForPlayer(player)
        }
        this.launchSplashExited = launchSplashExited
        this.lifecycleBinding = lifecycleBinding
        continuityView.visibility = when (continuityVisibility) {
            IntroContinuityVisibility.Visible -> View.VISIBLE
            IntroContinuityVisibility.Hidden -> View.GONE
        }
        attachPendingPlayer()
        if (
            continuityBarrier.state != IntroPlayerAttachment.Ready &&
            launchSplashExited &&
            continuityView.visibility == View.VISIBLE
        ) {
            postInvalidateOnAnimation()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (
            launchSplashExited &&
            continuityView.visibility == View.VISIBLE &&
            windowVisibility == View.VISIBLE
        ) {
            val continuityPresented = continuityBarrier.onVisibleFrame(drawingTime)
            if (continuityPresented) {
                lifecycleBinding?.onContinuityPresented()
            } else if (continuityBarrier.state != IntroPlayerAttachment.Ready) {
                postInvalidateOnAnimation()
            }
        } else if (continuityBarrier.state != IntroPlayerAttachment.Ready) {
            continuityBarrier.reset()
        }
    }

    private fun attachPendingPlayer() {
        val player = pendingPlayer ?: return
        if (playerView.player !== player) {
            playerView.player = player
        }
        if (notifiedPlayer !== player) {
            notifiedPlayer = player
            lifecycleBinding?.onPlayerSurfaceAttached()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachPendingPlayer()
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        if (continuityBarrier.state != IntroPlayerAttachment.Ready) {
            continuityBarrier.reset()
        }
        detachPlayer()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (
            visibility != View.VISIBLE &&
            continuityBarrier.state != IntroPlayerAttachment.Ready
        ) {
            continuityBarrier.reset()
        } else if (visibility == View.VISIBLE) {
            postInvalidateOnAnimation()
        }
    }

    fun release() {
        detachPlayer()
        pendingPlayer = null
        lifecycleBinding = null
        launchSplashExited = false
        continuityBarrier.reset()
    }

    private fun resetForPlayer(player: ExoPlayer) {
        detachPlayer()
        pendingPlayer = player
        continuityBarrier.reset()
    }

    private fun detachPlayer() {
        if (notifiedPlayer != null) {
            lifecycleBinding?.onPlayerSurfaceUnavailable()
            notifiedPlayer = null
        }
        playerView.player = null
    }
}
