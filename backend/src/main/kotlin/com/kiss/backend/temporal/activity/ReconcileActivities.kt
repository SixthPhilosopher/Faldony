package com.kiss.backend.temporal.activity

import com.kiss.backend.model.entity.ProcessingErrorCode
import com.kiss.backend.model.entity.ProcessingLedger
import com.kiss.backend.model.entity.ProcessingStage
import com.kiss.backend.repository.CollectionDocumentAssociationRepository
import com.kiss.backend.repository.DocumentChunkRepository
import com.kiss.backend.repository.DocumentRepository
import com.kiss.backend.repository.ProcessingLedgerRepository
import com.kiss.backend.service.DocumentWorkflowService
import com.kiss.backend.service.LocalStorageService
import com.kiss.backend.temporal.workflow.DocumentProcessingWorkflowImpl.Companion.WORKFLOW_ID_PREFIX
import com.kiss.backend.temporal.workflow.EmbeddingBackfillWorkflow
import com.google.protobuf.ByteString
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.activity.ActivityInterface
import io.temporal.api.filter.v1.WorkflowTypeFilter
import io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsRequest
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowExecutionAlreadyStarted
import io.temporal.client.WorkflowNotFoundException
import io.temporal.client.WorkflowOptions
import io.temporal.spring.boot.ActivityImpl
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant

@ActivityInterface
interface ReconcileActivities {

    /**
     * Single reconciliation pass over side effects that escaped their normal
     * lifecycle (crash windows, forced terminates):
     *  1. delete originals older than [grace] that are neither a completed
     *     document nor backed by a running processing workflow;
     *  2. delete claim-check scratch older than [grace];
     *  3. terminate + clean workflows still open longer than [staleAfter];
     *  4. delete document rows whose stored object is gone (zombie rows);
     *  5. backfill embeddings for degraded documents (embed-<docId> workflow);
     *  6. HEAL the ledger: classify stuck rows, auto-retry TRANSIENT failures,
     *     write proper terminal states for closed-without-terminal rows.
     */
    fun reconcile(grace: Duration, staleAfter: Duration)
}

/**
 * Bean name kept as `reconcileActivitiesImpl`; auto-discovered by the Temporal
 * worker (workers-auto-discovery + @ActivityImpl).
 *
 * The ledger pass is a HEALER, not a patcher: it reads the typed
 * stage/errorCode that FailureClassifier wrote, health-gates docling, and
 * only auto-restarts transient failures (bounded by retryCount). Permanent
 * failures and cancellations are the user's job via `POST /processes/{id}/retry`.
 */
