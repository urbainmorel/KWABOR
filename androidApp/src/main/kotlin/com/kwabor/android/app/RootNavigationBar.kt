package com.kwabor.android.app

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.navigation.RootNavigationDestination
import com.kwabor.shared.presentation.navigation.RootNavigationProfile
import com.kwabor.shared.presentation.navigation.label

@Composable
internal fun KwaborBottomNavigation(
    selectedDestination: RootNavigationDestination,
    strings: KwaborStrings,
    profile: RootNavigationProfile,
    onDestinationSelected: (RootNavigationDestination) -> Unit,
) {
    NavigationBar {
        profile.destinations.forEach { destination ->
            NavigationBarItem(
                selected = selectedDestination == destination,
                onClick = { onDestinationSelected(destination) },
                icon = { Icon(destination.icon(), contentDescription = destination.label(strings, profile)) },
                label = { Text(text = destination.label(strings, profile)) },
            )
        }
    }
}
