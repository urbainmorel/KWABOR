package com.kwabor.android.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.kwabor.android.design.KwaborSpacing
import com.kwabor.android.ui.screens.auth.CitySelectionSheet
import com.kwabor.shared.domain.catalog.City
import com.kwabor.shared.domain.profile.ProfileDetails
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.profile.ProfileDetailsForm
import com.kwabor.shared.presentation.profile.ProfileDetailsPresenter
import kotlinx.coroutines.launch

@Composable
internal fun ProfileDetailsEditor(ownerId: String, presenter: ProfileDetailsPresenter, strings: KwaborStrings) {
    key(ownerId) {
        var form by remember { mutableStateOf<ProfileDetailsForm?>(null) }
        var loading by remember { mutableStateOf(true) }
        var reload by remember { mutableIntStateOf(0) }
        LaunchedEffect(presenter, reload) {
            loading = true
            form = presenter.load(ownerId)
            loading = false
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = KwaborSpacing.Xxxl),
            verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Sm),
        ) {
            Text(strings.settings.profileDetailsTitle, style = MaterialTheme.typography.titleLarge)
            val loaded = form
            when {
                loading -> CircularProgressIndicator()
                loaded == null -> {
                    Text(strings.settings.profileLoadFailed)
                    TextButton(onClick = { reload++ }) { Text(strings.retry) }
                }
                else -> EditableProfileDetails(
                    form = loaded,
                    strings = strings,
                    save = { details -> presenter.save(ownerId, details) },
                )
            }
        }
    }
}

@Composable
private fun EditableProfileDetails(
    form: ProfileDetailsForm,
    strings: KwaborStrings,
    save: suspend (ProfileDetails) -> ProfileDetails?,
) {
    val scope = rememberCoroutineScope()
    var saved by remember(form) { mutableStateOf(form.details) }
    var draft by remember(form) { mutableStateOf(form.details) }
    var saving by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    ProfileDetailsFields(draft, form.cities, strings, enabled = !saving) { details ->
        draft = details
        feedback = null
    }
    Button(
        onClick = {
            saving = true
            scope.launch {
                val result = save(draft)
                if (result != null) {
                    saved = result
                    draft = result
                }
                feedback = if (result == null) strings.settings.profileSaveFailed else strings.settings.profileSaved
                saving = false
            }
        },
        enabled = !saving && draft != saved,
    ) { Text(if (saving) strings.loading else strings.settings.profileSave) }
    feedback?.let { Text(it) }
}

@Composable
private fun ProfileDetailsFields(
    draft: ProfileDetails,
    cities: List<City>,
    strings: KwaborStrings,
    enabled: Boolean,
    onChange: (ProfileDetails) -> Unit,
) {
    OutlinedTextField(
        value = draft.firstName,
        onValueChange = { onChange(draft.copy(firstName = it)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = enabled,
        label = { Text(strings.authFirstName) },
    )
    OutlinedTextField(
        value = draft.lastName,
        onValueChange = { onChange(draft.copy(lastName = it)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = enabled,
        label = { Text(strings.authLastName) },
    )
    ProfileCityField(draft.cityId, cities, strings, enabled) { onChange(draft.copy(cityId = it)) }
}

@Composable
private fun ProfileCityField(
    cityId: String?,
    cities: List<City>,
    strings: KwaborStrings,
    enabled: Boolean,
    onChange: (String?) -> Unit,
) {
    var showCities by remember { mutableStateOf(false) }
    Text(strings.registrationCityTitle)
    OutlinedButton(onClick = { showCities = true }, enabled = enabled) {
        val city = cities.firstOrNull { it.id == cityId }
        Text(city?.name ?: strings.settings.profileNoCity)
    }
    if (cityId != null) {
        TextButton(onClick = { onChange(null) }, enabled = enabled) {
            Text(strings.settings.profileClearCity)
        }
    }
    if (showCities) {
        CitySelectionSheet(
            cities = cities,
            selectedCityId = cityId,
            onSelected = {
                onChange(it)
                showCities = false
            },
            onDismiss = { showCities = false },
        )
    }
}
