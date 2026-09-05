package org.kiss.ui.documents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import org.kiss.entity.ProcessHistoryRow
import org.kiss.entity.ProcessQueueRow
import org.kiss.frontendkmp.generated.resources.Res
import org.kiss.frontendkmp.generated.resources.action_cancel
import org.kiss.frontendkmp.generated.resources.action_open
import org.kiss.frontendkmp.generated.resources.action_retry
import org.kiss.frontendkmp.generated.resources.close
import org.kiss.frontendkmp.generated.resources.history_empty
import org.kiss.frontendkmp.generated.resources.load_more
import org.kiss.frontendkmp.generated.resources.processing_history_title
import org.kiss.frontendkmp.generated.resources.processing_queue_title
import org.kiss.frontendkmp.generated.resources.queue_empty
import org.kiss.frontendkmp.generated.resources.regenerate_data
import org.kiss.frontendkmp.generated.resources.stage_queued
import org.kiss.utils.formatDateTime
import org.kiss.utils.formatElapsed

private val TERMINAL_STAGES = setOf("COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT")

/**
 * Processing screen: a live view of the Temporal queue (each job watched over
 * SSE) plus the terminal history ledger with retry / cancel / open actions.
 */
@Composable
fun ProcessingScreen(viewModel: ProcessViewModel) {
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val live by viewModel.live.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val historyExhausted by viewModel.historyExhausted.collectAsStateWithLifecycle()

    var showHistory by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !showHistory,
                onClick = { showHistory = false },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text(stringResource(Res.string.processing_queue_title)) }
            SegmentedButton(
                selected = showHistory,
                onClick = { showHistory = true },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text(stringResource(Res.string.processing_history_title)) }
        }

        if (error != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(
                        error ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::clearError) { Text(stringResource(Res.string.close)) }
                }
            }
        }

        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            if (showHistory) {
                HistoryPane(
                    rows = history,
                    exhausted = historyExhausted,
                    loading = loading,
                    onLoadMore = viewModel::loadMoreHistory,
                    onRetry = viewModel::retryUpload,
                    onOpen = viewModel::openDocument,
                )
            } else {
                ActivePane(
                    rows = queue,
                    live = live,
                    loading = loading,
                    onCancel = viewModel::cancelUpload,
                    onRefresh = viewModel::refresh,
                )
            }
        }
    }
}

@Composable
private fun ActivePane(
    rows: List<ProcessQueueRow>,
    live: Map<String, org.kiss.data.ProcessTaskEvent>,
    loading: Boolean,
    onCancel: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        if (rows.isEmpty()) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (loading) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(Res.string.queue_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRefresh) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(stringResource(Res.string.regenerate_data), modifier = Modifier.padding(start = 6.dp))
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.uploadId }) { row ->
                    ActiveRow(row, live[row.uploadId], onCancel)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun ActiveRow(
    row: ProcessQueueRow,
    task: org.kiss.data.ProcessTaskEvent?,
    onCancel: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                row.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatElapsed(row.startedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            task?.message?.takeIf { it.isNotBlank() }?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        ProcessingStageChip(task?.stage ?: stringResource(Res.string.stage_queued))
        TextButton(onClick = { onCancel(row.uploadId) }) {
            Icon(Icons.Outlined.Cancel, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(stringResource(Res.string.action_cancel), modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
private fun HistoryPane(
    rows: List<ProcessHistoryRow>,
    exhausted: Boolean,
    loading: Boolean,
    onLoadMore: () -> Unit,
    onRetry: (String) -> Unit,
    onOpen: (Long) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        if (rows.isEmpty()) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (loading) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(Res.string.history_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(rows, key = { i, r -> "${r.uploadId}-${r.createdAt}" }) { _, row ->
                    HistoryRow(row, onRetry, onOpen)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (!exhausted) {
                    item(key = "load-more") {
                        Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                            TextButton(onClick = onLoadMore) { Text(stringResource(Res.string.load_more)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    row: ProcessHistoryRow,
    onRetry: (String) -> Unit,
    onOpen: (Long) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                formatDateTime(row.updatedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            row.error?.takeIf { it.isNotBlank() }?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ProcessingStageChip(row.stage)
            if (row.errorCode != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        row.errorCode,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            if (row.documentId != null) {
                TextButton(onClick = { onOpen(row.documentId) }) {
                    Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(stringResource(Res.string.action_open), modifier = Modifier.padding(start = 4.dp))
                }
            } else if (row.stage != "COMPLETED" && row.stage in TERMINAL_STAGES) {
                TextButton(onClick = { onRetry(row.uploadId) }) {
                    Icon(Icons.Outlined.Replay, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(stringResource(Res.string.action_retry), modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}

@Composable
fun ProcessingStageChip(stage: String) {
    val container: androidx.compose.ui.graphics.Color
    val content: androidx.compose.ui.graphics.Color
    when (stage) {
        "COMPLETED" -> {
            container = MaterialTheme.colorScheme.primaryContainer
            content = MaterialTheme.colorScheme.onPrimaryContainer
        }
        "FAILED", "TIMED_OUT" -> {
            container = MaterialTheme.colorScheme.errorContainer
            content = MaterialTheme.colorScheme.onErrorContainer
        }
        "CANCELLED" -> {
            container = MaterialTheme.colorScheme.surfaceVariant
            content = MaterialTheme.colorScheme.onSurfaceVariant
        }
        else -> {
            container = MaterialTheme.colorScheme.tertiaryContainer
            content = MaterialTheme.colorScheme.onTertiaryContainer
        }
    }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            if (stage == "COMPLETED") {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(14.dp))
            } else if (stage == "FAILED" || stage == "TIMED_OUT") {
                Icon(Icons.Outlined.Cancel, contentDescription = null, modifier = Modifier.size(14.dp))
            } else if (stage == "CANCELLED") {
                Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(14.dp))
            }
            Text(stage, style = MaterialTheme.typography.labelMedium)
        }
    }
}