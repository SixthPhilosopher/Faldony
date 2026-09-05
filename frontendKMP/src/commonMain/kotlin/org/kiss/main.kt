package org.kiss

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import org.kiss.utils.jsLog

/** ComposeViewport (browser) entry — see wasmJsMain resources/index.html. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    jsLog("[Faldony] main() starting")
    ComposeViewport {
        jsLog("[Faldony] ComposeViewport mounted")
        App()
    }
}