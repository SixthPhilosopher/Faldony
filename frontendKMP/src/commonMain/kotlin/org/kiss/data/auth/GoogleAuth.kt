package org.kiss.data.auth

/**
 * Google Identity Services interop.
 *
 * The Google OAuth client id is baked into the build as [AuthConfig.GOOGLE_CLIENT_ID]
 * (generated from the GOOGLE_CLIENT_ID env var). It is a PUBLIC OAuth identifier,
 * safe to ship in the bundle. A blank id disables login.
 */
expect object GoogleAuth {

    /**
     * Loaded the GIS script and initialized the client.
     * `onToken` receives the raw Google id_token credential.
     * Returns false when no client id is configured (login unavailable).
     */
    fun init(onToken: (String) -> Unit): Boolean

    /**
     * Renders the "Sign in with Google" button into the DOM (the visual
     * widget Google provides — it cannot be drawn by Compose).
     */
    fun renderButton(onToken: (String) -> Unit)

    /** Removes the rendered button from the DOM. */
    fun disposeButton()

    /**
     * Positions the button host over the reserved Compose slot (window Y in
     * px). The GIS widget is a raw DOM element, so the login screen tells it
     * where to sit after layout — matching the slot means it never overlaps
     * the logo.
     */
    fun positionButton(topPx: Int)
}