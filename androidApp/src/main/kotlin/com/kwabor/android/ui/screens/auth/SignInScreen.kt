package com.kwabor.android.ui.screens.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.kwabor.android.R
import com.kwabor.android.design.KwaborRadius
import com.kwabor.android.design.KwaborSizing
import com.kwabor.android.design.KwaborSpacing
import com.kwabor.android.presentation.auth.AuthAccessUiState
import com.kwabor.android.presentation.auth.SignInStep
import com.kwabor.android.presentation.auth.looksLikeEmail
import com.kwabor.shared.i18n.KwaborStrings

internal data class SignInScreenActions(
    val onBack: () -> Unit,
    val onEmailChange: (String) -> Unit,
    val onContinueFromEmail: () -> Unit,
    val onSubmitPassword: (String) -> Unit,
    val onForgotPassword: () -> Unit,
    val onSignUp: () -> Unit,
)

internal object SignInScreen {
    @Composable
    operator fun invoke(state: AuthAccessUiState, strings: KwaborStrings, actions: SignInScreenActions) {
        BackHandler(onBack = actions.onBack)
        AuthScreenFrame(onBack = actions.onBack) {
            AuthInlineMessage(state.errorMessage ?: state.noticeMessage, state.errorMessage != null)
            when (state.signInStep) {
                SignInStep.Email -> SignInEmailStep(state, strings, actions)
                SignInStep.Password -> key(state.signInEmail) {
                    SignInPasswordStep(state, actions)
                }
            }
            SignUpPrompt(actions.onSignUp)
        }
    }
}

@Composable
internal fun AuthScreenFrame(onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().imePadding()) {
            AuthLanguageChip(modifier = Modifier.align(Alignment.TopEnd).padding(KwaborSpacing.Lg))
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = KwaborSpacing.Xxl, vertical = KwaborSpacing.Xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                AuthBackButton(onBack)
                Image(
                    painter = painterResource(R.drawable.kwabor_brand_mark),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(AUTH_MARK_SIZE),
                )
                Spacer(Modifier.height(KwaborSpacing.Xl))
                content()
            }
        }
    }
}

@Composable
private fun SignInEmailStep(state: AuthAccessUiState, strings: KwaborStrings, actions: SignInScreenActions) {
    AuthHeading(
        title = stringResource(R.string.auth_sign_in_email_title),
        supportingText = stringResource(R.string.auth_sign_in_email_support),
    )
    Spacer(Modifier.height(KwaborSpacing.Xl))
    OutlinedTextField(
        value = state.signInEmail,
        onValueChange = actions.onEmailChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.isLoading,
        singleLine = true,
        label = { Text(strings.authEmail) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(
            onDone = { if (state.signInEmail.looksLikeEmail()) actions.onContinueFromEmail() },
        ),
    )
    Spacer(Modifier.height(KwaborSpacing.Lg))
    AuthPrimaryButton(
        label = stringResource(R.string.auth_sign_in_continue),
        loading = state.isLoading,
        enabled = state.signInEmail.looksLikeEmail(),
        onClick = actions.onContinueFromEmail,
    )
}

@Composable
private fun ColumnScope.SignInPasswordStep(state: AuthAccessUiState, actions: SignInScreenActions) {
    var password by remember { mutableStateOf("") }
    AuthHeading(
        title = stringResource(R.string.auth_sign_in_password_title),
        supportingText = stringResource(R.string.auth_sign_in_password_support, state.signInEmail),
    )
    Spacer(Modifier.height(KwaborSpacing.Xl))
    AuthPasswordField(
        value = password,
        onValueChange = { updated -> password = updated },
        label = stringResource(R.string.auth_password_label),
        enabled = !state.isLoading,
        onDone = { if (password.isNotEmpty()) actions.onSubmitPassword(password) },
    )
    TextButton(
        onClick = actions.onForgotPassword,
        modifier = Modifier.align(Alignment.End),
        enabled = !state.isLoading,
    ) {
        Text(stringResource(R.string.auth_forgot_password))
    }
    AuthPrimaryButton(
        label = stringResource(R.string.auth_sign_in_submit),
        loading = state.isLoading,
        enabled = password.isNotEmpty(),
        onClick = { actions.onSubmitPassword(password) },
    )
}

@Composable
internal fun AuthPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    onDone: (() -> Unit)? = null,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        label = { Text(label) },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = if (onDone == null) ImeAction.Next else ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = stringResource(
                        if (visible) R.string.auth_password_hide else R.string.auth_password_show,
                    ),
                )
            }
        },
    )
}

@Composable
internal fun AuthPrimaryButton(label: String, loading: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled && !loading,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(KwaborSpacing.Xl),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = KwaborSizing.Hairline,
            )
            Spacer(Modifier.width(KwaborSpacing.Sm))
        }
        Text(label)
    }
}

@Composable
internal fun AuthHeading(title: String, supportingText: String) {
    Text(text = title, style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(KwaborSpacing.Sm))
    Text(
        text = supportingText,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.secondary,
    )
}

@Composable
internal fun AuthInlineMessage(message: String?, isError: Boolean) {
    if (message == null) return
    Text(
        text = message,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = KwaborSpacing.Lg)
            .semantics { liveRegion = LiveRegionMode.Polite },
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun SignUpPrompt(onSignUp: () -> Unit) {
    Row(
        modifier = Modifier.padding(top = KwaborSpacing.Lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.auth_no_account), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onSignUp) {
            Text(stringResource(R.string.auth_create_account))
        }
    }
}

@Composable
internal fun AuthLanguageChip(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.auth_language_chip),
        modifier = modifier
            .border(
                width = KwaborSizing.Hairline,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(KwaborRadius.Pill),
            )
            .padding(horizontal = KwaborSpacing.Md, vertical = KwaborSpacing.Sm),
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
internal fun AuthBackButton(onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth()) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.CenterStart).size(KwaborSizing.TouchTarget),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.registration_back_accessibility),
            )
        }
    }
}

private val AUTH_MARK_SIZE = KwaborSizing.BrandMark
