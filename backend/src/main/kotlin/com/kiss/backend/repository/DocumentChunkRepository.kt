package com.kiss.backend.repository

import com.kiss.backend.model.entity.DocumentType
import com.pgvector.PGhalfvec
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

/**
 * Postgres-native search & chunk store (pgvector + pg_trgm + FTS).
 *
 * ## Unified documents listing (browse + search)
 * One endpoint serves both:
 *  - **Browse** ([browseDocuments]): pure metadata listing — filters + sort
 *    directly over `document`. NO embeddings, NO chunks, NO ranking, and NO
 *    artificial cap (every matching document is returned).
 *  - **Search** ([searchDocuments]): relevance retrieval for a non-empty `q`.
 *    A document is returned ONLY if at least one retriever ranked it inside
 *    its bounded top-k window — nonmatches never enter the result set. The
 *    window `k` bounds WORK (per-retriever depth), not pages: since delivery
 *    is a single SSE stream (no cursor/page paging, see DocumentService),
 *    the window is computed ONCE per search and everything matched is streamed.
 *
 * ## Ordering
 * Both paths order by the SAME requested sort field (`sortColumn`/
 * `sortDirection`, whitelisted) with a stable `id ASC` tiebreak. Search keeps
 * this field ordering too — the RRF score still determines WHICH documents
 * are in the window and is logged server-side, but it does not order the
 * output (the product sorts the table by a column, not by relevance).
 *
 * ## Fixed retrieval window (search only)
 * Each retriever emits its top-k documents (over-fetching chunks ×
 * [OVERFETCH_CHUNKS] before document-level dedup so a single long document
 * cannot monopolize the window), then the bounded union is fused with RRF for
 * score/statistics. Width `k` is a deployment constant (`faldony.search.window`),
 * never exposed to clients.
 *
 * ## Tie-breakers & chunk dedup
 * Every ranker orders by (score, document_id[, chunk_index]) — deterministic.
 * Chunk rankers over-fetch [OVERFETCH_CHUNKS]×k chunks, assign per-chunk ranks,
 * then keep the BEST rank per document.
 *
 * ## Title retriever
 * Matches via the pg_trgm `%>` operator (word_similarity(query, title) above
 * `pg_trgm.word_similarity_threshold`): only matching titles enter, no boosted
 * RRF points for zero-similarity titles; GIN-accelerated on `title`.
 *
 * ## Iterative scans / knobs
 * The search GUCs (`hnsw.iterative_scan`, `hnsw.ef_search`,
 * `pg_trgm.word_similarity_threshold`) are DATABASE-level session defaults
 * applied once by migration V1 — every pooled connection inherits them on
 * first use. No per-request `SET LOCAL` plumbing, no silent no-op when a call
 * happens outside a transaction. `iterative_scan = relaxed_order` (pgvector
 * >= 0.8) keeps filtered HNSW scans walking the graph until LIMIT is
 * satisfied; relaxed (vs strict) distance order is safe here because the
 * final ORDER BY is a metadata field, not distance.
 *
 * ## Write model
 * Chunks are inserted EXACTLY ONCE at finalize (plain INSERT; the
 * (document_id, chunk_index) constraint is the hard guard). Only the
 * title-update path re-writes chunk 0 (idempotent upsert). Vectors are bound
 * as native halfvec values via `com.pgvector:pgvector` (PGhalfvec); reading
 * them back needs no type registration because this code never selects
 * embedding columns.
 */
