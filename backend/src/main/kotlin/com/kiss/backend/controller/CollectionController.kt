package com.kiss.backend.controller

import com.kiss.backend.model.dto.AddDocumentToCollectionRequest
import com.kiss.backend.model.dto.CollectionCreateRequest
import com.kiss.backend.model.dto.CollectionDto
import com.kiss.backend.model.dto.DocumentDto
import com.kiss.backend.model.dto.MoveDocumentRequest
import com.kiss.backend.model.dto.MovePosition
import com.kiss.backend.model.entity.Collection
import com.kiss.backend.model.entity.Document
import com.kiss.backend.repository.CollectionDocumentAssociationRepository
import com.kiss.backend.repository.CollectionRepository
import com.kiss.backend.service.CollectionService
import com.kiss.backend.service.DocumentService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/v1/collections")
@Tag(name = "Collections", description = "Collection (document groups) management endpoints")
class CollectionController(
    private val collectionRepository: CollectionRepository,
    private val collectionService: CollectionService,
    private val documentService: DocumentService,
    private val associationRepository: CollectionDocumentAssociationRepository
) {

    /** Full list (reference data — plain, unbounded; used for dropdowns too). */
    @GetMapping
    @Operation(summary = "List all collections")
    @ApiResponse(responseCode = "200", description = "List of collections")
    fun list(): List<CollectionDto> =
        collectionRepository.findAll().map { it.toDto() }

    @GetMapping("/{id}")
    @Operation(summary = "Collection detail")
    @ApiResponse(responseCode = "200", description = "Collection details")
    @ApiResponse(responseCode = "404", description = "Collection not found")
    fun getById(@PathVariable id: Long): ResponseEntity<CollectionDto> =
        ResponseEntity.ok(findCollection(id).toDto())

    @GetMapping("/{id}/documents")
    @Operation(summary = "Get the documents in a collection (member order)")
    @ApiResponse(responseCode = "200", description = "List of documents")
    @ApiResponse(responseCode = "404", description = "Collection not found")
    fun getDocuments(@PathVariable id: Long): ResponseEntity<List<DocumentDto>> {
        val collection = findCollection(id)
        val associations = collectionService.getDocuments(collection)
        return ResponseEntity.ok(documentService.documentsFromAssociations(associations))
    }

    @PostMapping
    @Operation(summary = "Create a collection")
    @ApiResponse(responseCode = "201", description = "Collection created")
    fun create(@Valid @RequestBody req: CollectionCreateRequest): ResponseEntity<CollectionDto> {
        val saved = collectionService.createCollection(req.name, req.description)
        return ResponseEntity.status(HttpStatus.CREATED).body(saved.toDto())
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a collection (and its memberships)")
    @ApiResponse(responseCode = "204", description = "Collection deleted")
    @ApiResponse(responseCode = "404", description = "Collection not found")
    fun delete(@PathVariable id: Long): ResponseEntity<Void> {
        collectionService.deleteCollection(findCollection(id))
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{id}/documents")
    @Operation(summary = "Add a released document to the end of a collection")
    @ApiResponse(responseCode = "201", description = "Document added")
    @ApiResponse(responseCode = "404", description = "Collection or Document not found")
    fun addDocument(
        @PathVariable id: Long,
        @Valid @RequestBody req: AddDocumentToCollectionRequest
    ): ResponseEntity<Void> {
        val collection = findCollection(id)
        val document = documentService.getDocumentEntity(req.documentId)
        collectionService.addDocumentToEnd(collection, document)
        return ResponseEntity.status(HttpStatus.CREATED).build()
    }

    @DeleteMapping("/{id}/documents/{documentId}")
    @Operation(summary = "Remove a document from a collection")
    @ApiResponse(responseCode = "204", description = "Document removed")
    @ApiResponse(responseCode = "404", description = "Collection, Document, or membership not found")
    fun removeDocument(
        @PathVariable id: Long,
        @PathVariable documentId: Long
    ): ResponseEntity<Void> {
        val collection = findCollection(id)
        val document = documentService.getDocumentEntity(documentId)
        collectionService.removeDocumentFromCollection(collection, document)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{id}/documents/{documentId}/move")
    @Operation(summary = "Move a document within the collection (start/end/before/after)")
    @ApiResponse(responseCode = "204", description = "Document moved")
    @ApiResponse(responseCode = "404", description = "Collection, Document, or membership not found")
    fun moveDocument(
        @PathVariable id: Long,
        @PathVariable documentId: Long,
        @Valid @RequestBody request: MoveDocumentRequest
    ): ResponseEntity<Void> {
        val collection = findCollection(id)
        val document = documentService.getDocumentEntity(documentId)
        when (request.position) {
            MovePosition.START -> collectionService.moveDocumentToStart(collection, document)
            MovePosition.END -> collectionService.moveDocumentToEnd(collection, document)
            MovePosition.BEFORE -> {
                val target = requireTarget(request.targetDocumentId)
                collectionService.moveDocumentBefore(collection, document, target)
            }
            MovePosition.AFTER -> {
                val target = requireTarget(request.targetDocumentId)
                collectionService.moveDocumentAfter(collection, document, target)
            }
        }
        return ResponseEntity.noContent().build()
    }

    private fun requireTarget(targetDocumentId: Long?): Document =
        documentService.getDocumentEntity(
            targetDocumentId ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "targetDocumentId is required for BEFORE/AFTER")
        )

    private fun findCollection(id: Long): Collection =
        collectionRepository.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Collection not found") }

    private fun Collection.toDto(): CollectionDto = CollectionDto(
        id = id!!,
        name = name,
        description = description,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}