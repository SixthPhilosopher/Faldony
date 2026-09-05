@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package org.kiss.ui.theme

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.MediaQueryListEvent
import org.w3c.dom.events.Event

/**
 * wasmJs actuals for the theme shell: observe the OS color-scheme media query
 * and paint the <body> so the page outside the Compose canvas matches the
 * active Material theme (the previous white page in dark mode came from an
 * unstyled shell).
 */
actual fun observeSystemDarkTheme(onChange: (Boolean) -> Unit): () -> Unit {
    val mql = window.matchMedia("(prefers-color-scheme: dark)")
    val listener: (Event) -> Unit = { e -> onChange((e as MediaQueryListEvent).matches) }
    mql.addEventListener("change", listener)
    return { mql.removeEventListener("change", listener) }
}

actual fun syncBodyTheme(isDark: Boolean) {
    val bg = if (isDark) "#1c1b1f" else "#fdf8fd"
    val fg = if (isDark) "#e7e0eb" else "#1e1a20"
    document.body?.style?.backgroundColor = bg
    document.body?.style?.color = fg
    (document.documentElement as? HTMLElement)?.style?.backgroundColor = bg
}