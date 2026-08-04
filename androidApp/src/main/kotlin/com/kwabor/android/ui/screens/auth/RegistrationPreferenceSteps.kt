package com.kwabor.android.ui.screens.auth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.kwabor.android.R
import com.kwabor.android.design.KwaborSizing
import com.kwabor.android.design.KwaborSpacing
import com.kwabor.android.presentation.auth.AuthProtectedAction
import com.kwabor.shared.domain.auth.LegalDocumentRevision
import com.kwabor.shared.domain.auth.LegalDocumentType
import com.kwabor.shared.domain.catalog.City
import com.kwabor.shared.domain.money.KwaborCurrency
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.auth.RegistrationRequirementsStatus
import com.kwabor.shared.presentation.auth.RegistrationUiState

@Composable
internal fun ProfileStep(
    screenState: RegistrationScreenState,
    strings: KwaborStrings,
    actions: RegistrationScreenActions,
    modifier: Modifier,
) {
    val state = screenState.registration
    var selector by remember { mutableStateOf(ProfileSelector.None) }
    ProfileForm(
        screenState = screenState,
        strings = strings,
        actions = actions,
        selectionActions = ProfileSelectionActions(
            onOpenCity = { selector = ProfileSelector.City },
            onOpenCurrency = { selector = ProfileSelector.Currency },
        ),
        modifier = modifier,
    )
    ProfileSelectorSheet(
        selector = selector,
        state = state,
        actions = actions,
        onDismiss = { selector = ProfileSelector.None },
    )
}

@Composable
private fun ProfileForm(
    screenState: RegistrationScreenState,
    strings: KwaborStrings,
    actions: RegistrationScreenActions,
    selectionActions: ProfileSelectionActions,
    modifier: Modifier,
) {
    val state = screenState.registration
    val selectedCity = state.cities.firstOrNull { city -> city.id == state.selectedCityId }
    RegistrationScrollableColumn(modifier) {
        StepHeading(
            title = stringResource(R.string.registration_profile_title),
            supportingText = stringResource(R.string.registration_profile_support),
        )
        ProfileIdentityFields(state = state, strings = strings, actions = actions)
        ProfilePreferenceFields(
            state = state,
            selectedCity = selectedCity,
            strings = strings,
            selectionActions = selectionActions,
        )
        RequirementsLoadingIndicator(state = state, strings = strings)
        ProfileLegalAcceptances(state = state, strings = strings, actions = actions)
        ContinueButton(
            label = profileCtaLabel(screenState),
            loading = state.isLoading,
            enabled = state.canCompleteProfile(selectedCity),
            onClick = actions.onCompleteProfile,
        )
    }
}

@Composable
private fun ProfileIdentityFields(
    state: RegistrationUiState,
    strings: KwaborStrings,
    actions: RegistrationScreenActions,
) {
    OutlinedTextField(
        value = state.firstName,
        onValueChange = actions.onFirstNameChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.isLoading,
        singleLine = true,
        label = { Text(strings.authFirstName) },
    )
    OutlinedTextField(
        value = state.lastName,
        onValueChange = actions.onLastNameChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.isLoading,
        singleLine = true,
        label = { Text(strings.authLastName) },
    )
}

@Composable
private fun ProfilePreferenceFields(
    state: RegistrationUiState,
    selectedCity: City?,
    strings: KwaborStrings,
    selectionActions: ProfileSelectionActions,
) {
    Text(strings.registrationCityTitle)
    OutlinedButton(
        onClick = selectionActions.onOpenCity,
        modifier = Modifier.fillMaxWidth(),
        enabled = state.requirementsReady && !state.isLoading,
    ) {
        Text(selectedCity?.name ?: stringResource(R.string.registration_choose_city))
    }
    Text(strings.registrationCurrencyTitle)
    OutlinedButton(
        onClick = selectionActions.onOpenCurrency,
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.isLoading,
    ) {
        Text(
            stringResource(
                R.string.registration_currency_option,
                state.preferredCurrency.name.uppercase(),
                state.preferredCurrency.symbol,
            ),
        )
    }
    Text(
        text = stringResource(R.string.registration_currency_support),
        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
        color = androidx.compose.material3.MaterialTheme.colorScheme.secondary,
    )
}

@Composable
private fun RequirementsLoadingIndicator(state: RegistrationUiState, strings: KwaborStrings) {
    if (state.requirementsStatus != RegistrationRequirementsStatus.Loading) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(KwaborSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator()
        Text(strings.loading)
    }
}

