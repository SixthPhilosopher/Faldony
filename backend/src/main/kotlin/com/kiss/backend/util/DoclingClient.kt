package com.kiss.backend.util

import ai.docling.serve.api.chunk.response.ChunkDocumentResponse
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.kiss.backend.temporal.workflow.NonRetryableDocumentException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.InputStreamResource
import org.springframework.http.HttpEntity
import org.springframework.http.InvalidMediaTypeException
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.stereotype.Component
import org.springframework.util.MultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import java.io.InputStream
import java.net.http.HttpClient
import java.time.Duration

/** One docling task status poll. Own DTO (decision: don't extend the lagging library DTO). */
enum class DoclingTaskStatus { PENDING, STARTED, SUCCESS, FAILURE }

data class DoclingChunkResult(
    val chunks: List<String>,
    val status: String,
    val pageCount: Int
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TaskStatusPayload(
    val task_id: String? = null,
    val task_status: String? = null,
    val task_position: Int? = null,
    val error_message: String? = null,
    val failure: Any? = null
)

data class DoclingPollResult(
    val found: Boolean,
    val status: DoclingTaskStatus?,
    val position: Int?,
    val errorMessage: String?
)

data class DoclingSubmitResult(
    val taskId: String
)

/**
 * Streaming client for docling-serve's ASYNC hybrid-chunk pipeline:
 * `POST /v1/chunk/hybrid/file/async` -> poll status (45s cadence driven by the
 * Temporal workflow's durable timer, never a blocking HTTP) -> fetch result.
 *
 * Wire notes (verified against docling-serve 0.6.x OpenAPI/dosu):
 * - `/file/async` accepts EXACTLY the same multipart fields as the sync route
 *   (convert_ prefix + chunking_ prefix, repeated list parts, to_formats
 *   excluded). `convert_document_timeout` is a per-job bound on orphaned work.
 * - Poll returns 200 with `task_status` in {pending|started|success|failure}
 *   plus `error_message`/`failure`; 404 means the task vanished (server
 *   restart / result GC'd) -> callers resubmit.
 * - Result is single-use in the long run (background deletion ~300s after the
 *   first fetch) but re-fetchable in that window; a 404 on the result GET
 *   reliably means "gone".
 * - 4xx is permanent (form/validation) -> NonRetryableDocumentException.
 */
@Component
class DoclingClient(
    @Value("\${faldony.docling.base-url}") baseUrl: String,
    restClientBuilderProvider: ObjectProvider<RestClient.Builder>
) {

    private val logger = LoggerFactory.getLogger(DoclingClient::class.java)

    // Only SHORT calls now (submit / poll / fetch), so a modest read timeout.
    private val restClient = restClientBuilderProvider.getObject()
        .baseUrl(baseUrl)
        .requestFactory(
            JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(30))
                    .build()
            ).apply {
                setReadTimeout(Duration.ofSeconds(60))
            })
        .build()

    /**
     * Submits the file to the /file/async route. Returns the task id.
     * 4xx = permanent (bad form/validation) -> fail fast without retries.
     */
    fun submitAsync(stream: InputStream, filename: String, mimeType: String): DoclingSubmitResult =
        stream.use {

            val safeMediaType = try {
                MediaType.parseMediaType(mimeType)
            } catch (_: InvalidMediaTypeException) {
                MediaType.APPLICATION_OCTET_STREAM
            }

            val body = MultipartBodyBuilder().apply {
                part("files", streamingResource(it, filename))
                    .contentType(safeMediaType)
                part("convert_do_ocr", "true")
                part("convert_ocr_lang", "ita")
                part("convert_ocr_lang", "eng")
                part("include_converted_doc", "true")
                part("target_type", "inbody")
                part("chunking_max_tokens", "512")
                part("chunking_tokenizer", "intfloat/multilingual-e5-base")
                part("chunking_merge_peers", "true")
                part("chunking_use_markdown_tables", "true")
                part("chunking_include_raw_text", "true")
                // Per-job cap on server-side work: bounds orphaned compute after
                // a user cancellation (server has no abort endpoint).
                part("convert_document_timeout", "${DOCUMENT_TIMEOUT_SECONDS}")
            }.build()

            logger.info("Submitting async docling conversion (filename={})", filename)
            val json = postBody("/v1/chunk/hybrid/file/async", body, Map::class.java)
            val taskId = (json as? Map<*, *>)?.get("task_id") as? String
                ?: throw IllegalStateException("docling-serve async submit returned no task_id: $json")
            logger.info("Docling task submitted: {}", taskId)
            DoclingSubmitResult(taskId)
        }

    /**
     * Polls a task. `found=false` when the task no longer exists (404) —
     * the caller (workflow) decides resubmit.
     */
    fun pollStatus(taskId: String): DoclingPollResult {
        val payload: TaskStatusPayload = restClient.get()
            .uri("/v1/status/poll/{taskId}", taskId)
            .retrieve()
            .onStatus({ status -> status.value() == 404 }) { _, _ -> /* 404 handled below */ }
            .onStatus({ status -> status.is4xxClientError }) { request, response ->
                val body = response.body.readBytes().decodeToString().take(2000)
                throw NonRetryableDocumentException(
                    "docling-serve poll rejected ${request.uri}: ${response.statusCode} - $body"
                )
            }
            .body<TaskStatusPayload>()
            ?: throw IllegalStateException("docling-serve poll returned an empty body for $taskId")

        val raw = payload.task_status ?: return DoclingPollResult(found = false, null, null, null)
        val status = when (raw.lowercase()) {
            "pending" -> DoclingTaskStatus.PENDING
            "started" -> DoclingTaskStatus.STARTED
            "success" -> DoclingTaskStatus.SUCCESS
            "failure" -> DoclingTaskStatus.FAILURE
            else -> null
        }
        return DoclingPollResult(
            found = status != null,
            status = status,
            position = payload.task_position,
            errorMessage = payload.error_message
        )
    }

    /** Fetches the conversion result (single-use in the long run). */
    fun fetchResult(taskId: String): ChunkDocumentResponse =
        restClient.get()
            .uri("/v1/result/{taskId}", taskId)
            .retrieve()
            .onStatus({ status -> status.value() == 404 }) { request, _ ->
                throw NonRetryableDocumentException(
                    "docling-serve result $taskId not found (${request.uri}) - task vanished"
                )
            }
            .onStatus({ status -> status.is4xxClientError }) { request, response ->
                val body = response.body.readBytes().decodeToString().take(2000)
                throw NonRetryableDocumentException(
                    "docling-serve result rejected ${request.uri}: ${response.statusCode} - $body"
                )
            }
            .body<ChunkDocumentResponse>()
            ?: throw IllegalStateException("docling-serve returned an empty result for $taskId")

    /** Parses the ChunkDocumentResponse into the classic ExtractionRef payload shapes. */
    fun toDoclingChunkResult(response: ChunkDocumentResponse): DoclingChunkResult {
        val docResponse = response.documents.firstOrNull()
        val document = docResponse?.content
        val chunks = response.chunks.mapNotNull { it.text.takeIf(String::isNotBlank) }
        val status = docResponse?.status ?: "failure"
        val pageCount = document?.jsonContent?.pages?.size?.coerceAtLeast(1)
            ?: response.chunks.flatMap { it.pageNumbers }.maxOrNull()?.coerceAtLeast(1)
            ?: 1
        return DoclingChunkResult(chunks = chunks, status = status, pageCount = pageCount)
    }

    // ---------------------------------------------------------------- private

    private fun postBody(path: String, body: MultiValueMap<String, HttpEntity<*>>, asType: Class<*>): Any =
        restClient.post()
            .uri(path)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(body)
            .retrieve()
            .onStatus({ status -> status.is4xxClientError }) { request, response ->
                val responseBody = response.body.readBytes().decodeToString().take(4000)
                logger.error(
                    "docling-serve rejected {} ({} {}): status={}, body={}",
                    path, request.method, request.uri, response.statusCode, responseBody
                )
                throw NonRetryableDocumentException(
                    "docling-serve rejected $path: ${response.statusCode} - $responseBody"
                )
            }
            .body(asType)
            ?: throw IllegalStateException("docling-serve returned an empty body for $path")

    private fun streamingResource(stream: InputStream, filename: String): InputStreamResource =
        object : InputStreamResource(stream) {
            override fun getFilename(): String = filename
            override fun contentLength(): Long = -1L // streamed
        }

    companion object {
        const val DOCUMENT_TIMEOUT_SECONDS = 1800L
        const val POLL_INTERVAL_SECONDS = 45L
    }
}