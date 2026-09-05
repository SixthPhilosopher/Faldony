package com.kiss.backend.service

import com.kiss.backend.model.dto.*
import com.kiss.backend.model.entity.*
import com.kiss.backend.repository.*
import com.kiss.backend.temporal.workflow.DocumentProcessRequest
import com.kiss.backend.temporal.workflow.DocumentProcessingWorkflowImpl.Companion.WORKFLOW_ID_PREFIX
import com.kiss.backend.temporal.workflow.TitleUpdateWorkflow
import com.kiss.backend.util.MimeTypes
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowExecutionAlreadyStarted
import io.temporal.client.WorkflowNotFoundException
import io.temporal.client.WorkflowOptions
import org.apache.commons.codec.digest.DigestUtils
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.FileSystemResource
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.nio.file.Files
import java.time.Instant

/** Streamed download payload served directly by the API (no presign needed). */
data class DownloadStream(
    val resource: FileSystemResource,
    val contentType: String,
    val fileName: String,
    val inline: Boolean
)

/**
 * Transactions are scoped PER METHOD, never across a stream: the SSE listing
 * ([streamDocumentsTo]) runs each ranking pass and each hydration batch inside
 * a SHORT read-only [TransactionTemplate] while the emitter writes happen
 * outside any transaction. Only the small write paths take real transactions.
 */
