package org.kiss.ui.documents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.kiss.utils.jsError
import org.kiss.utils.jsLog
import org.kiss.data.DocsEvent
import org.kiss.data.DocumentDto
import org.kiss.data.FaldonyApi
import org.kiss.data.PersonDto
import org.kiss.data.TagDto
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import org.kiss.data.PickedFile
import org.kiss.data.downloadBytes
import org.kiss.data.openBlobTab
import org.kiss.data.readPickedFileBytes
import org.kiss.entity.AutocompleteEntity
import org.kiss.entity.DocumentRowDto
import org.kiss.entity.toEntity
import org.kiss.entity.toRow

private object Log {
    fun d(tag: String, msg: String) { jsLog("$tag: $msg") }
    fun e(tag: String, msg: String, throwable: Throwable? = null) {
        jsError("$tag: $msg")
        throwable?.let { jsError(it.message ?: it.toString()) }
    }
}

/** Sort token the backend maps to RRF relevance ordering. */
private const val SORT_RELEVANCE = "relevance,desc"

enum class SortKey { CREATED_AT, UPDATED_AT, TITLE, TYPE, PAGES }

sealed interface UploadState {
    data object Idle : UploadState
    data object Uploading : UploadState
    data class Processing(val uploadId: String) : UploadState
    data class Done(val documentId: Long?) : UploadState
    data class Failed(val message: String) : UploadState
}

/** Liveness of the document SSE stream backing the table. */
data class StreamStatus(
    val live: Boolean = false,
    val delivered: Int = 0,
    val truncated: Boolean = false,
    val error: String? = null,
)

sealed interface PreviewState {
    data object Idle : PreviewState
    data object Loading : PreviewState
    data class Ready(val kind: PreviewKind) : PreviewState
    data class Error(val message: String) : PreviewState
}

sealed interface PreviewKind {
    data class Image(val bytes: ByteArray, val mimeType: String) : PreviewKind
    data class Pdf(val bytes: ByteArray, val fileName: String) : PreviewKind
    data class Text(val text: String) : PreviewKind
    data object Unsupported : PreviewKind
}