@Repository
class DocumentChunkRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val namedJdbcTemplate: NamedParameterJdbcTemplate
) {

    /** A ranked row: the document id plus the RRF score (log-only). */
    data class RankedDocument(
        val id: Long,
        val score: Double
    )

    /** One search computation: ranked rows + ranker health + match total. */
    data class SearchWindow(
        val rows: List<RankedDocument>,
        val vectorDocs: Int,
        val lexicalDocs: Int,
        val overlap: Int,
        /** Distinct documents matched inside the window (drives `truncated`). */
        val matchedTotal: Int
    )

    // ------------------------------------------------------------------ browse

    /**
     * Metadata browse: every filter-matching document, ordered by the
     * requested sort field, NO cap (a plain indexed list — the "no q" path).
     */
    fun browseDocuments(
        type: DocumentType?,
        partyIds: List<Long>,
        tagIds: List<Long>,
        collectionId: Long?,
        createdAfter: Instant?,
        createdBefore: Instant?,
        updatedAfter: Instant?,
        updatedBefore: Instant?,
        sortColumn: String,
        sortDirection: String
    ): List<Long> {
        val filterClause = buildFilterClause(
            type, partyIds, tagIds, collectionId,
            createdAfter, createdBefore, updatedAfter, updatedBefore
        )
        // Browse has no RRF CTE: the `relevance` pseudo-column must never reach
        // this path — fall back to the default metadata sort.
        val orderBy = SORT_COLUMNS[sortColumn]?.takeUnless { it == "rfd.score" }
            ?: SORT_COLUMNS.getValue(DEFAULT_SORT)
        val direction = if (sortDirection.equals("asc", ignoreCase = true)) "ASC" else "DESC"

        val sql = """
            SELECT d.id AS document_id
            FROM document d
            WHERE 1 = 1$filterClause
            ORDER BY $orderBy $direction, d.id ASC
        """.trimIndent()

        val params = bindFilterParams(
            MapSqlParameterSource(), type, partyIds, tagIds, collectionId,
            createdAfter, createdBefore, updatedAfter, updatedBefore
        )
        return namedJdbcTemplate.queryForList(sql, params, Long::class.java).mapNotNull { it }
    }

    // ------------------------------------------------------------------ search

    /**
     * Relevance search over a FIXED window [k]. Every retriever emits its
     * top-k documents; RRF fuses only those (for score/stats). Result is
     * ordered by the requested sort FIELD (see class doc), id tiebreak.
     *
     * Nonmatches never enter the result set. [matchedTotal] reports how many
     * matched documents exist inside the window (used to detect truncation
     * when it equals k).
     */
    fun searchDocuments(
        k: Int,
        queryText: String,
        queryVector: FloatArray?,
        type: DocumentType?,
        partyIds: List<Long>,
        tagIds: List<Long>,
        collectionId: Long?,
        createdAfter: Instant?,
        createdBefore: Instant?,
        updatedAfter: Instant?,
        updatedBefore: Instant?,
        sortColumn: String,
        sortDirection: String
    ): SearchWindow {
        val text = queryText.trim()
        val hasText = text.isNotEmpty()
        val hasVector = queryVector != null && queryVector.isNotEmpty()
        val filterClause = buildFilterClause(
            type, partyIds, tagIds, collectionId,
            createdAfter, createdBefore, updatedAfter, updatedBefore
        )
        val orderBy = SORT_COLUMNS[sortColumn] ?: SORT_COLUMNS.getValue(DEFAULT_SORT)
        val direction = if (sortDirection.equals("asc", ignoreCase = true)) "ASC" else "DESC"
        // Sort tokens:
        //   "relevance"  -> ranked search orders by the RRF score alone
        //   any column   -> the caller's metadata sort re-orders the matched
        //                   (relevance-determined) set; score stays tiebreak
        //   browse       -> plain metadata order, no ranking involved
        val orderClause = when {
            sortColumn.equals("relevance", ignoreCase = true) ->
                "rfd.score DESC, rfd.document_id ASC"
            hasText || hasVector ->
                "$orderBy $direction, rfd.score DESC, rfd.document_id ASC"
            else ->
                "$orderBy $direction, rfd.document_id ASC"
        }

        val sql = """
            WITH candidates AS (
                SELECT d.id AS document_id
                FROM document d
                WHERE 1 = 1$filterClause
            ),
            ${bm25Cte(hasText)},
            ${trgmCte(hasText)},
            ${hnswCte(hasVector)},
            ${titleCte(hasText)},
            matched AS MATERIALIZED (
                SELECT document_id FROM bm25
                UNION SELECT document_id FROM trgm
                UNION SELECT document_id FROM hnsw
                UNION SELECT document_id FROM title
            ),
            rrf AS (
                SELECT m.document_id,
                       COALESCE((SELECT 1.0 / (60 + MIN(b.rnk)) FROM bm25 b WHERE b.document_id = m.document_id), 0.0)
                     + COALESCE((SELECT 1.0 / (60 + MIN(t.rnk)) FROM trgm t WHERE t.document_id = m.document_id), 0.0)
                     + COALESCE((SELECT 1.0 / (60 + MIN(h.rnk)) FROM hnsw h WHERE h.document_id = m.document_id), 0.0)
                     + $TITLE_BOOST * COALESCE((SELECT 1.0 / (60 + r.rnk) FROM title r WHERE r.document_id = m.document_id), 0.0) AS score,
                       EXISTS(SELECT 1 FROM bm25 b WHERE b.document_id = m.document_id) AS f_bm25,
                       EXISTS(SELECT 1 FROM trgm t WHERE t.document_id = m.document_id) AS f_trgm,
                       EXISTS(SELECT 1 FROM hnsw h WHERE h.document_id = m.document_id) AS f_hnsw,
                       EXISTS(SELECT 1 FROM title r WHERE r.document_id = m.document_id) AS f_title
                FROM matched m
            )
            SELECT rfd.document_id,
                   rfd.score,
                   COUNT(*) OVER () AS matched_total,
                   -- rrf emits exactly one row per document, so plain counts
                   -- (no DISTINCT — not supported in window functions)
                   -- are already distinct-by-document.
                   COUNT(*) FILTER (WHERE rfd.f_hnsw) OVER () AS vector_count,
                   COUNT(*) FILTER (WHERE rfd.f_bm25 OR rfd.f_trgm OR rfd.f_title) OVER () AS lexical_count,
                   COUNT(*) FILTER (WHERE rfd.f_hnsw AND (rfd.f_bm25 OR rfd.f_trgm OR rfd.f_title)) OVER () AS overlap_count
            FROM rrf rfd
            JOIN document d ON d.id = rfd.document_id
            ORDER BY $orderClause
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("k", k)
            .addValue("overfetchK", k * OVERFETCH_CHUNKS)
            .addValue("queryText", text)
            .addValue("queryVector", queryVector?.let { PGhalfvec(it) })
        bindFilterParams(
            params, type, partyIds, tagIds, collectionId,
            createdAfter, createdBefore, updatedAfter, updatedBefore
        )

        // Window functions run before any LIMIT, so the per-row aggregates are
        // the request's global values (last row wins).
        val stats = IntArray(3)
        var matchedTotal = 0
        val rows = namedJdbcTemplate.query(sql, params) { rs, _ ->
            stats[0] = rs.getInt("vector_count")
            stats[1] = rs.getInt("lexical_count")
            stats[2] = rs.getInt("overlap_count")
            matchedTotal = rs.getInt("matched_total")
            RankedDocument(
                id = rs.getLong("document_id"),
                score = rs.getDouble("score")
            )
        }

        return SearchWindow(
            rows = rows,
            vectorDocs = stats[0],
            lexicalDocs = stats[1],
            overlap = stats[2],
            matchedTotal = matchedTotal
        )
    }

    // ------------------------------------------------------------------ writes

    fun deleteChunksForDocument(documentId: Long) {
        jdbcTemplate.update("DELETE FROM document_chunk WHERE document_id = ?", documentId)
    }

    /**
     * Single-write finalize insert (no upsert): each (document_id, chunk_index)
     * is written exactly once; the unique constraint is the hard guard.
     * Vector bound as a halfvec PGobject (native type, no string casts).
     */
    fun insertChunk(documentId: Long, chunkIndex: Int, chunkText: String, chunkSource: String, embedding: FloatArray?) {
        jdbcTemplate.update(
            """
            INSERT INTO document_chunk (document_id, chunk_index, chunk_text, chunk_source, embedding)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            documentId, chunkIndex, chunkText, chunkSource, embedding?.let { PGhalfvec(it) }
        )
    }

    /** Title-update upsert: idempotent (see DocumentActivities.updateTitleDocument). */
    fun insertTitleChunk(documentId: Long, title: String, embedding: FloatArray?) {
        jdbcTemplate.update(
            """
            INSERT INTO document_chunk (document_id, chunk_index, chunk_text, chunk_source, embedding)
            VALUES (?, 0, ?, 'TITLE', ?)
            ON CONFLICT (document_id, chunk_index)
            DO UPDATE SET chunk_text = EXCLUDED.chunk_text,
                          chunk_source = EXCLUDED.chunk_source,
                          embedding = EXCLUDED.embedding,
                          tsv = NULL
            """.trimIndent(),
            documentId, title, embedding?.let { PGhalfvec(it) }
        )
    }

    /** Current chunk-0 (title) text, or null when no title chunk exists yet. */
    fun titleChunkText(documentId: Long): String? = runCatching {
        jdbcTemplate.queryForObject(
            "SELECT chunk_text FROM document_chunk WHERE document_id = ? AND chunk_index = 0",
            String::class.java, documentId
        )
    }.getOrNull()

    // --------------------------------------------------------- embedding backfill

    /** New or degraded documents: those having at least one chunk without vectors. */
    fun findDocumentIdsWithMissingEmbeddings(): List<Long> =
        jdbcTemplate.queryForList(
            "SELECT DISTINCT document_id FROM document_chunk WHERE embedding IS NULL AND document_id IS NOT NULL",
            Long::class.java
        ).mapNotNull { it }

    /** (chunk_index, chunk_text) of the chunks of one document lacking vectors. */
    fun chunksMissingEmbedding(documentId: Long): List<Pair<Int, String>> =
        jdbcTemplate.query(
            "SELECT chunk_index, chunk_text FROM document_chunk WHERE document_id = ? AND embedding IS NULL ORDER BY chunk_index",
            { rs, _ -> rs.getInt(1) to rs.getString(2) },
            documentId
        )

    /** Vector-only upsert guarded on NULL: retries can never clobber a fresh vector. */
    fun updateChunkEmbedding(documentId: Long, chunkIndex: Int, embedding: FloatArray) {
        jdbcTemplate.update(
            "UPDATE document_chunk SET embedding = ? WHERE document_id = ? AND chunk_index = ? AND embedding IS NULL",
            PGhalfvec(embedding), documentId, chunkIndex
        )
    }

    // ------------------------------------------------------------ SQL builders

    /**
     * Chunk ranker: over-fetch [OVERFETCH_CHUNKS]×k chunks in deterministic
     * order, assign per-chunk ranks (window), keep the BEST rank per document,
     * cap at k documents. Document dedup happens BEFORE RRF so a single long
     * document cannot dominate the window.
     *
     * @param sortExpr   similarity/distance expression, alias `sim` (c-scoped)
     * @param windowOrder columns available in the window level: `sim`,
     *                    `document_id`, `chunk_index` (all projected by the
     *                    inner select — the c-aliased expression is NOT in the
     *                    window function's scope)
     * @param ascending  distances sort ascending (hnsw), similarities desc
     */
    private fun chunkRankerCte(
        alias: String,
        enabled: Boolean,
        matchPredicate: String,
        sortExpr: String,
        windowOrder: String,
        ascending: Boolean
    ): String {
        if (!enabled) return EMPTY_CTE(alias)
        val dir = if (ascending) "ASC" else "DESC"
        return """
            $alias AS MATERIALIZED (
                SELECT document_id, MIN(rnk) AS rnk
                FROM (
                    SELECT document_id, chunk_index,
                           row_number() OVER (ORDER BY $windowOrder) AS rnk
                    FROM (
                        SELECT c.document_id, c.chunk_index, $sortExpr AS sim
                        FROM document_chunk c
                        WHERE c.document_id IN (SELECT document_id FROM candidates)
                          AND $matchPredicate
                        ORDER BY $sortExpr $dir, c.document_id ASC, c.chunk_index ASC
                        LIMIT :overfetchK
                    ) limited
                ) ranked
                GROUP BY document_id
                ORDER BY rnk ASC
                LIMIT :k
            )
        """.trimIndent()
    }

    private val EMPTY_CTE: (String) -> String = { alias ->
        """$alias AS MATERIALIZED (SELECT CAST(NULL AS BIGINT) AS document_id, CAST(NULL AS BIGINT) AS rnk WHERE 1 = 0)"""
    }

    private fun bm25Cte(hasText: Boolean): String =
        chunkRankerCte(
            "bm25", hasText,
            matchPredicate = "c.tsv @@ plainto_tsquery('faldony_bi', :queryText)",
            sortExpr = "ts_rank_cd(c.tsv, plainto_tsquery('faldony_bi', :queryText))",
            windowOrder = "sim DESC, document_id ASC, chunk_index ASC",
            ascending = false
        )

    private fun trgmCte(hasText: Boolean): String =
        chunkRankerCte(
            "trgm", hasText,
            matchPredicate = "c.chunk_text ILIKE '%' || :queryText || '%'",
            sortExpr = "similarity(c.chunk_text, :queryText)",
            windowOrder = "sim DESC, document_id ASC, chunk_index ASC",
            ascending = false
        )

    private fun hnswCte(hasVector: Boolean): String =
        chunkRankerCte(
            "hnsw", hasVector,
            matchPredicate = "c.embedding IS NOT NULL",
            sortExpr = "c.embedding <=> :queryVector",
            windowOrder = "sim ASC, document_id ASC, chunk_index ASC",
            ascending = true
        )

    /**
     * Title retriever: ONLY matching titles enter (see class doc). Indexed via
     * the `%>` GIN recheck on `title`.
     */
    private fun titleCte(hasText: Boolean): String =
        if (hasText) """
            title AS MATERIALIZED (
                SELECT document_id, MIN(rnk) AS rnk
                FROM (
                    SELECT document_id,
                           row_number() OVER (ORDER BY sim DESC, document_id ASC) AS rnk
                    FROM (
                        SELECT d.id AS document_id,
                               word_similarity(:queryText, d.title) AS sim
                        FROM document d
                        WHERE d.id IN (SELECT document_id FROM candidates)
                          AND d.title %> :queryText
                        ORDER BY word_similarity(:queryText, d.title) DESC, d.id ASC
                        LIMIT :k
                    ) limited
                ) ranked
                GROUP BY document_id
                ORDER BY rnk ASC
                LIMIT :k
            )
        """.trimIndent()
        else EMPTY_CTE("title")

    // ------------------------------------------------------------------ utils

    private fun buildFilterClause(
        type: DocumentType?,
        partyIds: List<Long>,
        tagIds: List<Long>,
        collectionId: Long?,
        createdAfter: Instant?,
        createdBefore: Instant?,
        updatedAfter: Instant?,
        updatedBefore: Instant?
    ): String = buildString {
        if (type != null) append(" AND d.type = :type")
        if (partyIds.isNotEmpty()) append(
            " AND EXISTS (SELECT 1 FROM document_parties dp WHERE dp.document_id = d.id AND dp.person_id IN (:partyIds))"
        )
        if (tagIds.isNotEmpty()) append(
            " AND EXISTS (SELECT 1 FROM document_tags dt WHERE dt.document_id = d.id AND dt.tag_id IN (:tagIds))"
        )
        if (collectionId != null) append(
            " AND EXISTS (SELECT 1 FROM collection_document_association cda WHERE cda.document_id = d.id AND cda.collection_id = :collectionId)"
        )
        if (createdAfter != null) append(" AND d.created_at >= :createdAfter")
        if (createdBefore != null) append(" AND d.created_at <= :createdBefore")
        if (updatedAfter != null) append(" AND d.updated_at >= :updatedAfter")
        if (updatedBefore != null) append(" AND d.updated_at <= :updatedBefore")
    }

    private fun bindFilterParams(
        params: MapSqlParameterSource,
        type: DocumentType?,
        partyIds: List<Long>,
        tagIds: List<Long>,
        collectionId: Long?,
        createdAfter: Instant?,
        createdBefore: Instant?,
        updatedAfter: Instant?,
        updatedBefore: Instant?
    ): MapSqlParameterSource {
        if (type != null) params.addValue("type", type.name)
        if (partyIds.isNotEmpty()) params.addValue("partyIds", partyIds)
        if (tagIds.isNotEmpty()) params.addValue("tagIds", tagIds)
        if (collectionId != null) params.addValue("collectionId", collectionId)
        if (createdAfter != null) params.addValue("createdAfter", Timestamp.from(createdAfter))
        if (createdBefore != null) params.addValue("createdBefore", Timestamp.from(createdBefore))
        if (updatedAfter != null) params.addValue("updatedAfter", Timestamp.from(updatedAfter))
        if (updatedBefore != null) params.addValue("updatedBefore", Timestamp.from(updatedBefore))
        return params
    }

    companion object {
        const val TITLE_BOOST = 3.0
        const val DEFAULT_SORT = "createdAt"

        /** Chunk over-fetch factor before document-level dedup per ranker. */
        const val OVERFETCH_CHUNKS = 4

        /** Whitelisted sort columns (user input is never interpolated into SQL).
         *  `relevance` is a pseudo-column: it is allowed by `parseSort` and
         *  resolved to the RRF score ordering inside [searchDocuments]. */
        val SORT_COLUMNS: Map<String, String> = mapOf(
            "relevance" to "rfd.score",
            "createdAt" to "d.created_at",
            "updatedAt" to "d.updated_at",
            "title" to "d.title",
            "type" to "d.type",
            "pages" to "d.pages"
        )
    }
}