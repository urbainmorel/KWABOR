package com.kwabor.android.ui.screens.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.kwabor.android.R
import com.kwabor.android.design.KwaborSizing
import com.kwabor.android.design.KwaborSpacing
import com.kwabor.android.presentation.auth.AuthAccessUiState
import com.kwabor.android.presentation.auth.isAccountDeletionConfirmationValid
import com.kwabor.android.ui.screens.auth.AuthInlineMessage
import com.kwabor.android.ui.screens.auth.AuthPasswordField
import com.kwabor.android.ui.screens.auth.AuthPasswordFieldOptions
import com.kwabor.android.ui.screens.auth.GoogleSignInButton
import com.kwabor.shared.domain.auth.AuthenticationMethod
import com.kwabor.shared.i18n.KwaborStrings

internal data class ProfileSessionScreenActions(
    val onRequestSignOut: () -> Unit,
    val onCancelSignOut: () -> Unit,
    val onConfirmSignOut: () -> Unit,
    val onRequestAccountDeletion: () -> Unit,
    val onCancelAccountDeletion: () -> Unit,
    val onDeleteAccountWithPassword: (String, String) -> Unit,
    val onDeleteAccountWithGoogle: (String) -> Unit,
)

internal data class ProfileSessionUiModel(
    val email: String,
    val authenticationMethod: AuthenticationMethod,
    val authAccessState: AuthAccessUiState,
)

internal object ProfileSessionScreen {
    @Composable
    operator fun invoke(
        model: ProfileSessionUiModel,
        strings: KwaborStrings,
        actions: ProfileSessionScreenActions,
        modifier: Modifier = Modifier,
    ) {
        Surface(modifier = modifier.fillMaxSize()) {
            ProfileSessionContent(
                email = model.email,
                authAccessState = model.authAccessState,
                strings = strings,
                actions = actions,
            )
        }
        if (model.authAccessState.signOutConfirmationVisible) {
            SignOutConfirmationDialog(
                loading = model.authAccessState.signOutInProgress,
                strings = strings,
                actions = actions,
            )
        }
        if (model.authAccessState.accountDeletionDialogVisible) {
            AccountDeletionDialog(
                model = AccountDeletionDialogUiModel(
                    authenticationMethod = model.authenticationMethod,
                    state = model.authAccessState,
                    strings = strings,
                ),
                actions = actions,
            )
        }
    }
}

@Composable
private fun ProfileSessionContent(
    email: String,
    authAccessState: AuthAccessUiState,
    strings: KwaborStrings,
    actions: ProfileSessionScreenActions,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(KwaborSpacing.Xxl),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Top,
    ) {
        AccountSummary(email = email, authAccessState = authAccessState, strings = strings)
        Spacer(Modifier.height(KwaborSpacing.Xxl))
        DangerZone(authAccessState = authAccessState, strings = strings, actions = actions)
    }
}

@Composable
private fun AccountSummary(email: String, authAccessState: AuthAccessUiState, strings: KwaborStrings) {
    Text(
        text = strings.authAccount,
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.headlineSmall,
    )
    Spacer(Modifier.height(KwaborSpacing.Sm))
    Text(
        text = stringResource(R.string.profile_connected_as, email),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.secondary,
    )
    authAccessState.signOutErrorMessage?.let { error ->
        Spacer(Modifier.height(KwaborSpacing.Lg))
        Text(
            text = error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun DangerZone(
    authAccessState: AuthAccessUiState,
    strings: KwaborStrings,
    actions: ProfileSessionScreenActions,
) {
    Text(
        text = strings.dangerZoneTitle,
        modifier = Modifier.semantics { heading() },
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(Modifier.height(KwaborSpacing.Lg))
    DangerZoneButton(
        title = strings.authSignOut,
        enabled = !authAccessState.signOutInProgress,
        onClick = actions.onRequestSignOut,
    )
    Spacer(Modifier.height(KwaborSpacing.Xxl))
    Text(
        text = strings.authDeleteAccountWarning,
        color = MaterialTheme.colorScheme.secondary,
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(KwaborSpacing.Lg))
    DangerZoneButton(
        title = stringResource(R.string.profile_delete_account),
        enabled = !authAccessState.signOutInProgress,
        onClick = actions.onRequestAccountDeletion,
    )
}

@Composable
private fun DangerZoneButton(title: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = KwaborSizing.MinimumAccessibleTouchTarget),
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) {
        Text(title)
    }
}

private data class AccountDeletionDialogUiModel(
    val authenticationMethod: AuthenticationMethod,
    val state: AuthAccessUiState,
    val strings: KwaborStrings,
)

private data class AccountDeletionForm(
    val password: String,
    val confirmation: String,
    val confirmationPhrase: String,
    val confirmationValid: Boolean,
)

private data class AccountDeletionFormActions(
    val onPasswordChanged: (String) -> Unit,
    val onConfirmationChanged: (String) -> Unit,
)

@Composable
private fun AccountDeletionDialog(model: AccountDeletionDialogUiModel, actions: ProfileSessionScreenActions) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val confirmationPhrase = model.strings.authDeleteAccountConfirmationPhrase
    val form = AccountDeletionForm(
        password = password,
        confirmation = confirmation,
        confirmationPhrase = confirmationPhrase,
        confirmationValid = isAccountDeletionConfirmationValid(
            value = confirmation,
            expected = confirmationPhrase,
        ),
    )
    AccountDeletionAlert(
        model = model,
        form = form,
        formActions = AccountDeletionFormActions(
            onPasswordChanged = { password = it },
            onConfirmationChanged = { confirmation = it },
        ),
        actions = actions,
    )
}

@Composable
private fun AccountDeletionAlert(
    model: AccountDeletionDialogUiModel,
    form: AccountDeletionForm,
    formActions: AccountDeletionFormActions,
    actions: ProfileSessionScreenActions,
) {
    AlertDialog(
        onDismissRequest = {
            if (!model.state.accountDeletionInProgress) actions.onCancelAccountDeletion()
        },
        title = { Text(stringResource(R.string.profile_delete_account_title)) },
        text = {
            AccountDeletionDialogContent(
                model = model,
                form = form,
                formActions = formActions,
                actions = actions,
            )
        },
        confirmButton = {
            AccountDeletionConfirmButton(
                model = model,
                form = form,
                onConfirm = actions.onDeleteAccountWithPassword,
            )
        },
        dismissButton = {
            TextButton(
                onClick = actions.onCancelAccountDeletion,
                enabled = !model.state.accountDeletionInProgress,
            ) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun AccountDeletionDialogContent(
    model: AccountDeletionDialogUiModel,
    form: AccountDeletionForm,
    formActions: AccountDeletionFormActions,
    actions: ProfileSessionScreenActions,
) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.profile_delete_account_support))
        Spacer(Modifier.height(KwaborSpacing.Lg))
        AuthInlineMessage(model.state.accountDeletionErrorMessage, isError = true)
        if (model.authenticationMethod != AuthenticationMethod.Apple) {
            AccountDeletionConfirmationField(
                form = form,
                enabled = !model.state.accountDeletionInProgress,
                onValueChanged = formActions.onConfirmationChanged,
            )
        }
        AccountDeletionCredentialField(
            model = model,
            form = form,
            onPasswordChanged = formActions.onPasswordChanged,
            onGoogleDeletion = actions.onDeleteAccountWithGoogle,
        )
    }
}

