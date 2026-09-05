package com.kiss.backend.temporal.workflow

import com.kiss.backend.model.entity.ProcessingErrorCode
import com.kiss.backend.model.entity.ProcessingStage
import com.kiss.backend.temporal.activity.DocumentActivities
import com.kiss.backend.util.DoclingClient
import com.kiss.backend.util.DoclingTaskStatus
import com.kiss.backend.util.FailureClassifier
import io.temporal.activity.ActivityCancellationType
import io.temporal.failure.ApplicationFailure
import io.temporal.failure.TemporalFailure
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.spring.boot.WorkflowImpl
import io.temporal.workflow.*
import io.temporal.workflowstreams.WorkflowStream
import io.temporal.workflowstreams.WorkflowTopicHandle
import java.time.Duration

@WorkflowInterface
interface DocumentProcessingWorkflow {
    @WorkflowMethod
    fun processDocument(request: DocumentProcessRequest)

    @QueryMethod
    fun getStatus(): DocumentProgress

    @SignalMethod
    fun cancel()
}

/**
 * Orchestrates the extraction pipeline for one upload:
 * extract+chunk (docling) -> embed (Spring AI) -> finalize (single DB write).
 *
 * Workflow code performs no I/O; all side effects live in activities.
 * The database is only touched once, by the final `finalizeDocument` activity,
 * so a row existing == a fully processed document.
 *
 * Failure semantics:
 * - Transient downtime: per-activity retries (capped) absorb it.
 * - Permanent failures fall through as [ActivityFailure] (or other
 *   [TemporalFailure]); a [Saga] compensation registered at start deletes the
 *   upload object + claim-check scratch (idempotent), then the original failure
 *   is rethrown so the execution closes in a Failed state (never a stuck-open
 *   workflow task retry loop).
 * - Cancellation is COOPERATIVE: the /cancel endpoint signals `cancel` and
 *   requests cancellation (`WorkflowClient.requestCancel`) — never terminate,
 *   which by definition skips ALL workflow cleanup. The signal flag is checked
 *   between steps; while an activity runs, the cancel lands at the next
 *   checkpoint (bounded by the extract StartToClose timeout — worst case a
 *   few minutes). Compensations run inside a DETACHED cancellation scope — a
 *   cancelled root scope would otherwise cancel the compensation activity
 *   itself before it runs (known Temporal gotcha; the SDK's Saga does not
 *   detach by default).
 * - Terminal states are ALSO projected to the durable `processing_ledger`
 *   (statusActivities) so failures/cancels stay visible after the workflow
 *   closes and can be retried without re-upload.
 *
 * VERSIONING NOTE: this workflow code is part of the durable contract. Any
 * future change to the visible behavior of OPEN executions must be gated with
 * `Workflow.getVersion(changeId, ...)` (versioning best practice); changes to
 * closed/never-started executions need no gate.
 */
