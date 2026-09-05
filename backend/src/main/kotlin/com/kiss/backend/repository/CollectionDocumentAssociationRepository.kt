package com.kiss.backend.repository

import com.kiss.backend.model.entity.Collection
import com.kiss.backend.model.entity.CollectionDocumentAssociation
import com.kiss.backend.model.entity.Document
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface CollectionDocumentAssociationRepository : JpaRepository<CollectionDocumentAssociation, Long> {
    fun findByCollection(collection: Collection): List<CollectionDocumentAssociation>

    fun findByCollectionOrderBySortKeyAsc(collection: Collection): List<CollectionDocumentAssociation>

    fun findByCollectionAndDocument(collection: Collection, document: Document): CollectionDocumentAssociation?

    fun findByDocument(document: Document): List<CollectionDocumentAssociation>

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = ["collection"])
    fun findByDocumentIn(documents: Iterable<Document>): List<CollectionDocumentAssociation>

    @Query("SELECT a FROM CollectionDocumentAssociation a JOIN FETCH a.document d LEFT JOIN FETCH d.parties LEFT JOIN FETCH d.tags WHERE a.collection = :collection ORDER BY a.sortKey ASC")
    fun findByCollectionWithDocumentsOrderBySortKeyAsc(collection: Collection): List<CollectionDocumentAssociation>

    @Query("SELECT cda FROM CollectionDocumentAssociation cda WHERE cda.collection = :collection AND cda.sortKey < :sortKey ORDER BY cda.sortKey DESC LIMIT 1")
    fun findPreviousAssociation(collection: Collection, sortKey: String): CollectionDocumentAssociation?

    @Query("SELECT cda FROM CollectionDocumentAssociation cda WHERE cda.collection = :collection AND cda.sortKey > :sortKey ORDER BY cda.sortKey ASC LIMIT 1")
    fun findNextAssociation(collection: Collection, sortKey: String): CollectionDocumentAssociation?
}