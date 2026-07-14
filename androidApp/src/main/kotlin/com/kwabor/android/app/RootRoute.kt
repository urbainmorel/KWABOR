package com.kwabor.android.app

import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.navOptions
import com.kwabor.shared.presentation.navigation.RootNavigationDestination
import kotlinx.serialization.Serializable

@Serializable
internal data object HomeRoute

@Serializable
internal data object SocialRoute

@Serializable
internal data object AddRoute

@Serializable
internal data object NotificationsRoute

@Serializable
internal data object ProfileRoute

internal fun NavDestination.toRootDestination(): RootNavigationDestination? = when {
    hasRoute<HomeRoute>() -> RootNavigationDestination.Home
    hasRoute<SocialRoute>() -> RootNavigationDestination.Social
    hasRoute<AddRoute>() -> RootNavigationDestination.Add
    hasRoute<NotificationsRoute>() -> RootNavigationDestination.Notifications
    hasRoute<ProfileRoute>() -> RootNavigationDestination.Profile
    else -> null
}

internal fun NavHostController.navigateToRoot(destination: RootNavigationDestination) {
    val options = navOptions {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
    when (destination) {
        RootNavigationDestination.Home -> navigate(HomeRoute, options)
        RootNavigationDestination.Social -> navigate(SocialRoute, options)
        RootNavigationDestination.Add -> navigate(AddRoute, options)
        RootNavigationDestination.Notifications -> navigate(NotificationsRoute, options)
        RootNavigationDestination.Profile -> navigate(ProfileRoute, options)
    }
}
