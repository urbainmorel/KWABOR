package com.kwabor.android.ui.screens.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.kwabor.android.R
import com.kwabor.android.design.KwaborRadius
import com.kwabor.android.design.KwaborSizing
import com.kwabor.android.design.KwaborSpacing
import com.kwabor.android.detail.DetailExternalAction
import com.kwabor.android.detail.DetailExternalActionLauncher
import com.kwabor.android.ui.components.KwaborLoadingState
import com.kwabor.android.ui.components.KwaborStateMessage
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.detail.CatalogDetailOpenRequestId
import com.kwabor.shared.presentation.detail.CatalogDetailUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CatalogDetailSheet(
    state: CatalogDetailUiState,
    strings: KwaborStrings,
    platformDependencies: CatalogDetailPlatformDependencies,
    actions: CatalogDetailSheetActions,
    onContentPresented: (CatalogDetailOpenRequestId, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    if (state == CatalogDetailUiState.Closed) return

    val externalActionState = rememberCatalogDetailExternalActionState(
        state = state,
        strings = strings,
        launcher = platformDependencies.externalActionLauncher,
    )
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val sheetHeightFraction = detailSheetHeightFraction(maxWidth.value)
        val heroHeight = detailHeroHeight(maxHeight * sheetHeightFraction)
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val contentActions = rememberAnimatedSheetActions(actions, sheetState)
        val contentResources = CatalogDetailContentResources(
            strings = strings,
            mediaUrlPolicy = platformDependencies.mediaUrlPolicy,
            actions = contentActions,
            externalActionCallbacks = externalActionState.callbacks,
            onContentPresented = onContentPresented,
            heroHeight = heroHeight,
        )
        CatalogDetailPrimaryBottomSheet(
            state = state,
            resources = contentResources,
            sheetState = sheetState,
            snackbarHostState = externalActionState.snackbarHostState,
            sheetHeightFraction = sheetHeightFraction,
        )
        CatalogDetailContactOverlay(externalActionState, strings)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
private data class CatalogDetailExternalActionState(
    val model: CatalogDetailExternalActionUiModel?,
    val contactSheetState: SheetState,
    val snackbarHostState: SnackbarHostState,
    val callbacks: CatalogDetailExternalActionCallbacks,
    val isContactSheetVisible: Boolean,
    val onContactDismiss: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun rememberCatalogDetailExternalActionState(
    state: CatalogDetailUiState,
    strings: KwaborStrings,
    launcher: DetailExternalActionLauncher,
): CatalogDetailExternalActionState {
    val contentModel = (state as? CatalogDetailUiState.Content)?.model
    val contentId = contentModel?.id
    val model = remember(contentModel) { contentModel?.toExternalActionUiModel() }
    val contactSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var isContactSheetVisible by rememberSaveable(contentId) { mutableStateOf(false) }
    val currentLauncher = rememberUpdatedState(launcher)
    val currentFailureMessage = rememberUpdatedState(strings.detail.externalActionFailed)
    val currentDismissLabel = rememberUpdatedState(strings.detail.dismiss)
    val onLaunch = remember(snackbarHostState, coroutineScope, contentId) {
        { action: DetailExternalAction ->
            isContactSheetVisible = false
            if (currentLauncher.value.launch(action).shouldShowGenericError) {
                coroutineScope.showExternalActionError(
                    snackbarHostState,
                    currentFailureMessage.value,
                    currentDismissLabel.value,
                )
            }
        }
    }
    return CatalogDetailExternalActionState(
        model = model,
        contactSheetState = contactSheetState,
        snackbarHostState = snackbarHostState,
        callbacks = CatalogDetailExternalActionCallbacks(
            onLaunch = onLaunch,
            onContactRequested = { isContactSheetVisible = true },
        ),
        isContactSheetVisible = isContactSheetVisible,
        onContactDismiss = { isContactSheetVisible = false },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatalogDetailPrimaryBottomSheet(
    state: CatalogDetailUiState,
    resources: CatalogDetailContentResources,
    sheetState: SheetState,
    snackbarHostState: SnackbarHostState,
    sheetHeightFraction: Float,
) {
    ModalBottomSheet(
        onDismissRequest = resources.actions.onDismiss,
        sheetState = sheetState,
        sheetMaxWidth = KwaborSizing.DetailSheetMaxWidth,
        shape = RoundedCornerShape(topStart = KwaborRadius.Sheet, topEnd = KwaborRadius.Sheet),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(sheetHeightFraction),
        ) {
            CatalogDetailSheetBody(state, resources, Modifier.fillMaxSize())
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(KwaborSpacing.Lg)
                    .externalActionErrorSemantics(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatalogDetailContactOverlay(state: CatalogDetailExternalActionState, strings: KwaborStrings) {
    if (!state.isContactSheetVisible) return
    val contact = state.model?.contact ?: return
    DetailContactBottomSheet(
        contact = contact,
        strings = strings.detail,
        sheetState = state.contactSheetState,
        onDismiss = state.onContactDismiss,
        onLaunch = state.callbacks.onLaunch,
    )
}

private fun CoroutineScope.showExternalActionError(
    hostState: SnackbarHostState,
    message: String,
    dismissLabel: String,
) {
    launch {
        hostState.currentSnackbarData?.dismiss()
        hostState.showSnackbar(
            message = message,
            actionLabel = dismissLabel,
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun rememberAnimatedSheetActions(
    actions: CatalogDetailSheetActions,
    sheetState: SheetState,
): CatalogDetailSheetActions {
    val coroutineScope = rememberCoroutineScope()
    val currentDismiss = rememberUpdatedState(actions.onDismiss)
    return remember(actions, sheetState, coroutineScope) {
        actions.copy(
            onDismiss = {
                coroutineScope.launch {
                    sheetState.hide()
                    currentDismiss.value()
                }
            },
        )
    }
}

@Composable
private fun CatalogDetailSheetBody(
    state: CatalogDetailUiState,
    resources: CatalogDetailContentResources,
    modifier: Modifier,
) {
    val announcement = state.announcementText(resources.strings)
    Box(
        modifier = modifier.semantics {
            if (announcement != null) {
                liveRegion = LiveRegionMode.Polite
                stateDescription = announcement
            }
        },
    ) {
        when (state) {
            CatalogDetailUiState.Closed -> Unit
            is CatalogDetailUiState.Loading -> CatalogDetailLoading(
                resources.strings,
                resources.actions,
                Modifier.fillMaxSize(),
            )
            is CatalogDetailUiState.Content -> CatalogDetailContent(
                state = state,
                resources = resources,
                modifier = Modifier.fillMaxSize(),
            )
            is CatalogDetailUiState.NotFound,
            is CatalogDetailUiState.OfflineFailure,
            is CatalogDetailUiState.Failure,
            -> CatalogDetailFailure(state, resources.strings, resources.actions, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun CatalogDetailLoading(strings: KwaborStrings, actions: CatalogDetailSheetActions, modifier: Modifier) {
    LazyColumn(modifier = modifier) {
        item { DetailCloseRow(label = strings.detail.close, onDismiss = actions.onDismiss) }
        item {
            KwaborLoadingState(
                strings = strings,
                modifier = Modifier,
            )
        }
    }
}

@Composable
private fun CatalogDetailFailure(
    state: CatalogDetailUiState,
    strings: KwaborStrings,
    actions: CatalogDetailSheetActions,
    modifier: Modifier,
) {
    val failure = state.failureContentOrNull() ?: return
    LazyColumn(modifier = modifier) {
        item { DetailCloseRow(label = strings.detail.close, onDismiss = actions.onDismiss) }
        item {
            KwaborStateMessage(
                title = stringResource(failure.titleResource),
                supportingText = failure.message,
                actionLabel = strings.retry,
                onAction = actions.onRetry,
                modifier = Modifier.padding(KwaborSpacing.Xxl),
            )
        }
    }
}

@Composable
private fun DetailCloseRow(label: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KwaborSpacing.Lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .size(KwaborSizing.MinimumAccessibleTouchTarget)
                .semantics { role = Role.Button },
        ) {
            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = label)
        }
    }
    HorizontalDivider()
}

@Composable
private fun CatalogDetailUiState.announcementText(strings: KwaborStrings): String? = when (announcementOrNull()) {
    CatalogDetailAnnouncement.Loading -> stringResource(R.string.detail_loading)
    CatalogDetailAnnouncement.NotFound -> strings.detail.unavailable
    CatalogDetailAnnouncement.Offline -> strings.detail.offlineUnavailable
    CatalogDetailAnnouncement.Failure -> strings.detail.loadFailed
    CatalogDetailAnnouncement.EventEnded -> strings.detail.eventEnded
    null -> null
}

private fun CatalogDetailUiState.failureContentOrNull(): CatalogDetailFailureContent? = when (this) {
    CatalogDetailUiState.Closed,
    is CatalogDetailUiState.Loading,
    is CatalogDetailUiState.Content,
    -> null
    is CatalogDetailUiState.NotFound -> CatalogDetailFailureContent(R.string.detail_not_found_title, message)
    is CatalogDetailUiState.OfflineFailure -> CatalogDetailFailureContent(R.string.detail_offline_title, message)
    is CatalogDetailUiState.Failure -> CatalogDetailFailureContent(R.string.detail_error_title, message)
}

private data class CatalogDetailFailureContent(
    val titleResource: Int,
    val message: String,
)
