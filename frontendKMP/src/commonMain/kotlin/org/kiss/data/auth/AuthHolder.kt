package org.kiss.data.auth

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the user has a valid Google id_token to talk to the backend.
 *
 * - [Unknown] → starting state before [configure] has restored the token.
 * - [SignedOut] → the UI shows the login screen (Google Identity Services).
 * - [SignedIn] → the UI shows the document library; every API call (plain
 *   HTTP and SSE alike) attaches the token as a Bearer credential.
 *
 * The token is persisted in the platform settings (localStorage on web), so
 * a page reload stays signed in. The recursive transition back to [SignedOut]
 * is driven by the API layer: any 401 response flips the state, so a single
 * expired/revoked token logs the user out consistently.
 */
sealed interface AuthState {
    data object Unknown : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val token: String) : AuthState
}

object AuthHolder {

    private const val KEY_TOKEN = "faldony.auth.token"

    private var settings: Settings? = null

    private val _state = MutableStateFlow<AuthState>(AuthState.Unknown)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    /** Token attached to the next requests, or null when signed out. */
    val currentToken: String?
        get() = (_state.value as? AuthState.SignedIn)?.token

    /** Binds the settings store and restores a previously persisted token. */
    fun configure(store: Settings) {
        if (settings != null) return
        settings = store
        val saved = store.getStringOrNull(KEY_TOKEN)
        if (saved != null && saved.isNotBlank()) {
            _state.value = AuthState.SignedIn(saved)
        } else {
            _state.value = AuthState.SignedOut
        }
    }

    fun signedIn(token: String) {
        settings?.putString(KEY_TOKEN, token)
        _state.value = AuthState.SignedIn(token)
    }

    fun signedOut() {
        settings?.remove(KEY_TOKEN)
        _state.value = AuthState.SignedOut
    }

    /** Called by the API layer whenever any call answers 401. */
    fun onUnauthorized() {
        if (_state.value is AuthState.SignedIn) {
            signedOut()
        }
    }
}