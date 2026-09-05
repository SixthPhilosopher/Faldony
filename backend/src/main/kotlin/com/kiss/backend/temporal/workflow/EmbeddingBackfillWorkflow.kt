package com.kiss.backend.temporal.workflow

import com.kiss.backend.temporal.activity.DocumentActivities
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.spring.boot.WorkflowImpl
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.time.Duration

@WorkflowInterface
interface EmbeddingBackfillWorkflow {
    @WorkflowMethod
    fun embed(documentId: Long)
}

/**
 * Background embedding repair for degraded documents (no vectors in some/all
 * chunks). Started by the reconciler sweep — never by user endpoints.
 *
 * Idempotent by construction: the activity embeds ONLY chunks whose vector is
 * NULL and the per-chunk UPDATE is guarded on NULL, so restarts are harmless
 * and a completed run needs never re-run. Bounded retries (capped interval);
 * on exhaustion the workflow closes and the next sweep re-arms it.
 */
@WorkflowImpl(workers = ["faldony-worker"])
class EmbeddingBackfillWorkflowImpl : EmbeddingBackfillWorkflow {

    private val logger = Workflow.getLogger(EmbeddingBackfillWorkflowImpl::class.java)

    private val activities: DocumentActivities = Workflow.newActivityStub(
        DocumentActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(30))
            .setHeartbeatTimeout(Duration.ofMinutes(5))
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofMinutes(1))
                    .setBackoffCoefficient(2.0)
                    .setMaximumInterval(Duration.ofMinutes(30))
                    .setMaximumAttempts(10)
                    .setDoNotRetry(*arrayOf("NonRetryableDocumentException"))
                    .build()
            )
            .build()
    )

    override fun embed(documentId: Long) {
        logger.info("Embedding backfill starting for document {}", documentId)
        try {
            val embedded = activities.embedMissingChunks(documentId)
            logger.info("Embedding backfill completed for document {} ({} chunks)", documentId, embedded)
        } catch (e: io.temporal.failure.TemporalFailure) {
            logger.error("Embedding backfill failed for document {}: {}", documentId, e.message)
            throw e
        }
    }
}