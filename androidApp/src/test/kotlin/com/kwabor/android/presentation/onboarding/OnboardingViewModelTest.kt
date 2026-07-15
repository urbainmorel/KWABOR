package com.kwabor.android.presentation.onboarding

import com.kwabor.android.onboarding.FirstLaunchStore
import com.kwabor.shared.domain.observability.AnalyticsEventName
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    fun introCompletion_isPersistedAndDoesNotCreateGuestSession() = runTest {
        val store = FakeFirstLaunchStore()
        val events = mutableListOf<AnalyticsEventName>()
        val viewModel = createViewModel(store = store, events = events, scope = this)

        viewModel.onIntent(OnboardingIntent.IntroDisplayed)
        viewModel.onIntent(OnboardingIntent.IntroCompleted)

        assertTrue(store.introSeen)
        assertFalse(viewModel.state.value.isIntroRequired)
        assertFalse(viewModel.state.value.isGuestSession)
        assertEquals(listOf(AnalyticsEventName.IntroVideoShown), events)
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

private class FakeFirstLaunchStore : FirstLaunchStore {
    var introSeen = false

    override fun isIntroRequired(): Boolean = !introSeen

    override fun markIntroSeen() {
        introSeen = true
    }
}
