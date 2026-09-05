@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package org.kiss.utils

/**
 * Console logging for Kotlin/Wasm: `js(code)` may only appear as the single
 * expression of a top-level function body (or a property initializer), so
 * the console escapes live here as two one-line helpers and everything else
 * calls them.
 */
internal fun jsLog(message: String): Unit = js("console.log(message)")

internal fun jsError(message: String): Unit = js("console.error(message)")