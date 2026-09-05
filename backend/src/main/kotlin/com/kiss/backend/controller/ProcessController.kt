package com.kiss.backend.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.kiss.backend.model.dto.ProcessHistoryDto
import com.kiss.backend.model.dto.ProcessStatusDto
import com.kiss.backend.repository.DocumentRepository
import com.kiss.backend.service.DocumentWorkflowService
import com.kiss.backend.service.LocalStorageService
import com.kiss.backend.service.ProcessQueueService
import com.kiss.backend.temporal.workflow.DocumentProcessingWorkflowImpl.Companion.WORKFLOW_ID_PREFIX
import com.kiss.backend.temporal.workflow.DocumentProcessRequest
import com.kiss.backend.temporal.stream.WorkflowJobStreamer
import com.kiss.backend.util.MimeTypes
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * In-flight upload ("process") endpoints.
 *
 * A process exists in Temporal (workflow `doc-<hash>`) until handled; once the
 * workflow closes its outcome is projected to the durable `processing_ledger`
 * (see ProcessQueueService), which is what /history reads and /retry consumes.
 */
@RestController
@RequestMapping("/api/v1/processes")
@Tag(name = "Processes", description = "In-flight document uploads: live events, status, cancel, history, retry")
class ProcessController(
    private val streamer: WorkflowJobStreamer,
    private val processQueueService: ProcessQueueService,
    private val documentWorkflowService: DocumentWorkflowService,
    private val workflowClient: WorkflowClient,
    private val storageService: LocalStorageService,
    private val documentRepository: DocumentRepository,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(ProcessController::class.java)

    @GetMapping(value = ["/{uploadId}/events"], produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @Operation(summary = "SSE stream of processing events for an upload")
    @ApiResponse(responseCode = "200", description = "SSE stream established")
    fun events(@PathVariable uploadId: String): SseEmitter {
        val emitter = SseEmitter(0L)
        streamer.streamFor(uploadId, emitter)
        return emitter
    }

//    @GetMapping("/{uploadId}")
//    @Operation(summary = "Polling fallback: current processing status of an upload")
//    @ApiResponse(responseCode = "200", description = "Current process status")
//    fun getProcessStatus(@PathVariable uploadId: String): ResponseEntity<ProcessStatusDto> {
//        return ResponseEntity.ok(processQueueService.status(uploadId))
//    }

    /**
     * Durable processing history (pageable). Includes FAILED/CANCELLED entries
     * that left Temporal visibility the moment their workflow closed.
     */
    @GetMapping("/history")
    @Operation(summary = "Durable processing history (ledger), newest first")
    @ApiResponse(responseCode = "200", description = "History page")
    fun history(
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int
    ): List<ProcessHistoryDto> =
        processQueueService.history(limit.coerceIn(1, 500), offset.coerceAtLeast(0))

    /**
     * Retry a FAILED/CANCELLED upload WITHOUT re-uploading the file: the
     * workflow is re-started from the ledger's stored request; nothing is
     * referenced from Temporal visibility.
     */
    @PostMapping("/{uploadId}/retry")
    @Operation(summary = "Retry a FAILED/CANCELLED upload without re-uploading (file must still exist)")
    @ApiResponse(responseCode = "202", description = "Retry accepted")
    @ApiResponse(responseCode = "404", description = "No such process or file gone")
    @ApiResponse(responseCode = "409", description = "Already processed or already processing")
    fun retryProcess(@PathVariable uploadId: String): ResponseEntity<Void> {
        if (documentRepository.findByFileHash(uploadId) != null) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Document is already processed - delete the document instead"
            )
        }
        val ledger = processQueueService.findLedger(uploadId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No such upload process")
        val request = ledger.jobSpec?.let { json ->
            runCatching { objectMapper.readValue(json, DocumentProcessRequest::class.java) }.getOrNull()
        } ?: throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Process record is incomplete")

        if (!storageService.getResource(request.objectKey).exists()) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Upload file no longer available")
        }

        // Runs startOrAttach: an already-running workflow yields 409; a fresh
        // start rewrites the ledger's stage to PROCESSING via the workflow's
        // first activity (markProcessingStarted).
        val started = documentWorkflowService.startOrAttach(request, MimeTypes.documentTypeFor(request.mimeType))
        if (!started) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Upload is already processing")
        }
        return ResponseEntity.accepted().build()
    }

    /**
     * Cancel an in-flight upload. COOPERATIVE ONLY — no terminate: termination
     * is the hard kill that skips all workflow cleanup by definition. The
     * signal sets the workflow's cancel flag (checked between steps) and
     * requestCancel stops forward work at the next checkpoint; the workflow's
     * DETACHED compensation then deletes the object + scratch. Worst case the
     * cancel lands after the currently-running activity (bounded by its
     * StartToClose timeout). The reconciler is the backstop.
     */
    @PostMapping("/{uploadId}/cancel")
    @Operation(summary = "Cancel an in-flight upload (cooperative; compensation runs in the workflow)")
    @ApiResponse(responseCode = "202", description = "Cancellation accepted")
    @ApiResponse(responseCode = "404", description = "No such process")
    @ApiResponse(responseCode = "409", description = "Already processed (no longer cancellable)")
    fun cancelProcess(@PathVariable uploadId: String): ResponseEntity<Void> {
        val workflowId = WORKFLOW_ID_PREFIX + uploadId

        // C1: a completed document is NOT cancellable - deleting here would
        // destroy the released file (row survives -> undownloadable doc).
        if (documentRepository.findByFileHash(uploadId) != null) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Document is already processed - delete the document instead"
            )
        }

        try {
            workflowClient.newUntypedWorkflowStub(workflowId).signal("cancel")
        } catch (e: WorkflowNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "No such upload process")
        }

        try {
            workflowClient.newUntypedWorkflowStub(workflowId).cancel()
        } catch (e: WorkflowNotFoundException) {
            // closed between signal and cancel (e.g. finalize won the race):
            // completion is allowed to win; the row it committed is valid.
            logger.debug("Workflow {} already closed during cancel", workflowId)
        }
        return ResponseEntity.accepted().build()
    }
}