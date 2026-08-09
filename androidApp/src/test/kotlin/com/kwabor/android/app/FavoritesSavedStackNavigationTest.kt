package com.kwabor.android.app

import android.app.Application
import android.content.Context
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import com.kwabor.shared.presentation.navigation.RootNavigationDestination
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [TEST_SDK], manifest = Config.NONE)
class FavoritesSavedStackNavigationTest {
    @Test
    fun coldGuestRestore_resetsHomeAndClearsRestoredPrivateProfileStack() {
        val navController = testNavController()
        navController.navigateToRoot(RootNavigationDestination.Profile)
        navController.navigate(FavoritesRoute)
        navController.navigateToRoot(RootNavigationDestination.Home)

        navController.navigateToRoot(RootNavigationDestination.Profile)
        assertCurrentRoute<FavoritesRoute>(navController)

        navController.applyFavoritesNavigationPrivacy(
            FavoritesNavigationPrivacyDecision.ResetToHomeAndPurge,
        )

        assertCurrentRoute<HomeRoute>(navController)
        navController.navigateToRoot(RootNavigationDestination.Profile)

        assertCurrentRoute<ProfileRoute>(navController)
    }

    @Test
    fun accountChange_clearsInactiveSavedProfileStackBeforeRestore() {
        val navController = testNavController()
        navController.navigateToRoot(RootNavigationDestination.Profile)
        navController.navigate(FavoritesRoute)
        navController.navigateToRoot(RootNavigationDestination.Home)

        navController.navigateToRoot(RootNavigationDestination.Profile)
        assertCurrentRoute<FavoritesRoute>(navController)
        navController.navigateToRoot(RootNavigationDestination.Home)
        assertFalse(navController.popBackStack(ProfileRoute, inclusive = false))

        navController.purgePrivateProfileChildren()
        navController.navigateToRoot(RootNavigationDestination.Profile)

        assertCurrentRoute<ProfileRoute>(navController)
    }

    private fun testNavController(): TestNavHostController {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return TestNavHostController(context).apply {
            navigatorProvider.addNavigator(ComposeNavigator())
            graph = createGraph(startDestination = HomeRoute) {
                composable<HomeRoute> {}
                composable<GuideDiscoveryRoute> {}
                composable<SocialRoute> {}
                composable<AddRoute> {}
                composable<NotificationsRoute> {}
                composable<ProfileRoute> {}
                composable<FavoritesRoute> {}
                composable<SettingsRoute> {}
            }
        }
    }
}

private inline fun <reified Route : Any> assertCurrentRoute(navController: NavHostController) {
    assertTrue(requireNotNull(navController.currentDestination).hasRoute<Route>())
}

private const val TEST_SDK = 35
