package com.kwabor.shared.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.ui.graphics.vector.ImageVector
import com.kwabor.shared.i18n.KwaborStrings

enum class RootDestination {
    Home,
    Social,
    Add,
    Notifications,
    Profile,
}

internal fun RootDestination.label(strings: KwaborStrings): String = when (this) {
    RootDestination.Home -> strings.home
    RootDestination.Social -> strings.social
    RootDestination.Add -> strings.add
    RootDestination.Notifications -> strings.notifications
    RootDestination.Profile -> strings.profile
}

internal fun RootDestination.icon(): ImageVector = when (this) {
    RootDestination.Home -> Icons.Filled.Explore
    RootDestination.Social -> Icons.Filled.PlayCircle
    RootDestination.Add -> Icons.Filled.AddCircle
    RootDestination.Notifications -> Icons.Filled.Notifications
    RootDestination.Profile -> Icons.Filled.Person
}
