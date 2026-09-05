package org.kiss.ui.documents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import org.jetbrains.compose.resources.stringResource
import org.kiss.data.DocumentDto
import org.kiss.data.PersonDto
import org.kiss.data.TagDto
import org.kiss.frontendkmp.generated.resources.Res
import org.kiss.frontendkmp.generated.resources.action_open
import org.kiss.frontendkmp.generated.resources.document_indexed
import org.kiss.frontendkmp.generated.resources.documents_subtitle
import org.kiss.frontendkmp.generated.resources.regenerate_data
import org.kiss.frontendkmp.generated.resources.search_placeholder
import org.kiss.frontendkmp.generated.resources.stream_closed_status
import org.kiss.frontendkmp.generated.resources.stream_error_status
import org.kiss.frontendkmp.generated.resources.stream_live_status
import org.kiss.frontendkmp.generated.resources.stream_truncated_status
import org.kiss.frontendkmp.generated.resources.switch_to_dark
import org.kiss.frontendkmp.generated.resources.switch_to_light
import org.kiss.frontendkmp.generated.resources.tab_documents
import org.kiss.frontendkmp.generated.resources.tab_processing
import org.kiss.frontendkmp.generated.resources.upload_button
import org.kiss.ui.theme.LocalThemeState
import org.kiss.ui.theme.Spacing

private enum class MainTab { DOCUMENTS, PROCESSING }