@Composable
private fun AccountDeletionConfirmationField(
    form: AccountDeletionForm,
    enabled: Boolean,
    onValueChanged: (String) -> Unit,
) {
    Text(
        stringResource(
            R.string.profile_delete_account_confirmation_prompt,
            form.confirmationPhrase,
        ),
    )
    Spacer(Modifier.height(KwaborSpacing.Md))
    OutlinedTextField(
        value = form.confirmation,
        onValueChange = onValueChanged,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        label = { Text(form.confirmationPhrase) },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
    )
    Spacer(Modifier.height(KwaborSpacing.Lg))
}

@Composable
private fun AccountDeletionCredentialField(
    model: AccountDeletionDialogUiModel,
    form: AccountDeletionForm,
    onPasswordChanged: (String) -> Unit,
    onGoogleDeletion: (String) -> Unit,
) {
    when (model.authenticationMethod) {
        AuthenticationMethod.Email -> AccountDeletionPasswordField(
            form = form,
            enabled = !model.state.accountDeletionInProgress,
            strings = model.strings,
            onPasswordChanged = onPasswordChanged,
        )
        AuthenticationMethod.Google -> AccountDeletionGoogleButton(
            form = form,
            loading = model.state.accountDeletionInProgress,
            onGoogleDeletion = onGoogleDeletion,
        )
        AuthenticationMethod.Apple -> {
            Text(stringResource(R.string.profile_delete_account_apple_device))
        }
    }
}

@Composable
private fun AccountDeletionPasswordField(
    form: AccountDeletionForm,
    enabled: Boolean,
    strings: KwaborStrings,
    onPasswordChanged: (String) -> Unit,
) {
    Text(stringResource(R.string.profile_delete_account_password_support))
    Spacer(Modifier.height(KwaborSpacing.Md))
    AuthPasswordField(
        value = form.password,
        onValueChange = onPasswordChanged,
        label = strings.registrationPassword,
        options = AuthPasswordFieldOptions(enabled = enabled),
    )
}

@Composable
private fun AccountDeletionGoogleButton(
    form: AccountDeletionForm,
    loading: Boolean,
    onGoogleDeletion: (String) -> Unit,
) {
    Text(stringResource(R.string.profile_delete_account_google_support))
    Spacer(Modifier.height(KwaborSpacing.Md))
    GoogleSignInButton(
        loading = loading,
        enabled = form.confirmationValid && !loading,
        onClick = { onGoogleDeletion(form.confirmation) },
    )
}

@Composable
private fun AccountDeletionConfirmButton(
    model: AccountDeletionDialogUiModel,
    form: AccountDeletionForm,
    onConfirm: (String, String) -> Unit,
) {
    if (model.authenticationMethod != AuthenticationMethod.Email) return
    TextButton(
        onClick = { onConfirm(form.password, form.confirmation) },
        enabled = form.confirmationValid &&
            form.password.isNotBlank() &&
            !model.state.accountDeletionInProgress,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) {
        if (model.state.accountDeletionInProgress) {
            CircularProgressIndicator(
                modifier = Modifier.padding(end = KwaborSpacing.Sm).size(KwaborSpacing.Xl),
                strokeWidth = KwaborSizing.Hairline,
            )
        }
        Text(stringResource(R.string.profile_delete_account_confirm))
    }
}

@Composable
private fun SignOutConfirmationDialog(loading: Boolean, strings: KwaborStrings, actions: ProfileSessionScreenActions) {
    AlertDialog(
        onDismissRequest = { if (!loading) actions.onCancelSignOut() },
        title = { Text(strings.authSignOutTitle) },
        text = { Text(strings.authSignOutConfirmation) },
        confirmButton = {
            TextButton(
                onClick = actions.onConfirmSignOut,
                enabled = !loading,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = KwaborSpacing.Sm).size(KwaborSpacing.Xl),
                        strokeWidth = KwaborSizing.Hairline,
                    )
                }
                Text(strings.authConfirm)
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onCancelSignOut, enabled = !loading) {
                Text(strings.authCancel)
            }
        },
    )
}