@Composable
private fun ProfileLegalAcceptances(
    state: RegistrationUiState,
    strings: KwaborStrings,
    actions: RegistrationScreenActions,
) {
    LegalAcceptanceRow(
        document = state.termsDocument,
        accepted = state.termsAccepted,
        label = strings.registrationTermsAcceptance,
        type = LegalDocumentType.Terms,
        actions = actions,
    )
    LegalAcceptanceRow(
        document = state.privacyDocument,
        accepted = state.privacyAccepted,
        label = strings.registrationPrivacyAcceptance,
        type = LegalDocumentType.PrivacyPolicy,
        actions = actions,
    )
    LegalAcceptanceRow(
        document = state.ugcDocument,
        accepted = state.ugcAccepted,
        label = strings.registrationUgcAcceptance,
        type = LegalDocumentType.UgcLicense,
        actions = actions,
    )
}

@Composable
private fun ProfileSelectorSheet(
    selector: ProfileSelector,
    state: RegistrationUiState,
    actions: RegistrationScreenActions,
    onDismiss: () -> Unit,
) {
    when (selector) {
        ProfileSelector.None -> Unit
        ProfileSelector.City -> CitySelectionSheet(
            cities = state.cities,
            selectedCityId = state.selectedCityId,
            onSelected = { cityId ->
                actions.onCitySelected(cityId)
                onDismiss()
            },
            onDismiss = onDismiss,
        )
        ProfileSelector.Currency -> CurrencySelectionSheet(
            selectedCurrency = state.preferredCurrency,
            onSelected = { currency ->
                actions.onCurrencySelected(currency)
                onDismiss()
            },
            onDismiss = onDismiss,
        )
    }
}

private fun RegistrationUiState.canCompleteProfile(selectedCity: City?): Boolean = requirementsReady &&
    firstName.isNotBlank() &&
    lastName.isNotBlank() &&
    selectedCity != null &&
    termsAccepted &&
    privacyAccepted &&
    ugcAccepted

private data class ProfileSelectionActions(
    val onOpenCity: () -> Unit,
    val onOpenCurrency: () -> Unit,
)

private enum class ProfileSelector {
    None,
    City,
    Currency,
}

@Composable
private fun profileCtaLabel(state: RegistrationScreenState): String = when (state.softWallContext?.action) {
    AuthProtectedAction.Favorite -> stringResource(R.string.registration_complete_favorite)
    AuthProtectedAction.Like -> stringResource(R.string.registration_complete_like)
    AuthProtectedAction.Other, null -> stringResource(R.string.registration_complete_default)
}

@Composable
private fun LegalAcceptanceRow(
    document: LegalDocumentRevision?,
    accepted: Boolean,
    label: String,
    type: LegalDocumentType,
    actions: RegistrationScreenActions,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = accepted,
                onCheckedChange = { updated -> actions.onLegalAcceptanceChanged(type, updated) },
                enabled = document != null,
            )
            Text(label, modifier = Modifier.weight(1f))
        }
        document?.let { revision ->
            TextButton(
                onClick = { actions.onOpenLegalDocument(type) },
                modifier = Modifier.padding(start = KwaborSizing.TouchTarget),
            ) {
                Text(stringResource(R.string.registration_read_document, revision.version))
            }
        }
    }
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KwaborSpacing.Xxl),
            verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Md),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { updated -> query = updated },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.registration_city_search)) },
            )
            androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxWidth()) {
                if (filteredCities.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.registration_city_empty),
                            modifier = Modifier.padding(vertical = KwaborSpacing.Xxl),
                        )
                    }
                }
                items(count = filteredCities.size, key = { index -> filteredCities[index].id }) { index ->
                    SelectionRow(
                        selected = filteredCities[index].id == selectedCityId,
                        label = filteredCities[index].name,
                        onClick = { onSelected(filteredCities[index].id) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CurrencySelectionSheet(
    selectedCurrency: KwaborCurrency,
    onSelected: (KwaborCurrency) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KwaborSpacing.Xxl, vertical = KwaborSpacing.Lg),
        ) {
            KwaborCurrency.entries.forEach { currency ->
                SelectionRow(
                    selected = currency == selectedCurrency,
                    label = stringResource(
                        R.string.registration_currency_option,
                        currency.name.uppercase(),
                        currency.symbol,
                    ),
                    onClick = { onSelected(currency) },
                )
            }
        }
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
    HorizontalDivider()
}
