package com.kiss.backend.service

import com.kiss.backend.model.dto.ProcessHistoryDto
import com.kiss.backend.model.dto.ProcessItemDto
import com.kiss.backend.model.dto.ProcessStatusDto
import com.kiss.backend.model.entity.DocumentType
import com.kiss.backend.model.entity.ProcessingErrorCode
import com.kiss.backend.model.entity.ProcessingLedger
import com.kiss.backend.model.entity.ProcessingStage
import com.kiss.backend.repository.ProcessingLedgerRepository
import com.kiss.backend.temporal.workflow.DocumentProgress
import com.kiss.backend.temporal.workflow.DocumentProcessingWorkflowImpl.Companion.WORKFLOW_ID_PREFIX
import com.google.protobuf.ByteString
import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.api.filter.v1.WorkflowTypeFilter
import io.temporal.api.workflow.v1.WorkflowExecutionInfo
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest
import io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsRequest
import io.temporal.client.WorkflowClient
import io.temporal.common.converter.DefaultDataConverter
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Processing queue + durable history, with ONE typed vocabulary
 * ([ProcessingStage]/[ProcessingErrorCode]).
 *
 * Live list stays Temporal open-workflow visibility (correct for in-flight);
 * status resolves LEDGER-first (any stage, live+terminal) with Temporal
 * describe only as the not-found fallback; history is the ledger.
 */
@Service
class ProcessQueueService(
    private val workflowClient: WorkflowClient,
    private val ledgerRepository: ProcessingLedgerRepository
) {

    private val logger = LoggerFactory.getLogger(ProcessQueueService::class.java)
    private val converter = DefaultDataConverter.STANDARD_INSTANCE

    private val namespace: String get() = workflowClient.options.namespace

    private val workflowTypeFilter = WorkflowTypeFilter.newBuilder()
        .setName("DocumentProcessingWorkflow")
        .build()

    /**
     * Lists every open `DocumentProcessingWorkflow` execution (pages joined).
     */
    fun list(): List<ProcessItemDto> {
        val stub = workflowClient.workflowServiceStubs.blockingStub()
        val items = mutableListOf<ProcessItemDto>()
        var nextPage: ByteString? = ByteString.EMPTY

        do {
            val request = ListOpenWorkflowExecutionsRequest.newBuilder()
                .setNamespace(namespace)
                .setTypeFilter(workflowTypeFilter)
                .setMaximumPageSize(100)
            if (nextPage != null && nextPage.size() > 0) request.nextPageToken = nextPage
            val response = stub.listOpenWorkflowExecutions(request.build())
            items += response.executionsList.mapNotNull { toItem(it) }
            nextPage = response.nextPageToken
        } while (nextPage != null && nextPage.size() > 0)

        return items
    }

    /**
     * Ledger-first status: the durable row (live or terminal) wins; temporal
     * describe is only the instant-start fallback when no row exists yet.
     */
    fun status(uploadId: String): ProcessStatusDto {
        ledgerRepository.findByUploadId(uploadId)?.let { ledger ->
            return ProcessStatusDto(
                uploadId = uploadId,
                stage = ledger.stage,
                errorCode = ledger.errorCode,
                message = ledger.error,
                documentId = ledger.documentId
            )
        }

        val workflowId = WORKFLOW_ID_PREFIX + uploadId
        val live = try {
            workflowClient.newUntypedWorkflowStub(workflowId)
                .query("getStatus", DocumentProgress::class.java)
        } catch (e: Exception) {
            null
        }
        if (live != null) {
            val stage = runCatching { ProcessingStage.valueOf(live.stage) }.getOrNull()
                ?: ProcessingStage.PROCESSING
            return ProcessStatusDto(uploadId = uploadId, stage = stage)
        }

        // Neither ledger nor live workflow — describe for a just-started/closed race.
        val closed = try {
            workflowClient.workflowServiceStubs.blockingStub()
                .describeWorkflowExecution(DescribeWorkflowExecutionRequest.newBuilder()
                    .setNamespace(namespace)
                    .setExecution(WorkflowExecution.newBuilder().setWorkflowId(workflowId).build())
                    .build())
                .workflowExecutionInfo
        } catch (e: Exception) {
            null
        }
        return if (closed != null) {
            val (stage, code) = com.kiss.backend.util.FailureClassifier.ofClosedStatus(closed.status)
            ProcessStatusDto(uploadId, stage, code)
        } else {
            ProcessStatusDto(uploadId, ProcessingStage.FAILED, ProcessingErrorCode.UNKNOWN, "not found")
        }
    }

    // ------------------------------------------------------------------ ledger

    /** Durable processing history (FAILED/CANCELLED/TIMED_OUT/COMPLETED included). */
    fun history(limit: Int, offset: Int): List<ProcessHistoryDto> {
        if (limit <= 0) return emptyList()
        val page = ledgerRepository.findAll(
            PageRequest.of(offset / limit, limit, Sort.by(Sort.Direction.DESC, "id"))
        )
        return page.content.map {
            ProcessHistoryDto(
                uploadId = it.uploadId,
                title = it.title,
                type = it.type,
                stage = it.stage,
                errorCode = it.errorCode,
                error = it.error,
                documentId = it.documentId,
                createdAt = it.createdAt,
                updatedAt = it.updatedAt
            )
        }
    }

    /** The durable record for retry-without-reupload (`jobSpec`). */
    fun findLedger(uploadId: String): ProcessingLedger? = ledgerRepository.findByUploadId(uploadId)

    private fun toItem(info: WorkflowExecutionInfo): ProcessItemDto? {
        val uploadId = info.execution.workflowId.removePrefix(WORKFLOW_ID_PREFIX)
        val memo = info.memo.fieldsMap ?: emptyMap()

        val title = memo["title"]?.let { payload ->
            runCatching { converter.fromPayload(payload, String::class.java, String::class.java) }.getOrNull()
        } ?: ""
        val type = memo["type"]?.let { payload ->
            runCatching { converter.fromPayload(payload, String::class.java, String::class.java) }
                .getOrNull()
                ?.let { runCatching { DocumentType.valueOf(it) }.getOrNull() }
        }

        val startedAt = runCatching { info.startTime?.let { Instant.ofEpochSecond(it.seconds, it.nanos.toLong()) } }
            .getOrNull()
            ?: Instant.EPOCH

        return ProcessItemDto(uploadId = uploadId, title = title, type = type, startedAt = startedAt)
    }
}