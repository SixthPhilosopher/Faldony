package com.kiss.backend.controller

import com.kiss.backend.model.dto.*
import com.kiss.backend.repository.DocumentChunkRepository
import com.kiss.backend.service.DocumentService
import com.kiss.backend.service.ProcessQueueService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.beans.factory.annotation.Qualifier
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ContentDisposition
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.Executor

@RestController
@RequestMapping("/api/v1/documents")
@Tag(name = "Documents", description = "Document management endpoints")
class DocumentController(
    private val documentService: DocumentService,
    private val processQueueService: ProcessQueueService,
    @Qualifier("documentStreamExecutor") private val streamExecutor: Executor
) {

    /**
     * The single upload call: bytes arrive as a multipart `file` part while the
     * document metadata rides in a JSON `metadata` part. All upload checks
     * (magic bytes, hash, dedup, references) run before anything is persisted;
     * the DB row only appears once the workflow finalizes.
     */
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @Operation(
        summary = "Upload a document (file + metadata in one request)",
        description = "Returns 202 with an uploadId (content hash) while processing, or 200 with " +
            "the existing documentId when the same file was already uploaded (idempotent)."
    )
    @ApiResponse(responseCode = "202", description = "Accepted for processing")
    @ApiResponse(responseCode = "200", description = "Already processed (duplicate)")
    @ApiResponse(responseCode = "413", description = "File too large")
    @ApiResponse(responseCode = "415", description = "Unsupported/unrecognized file type")
    fun uploadDocument(
        @RequestPart("file") file: MultipartFile,
        @RequestPart("metadata") @Valid metadata: DocumentUploadRequest
    ): ResponseEntity<DocumentUploadResponseDto> {
        val res = documentService.uploadDocument(file, metadata)
        val status = if (res.status == "COMPLETED") HttpStatus.OK else HttpStatus.ACCEPTED
        return ResponseEntity.status(status).body(res)
    }

    /**
     * Documents as SSE — moved to `/stream` so the JSON list can own the
     * base path without content-negotiation ambiguity.
     *
     *  - blank `q` → **browse**: every filter-matching document, ordered by the
     *    sort field; no ranking, no cap.
     *  - non-blank `q` → **ranked search**: only documents matched by at least
     *    one retriever inside the internal ranking window (see
     *    DocumentChunkRepository).
     *
     * Events: one `doc` per DocumentDto (sort-field order), then a terminal
     * `done` event with `{delivered, truncated}`.
     */
    @GetMapping(value = ["/stream"], produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @Operation(
        summary = "Documents list / ranked search (SSE)",
        description = "Streams documents as `doc` events + a terminal `done` event. " +
            "Same filters/sort as the JSON list; base path is /api/v1/documents."
    )
    @ApiResponse(responseCode = "200", description = "SSE stream (doc* -> done)")
    fun documents(@ModelAttribute @Valid query: DocumentQuery): SseEmitter {
        val (sortColumn, sortDirection) = parseSort(query.sort)
        val emitter = SseEmitter(0L) // no server timeout: stream closes on done/error/disconnect
        emitter.onError { /* client disconnected — terminal event skipped, stream closed */ }
        emitter.onTimeout { emitter.complete() }
        streamExecutor.execute {
            documentService.streamDocumentsTo(
                emitter, query.q, query.type, query.partyIds, query.tagIds, query.collectionId,
                query.createdFrom, query.createdTo, query.updatedFrom, query.updatedTo,
                sortColumn, sortDirection
            )
        }
        return emitter
    }

    /**
     * Documents list as JSON (base path): a blocking list of hydrated
     * DocumentDto for one-shot fetches (KMP frontend).
     */
    @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Documents list (JSON) — same filters/sort as the SSE stream")
    fun documentsJson(@ModelAttribute @Valid query: DocumentQuery): List<DocumentDto> {
        val (sortColumn, sortDirection) = parseSort(query.sort)
        return documentService.listDocuments(
            query.q, query.type, query.partyIds, query.tagIds, query.collectionId,
            query.createdFrom, query.createdTo, query.updatedFrom, query.updatedTo,
            sortColumn, sortDirection
        )
    }

    /** Uploads that are processing but not yet released — backed by Temporal (
     * open `DocumentProcessingWorkflow` executions), since no DB row exists yet. */
    @GetMapping("/queue")
    @Operation(summary = "Processing queue: in-flight uploads, not yet available")
    @ApiResponse(responseCode = "200", description = "List of in-flight uploads")
    fun getProcessingQueue(): List<ProcessItemDto> {
        return processQueueService.list()
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get document by ID")
    @ApiResponse(responseCode = "200", description = "Document details")
    @ApiResponse(responseCode = "404", description = "Document not found")
    fun getDocumentById(@PathVariable id: Long): ResponseEntity<DocumentDto> {
        return ResponseEntity.ok(documentService.getDocumentById(id))
    }

    @PatchMapping("/{id}")
    @Operation(
        summary = "Update a document's metadata (title, parties, tags)",
        description = "Composite PATCH: optional title / partyIds / tagIds. A null field is left " +
            "unchanged, an empty list clears the set. Title changes are re-indexed via Temporal " +
            "after commit. Collections stay managed via the collections API."
    )
    @ApiResponse(responseCode = "200", description = "Document with the updated metadata")
    @ApiResponse(responseCode = "400", description = "Validation failed or referenced party/tag not found")
    @ApiResponse(responseCode = "404", description = "Document not found")
    fun patchDocument(
        @PathVariable id: Long,
        @Valid @RequestBody req: DocumentUpdateRequest
    ): ResponseEntity<DocumentDto> {
        return ResponseEntity.ok(documentService.updateDocument(id, req))
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete document entirely (file, chunks, memberships)")
    @ApiResponse(responseCode = "204", description = "Document deleted")
    @ApiResponse(responseCode = "404", description = "Document not found")
    fun deleteDocument(@PathVariable id: Long): ResponseEntity<Void> {
        documentService.deleteDocument(id)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/{id}/download")
    @Operation(summary = "Download the document file (streamed by the API)")
    @ApiResponse(responseCode = "200", description = "File stream with Content-Type and Content-Disposition")
    @ApiResponse(responseCode = "404", description = "Document not found")
    fun downloadDocument(
        @PathVariable id: Long,
        @RequestParam(required = false) inline: Boolean?
    ): ResponseEntity<org.springframework.core.io.Resource> {
        // inline defaults to "auto": viewable types (image/pdf/text) are inline
        // (browser/iframe display), office files are attachments (download).
        // The FileSystemResource is streamed by MVC's ResourceHttpMessageConverter,
        // which also derives the Content-Length from the file attributes.
        val d = documentService.streamDownload(id, inline)
        val disposition = (if (d.inline) ContentDisposition.inline() else ContentDisposition.attachment())
            .filename(d.fileName, Charsets.UTF_8)
            .build()
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(d.contentType))
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .body(d.resource)
    }

    @PostMapping("/{id}/parties/{partyId}")
    @Operation(summary = "Add a party to a document")
    @ApiResponse(responseCode = "200", description = "Party added")
    @ApiResponse(responseCode = "404", description = "Document or party not found")
    fun addParty(@PathVariable id: Long, @PathVariable partyId: Long): ResponseEntity<DocumentDto> {
        return ResponseEntity.ok(documentService.addParty(id, partyId))
    }

    @DeleteMapping("/{id}/parties/{partyId}")
    @Operation(summary = "Remove a party from a document")
    @ApiResponse(responseCode = "200", description = "Party removed")
    @ApiResponse(responseCode = "404", description = "Document or party not found")
    fun removeParty(@PathVariable id: Long, @PathVariable partyId: Long): ResponseEntity<DocumentDto> {
        return ResponseEntity.ok(documentService.removeParty(id, partyId))
    }

    @PostMapping("/{id}/tags/{tagId}")
    @Operation(summary = "Add a tag to a document")
    @ApiResponse(responseCode = "200", description = "Tag added")
    @ApiResponse(responseCode = "404", description = "Document or tag not found")
    fun addTag(@PathVariable id: Long, @PathVariable tagId: Long): ResponseEntity<DocumentDto> {
        return ResponseEntity.ok(documentService.addTag(id, tagId))
    }

    @DeleteMapping("/{id}/tags/{tagId}")
    @Operation(summary = "Remove a tag from a document")
    @ApiResponse(responseCode = "200", description = "Tag removed")
    @ApiResponse(responseCode = "404", description = "Document or tag not found")
    fun removeTag(@PathVariable id: Long, @PathVariable tagId: Long): ResponseEntity<DocumentDto> {
        return ResponseEntity.ok(documentService.removeTag(id, tagId))
    }

    /**
     * Parses `?sort=field,asc|desc`. Field whitelisted against
     * DocumentChunkRepository.SORT_COLUMNS (never interpolated raw into SQL).
     */
    private fun parseSort(sort: String): Pair<String, String> {
        val parts = sort.split(",", limit = 2)
        val column = parts[0].trim()
        val direction = parts.getOrNull(1)?.trim()?.lowercase() ?: "desc"
        if (column !in DocumentChunkRepository.SORT_COLUMNS) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported sort column: $column")
        }
        if (direction != "asc" && direction != "desc") {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Sort direction must be asc or desc")
        }
        return column to direction
    }
}