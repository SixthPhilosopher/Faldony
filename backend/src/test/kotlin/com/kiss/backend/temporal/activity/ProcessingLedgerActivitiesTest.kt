package com.kiss.backend.temporal.activity

import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.kiss.backend.model.entity.DocumentType
import com.kiss.backend.model.entity.ProcessingErrorCode
import com.kiss.backend.model.entity.ProcessingLedger
import com.kiss.backend.model.entity.ProcessingStage
import com.kiss.backend.repository.CollectionRepository
import com.kiss.backend.repository.DocumentChunkRepository
import com.kiss.backend.repository.DocumentRepository
import com.kiss.backend.repository.PersonRepository
import com.kiss.backend.repository.ProcessingLedgerRepository
import com.kiss.backend.repository.TagRepository
import com.kiss.backend.service.CollectionService
import com.kiss.backend.service.LocalStorageService
import com.kiss.backend.service.SearchService
import com.kiss.backend.temporal.workflow.DocumentProcessRequest
import com.kiss.backend.util.DoclingClient
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the durable-ledger single-writer + embedding-backfill
 * activities (DocumentActivities.Impl) — no Spring, no DB.
 */
class ProcessingLedgerActivitiesTest {

    private class Fixture(
        val impl: DocumentActivities.Impl,
        val ledgerRepo: ProcessingLedgerRepository,
        val chunkRepo: DocumentChunkRepository,
        val search: SearchService
    )

    private fun fixture(): Fixture {
        val ledgerRepo = Mockito.mock(ProcessingLedgerRepository::class.java)
        val chunkRepo = Mockito.mock(DocumentChunkRepository::class.java)
        val search = Mockito.mock(SearchService::class.java)
        val impl = DocumentActivities.Impl(
            documentRepository = Mockito.mock(DocumentRepository::class.java),
            personRepository = Mockito.mock(PersonRepository::class.java),
            tagRepository = Mockito.mock(TagRepository::class.java),
            collectionRepository = Mockito.mock(CollectionRepository::class.java),
            ledgerRepository = ledgerRepo,
            storageService = Mockito.mock(LocalStorageService::class.java),
            doclingClient = Mockito.mock(DoclingClient::class.java),
            searchService = search,
            chunkRepository = chunkRepo,
            collectionService = Mockito.mock(CollectionService::class.java),
            objectMapper = JsonMapper.builder()
                .addModule(KotlinModule.Builder().build())
                .addModule(JavaTimeModule())
                .build()
        )
        return Fixture(impl, ledgerRepo, chunkRepo, search)
    }

    // ------------------------------------------------------------- ledger

    @Test
    fun `updateProcessingStatus creates a new ledger row with the job spec`() {
        val f = fixture()
        Mockito.`when`(f.ledgerRepo.findByUploadId("hash-1")).thenReturn(null)

        f.impl.updateProcessingStatus(
            "hash-1", ProcessingStage.PROCESSING, jobSpec =
                DocumentProcessRequest(hash = "hash-1", title = "T", mimeType = "application/pdf")
        )

        val captor = ArgumentCaptor.forClass(ProcessingLedger::class.java)
        Mockito.verify(f.ledgerRepo).save(captor.capture())
        assertEquals(ProcessingStage.PROCESSING, captor.value.stage)
        assertEquals(DocumentType.PDF, captor.value.type)
        assertEquals(0, captor.value.retryCount)
        assertTrue(captor.value.jobSpec!!.contains("hash-1"), "job spec persisted for retry")
    }

    @Test
    fun `updateProcessingStatus refreshes an existing row and bumps retryCount on re-run`() {
        val f = fixture()
        val existing = ProcessingLedger(
            uploadId = "hash-1", title = "old", type = DocumentType.DOCUMENT,
            stage = ProcessingStage.FAILED, errorCode = ProcessingErrorCode.DOCLING_DOWN
        )
        Mockito.`when`(f.ledgerRepo.findByUploadId("hash-1")).thenReturn(existing)

        // A transient failure being retried: state leaves FAILED -> attempt bumps.
        f.impl.updateProcessingStatus("hash-1", ProcessingStage.PROCESSING, jobSpec =
            DocumentProcessRequest(hash = "hash-1", title = "new", mimeType = "application/pdf"))

        Mockito.verify(f.ledgerRepo).save(existing)
        assertEquals(ProcessingStage.PROCESSING, existing.stage)
        assertEquals(1, existing.retryCount)

        // Same-stage updates do NOT bump.
        f.impl.updateProcessingStatus("hash-1", ProcessingStage.PROCESSING)
        assertEquals(1, existing.retryCount)
    }

    @Test
    fun `updateProcessingStatus writes the terminal outcome with a typed code`() {
        val f = fixture()
        val existing = ProcessingLedger(
            uploadId = "hash-1", title = "T", type = DocumentType.PDF,
            stage = ProcessingStage.EMBEDDING
        )
        Mockito.`when`(f.ledgerRepo.findByUploadId("hash-1")).thenReturn(existing)

        f.impl.updateProcessingStatus(
            "hash-1", ProcessingStage.FAILED,
            ProcessingErrorCode.BAD_FILE, "docling rejected the file", null
        )

        Mockito.verify(f.ledgerRepo).save(existing)
        assertEquals(ProcessingStage.FAILED, existing.stage)
        assertEquals(ProcessingErrorCode.BAD_FILE, existing.errorCode)
        assertEquals("docling rejected the file", existing.error)
        assertEquals(0, existing.retryCount)
    }

    // ------------------------------------------------------------- backfill

    @Test
    fun `embedMissingChunks is a no-op when the document is already embedded`() {
        val f = fixture()
        Mockito.`when`(f.chunkRepo.chunksMissingEmbedding(9L)).thenReturn(emptyList())

        assertEquals(0, f.impl.embedMissingChunks(9L))
        Mockito.verify(f.search, Mockito.never()).embedPassages(Mockito.anyList())
    }

    @Test
    fun `embedMissingChunks embeds only NULL chunks and writes them back`() {
        val f = fixture()
        Mockito.`when`(f.chunkRepo.chunksMissingEmbedding(9L)).thenReturn(
            listOf(0 to "title", 1 to "body one", 2 to "body two")
        )
        Mockito.`when`(f.search.embedPassages(listOf("title", "body one", "body two")))
            .thenReturn(listOf(floatArrayOf(0.1f), floatArrayOf(0.2f), floatArrayOf(0.3f)))

        // Run inside Temporal's TestActivityEnvironment so the activity has a
        // real (heartbeat-capable) ActivityExecutionContext.
        val env = io.temporal.testing.TestActivityEnvironment.newInstance()
        env.registerActivitiesImplementations(f.impl)
        val done = env.newActivityStub(DocumentActivities::class.java).embedMissingChunks(9L)

        assertEquals(3, done)
        Mockito.verify(f.chunkRepo).updateChunkEmbedding(9L, 0, floatArrayOf(0.1f))
        Mockito.verify(f.chunkRepo).updateChunkEmbedding(9L, 1, floatArrayOf(0.2f))
        Mockito.verify(f.chunkRepo).updateChunkEmbedding(9L, 2, floatArrayOf(0.3f))
    }
}