private fun sortParam(key: SortKey, ascending: Boolean): String {
    val column = when (key) {
        SortKey.CREATED_AT -> "createdAt"
        SortKey.UPDATED_AT -> "updatedAt"
        SortKey.TITLE -> "title"
        SortKey.TYPE -> "type"
        SortKey.PAGES -> "pages"
    }
    return "$column,${if (ascending) "asc" else "desc"}"
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DocumentsViewModel(
    private val onProcessListChanged: (() -> Unit)? = null,
    initialApi: FaldonyApi = FaldonyApi(),
) : ViewModel() {

    /** Swappable API client — rebuilt when the backend URL changes. */
    private var api: FaldonyApi = initialApi

    // --- inputs -----------------------------------------------------------
    private val _search = MutableStateFlow("")
    val search: StateFlow<String> = _search.asStateFlow()

    /**
     * Sorting intent. [active] becomes true only when the user clicks a
     * column header; a default header (Created) is not an explicit choice.
     * While searching without an explicit sort, the backend orders by
     * relevance.
     */
    private val _sortKey = MutableStateFlow(SortKey.CREATED_AT)
    val sortKey: StateFlow<SortKey> = _sortKey.asStateFlow()

    private val _sortAscending = MutableStateFlow(false)
    val sortAscending: StateFlow<Boolean> = _sortAscending.asStateFlow()

    private val _userSortActive = MutableStateFlow(false)
    val userSortActive: StateFlow<Boolean> = _userSortActive.asStateFlow()

    /** One combined, small value: the whole sorting intent in a single flow. */
    private data class Sorting(
        val key: SortKey = SortKey.CREATED_AT,
        val ascending: Boolean = false,
        val explicit: Boolean = false,
    )

    private val sorting = combine(_sortKey, _sortAscending, _userSortActive) { key, ascending, explicit ->
        Sorting(key, ascending, explicit)
    }

    private val _partyFilter = MutableStateFlow<List<AutocompleteEntity>>(emptyList())
    val partyFilter: StateFlow<List<AutocompleteEntity>> = _partyFilter.asStateFlow()

    private val _tagFilter = MutableStateFlow<List<AutocompleteEntity>>(emptyList())
    val tagFilter: StateFlow<List<AutocompleteEntity>> = _tagFilter.asStateFlow()

    private val _typeFilter = MutableStateFlow<List<AutocompleteEntity>>(emptyList())
    val typeFilter: StateFlow<List<AutocompleteEntity>> = _typeFilter.asStateFlow()

    private val _refreshTick = MutableStateFlow(0L)

    private data class Filters(
        val parties: List<AutocompleteEntity>,
        val tags: List<AutocompleteEntity>,
        val types: List<AutocompleteEntity>,
    )

    private val filters =
        combine(_partyFilter, _tagFilter, _typeFilter) { parties, tags, types ->
            Filters(parties, tags, types)
        }

    /** The tick is part of the key so `distinctUntilChanged` re-fires the
     *  stream on manual refresh and backend switches. */
    private data class Query(
        val q: String,
        val partyIds: List<Long>,
        val tagIds: List<Long>,
        val type: String?,
        val sort: String,
        val tick: Long,
    )

    /**
     * The wire sort token:
     *  - searching without an explicit column  -> relevance
     *  - browsing or an explicit column click  -> column token
     */
    private fun QuerySorting(
        search: String,
        sorting: Sorting,
    ): String = if (search.isNotBlank() && !sorting.explicit) {
        SORT_RELEVANCE
    } else {
        sortParam(sorting.key, sorting.ascending)
    }

    private val query =
        combine(_search.debounce(300), filters, sorting, _refreshTick) { search, f, s, tick ->
            Query(
                q = search.trim(),
                partyIds = f.parties.map { it.id },
                tagIds = f.tags.map { it.id },
                type = f.types.firstOrNull()?.name,
                sort = QuerySorting(search.trim(), s),
                tick = tick,
            )
        }

    // --- derived rows (live SSE) ------------------------------------------
    private val _streamStatus = MutableStateFlow(StreamStatus())
    val streamStatus: StateFlow<StreamStatus> = _streamStatus.asStateFlow()

    val rows: StateFlow<List<DocumentRowDto>> = query
        .distinctUntilChanged()
        .flatMapLatest { q ->
            flow {
                val acc = mutableListOf<DocumentRowDto>()
                _streamStatus.value = StreamStatus(live = true)
                try {
                    api.documentsStream(
                        q = q.q.ifBlank { null },
                        type = q.type,
                        partyIds = q.partyIds,
                        tagIds = q.tagIds,
                        sort = q.sort,
                    ).collect { event ->
                        when (event) {
                            is DocsEvent.Doc -> {
                                acc += event.dto.toRow()
                                _streamStatus.value = StreamStatus(live = true, delivered = acc.size)
                                emit(acc.toList())
                            }
                            is DocsEvent.Done ->
                                _streamStatus.value = StreamStatus(
                                    live = false,
                                    delivered = event.delivered,
                                    truncated = event.truncated,
                                )
                            is DocsEvent.Error -> throw IllegalStateException(event.message)
                        }
                    }
                    _streamStatus.value = _streamStatus.value.copy(live = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Keep the rows we already have; surface the failure.
                    Log.e("docs", "stream failed: ${e.message ?: e::class.simpleName}", e)
                    _streamStatus.value = StreamStatus(live = false, delivered = acc.size, error = e.message)
                    emit(acc.toList())
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- detail / preview ---------------------------------------------------
    private val _selectedId = MutableStateFlow<Long?>(null)
    val selectedId: StateFlow<Long?> = _selectedId.asStateFlow()

    private val _detail = MutableStateFlow<DocumentDto?>(null)
    val detail: StateFlow<DocumentDto?> = _detail.asStateFlow()

    private val _detailLoading = MutableStateFlow(false)
    val detailLoading: StateFlow<Boolean> = _detailLoading.asStateFlow()

    private val _preview = MutableStateFlow<PreviewState>(PreviewState.Idle)
    val preview: StateFlow<PreviewState> = _preview.asStateFlow()

    /** Opens the details drawer for [row] (table click). */
    fun openPreview(row: DocumentRowDto) = openDocument(row.id)

    /** Opens the details drawer for [id], always refetching fresh data. */
    fun openDocument(id: Long) {
        _selectedId.value = id
        refreshDetail()
    }

    fun closePreview() {
        _selectedId.value = null
        _detail.value = null
        _preview.value = PreviewState.Idle
    }

    /** Refetches the selected document from the server (drawer refresh button). */
    fun refreshDetail() {
        val id = _selectedId.value ?: return
        viewModelScope.launch {
            _detailLoading.value = true
            try {
                _detail.value = api.getDocument(id)
            } catch (e: Exception) {
                Log.e("detail", "refresh failed for $id", e)
                _errorMessage.value = e.message ?: "Failed to load document"
            } finally {
                _detailLoading.value = false
            }
            loadPreview(id)
        }
    }

    private fun loadPreview(id: Long) {
        viewModelScope.launch {
            _preview.value = PreviewState.Loading
            try {
                val payload = api.downloadDocument(id, title = null, inline = true)
                val ct = payload.contentType.lowercase()
                _preview.value = when {
                    ct.startsWith("image/") -> PreviewState.Ready(PreviewKind.Image(payload.bytes, payload.contentType))
                    ct == "application/pdf" -> PreviewState.Ready(PreviewKind.Pdf(payload.bytes, payload.fileName))
                    ct.startsWith("text/") ->
                        PreviewState.Ready(PreviewKind.Text(payload.bytes.decodeToString().take(200_000)))
                    else -> PreviewState.Ready(PreviewKind.Unsupported)
                }
            } catch (e: Exception) {
                Log.e("preview", "preview load failed for $id", e)
                _preview.value = PreviewState.Error(e.message ?: "Preview unavailable")
            }
        }
    }

    fun openPreviewInTab() {
        val ready = _preview.value as? PreviewState.Ready ?: return
        when (val kind = ready.kind) {
            is PreviewKind.Pdf -> openBlobTab(kind.bytes, "application/pdf")
            is PreviewKind.Image -> openBlobTab(kind.bytes, kind.mimeType)
            else -> Unit
        }
    }

    // --- detail edits -------------------------------------------------------

    fun updateDetailTitle(newTitle: String) {
        val current = _detail.value ?: return
        val title = newTitle.trim()
        if (title.isEmpty() || title == current.title) return
        viewModelScope.launch {
            try {
                _detail.value = api.updateDocument(current.id, title = title)
            } catch (e: Exception) {
                Log.e("detail", "title update failed", e)
                _errorMessage.value = e.message ?: "Title update failed"
            }
            refresh()
        }
    }

    fun toggleDetailParty(party: PersonDto, add: Boolean) {
        val d = _detail.value ?: return
        viewModelScope.launch {
            try {
                _detail.value = if (add) api.addDocumentParty(d.id, party.id)
                else api.removeDocumentParty(d.id, party.id)
            } catch (e: Exception) {
                Log.e("detail", "party change failed", e)
                _errorMessage.value = e.message ?: "Party update failed"
            }
            refresh()
        }
    }

    fun toggleDetailTag(tag: TagDto, add: Boolean) {
        val d = _detail.value ?: return
        viewModelScope.launch {
            try {
                _detail.value = if (add) api.addDocumentTag(d.id, tag.id)
                else api.removeDocumentTag(d.id, tag.id)
            } catch (e: Exception) {
                Log.e("detail", "tag change failed", e)
                _errorMessage.value = e.message ?: "Tag update failed"
            }
            refresh()
        }
    }

    // --- backend -------------------------------------------------------------

    // --- reference data (backend-backed) --------------------------------------
    val referenceData = ReferenceData()

    inner class ReferenceData {
        suspend fun getDocumentTypes(q: String): List<AutocompleteEntity> =
            org.kiss.entity.DocumentType.entries
                .mapIndexed { i, type -> AutocompleteEntity(i + 1L, type.name) }
                .filter { q.isBlank() || it.name.contains(q.trim(), ignoreCase = true) }

        suspend fun getParties(q: String): List<AutocompleteEntity> =
            api.parties()
                .map { AutocompleteEntity(it.id, it.name) }
                .filter { q.isBlank() || it.name.contains(q.trim(), ignoreCase = true) }

        suspend fun getTags(q: String): List<AutocompleteEntity> =
            api.tags()
                .map { AutocompleteEntity(it.id, it.name) }
                .filter { q.isBlank() || it.name.contains(q.trim(), ignoreCase = true) }

        suspend fun getCollections(q: String): List<AutocompleteEntity> =
            api.collections()
                .map { AutocompleteEntity(it.id, it.name) }
                .filter { q.isBlank() || it.name.contains(q.trim(), ignoreCase = true) }
    }

    // --- upload -------------------------------------------------------------
    private val _upload = MutableStateFlow<UploadState>(UploadState.Idle)
    val upload: StateFlow<UploadState> = _upload.asStateFlow()

    private var pickedFile: PickedFile? = null
    private val _stagedFile = MutableStateFlow<PickedFile?>(null)
    val stagedFileFlow: StateFlow<PickedFile?> = _stagedFile.asStateFlow()

    /**
     * Registers the platform file the user chose in the FileKit picker.
     * Reads the bytes OFF the main thread. The same file may arrive multiple
     * times (launcher re-fires on recomposition) — dedupe by identity/name.
     */
    fun setStagedFileFromPlatform(file: PlatformFile) {
        val existing = _stagedFile.value
        if (existing != null && existing.name == file.name) {
            Log.d("upload", "duplicate pick ignored: ${file.name}")
            return
        }
        viewModelScope.launch {
            Log.d("upload", "reading ${file.name}")
            try {
                val bytes = readPickedFileBytes(file)
                val mime = file.mimeType()?.toString()?.takeIf { it.isNotBlank() }
                    ?: "application/octet-stream"
                val staged = PickedFile(name = file.name, mimeType = mime, bytes = bytes)
                pickedFile = staged
                _stagedFile.value = staged
                Log.d("upload", "staged OK name=${file.name} bytes=${bytes.size} mime=$mime")
            } catch (e: Exception) {
                Log.e("upload", "reading staged file failed", e)
                pickedFile = null
                _stagedFile.value = null
                setUploadError("Could not read ${file.name}: ${e.message}")
            }
        }
    }

    fun stagedFile(): PickedFile? = pickedFile

    /** Single source of truth for the staged file (dialog + VM stay in sync). */
    fun setStagedFile(file: PickedFile?) {
        Log.d("upload", "setStagedFile(${file?.name ?: "null"})")
        pickedFile = file
        _stagedFile.value = file
    }

    /**
     * Uploads the staged file with title + party/tag/collection references.
     *
     * A PROCESSING response closes the dialog (the Processing tab takes over
     * via [onProcessListChanged]); a COMPLETED response (dedup) lands in the
     * Done state immediately.
     */
    fun submitUpload(
        title: String,
        partyIds: List<Long>,
        tagIds: List<Long>,
        collectionIds: List<Long>,
    ) {
        val file = pickedFile ?: return
        val titleText = title.trim().ifBlank { file.name.substringBeforeLast('.') }
        Log.d("upload", "starting upload file=${file.name} size=${file.bytes.size} mime=${file.mimeType} url=${api.baseUrl}")
        viewModelScope.launch {
            _upload.value = UploadState.Uploading
            try {
                val resp = api.upload(
                    title = titleText,
                    fileName = file.name,
                    mimeType = file.mimeType,
                    bytes = file.bytes,
                    partyIds = partyIds,
                    tagIds = tagIds,
                    collectionIds = collectionIds,
                )
                Log.d("upload", "response=${resp.status} uploadId=${resp.uploadId} documentId=${resp.documentId}")
                // Close the dialog: the picker surface has served its purpose.
                dismissUpload()
                when (resp.status) {
                    "COMPLETED" -> {
                        _upload.value = UploadState.Done(resp.documentId)
                        refresh()
                    }
                    else -> {
                        _upload.value = UploadState.Processing(resp.uploadId)
                        onProcessListChanged?.invoke()
                    }
                }
            } catch (e: Exception) {
                Log.e("upload", "upload failed", e)
                _upload.value = UploadState.Failed(
                    "${api.baseUrl} — ${e.message ?: e::class.simpleName ?: "error"}",
                )
            }
        }
    }

    /** No explicit lifecycle reset: dialog visibility is driven by the file. */
    fun dismissUpload() { pickedFile = null; _stagedFile.value = null }

    /** Surfaces a picker failure (FileKit onError) through the error snackbar. */
    fun setUploadError(message: String?) {
        if (!message.isNullOrBlank()) {
            Log.e("upload", "picker error: $message")
            _errorMessage.value = message
        }
    }

    /** Inline quick-create of a party; result delivered to [onResult]. */
    fun quickCreateParty(name: String, emails: List<String>, onResult: (PersonDto?) -> Unit) {
        viewModelScope.launch {
            val cleaned = emails.map { it.trim() }.filter { it.isNotBlank() }.toSet()
            val result = runCatching { api.createParty(name.trim(), cleaned) }
                .onFailure { Log.e("party", "create failed", it); _errorMessage.value = it.message ?: "Party creation failed" }
                .getOrNull()
            onResult(result)
        }
    }

    /** Inline quick-create of a tag; result delivered to [onResult]. */
    fun quickCreateTag(name: String, onResult: (TagDto?) -> Unit) {
        viewModelScope.launch {
            val result = runCatching { api.createTag(name.trim()) }
                .onFailure { Log.e("tag", "create failed", it); _errorMessage.value = it.message ?: "Tag creation failed" }
                .getOrNull()
            onResult(result)
        }
    }

    // --- actions -----------------------------------------------------------

    fun setSearch(value: String) { _search.value = value }
    fun setPartyFilter(value: List<AutocompleteEntity>) { _partyFilter.value = value }
    fun setTagFilter(value: List<AutocompleteEntity>) { _tagFilter.value = value }
    fun setTypeFilter(value: List<AutocompleteEntity>) { _typeFilter.value = value }

    fun toggleSort(key: SortKey) {
        _userSortActive.value = true
        if (key == _sortKey.value) _sortAscending.value = !_sortAscending.value
        else {
            _sortKey.value = key
            _sortAscending.value = true
        }
    }

    /** Drops back to relevance ordering (used for the search flow). */
    fun resetSortToRelevance() {
        _userSortActive.value = false
    }

    fun refresh() { _refreshTick.value += 1 }

    /** Downloads a document with the real filename + content type. */
    fun download(doc: DocumentRowDto) = downloadPayload(doc.id, doc.title, inline = false)

    fun downloadDetail() {
        _detail.value?.let { downloadPayload(it.id, it.title, inline = false) }
    }

    private fun downloadPayload(id: Long, title: String, inline: Boolean?) {
        viewModelScope.launch {
            try {
                val payload = api.downloadDocument(id, title = title, inline = inline)
                downloadBytes(payload.fileName, payload.bytes, payload.contentType)
            } catch (e: Exception) {
                Log.e("download", "download failed for $id", e)
                _errorMessage.value = e.message ?: "Download failed"
            }
        }
    }

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    fun clearError() { _errorMessage.value = null }
}
