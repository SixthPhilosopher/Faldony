package com.kiss.backend.temporal.activity

import com.fasterxml.jackson.databind.ObjectMapper
import com.kiss.backend.model.entity.Document
import com.kiss.backend.model.entity.Person
import com.kiss.backend.model.entity.ProcessingErrorCode
import com.kiss.backend.model.entity.ProcessingLedger
import com.kiss.backend.model.entity.ProcessingStage
import com.kiss.backend.model.entity.Tag
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
import com.kiss.backend.temporal.workflow.NonRetryableDocumentException
import com.kiss.backend.util.DoclingClient
import com.kiss.backend.util.DoclingPollResult
import com.kiss.backend.util.MimeTypes
import io.temporal.activity.Activity
import io.temporal.activity.ActivityInterface
import io.temporal.spring.boot.ActivityImpl
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Extraction result as a CLAIM-CHECK REFERENCE: the heavy chunk texts live in
 * the local scratch store, only this small ticket crosses Temporal history.
 */
data class ExtractionRef(
    val title: String = "",
    val chunksKey: String = "",
    val chunkCount: Int = 0,
    val pageCount: Int = 0
)

/** Embedding result as a claim-check reference (null key = degraded, no vectors). */
data class EmbeddingRef(
    val embeddingsKey: String? = null,
    val count: Int = 0
)

data class EmbeddingProgress(
    val done: Int = 0,
    val total: Int = 0
)

@ActivityInterface
interface DocumentActivities {

    /**
     * Submits the stored file to docling-serve's ASYNC route
     * (`/v1/chunk/hybrid/file/async`). Fast (<1s, no blocking wait).
     * The returned task_id crosses history; the file never does.
     */
    fun submitDoclingConvert(request: DocumentProcessRequest): String

    /**
     * Polls one docling task. 404 (vanished — server restart / result GC) is
     * reported as `found=false` so the workflow can resubmit.
     */
    fun pollDocling(taskId: String): DoclingPollResult

    /**
     * Fetches a completed docling result (single-use in the long run) and
     * persists the chunks to scratch `scratch/doc-{hash}/chunks.json` BEFORE
     * returning — the claim-check that protects the one-shot payload.
     * Returns the tiny [ExtractionRef].
     */
    fun fetchDoclingResult(request: DocumentProcessRequest, taskId: String): ExtractionRef

    /**
     * Reads the chunk texts back from scratch, generates local embeddings
     * (Spring AI Transformers/ONNX) in-memory with heartbeat resume, and
     * persists them to `scratch/doc-{hash}/embeddings.json`. Returns an
     * [EmbeddingRef] (null key when no vectors — text-only degradation).
     */
    fun embedChunks(uploadId: String, ref: ExtractionRef): EmbeddingRef

    /**
     * The ONLY database write of the pipeline: reads the scratch data back by
     * reference, inserts the completed document + chunks atomically, then
     * deletes the scratch directory. Idempotent via [DocumentRepository.findByFileHash]
     * (a committed-then-retried attempt reuses the existing row).
     * Returns the created (or existing) document id.
     */
    fun finalizeDocument(request: DocumentProcessRequest, extraction: ExtractionRef, embeddings: EmbeddingRef): Long

    /**
     * Compensation / failure cleanup: removes the upload object AND its scratch
     * directory. Idempotent (missing files are a success).
     */
    fun cleanupFailedUpload(objectKey: String, hash: String)

    /**
     * Title-only metadata update: sets the new title, re-embeds the TITLE chunk
     * and prunes dictionary entries left over from the old title.
     */
    fun updateTitleDocument(documentId: Long, newTitle: String)

    /**
     * The SINGLE durable status writer: idempotent UPSERT of the
     * `processing_ledger` row (seeds the job spec at start, records milestone
     * and terminal stages, bumps retry_count on a re-run).
     */
    fun updateProcessingStatus(
        uploadId: String,
        stage: ProcessingStage,
        errorCode: ProcessingErrorCode? = null,
        message: String? = null,
        documentId: Long? = null,
        jobSpec: DocumentProcessRequest? = null
    )

    /**
     * Embedding backfill for degraded documents (reconciler-driven): embeds
     * only the chunks whose vector is NULL and writes them back (idempotent
     * guard). No-op when everything is already embedded.
     */
    fun embedMissingChunks(documentId: Long): Int

