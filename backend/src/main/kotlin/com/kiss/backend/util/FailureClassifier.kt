package com.kiss.backend.util

import com.kiss.backend.model.entity.ProcessingErrorCode
import com.kiss.backend.model.entity.ProcessingStage
import io.temporal.api.enums.v1.WorkflowExecutionStatus
import io.temporal.failure.ActivityFailure
import io.temporal.failure.ApplicationFailure
import io.temporal.failure.CanceledFailure
import io.temporal.failure.TimeoutFailure

/**
 * Maps any failure/close-status to the typed (stage, code, message) terminal
 * that the ledger, DTOs and reconciler all consume. Replaces the ad-hoc
 * string literals + CloseStatusMapper; ONE classifier everywhere.
 */
object FailureClassifier {

    /** Terminal outcome of a workflow's catch block. */
    fun failureOf(e: Throwable): Triple<ProcessingStage, ProcessingErrorCode, String> {
        // Cancellation (both a real cancel request and the signal flag) is CANCELLED.
        if (e is CanceledFailure || e.message?.startsWith("Upload cancelled") == true) {
            return Triple(ProcessingStage.CANCELLED, ProcessingErrorCode.CANCELLED, e.message ?: "cancelled")
        }

        // A workflow-side timeout (execution/start-to-close) that closed the execution.
        if (e is TimeoutFailure) {
            return Triple(ProcessingStage.TIMED_OUT, ProcessingErrorCode.TIMEOUT, e.message ?: "workflow timeout")
        }

        // Unwrap activity-level failures to inspect their cause.
        val cause = (e as? ActivityFailure)?.cause
        if (cause is TimeoutFailure) {
            val code = if (cause.timeoutType == io.temporal.api.enums.v1.TimeoutType.TIMEOUT_TYPE_HEARTBEAT) {
                ProcessingErrorCode.WORKER
            } else {
                ProcessingErrorCode.TIMEOUT
            }
            return Triple(ProcessingStage.TIMED_OUT, code, cause.message ?: "activity timeout")
        }
        if (cause is CanceledFailure) {
            return Triple(ProcessingStage.CANCELLED, ProcessingErrorCode.CANCELLED, cause.message ?: "cancelled")
        }

        // ApplicationFailure => permanent: distinguish bad-file vs config by type/message.
        if (cause is ApplicationFailure) {
            val raw = cause.type.ifBlank { cause.message.orEmpty() }
            return when {
                raw.contains("NonRetryableDocumentException", ignoreCase = true) ||
                    cause.message?.contains("docling-serve rejected", ignoreCase = true) == true ->
                    Triple(ProcessingStage.FAILED, ProcessingErrorCode.BAD_FILE, cause.message ?: "bad input")
                raw.contains("Embedding", ignoreCase = true) ->
                    Triple(ProcessingStage.FAILED, ProcessingErrorCode.EMBEDDING_UNAVAILABLE, cause.message ?: "embedding unavailable")
                else ->
                    Triple(ProcessingStage.FAILED, ProcessingErrorCode.CONFIG, cause.message ?: "application failure")
            }
        }

        // Everything transient/unknown a worker crash or an unclassified failure.
        return Triple(ProcessingStage.FAILED, ProcessingErrorCode.UNKNOWN, e.message ?: "unclassified failure")
    }

    /** Terminal outcome derived from a closed-workflow visibility status. */
    fun ofClosedStatus(status: WorkflowExecutionStatus): Pair<ProcessingStage, ProcessingErrorCode?> = when (status) {
        WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED -> ProcessingStage.COMPLETED to null
        WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_FAILED -> ProcessingStage.FAILED to ProcessingErrorCode.UNKNOWN
        WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_CANCELED -> ProcessingStage.CANCELLED to ProcessingErrorCode.CANCELLED
        WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TERMINATED -> ProcessingStage.CANCELLED to ProcessingErrorCode.CANCELLED
        WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TIMED_OUT -> ProcessingStage.TIMED_OUT to ProcessingErrorCode.TIMEOUT
        WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING -> ProcessingStage.PROCESSING to null
        else -> ProcessingStage.FAILED to ProcessingErrorCode.UNKNOWN
    }
}