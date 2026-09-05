package com.kiss.backend.repository

import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.flywaydb.core.Flyway
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Timestamp
import java.time.Instant

@Testcontainers
class DocumentChunkRepositoryTest {

    companion object {
        @Container
        val postgres = PostgreSQLContainer<Nothing>("pgvector/pgvector:pg17").apply {
            withDatabaseName("testdb")
            withUsername("test")
            withPassword("test")
        }

        private const val TRUNCATE_SQL =
            "TRUNCATE document, document_chunk, person, tag, collection, " +
                "collection_document_association, document_parties, document_tags " +
                "RESTART IDENTITY CASCADE"
    }

    private val fix = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun emptySearchDoesNotCrash() {
        val (jdbc, named) = setup()
        jdbc.execute(TRUNCATE_SQL)
        val repo = DocumentChunkRepository(jdbc, named)

        val window = repo.searchDocuments(
            k = 100,
            queryText = "whatever", queryVector = floatArrayOf(0.0f),
            type = null, partyIds = emptyList(), tagIds = emptyList(), collectionId = null,
            createdAfter = null, createdBefore = null, updatedAfter = null, updatedBefore = null,
            sortColumn = "createdAt", sortDirection = "desc"
        )

        assert(window.rows.isEmpty()) { "empty store must yield no matches" }
        assert(window.matchedTotal == 0)

        (jdbc.dataSource as HikariDataSource).close()
    }

    @Test
    fun searchReturnsOnlyMatchedDocuments() {
        val (jdbc, named) = setup()
        jdbc.execute(TRUNCATE_SQL)
        val repo = DocumentChunkRepository(jdbc, named)

        val docA = insertDocument(jdbc, "hash-a", "documents/hash-a.pdf", "Background report", "application/pdf", "PDF", fix.minusSeconds(3600))
        val docB = insertDocument(jdbc, "hash-b", "documents/hash-b.pdf", "Unrelated notes", "application/pdf", "PDF", fix)

        insertChunk(jdbc, docA, 0, "Background report", "TITLE")
        insertChunk(jdbc, docA, 1, "Background details paragraph about the topic.", "BODY")
        insertChunk(jdbc, docB, 0, "Unrelated notes", "TITLE")
        insertChunk(jdbc, docB, 1, "Completely different subject matter.", "BODY")

        // q="background" matches ONLY docA (title + body) — docB must not be
        // returned: nonmatches never enter the result set.
        val window = repo.searchDocuments(
            k = 100,
            queryText = "background", queryVector = null,
            type = null, partyIds = emptyList(), tagIds = emptyList(), collectionId = null,
            createdAfter = null, createdBefore = null, updatedAfter = null, updatedBefore = null,
            sortColumn = "createdAt", sortDirection = "desc"
        )
        assert(window.rows.size == 1) { "only the matching doc is returned, got ${window.rows.map { it.id }}" }
        assert(window.rows.first().id == docA)
        assert(window.matchedTotal == 1)
        assert(window.lexicalDocs == 1) { "one lexical match (docA), got ${window.lexicalDocs}" }
        assert(window.vectorDocs == 0) { "no embeddings seeded, semantic side empty" }

        (jdbc.dataSource as HikariDataSource).close()
    }

    @Test
    fun searchOrdersByTheRequestedSortField() {
        val (jdbc, named) = setup()
        jdbc.execute(TRUNCATE_SQL)
        val repo = DocumentChunkRepository(jdbc, named)

        // Both docs match "report"; ordering must follow the requested column
        // (title asc), NOT relevance.
        val docA = insertDocument(jdbc, "hash-a", "documents/hash-a.pdf", "Annual report 2025", "application/pdf", "PDF", fix.minusSeconds(7200))
        val docB = insertDocument(jdbc, "hash-b", "documents/hash-b.pdf", "Draft report 2026", "application/pdf", "PDF", fix.minusSeconds(3600))
        insertChunk(jdbc, docA, 0, "Annual report 2025", "TITLE")
        insertChunk(jdbc, docB, 0, "Draft report 2026", "TITLE")

        val window = repo.searchDocuments(
            k = 100,
            queryText = "report", queryVector = null,
            type = null, partyIds = emptyList(), tagIds = emptyList(), collectionId = null,
            createdAfter = null, createdBefore = null, updatedAfter = null, updatedBefore = null,
            sortColumn = "title", sortDirection = "asc"
        )
        assert(window.rows.map { it.id } == listOf(docA, docB)) { "date asc (tie id), got ${window.rows.map { it.id }}" }

        (jdbc.dataSource as HikariDataSource).close()
    }

    @Test
    fun browseIsUncappedAndAppliesUpdatedRange() {
        val (jdbc, named) = setup()
        jdbc.execute(TRUNCATE_SQL)
        val repo = DocumentChunkRepository(jdbc, named)

        // No chunks at all — browse must work without any search structures.
        val docA = insertDocument(jdbc, "hash-a", "documents/hash-a.pdf", "Old letters", "application/pdf", "PDF", fix.minusSeconds(7200))
        val docB = insertDocument(jdbc, "hash-b", "documents/hash-b.pdf", "Recent deeds", "application/pdf", "PDF", fix.minusSeconds(3600))
        val docC = insertDocument(jdbc, "hash-c", "documents/hash-c.pdf", "Future plans", "application/pdf", "PDF", fix)

        // Date desc: newest first; docA is excluded by the updated-range filter.
        val ids = repo.browseDocuments(
            type = null, partyIds = emptyList(), tagIds = emptyList(), collectionId = null,
            createdAfter = null, createdBefore = null,
            updatedAfter = fix.minusSeconds(7200).plusSeconds(1), updatedBefore = null,
            sortColumn = "createdAt", sortDirection = "desc"
        )
        assert(ids == listOf(docC, docB)) { "uncapped list, range-filtered, got $ids" }

        (jdbc.dataSource as HikariDataSource).close()
    }

    /** Migrates Flyway and returns (JdbcTemplate, NamedParameterJdbcTemplate). */
    private fun setup(): Pair<JdbcTemplate, NamedParameterJdbcTemplate> {
        val flyway = Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .load()
        flyway.migrate()

        val config = HikariConfig().apply {
            jdbcUrl = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
        }
        val dataSource = HikariDataSource(config)
        return JdbcTemplate(dataSource) to NamedParameterJdbcTemplate(dataSource)
    }

    private fun insertDocument(
        jdbc: JdbcTemplate,
        hash: String,
        objectKey: String,
        title: String,
        mimeType: String,
        type: String,
        createdAt: Instant
    ): Long {
        val ts = Timestamp.from(createdAt)
        return jdbc.queryForObject(
            """
            INSERT INTO document (file_hash, object_key, mime_type, title, pages, type, created_at, updated_at)
            VALUES (?, ?, ?, ?, 1, ?, ?, ?)
            RETURNING id
            """.trimIndent(),
            Long::class.java, hash, objectKey, mimeType, title, type, ts, ts
        )!!
    }

    private fun insertChunk(jdbc: JdbcTemplate, documentId: Long, chunkIndex: Int, text: String, source: String) {
        jdbc.update(
            """
            INSERT INTO document_chunk (document_id, chunk_index, chunk_text, chunk_source, embedding)
            VALUES (?, ?, ?, ?, NULL)
            """.trimIndent(),
            documentId, chunkIndex, text, source
        )
    }
}