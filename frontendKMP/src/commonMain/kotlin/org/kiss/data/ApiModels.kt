package org.kiss.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.datetime.Instant

// ---------------------------------------------------------------------------
// Wire DTOs — mirror the backend JSON exactly (see backend model/dto/Dtos.kt).
// ---------------------------------------------------------------------------

@Serializable
enum class DocumentType { DOCUMENT, IMAGE, PDF }

@Serializable
data class PersonDto(val id: Long, val name: String, val emails: Set<String> = emptySet())

@Serializable
data class TagDto(val id: Long, val name: String)

@Serializable
data class CollectionDto(
    val id: Long,
    val name: String,
    val description: String? = null,
    @SerialName("createdAt") val createdAt: Instant,
    @SerialName("updatedAt") val updatedAt: Instant,
)

@Serializable
data class DocumentDto(
    val id: Long,
    val title: String,
    val type: DocumentType,
    val parties: List<PersonDto> = emptyList(),
    val tags: List<TagDto> = emptyList(),
    val collections: List<CollectionDto> = emptyList(),
    val pages: Int,
    @SerialName("createdAt") val createdAt: Instant,
    @SerialName("updatedAt") val updatedAt: Instant,
)

/** The upload body travels as a JSON `metadata` multipart part. */
@Serializable
data class UploadRequest(
    val title: String,
    val partyIds: List<Long> = emptyList(),
    val tagIds: List<Long> = emptyList(),
    val collectionIds: List<Long> = emptyList(),
)

/**
 * PATCH /documents/{id} body. Backend semantics: `null` = unchanged,
 * `[]` = clear. The client encodes defaults so `[]` is actually sent.
 */
@Serializable
data class DocumentUpdateRequest(
    val title: String? = null,
    val partyIds: List<Long>? = null,
    val tagIds: List<Long>? = null,
)

@Serializable
data class PersonCreateRequest(val name: String, val emails: Set<String> = emptySet())

@Serializable
data class TagCreateRequest(val name: String)

@Serializable
data class UploadResponseDto(
    @SerialName("uploadId") val uploadId: String,
    val status: String,
    @SerialName("documentId") val documentId: Long? = null,
    @SerialName("sseUrl") val sseUrl: String? = null,
    @SerialName("queuedAt") val queuedAt: Instant? = null,
)

@Serializable
data class ProcessStatusDto(
    @SerialName("uploadId") val uploadId: String,
    val stage: String,
    val errorCode: String? = null,
    val message: String? = null,
    @SerialName("documentId") val documentId: Long? = null,
)

@Serializable
data class ProcessItemDto(
    @SerialName("uploadId") val uploadId: String,
    val title: String,
    val type: DocumentType? = null,
    @SerialName("startedAt") val startedAt: Instant,
)

@Serializable
data class ProcessHistoryDto(
    @SerialName("uploadId") val uploadId: String,
    val title: String,
    val type: DocumentType? = null,
    val stage: String,
    val errorCode: String? = null,
    val error: String? = null,
    @SerialName("documentId") val documentId: Long? = null,
    @SerialName("createdAt") val createdAt: Instant,
    @SerialName("updatedAt") val updatedAt: Instant,
)

/** Payload of `event:task` on `/api/v1/processes/{uploadId}/events` (backend DocumentProgressEvent). */
@Serializable
data class ProcessTaskEvent(
    @SerialName("uploadId") val uploadId: String = "",
    val stage: String = "",
    val message: String? = null,
    @SerialName("documentId") val documentId: Long? = null,
)

@Serializable
data class ApiErrorDto(val code: String? = null, val message: String? = null)

/** Terminal `done` event data on the document stream. */
@Serializable
data class DonePayload(val delivered: Int, val truncated: Boolean)

/** SSE doc-stream events: `doc` / `done` / `error`. */
sealed interface DocsEvent {
    data class Doc(val dto: DocumentDto) : DocsEvent
    data class Done(val delivered: Int, val truncated: Boolean) : DocsEvent
    data class Error(val message: String) : DocsEvent
}

/** Reference data (parties / tags) fetched from the backend for the filters. */
data class ReferenceEntry(val id: Long, val name: String)

/** A downloaded document: raw bytes + real content type + real filename. */
data class DownloadPayload(val bytes: ByteArray, val contentType: String, val fileName: String)