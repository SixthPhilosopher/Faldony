package org.kiss.ui.documents

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.skia.Image as SkiaImage
import org.kiss.data.DocumentDto
import org.kiss.data.PersonDto
import org.kiss.data.TagDto
import org.kiss.data.openBlobTab
import org.kiss.data.openDomPreview
import org.kiss.entity.AutocompleteEntity
import org.kiss.frontendkmp.generated.resources.Res
import org.kiss.frontendkmp.generated.resources.action_edit_title
import org.kiss.frontendkmp.generated.resources.action_refresh_details
import org.kiss.frontendkmp.generated.resources.action_save_title
import org.kiss.frontendkmp.generated.resources.close
import org.kiss.frontendkmp.generated.resources.detail_document_id
import org.kiss.frontendkmp.generated.resources.download
import org.kiss.frontendkmp.generated.resources.field_collections
import org.kiss.frontendkmp.generated.resources.field_created_at
import org.kiss.frontendkmp.generated.resources.field_last_update
import org.kiss.frontendkmp.generated.resources.field_pages
import org.kiss.frontendkmp.generated.resources.field_parties
import org.kiss.frontendkmp.generated.resources.field_tags
import org.kiss.frontendkmp.generated.resources.field_title
import org.kiss.frontendkmp.generated.resources.field_type
import org.kiss.frontendkmp.generated.resources.preview_loading
import org.kiss.frontendkmp.generated.resources.preview_open_tab
import org.kiss.frontendkmp.generated.resources.preview_overlay_hint
import org.kiss.frontendkmp.generated.resources.preview_title
import org.kiss.frontendkmp.generated.resources.preview_unavailable
import org.kiss.ui.components.AutocompleteField
import org.kiss.ui.components.EntityChip
import org.kiss.ui.components.SelectableEntityChip
import org.kiss.ui.theme.Spacing
import org.kiss.utils.formatDateTime

private val DRAWER_WIDTH = 560.dp

/**
 * Slide-in-from-the-right modal drawer with the full document details:
 * editable title, parties/tags management, inline preview (image / PDF
 * rendered in a DOM overlay matching the panel width / text) and a real
 * refresh button. Keeps the last document on screen for the exit animation.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DocumentDetailsDrawer(
    selectedId: Long?,
    doc: DocumentDto?,
    loading: Boolean,
    preview: PreviewState,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
    onDownload: () -> Unit,
    onUpdateTitle: (String) -> Unit,
    onToggleParty: (PersonDto, Boolean) -> Unit,
    onToggleTag: (TagDto, Boolean) -> Unit,
    onOpenInTab: () -> Unit,
    partyLoader: suspend (String) -> List<AutocompleteEntity>,
    tagLoader: suspend (String) -> List<AutocompleteEntity>,
) {
    var lastDoc by remember { mutableStateOf(doc) }
    if (doc != null) lastDoc = doc
    val visible = selectedId != null

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInHorizontally { it },
        exit = fadeOut() + slideOutHorizontally { it },
        modifier = Modifier.fillMaxSize(),
    ) {
        val current = lastDoc
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val panelMaxWidth = maxWidth
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(onClick = onClose),
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .then(if (panelMaxWidth < 600.dp) Modifier.fillMaxWidth() else Modifier.width(DRAWER_WIDTH))
                    .fillMaxHeight(),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(Spacing.drawerPadding),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    DrawerHeader(
                        id = selectedId,
                        onRefresh = onRefresh,
                        onDownload = onDownload,
                        onClose = onClose,
                    )
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (current != null) {
                        TitleEditor(title = current.title, onSave = onUpdateTitle)
                        PreviewSection(
                            state = preview,
                            onOpenInTab = onOpenInTab,
                            panelWidth = if (panelMaxWidth < 600.dp) panelMaxWidth else DRAWER_WIDTH,
                            onClose = onClose,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        MetadataSection(
                            doc = current,
                            onToggleParty = onToggleParty,
                            onToggleTag = onToggleTag,
                            partyLoader = partyLoader,
                            tagLoader = tagLoader,
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerHeader(id: Long?, onRefresh: () -> Unit, onDownload: () -> Unit, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            id?.let { stringResource(Res.string.detail_document_id, it) } ?: "",
            style = MaterialTheme.typography.headlineSmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            IconButton(onClick = onRefresh) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = stringResource(Res.string.action_refresh_details),
                )
            }
            IconButton(onClick = onDownload) {
                Icon(
                    Icons.Outlined.Download,
                    contentDescription = stringResource(Res.string.download),
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(Res.string.close))
            }
        }
    }
}

@Composable
private fun TitleEditor(title: String, onSave: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(title) }
    LaunchedEffect(title) { draft = title }

    if (!editing) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            SelectionContainer {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
            }
            DisableSelection {
                IconButton(onClick = { editing = true; draft = title }) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = stringResource(Res.string.action_edit_title),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text(stringResource(Res.string.field_title)) },
            )
            TextButton(onClick = { onSave(draft); editing = false }) {
                Text(stringResource(Res.string.action_save_title))
            }
            IconButton(onClick = { editing = false }) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(Res.string.close))
            }
        }
    }
}

@Composable
private fun PreviewSection(
    state: PreviewState,
    onOpenInTab: () -> Unit,
    panelWidth: Dp,
    onClose: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(Res.string.preview_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (state) {
            is PreviewState.Idle, is PreviewState.Loading -> Box(
                Modifier.fillMaxWidth().height(160.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(
                        stringResource(Res.string.preview_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is PreviewState.Error -> Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }

            is PreviewState.Ready -> when (val kind = state.kind) {
                is PreviewKind.Image -> PreviewImage(kind.bytes)
                is PreviewKind.Pdf -> PreviewMediaHost(
                    bytes = kind.bytes,
                    mimeType = "application/pdf",
                    panelWidth = panelWidth,
                    onClose = onClose,
                    onOpenInTab = onOpenInTab,
                )
                is PreviewKind.Text -> PreviewText(kind.text)
                is PreviewKind.Unsupported -> Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(Res.string.preview_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
    }
}

/**
 * In-canvas image preview: decoded via skiko and drawn directly, so the
 * details metadata below stays visible (no DOM overlay — that was hiding the
 * whole drawer behind the preview panel).
 */
