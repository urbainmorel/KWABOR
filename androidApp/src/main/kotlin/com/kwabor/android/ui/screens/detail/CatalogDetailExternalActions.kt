package com.kwabor.android.ui.screens.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import com.kwabor.android.design.KwaborSizing
import com.kwabor.android.design.KwaborSpacing
import com.kwabor.android.detail.DetailExternalAction
import com.kwabor.shared.i18n.CatalogDetailStrings

@Composable
internal fun DetailBottomExternalActionBar(
    model: CatalogDetailExternalActionUiModel,
    strings: CatalogDetailStrings,
    callbacks: CatalogDetailExternalActionCallbacks,
    modifier: Modifier = Modifier,
) {
    val primary = model.primary ?: return

    Surface(modifier = modifier.fillMaxWidth()) {
        Column {
            HorizontalDivider()
            Row(
                modifier = Modifier.padding(horizontal = KwaborSpacing.Xxl, vertical = KwaborSpacing.Lg),
                horizontalArrangement = Arrangement.spacedBy(KwaborSpacing.Md),
            ) {
                DetailPrimaryExternalAction(primary, strings, callbacks)
            }
        }
    }
}

@Composable
private fun RowScope.DetailPrimaryExternalAction(
    primary: CatalogDetailPrimaryExternalAction,
    strings: CatalogDetailStrings,
    callbacks: CatalogDetailExternalActionCallbacks,
) {
    when (primary) {
        is CatalogDetailPrimaryExternalAction.Directions -> DetailPrimaryDirectionsButton(
            action = primary.action,
            label = strings.directions,
            opensExternally = strings.opensExternally,
            onLaunch = callbacks.onLaunch,
        )
        CatalogDetailPrimaryExternalAction.Contact -> DetailContactButton(
            label = strings.contact,
            onClick = callbacks.onContactRequested,
        )
        is CatalogDetailPrimaryExternalAction.Ticket -> DetailTicketButton(
            action = primary.action,
            enabled = primary.enabled,
            strings = strings,
            onLaunch = callbacks.onLaunch,
        )
    }
}

@Composable
internal fun DetailInlineDirectionsButton(
    action: DetailExternalAction.Directions,
    strings: CatalogDetailStrings,
    onLaunch: (DetailExternalAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = { onLaunch(action) },
        modifier = modifier
            .heightIn(min = KwaborSizing.MinimumAccessibleTouchTarget)
            .externalActionSemantics(strings.opensExternally),
    ) {
        ExternalActionButtonContent(icon = Icons.Filled.Directions, label = strings.directions)
    }
}

@Composable
private fun RowScope.DetailPrimaryDirectionsButton(
    action: DetailExternalAction.Directions,
    label: String,
    opensExternally: String,
    onLaunch: (DetailExternalAction) -> Unit,
) {
    Button(
        onClick = { onLaunch(action) },
        modifier = Modifier
            .weight(1f)
            .heightIn(min = KwaborSizing.MinimumAccessibleTouchTarget)
            .externalActionSemantics(opensExternally),
    ) {
        ExternalActionButtonContent(icon = Icons.Filled.Directions, label = label)
    }
}

@Composable
private fun RowScope.DetailContactButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .weight(1f)
            .heightIn(min = KwaborSizing.MinimumAccessibleTouchTarget),
    ) {
        ExternalActionButtonContent(icon = Icons.Filled.Call, label = label)
    }
}

@Composable
private fun RowScope.DetailTicketButton(
    action: DetailExternalAction.Https,
    enabled: Boolean,
    strings: CatalogDetailStrings,
    onLaunch: (DetailExternalAction) -> Unit,
) {
    Button(
        onClick = { onLaunch(action) },
        enabled = enabled,
        modifier = Modifier
            .weight(1f)
            .heightIn(min = KwaborSizing.MinimumAccessibleTouchTarget)
            .externalActionSemantics(if (enabled) strings.opensExternally else strings.eventEnded),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        ExternalActionButtonContent(icon = Icons.Filled.ConfirmationNumber, label = strings.ticket)
    }
}

@Composable
internal fun DetailMenuButton(
    label: String,
    opensExternally: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = KwaborSizing.MinimumAccessibleTouchTarget)
            .externalActionSemantics(opensExternally),
    ) {
        ExternalActionButtonContent(icon = Icons.Filled.RestaurantMenu, label = label)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailContactBottomSheet(
    contact: CatalogDetailContactActions,
    strings: CatalogDetailStrings,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onLaunch: (DetailExternalAction) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = KwaborSpacing.Xxl, vertical = KwaborSpacing.Lg)
                .semantics { isTraversalGroup = true },
            verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Sm),
        ) {
            DetailContactSheetHeader(strings)
            DetailContactActionList(contact, strings, onLaunch)
        }
    }
}

@Composable
private fun DetailContactSheetHeader(strings: CatalogDetailStrings) {
    Text(
        text = strings.contact,
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        text = strings.opensExternally,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.secondary,
    )
}

@Composable
private fun DetailContactActionList(
    contact: CatalogDetailContactActions,
    strings: CatalogDetailStrings,
    onLaunch: (DetailExternalAction) -> Unit,
) {
    contact.phone?.let { action ->
        DetailContactActionRow(strings.call, Icons.Filled.Call, strings.opensExternally, PHONE_TRAVERSAL_INDEX) {
            onLaunch(action)
        }
    }
    contact.whatsapp?.let { action ->
        DetailContactActionRow(
            strings.whatsapp,
            Icons.AutoMirrored.Filled.Chat,
            strings.opensExternally,
            WHATSAPP_TRAVERSAL_INDEX,
        ) {
            onLaunch(action)
        }
    }
    contact.website?.let { action ->
        DetailContactActionRow(
            strings.website,
            Icons.Filled.Language,
            strings.opensExternally,
            WEBSITE_TRAVERSAL_INDEX,
        ) {
            onLaunch(action)
        }
    }
    contact.email?.let { action ->
        DetailContactActionRow(strings.email, Icons.Filled.Email, strings.opensExternally, EMAIL_TRAVERSAL_INDEX) {
            onLaunch(action)
        }
    }
}

@Composable
private fun DetailContactActionRow(
    label: String,
    icon: ImageVector,
    opensExternally: String,
    focusOrder: Float,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(text = label, fontWeight = FontWeight.Medium) },
        leadingContent = { Icon(imageVector = icon, contentDescription = null) },
        trailingContent = { Icon(imageVector = Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = KwaborSizing.MinimumAccessibleTouchTarget)
            .semantics {
                role = Role.Button
                stateDescription = opensExternally
                traversalIndex = focusOrder
            }
            .clickable(onClick = onClick),
    )
}

@Composable
private fun ExternalActionButtonContent(icon: ImageVector, label: String) {
    Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(KwaborSpacing.Xl))
    Spacer(modifier = Modifier.width(KwaborSpacing.Sm))
    Text(text = label)
}

internal fun Modifier.externalActionErrorSemantics(): Modifier = semantics {
    liveRegion = LiveRegionMode.Assertive
}

private fun Modifier.externalActionSemantics(opensExternally: String): Modifier = semantics {
    stateDescription = opensExternally
}

private const val PHONE_TRAVERSAL_INDEX = 1f
private const val WHATSAPP_TRAVERSAL_INDEX = 2f
private const val WEBSITE_TRAVERSAL_INDEX = 3f
private const val EMAIL_TRAVERSAL_INDEX = 4f
