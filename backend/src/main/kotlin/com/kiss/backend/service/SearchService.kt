package com.kiss.backend.service

import org.slf4j.LoggerFactory
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.beans.factory.annotation.Value
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service

/**
 * On-device ONNX embedding provider (Spring AI TransformersEmbeddingModel, e5-base, 768 dims).
 *
 * Two entry points with the CORRECT e5 prefixes — this is load-bearing:
 *   - [embedQuery]  prepends `query: `   (used for the search box input)
 *   - [embedPassages] prepends `passage: ` (used for indexed chunks/titles)
 * The two prefixes tune the same e5 model differently; crossing them up
 * (e.g. wrapping `query:` inside a `passage:` prefix) silently degrades the
 * semantic retriever and was a real production bug here.
 *
 * [Applies] never silently degrade: the callers decide (search returns 503 via
 * ResponseStatusException in DocumentService when embedding fails) — a search
 * that "just runs text-only" hides a broken semantic arm from monitoring and
 * produces inconsistent pages across a cursor sequence.
 */
@Service
class SearchService(
    private val embeddingModel: EmbeddingModel,
    @Value("\${faldony.search.embedding-model}") private val modelVersion: String
) {

    private val logger = LoggerFactory.getLogger(SearchService::class.java)

    val expectedDimension: Int
        get() = EMBEDDING_DIMENSIONS

    /** Human-readable model tag surfaced in search responses/cursors. */
    fun modelVersion(): String = modelVersion

    /**
     * Embeds the raw user query with the `query:` prefix.
     * Throws on failure — see class docs for why there is no silent fallback.
     *
     * Cached in Caffeine (cache `queryEmbeddings`, TTL/max-size configured in
     * application.yaml under `spring.cache.*`): pagination re-embeds the same
     * query on every page (~50-200ms ONNX each), and reusing the SAME cached
     * vector across a cursor sequence prevents ranks from drifting
     * mid-pagination. The key couples the ranking version (so bumping
     * `faldony.search.ranking-version` invalidates entries immediately) with
     * the (already normalized) text.
     */
    @Cacheable(cacheNames = ["queryEmbeddings"], key = "#root.target.modelVersion() + '|' + #text")
    fun embedQuery(text: String): FloatArray {
        val doubles = embeddingModel.embed(listOf("query: $text")).firstOrNull()
            ?: throw IllegalStateException("Embedding model returned no vector")
        val vector = doubles.map { it }.toFloatArray()
        validateDimension(vector, "query")
        return vector
    }

    /**
     * Embeds indexed texts with the `passage:` prefix. Every returned vector
     * is validated to have exactly [EMBEDDING_DIMENSIONS] dimensions so a
     * model/format drift fails loudly at index time instead of silently
     * poisoning the vector column with wrong-dimension data.
     *
     * Texts are truncated to [MAX_PASSAGE_CHARS] BEFORE the ONNX call: e5-base
     * has a 512-token context window and the ONNX runtime throws
     * "Token indices sequence length is longer than the specified maximum
     * sequence length" on longer inputs (seen in logs: 2001 > 512). Docling's
     * max_tokens cap is best-effort (merge_peers/markdown tables can exceed
     * it), so this guard is the hard safety net. Truncation is a pragmatic
     * trade-off: a degraded tail beats a permanently missing vector.
     */
    fun embedPassages(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val prefixed = texts.map { raw ->
            val text = if (raw.length > MAX_PASSAGE_CHARS) {
                logger.warn(
                    "Truncating passage from {} chars to {} (e5 512-token window)",
                    raw.length, MAX_PASSAGE_CHARS,
                )
                raw.substring(0, MAX_PASSAGE_CHARS)
            } else {
                raw
            }
            "passage: $text"
        }
        val vectors = embeddingModel.embed(prefixed).map { doubles ->
            doubles.map { it }.toFloatArray()
        }
        if (vectors.any { it.size != EMBEDDING_DIMENSIONS }) {
            throw IllegalStateException(
                "Embedding produced ${vectors.first { it.size != EMBEDDING_DIMENSIONS }.size} dims, expected $EMBEDDING_DIMENSIONS"
            )
        }
        return vectors
    }

    private fun validateDimension(vector: FloatArray, label: String) {
        if (vector.size != EMBEDDING_DIMENSIONS) {
            throw IllegalStateException("$label embedding has ${vector.size} dims, expected $EMBEDDING_DIMENSIONS")
        }
    }

    companion object {
        const val EMBEDDING_DIMENSIONS = 768

        /**
         * Character cap applied before embedding (see [embedPassages]).
         * e5-base: max_seq_length=512 tokens ≈ ~2000 chars for latin text;
         * slightly conservative to stay under the window for most languages.
         */
        const val MAX_PASSAGE_CHARS = 2000
    }
}