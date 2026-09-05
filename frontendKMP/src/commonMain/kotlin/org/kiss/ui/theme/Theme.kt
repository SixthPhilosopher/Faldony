package org.kiss.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.russhwolf.settings.Settings

enum class ColorMode { SYSTEM, LIGHT, DARK }

private const val KEY_MODE = "faldony.color-mode"

class ThemeState(
    val mode: ColorMode,
    val isDark: Boolean,
    val toggle: () -> Unit,
)

val LocalThemeState = staticCompositionLocalOf<ThemeState> {
    error("ThemeState is not provided — wrap the app in FaldonyTheme")
}

private val LightColors = lightColorScheme()
private val DarkColors = darkColorScheme()

/** Observes the OS color-scheme preference; returns an unregister function. */
expect fun observeSystemDarkTheme(onChange: (Boolean) -> Unit): () -> Unit

/** Paints the HTML shell (body background) to match the active Compose theme. */
expect fun syncBodyTheme(isDark: Boolean)

@Composable
fun FaldonyTheme(settings: Settings, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    var storedMode by remember { mutableStateOf(settings.getStringOrNull(KEY_MODE)) }
    var systemDarkState by remember { mutableStateOf(systemDark) }

    // SYSTEM mode reacts live to OS changes (the web value is only sampled at
    // composition time otherwise).
    DisposableEffect(Unit) {
        val cancel = observeSystemDarkTheme { systemDarkState = it }
        onDispose { cancel() }
    }

    fun persist(mode: ColorMode) {
        val value = when (mode) {
            ColorMode.SYSTEM -> null
            ColorMode.LIGHT -> "light"
            ColorMode.DARK -> "dark"
        }
        if (value == null) settings.remove(KEY_MODE) else settings.putString(KEY_MODE, value)
        storedMode = value
    }

    val mode = when (storedMode) {
        "light" -> ColorMode.LIGHT
        "dark" -> ColorMode.DARK
        // No stored preference → light by default (not OS/system).
        else -> ColorMode.LIGHT
    }
    val isDark = when (mode) {
        ColorMode.SYSTEM -> systemDarkState
        ColorMode.LIGHT -> false
        ColorMode.DARK -> true
    }

    // Keep the HTML shell in sync with the Compose scheme (body background).
    LaunchedEffect(isDark) { syncBodyTheme(isDark) }

    val themeState = ThemeState(
        mode = mode,
        isDark = isDark,
        toggle = { persist(if (isDark) ColorMode.LIGHT else ColorMode.DARK) },
    )

    CompositionLocalProvider(LocalThemeState provides themeState) {
        MaterialTheme(
            colorScheme = if (isDark) DarkColors else LightColors,
            content = content,
        )
    }
}