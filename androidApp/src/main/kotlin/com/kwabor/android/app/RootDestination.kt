package com.kwabor.android.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.ui.graphics.vector.ImageVector
import com.kwabor.shared.presentation.navigation.RootNavigationDestination

internal fun RootNavigationDestination.icon(): ImageVector = when (this) {
    RootNavigationDestination.Home -> Icons.Filled.Explore
    RootNavigationDestination.Social -> Icons.Filled.PlayCircle
    RootNavigationDestination.Add -> Icons.Filled.AddCircle
    RootNavigationDestination.Notifications -> Icons.Filled.Notifications
    RootNavigationDestination.Profile -> Icons.Filled.Person
}