@Composable
private fun PreviewImage(bytes: ByteArray) {
    val bitmap: ImageBitmap? = remember(bytes) {
        runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(300.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(
                stringResource(Res.string.preview_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

/**
 * Renders a PDF through a DOM overlay (the Compose canvas cannot host an
 * <iframe> for PDF blob URLs). The overlay is removed on drawer close; an
 * "open in tab" escape hatch stays visible underneath.
 */
@Composable
private fun PreviewMediaHost(
    bytes: ByteArray,
    mimeType: String,
    panelWidth: Dp,
    onClose: () -> Unit,
    onOpenInTab: () -> Unit,
) {
    val density = LocalDensity.current
    val widthPx = with(density) { panelWidth.toPx().roundToInt() }
    Box(
        Modifier
            .fillMaxWidth()
            .height(300.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(Res.string.preview_overlay_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpenInTab) {
                Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(stringResource(Res.string.preview_open_tab), modifier = Modifier.padding(start = 6.dp))
            }
        }
        DisposableEffect(bytes, widthPx, mimeType) {
            val close = openDomPreview(bytes, mimeType, widthPx, onClose)
            onDispose { close() }
        }
    }
}

@Composable
private fun PreviewText(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(240.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetadataSection(
    doc: DocumentDto,
    onToggleParty: (PersonDto, Boolean) -> Unit,
    onToggleTag: (TagDto, Boolean) -> Unit,
    partyLoader: suspend (String) -> List<AutocompleteEntity>,
    tagLoader: suspend (String) -> List<AutocompleteEntity>,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DrawerField(stringResource(Res.string.field_type), doc.type.name)
        DrawerField(stringResource(Res.string.field_pages), doc.pages.toString())
        DrawerField(stringResource(Res.string.field_created_at), formatDateTime(doc.createdAt))
        DrawerField(stringResource(Res.string.field_last_update), formatDateTime(doc.updatedAt))

        DrawerField(stringResource(Res.string.field_parties)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (doc.parties.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        doc.parties.forEach { party ->
                            SelectableEntityChip(
                                party.name,
                                onRemove = { onToggleParty(party, false) },
                            )
                        }
                    }
                }
                AutocompleteField(
                    loadOptions = partyLoader,
                    selected = emptyList(),
                    onSelectedChange = { list ->
                        list.firstOrNull()?.let { selected ->
                            onToggleParty(PersonDto(selected.id, selected.name), true)
                        }
                    },
                )
            }
        }

        DrawerField(stringResource(Res.string.field_tags)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (doc.tags.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        doc.tags.forEach { tag ->
                            SelectableEntityChip(
                                tag.name,
                                onRemove = { onToggleTag(tag, false) },
                            )
                        }
                    }
                }
                AutocompleteField(
                    loadOptions = tagLoader,
                    selected = emptyList(),
                    onSelectedChange = { list ->
                        list.firstOrNull()?.let { selected ->
                            onToggleTag(TagDto(selected.id, selected.name), true)
                        }
                    },
                )
            }
        }

        if (doc.collections.isNotEmpty()) {
            DrawerField(stringResource(Res.string.field_collections)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    doc.collections.forEach { collection -> EntityChip(collection.name) }
                }
            }
        }
    }
}

@Composable
private fun DrawerField(label: String, value: String) {
    DrawerField(label) { Text(value, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun DrawerField(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}