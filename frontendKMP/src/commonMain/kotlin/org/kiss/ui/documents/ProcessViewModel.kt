package org.kiss.ui.documents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.kiss.utils.jsLog
import org.kiss.data.FaldonyApi
import org.kiss.data.ProcessHistoryDto
import org.kiss.data.ProcessItemDto
import org.kiss.data.ProcessTaskEvent
import org.kiss.entity.ProcessHistoryRow
import org.kiss.entity.ProcessQueueRow
import org.kiss.entity.toEntity

private fun ProcessItemDto.toRow(): ProcessQueueRow =
    ProcessQueueRow(uploadId = uploadId, title = title, type = type?.toEntity(), startedAt = startedAt)

private fun ProcessHistoryDto.toRow(): ProcessHistoryRow = ProcessHistoryRow(
    uploadId = uploadId,
    title = title,
    type = type?.toEntity(),
    stage = stage,
    errorCode = errorCode,
    error = error,
    documentId = documentId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private val TERMINAL_STAGES = setOf("COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT")

/**
 * Backs the Processing screen: the open Temporal queue (live via per-job SSE),
 * the terminal history ledger, and retry/cancel actions.
 */
class ProcessViewModel(
    initialApi: FaldonyApi = FaldonyApi(),
) : ViewModel() {

    /** Invoked when a watched job reaches COMPLETED (document landed). */
    var onDocumentsChanged: () -> Unit = {}

    /** Invoked when the user asks to open a finished document. */
    var onOpenDocument: (Long) -> Unit = {}

    private var api: FaldonyApi = initialApi

    private val _queue = MutableStateFlow<List<ProcessQueueRow>>(emptyList())
    val queue: StateFlow<List<ProcessQueueRow>> = _queue.asStateFlow()

    private val _history = MutableStateFlow<List<ProcessHistoryRow>>(emptyList())
    val history: StateFlow<List<ProcessHistoryRow>> = _history.asStateFlow()

    private val _historyOffset = MutableStateFlow(0)
    private val _historyExhausted = MutableStateFlow(false)
    val historyExhausted: StateFlow<Boolean> = _historyExhausted.asStateFlow()

    /** Latest `task` event per watched uploadId. */
    private val _live = MutableStateFlow<Map<String, ProcessTaskEvent>>(emptyMap())
    val live: StateFlow<Map<String, ProcessTaskEvent>> = _live.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    fun clearError() { _error.value = null }

    private val watchJobs = mutableMapOf<String, Job>()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            runCatching { api.queue() }
                .onSuccess { items ->
                    _queue.value = items.map { it.toRow() }
                    items.forEach { watch(it.uploadId) }
                }
                .onFailure { _error.value = it.message ?: "Failed to load the processing queue" }
            runCatching { api.history(limit = 100, offset = 0) }
                .onSuccess { page ->
                    _history.value = page.map { it.toRow() }
                    _historyOffset.value = page.size
                    _historyExhausted.value = page.size < 100
                }
                .onFailure { _error.value = it.message ?: "Failed to load processing history" }
            _loading.value = false
        }
    }

    /**
     * Attaches a live SSE session to a queued job. The job entry disappears
     * from the backend queue once its workflow closes, so the terminal event
     * triggers a data refresh.
     */
    fun watch(uploadId: String) {
        if (watchJobs.containsKey(uploadId)) return
        val job = viewModelScope.launch {
            try {
                api.processStream(uploadId).collect { event ->
                    _live.update { it + (uploadId to event) }
                    if (event.stage in TERMINAL_STAGES) {
                        if (event.stage == "COMPLETED") onDocumentsChanged()
                        refresh()
                        watchJobs.remove(uploadId)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ProcLog.d("process", "watch $uploadId failed: ${e.message}")
                watchJobs.remove(uploadId)
            }
        }
        watchJobs[uploadId] = job
    }

    fun cancelUpload(uploadId: String) {
        viewModelScope.launch {
            runCatching { api.cancelProcess(uploadId) }
                .onFailure { _error.value = it.message ?: "Cancel failed" }
            refresh()
        }
    }

    fun retryUpload(uploadId: String) {
        viewModelScope.launch {
            runCatching { api.retryProcess(uploadId) }
                .onFailure { _error.value = it.message ?: "Retry failed" }
            refresh()
        }
    }

    fun openDocument(documentId: Long) { onOpenDocument(documentId) }

    fun loadMoreHistory() {
        if (_historyExhausted.value) return
        viewModelScope.launch {
            val offset = _historyOffset.value
            runCatching { api.history(limit = 100, offset = offset) }
                .onSuccess { page ->
                    _history.update { it + page.map { r -> r.toRow() } }
                    _historyOffset.value = offset + page.size
                    _historyExhausted.value = page.size < 100
                }
                .onFailure { _error.value = it.message ?: "Failed to load more history" }
        }
    }
}

private object ProcLog {
    fun d(tag: String, msg: String) { jsLog("$tag: $msg") }
}