@Composable
fun DocumentsScreen() {
    val procVm: ProcessViewModel = viewModel { ProcessViewModel() }
    val viewModel: DocumentsViewModel = viewModel {
        DocumentsViewModel(onProcessListChanged = procVm::refresh)
    }
    // Bidirectional wiring: the processing screen notifies the documents
    // screen when an upload lands / when the user wants to open a result.
    procVm.onDocumentsChanged = viewModel::refresh
    procVm.onOpenDocument = viewModel::openDocument

    val search by viewModel.search.collectAsStateWithLifecycle()
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val upload by viewModel.upload.collectAsStateWithLifecycle()
    val stagedFile by viewModel.stagedFileFlow.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedId.collectAsStateWithLifecycle()
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val detailLoading by viewModel.detailLoading.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val streamStatus by viewModel.streamStatus.collectAsStateWithLifecycle()
    val theme = LocalThemeState.current

    var tab by remember { mutableStateOf(MainTab.DOCUMENTS) }
    var uploadDialogOpen by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val openLabel = stringResource(Res.string.action_open)
    val indexedMessage = stringResource(Res.string.document_indexed)

    // FileKit launcher: opens the native picker on a real user click and
    // hands the chosen file to the ViewModel (staging stays in coroutines).
    val filePickerLauncher = rememberFilePickerLauncher(
        type = FileKitType.File(
            listOf("pdf", "doc", "docx", "txt", "png", "jpg", "jpeg", "tiff", "bmp", "webp", "gif"),
        ),
        onError = { viewModel.setUploadError(it.message) },
        onResult = { file ->
            if (file == null) return@rememberFilePickerLauncher // cancelled: keep dialog open
            viewModel.setStagedFileFromPlatform(file)
        },
    )

    // Upload transitions: processing → switch to Processing tab; done → snackbar
    // with an "Open" action; failed → error snackbar. The dialog itself is
    // closed on submit (submitUpload clears the staged file), so `Processing`
    // here must NOT re-open anything.
    LaunchedEffect(upload) {
        when (val state = upload) {
            is UploadState.Processing -> tab = MainTab.PROCESSING
            is UploadState.Done -> {
                val result = snackbarHostState.showSnackbar(
                    message = indexedMessage,
                    actionLabel = openLabel,
                    duration = SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) state.documentId?.let(viewModel::openDocument)
                viewModel.dismissUpload()
            }
            is UploadState.Failed -> {
                snackbarHostState.showSnackbar(state.message, duration = SnackbarDuration.Long)
                viewModel.dismissUpload()
            }
            else -> Unit
        }
    }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Long)
            viewModel.clearError()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.screenPadding, vertical = Spacing.sectionGap),
            verticalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.iconTextGap),
                    ) {
                        Text(
                        "Faldony",
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    }
                    Text(
                        stringResource(
                            Res.string.documents_subtitle,
                            rows.size,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.toolbarIconGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = theme.toggle) {
                        Icon(
                            if (theme.isDark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                            contentDescription = stringResource(
                                if (theme.isDark) Res.string.switch_to_light else Res.string.switch_to_dark,
                            ),
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    IconButton(onClick = {
                        viewModel.refresh()
                        procVm.refresh()
                    }) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = stringResource(Res.string.regenerate_data),
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    TextButton(onClick = { uploadDialogOpen = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Text(stringResource(Res.string.upload_button))
                    }
                }
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = tab == MainTab.DOCUMENTS,
                    onClick = { tab = MainTab.DOCUMENTS },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text(stringResource(Res.string.tab_documents)) }
                SegmentedButton(
                    selected = tab == MainTab.PROCESSING,
                    onClick = { tab = MainTab.PROCESSING },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text(stringResource(Res.string.tab_processing)) }
            }

            if (tab == MainTab.DOCUMENTS) {
                OutlinedTextField(
                    value = search,
                    onValueChange = viewModel::setSearch,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(Res.string.search_placeholder)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                )
                StreamStatusLine(status = streamStatus)
                Surface(
                    shape = MaterialTheme.shapes.large,
                    tonalElevation = 0.dp,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    DocumentsTable(viewModel)
                }
            } else {
                ProcessingScreen(procVm)
            }
        }

        UploadDialog(
            visible = uploadDialogOpen,
            stagedFile = stagedFile,
            onRequestFilePick = filePickerLauncher::launch,
            onUpload = viewModel::submitUpload,
            onQuickCreateParty = viewModel::quickCreateParty,
            onQuickCreateTag = viewModel::quickCreateTag,
            onDismiss = { uploadDialogOpen = false; viewModel.dismissUpload() },
            partyLoader = viewModel.referenceData::getParties,
            tagLoader = viewModel.referenceData::getTags,
            collectionLoader = viewModel.referenceData::getCollections,
        )

        DocumentDetailsDrawer(
            selectedId = selectedId,
            doc = detail,
            loading = detailLoading,
            preview = preview,
            onClose = viewModel::closePreview,
            onRefresh = viewModel::refreshDetail,
            onDownload = viewModel::downloadDetail,
            onUpdateTitle = viewModel::updateDetailTitle,
            onToggleParty = { party, add -> viewModel.toggleDetailParty(party, add) },
            onToggleTag = { tag, add -> viewModel.toggleDetailTag(tag, add) },
            onOpenInTab = viewModel::openPreviewInTab,
            partyLoader = { q ->
                val existing = detail?.parties?.map { it.id }.orEmpty()
                viewModel.referenceData.getParties(q).filter { it.id !in existing }
            },
            tagLoader = { q ->
                val existing = detail?.tags?.map { it.id }.orEmpty()
                viewModel.referenceData.getTags(q).filter { it.id !in existing }
            },
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
    }
}

/** Single-line stream health indicator under the search field. */
@Composable
private fun StreamStatusLine(status: StreamStatus) {
    val text = when {
        status.error != null -> stringResource(Res.string.stream_error_status)
        status.truncated -> stringResource(Res.string.stream_truncated_status, status.delivered)
        status.live -> stringResource(Res.string.stream_live_status, status.delivered)
        else -> stringResource(Res.string.stream_closed_status, status.delivered)
    }
    val color = when {
        status.error != null -> MaterialTheme.colorScheme.error
        status.truncated -> MaterialTheme.colorScheme.tertiary
        status.live -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
    )
}
