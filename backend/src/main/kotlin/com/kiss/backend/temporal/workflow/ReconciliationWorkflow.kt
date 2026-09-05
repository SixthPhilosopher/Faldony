package com.kiss.backend.temporal.workflow

import com.kiss.backend.temporal.activity.ReconcileActivities
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.spring.boot.WorkflowImpl
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.time.Duration

@WorkflowInterface
interface ReconciliationWorkflow {
    @WorkflowMethod
    fun run()
}

/**
 * Periodically reconciles upload side effects (orphan objects, stale open
 * workflows). Run by a Temporal Schedule (`faldony-reconcile`, every 5 min,
 * overlap skip) created on startup by the backend.
 */
@WorkflowImpl(workers = ["faldony-worker"])
class ReconciliationWorkflowImpl : ReconciliationWorkflow {

    private val logger = Workflow.getLogger(ReconciliationWorkflowImpl::class.java)

    private val activities: ReconcileActivities = Workflow.newActivityStub(
        ReconcileActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(10))
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setMaximumAttempts(3)
                    .build()
            )
            .build()
    )

    override fun run() {
        val started = Workflow.currentTimeMillis()
        logger.info("Reconciliation sweep started (grace={}, staleAfter={})", GRACE_PERIOD, STALE_AFTER)
        try {
            activities.reconcile(GRACE_PERIOD, STALE_AFTER)
            logger.info("Reconciliation sweep completed in {} ms", Workflow.currentTimeMillis() - started)
        } catch (e: Exception) {
            logger.error("Reconciliation sweep failed after {} ms: {}", Workflow.currentTimeMillis() - started, e.message)
            throw e
        }
    }

    companion object {
        // An upload with no document and no running workflow is garbage after this.
        val GRACE_PERIOD: Duration = Duration.ofMinutes(30)

        // A processing workflow open this long is considered wedged (docling
        // retries are capped far below this).
        val STALE_AFTER: Duration = Duration.ofHours(48)

        const val SCHEDULE_ID = "faldony-reconcile"
    }
}