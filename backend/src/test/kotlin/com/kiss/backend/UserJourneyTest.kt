package com.kiss.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.kiss.backend.controller.DocumentController
import com.kiss.backend.controller.ProcessController
import com.kiss.backend.model.dto.DocumentQuery
import com.kiss.backend.model.dto.DocumentUploadResponseDto
import com.kiss.backend.model.dto.ProcessHistoryDto
import com.kiss.backend.model.entity.DocumentType
import com.kiss.backend.model.entity.ProcessingStage
import com.kiss.backend.model.entity.ProcessingLedger
import com.kiss.backend.repository.DocumentRepository
import com.kiss.backend.service.DocumentService
import com.kiss.backend.service.DocumentWorkflowService
import com.kiss.backend.service.LocalStorageService
import com.kiss.backend.service.ProcessQueueService
import com.kiss.backend.temporal.stream.WorkflowJobStreamer
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowStub
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.springframework.core.io.FileSystemResource
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.nio.file.Files
import java.time.Instant
import org.springframework.web.multipart.MultipartFile
import java.util.concurrent.Executor

/**
 * User-journey controller tests on STANDALONE MockMvc (no Spring context, no
 * DB, no Docker, no env): the full document/process API contract with mocked
 * services — upload 202/dedup 200, SSE documents, composite PATCH, cancel
 * (signal+cancel, never terminate), durable history + retry.
 */
class UserJourneyTest {

    private val mapper: ObjectMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .addModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build()

    private val documentService = mock<DocumentService>()
    private val processQueueService = mock<ProcessQueueService>()
    private val documentWorkflowService = mock<DocumentWorkflowService>()
    private val workflowClient = mock<WorkflowClient>()
    private val workflowJobStreamer = mock<WorkflowJobStreamer>()
    private val storageService = mock<LocalStorageService>()
    private val documentRepository = mock<DocumentRepository>()
    private val executor = Executor { it.run() }

    private fun mvc(): MockMvc = MockMvcBuilders.standaloneSetup(
        DocumentController(documentService, processQueueService, executor),
        ProcessController(
            workflowJobStreamer, processQueueService, documentWorkflowService,
            workflowClient, storageService, documentRepository, mapper
        )
    )
        .build()

    // ------------------------------------------------------------- upload

    @Test
    fun `upload accepted for processing returns 202 with sseUrl`() {
        Mockito.`when`(
            documentService.uploadDocument(any(), any())
        ).thenReturn(
            DocumentUploadResponseDto(
                uploadId = "hash-1", status = "PROCESSING",
                sseUrl = "/api/v1/processes/hash-1/events"
            )
        )

        val result = mvc().perform(
            multipart("/api/v1/documents")
                .file(MockMultipartFile("file", "a.pdf", "application/pdf", byteArrayOf(1, 2)))
                .file(MockMultipartFile("metadata", "", "application/json",
                    """{"title":"Annual report","partyIds":[],"tagIds":[],"collectionIds":[]}""".toByteArray()))
        )
            .andExpect(status().isAccepted)
            .andExpect(
                content().json("""{"uploadId":"hash-1","status":"PROCESSING","sseUrl":"/api/v1/processes/hash-1/events"}""")
            )
    }

    @Test
    fun `dedup re-upload returns 200 COMPLETED`() {
        Mockito.`when`(
            documentService.uploadDocument(any(), any())
        ).thenReturn(DocumentUploadResponseDto(uploadId = "hash-1", status = "COMPLETED", documentId = 7))
        mvc().perform(
            multipart("/api/v1/documents")
                .file(MockMultipartFile("file", "a.pdf", "application/pdf", byteArrayOf(1)))
                .file(MockMultipartFile("metadata", "", "application/json",
                    """{"title":"dup","partyIds":[],"tagIds":[],"collectionIds":[]}""".toByteArray()))
        )
            .andExpect(status().isOk)
            .andExpect(content().json("""{"uploadId":"hash-1","status":"COMPLETED","documentId":7}"""))
    }

