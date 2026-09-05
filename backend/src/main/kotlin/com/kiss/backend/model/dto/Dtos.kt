package com.kiss.backend.model.dto

import com.kiss.backend.model.entity.DocumentType
import com.kiss.backend.model.entity.ProcessingErrorCode
import com.kiss.backend.model.entity.ProcessingStage
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant


// ---------------------------------------------------------------------------
// ERROR HANDLING
// ---------------------------------------------------------------------------

data class ApiError(
    val code: String,
    val message: String,
    val timestamp: Instant = Instant.now(),
    val details: List<FieldError>? = null
)

data class FieldError(val field: String, val message: String)


// ---------------------------------------------------------------------------
// DOCUMENTS
// ---------------------------------------------------------------------------

data class DocumentDto(
    val id: Long,
    val title: String,
    val type: DocumentType,
    val parties: List<PersonDto>,
    val tags: List<TagDto>,
    val collections: List<CollectionDto>,
    val pages: Int,
    val createdAt: Instant,
    val updatedAt: Instant
)

/**
 * Metadata edit for a released document. Composite PATCH:
 * a `null` field is left unchanged, `[]` clears the set, absent = untouched.
 * Title changes are re-indexed (Temporal) after commit.
 */
data class DocumentUpdateRequest(
    @field:Size(min = 1, max = 255, message = "Title must be 1-255 characters")
    val title: String? = null,
    @field:Size(max = 50, message = "At most 50 party ids")
    val partyIds: List<Long>? = null,
    @field:Size(max = 50, message = "At most 50 tag ids")
    val tagIds: List<Long>? = null
)

/**
 * Metadata accepted by the single-call upload endpoint. The file bytes travel
 * in a separate multipart part; the mime type is sniffed from the magic bytes,
 * so no mimeType is required from the client.
 */
data class DocumentUploadRequest(
    @field:NotBlank(message = "Title cannot be blank")
    @field:Size(max = 255, message = "Title must be at most 255 characters")
    val title: String,
    @field:Size(max = 50, message = "At most 50 party ids")
    val partyIds: List<Long> = emptyList(),
    @field:Size(max = 50, message = "At most 50 tag ids")
    val tagIds: List<Long> = emptyList(),
    @field:Size(max = 50, message = "At most 50 collection ids")
    val collectionIds: List<Long> = emptyList()
)

/**
 * Result of the upload request. When [documentId] is present the file was
 * already fully processed (idempotent re-upload) and is returned directly;
 * otherwise [uploadId] is the content hash the client uses to follow progress
 * via SSE ([sseUrl]) and the process queue.
 */
data class DocumentUploadResponseDto(
    val uploadId: String,
    val status: String,
    val documentId: Long? = null,
    val sseUrl: String? = null,
    val queuedAt: Instant = Instant.now()
)

enum class MovePosition { START, END, BEFORE, AFTER }

data class MoveDocumentRequest(
    @field:NotNull(message = "position is required")
    val position: MovePosition,
    /** Required when position is BEFORE/AFTER. */
    val targetDocumentId: Long? = null
)


// ---------------------------------------------------------------------------
// COLLECTIONS
// ---------------------------------------------------------------------------

data class AddDocumentToCollectionRequest(
    @field:NotNull(message = "documentId is required")
    val documentId: Long
)

data class CollectionCreateRequest(
    @field:NotBlank(message = "Collection name cannot be blank")
    val name: String,
    val description: String? = null
)

data class CollectionDto(
    val id: Long,
    val name: String,
    val description: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant
)


// ---------------------------------------------------------------------------
// PERSONS (PARTIES)
// ---------------------------------------------------------------------------

data class PersonDto(val id: Long, val name: String, val emails: Set<String>)

data class PersonCreateRequest(@field:NotBlank val name: String, val emails: MutableSet<String> = mutableSetOf())

data class PersonPatchRequest(val name: String?, val emails: Set<String>?)


// ---------------------------------------------------------------------------
// TAGS
// ---------------------------------------------------------------------------

data class TagDto(val id: Long, val name: String)

data class TagCreateRequest(@field:NotBlank val name: String)

data class TagPatchRequest(@field:NotBlank val name: String?)


// ---------------------------------------------------------------------------
// SEARCH
// ---------------------------------------------------------------------------

/**
 * Search / browse filter set.
 *
 * The endpoint is UNIFIED on `GET /api/v1/documents`:
 *  - blank `q`  → browse: every filter-matching document, ordered by [sort],
 *                 NO ranking cap (unbounded list).
 *  - non-blank `q` → ranked search: only documents matched by at least one
 *                 retriever (bounded top-k window, see DocumentChunkRepository),
 *                 ordered by [sort]. Acknowledged limitation: results beyond
 *                 the window are not delivered (logged server-side).
 */
data class DocumentQuery(
    val q: String? = null,
    val type: DocumentType? = null,
    @field:Size(max = 50, message = "at most 50 party ids")
    val partyIds: List<Long> = emptyList(),
    @field:Size(max = 50, message = "at most 50 tag ids")
    val tagIds: List<Long> = emptyList(),
    val collectionId: Long? = null,
    val createdFrom: Instant? = null,
    val createdTo: Instant? = null,
    val updatedFrom: Instant? = null,
    val updatedTo: Instant? = null,
    /** `field,asc|desc`, whitelisted; default createdAt,desc. */
    val sort: String = "createdAt,desc"
)


// ---------------------------------------------------------------------------
// PROCESSING (TEMPORAL)
// ---------------------------------------------------------------------------

/**
 * One in-flight upload from the Temporal-backed queue. [uploadId] is the
 * content hash (== workflow id `doc-<hash>`), title/type come from the
 * workflow memo, startedAt from the workflow's start time.
 */
data class ProcessItemDto(
    val uploadId: String,
    val title: String,
    val type: DocumentType?,
    val startedAt: Instant
)

/** Live polling status of an upload (workflow stage or terminal, typed). */
data class ProcessStatusDto(
    val uploadId: String,
    val stage: ProcessingStage,
    val errorCode: ProcessingErrorCode? = null,
    val message: String? = null,
    val documentId: Long? = null
)

/**
 * One durable ledger entry: a processing attempt that OUTLIVES its workflow
 * (history/audit + retry). Sourced from `processing_ledger`, written by
 * workflow activities — never from Temporal visibility.
 */
data class ProcessHistoryDto(
    val uploadId: String,
    val title: String,
    val type: DocumentType?,
    val stage: ProcessingStage,
    val errorCode: ProcessingErrorCode? = null,
    val error: String? = null,
    val documentId: Long? = null,
    val createdAt: Instant,
    val updatedAt: Instant
)