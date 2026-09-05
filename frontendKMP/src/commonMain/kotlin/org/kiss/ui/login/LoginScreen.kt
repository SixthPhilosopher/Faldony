package org.kiss.ui.login

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.kiss.data.auth.AuthHolder
import org.kiss.data.auth.GoogleAuth
import org.kiss.frontendkmp.generated.resources.Res
import org.kiss.frontendkmp.generated.resources.faldony
import org.kiss.frontendkmp.generated.resources.login_available_offline
import org.kiss.frontendkmp.generated.resources.login_subtitle
import org.kiss.frontendkmp.generated.resources.login_title

/**
 * Shown while no Google id_token is available. The "Sign in with Google"
 * button is the GIS widget (Google's own DOM button — it cannot be drawn by
 * Compose), mounted by [GoogleAuth.renderButton] on top of this surface.
 *
 * The DOM host is disposed when the screen leaves, otherwise the fixed
 * centered button would keep overlaying every subsequent page.
 */
@Composable
fun LoginScreen() {
    var authUnavailable by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        authUnavailable = !GoogleAuth.init { token -> AuthHolder.signedIn(token) }
        if (!authUnavailable) GoogleAuth.renderButton { token -> AuthHolder.signedIn(token) }
    }

    DisposableEffect(Unit) {
        onDispose { GoogleAuth.disposeButton() }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painterResource(Res.drawable.faldony),
                contentDescription = null,
                modifier = Modifier
                    .size(300.dp)
                    .clip(RoundedCornerShape(60.dp)),
            )
            Spacer(Modifier.height(24.dp))
            Text(stringResource(Res.string.login_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(Res.string.login_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Reserved slot for the Google Identity Services button, a DOM widget that
            // cannot be drawn by Compose. Its host tracks the slot's window
            // position so it lands exactly here, never overlapping the logo.
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .onGloballyPositioned { coords ->
                        GoogleAuth.positionButton(coords.positionInWindow().y.roundToInt())
                    }
            )
            if (authUnavailable) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(Res.string.login_available_offline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}