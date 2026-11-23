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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import com.kwabor.android.R
import com.kwabor.android.design.KwaborSpacing
import com.kwabor.android.presentation.auth.AuthProtectedAction
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
    var showCitySelector by remember { mutableStateOf(false) }
    RegistrationScrollableColumn(modifier) {
        StepHeading(
            title = stringResource(R.string.registration_profile_title),
            supportingText = stringResource(R.string.registration_profile_support),
        )
        RequirementsLoadingIndicator(state = state, strings = strings)
        if (state.requiresProfileIdentity) {
            ProfileIdentityFields(state = state, strings = strings, actions = actions)
            RegistrationCityField(state, strings) { showCitySelector = true }
        }
        CurrencyOptions(state = state, strings = strings, onSelected = actions.onCurrencySelected)
        ProfileLegalAcceptance(state = state, strings = strings, actions = actions)
        ContinueButton(
            label = profileCtaLabel(screenState),
            loading = state.isLoading,
            enabled = state.canCompleteProfile,
            onClick = actions.onCompleteProfile,
        )
    }
    if (showCitySelector) {
        CitySelectionSheet(
            cities = state.cities,
            selectedCityId = state.selectedCityId,
            onSelected = { cityId ->
                actions.onCitySelected(cityId)
                showCitySelector = false
            },
            onDismiss = { showCitySelector = false },
        )
    }
}

@Composable
private fun RegistrationCityField(state: RegistrationUiState, strings: KwaborStrings, onClick: () -> Unit) {
    Text(strings.registrationCityTitle)
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = state.requirementsReady && !state.isLoading,
    ) {
        val city = state.cities.firstOrNull { it.id == state.selectedCityId }
        Text(city?.name ?: stringResource(R.string.registration_choose_city))
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
private fun CurrencyOptions(state: RegistrationUiState, strings: KwaborStrings, onSelected: (KwaborCurrency) -> Unit) {
    Text(strings.registrationCurrencyTitle, style = MaterialTheme.typography.titleSmall)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(KwaborSpacing.Xs),
    ) {
        KwaborCurrency.entries.forEach { currency ->
            FilterChip(
                selected = currency == state.preferredCurrency,
                onClick = { onSelected(currency) },
                label = { Text(currency.name.uppercase(), maxLines = 1) },
                modifier = Modifier.weight(1f),
                enabled = !state.isLoading,
            )
        }
    }
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
private fun ProfileLegalAcceptance(
    state: RegistrationUiState,
    strings: KwaborStrings,
    actions: RegistrationScreenActions,
) {
    if (state.termsDocument == null || state.privacyDocument == null) return
    val linkStyle = TextLinkStyles(
        style = SpanStyle(color = MaterialTheme.colorScheme.onSurface, textDecoration = TextDecoration.Underline),
    )
    val termsLink = LinkAnnotation.Clickable("terms", linkStyle) {
        actions.onOpenLegalDocument(LegalDocumentType.Terms)
    }
    val privacyLink = LinkAnnotation.Clickable("privacy", linkStyle) {
        actions.onOpenLegalDocument(LegalDocumentType.PrivacyPolicy)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = state.legalAccepted,
            onCheckedChange = actions.onLegalAcceptanceChanged,
            enabled = state.requirementsReady && !state.isLoading,
            modifier = Modifier.semantics { contentDescription = strings.authLegalAcceptance },
        )
        Text(
            text = buildAnnotatedString {
                append(strings.registrationLegalPrefix)
                withLink(termsLink) { append(strings.registrationTermsLabel) }
                append(strings.registrationLegalConjunction)
                withLink(privacyLink) { append(strings.registrationPrivacyLabel) }
            },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun profileCtaLabel(state: RegistrationScreenState): String = when (state.softWallContext?.action) {
    AuthProtectedAction.Favorite -> stringResource(R.string.registration_complete_favorite)
    AuthProtectedAction.Like -> stringResource(R.string.registration_complete_like)
    AuthProtectedAction.Other, null -> stringResource(R.string.registration_complete_default)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CitySelectionSheet(
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
