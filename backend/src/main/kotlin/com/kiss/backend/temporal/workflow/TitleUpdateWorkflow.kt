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
interface TitleUpdateWorkflow {
    @WorkflowMethod
    fun updateTitle(documentId: Long, newTitle: String)
}

/**
 * Durable title-only metadata change: sets the title, re-embeds the TITLE chunk
 * and prunes stale dictionary entries — all via a single activity.
 */
@WorkflowImpl(workers = ["faldony-worker"])
class TitleUpdateWorkflowImpl : TitleUpdateWorkflow {

    private val logger = Workflow.getLogger(TitleUpdateWorkflowImpl::class.java)

    private val activities: DocumentActivities = Workflow.newActivityStub(
        DocumentActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(5))
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setMaximumAttempts(5)
                    .setDoNotRetry(*arrayOf("NonRetryableDocumentException"))
                    .build()
            )
            .build()
    )

    override fun updateTitle(documentId: Long, newTitle: String) {
        logger.info("Title update starting for document {} -> '{}'", documentId, newTitle)
        try {
            activities.updateTitleDocument(documentId, newTitle)
        } catch (e: io.temporal.failure.TemporalFailure) {
            logger.error("Title update failed for document {}: {}", documentId, e.message)
            throw e
        }
    }
}