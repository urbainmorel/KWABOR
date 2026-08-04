package com.kwabor.android.app

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.navigation.RootNavigationDestination
import com.kwabor.shared.presentation.navigation.label

@Composable
internal fun KwaborBottomNavigation(
    selectedDestination: RootNavigationDestination,
    strings: KwaborStrings,
    onDestinationSelected: (RootNavigationDestination) -> Unit,
) {
    NavigationBar {
        RootNavigationDestination.entries.forEach { destination ->
            NavigationBarItem(
                selected = selectedDestination == destination,
                onClick = { onDestinationSelected(destination) },
                icon = { Icon(destination.icon(), contentDescription = destination.label(strings)) },
                label = { Text(text = destination.label(strings)) },
            )
        }
    }
}
