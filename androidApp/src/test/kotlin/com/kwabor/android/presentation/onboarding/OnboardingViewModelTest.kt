package com.kwabor.android.presentation.onboarding

import com.kwabor.android.onboarding.FirstLaunchStore
import com.kwabor.android.onboarding.IntroLaunchDecision
import com.kwabor.android.onboarding.IntroLaunchRequest
import com.kwabor.android.onboarding.IntroMediaSource
import com.kwabor.android.onboarding.PendingRemoteIntro
import com.kwabor.shared.domain.observability.AnalyticsEventName
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    @Test
    fun introCompletion_isPersistedAndDoesNotCreateGuestSession() = runTest {
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
    fun remoteIntroIsMarkedPresentedOnlyAfterCompletionAndNeverMarksBundledIntro() = runTest {
        val store = FakeFirstLaunchStore(bundledIntroSeen = true)
        val events = mutableListOf<AnalyticsEventName>()
        val viewModel = createViewModel(
            store = store,
            events = events,
            scope = this,
            launchDecision = completedDecision(remoteLaunchRequest()),
        )

        viewModel.onIntent(OnboardingIntent.IntroDisplayed)
        viewModel.onIntent(OnboardingIntent.IntroDisplayed)

        assertEquals(0L, store.lastPresentedRevision)

        viewModel.onIntent(OnboardingIntent.IntroCompleted)

        assertEquals(REMOTE_REVISION, store.lastPresentedRevision)
        assertTrue(store.bundledIntroSeen)
        assertFalse(viewModel.state.value.isIntroRequired)
        assertEquals(listOf(AnalyticsEventName.IntroVideoShown), events)
    }

    @Test
    fun skippingRemoteIntroConsumesItsRevisionAfterTheControlledTransition() = runTest {
        val store = FakeFirstLaunchStore(bundledIntroSeen = true)
        val events = mutableListOf<AnalyticsEventName>()
        val viewModel = createViewModel(
            store = store,
            events = events,
            scope = this,
            launchDecision = completedDecision(remoteLaunchRequest()),
        )

        viewModel.onIntent(OnboardingIntent.IntroSkipped)

        assertEquals(REMOTE_REVISION, store.lastPresentedRevision)
        assertFalse(viewModel.state.value.isIntroRequired)
        assertEquals(listOf(AnalyticsEventName.IntroVideoSkipped), events)
    }

    @Test
    fun skippedIntroAndGuestConfirmation_areExplicit() = runTest {
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
    fun returningLaunchRemainsPendingUntilLocalMediaDecisionCompletes() = runTest {
        val store = FakeFirstLaunchStore(bundledIntroSeen = true)
        val events = mutableListOf<AnalyticsEventName>()
        val decision = MutableStateFlow(IntroLaunchDecision.Pending)
        val viewModel = createViewModel(
            store = store,
            events = events,
            scope = this,
            launchDecision = decision,
        )

        assertFalse(viewModel.state.value.isLaunchDecisionComplete)

        decision.value = IntroLaunchDecision.complete(
            IntroLaunchRequest(isRequired = false, mediaSource = IntroMediaSource.Bundled),
        )
        advanceUntilIdle()

        assertTrue(viewModel.state.value.isLaunchDecisionComplete)
        assertFalse(viewModel.state.value.isIntroRequired)
    }

    @Test
    fun signupAndSigninEmitDistinctEffects() = runTest {
        val signUpViewModel = createViewModel(
            store = FakeFirstLaunchStore(),
            events = mutableListOf(),
            scope = this,
        )
        signUpViewModel.onIntent(OnboardingIntent.SignUpSelected)

        assertEquals(OnboardingEffect.OpenRegistration, signUpViewModel.effects.first())

        val signInViewModel = createViewModel(
            store = FakeFirstLaunchStore(),
            events = mutableListOf(),
            scope = this,
        )
        signInViewModel.onIntent(OnboardingIntent.SignInSelected)

        assertEquals(OnboardingEffect.OpenSignIn, signInViewModel.effects.first())
    }

    private fun createViewModel(
        store: FakeFirstLaunchStore,
        events: MutableList<AnalyticsEventName>,
        scope: TestScope,
        launchDecision: MutableStateFlow<IntroLaunchDecision> = completedDecision(
            IntroLaunchRequest(
                isRequired = true,
                mediaSource = IntroMediaSource.Bundled,
            ),
        ),
    ): OnboardingViewModel = OnboardingViewModel(
        firstLaunchStore = store,
        launchDecision = launchDecision,
        track = { event -> events += event.name },
        coroutineScope = TestScope(StandardTestDispatcher(scope.testScheduler)),
    )

    private fun remoteLaunchRequest(): IntroLaunchRequest = IntroLaunchRequest(
        isRequired = true,
        mediaSource = IntroMediaSource.Remote(
            file = java.io.File("remote-intro.mp4"),
            revision = REMOTE_REVISION,
        ),
    )

    private fun completedDecision(request: IntroLaunchRequest): MutableStateFlow<IntroLaunchDecision> =
        MutableStateFlow(IntroLaunchDecision.complete(request))
}

private class FakeFirstLaunchStore(
    var bundledIntroSeen: Boolean = false,
) : FirstLaunchStore {
    var pending: PendingRemoteIntro? = null
    var lastPresentedRevision = 0L

    override fun isBundledIntroRequired(): Boolean = !bundledIntroSeen

    override fun markBundledIntroSeen() {
        bundledIntroSeen = true
    }

    override fun pendingRemoteIntro(): PendingRemoteIntro? = pending

    override fun lastPresentedRemoteRevision(): Long = lastPresentedRevision

    override fun markRemoteIntroPending(intro: PendingRemoteIntro) {
        pending = intro
    }

    override fun markRemoteIntroPresented(revision: Long) {
        lastPresentedRevision = maxOf(lastPresentedRevision, revision)
        pending = pending?.takeIf { it.revision > revision }
    }

    override fun clearPendingRemoteIntro() {
        pending = null
    }
}

private const val REMOTE_REVISION = 3L