@WorkflowImpl(workers = ["faldony-worker"])
class DocumentProcessingWorkflowImpl @WorkflowInit constructor(
    request: DocumentProcessRequest
) : DocumentProcessingWorkflow {

    private val logger = Workflow.getLogger(DocumentProcessingWorkflowImpl::class.java)

    // Durable event channel: status topic consumed by the SSE bridge.
    private val stream = WorkflowStream.newInstance()
    private val statusTopic: WorkflowTopicHandle = stream.topic(STATUS_TOPIC)

    // Durable status ledger (processing_ledger): cheap, idempotent DB write at
    // start and terminal transitions — the UI history/retry source. Loose
    // requirement: a failed status write must never cost the document.
    private val statusActivities: DocumentActivities = Workflow.newActivityStub(
        DocumentActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(1))
                    .setBackoffCoefficient(1.5)
                    .setMaximumInterval(Duration.ofMinutes(1))
                    .setMaximumAttempts(5)
                    .build()
            )
            .build()
    )

    // Docling async conversion: THREE short activities, no blocking calls.
    // submit (ms) / poll (ms) / fetch (ms) — a short start-to-close, default
    // TRY_CANCEL: cancellation lands between steps and at the durable timer
    // in the poll loop. The 45s wait between polls is a Workflow.sleep —
    // a durable timer, so cancel arrives promptly and history stays tiny.
    private val extractActivities: DocumentActivities = Workflow.newActivityStub(
        DocumentActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(60))
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(2))
                    .setBackoffCoefficient(2.0)
                    .setMaximumInterval(Duration.ofMinutes(10))
                    .setMaximumAttempts(20)
                    .setDoNotRetry(*arrayOf("NonRetryableDocumentException"))
                    .build()
            )
            .build()
    )

    // Local ONNX embedding: slow, memory-heavy on first download.
    // Heartbeat timeout accommodates per-batch cold-start latency (model load)
    // on slow hosts; batching + heartbeat details give resume-on-retry.
    private val embedActivities: DocumentActivities = Workflow.newActivityStub(
        DocumentActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(30))
            .setHeartbeatTimeout(Duration.ofMinutes(5))
            // TRY_CANCEL (explicit): the embed activity heartbeats per batch,
            // so cancellation IS delivered; the activity catches it, cleans up,
            // and rethrows so its execution closes cancelled.
            .setCancellationType(ActivityCancellationType.TRY_CANCEL)
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(5))
                    .setBackoffCoefficient(2.0)
                    .setMaximumInterval(Duration.ofMinutes(10))
                    .setMaximumAttempts(5)
                    .build()
            )
            .build()
    )

    // Single write: short transaction.
    private val indexActivities: DocumentActivities = Workflow.newActivityStub(
        DocumentActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(60))
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(1))
                    .setBackoffCoefficient(1.5)
                    .setMaximumInterval(Duration.ofMinutes(10))
                    .setMaximumAttempts(8)
                    .setDoNotRetry(*arrayOf("NonRetryableDocumentException"))
                    .build()
            )
            .build()
    )

    // Compensation: idempotent object deletion, short + fast-retry so cleanup
    // always lands; failing it must not prevent the workflow from closing.
    private val cleanupActivities: DocumentActivities = Workflow.newActivityStub(
        DocumentActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(1))
                    .setBackoffCoefficient(2.0)
                    .setMaximumInterval(Duration.ofMinutes(1))
                    .setMaximumAttempts(5)
                    .build()
            )
            .build()
    )

    private var uploadId: String = request.hash
    private var stage: String = "STARTED"
    private var cancelled: Boolean = false

    override fun processDocument(request: DocumentProcessRequest) {
        this.uploadId = request.hash
        ensureLedger(request)
        publish("PROCESSING", null)
        logger.info("Processing upload {} (mime={}, {} chars title)", request.hash, request.mimeType, request.title.length)

        // Saga best practices: register BEFORE any forward activity (the
        // compensation must handle the never-executed case - it does, deletes
        // are idempotent); run compensations even if one fails
        // (continueWithError) so a single flaky cleanup never orphans the rest;
        // keep payloads tiny (refs only); re-throw the original failure after.
        val compensations = Saga(Saga.Options.Builder().setContinueWithError(true).build())
        try {
            // Registered before any work: deletes the upload object + scratch.
            compensations.addCompensation(
                { cleanupActivities.cleanupFailedUpload(request.objectKey, request.hash) }
            )

            // ---- Docling async conversion: submit -> poll(45s timer) -> fetch.
            logger.info("Submitting docling conversion for upload {}", request.hash)
            publish("EXTRACTING", null)
            var taskId = extractActivities.submitDoclingConvert(request)
            var attempts = 0
            while (true) {
                checkCancelled()
                val poll = extractActivities.pollDocling(taskId)
                when (poll.status) {
                    DoclingTaskStatus.SUCCESS -> break
                    DoclingTaskStatus.FAILURE -> throw NonRetryableDocumentException(
                        "docling conversion failed for task $taskId: ${poll.errorMessage}"
                    )
                    // Task vanished (server restart / result GC'd): resubmit the file.
                    null -> {
                        if (attempts >= MAX_RESUBMITS) {
                            throw ApplicationFailure.newFailure(
                                "docling task $taskId kept vanishing (server unstable)", "DoclingUnstable"
                            )
                        }
                        attempts++
                        logger.warn("Docling task {} vanished; resubmitting (attempt {})", taskId, attempts)
                        taskId = extractActivities.submitDoclingConvert(request)
                    }
                    else -> Workflow.sleep(Duration.ofSeconds(DoclingClient.POLL_INTERVAL_SECONDS))
                }
            }
            checkCancelled()
            val extracted = extractActivities.fetchDoclingResult(request, taskId)

            logger.info("Embedding {} chunks for upload {}", extracted.chunkCount, request.hash)
            publish("EMBEDDING", null)
            updateLedger(ProcessingStage.EMBEDDING, jobSpec = request)
            // Soft-fail: exhausted embedding retries degrade to title/body text
            // search (no vectors) instead of failing the upload. Cancellation
            // failures must NOT be swallowed here - let them propagate so the
            // workflow reacts (compensates/closes) instead of "degrading".
            val embeddings = try {
                embedActivities.embedChunks(request.hash, extracted)
            } catch (e: io.temporal.failure.CanceledFailure) {
                throw e
            } catch (e: Exception) {
                logger.warn("Embedding failed after retries for {}, continuing text-only", request.hash, e)
                com.kiss.backend.temporal.activity.EmbeddingRef(null, 0)
            }
            checkCancelled()

            logger.info("Finalizing document for upload {}", request.hash)
            publish("INDEXING", null)
            updateLedger(ProcessingStage.INDEXING, jobSpec = request)
            val documentId = indexActivities.finalizeDocument(request, extracted, embeddings)

            logger.info("Completing upload {} as document {}", request.hash, documentId)
            publish("COMPLETED", null, documentId = documentId)
            updateLedger(ProcessingStage.COMPLETED, documentId = documentId)
            logger.info("Upload {} processed successfully (document {})", request.hash, documentId)
        } catch (e: TemporalFailure) {
            // Permanent failure or cancellation: classify (typed), publish,
            // write ledger + compensate in a DETACHED scope (a cancelled root
            // scope would cancel both before they run — the MCP-documented
            // gotcha), then rethrow so the execution closes with the original
            // failure (never a workflow-task retry loop).
            val (finalStage, errorCode, message) = FailureClassifier.failureOf(e)
            logger.error("Processing {} for upload {}: {}", finalStage, request.hash, message)
            publish(finalStage.name, message)
            try {
                Workflow.newDetachedCancellationScope {
                    compensations.compensate()
                    updateLedger(finalStage, errorCode, message)
                }.run()
            } catch (compensationFailure: Exception) {
                // Best practice: log compensation failures and continue; the
                // reconciler sweep is the backstop for anything left behind.
                logger.warn("Compensation/ledger failed for upload {}: {}", request.hash, compensationFailure.message)
            }
            throw e
        }
    }

    /** Seeds the ledger row at (re)start, carrying the full job spec for audit/retry. */
    private fun ensureLedger(request: DocumentProcessRequest) {
        try {
            statusActivities.updateProcessingStatus(
                request.hash, ProcessingStage.PROCESSING, jobSpec = request
            )
        } catch (e: Exception) {
            logger.warn("Ledger seed failed for upload {}: {}", request.hash, e.message)
        }
    }

    /** Ledger milestone/terminal write, best-effort: must never mask the outcome. */
    private fun updateLedger(
        stage: ProcessingStage,
        errorCode: ProcessingErrorCode? = null,
        message: String? = null,
        documentId: Long? = null,
        jobSpec: DocumentProcessRequest? = null
    ) {
        try {
            statusActivities.updateProcessingStatus(uploadId, stage, errorCode, message, documentId, jobSpec)
        } catch (e: Exception) {
            logger.warn("Ledger write {} failed for upload {}: {}", stage, uploadId, e.message)
        }
    }

    private fun checkCancelled() {
        if (cancelled) {
            logger.warn("Cancellation requested for upload {} at stage {}", uploadId, stage)
            // Must be a Temporal failure: a plain exception would retry the
            // workflow task indefinitely (stuck-open execution).
            throw ApplicationFailure.newFailure("Upload cancelled by user", "UploadCancelled")
        }
    }

    private fun publish(stageName: String, message: String?, documentId: Long? = null) {
        this.stage = stageName
        statusTopic.publish(DocumentProgressEvent(this.uploadId, stageName, message, documentId))
    }

    override fun getStatus(): DocumentProgress = DocumentProgress(uploadId = uploadId, stage = stage)

    override fun cancel() {
        cancelled = true
    }

    companion object {
        const val WORKFLOW_ID_PREFIX = "doc-"
        const val STATUS_TOPIC = "status"
        const val MAX_RESUBMITS = 3

        /** Execution-timeout bound: a wedged execution becomes a real TIMED_OUT. */
        val EXECUTION_TIMEOUT: Duration = Duration.ofHours(3)
    }
}