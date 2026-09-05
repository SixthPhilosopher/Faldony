package org.kiss.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.kiss.entity.AutocompleteEntity
import org.kiss.frontendkmp.generated.resources.Res
import org.kiss.frontendkmp.generated.resources.filter_placeholder
import org.kiss.frontendkmp.generated.resources.loading_options
import org.kiss.frontendkmp.generated.resources.no_results

private const val DEBOUNCE_MS = 200L
private const val MAX_VISIBLE_ROWS = 8
private val MAX_DROPDOWN_HEIGHT = (MAX_VISIBLE_ROWS * 40).dp

/** "A" for Apple, "3" for 3M → digits collapse into "0-9". */
private fun groupKey(name: String): String {
    val first = name.firstOrNull()?.uppercase() ?: "#"
    return if (first.isNotEmpty() && first[0].isDigit()) "0-9" else first
}

/**
 * M3 multi-value filter control bound to an async provider:
 * typed query → debounce → suspend fetch; dropdown pops under the field, options
 * are grouped (sticky headers) and virtualized (LazyColumn), checked rows carry
 * a checkbox; selections become deletable colored chips inside the control.
 */
@Composable
fun AutocompleteField(
    loadOptions: suspend (query: String) -> List<AutocompleteEntity>,
    selected: List<AutocompleteEntity>,
    onSelectedChange: (List<AutocompleteEntity>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var options by remember { mutableStateOf<List<AutocompleteEntity>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var anchorHeightPx by remember { mutableIntStateOf(0) }

    val scope = rememberCoroutineScope()
    var fetchJob by remember { mutableStateOf<Job?>(null) }

    fun refresh() {
        fetchJob?.cancel()
        fetchJob = scope.launch {
            loading = true
            delay(DEBOUNCE_MS)
            options = loadOptions(query.trim())
            loading = false
        }
    }

    LaunchedEffect(open) {
        if (open) {
            query = ""
            refresh()
        } else {
            options = emptyList()
        }
    }

    val density = LocalDensity.current
    val defaultOffsetY = with(density) { 64.dp.toPx().toInt() }
    val extraGapPx = with(density) { 8.dp.toPx().toInt() }
    val offsetY = if (anchorHeightPx > 0) anchorHeightPx + extraGapPx else defaultOffsetY

    Box(modifier = modifier.fillMaxWidth().onSizeChanged { anchorHeightPx = it.height }) {
        Column {
            if (selected.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    selected.forEach { entity ->
                        SelectableEntityChip(
                            entity.name,
                            onRemove = {
                                onSelectedChange(selected.filterNot { it.id == entity.id })
                            },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { newValue ->
                    query = newValue
                    refresh()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .onFocusChanged { if (it.isFocused) open = true },
                singleLine = true,
                placeholder = { Text(stringResource(Res.string.filter_placeholder)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                trailingIcon = {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
                    }
                },
            )
        }
    }

    if (open) {
        Popup(onDismissRequest = { open = false }, offset = IntOffset(0, offsetY)) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                shadowElevation = 8.dp,
                modifier = Modifier.width(240.dp),
            ) {
                val grouped = options.groupBy { groupKey(it.name) }.entries.sortedBy { it.key }
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = MAX_DROPDOWN_HEIGHT)) {
                    grouped.forEach { (group, members) ->
                        stickyHeader(key = "group-$group") {
                            GroupHeader(group)
                        }
                        members.forEach { entity ->
                            item(key = "entity-${entity.id}") {
                                OptionRow(
                                    entity = entity,
                                    isSelected = selected.any { it.id == entity.id },
                                    onToggle = {
                                        onSelectedChange(
                                            if (selected.any { it.id == entity.id }) {
                                                selected.filterNot { s -> s.id == entity.id }
                                            } else {
                                                selected + entity
                                            },
                                        )
                                    },
                                )
                            }
                        }
                    }
                    if (loading && options.isEmpty()) {
                        item(key = "loading") {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text(
                                    stringResource(Res.string.loading_options),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    } else if (!loading && options.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                stringResource(Res.string.no_results),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(group: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .height(32.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            group.replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

@Composable
private fun OptionRow(
    entity: AutocompleteEntity,
    isSelected: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = null,
            modifier = Modifier.size(18.dp),
        )
        Text(
            entity.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}