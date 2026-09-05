package com.kiss.backend.temporal.stream

import com.kiss.backend.temporal.workflow.DocumentProcessingWorkflowImpl.Companion.WORKFLOW_ID_PREFIX
import com.kiss.backend.temporal.workflow.DocumentProgressEvent
import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest
import io.temporal.client.WorkflowClient
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.workflowstreams.SubscribeOptions
import io.temporal.workflowstreams.WorkflowStreamClient
import io.temporal.workflowstreams.WorkflowStreamItem
import io.temporal.workflowstreams.WorkflowStreamListener
import io.temporal.workflowstreams.WorkflowStreamSubscriptionHandle
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.CompletionStage

/**
 * Bridges an upload's Temporal Workflow Stream (`doc-<hash>`) to Server-Sent
 * Events.
 *
 * The stream lives only while the workflow runs; on close (or when the
 * subscriber is late and the stream is already gone) we fall back to the
 * workflow execution close status from Temporal, so the client ALWAYS receives
 * a terminal [DocumentProgressEvent].
 */
@Component
class WorkflowJobStreamer(
    private val workflowClient: WorkflowClient
) {

    private val logger = LoggerFactory.getLogger(WorkflowJobStreamer::class.java)

    private val converter = DefaultDataConverter.STANDARD_INSTANCE

    fun streamFor(uploadId: String, emitter: SseEmitter) {
        val workflowId = WORKFLOW_ID_PREFIX + uploadId

        var handle: WorkflowStreamSubscriptionHandle? = null
        try {
            val streamClient = WorkflowStreamClient.newInstance(workflowClient, workflowId)
            handle = streamClient.subscribe(
                SubscribeOptions.newBuilder().setTopics(STATUS_TOPIC).build(),
                object : WorkflowStreamListener {
                    override fun onNext(item: WorkflowStreamItem): CompletionStage<Void>? {
                        try {
                            val event = converter.fromPayload(
                                item.payload,
                                DocumentProgressEvent::class.java,
                                DocumentProgressEvent::class.java
                            )
                            emitter.send(SseEmitter.event().name("task").data(event))
                        } catch (ex: Exception) {
                            // SSE connection closed or serialization failed
                            logger.debug("SSE write failed for {}", workflowId, ex)
                            handle?.close()
                            emitter.complete()
                        }
                        return null
                    }

                    override fun onCompleted() {
                        sendTerminalStatus(uploadId, emitter)
                    }
                }
            )

            emitter.onCompletion { handle?.close(); streamClient.close() }
            emitter.onTimeout { handle?.close(); streamClient.close() }
            emitter.onError { handle?.close(); streamClient.close() }
        } catch (ex: Exception) {
            // Workflow absent or already terminal before subscribing (late
            // subscriber): fall back to the durable close status instead of
            // closing silently.
            logger.debug("Cannot subscribe to workflow stream {} : {}", workflowId, ex.message)
            sendTerminalStatus(uploadId, emitter)
        }
    }

    /** Terminal event from the workflow execution close status (no processing
     * table involved): COMPLETED, FAILED, CANCELLED, TIMED_OUT or UNKNOWN. */
    private fun sendTerminalStatus(uploadId: String, emitter: SseEmitter) {
        try {
            val status = closeStatus(uploadId) ?: "UNKNOWN"
            emitter.send(SseEmitter.event().name("task").data(DocumentProgressEvent(uploadId, status, null)))
        } catch (ex: Exception) {
            logger.debug("Terminal SSE write failed for upload {}", uploadId, ex)
        } finally {
            emitter.complete()
        }
    }

    private fun closeStatus(uploadId: String): String? {
        val workflowId = WORKFLOW_ID_PREFIX + uploadId
        val response = workflowClient.workflowServiceStubs.blockingStub()
            .describeWorkflowExecution(DescribeWorkflowExecutionRequest.newBuilder()
                .setNamespace(workflowClient.options.namespace)
                .setExecution(WorkflowExecution.newBuilder().setWorkflowId(workflowId).build())
                .build())
        // Shared classifier: TERMINATED == aborted == CANCELLED so the SSE
        // terminal event matches the ledger/polling vocabulary.
        return com.kiss.backend.util.FailureClassifier.ofClosedStatus(response.workflowExecutionInfo.status).first.name
    }

    companion object {
        const val STATUS_TOPIC = "status"
    }
}