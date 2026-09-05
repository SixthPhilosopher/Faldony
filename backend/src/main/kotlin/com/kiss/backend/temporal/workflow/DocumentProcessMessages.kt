package com.kiss.backend.temporal.workflow

/**
 * Everything the extraction workflow needs to process one upload.
 * Only lightweight values (never file bytes): the content [hash] doubles as
 * the upload id, workflow id (`doc-<hash>`) and dedup key.
 */
data class DocumentProcessRequest(
    val hash: String = "",
    val title: String = "",
    val objectKey: String = "",
    val mimeType: String = "",
    val sizeBytes: Long = 0,
    val partyIds: List<Long> = emptyList(),
    val tagIds: List<Long> = emptyList(),
    val collectionIds: List<Long> = emptyList()
)

data class DocumentProgress(
    val uploadId: String = "",
    val stage: String = ""
)

/**
 * Payload streamed on the workflow's `status` topic (Workflow Streams).
 * Serialized by Temporal's converter; consumed by the SSE bridge.
 * `documentId` is populated only on the terminal COMPLETED event.
 */
data class DocumentProgressEvent(
    val uploadId: String = "",
    val stage: String = "",
    val message: String? = null,
    val documentId: Long? = null
)