@Component("reconcileActivitiesImpl")
@ActivityImpl(workers = ["faldony-worker"])
class ReconcileActivitiesImpl(
    private val storageService: LocalStorageService,
    private val documentRepository: DocumentRepository,
    private val chunkRepository: DocumentChunkRepository,
    private val associationRepository: CollectionDocumentAssociationRepository,
    private val ledgerRepository: ProcessingLedgerRepository,
    private val workflowClient: WorkflowClient,
    private val transactionManager: PlatformTransactionManager,
    @org.springframework.beans.factory.annotation.Value("\${faldony.docling.base-url}")
    private val doclingBaseUrl: String,
    private val objectMapper: com.fasterxml.jackson.databind.ObjectMapper = com.fasterxml.jackson.databind.ObjectMapper(),
    private val restClientBuilderProvider: org.springframework.beans.factory.ObjectProvider<org.springframework.web.client.RestClient.Builder>,
) : ReconcileActivities {

    private val logger = LoggerFactory.getLogger(ReconcileActivitiesImpl::class.java)
    private val writeTx = TransactionTemplate(transactionManager)

    // Uses the shared RestClient.Builder bean (HTTP/1.1-pinned JDK client):
    // a bare RestClient.builder() defaults to HTTP/2, which fires an h2c
    // upgrade at docling-serve (uvicorn rejects it with "Unsupported upgrade
    // request") on every sweep.
    private val healthClient: org.springframework.web.client.RestClient =
        restClientBuilderProvider.getObject().baseUrl(doclingBaseUrl).build()

    override fun reconcile(grace: Duration, staleAfter: Duration) {
        val now = Instant.now()
        // Hash-only projection: full entities are not needed for presence checks
        // and do not waste the sweep on eager-relation loads.
        val completedHashes = documentRepository.findAllFileHashes().toSet()
        val openWorkflows = openProcessingWorkflows()
        logger.info("Reconcile sweep: {} complete docs, {} open workflows", completedHashes.size, openWorkflows.size)

        // 1. Original files: no completed row AND no running workflow AND old enough.
        storageService.listPrefix("${LocalStorageService.DOCUMENTS_PREFIX}/").forEach { file ->
            val hash = file.key.removePrefix("${LocalStorageService.DOCUMENTS_PREFIX}/").take(64)
            if (hash.length != 64) return@forEach
            if (completedHashes.contains(hash) || openWorkflows.containsKey(hash)) return@forEach
            if (file.lastModified.isBefore(now.minus(grace))) {
                logger.warn("Deleting orphan upload object {} (no document, no workflow)", file.key)
                storageService.deleteObject(file.key)
            }
        }

        // 2. Claim-check scratch older than grace -> GC.
        storageService.listPrefix("${LocalStorageService.SCRATCH_PREFIX}/").forEach { file ->
            if (file.lastModified.isBefore(now.minus(grace))) {
                logger.warn("Deleting stale scratch file {}", file.key)
                storageService.deleteObject(file.key)
            }
        }

        // 3. Stale open workflows -> terminate and clean their artifacts.
        openWorkflows.forEach { (hash, startedAt) ->
            if (startedAt.isBefore(now.minus(staleAfter))) {
                logger.warn("Terminating stale processing workflow doc-{} (started {})", hash, startedAt)
                try {
                    workflowClient.newUntypedWorkflowStub(WORKFLOW_ID_PREFIX + hash)
                        .terminate("reconcile: stale processing")
                } catch (e: WorkflowNotFoundException) {
                    // already closed
                }
                storageService.deleteObjectsByHashPrefix(hash)
                storageService.deleteScratch(hash)
            }
        }

        // 4. Zombie rows: a document row whose object is gone (delete/cancel
        //    races, disk loss). The re-upload dedup short-circuit trusts the
        //    row, so these must never linger.
        val existingKeys = storageService.listPrefix("${LocalStorageService.DOCUMENTS_PREFIX}/")
            .map { it.key }.toSet()
        deleteZombieDocuments(existingKeys)

        // 5. Degraded documents (chunks without vectors): fire the idempotent
        //    embed-<docId> backfill workflow. Failure closes the workflow; the
        //    NEXT sweep re-arms it (bounded patience, no eternal timers).
        val degraded = chunkRepository.findDocumentIdsWithMissingEmbeddings()
        logger.debug("Reconcile sweep: {} documents with missing embeddings", degraded.size)
        degraded.forEach { docId ->
            startEmbeddingBackfill(docId)
        }

        // 6. Ledger healing: classify stuck rows, auto-retry transient failures
        //    (health-gated), and write REAL terminal states for rows whose
        //    execution closed without a terminal write.
        healLedger(now, grace, openWorkflows)

        logger.debug("Reconcile sweep finished in {} ms", Duration.between(now, Instant.now()).toMillis())
    }

    /**
     * The triage pass. ONLY transient codes + under the retry cap are
     * auto-restarted; every restart goes through startOrAttach (so a still
     * open flow is attached, never double-started) and through a docling
     * health gate (a down server = defer to the next sweep = natural backoff).
     */
    private fun healLedger(now: Instant, grace: Duration, openWorkflows: Map<String, Instant>) {
        val terminalCutoff = now.minus(LEDGER_TERMINAL_RETENTION)
        val staleCutoff = now.minus(grace)
        val healed = mutableListOf<ProcessingLedger>()
        writeTx.execute {
            // GC completed/terminal rows past retention.
            ledgerRepository.deleteByStageInAndUpdatedAtBefore(
                listOf(ProcessingStage.COMPLETED, ProcessingStage.FAILED,
                    ProcessingStage.CANCELLED, ProcessingStage.TIMED_OUT),
                terminalCutoff
            )

            // Rows stuck in a non-terminal stage with NO open workflow: the
            // execution closed without a terminal write -> classify the close
            // status and record the REAL terminal state.
            ledgerRepository.findByStageInAndUpdatedAtBefore(
                listOf(ProcessingStage.PROCESSING, ProcessingStage.EXTRACTING,
                    ProcessingStage.EMBEDDING, ProcessingStage.INDEXING, ProcessingStage.STARTED),
                staleCutoff
            ).forEach { row ->
                if (!openWorkflows.containsKey(row.uploadId)) {
                    val closed = describeStatus(row.uploadId)
                    val (stage, code) = if (closed != null) {
                        com.kiss.backend.util.FailureClassifier.ofClosedStatus(closed)
                    } else {
                        ProcessingStage.FAILED to ProcessingErrorCode.WORKER
                    }
                    logger.warn("Ledger row {} closed without terminal write -> {}", row.uploadId, stage)
                    row.stage = stage
                    row.errorCode = code
                    row.error = "Swept: execution closed, terminal state recovered"
                    healed += row
                }
            }
            healed.forEach(ledgerRepository::save)
            null
        }

        // Out-of-transaction restarts: every transient FAILED row under the cap.
        autoRetryTransientFailures()
    }

    /** Restarts transient FAILED uploads (health-gated, capped). */
    private fun autoRetryTransientFailures() {
        if (!doclingHealthy()) {
            logger.info("Docling unhealthy — deferring transient-failure retries to the next sweep")
            return
        }
        val candidates = ledgerRepository.findByStageAndErrorCodeIn(
            ProcessingStage.FAILED,
            ProcessingErrorCode.entries.filter { it.transient && it != ProcessingErrorCode.UNKNOWN }
        )
        if (candidates.isEmpty()) {
            logger.debug("No transient failures to auto-retry this sweep")
            return
        }
        logger.info("Sweeping {} transiently-failed uploads for auto-retry", candidates.size)
        candidates.forEach { row ->
            val maxed = (row.retryCount ?: 0) >= MAX_AUTO_RETRIES
            if (maxed) {
                logger.info("Upload {} exceeded auto-retry cap ({}), leaving to user retry", row.uploadId, row.retryCount)
                return@forEach
            }
            val jobSpec = row.jobSpec?.let { json ->
                runCatching { objectMapper.readValue(json, com.kiss.backend.temporal.workflow.DocumentProcessRequest::class.java) }
                    .getOrNull()
            } ?: run {
                logger.warn("Upload {} has no job spec, skipping auto-retry", row.uploadId)
                return@forEach
            }
            logger.info("Auto-retrying transient failure for upload {} (attempt {})",
                row.uploadId, (row.retryCount ?: 0) + 1)
            try {
                startOrAttachProcessing(jobSpec)
            } catch (e: Exception) {
                logger.warn("Auto-retry start failed for {}: {}", row.uploadId, e.message)
            }
        }
    }

    /** WorkflowExecutionStatus of the execution for this upload, or null when absent. */
    private fun describeStatus(uploadId: String): io.temporal.api.enums.v1.WorkflowExecutionStatus? =
        runCatching {
            workflowClient.workflowServiceStubs.blockingStub()
                .describeWorkflowExecution(
                    io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest.newBuilder()
                        .setNamespace(workflowClient.options.namespace)
                        .setExecution(
                            io.temporal.api.common.v1.WorkflowExecution.newBuilder()
                                .setWorkflowId(WORKFLOW_ID_PREFIX + uploadId).build()
                        )
                        .build()
                )
                .workflowExecutionInfo
                ?.status
        }.getOrNull()

    private fun startOrAttachProcessing(request: com.kiss.backend.temporal.workflow.DocumentProcessRequest) {
        val options = WorkflowOptions.newBuilder()
            .setWorkflowId(WORKFLOW_ID_PREFIX + request.hash)
            .setTaskQueue(DocumentWorkflowService.TASK_QUEUE)
            .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
            .setWorkflowIdConflictPolicy(
                io.temporal.api.enums.v1.WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
            .build()
        val stub = workflowClient.newWorkflowStub(
            com.kiss.backend.temporal.workflow.DocumentProcessingWorkflow::class.java, options)
        WorkflowClient.start(stub::processDocument, request)
    }

    /** Lightweight docling health gate (downtime = defer, no hard failure). */
    private fun doclingHealthy(): Boolean =
        runCatching {
            healthClient.get().uri("/ready").retrieve().toBodilessEntity().statusCode.is2xxSuccessful
        }.getOrDefault(false)

    /** Starts the idempotent embed-<docId> backfill (attach when one is already open). */
    private fun startEmbeddingBackfill(documentId: Long) {
        logger.info("Requesting embedding backfill for document {}", documentId)
        val options = WorkflowOptions.newBuilder()
            .setWorkflowId("embed-$documentId")
            .setTaskQueue(DocumentWorkflowService.TASK_QUEUE)
            .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
            .build()
        val stub = workflowClient.newWorkflowStub(EmbeddingBackfillWorkflow::class.java, options)
        try {
            WorkflowClient.start(stub::embed, documentId)
            logger.info("Started embedding backfill for document {}", documentId)
        } catch (e: WorkflowExecutionAlreadyStarted) {
            // already running (or a completed run is still within retention) — no-op
        }
    }

    /** Deletes document rows whose stored object is missing (row-level heal, own tx). */
    private fun deleteZombieDocuments(existingKeys: Set<String>) {
        writeTx.execute {
            documentRepository.findByObjectKeyNotIn(existingKeys).forEach { doc ->
                logger.warn("Deleting zombie document {} (object {} missing)", doc.id, doc.objectKey)
                chunkRepository.deleteChunksForDocument(doc.id!!)
                associationRepository.deleteAll(associationRepository.findByDocument(doc))
                documentRepository.delete(doc)
            }
            null
        }
    }

    private fun openProcessingWorkflows(): Map<String, Instant> {
        val result = mutableMapOf<String, Instant>()
        val stub = workflowClient.workflowServiceStubs.blockingStub()
        val typeFilter = WorkflowTypeFilter.newBuilder().setName("DocumentProcessingWorkflow").build()
        var nextPage: ByteString? = ByteString.EMPTY

        do {
            val request = ListOpenWorkflowExecutionsRequest.newBuilder()
                .setNamespace(workflowClient.options.namespace)
                .setTypeFilter(typeFilter)
                .setMaximumPageSize(100)
            if (nextPage != null && nextPage.size() > 0) request.nextPageToken = nextPage
            val response = stub.listOpenWorkflowExecutions(request.build())
            response.executionsList.forEach { info ->
                val hash = info.execution.workflowId.removePrefix(WORKFLOW_ID_PREFIX)
                val startedAt = runCatching {
                    info.startTime?.let { Instant.ofEpochSecond(it.seconds, it.nanos.toLong()) }
                }.getOrNull() ?: Instant.EPOCH
                if (hash.isNotEmpty()) result[hash] = startedAt
            }
            nextPage = response.nextPageToken
        } while (nextPage != null && nextPage.size() > 0)

        return result
    }

    companion object {
        /** Audit history is kept for terminal ledger rows for one month. */
        val LEDGER_TERMINAL_RETENTION: Duration = Duration.ofDays(30)

        /** Auto-retries a transient failure at most this many times; user retry resets. */
        const val MAX_AUTO_RETRIES = 3
    }
}