    /**
     * Spring bean implementation, auto-discovered by the Temporal worker
     * (workers-auto-discovery + @ActivityImpl). Bean name kept as
     * `documentActivitiesImpl` for clarity.
     */
    @Component("documentActivitiesImpl")
    @ActivityImpl(workers = ["faldony-worker"])
    class Impl(
        private val documentRepository: DocumentRepository,
        private val personRepository: PersonRepository,
        private val tagRepository: TagRepository,
        private val collectionRepository: CollectionRepository,
        private val ledgerRepository: ProcessingLedgerRepository,
        private val storageService: LocalStorageService,
        private val doclingClient: DoclingClient,
        private val searchService: SearchService,
        private val chunkRepository: DocumentChunkRepository,
        private val collectionService: CollectionService,
        private val objectMapper: ObjectMapper
    ) : DocumentActivities {

        private val logger = LoggerFactory.getLogger(Impl::class.java)

        override fun submitDoclingConvert(request: DocumentProcessRequest): String {
            // Filename hint for docling: mime-derived extension (txt for text),
            // decoupled from the original-extension object key.
            val filename = "${request.hash}.${MimeTypes.doclingDetectionExtension(request.mimeType)}"
            return storageService.getObject(request.objectKey).use { stream ->
                val taskId = doclingClient.submitAsync(stream, filename, request.mimeType).taskId
                logger.info("Submitted docling conversion for upload {} (object {}), task {}", request.hash, request.objectKey, taskId)
                taskId
            }
        }

        override fun pollDocling(taskId: String): DoclingPollResult {
            val result = doclingClient.pollStatus(taskId)
            logger.debug("Docling poll {}: {}", taskId, result)
            if (result.found) {
                logger.debug("Docling task {} status: {}", taskId, result.status)
            } else {
                logger.warn("Docling task {} not found (vanished)", taskId)
            }
            return result
        }

        override fun fetchDoclingResult(request: DocumentProcessRequest, taskId: String): ExtractionRef {
            val response = doclingClient.fetchResult(taskId)
            val result = doclingClient.toDoclingChunkResult(response)

            // Empty OCR output is ACCEPTED (not a failure): the document is still
            // released and searchable via its chunks and title.
            val chunks = result.chunks.ifEmpty { emptyList() }

            // Claim-check write: chunk texts go to scratch under the UPLOAD hash
            // (same layout as before, so embed/finalize/cleanup/reconciler work
            // unchanged), only the ref goes back.
            val chunksKey = scratchKey(request.hash, "chunks.json")
            storageService.writeText(chunksKey, objectMapper.writeValueAsString(chunks))

            logger.info("Fetched {} chunks and {} pages for upload {}, task {}, scratch={}",
                chunks.size, result.pageCount, request.hash, taskId, chunksKey)
            return ExtractionRef(
                title = request.title,
                chunksKey = chunksKey,
                chunkCount = chunks.size,
                pageCount = result.pageCount
            )
        }

        override fun embedChunks(uploadId: String, ref: ExtractionRef): EmbeddingRef {
            val rawChunks = objectMapper.readValue(
                storageService.readText(ref.chunksKey) ?: "[]",
                Array<String>::class.java
            ).toList()

            // The title is ALWAYS part of the embedding batch (even when OCR
            // produced zero chunks) so the pipeline stays positionally aligned:
            //   passages = [title] + body chunks
            //   finalize  -> vectors[0] = TITLE  chunk, vectors[idx+1] = body idx
            // This explicit [title] + chunks layout replaces the old scheme
            // where only body chunks were embedded and finalize shifted vectors
            // by one position — every persisted embedding was mismatched.
            val passages = mutableListOf(ref.title)
            rawChunks.filter { it.isNotBlank() }.forEach { passages.add(it) }

            logger.info("Embedding {} passages for upload {} (title + {} chunks)", passages.size, uploadId, passages.size - 1)
            val ctx = Activity.getExecutionContext()
            val batchSize = 20
            // Resume from the last heartbeat checkpoint if this activity is retried.
            val start = ctx.getHeartbeatDetails(EmbeddingProgress::class.java)
                .map { it.done.coerceIn(0, passages.size) }
                .orElse(0)

            val result = arrayOfNulls<FloatArray>(passages.size)
            try {
                var i = start
                while (i < passages.size) {
                    ctx.heartbeat(EmbeddingProgress(done = i, total = passages.size))
                    val end = minOf(i + batchSize, passages.size)
                    val vectors = searchService.embedPassages(passages.subList(i, end))
                    // embedPassages already validates 768 dims per vector.
                    vectors.forEachIndexed { k, v -> result[i + k] = v }
                    i = end
                }
            } catch (e: io.temporal.client.ActivityCompletionException) {
                // Cancellation (or heartbeat timeout) delivered to this activity:
                // clean up nothing persistent (scratch is consumed by finalize /
                // compensated on failure) and rethrow so the execution closes
                // cancelled — never swallows it. (MCP: catch, clean up, rethrow.)
                logger.warn("Embedding activity interrupted for {}", uploadId)
                throw e
            }

            val embeddingsKey = scratchKey(uploadId, "embeddings.json")
            val data = result.map { it ?: FloatArray(0) }.toTypedArray()
            val resumable = start > 0
            if (resumable) {
                logger.info("Embedding resume for upload {}: started from {} of {} (heartbeat checkpoint)", uploadId, start, passages.size)
            }
            storageService.writeText(embeddingsKey, objectMapper.writeValueAsString(data))
            logger.info("Embedding completed for upload {}: {} vectors -> {}", uploadId, data.size, embeddingsKey)
            return EmbeddingRef(embeddingsKey, passages.size)
        }

        @Transactional
        override fun finalizeDocument(
            request: DocumentProcessRequest,
            ref: ExtractionRef,
            embeddings: EmbeddingRef
        ): Long {
            // Idempotency guard: a previous attempt may have committed before a
            // worker crash — reuse the existing row instead of inserting twice.
            documentRepository.findByFileHash(request.hash)?.let { existing ->
                logger.info("Document {} already finalized for upload {}, reusing", existing.id, request.hash)
                storageService.deleteScratch(request.hash)
                return existing.id ?: throw IllegalStateException("Document id not present")
            }

            val chunks = storageService.readText(ref.chunksKey)
                ?.let { objectMapper.readValue(it, Array<String>::class.java).toList() }
                ?: emptyList()
            val vectors: List<FloatArray> = embeddings.embeddingsKey?.let { key ->
                val raw = storageService.readText(key)
                if (raw != null) {
                    objectMapper.readValue(raw, Array<FloatArray>::class.java).map { ir -> ir }
                } else emptyList()
            } ?: emptyList()

            // Positional contract of embeds: vectors[0] is the TITLE embedding,
            // vectors[idx+1] the embedding of body chunk idx. Validate instead
            // of trusting the file: a mismatch would silently poison every
            // semantic search result.
            if (embeddings.embeddingsKey != null && vectors.size != chunks.size + 1) {
                throw NonRetryableDocumentException(
                    "Embedding count mismatch at finalize for upload ${request.hash}: " +
                        "expected ${chunks.size + 1} vectors ([title] + chunks), got ${vectors.size}"
                )
            }

            // Re-validate references captured at upload time.
            val parties = resolvePersons(request.partyIds)
            val tags = resolveTags(request.tagIds)

            val doc = Document(
                fileHash = request.hash,
                objectKey = request.objectKey,
                mimeType = request.mimeType,
                title = request.title,
                pages = maxOf(1, ref.pageCount),
                parties = parties,
                tags = tags,
                type = MimeTypes.documentTypeFor(request.mimeType)
            )
            val saved = documentRepository.save(doc)
            val id = saved.id ?: throw IllegalStateException("Document id not generated")

            chunkRepository.insertChunk(id, 0, ref.title, "TITLE", vectors.getOrNull(0))
            chunks.forEachIndexed { idx, chunkText ->
                chunkRepository.insertChunk(id, idx + 1, chunkText, "BODY", vectors.getOrNull(idx + 1))
            }

            if (request.collectionIds.isNotEmpty()) {
                val collections = collectionRepository.findAllById(request.collectionIds)
                if (collections.size != request.collectionIds.size) {
                    logger.warn(
                        "Some collections missing at finalize for upload {} ({} / {})",
                        request.hash, collections.size, request.collectionIds.size
                    )
                }
                collections.forEach { collectionService.addDocumentToEnd(it, saved) }
            }

            logger.info("Finalized document {} for upload {}", id, request.hash)
            // Claim-check cleanup: scratch is consumed.
            storageService.deleteScratch(request.hash)
            return id
        }

        override fun cleanupFailedUpload(objectKey: String, hash: String) {
            storageService.deleteObject(objectKey)
            storageService.deleteScratch(hash)
            logger.info("Cleaned up upload object {} and scratch for {}", objectKey, hash)
        }

        @Transactional
        override fun updateTitleDocument(documentId: Long, newTitle: String) {
            // Already indexed? (retry after commit, or a same-title re-save
            // repairing nothing) — the upsert is idempotent, so skip the
            // embed entirely when chunk 0 already carries the title.
            if (chunkRepository.titleChunkText(documentId) == newTitle) {
                logger.info("Title chunk for document {} already indexed, skipping", documentId)
                return
            }

            val doc = documentRepository.findById(documentId)
                .orElseThrow { NonRetryableDocumentException("Document $documentId not found") }

            if (newTitle != doc.title) {
                doc.title = newTitle
                documentRepository.save(doc)
            }

            val titleEmbedding = searchService.embedPassages(listOf(newTitle)).first()
            chunkRepository.insertTitleChunk(documentId, newTitle, titleEmbedding)

            logger.info("Re-indexed title for document {} to '{}'", documentId, newTitle)
        }

        @Transactional
        override fun updateProcessingStatus(
            uploadId: String,
            stage: ProcessingStage,
            errorCode: ProcessingErrorCode?,
            message: String?,
            documentId: Long?,
            jobSpec: DocumentProcessRequest?
        ) {
            val existing = ledgerRepository.findByUploadId(uploadId)
            if (existing == null) {
                if (jobSpec == null) {
                    logger.warn("Status write for unknown ledger entry {} (no job spec)", uploadId)
                    return
                }
                ledgerRepository.save(
                    ProcessingLedger(
                        uploadId = uploadId,
                        title = jobSpec.title,
                        type = MimeTypes.documentTypeFor(jobSpec.mimeType),
                        stage = stage,
                        errorCode = errorCode,
                        error = message,
                        documentId = documentId,
                        jobSpec = objectMapper.writeValueAsString(jobSpec),
                        retryCount = 0
                    )
                )
                logger.info("Ledger entry created for upload {}: stage={}, errorCode={}", uploadId, stage, errorCode)
            } else {
                // A re-run of the SAME workflow (reconciler auto-retry / user retry)
                // bumps the attempt counter whenever we leave the failed state.
                val previous = existing.stage
                if (existing.stage == ProcessingStage.FAILED && stage != ProcessingStage.FAILED) {
                    existing.retryCount = (existing.retryCount ?: 0) + 1
                }
                if (jobSpec != null) {
                    existing.title = jobSpec.title
                    existing.type = MimeTypes.documentTypeFor(jobSpec.mimeType)
                    existing.jobSpec = objectMapper.writeValueAsString(jobSpec)
                }
                existing.stage = stage
                existing.errorCode = errorCode
                existing.error = message?.take(2000)
                existing.documentId = documentId
                ledgerRepository.save(existing)
                if (previous != stage) {
                    logger.info("Ledger entry updated for upload {}: {} -> {} (errorCode={}, retryCount={})",
                        uploadId, previous, stage, errorCode, existing.retryCount)
                }
            }
        }

        override fun embedMissingChunks(documentId: Long): Int {
            val missing = chunkRepository.chunksMissingEmbedding(documentId)
            if (missing.isEmpty()) {
                logger.debug("Embedding backfill no-op for document {}: no missing vectors", documentId)
                return 0
            }
            logger.info("Backfilling embeddings for document {} ({} chunks)", documentId, missing.size)

            val ctx = Activity.getExecutionContext()
            var done = 0
            for (batch in missing.chunked(EMBED_BATCH)) {
                ctx.heartbeat(done)
                val vectors = searchService.embedPassages(batch.map { it.second })
                batch.zip(vectors).forEach { (chunk, vec) ->
                    chunkRepository.updateChunkEmbedding(documentId, chunk.first, vec)
                }
                done += batch.size
                logger.debug("Backfill progress for document {}: {}/{} chunks embedded", documentId, done, missing.size)
            }
            logger.info("Embedding backfill done for document {}: {} vectors written", documentId, done)
            return done
        }

        private fun scratchKey(hash: String, fileName: String): String =
            "${LocalStorageService.SCRATCH_PREFIX}/doc-$hash/$fileName"

        private fun resolvePersons(ids: List<Long>): MutableSet<Person> =
            if (ids.isEmpty()) mutableSetOf()
            else {
                val found = personRepository.findAllById(ids)
                if (found.size != ids.size) {
                    logger.warn("Some parties missing at finalize ({} of {})", found.size, ids.size)
                }
                found.toMutableSet()
            }

        private fun resolveTags(ids: List<Long>): MutableSet<Tag> =
            if (ids.isEmpty()) mutableSetOf()
            else {
                val found = tagRepository.findAllById(ids)
                if (found.size != ids.size) {
                    logger.warn("Some tags missing at finalize ({} of {})", found.size, ids.size)
                }
                found.toMutableSet()
            }

        companion object {
            const val EMBED_BATCH = 20
        }
    }
}