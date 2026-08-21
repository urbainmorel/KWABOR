package com.kwabor.android.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.kwabor.android.R
import com.kwabor.android.design.KwaborSpacing
import com.kwabor.android.media.ListingMediaUrlPolicy
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.detail.CatalogDetailContentUiModel
import com.kwabor.shared.presentation.detail.CatalogDetailOpenRequestId
import com.kwabor.shared.presentation.detail.CatalogDetailUiState
import kotlinx.coroutines.awaitCancellation

private data class DetailContentData(
    val state: CatalogDetailUiState.Content,
    val visibleMedia: List<VisibleCatalogDetailMedia>,
    val selectedVisibleIndex: Int,
    val externalActions: CatalogDetailExternalActionUiModel,
)

internal data class CatalogDetailContentResources(
    val strings: KwaborStrings,
    val mediaUrlPolicy: ListingMediaUrlPolicy,
    val actions: CatalogDetailSheetActions,
    val externalActionCallbacks: CatalogDetailExternalActionCallbacks,
    val onContentPresented: (CatalogDetailOpenRequestId, String) -> Unit,
    val heroHeight: Dp,
)

@Composable
internal fun CatalogDetailContent(
    state: CatalogDetailUiState.Content,
    resources: CatalogDetailContentResources,
    modifier: Modifier,
) {
    var hasBeenPlaced by remember(state.openRequestId, state.model.id) { mutableStateOf(false) }
    ReportCatalogDetailContentPresentation(
        state = state,
        hasBeenPlaced = hasBeenPlaced,
        onContentPresented = resources.onContentPresented,
    )
    val visibleMedia = remember(state.model.media, resources.mediaUrlPolicy) {
        visibleOfficialImages(state.model.media, resources.mediaUrlPolicy)
    }
    val data = DetailContentData(
        state = state,
        visibleMedia = visibleMedia,
        selectedVisibleIndex = visibleMediaSelectionIndex(visibleMedia, state.selectedMediaIndex),
        externalActions = remember(state.model) { state.model.toExternalActionUiModel() },
    )
    Column(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                if (coordinates.size.width > 0 && coordinates.size.height > 0) hasBeenPlaced = true
            }
            .semantics { isTraversalGroup = true },
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .semantics { traversalIndex = DETAIL_CONTENT_TRAVERSAL_INDEX },
            verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Xxl),
        ) {
            detailMediaItems(data, resources)
            detailSummaryItems(data, resources)
            detailAttributeItems(data, resources)
            detailLocationItems(data, resources)
        }
        DetailBottomExternalActionBar(
            model = data.externalActions,
            strings = resources.strings.detail,
            callbacks = resources.externalActionCallbacks,
            modifier = Modifier.semantics { traversalIndex = DETAIL_PRIMARY_ACTION_TRAVERSAL_INDEX },
        )
    }
}

@Composable
private fun ReportCatalogDetailContentPresentation(
    state: CatalogDetailUiState.Content,
    hasBeenPlaced: Boolean,
    onContentPresented: (CatalogDetailOpenRequestId, String) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentCallback = rememberUpdatedState(onContentPresented)
    var hasReported by remember(state.openRequestId, state.model.id) { mutableStateOf(false) }
    LaunchedEffect(lifecycleOwner, state.openRequestId, state.model.id, hasBeenPlaced) {
        if (!hasBeenPlaced) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!hasReported) {
                withFrameNanos { frameTimeNanos -> frameTimeNanos }
                if (!hasReported) {
                    hasReported = true
                    currentCallback.value(state.openRequestId, state.model.id)
                }
            }
            awaitCancellation()
        }
    }
}

private fun LazyListScope.detailMediaItems(data: DetailContentData, resources: CatalogDetailContentResources) {
    item {
        DetailHero(
            model = data.state.model,
            media = data.visibleMedia.getOrNull(data.selectedVisibleIndex)?.media,
            resources = resources,
            modifier = Modifier.height(resources.heroHeight),
        )
    }
    if (data.visibleMedia.size > 1) {
        item {
            DetailGallery(
                media = data.visibleMedia,
                selectedVisibleIndex = data.selectedVisibleIndex,
                mediaUrlPolicy = resources.mediaUrlPolicy,
                onMediaSelected = resources.actions.onMediaSelected,
                modifier = Modifier.padding(horizontal = KwaborSpacing.Xxl),
            )
        }
    }
}

