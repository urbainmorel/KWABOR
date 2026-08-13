package com.kwabor.android.app

import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.navOptions
import com.kwabor.shared.presentation.navigation.RootNavigationDestination
import com.kwabor.shared.presentation.navigation.RootNavigationProfile
import com.kwabor.shared.presentation.navigation.isVisibleIn
import kotlinx.serialization.Serializable

@Serializable
internal data object HomeRoute

@Serializable
internal data object GuideDiscoveryRoute

@Serializable
internal data object SocialRoute

@Serializable
internal data object AddRoute

@Serializable
internal data object NotificationsRoute

@Serializable
internal data object ProfileRoute

@Serializable
internal data object FavoritesRoute {
    val rootDestination = RootNavigationDestination.Profile
}

@Serializable
internal data object SettingsRoute {
    val rootDestination = RootNavigationDestination.Profile
}

internal fun NavDestination.toRootDestination(): RootNavigationDestination? = when {
    hasRoute<HomeRoute>() -> RootNavigationDestination.Home
    hasRoute<GuideDiscoveryRoute>() -> RootNavigationDestination.Home
    hasRoute<SocialRoute>() -> RootNavigationDestination.Social
    hasRoute<AddRoute>() -> RootNavigationDestination.Add
    hasRoute<NotificationsRoute>() -> RootNavigationDestination.Notifications
    hasRoute<ProfileRoute>() -> RootNavigationDestination.Profile
    hasRoute<FavoritesRoute>() -> FavoritesRoute.rootDestination
    hasRoute<SettingsRoute>() -> SettingsRoute.rootDestination
    else -> null
}

internal fun NavHostController.navigateToRoot(
    destination: RootNavigationDestination,
    profile: RootNavigationProfile = RootNavigationProfile.Full,
) {
    val visibleDestination = destination.takeIf { candidate -> candidate.isVisibleIn(profile) }
        ?: RootNavigationDestination.Home
    val options = navOptions {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
    when (visibleDestination) {
        RootNavigationDestination.Home -> navigate(HomeRoute, options)
        RootNavigationDestination.Social -> navigate(SocialRoute, options)
        RootNavigationDestination.Add -> navigate(AddRoute, options)
        RootNavigationDestination.Notifications -> navigate(NotificationsRoute, options)
        RootNavigationDestination.Profile -> navigate(ProfileRoute, options)
    }
}

internal fun NavHostController.resetToHomeAfterAuthenticationEnd() {
    navigate(HomeRoute) {
        popUpTo(graph.findStartDestination().id) {
            saveState = false
        }
        launchSingleTop = true
        restoreState = false
    }
    clearBackStack(HomeRoute)
    clearBackStack(GuideDiscoveryRoute)
    clearBackStack(SocialRoute)
    clearBackStack(AddRoute)
    clearBackStack(NotificationsRoute)
    clearBackStack(ProfileRoute)
    clearBackStack(FavoritesRoute)
    clearBackStack(SettingsRoute)
}

internal fun NavHostController.purgePrivateProfileChildren() {
    val profileWasActive = popBackStack(ProfileRoute, inclusive = false)
    if (!profileWasActive) {
        clearBackStack(ProfileRoute)
    }
    clearBackStack(FavoritesRoute)
    clearBackStack(SettingsRoute)
}