@Service
class DocumentService(
    private val documentRepository: DocumentRepository,
    private val personRepository: PersonRepository,
    private val tagRepository: TagRepository,
    private val collectionRepository: CollectionRepository,
    private val associationRepository: CollectionDocumentAssociationRepository,
    private val collectionService: CollectionService,
    private val storageService: LocalStorageService,
    private val chunkRepository: DocumentChunkRepository,
    private val workflowClient: WorkflowClient,
    private val searchService: SearchService,
    private val documentWorkflowService: DocumentWorkflowService,
    private val transactionManager: PlatformTransactionManager,
    @Value("\${faldony.upload.max-size-bytes}") private val maxUploadBytes: Long,
    @Value("\${faldony.search.window}") private val searchWindow: Int,
    @Value("\${faldony.search.stream-batch-size}") private val streamBatchSize: Int,
    @Value("\${faldony.storage.verify-on-download}") private val verifyOnDownload: Boolean
) {
    private val logger = LoggerFactory.getLogger(DocumentService::class.java)
    private val tx = TransactionTemplate(transactionManager).apply { isReadOnly = true }
    private val writeTx = TransactionTemplate(transactionManager)

    /**
     * Unified documents SSE stream: the single backend of `GET /api/v1/documents`.
     *
     *  - blank `q` → **browse**: every filter-matching document (uncapped),
     *    ordered by the sort field; `truncated` is always false.
     *  - non-blank `q` → **search**: only documents matched by at least one
     *    retriever inside the fixed ranking window [searchWindow]; `truncated`
     *    is true when the matches reached the window depth (more results may
     *    exist beyond it — see DocumentChunkRepository docs).
     *
     * Emits one `doc` event per document (sort-field order, hydrated in
     * batches so each DB roundtrip is a SHORT read-only transaction), then a
     * terminal `done` event `{delivered, truncated}`; failures emit a single
     * `error` event instead. Runs on the controller's dedicated streaming
     * executor — never a Tomcat worker thread.
     */
    fun streamDocumentsTo(
        emitter: SseEmitter,
        q: String?,
        type: DocumentType?,
        partyIds: List<Long>,
        tagIds: List<Long>,
        collectionId: Long?,
        createdFrom: Instant?,
        createdTo: Instant?,
        updatedFrom: Instant?,
        updatedTo: Instant?,
        sortColumn: String,
        sortDirection: String
    ) {
        try {
            val normalized = q?.lowercase()?.trim().orEmpty()
            var delivered = 0
            var truncated = false

            if (normalized.isEmpty()) {
                // Browse: plain uncapped listing, no ranking involved.
                val ids = tx.execute {
                    chunkRepository.browseDocuments(
                        type, partyIds, tagIds, collectionId,
                        createdFrom, createdTo, updatedFrom, updatedTo, sortColumn, sortDirection
                    )
                }!!
                for (dto in hydrateInBatches(ids)) {
                    emitter.send(SseEmitter.event().name("doc").data(dto))
                    delivered++
                }
            } else {
                // Search: single ranking pass over the fixed window, then stream.
                val vector = queryVector(normalized)
                val window = tx.execute {
                    chunkRepository.searchDocuments(
                        searchWindow, normalized, vector, type, partyIds, tagIds,
                        collectionId, createdFrom, createdTo, updatedFrom, updatedTo, sortColumn, sortDirection
                    )
                }!!
                logRankerStats(window, normalized)
                logScores(window)

                for (dto in hydrateInBatches(window.rows.map { it.id })) {
                    emitter.send(SseEmitter.event().name("doc").data(dto))
                    delivered++
                }
                // Depth-limited: the ranker windows hit their cap — some
                // matching documents are not delivered.
                truncated = window.rows.size >= searchWindow
            }

            emitter.send(
                SseEmitter.event().name("done")
                    .data(mapOf("delivered" to delivered, "truncated" to truncated))
            )
        } catch (e: Exception) {
            if (e is java.io.IOException) {
                // Normal client disconnect — the terminal event is skipped.
                logger.debug("SSE documents stream closed early (client disconnected)")
            } else {
                logger.error("SSE documents stream failed", e)
                try {
                    emitter.send(SseEmitter.event().name("error").data("stream failed"))
                } catch (ignore: Exception) {
                    // client already gone — nothing to report to
                }
            }
        } finally {
            emitter.complete()
        }
    }

    /**
     * Hydrates [ids] in [streamBatchSize] chunks, yielding the DTOs in the
     * original order. Each batch runs inside its own SHORT read-only
     * transaction — never a long-lived transaction across the whole stream.
     */
    private fun hydrateInBatches(ids: List<Long>): Sequence<DocumentDto> = sequence {
        for (batch in ids.chunked(streamBatchSize)) {
            yieldAll(tx.execute { hydrateIds(batch) } ?: emptyList())
        }
    }

    // ------------------------------------------------ metadata updates (PATCH)

    /**
     * JSON list variant (same filters/sort as the SSE stream) — returns the
     * hydrated documents directly. One blocking call; used by the KMP frontend
     * (a one-shot fetch is the wrong use of SSE, which never closes).
     */
    @Transactional(readOnly = true)
    fun listDocuments(
        q: String?,
        type: DocumentType?,
        partyIds: List<Long>,
        tagIds: List<Long>,
        collectionId: Long?,
        createdFrom: Instant?,
        createdTo: Instant?,
        updatedFrom: Instant?,
        updatedTo: Instant?,
        sortColumn: String,
        sortDirection: String
    ): List<DocumentDto> {
        val normalized = q?.lowercase()?.trim().orEmpty()
        val ids = if (normalized.isEmpty()) {
            chunkRepository.browseDocuments(
                type, partyIds, tagIds, collectionId,
                createdFrom, createdTo, updatedFrom, updatedTo, sortColumn, sortDirection
            )
        } else {
            val vector = queryVector(normalized)
            chunkRepository.searchDocuments(
                searchWindow, normalized, vector, type, partyIds, tagIds,
                collectionId, createdFrom, createdTo, updatedFrom, updatedTo, sortColumn, sortDirection
            ).rows.map { it.id }
        }
        return hydrateIds(ids)
    }

    /**
     * Composite metadata update: optional title, party set, tag set — a `null`
     * field is untouched, `[]` clears. All-or-nothing in one transaction.
     * A changed title schedules the re-index via Temporal AFTER commit.
     */
    @Transactional
    fun updateDocument(id: Long, req: DocumentUpdateRequest): DocumentDto {
        val doc = findDocument(id)

        req.title?.let { raw ->
            val newTitle = raw.trim()
            if (newTitle.isEmpty()) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Title cannot be blank")
            }
            if (newTitle != doc.title) {
                doc.title = newTitle
                documentRepository.save(doc)
            }
            // Always schedule the re-index (idempotent): a previously failed
            // title workflow leaves chunk 0 stale, and a same-title re-save is
            // the only window to repair it — the activity's chunk-text guard
            // makes a no-op run cheap.
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        startTitleUpdateWorkflow(id, newTitle)
                    }
                }
            )
        }

        req.partyIds?.let { ids ->
            doc.parties.clear()
            doc.parties.addAll(resolvePersons(ids))
        }
        req.tagIds?.let { ids ->
            doc.tags.clear()
            doc.tags.addAll(resolveTags(ids))
        }

        return toDtoWithEmails(doc, associationRepository.findByDocument(doc))
    }

    private fun resolvePersons(ids: List<Long>): List<Person> {
        val found = personRepository.findAllById(ids)
        if (found.size != ids.size) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Some parties not found")
        }
        return found
    }

    private fun resolveTags(ids: List<Long>): List<Tag> {
        val found = tagRepository.findAllById(ids)
        if (found.size != ids.size) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Some tags not found")
        }
        return found
    }

    private fun startTitleUpdateWorkflow(id: Long, newTitle: String) {
        val workflowId = "title-$id"
        val options = WorkflowOptions.newBuilder()
            .setWorkflowId(workflowId)
            // Re-run is legitimately needed only after a FAILED run (same-title
            // re-save with an already-completed re-index is always a no-op thanks
            // to the activity's chunk-text guard) — so failed-only reuse.
            .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
            .setTaskQueue(DocumentWorkflowService.TASK_QUEUE)
            .build()
        val workflow = workflowClient.newWorkflowStub(TitleUpdateWorkflow::class.java, options)
        try {
            WorkflowClient.start(workflow::updateTitle, id, newTitle)
        } catch (e: WorkflowExecutionAlreadyStarted) {
            // A previous title update is still running (or a completed one is
            // still within retention) — the idempotent upsert (insertTitleChunk)
            // makes a re-run harmless, so attach instead of failing the caller
            // after the commit happened.
            logger.info("Title workflow {} already running, attaching", workflowId)
        }
    }

    // ------------------------------------------------------------- document CRUD

    /**
     * The single upload endpoint: validate -> spool -> sniff -> hash -> dedup
     * -> persist to the local file store (size-verified) -> start the workflow.
     *
     * Nothing is written to the database here; a document row appears only when
     * the workflow finalizes. A bad file is rejected before storage is touched,
     * so no workflow starts and nothing leaks.
     */
    fun uploadDocument(file: MultipartFile, req: DocumentUploadRequest): DocumentUploadResponseDto {
        if (maxUploadBytes > 0 && file.size > maxUploadBytes) {
            throw ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "File exceeds maximum allowed size")
        }

        val temp = Files.createTempFile("faldony-upload", ".tmp")
        try {
            // Momentary local store: stable, re-readable for sniff + hashing.
            file.inputStream.use { input -> Files.newOutputStream(temp).use { output -> input.copyTo(output) } }

            if (file.size == 0L) {
                throw ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Empty file")
            }

            // Upload check #1: magic bytes (Apache Tika sniffing; the mime
            // is never trusted from the client).
            val mime = Files.newInputStream(temp).use { MimeTypes.sniffUploadMimeType(it, file.originalFilename) }
                ?: throw ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported or unrecognized file type")
            if (!MimeTypes.isSupportedUploadMimeType(mime)) {
                throw ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "File type '$mime' is not supported")
            }

            // Upload check #2: server-computed SHA-256 (trusted, dedup-safe).
            val hash = Files.newInputStream(temp).use { DigestUtils.sha256Hex(it) }

            // Upload check #3: idempotent re-upload of an already
            // processed file returns the completed document. The row is only
            // trusted when its object actually exists — a stale row (delete/
            // cancel race) is healed so the fresh upload finalizes cleanly.
            documentRepository.findByFileHash(hash)?.let { existing ->
                if (storageService.getResource(existing.objectKey).exists()) {
                    return DocumentUploadResponseDto(
                        uploadId = hash,
                        status = "COMPLETED",
                        documentId = existing.id
                    )
                }
                logger.warn("Dedup hit for {} but object {} is missing; reprocessing", hash, existing.objectKey)
                writeTx.execute {
                    chunkRepository.deleteChunksForDocument(existing.id!!)
                    associationRepository.deleteAll(associationRepository.findByDocument(existing))
                    documentRepository.delete(existing)
                }
            }

            // Upload check #4: metadata references still exist.
            validateReferences(req)

            // Persist (streamed from the spool). The object is named by its
            // content hash; the ORIGINAL upload extension is preserved so the
            // user gets it back at download time, right or wrong.
            val originalExt = MimeTypes.safeObjectKeyExtension(file.originalFilename)
            val objectKey = buildString {
                append("documents/$hash")
                originalExt?.let { append(".$it") }
            }

            Files.newInputStream(temp).use { input ->
                storageService.put(objectKey, input)
            }
            val storedSize = runCatching { storageService.fileSize(objectKey) }.getOrNull()
            if (storedSize == null || storedSize != file.size) {
                logger.error("Storage verification failed for {} (expected {} bytes, stored {})",
                    objectKey, file.size, storedSize)
                storageService.deleteObject(objectKey)
                throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Storage verification failed")
            }

            val request = DocumentProcessRequest(
                hash = hash,
                title = req.title.trim(),
                objectKey = objectKey,
                mimeType = mime,
                sizeBytes = file.size,
                partyIds = req.partyIds,
                tagIds = req.tagIds,
                collectionIds = req.collectionIds
            )
            documentWorkflowService.startOrAttach(request, MimeTypes.documentTypeFor(mime))

            return DocumentUploadResponseDto(
                uploadId = hash,
                status = "PROCESSING",
                sseUrl = "/api/v1/processes/$hash/events"
            )
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    @Transactional(readOnly = true)
    fun getDocumentById(id: Long): DocumentDto {
        val doc = findDocument(id)
        return toDtoWithEmails(doc, associationRepository.findByDocument(doc))
    }

    /** Entity-level access for cross-cutting services (collections). */
    @Transactional(readOnly = true)
    fun getDocumentEntity(id: Long): Document = findDocument(id)

    /**
     * Maps a collection's ordered memberships to Document DTOs (association
     * order preserved, emails batched). Used by the collection endpoint —
     * lives here so hydration stays in one place.
     */
    @Transactional(readOnly = true)
    fun documentsFromAssociations(
        associations: List<com.kiss.backend.model.entity.CollectionDocumentAssociation>
    ): List<DocumentDto> = hydrateIds(associations.map { it.document.id!! })

    @Transactional
    fun addParty(documentId: Long, partyId: Long): DocumentDto {
        val doc = findDocument(documentId)
        val party = personRepository.findById(partyId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Party not found") }
        doc.parties.add(party)
        return toDtoWithEmails(documentRepository.save(doc), associationRepository.findByDocument(doc))
    }

    @Transactional
    fun removeParty(documentId: Long, partyId: Long): DocumentDto {
        val doc = findDocument(documentId)
        val party = personRepository.findById(partyId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Party not found") }
        doc.parties.remove(party)
        return toDtoWithEmails(documentRepository.save(doc), associationRepository.findByDocument(doc))
    }

    @Transactional
    fun addTag(documentId: Long, tagId: Long): DocumentDto {
        val doc = findDocument(documentId)
        val tag = tagRepository.findById(tagId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Tag not found") }
        doc.tags.add(tag)
        return toDtoWithEmails(documentRepository.save(doc), associationRepository.findByDocument(doc))
    }

    @Transactional
    fun removeTag(documentId: Long, tagId: Long): DocumentDto {
        val doc = findDocument(documentId)
        val tag = tagRepository.findById(tagId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Tag not found") }
        doc.tags.remove(tag)
        return toDtoWithEmails(documentRepository.save(doc), associationRepository.findByDocument(doc))
    }

    /** Delete removes every trace: workflow, S3 object, chunks, collection memberships, row. */
    @Transactional
    fun deleteDocument(id: Long) {
        val doc = findDocument(id)

        terminateWorkflow(WORKFLOW_ID_PREFIX + doc.fileHash)

        storageService.deleteObject(doc.objectKey)
        chunkRepository.deleteChunksForDocument(id)
        associationRepository.deleteAll(associationRepository.findByDocument(doc))
        documentRepository.delete(doc)
    }

    @Transactional(readOnly = true)
    fun streamDownload(id: Long, inline: Boolean? = null): DownloadStream {
        val doc = findDocument(id)
        // Content type lives on the ROW (written from the Tika sniff at
        // finalize) - single source of truth, no storage metadata needed.
        val mime = doc.mimeType.ifBlank { "application/octet-stream" }
        // Disposition default: browsers can render images/PDF/text inline (webview);
        // office files are pure downloads.
        val viewable = mime.startsWith("image/") || mime == "application/pdf" || mime.startsWith("text/")
        val effectiveInline = inline ?: viewable
        // The download keeps the ORIGINAL extension stored on the key.
        val originalExt = doc.objectKey.substringAfterLast('.')
            .takeIf { it != doc.objectKey && !it.contains('/') }
        val fileName = doc.title + (originalExt?.let { ".$it" } ?: "")

        val resource = storageService.getResource(doc.objectKey)
        // A missing file (e.g. cancel raced the finalize commit) is a clean 404.
        if (!resource.exists()) {
            logger.warn("File unavailable for document {} (key {})", id, doc.objectKey)
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "File no longer available")
        }

        // verify-on-download: the stored bytes must still match the content
        // hash recorded on the row. A mismatch means corruption after the
        // validated write - delete the document + object (no-zombie) and fail
        // loudly instead of serving garbage.
        if (verifyOnDownload && !storageService.verifyHash(doc.objectKey, doc.fileHash)) {
            logger.error("Stored file corrupted for document {} (key {}): hash mismatch", id, doc.objectKey)
            storageService.deleteObject(doc.objectKey)
            documentRepository.delete(doc)
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Stored file is corrupted")
        }

        return DownloadStream(
            resource = resource,
            contentType = mime,
            fileName = fileName,
            inline = effectiveInline
        )
    }

    // ------------------------------------------------------------- hydration

    /**
     * Loads documents (batched emails/associations) preserving [ids] order.
     * Used by both the browse list and per-batch SSE hydration.
     */
    private fun hydrateIds(ids: List<Long>): List<DocumentDto> {
        if (ids.isEmpty()) return emptyList()
        val docs = documentRepository.findAllWithRelationsByIdIn(ids).associateBy { it.id!! }
        val associationsByDocId = associationRepository.findByDocumentIn(docs.values).groupBy { it.document.id }
        val emails = emailsFor(ids, docs)
        val rankById = ids.withIndex().associate { (i, id) -> id to i }
        return ids.mapNotNull { id -> docs[id] }
            .sortedBy { rankById.getValue(it.id!!) }
            .map { DocumentDtoMapper.toDto(it, associationsByDocId[it.id] ?: emptyList(), emails) }
    }

    private fun toDtoWithEmails(
        doc: Document,
        associations: List<com.kiss.backend.model.entity.CollectionDocumentAssociation>
    ): DocumentDto {
        val emails = emailsFor(listOf(doc.id!!), mapOf(doc.id!! to doc))
        return DocumentDtoMapper.toDto(doc, associations, emails)
    }

    /**
     * Batches the party email lookups: one (eager) query for all distinct
     * person ids instead of lazy N+1 per party row during mapping.
     */
    private fun emailsFor(ids: List<Long>, docs: Map<Long, Document>): Map<Long, Set<String>> {
        val personIds = docs.values.flatMap { it.parties.mapNotNull { p -> p.id } }.distinct()
        if (personIds.isEmpty()) return emptyMap()
        return personRepository.findAllByIdIn(personIds).associate { it.id!! to it.email.toSet() }
    }

    // ------------------------------------------------------------- embedding

    private fun queryVector(normalized: String): FloatArray {
        return try {
            // Cached at the source: SearchService.embedQuery carries @Cacheable
            // (Caffeine, TTL/max-size in application.yaml); key = model version
            // + normalized query, so identical queries reuse the same vector.
            searchService.embedQuery(normalized)
        } catch (e: Exception) {
            logger.error("Query embedding failed (no silent text-only fallback)", e)
            throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Embedding service unavailable; cannot run semantic search"
            )
        }
    }

    // ------------------------------------------------------------- observability

    /**
     * Per-request ranker observability (RRF practice: monitor the input
     * signals, not just the fused output). Query TEXT is never logged — only
     * its hash (search terms can be sensitive).
     */
    private fun logRankerStats(window: DocumentChunkRepository.SearchWindow, q: String) {
        logger.info(
            "search ranker stats: k={} vectorDocs={} lexicalDocs={} overlap={} matched={} queryHash={}",
            searchWindow, window.vectorDocs, window.lexicalDocs, window.overlap, window.matchedTotal,
            DigestUtils.sha256Hex(q.toByteArray(Charsets.UTF_8))
        )
    }

    // SEARCH SCORES (comment this line out to silence per-document score diagnostics)
    private fun logScores(window: DocumentChunkRepository.SearchWindow) {
        logger.info("search scores: {}", window.rows.joinToString { "${it.id}=${it.score}" })
    }

    // ------------------------------------------------------------- private utils

    private fun findDocument(id: Long): Document {
        return documentRepository.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found") }
    }

    private fun validateReferences(req: DocumentUploadRequest) {
        if (personRepository.findAllById(req.partyIds).size != req.partyIds.size) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Some parties not found")
        }
        if (tagRepository.findAllById(req.tagIds).size != req.tagIds.size) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Some tags not found")
        }
        if (collectionRepository.findAllById(req.collectionIds).size != req.collectionIds.size) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Some collections not found")
        }
    }

    private fun terminateWorkflow(workflowId: String) {
        try {
            workflowClient.newUntypedWorkflowStub(workflowId).terminate("document deleted")
        } catch (e: WorkflowNotFoundException) {
            // already finished / never started
        }
    }
}