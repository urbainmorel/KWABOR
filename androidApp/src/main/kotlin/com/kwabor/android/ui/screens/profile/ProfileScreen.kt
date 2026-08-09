package com.kwabor.android.ui.screens.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.kwabor.android.design.KwaborRadius
import com.kwabor.android.design.KwaborSizing
import com.kwabor.android.design.KwaborSpacing
import com.kwabor.shared.i18n.KwaborStrings

internal data class ProfileScreenUiModel(val email: String?)

internal data class ProfileScreenActions(
    val onFavoritesRequested: () -> Unit,
    val onSettingsRequested: () -> Unit,
)

internal object ProfileScreen {
    @Composable
    operator fun invoke(
        model: ProfileScreenUiModel,
        strings: KwaborStrings,
        actions: ProfileScreenActions,
        modifier: Modifier = Modifier,
    ) {
        val entryLabels = profileEntryLabels(strings)
        Surface(modifier = modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(KwaborSpacing.Xxl),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.Top,
            ) {
                Text(
                    text = strings.profile,
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(KwaborSpacing.Xxl))
                ProfileEmail(email = model.email, strings = strings)
                Spacer(Modifier.height(KwaborSpacing.Xxl))
                ProfileEntry(
                    title = entryLabels.favorites,
                    icon = Icons.Default.Bookmark,
                    onClick = actions.onFavoritesRequested,
                )
                Spacer(Modifier.height(KwaborSpacing.Lg))
                SettingsEntry(
                    title = entryLabels.settings,
                    strings = strings,
                    onClick = actions.onSettingsRequested,
                )
            }
        }
    }
}

@Composable
private fun ProfileEmail(email: String?, strings: KwaborStrings) {
    Text(
        text = strings.settings.emailLabel,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.secondary,
    )
    Spacer(Modifier.height(KwaborSpacing.Xs))
    Text(
        text = profileEmailValue(email = email, strings = strings),
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun SettingsEntry(title: String, strings: KwaborStrings, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = KwaborSizing.MinimumAccessibleTouchTarget)
            .semantics { role = Role.Button },
        shape = RoundedCornerShape(KwaborRadius.Card),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        SettingsEntryContent(title = title, strings = strings)
    }
}

@Composable
private fun ProfileEntry(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = KwaborSizing.MinimumAccessibleTouchTarget)
            .semantics { role = Role.Button },
        shape = RoundedCornerShape(KwaborRadius.Card),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(KwaborSpacing.Lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = icon, contentDescription = null)
            Spacer(Modifier.width(KwaborSpacing.Lg))
            Text(
                text = title,
                modifier = Modifier.weight(WEIGHT_CONTENT),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.width(KwaborSpacing.Lg))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
            )
        }
    }
}

@Composable
private fun SettingsEntryContent(title: String, strings: KwaborStrings) {
    Row(
        modifier = Modifier.padding(KwaborSpacing.Lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Settings,
            contentDescription = null,
        )
        Spacer(Modifier.width(KwaborSpacing.Lg))
        Column(modifier = Modifier.weight(WEIGHT_CONTENT)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(KwaborSpacing.Xs))
            Text(
                text = strings.settings.profileEntrySubtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        Spacer(Modifier.width(KwaborSpacing.Lg))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
        )
    }
}

internal fun profileEmailValue(email: String?, strings: KwaborStrings): String = strings.settings.accountEmail(email)

internal data class ProfileEntryLabels(
    val favorites: String,
    val settings: String,
)

internal fun profileEntryLabels(strings: KwaborStrings): ProfileEntryLabels = ProfileEntryLabels(
    favorites = strings.favorites.title,
    settings = strings.settings.title,
)

private const val WEIGHT_CONTENT = 1f
