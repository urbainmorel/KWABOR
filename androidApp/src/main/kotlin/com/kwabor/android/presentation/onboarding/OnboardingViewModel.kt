package com.kwabor.android.presentation.onboarding

import androidx.lifecycle.ViewModel
import com.kwabor.android.onboarding.FirstLaunchStore
import com.kwabor.shared.domain.observability.AnalyticsEvent
import com.kwabor.shared.domain.observability.AnalyticsEventName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

internal data class OnboardingUiState(
    val isIntroRequired: Boolean,
    val isGuestDisclosureVisible: Boolean = false,
    val isGuestSession: Boolean = false,
)

internal sealed interface OnboardingIntent {
    data object IntroDisplayed : OnboardingIntent

    data object IntroCompleted : OnboardingIntent

    data object IntroSkipped : OnboardingIntent

    data object SignUpSelected : OnboardingIntent

    data object SignInSelected : OnboardingIntent

    data object GuestSelected : OnboardingIntent

    data object GuestConfirmed : OnboardingIntent

    data object GuestCancelled : OnboardingIntent
}

internal sealed interface OnboardingEffect {
    data object OpenAuthentication : OnboardingEffect
}

internal class OnboardingViewModel(
    private val firstLaunchStore: FirstLaunchStore,
    private val track: (AnalyticsEvent) -> Unit,
    private val coroutineScope: CoroutineScope,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        OnboardingUiState(isIntroRequired = firstLaunchStore.isIntroRequired()),
    )
    val state: StateFlow<OnboardingUiState> = mutableState.asStateFlow()

    private val effectChannel = Channel<OnboardingEffect>(capacity = Channel.BUFFERED)
    val effects: Flow<OnboardingEffect> = effectChannel.receiveAsFlow()

    private var introDisplayTracked = false

    fun onIntent(intent: OnboardingIntent) {
        when (intent) {
            OnboardingIntent.IntroDisplayed -> trackIntroDisplayOnce()
            OnboardingIntent.IntroCompleted -> completeIntro(skipped = false)
            OnboardingIntent.IntroSkipped -> completeIntro(skipped = true)
            OnboardingIntent.SignUpSelected,
            OnboardingIntent.SignInSelected,
            -> openAuthentication()
            OnboardingIntent.GuestSelected -> updateState { it.copy(isGuestDisclosureVisible = true) }
            OnboardingIntent.GuestConfirmed -> updateState {
                it.copy(isGuestDisclosureVisible = false, isGuestSession = true)
            }
            OnboardingIntent.GuestCancelled -> updateState { it.copy(isGuestDisclosureVisible = false) }
        }
    }

    override fun onCleared() {
        effectChannel.close()
        coroutineScope.cancel()
        super.onCleared()
    }

    private fun trackIntroDisplayOnce() {
        if (introDisplayTracked) return
        introDisplayTracked = true
        track(AnalyticsEvent(name = AnalyticsEventName.IntroVideoShown))
    }

    private fun completeIntro(skipped: Boolean) {
        if (!mutableState.value.isIntroRequired) return
        firstLaunchStore.markIntroSeen()
        mutableState.value = mutableState.value.copy(isIntroRequired = false)
        if (skipped) {
            track(AnalyticsEvent(name = AnalyticsEventName.IntroVideoSkipped))
        }
    }

    private fun openAuthentication() {
        coroutineScope.launch {
            effectChannel.send(OnboardingEffect.OpenAuthentication)
        }
    }

    private fun updateState(transform: (OnboardingUiState) -> OnboardingUiState) {
        mutableState.value = transform(mutableState.value)
    }
}