private fun LazyListScope.detailSummaryItems(data: DetailContentData, resources: CatalogDetailContentResources) {
    item {
        DetailMetrics(
            metrics = data.state.model.metrics,
            strings = resources.strings.detail,
            modifier = Modifier.padding(horizontal = KwaborSpacing.Xxl),
        )
    }
    item {
        DetailDescription(
            description = data.state.model.description,
            expanded = data.state.isDescriptionExpanded,
            strings = resources.strings.detail,
            onToggle = resources.actions.onDescriptionToggle,
            modifier = Modifier.padding(horizontal = KwaborSpacing.Xxl),
        )
    }
    if (data.state.model.content.showsSummaryPrice()) {
        item {
            DetailPrice(
                price = data.state.model.price,
                strings = resources.strings.detail,
                commonStrings = resources.strings,
                modifier = Modifier.padding(horizontal = KwaborSpacing.Xxl),
            )
        }
    }
}

private fun LazyListScope.detailAttributeItems(data: DetailContentData, resources: CatalogDetailContentResources) {
    item {
        DetailTypedContent(
            content = data.state.model.content,
            strings = resources.strings.detail,
            commonStrings = resources.strings,
            externalActions = CatalogDetailExternalActionPresentation(
                model = data.externalActions,
                callbacks = resources.externalActionCallbacks,
            ),
            modifier = Modifier.padding(horizontal = KwaborSpacing.Xxl),
        )
    }
    if (data.state.model.content !is CatalogDetailContentUiModel.Event) {
        item {
            DetailOpeningHours(
                openingStatusLabel = data.state.model.openingStatusLabel,
                days = data.state.model.openingHours,
                strings = resources.strings.detail,
                modifier = Modifier.padding(horizontal = KwaborSpacing.Xxl),
            )
        }
    }
    if (data.state.model.amenities.isNotEmpty()) {
        item {
            DetailLabelList(
                heading = resources.strings.detail.amenities,
                labels = data.state.model.amenities,
                modifier = Modifier.padding(horizontal = KwaborSpacing.Xxl),
            )
        }
    }
}

private fun LazyListScope.detailLocationItems(data: DetailContentData, resources: CatalogDetailContentResources) {
    item {
        DetailLocation(
            location = data.state.model.location,
            strings = resources.strings.detail,
            modifier = Modifier.padding(horizontal = KwaborSpacing.Xxl),
        )
    }
    data.externalActions.secondaryDirections?.let { action ->
        item {
            DetailInlineDirectionsButton(
                action = action,
                strings = resources.strings.detail,
                onLaunch = resources.externalActionCallbacks.onLaunch,
                modifier = Modifier
                    .fillParentMaxWidth()
                    .padding(horizontal = KwaborSpacing.Xxl),
            )
        }
    }
    if (data.state.model.tags.isNotEmpty()) {
        item {
            DetailLabelList(
                heading = stringResource(R.string.detail_tags),
                labels = data.state.model.tags,
                modifier = Modifier.padding(horizontal = KwaborSpacing.Xxl),
            )
        }
    }
    item { Spacer(modifier = Modifier.size(KwaborSpacing.Xxxl)) }
}

private const val DETAIL_CONTENT_TRAVERSAL_INDEX = 0f
private const val DETAIL_PRIMARY_ACTION_TRAVERSAL_INDEX = 1f

private fun CatalogDetailContentUiModel.showsSummaryPrice(): Boolean = when (this) {
    is CatalogDetailContentUiModel.Place,
    is CatalogDetailContentUiModel.Lodging,
    is CatalogDetailContentUiModel.Food,
    is CatalogDetailContentUiModel.Nightlife,
    -> true
    is CatalogDetailContentUiModel.Guide,
    is CatalogDetailContentUiModel.Event,
    -> false
}
