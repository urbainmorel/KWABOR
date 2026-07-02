package com.kwabor.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.kwabor.shared.app.KwaborApp
import kotlinx.browser.document

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(viewportContainer = document.body!!) {
        KwaborApp()
    }
}
