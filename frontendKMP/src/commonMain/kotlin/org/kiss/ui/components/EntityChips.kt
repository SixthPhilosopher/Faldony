package org.kiss.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import org.kiss.frontendkmp.generated.resources.Res
import org.kiss.frontendkmp.generated.resources.remove_entity
import org.kiss.ui.theme.LocalThemeState

/**
 * Deterministic entity hue. JS-portable dirty recipe (FNV-ish over 32-bit
 * unsigned arithmetic) so the React and Compose UIs agree on colors.
 */
fun entityHue(name: String): Int {
    fun mix(hash: Int, c: Int) = hash xor (c + 0x9E3779B9.toInt()) + (hash shl 6) + (hash ushr 2)
    var hash = 0
    for (c in name) hash = mix(hash, c.code)
    return (hash.toUInt().toInt() and 0xFFFFFF).mod(360)
}

@Composable
private fun readIsDark(): Boolean = LocalThemeState.current.isDark

@Composable
private fun chipForeground(hue: Int): Color {
    val (lightness, saturation) = if (readIsDark()) 0.82f to 0.85f else 0.30f to 0.65f
    return Color.hsl(hue.toFloat(), saturation, lightness)
}

@Composable
private fun chipBackground(hue: Int): Color {
    val (lightness, alpha) = if (readIsDark()) 0.45f to 0.22f else 0.50f to 0.16f
    return Color.hsl(hue.toFloat(), 0.75f, lightness, alpha)
}

@Composable
private fun chipBorder(hue: Int): Color {
    val (lightness, alpha) = if (readIsDark()) 0.60f to 0.55f else 0.45f to 0.5f
    return Color.hsl(hue.toFloat(), if (readIsDark()) 0.8f else 0.7f, lightness, alpha)
}

@Composable
fun entityChipColors(name: String) = SuggestionChipDefaults.suggestionChipColors(
    containerColor = chipBackground(entityHue(name)),
    labelColor = chipForeground(entityHue(name)),
    iconContentColor = chipForeground(entityHue(name)),
)

@Composable
fun entityChipBorder(name: String) = BorderStroke(1.dp, chipBorder(entityHue(name)))

/** Small read-only chip colored by the entity's hashed hue (table cells, preview). */
@Composable
fun EntityChip(name: String, modifier: Modifier = Modifier) {
    SuggestionChip(
        onClick = {},
        label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier,
        colors = entityChipColors(name),
        border = entityChipBorder(name),
        elevation = null,
        shape = SuggestionChipDefaults.shape,
    )
}

/** Chip with a delete affordance (selected values inside the filter fields). */
@Composable
fun SelectableEntityChip(name: String, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    InputChip(
        selected = false,
        onClick = {},
        label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(Res.string.remove_entity, name),
                modifier = Modifier
                    .size(16.dp)
                    .clickable(onClick = onRemove),
            )
        },
        modifier = modifier,
        colors = InputChipDefaults.inputChipColors(
            containerColor = chipBackground(entityHue(name)),
            labelColor = chipForeground(entityHue(name)),
            trailingIconColor = chipForeground(entityHue(name)),
        ),
        border = entityChipBorder(name),
    )
}

/**
 * Minimal hover tooltip shown above the content while the pointer is inside.
 * Common-compose only; used to surface the document type names.
 */
@Composable
fun HoverTooltip(text: String, content: @Composable () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Enter) hovered = true
                        }
                    }
                }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Exit) hovered = false
                        }
                    }
                },
        ) {
            content()
        }
        if (hovered) {
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-36).dp),
            ) {
                Text(
                    text,
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}