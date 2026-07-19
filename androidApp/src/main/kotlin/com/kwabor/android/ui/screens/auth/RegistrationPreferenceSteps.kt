package com.kwabor.android.ui.screens.auth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.kwabor.android.R
import com.kwabor.android.design.KwaborSizing
import com.kwabor.android.design.KwaborSpacing
import com.kwabor.android.presentation.auth.RegistrationLocationStatus
import com.kwabor.shared.domain.catalog.City
import com.kwabor.shared.domain.money.KwaborCurrency
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.auth.RegistrationUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CityStep(
    screenState: RegistrationScreenState,
    strings: KwaborStrings,
    actions: RegistrationScreenActions,
    modifier: Modifier,
) {
    val state = screenState.registration
    var citySheetVisible by remember { mutableStateOf(false) }
    val selectedCity = state.cities.firstOrNull { city -> city.id == state.selectedCityId }
    RegistrationScrollableColumn(modifier) {
        StepHeading(strings.registrationCityTitle, stringResource(R.string.registration_city_support))
        CitySelectionButton(
            selectedCityName = selectedCity?.name,
            enabled = state.cities.isNotEmpty() && !state.isLoading,
            onClick = { citySheetVisible = true },
        )
        LocationButton(
            locationStatus = screenState.locationStatus,
            permissionRequestInFlight = screenState.locationPermissionRequestInFlight,
            state = state,
            strings = strings,
            actions = actions,
        )
        LocationStatusMessage(screenState.locationStatus)
        ContinueButton(
            label = strings.registrationContinue,
            loading = state.isLoading,
            enabled = selectedCity != null,
            onClick = actions.onContinueFromCity,
        )
    }
    if (citySheetVisible) {
        CitySelectionSheet(
            cities = state.cities,
            selectedCityId = state.selectedCityId,
            onSelected = { cityId ->
                actions.onCitySelected(cityId)
                citySheetVisible = false
            },
            onDismiss = { citySheetVisible = false },
        )
    }
}

@Composable
private fun CitySelectionButton(selectedCityName: String?, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), enabled = enabled) {
        Text(selectedCityName ?: stringResource(R.string.registration_choose_city))
    }
}

@Composable
private fun LocationButton(
    locationStatus: RegistrationLocationStatus,
    permissionRequestInFlight: Boolean,
    state: RegistrationUiState,
    strings: KwaborStrings,
    actions: RegistrationScreenActions,
) {
    OutlinedButton(
        onClick = actions.onUseLocation,
        modifier = Modifier.fillMaxWidth(),
        enabled = !permissionRequestInFlight &&
            locationStatus != RegistrationLocationStatus.Loading &&
            !state.isLoading,
    ) {
        if (permissionRequestInFlight || locationStatus == RegistrationLocationStatus.Loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(KwaborSpacing.Xl),
                strokeWidth = KwaborSizing.Hairline,
            )
            Spacer(Modifier.width(KwaborSpacing.Sm))
        } else {
            Icon(Icons.Default.LocationOn, contentDescription = null)
            Spacer(Modifier.width(KwaborSpacing.Sm))
        }
        Text(strings.registrationUseLocation)
    }
}

@Composable
private fun LocationStatusMessage(status: RegistrationLocationStatus) {
    val message = when (status) {
        RegistrationLocationStatus.Idle -> null
        RegistrationLocationStatus.Loading -> R.string.registration_location_loading
        RegistrationLocationStatus.PermissionDenied -> R.string.registration_location_denied
        RegistrationLocationStatus.LocationDisabled -> R.string.registration_location_disabled
        RegistrationLocationStatus.Unavailable -> R.string.registration_location_unavailable
        RegistrationLocationStatus.OutsideBenin -> R.string.registration_location_outside_benin
    } ?: return
    Text(
        text = stringResource(message),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
        color = androidx.compose.material3.MaterialTheme.colorScheme.secondary,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CitySelectionSheet(
    cities: List<City>,
    selectedCityId: String?,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filteredCities = remember(cities, query) {
        cities.filter { city -> city.name.contains(query.trim(), ignoreCase = true) }.sortedBy(City::name)
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        CitySelectionContent(
            query = query,
            onQueryChanged = { updated -> query = updated },
            cities = filteredCities,
            selectedCityId = selectedCityId,
            onSelected = onSelected,
        )
    }
}

@Composable
private fun CitySelectionContent(
    query: String,
    onQueryChanged: (String) -> Unit,
    cities: List<City>,
    selectedCityId: String?,
    onSelected: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KwaborSpacing.Xxl),
        verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Md),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChanged,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.registration_city_search)) },
        )
        androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxWidth()) {
            if (cities.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.registration_city_empty),
                        modifier = Modifier.padding(vertical = KwaborSpacing.Xxl),
                    )
                }
            }
            items(count = cities.size, key = { index -> cities[index].id }) { index ->
                CitySelectionRow(
                    city = cities[index],
                    selected = cities[index].id == selectedCityId,
                    onSelected = onSelected,
                )
            }
        }
    }
}

@Composable
private fun CitySelectionRow(city: City, selected: Boolean, onSelected: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelected(city.id) }
            .padding(vertical = KwaborSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = { onSelected(city.id) })
        Text(city.name, modifier = Modifier.padding(start = KwaborSpacing.Sm))
    }
    HorizontalDivider()
}

@Composable
internal fun CurrencyStep(
    state: RegistrationUiState,
    strings: KwaborStrings,
    actions: RegistrationScreenActions,
    modifier: Modifier,
) {
    RegistrationScrollableColumn(modifier) {
        StepHeading(strings.registrationCurrencyTitle, stringResource(R.string.registration_currency_support))
        KwaborCurrency.entries.forEach { currency ->
            SelectionRow(
                selected = state.preferredCurrency == currency,
                label = stringResource(
                    R.string.registration_currency_option,
                    currency.name.uppercase(),
                    currency.symbol,
                ),
                onClick = { actions.onCurrencySelected(currency) },
            )
        }
        ContinueButton(
            label = strings.registrationContinue,
            loading = state.isLoading,
            enabled = true,
            onClick = actions.onContinueFromCurrency,
        )
    }
}

@Composable
private fun SelectionRow(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = KwaborSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, modifier = Modifier.padding(start = KwaborSpacing.Sm))
    }
}
