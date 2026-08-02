package com.kwabor.android.presentation.onboarding

import com.kwabor.android.onboarding.FirstLaunchStore
import com.kwabor.shared.domain.observability.AnalyticsEventName
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    @Test
    fun introCompletionIsPersistedAndDoesNotCreateGuestSession() = runTest {
        val store = FakeFirstLaunchStore()
        val events = mutableListOf<AnalyticsEventName>()
        val viewModel = createViewModel(store = store, events = events, scope = this)

        viewModel.onIntent(OnboardingIntent.IntroDisplayed)
        viewModel.onIntent(OnboardingIntent.IntroCompleted)

        assertTrue(store.bundledIntroSeen)
        assertFalse(viewModel.state.value.isIntroRequired)
        assertFalse(viewModel.state.value.isGuestSession)
        assertEquals(listOf(AnalyticsEventName.IntroVideoShown), events)
    }

    @Test
    fun alreadyPresentedBundledRevisionDoesNotRequireTheIntro() = runTest {
        val viewModel = createViewModel(
            store = FakeFirstLaunchStore(bundledIntroSeen = true),
            events = mutableListOf(),
            scope = this,
        )

        assertFalse(viewModel.state.value.isIntroRequired)
    }

    @Test
    fun introDisplayTelemetryIsEmittedOnlyOncePerViewModel() = runTest {
        val events = mutableListOf<AnalyticsEventName>()
        val viewModel = createViewModel(FakeFirstLaunchStore(), events, this)

        viewModel.onIntent(OnboardingIntent.IntroDisplayed)
        viewModel.onIntent(OnboardingIntent.IntroDisplayed)

        assertEquals(listOf(AnalyticsEventName.IntroVideoShown), events)
    }

    @Test
    fun skippedIntroPersistsTheRevisionAndEmitsSkipTelemetry() = runTest {
        val store = FakeFirstLaunchStore()
        val events = mutableListOf<AnalyticsEventName>()
        val viewModel = createViewModel(store, events, this)

        viewModel.onIntent(OnboardingIntent.IntroSkipped)

        assertTrue(store.bundledIntroSeen)
        assertFalse(viewModel.state.value.isIntroRequired)
        assertEquals(listOf(AnalyticsEventName.IntroVideoSkipped), events)
    }

    @Test
    fun bundledPlaybackFailureRequiresFallbackWithoutConsumingTheRevision() = runTest {
        val store = FakeFirstLaunchStore()
        val events = mutableListOf<AnalyticsEventName>()
        val viewModel = createViewModel(store, events, this)

        viewModel.onIntent(OnboardingIntent.IntroPlaybackFailed)

        assertFalse(store.bundledIntroSeen)
        assertTrue(viewModel.state.value.isIntroRequired)
        assertTrue(viewModel.state.value.isStaticIntroFallbackRequired)
        assertEquals(emptyList(), events)
    }

    @Test
    fun continuingFromPlaybackFallbackConsumesTheBundledRevision() = runTest {
        val store = FakeFirstLaunchStore()
        val viewModel = createViewModel(store, mutableListOf(), this)

        viewModel.onIntent(OnboardingIntent.IntroPlaybackFailed)
        viewModel.onIntent(OnboardingIntent.IntroCompleted)

        assertTrue(store.bundledIntroSeen)
        assertFalse(viewModel.state.value.isIntroRequired)
    }

    @Test
    fun skippedIntroAndGuestConfirmationAreExplicit() = runTest {
        val store = FakeFirstLaunchStore()
        val events = mutableListOf<AnalyticsEventName>()
        val viewModel = createViewModel(store = store, events = events, scope = this)

        viewModel.onIntent(OnboardingIntent.IntroSkipped)
        viewModel.onIntent(OnboardingIntent.GuestSelected)
        viewModel.onIntent(OnboardingIntent.GuestConfirmed)

        assertTrue(viewModel.state.value.isGuestSession)
        assertFalse(viewModel.state.value.isGuestDisclosureVisible)
        assertEquals(listOf(AnalyticsEventName.IntroVideoSkipped), events)
    }

    @Test
    fun signupAndSigninEmitDistinctEffects() = runTest {
        val signUpViewModel = createViewModel(FakeFirstLaunchStore(), mutableListOf(), this)
        signUpViewModel.onIntent(OnboardingIntent.SignUpSelected)

        assertEquals(OnboardingEffect.OpenRegistration, signUpViewModel.effects.first())

        val signInViewModel = createViewModel(FakeFirstLaunchStore(), mutableListOf(), this)
        signInViewModel.onIntent(OnboardingIntent.SignInSelected)

        assertEquals(OnboardingEffect.OpenSignIn, signInViewModel.effects.first())
    }

    private fun createViewModel(
        store: FakeFirstLaunchStore,
        events: MutableList<AnalyticsEventName>,
        scope: TestScope,
    ): OnboardingViewModel = OnboardingViewModel(
        firstLaunchStore = store,
        track = { event -> events += event.name },
        coroutineScope = TestScope(StandardTestDispatcher(scope.testScheduler)),
    )
}

private class FakeFirstLaunchStore(
    var bundledIntroSeen: Boolean = false,
) : FirstLaunchStore {
    override fun isBundledIntroRequired(): Boolean = !bundledIntroSeen

    override fun markBundledIntroSeen() {
        bundledIntroSeen = true
    }
}
