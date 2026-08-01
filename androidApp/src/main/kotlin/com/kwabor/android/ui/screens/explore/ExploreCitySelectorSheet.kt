package com.kwabor.android.ui.screens.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.kwabor.android.design.KwaborSizing
import com.kwabor.android.design.KwaborSpacing
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.explore.ExploreCityOption
import com.kwabor.shared.presentation.explore.ExploreUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExploreCitySelectorSheet(state: ExploreUiState, strings: KwaborStrings, actions: ExploreScreenActions) {
    ModalBottomSheet(onDismissRequest = actions.onCityDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KwaborSpacing.Xxl, vertical = KwaborSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Md),
        ) {
            Text(
                text = strings.exploreSelectCity,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            ExploreLocationButton(
                isLocating = state.isLocating,
                strings = strings,
                onClick = actions.onUseLocation,
            )
            state.locationMessage?.let { message -> ExploreLocationMessage(message) }
            ExploreCityOptions(
                cities = state.availableCities,
                selectedCityId = state.selectedCityId,
                emptyLabel = strings.emptyStateTitle,
                onCitySelected = actions.onCitySelected,
            )
        }
    }
}

@Composable
private fun ExploreLocationButton(isLocating: Boolean, strings: KwaborStrings, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = !isLocating,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = KwaborSizing.MinimumAccessibleTouchTarget),
    ) {
        if (isLocating) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(KwaborSpacing.Xl)
                    .semantics { contentDescription = strings.loading },
                strokeWidth = KwaborSizing.Hairline,
            )
        } else {
            Icon(Icons.Filled.MyLocation, contentDescription = null)
        }
        Spacer(modifier = Modifier.width(KwaborSpacing.Sm))
        Text(strings.exploreUseLocation)
    }
}

@Composable
private fun ExploreLocationMessage(message: String) {
    Text(
        text = message,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.secondary,
    )
}

@Composable
private fun ExploreCityOptions(
    cities: List<ExploreCityOption>,
    selectedCityId: String?,
    emptyLabel: String,
    onCitySelected: (String) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        if (cities.isEmpty()) {
            item {
                Text(
                    text = emptyLabel,
                    modifier = Modifier.padding(vertical = KwaborSpacing.Xxl),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
        items(items = cities, key = ExploreCityOption::id) { city ->
            ExploreCityOptionRow(city, city.id == selectedCityId, onCitySelected)
        }
    }
}

@Composable
private fun ExploreCityOptionRow(city: ExploreCityOption, selected: Boolean, onSelected: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = KwaborSizing.MinimumAccessibleTouchTarget)
            .selectable(
                selected = selected,
                onClick = { onSelected(city.id) },
                role = Role.RadioButton,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(city.label, modifier = Modifier.padding(start = KwaborSpacing.Sm))
    }
    HorizontalDivider()
}
