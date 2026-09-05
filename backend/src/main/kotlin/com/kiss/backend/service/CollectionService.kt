package com.kiss.backend.service

import com.kiss.backend.model.entity.Collection
import com.kiss.backend.model.entity.CollectionDocumentAssociation
import com.kiss.backend.model.entity.Document
import com.kiss.backend.repository.CollectionDocumentAssociationRepository
import com.kiss.backend.repository.CollectionRepository
import com.kiss.backend.util.FractionalIndexing
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
@Transactional
class CollectionService(
    private val collectionRepository: CollectionRepository,
    private val associationRepository: CollectionDocumentAssociationRepository
) {

    /**
     * Create a new collection.
     */
    @Transactional
    fun createCollection(name: String, description: String? = null): Collection {
        val collection = Collection(name = name, description = description)
        return collectionRepository.save(collection)
    }

    /**
     * Add a document to the end of a collection.
     */
    @Transactional
    fun addDocumentToEnd(collection: Collection, document: Document): CollectionDocumentAssociation {
        val lastAssociation = associationRepository.findByCollectionOrderBySortKeyAsc(collection).lastOrNull()
        return addDocumentAt(collection, document, afterKey(lastAssociation, collection))
    }

    /**
     * Add a document to the start of a collection.
     */
    @Transactional
    fun addDocumentToStart(collection: Collection, document: Document): CollectionDocumentAssociation {
        val firstAssociation = associationRepository.findByCollectionOrderBySortKeyAsc(collection).firstOrNull()
        return addDocumentAt(collection, document, keyFor(null, firstAssociation, collection))
    }

    /**
     * Add a document before another document in the collection.
     */
    @Transactional
    fun addDocumentBefore(
        collection: Collection,
        newDocument: Document,
        beforeDocument: Document
    ): CollectionDocumentAssociation {
        val beforeAssociation = associationRepository.findByCollectionAndDocument(collection, beforeDocument)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found in collection")
        val previousAssociation = associationRepository.findPreviousAssociation(collection, beforeAssociation.sortKey)
        return addDocumentAt(
            collection,
            newDocument,
            keyFor(previousAssociation, beforeAssociation, collection)
        )
    }

    /**
     * Add a document after another document in the collection.
     */
    @Transactional
    fun addDocumentAfter(
        collection: Collection,
        newDocument: Document,
        afterDocument: Document
    ): CollectionDocumentAssociation {
        val afterAssociation = associationRepository.findByCollectionAndDocument(collection, afterDocument)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found in collection")
        val nextAssociation = associationRepository.findNextAssociation(collection, afterAssociation.sortKey)
        return addDocumentAt(
            collection,
            newDocument,
            keyFor(afterAssociation, nextAssociation, collection)
        )
    }

    /**
     * Internal: Add a document at a specific key value.
     */
    private fun addDocumentAt(
        collection: Collection,
        document: Document,
        sortKey: String
    ): CollectionDocumentAssociation {
        // Check if document already exists in collection (DB unique constraint is the
        // hard guard for concurrent duplicates -> DataIntegrityViolation 409).
        associationRepository.findByCollectionAndDocument(collection, document)?.let {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Document already exists in this collection")
        }

        val association = CollectionDocumentAssociation(
            collection = collection,
            document = document,
            sortKey = sortKey
        )
        return associationRepository.save(association)
    }

    /**
     * Remove a document from a collection.
     */
    @Transactional
    fun removeDocumentFromCollection(collection: Collection, document: Document) {
        val association = associationRepository.findByCollectionAndDocument(collection, document)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found in collection")
        associationRepository.delete(association)
    }

    /**
     * Get all documents in a collection, ordered.
     */
    @Transactional
    fun getDocumentsInOrder(collection: Collection): List<Document> {
        return associationRepository.findByCollectionWithDocumentsOrderBySortKeyAsc(collection)
            .map { it.document }
    }

    /**
     * All memberships of a collection in member order (eager documents).
     * Plain unbounded list — collection membership browsing needs no paging.
     */
    @Transactional(readOnly = true)
    fun getDocuments(collection: Collection): List<CollectionDocumentAssociation> =
        associationRepository.findByCollectionWithDocumentsOrderBySortKeyAsc(collection)

    /**
     * Move a document to a new position: before another document.
     */
    @Transactional
    fun moveDocumentBefore(
        collection: Collection,
        documentToMove: Document,
        beforeDocument: Document
    ): CollectionDocumentAssociation {
        if (documentToMove.id == beforeDocument.id) {
            return associationRepository.findByCollectionAndDocument(collection, documentToMove)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found in collection")
        }
        removeDocumentFromCollection(collection, documentToMove)
        return addDocumentBefore(collection, documentToMove, beforeDocument)
    }

    /**
     * Move a document to a new position: after another document.
     */
    @Transactional
    fun moveDocumentAfter(
        collection: Collection,
        documentToMove: Document,
        afterDocument: Document
    ): CollectionDocumentAssociation {
        if (documentToMove.id == afterDocument.id) {
            return associationRepository.findByCollectionAndDocument(collection, documentToMove)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found in collection")
        }
        removeDocumentFromCollection(collection, documentToMove)
        return addDocumentAfter(collection, documentToMove, afterDocument)
    }

    /**
     * Move a document to the start.
     */
    @Transactional
    fun moveDocumentToStart(collection: Collection, document: Document): CollectionDocumentAssociation {
        removeDocumentFromCollection(collection, document)
        return addDocumentToStart(collection, document)
    }

    /**
     * Move a document to the end.
     */
    @Transactional
    fun moveDocumentToEnd(collection: Collection, document: Document): CollectionDocumentAssociation {
        removeDocumentFromCollection(collection, document)
        return addDocumentToEnd(collection, document)
    }

    /**
     * Delete a collection and all its associations.
     */
    @Transactional
    fun deleteCollection(collection: Collection) {
        collectionRepository.delete(collection)
    }

    /**
     * Produces a fractional key between [beforeAssoc] and [afterAssoc]. If the key space
     * is exhausted (never in practice) the collection is renumbered first.
     */
    private fun keyFor(
        beforeAssoc: CollectionDocumentAssociation?,
        afterAssoc: CollectionDocumentAssociation?,
        collection: Collection
    ): String {
        return FractionalIndexing.between(beforeAssoc?.sortKey, afterAssoc?.sortKey)
            ?: rebalance(collection).let { rebalanced ->
                val newBefore = beforeAssoc?.let { b -> rebalanced.find { it.id == b.id }?.sortKey }
                val newAfter = afterAssoc?.let { a -> rebalanced.find { it.id == a.id }?.sortKey }
                FractionalIndexing.between(newBefore, newAfter)
                    ?: throw IllegalStateException("Cannot generate ordering key")
            }
    }

    /**
     * Produces a key strictly greater than [orderAssoc] (start when null).
     */
    private fun afterKey(
        orderAssoc: CollectionDocumentAssociation?,
        collection: Collection
    ): String {
        return FractionalIndexing.after(orderAssoc?.sortKey)
            ?: rebalance(collection).let { rebalanced ->
                val newOrder = orderAssoc?.let { o -> rebalanced.find { it.id == o.id }?.sortKey }
                FractionalIndexing.after(newOrder)
                    ?: throw IllegalStateException("Cannot generate ordering key")
            }
    }

    /**
     * Renumbers all associations of [collection] with fresh evenly-spaced keys.
     */
    private fun rebalance(collection: Collection): List<CollectionDocumentAssociation> {
        val ordered = associationRepository.findByCollectionOrderBySortKeyAsc(collection)
        var key = FractionalIndexing.start()
        ordered.forEach { association ->
            association.sortKey = key
            associationRepository.save(association)
            key = FractionalIndexing.after(key)!!
        }
        return ordered
    }
}