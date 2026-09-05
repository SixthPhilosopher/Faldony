package org.kiss

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.kiss.data.auth.AuthHolder
import org.kiss.data.auth.AuthState
import org.kiss.data.settings.createAppSettings
import org.kiss.ui.documents.DocumentsScreen
import org.kiss.ui.login.LoginScreen
import org.kiss.ui.theme.FaldonyTheme

@Composable
fun App() {
    val settings = remember { createAppSettings() }
    AuthHolder.configure(settings)
    val authState by AuthHolder.state.collectAsStateWithLifecycle()

    FaldonyTheme(settings = settings) {
        when (authState) {
            is AuthState.SignedIn -> key((authState as AuthState.SignedIn).token) { DocumentsScreen() }
            else -> LoginScreen()
        }
    }
}