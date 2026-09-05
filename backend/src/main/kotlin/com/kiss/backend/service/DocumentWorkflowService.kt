package com.kiss.backend.service

import com.kiss.backend.model.entity.DocumentType
import com.kiss.backend.temporal.workflow.DocumentProcessRequest
import com.kiss.backend.temporal.workflow.DocumentProcessingWorkflow
import com.kiss.backend.temporal.workflow.DocumentProcessingWorkflowImpl
import com.kiss.backend.temporal.workflow.DocumentProcessingWorkflowImpl.Companion.WORKFLOW_ID_PREFIX
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy
import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Starts (or attaches to) the extraction workflow for an upload.
 *
 * The workflow id is content-addressed: `doc-<hash>` (business-meaningful ID -
 * Temporal guarantees at most ONE open execution per id, so concurrent duplicate
 * uploads collapse onto a single workflow, and `DocumentService` already
 * short-circuits re-uploads of fully-processed files).
 *
 * WORKFLOW ID REUSE (deliberate, keep the default ALLOW_DUPLICATE):
 * - A FAILED workflow closes, the file stays, and a later re-upload of the same
 *   content must be able to START AGAIN under the same id -> ALLOW_DUPLICATE.
 * - A COMPLETED workflow must never restart: guaranteed by the DB dedup (200
 *   COMPLETED at upload time), not by the reuse policy - because deleteDocument
 *   wipes row + file, after which a re-upload legitimately reprocesses the same
 *   hash under the same id. ALLOW_DUPLICATE_FAILED_ONLY would wrongly block that.
 * Hence the default policy is the correct one here; see Temporal docs on
 * Workflow Id Reuse Policies for the trade-offs.
 */
@Service
class DocumentWorkflowService(
    private val workflowClient: WorkflowClient
) {

    private val logger = LoggerFactory.getLogger(DocumentWorkflowService::class.java)

    /**
     * @return true when a new workflow execution was started, false when a
     * running one with the same id already existed (attach).
     */
    fun startOrAttach(request: DocumentProcessRequest, type: DocumentType): Boolean {
        val options = WorkflowOptions.newBuilder()
            .setWorkflowId(WORKFLOW_ID_PREFIX + request.hash)
            .setTaskQueue(TASK_QUEUE)
            // Content-addressed: a duplicate upload while this hash is OPEN
            // must attach to the existing execution, not error. With
            // USE_EXISTING, `start` never throws; it returns the running
            // execution's RunId instead (see RunId comparison below).
            .setWorkflowIdConflictPolicy(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
            // Wall-clock bound: a truly wedged execution closes as TIMED_OUT
            // (classifier -> ledger -> reconciler can act) instead of staying
            // invisible in PROCESSING forever.
            .setWorkflowExecutionTimeout(DocumentProcessingWorkflowImpl.EXECUTION_TIMEOUT)
            .setMemo(mapOf(
                MEMO_TITLE to request.title,
                MEMO_TYPE to type.name
            ))
            .build()
        val workflow = workflowClient.newWorkflowStub(DocumentProcessingWorkflow::class.java, options)

        val startedRunId = WorkflowClient.start(workflow::processDocument, request).runId
        // Attach detection: if an execution was already open under this id
        // (USE_EXISTING), `start` returned ITS runId — so we report "started"
        // only when the execution now running is the one we just launched.
        val runningNow = runningRunId(request.hash)
        val started = runningNow == null || runningNow == startedRunId
        if (!started) {
            logger.info("Workflow {} already running, attaching", WORKFLOW_ID_PREFIX + request.hash)
        } else {
            logger.info("Started processing workflow for upload {}", request.hash)
        }
        return started
    }

    /** RunId of the currently open RUNNING execution with this workflow id, or null. */
    private fun runningRunId(hash: String): String? {
        val desc = runCatching {
            workflowClient.workflowServiceStubs.blockingStub().describeWorkflowExecution(
                io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest.newBuilder()
                    .setNamespace(workflowClient.options.namespace)
                    .setExecution(WorkflowExecution.newBuilder().setWorkflowId(WORKFLOW_ID_PREFIX + hash).build())
                    .build()
            )
        }.getOrNull() ?: return null
        val info = desc.workflowExecutionInfo
        return if (info != null &&
            info.status == io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING
        ) {
            info.execution.runId
        } else null
    }

    companion object {
        const val TASK_QUEUE = "faldony"

        // Memo keys carried on the workflow record so the Temporal-backed queue
        // can display each in-flight upload without extra lookups.
        const val MEMO_TITLE = "title"
        const val MEMO_TYPE = "type"
    }
}