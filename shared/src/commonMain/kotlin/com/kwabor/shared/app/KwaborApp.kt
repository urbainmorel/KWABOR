package com.kwabor.shared.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kwabor.shared.design.KwaborTheme
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.stringsFor

enum class RootDestination {
    Home,
    Social,
    Add,
    Notifications,
    Profile,
}

@Composable
fun KwaborApp() {
    KwaborTheme {
        var selectedDestination by remember { mutableStateOf(RootDestination.Home) }
        val strings = stringsFor(AppLocale.French)

        Scaffold(
            bottomBar = {
                NavigationBar {
                    RootDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = selectedDestination == destination,
                            onClick = { selectedDestination = destination },
                            icon = { Text(text = destination.label(strings)) },
                            label = { Text(text = destination.label(strings)) },
                        )
                    }
                }
            },
        ) { paddingValues ->
            KwaborRootContent(
                paddingValues = paddingValues,
                title = strings.homeTitle,
                status = strings.foundationStatus,
            )
        }
    }
}

@Composable
private fun KwaborRootContent(paddingValues: PaddingValues, title: String, status: String) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(all = 24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(text = title)
            Text(text = status)
        }
    }
}

private fun RootDestination.label(strings: com.kwabor.shared.i18n.KwaborStrings): String = when (this) {
    RootDestination.Home -> strings.home
    RootDestination.Social -> strings.social
    RootDestination.Add -> strings.add
    RootDestination.Notifications -> strings.notifications
    RootDestination.Profile -> strings.profile
}
