package com.kwabor.android.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.kwabor.android.design.KwaborAlpha
import com.kwabor.android.design.KwaborColors
import com.kwabor.android.design.KwaborRadius
import com.kwabor.android.design.KwaborSizing
import com.kwabor.android.design.KwaborSpacing
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.navigation.RootNavigationDestination
import com.kwabor.shared.presentation.navigation.RootNavigationProfile
import com.kwabor.shared.presentation.navigation.isVisibleIn
import com.kwabor.shared.presentation.navigation.label

@Composable
internal fun KwaborBottomNavigation(
    selectedDestination: RootNavigationDestination,
    strings: KwaborStrings,
    profile: RootNavigationProfile,
    onDestinationSelected: (RootNavigationDestination) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val selectionColor = colors.onSurface.copy(alpha = KwaborAlpha.NAVIGATION_SELECTION)
    val itemColors = NavigationBarItemDefaults.colors(
        selectedIconColor = colors.onSurface,
        indicatorColor = KwaborColors.Transparent,
        unselectedIconColor = colors.onSurfaceVariant,
        disabledIconColor = colors.outline,
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(NavigationBarDefaults.windowInsets)
            .padding(horizontal = KwaborSpacing.Lg, vertical = KwaborSpacing.Sm),
        shape = CircleShape,
        color = colors.surface,
        shadowElevation = KwaborSpacing.Sm,
    ) {
        NavigationBar(
            modifier = Modifier.height(KwaborSizing.FloatingNavigationHeight),
            containerColor = colors.surface,
            windowInsets = WindowInsets(left = 0, top = 0, right = 0, bottom = 0),
        ) {
            RootNavigationDestination.entries.forEach { destination ->
                val selected = selectedDestination == destination
                NavigationBarItem(
                    selected = selected,
                    enabled = destination.isVisibleIn(profile),
                    onClick = { onDestinationSelected(destination) },
                    icon = { Icon(destination.icon(), contentDescription = destination.label(strings, profile)) },
                    modifier = if (selected) destination.selectionBackground(selectionColor) else Modifier,
                    colors = itemColors,
                )
            }
        }
    }
}

// The outer capsule supplies the curve at the start and end of the bar.
private val StartSelectionShape = RoundedCornerShape(topEnd = KwaborRadius.Pill, bottomEnd = KwaborRadius.Pill)
private val EndSelectionShape = RoundedCornerShape(topStart = KwaborRadius.Pill, bottomStart = KwaborRadius.Pill)

private fun RootNavigationDestination.selectionBackground(color: Color): Modifier {
    val shape = when (this) {
        RootNavigationDestination.Home -> StartSelectionShape
        RootNavigationDestination.Profile -> EndSelectionShape
        RootNavigationDestination.Social,
        RootNavigationDestination.Add,
        RootNavigationDestination.Notifications,
        -> CircleShape
    }
    return Modifier.fillMaxHeight().background(color, shape)
}
