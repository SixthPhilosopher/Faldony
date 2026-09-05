@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package org.kiss.data.auth

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLScriptElement

/**
 * wasmJs actual for Google Identity Services (GIS).
 *
 * The client id is baked into the build as [AuthConfig.GOOGLE_CLIENT_ID]
 * (from the GOOGLE_CLIENT_ID env var — deploy/.env in Docker). It is a public
 * OAuth identifier, safe to ship to the browser. A blank id disables login.
 *
 * GIS is driven through the typed DOM + a few minimal JS-expression helpers
 * (the wrapper libraries lag the GIS API; the credential payload is an opaque
 * object we only read `.credential` from).
 */

// ------------------------------ GIS externals --------------------------------

private external class JsGsiAccounts { val id: JsGsiId }

private external class JsGsiId {
    fun initialize(options: JsInitOptions)
    fun renderButton(host: HTMLDivElement, options: JsRenderOptions)
}

private external class JsInitOptions {
    var client_id: String
    var callback: (String) -> Unit
    var auto_select: Boolean
}

private external class JsRenderOptions {
    var theme: String
    var size: String
}

// `js(code)` may only be the single expression of a top-level function body
// (or a property initializer) in Kotlin/Wasm — hence these one-liners.
private fun gsiOrNull(): JsGsiAccounts? = js("window.google.accounts")
private fun initOptions(id: String, cb: (String) -> Unit): JsInitOptions =
    js("({ client_id: id, callback: (resp) => cb(resp.credential), auto_select: false })")
private fun renderOptions(): JsRenderOptions = js("({ theme: 'outline', size: 'large' })")

// -----------------------------------------------------------------------------

actual object GoogleAuth {

    private var clientId: String? = null
    private var initialized = false
    private var onTokenCb: ((String) -> Unit)? = null

    private val gsiClientUrl = "https://accounts.google.com/gsi/client"

    actual fun init(onToken: (String) -> Unit): Boolean {
        onTokenCb = onToken
        val id = AuthConfig.GOOGLE_CLIENT_ID
        if (id.isBlank()) return false
        clientId = id

        if (document.querySelector("script[data-faldony-gsi]") == null) {
            val script = document.createElement("script") as HTMLScriptElement
            script.src = gsiClientUrl
            script.async = true
            script.setAttribute("data-faldony-gsi", "1")
            document.head?.appendChild(script)
        }

        waitForGsi()
        return true
    }

    actual fun renderButton(onToken: (String) -> Unit) {
        onTokenCb = onToken
        val gsi = gsiOrNull() ?: return
        removeButtonHost()
        val h = document.createElement("div") as HTMLDivElement
        h.id = "faldony-gsi"
        h.style.width = "240px"
        h.style.height = "40px"
        h.style.display = "flex"
        h.style.justifyContent = "center"
        h.style.alignItems = "center"
        h.style.zIndex = "10"
        document.body?.appendChild(h)
        host = h
        applyButtonPosition()
        gsi.id.renderButton(h, renderOptions())
    }

    /**
     * Positions the GIS host over the reserved slot the login screen reserves
     * in the Compose layout (same-origin of the page): fixed, horizontally
     * centered, top = the slot's window Y. Called by the Compose slot after
     * layout so the DOM widget tracks the CMP layout exactly.
     */
    actual fun positionButton(topPx: Int) {
        pendingTopPx = topPx
        applyButtonPosition()
    }

    // ------------------------------------------------------------ internals

    private var host: HTMLDivElement? = null
    private var pendingTopPx: Int? = null

    private fun applyButtonPosition() {
        val h = host ?: return
        val top = pendingTopPx ?: return
        h.style.position = "fixed"
        h.style.top = "${top}px"
        h.style.left = "50%"
        h.style.transform = "translateX(-50%)"
    }

    actual fun disposeButton() {
        removeButtonHost()
    }

    private fun removeButtonHost() {
        host = null
        document.getElementById("faldony-gsi")?.remove()
    }

    /** Pools until `google.accounts` is available, then initializes once. */
    private fun waitForGsi() {
        val gsi = gsiOrNull()
        if (gsi == null) {
            window.setTimeout({ waitForGsi(); null }, 100)
            return
        }
        if (!initialized) {
            gsi.id.initialize(initOptions(clientId.orEmpty()) { credential -> onTokenCb?.invoke(credential) })
            initialized = true
        }
    }
}