    // ------------------------------------------------------------- browse/search

    @Test
    fun `documents endpoint returns an SSE emitter and streams through the service`() {
        val controller = DocumentController(documentService, processQueueService, executor)
        val emitter = controller.documents(
            DocumentQuery(q = "report", sort = "title,asc")
        )

        // Wiring contract: the endpoint hands a live SseEmitter to the
        // streaming service with the parsed query. (The SSE wire format
        // itself is exercised end to end by the running stack over HTTP;
        // standalone MockMvc does not drive SseEmitter async dispatch.)
        Mockito.verify(documentService).streamDocumentsTo(
            eq(emitter), eq("report"), isNull(),
            eq(emptyList<Long>()), eq(emptyList<Long>()), isNull(),
            isNull(), isNull(), isNull(), isNull(),
            eq("title"), eq("asc")
        )
        assertNotNull(emitter)
    }

    // ------------------------------------------------------------- composite PATCH

    @Test
    fun `composite patch updates title parties and tags`() {
        mvc().perform(
            patch("/api/v1/documents/42")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"New title","partyIds":[1,2],"tagIds":[]}""")
        )
            .andExpect(status().isOk)
        Mockito.verify(documentService).updateDocument(eq(42L), any())
    }

    // ------------------------------------------------------------- processing

    @Test
    fun `history lists durable ledger entries`() {
        Mockito.`when`(processQueueService.history(50, 0)).thenReturn(
            listOf(
                ProcessHistoryDto(
                    uploadId = "hash-1", title = "T", type = DocumentType.PDF,
                    stage = ProcessingStage.FAILED, error = "boom", documentId = null,
                    createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH
                )
            )
        )
        mvc().perform(get("/api/v1/processes/history"))
            .andExpect(status().isOk)
            .andExpect(content().json("""[{"uploadId":"hash-1","stage":"FAILED","error":"boom"}]"""))
    }

    @Test
    fun `cancel signals and cancels but never terminates`() {
        val stub = mock<WorkflowStub>()
        Mockito.`when`(workflowClient.newUntypedWorkflowStub(any<String>())).thenReturn(stub)
        Mockito.`when`(documentRepository.findByFileHash("hash-1")).thenReturn(null)

        mvc().perform(post("/api/v1/processes/hash-1/cancel"))
            .andExpect(status().isAccepted)

        Mockito.verify(stub).signal("cancel")
        Mockito.verify(stub).cancel()
        Mockito.verify(stub, Mockito.never()).terminate(any())
    }

    @Test
    fun `retry restarts the workflow from the ledger`() {
        val tempFile = Files.createTempFile("faldony-retry", ".pdf")
        Mockito.`when`(documentRepository.findByFileHash("hash-1")).thenReturn(null)
        Mockito.`when`(processQueueService.findLedger("hash-1")).thenReturn(
            ProcessingLedger(
                uploadId = "hash-1",
                title = "T",
                type = DocumentType.PDF,
                stage = ProcessingStage.FAILED,
                error = "boom",
                jobSpec = """{"hash":"hash-1","title":"T","objectKey":"documents/hash-1.pdf","mimeType":"application/pdf"}"""
            )
        )
        Mockito.`when`(storageService.getResource(any())).thenReturn(FileSystemResource(tempFile))
        Mockito.`when`(
            documentWorkflowService.startOrAttach(any(), any())
        ).thenReturn(true)

        mvc().perform(post("/api/v1/processes/hash-1/retry"))
            .andExpect(status().isAccepted)

        Mockito.verify(documentWorkflowService).startOrAttach(any(), any())
    }

    @Test
    fun `retry without ledger is 404`() {
        Mockito.`when`(documentRepository.findByFileHash("ghost")).thenReturn(null)
        Mockito.`when`(processQueueService.findLedger("ghost")).thenReturn(null)
        mvc().perform(post("/api/v1/processes/ghost/retry"))
            .andExpect(status().isNotFound)
    }
}