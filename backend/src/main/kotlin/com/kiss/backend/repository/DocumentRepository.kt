package com.kiss.backend.repository

import com.kiss.backend.model.entity.Document
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface DocumentRepository : JpaRepository<Document, Long> {
    // Check if document with given file hash exists
    fun existsByFileHash(hash: String): Boolean

    // Find document by file hash
    fun findByFileHash(hash: String): Document?

    // Reconciler/lightweight passes: hash-only projection to avoid materializing
    // full entities (and their eager relations) on a periodical sweep.
    @org.springframework.data.jpa.repository.Query("SELECT d.fileHash FROM Document d")
    fun findAllFileHashes(): List<String>

    // Fetch documents with their eager relations in a single query
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = ["parties", "tags"])
    fun findAllWithRelationsByIdIn(ids: Iterable<Long>): List<Document>

    /** Zombie sweep: rows whose stored object no longer exists (delete/cancel races). */
    fun findByObjectKeyNotIn(keys: Collection<String>): List<Document>
}