package com.kwabor.android.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kwabor.android.design.KwaborTheme
import com.kwabor.android.ui.components.KwaborStateMessage
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.stringsFor

@Composable
internal fun KwaborUnavailableApp() {
    val strings = stringsFor(AppLocale.French)
    KwaborTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            KwaborStateMessage(
                title = strings.errorStateTitle,
                supportingText = strings.configurationUnavailable,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}
