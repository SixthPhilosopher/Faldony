package org.kiss.data

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import org.kiss.data.auth.AuthHolder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * Thin Ktor client for the Faldony backend.
 *
 * Dev-mode note: the backend's local decoder accepts any Bearer token, so the
 * client sends a constant dummy token. Production would inject a real Google
 * JWT here.
 */
object Api {

    const val API_PREFIX = "/api/v1"

    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Same as [json] but always emits default values Ã¢â‚¬â€ required so `[]` is
     *  distinguishable from "field omitted" in PATCH bodies. */
    val jsonWithDefaults = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    fun client(): HttpClient = HttpClient {
        expectSuccess = false
        install(ContentNegotiation) { json(Api.json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 15_000
        }
        defaultRequest {
            AuthHolder.currentToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }
    }

    /** Clients for SSE: no request/socket timeout Ã¢â‚¬â€ streams stay open indefinitely. */
    fun sseClient(): HttpClient = HttpClient {
        expectSuccess = false
        install(SSE)
        defaultRequest {
            AuthHolder.currentToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }
    }
}

/** Convenience holder so call sites share one HttpClient instance. */
class FaldonyApi(
    private val client: HttpClient = Api.client(),
    private val sseClient: HttpClient = Api.sseClient(),
    baseUrl: String = "",
) {
    private val base: String = baseUrl
    private val prefix: String get() = Api.API_PREFIX

    /** The active backend base URL (same-origin in both prod and dev — the
     *  serving layer proxies /api to the backend container). */
    val baseUrl: String get() = base

    // ------------------------------------------------------------------ docs

    /**
     * Uploads a file: multipart `file` + JSON `metadata` part.
     *
     * The multipart body is built MANUALLY with an explicit boundary Ã¢â‚¬â€ Ktor's
     * formData builder is ambiguous about whether it appends `name=` to a
     * provided Content-Disposition, and Spring's ContentDisposition.parse is
     * strict. This wire format is exactly what Spring's MultipartResolver
     * expects.
     */
    suspend fun upload(
        title: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        partyIds: List<Long> = emptyList(),
        tagIds: List<Long> = emptyList(),
        collectionIds: List<Long> = emptyList(),
    ): UploadResponseDto {
        val metadata = Api.jsonWithDefaults.encodeToString(
            UploadRequest(title = title, partyIds = partyIds, tagIds = tagIds, collectionIds = collectionIds),
        )
        val safeName = fileName
            .replace("\"", "")
            .replace("\r", "")
            .replace("\n", "")
        val boundary = "----Faldony" +
            (0 until 16).map { "0123456789abcdef"[Random.nextInt(16)] }.joinToString("")

        val fileHeader = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\n" +
            "Content-Type: $mimeType\r\n\r\n"
        val metadataHeader = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"metadata\"\r\n" +
            "Content-Type: application/json\r\n\r\n"
        val closing = "\r\n--$boundary--\r\n"

        val bodyBytes = byteArrayOf() +
            fileHeader.encodeToByteArray() +
            bytes +
            ("\r\n" + metadataHeader + metadata).encodeToByteArray() +
            closing.encodeToByteArray()

        val resp = client.post(base + "$prefix/documents") {
            setBody(bodyBytes)
            contentType(ContentType.parse("multipart/form-data; boundary=$boundary"))
        }
        checkResponse(resp, "upload")
        return Api.json.decodeFromString<UploadResponseDto>(resp.bodyAsText())
    }

    suspend fun getDocument(id: Long): DocumentDto {
        val resp = client.get(base + "$prefix/documents/$id") {}
        checkResponse(resp, "document $id")
        return Api.json.decodeFromString<DocumentDto>(resp.bodyAsText())
    }

    suspend fun updateDocument(
        id: Long,
        title: String? = null,
        partyIds: List<Long>? = null,
        tagIds: List<Long>? = null,
    ): DocumentDto {
        val body = Api.jsonWithDefaults.encodeToString(
            DocumentUpdateRequest(title = title, partyIds = partyIds, tagIds = tagIds),
        )
        val resp = client.patch(base + "$prefix/documents/$id") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        checkResponse(resp, "document update $id")
        return Api.json.decodeFromString<DocumentDto>(resp.bodyAsText())
    }

    suspend fun addDocumentParty(id: Long, partyId: Long): DocumentDto = memberChange("parties", "post", id, partyId)

    suspend fun removeDocumentParty(id: Long, partyId: Long): DocumentDto =
        memberChange("parties", "delete", id, partyId)

    suspend fun addDocumentTag(id: Long, tagId: Long): DocumentDto = memberChange("tags", "post", id, tagId)

    suspend fun removeDocumentTag(id: Long, tagId: Long): DocumentDto = memberChange("tags", "delete", id, tagId)

    private suspend fun memberChange(kind: String, verb: String, id: Long, refId: Long): DocumentDto {
        val url = base + "$prefix/documents/$id/$kind/$refId"
        val resp = when (verb) {
            "post" -> client.post(url) {}
            else -> client.delete(url) {}
        }
        checkResponse(resp, "$kind change on document $id")
        return Api.json.decodeFromString<DocumentDto>(resp.bodyAsText())
    }

    // --------------------------------------------------------------- search

    /**
     * Live document stream: `event:doc` (one per document, server-sorted),
     * terminal `event:done {delivered, truncated}`, or `event:error`. The
     * stream is fully open-ended; this flow completes when the server closes.
     */
    fun documentsStream(
        q: String? = null,
        type: String? = null,
        partyIds: List<Long> = emptyList(),
        tagIds: List<Long> = emptyList(),
        collectionId: Long? = null,
        sort: String = "createdAt,desc",
    ): Flow<DocsEvent> = flow {
        val params = mutableListOf<String>()
        q?.takeIf { it.isNotBlank() }?.let { params += "q=${it.encodeUrl()}" }
        type?.takeIf { it.isNotBlank() }?.let { params += "type=$it" }
        if (partyIds.isNotEmpty()) params += "partyIds=${partyIds.joinToString(",")}"
        if (tagIds.isNotEmpty()) params += "tagIds=${tagIds.joinToString(",")}"
        collectionId?.let { params += "collectionId=$it" }
        params += "sort=${sort.encodeUrl()}"
        val url = base + "$prefix/documents/stream?${params.joinToString("&")}"
        sseClient.sse(urlString = url, request = {}) {
            if (call.response.status.value == 401) AuthHolder.onUnauthorized()
            if (!call.response.status.isSuccess()) {
                throw IllegalStateException("document stream failed (${call.response.status.value})")
            }
            incoming.collect { event ->
                val data = event.data ?: ""
                when (event.event) {
                    "doc" -> {
                        val dto = try {
                            Api.json.decodeFromString<DocumentDto>(data)
                        } catch (e: Exception) {
                            emit(DocsEvent.Error("bad doc event: ${e.message}")); null
                        }
                        if (dto != null) emit(DocsEvent.Doc(dto))
                    }
                    "done" -> {
                        val done = try {
                            Api.json.decodeFromString<DonePayload>(data)
                        } catch (e: Exception) {
                            emit(DocsEvent.Error("bad done event: ${e.message}")); null
                        }
                        if (done != null) emit(DocsEvent.Done(done.delivered, done.truncated))
                    }
                    "error" -> emit(DocsEvent.Error(data))
                    else -> Unit
                }
            }
        }
    }

    // ------------------------------------------------------------- processes

    /** Polls the processing status of an upload (fallback; SSE is preferred). */
    suspend fun processStatus(uploadId: String): ProcessStatusDto {
        val resp = client.get(base + "$prefix/processes/${uploadId.encodeUrl()}") {
        }
        checkResponse(resp, "process status")
        return Api.json.decodeFromString<ProcessStatusDto>(resp.bodyAsText())
    }

    /**
     * Live process stream: `event:task` payloads until a terminal stage
     * (COMPLETED / FAILED / CANCELLED / TIMED_OUT), then the server closes.
     */
    fun processStream(uploadId: String): Flow<ProcessTaskEvent> = flow {
        val url = base + "$prefix/processes/${uploadId.encodeUrl()}/events"
        sseClient.sse(urlString = url, request = {}) {
            if (call.response.status.value == 401) AuthHolder.onUnauthorized()
            if (!call.response.status.isSuccess()) {
                throw IllegalStateException("process stream failed (${call.response.status.value})")
            }
            incoming.collect { event ->
                val data = event.data ?: ""
                if (event.event == "task" && data.isNotBlank()) {
                    val task = try {
                        Api.json.decodeFromString<ProcessTaskEvent>(data)
                    } catch (e: Exception) {
                        null
                    }
                    if (task != null) emit(task)
                }
            }
        }
    }

    suspend fun queue(): List<ProcessItemDto> {
        val resp = client.get(base + "$prefix/documents/queue") {}
        checkResponse(resp, "processing queue")
        return Api.json.decodeFromString<List<ProcessItemDto>>(resp.bodyAsText())
    }

    suspend fun history(limit: Int = 100, offset: Int = 0): List<ProcessHistoryDto> {
        val resp = client.get(base + "$prefix/processes/history?limit=$limit&offset=$offset") {
        }
        checkResponse(resp, "process history")
        return Api.json.decodeFromString<List<ProcessHistoryDto>>(resp.bodyAsText())
    }

    suspend fun retryProcess(uploadId: String) {
        val resp = client.post(base + "$prefix/processes/${uploadId.encodeUrl()}/retry") {
        }
        checkResponse(resp, "retry $uploadId")
    }

    suspend fun cancelProcess(uploadId: String) {
        val resp = client.post(base + "$prefix/processes/${uploadId.encodeUrl()}/cancel") {
        }
        checkResponse(resp, "cancel $uploadId")
    }

    // ------------------------------------------------------- reference data

    suspend fun parties(): List<PersonDto> {
        val resp = client.get(base + "$prefix/parties") {}
        checkResponse(resp, "parties")
        return Api.json.decodeFromString<List<PersonDto>>(resp.bodyAsText())
    }

    suspend fun tags(): List<TagDto> {
        val resp = client.get(base + "$prefix/tags") {}
        checkResponse(resp, "tags")
        return Api.json.decodeFromString<List<TagDto>>(resp.bodyAsText())
    }

    suspend fun collections(): List<CollectionDto> {
        val resp = client.get(base + "$prefix/collections") {}
        checkResponse(resp, "collections")
        return Api.json.decodeFromString<List<CollectionDto>>(resp.bodyAsText())
    }

    suspend fun createParty(name: String, emails: Set<String> = emptySet()): PersonDto {
        val resp = client.post(base + "$prefix/parties") {
            contentType(ContentType.Application.Json)
            setBody(Api.jsonWithDefaults.encodeToString(PersonCreateRequest(name = name, emails = emails)))
        }
        checkResponse(resp, "create party")
        return Api.json.decodeFromString<PersonDto>(resp.bodyAsText())
    }

    suspend fun createTag(name: String): TagDto {
        val resp = client.post(base + "$prefix/tags") {
            contentType(ContentType.Application.Json)
            setBody(Api.jsonWithDefaults.encodeToString(TagCreateRequest(name = name)))
        }
        checkResponse(resp, "create tag")
        return Api.json.decodeFromString<TagDto>(resp.bodyAsText())
    }

    // ------------------------------------------------------------- download

    /**
     * Downloads a document and keeps the REAL filename + content type from the
     * response. [inline]=true asks for an inline disposition (preview),
     * false forces an attachment (save), null lets the backend decide.
     */
    suspend fun downloadDocument(id: Long, title: String? = null, inline: Boolean? = null): DownloadPayload {
        val url = buildString {
            append(base)
            append("$prefix/documents/$id/download")
            if (inline != null) append("?inline=$inline")
        }
        val resp = client.get(url) {}
        checkResponse(resp, "download $id")
        val contentType = resp.headers[HttpHeaders.ContentType]
            ?.substringBefore(';')?.trim()
            ?.ifBlank { null }
            ?: "application/octet-stream"
        val disposition = resp.headers[HttpHeaders.ContentDisposition]
        val fileName = parseFileName(disposition) ?: fallbackFileName(title, contentType)
        return DownloadPayload(resp.bodyAsBytes(), contentType, fileName)
    }

    // ------------------------------------------------------------------ utils

    private suspend fun checkResponse(resp: HttpResponse, what: String) {
        if (resp.status.value == 401) AuthHolder.onUnauthorized()
        if (!resp.status.isSuccess()) {
            val body = try { resp.bodyAsText() } catch (e: Exception) { "" }
            val parsed = runCatching { Api.json.decodeFromString<ApiErrorDto>(body) }.getOrNull()
            val detail = parsed?.message?.takeIf { it.isNotBlank() }
                ?: body.take(500)
            throw IllegalStateException("$what failed (${resp.status.value}): $detail")
        }
    }

    private fun String.encodeUrl(): String = buildString {
        for (c in this@encodeUrl) {
            when {
                c.isLetterOrDigit() || c in "-_.~" -> append(c)
                else -> append('%').append(c.code.toString(16).uppercase().padStart(2, '0'))
            }
        }
    }

    /**
     * Parses `Content-Disposition`: prefers the RFC-5987 `filename*=UTF-8''Ã¢â‚¬Â¦`
     * form (what Spring sends), falls back to plain `filename="Ã¢â‚¬Â¦"`.
     */
    private fun parseFileName(contentDisposition: String?): String? {
        if (contentDisposition.isNullOrBlank()) return null
        val star = Regex("""filename\*\s*=\s*([^;]+)""").find(contentDisposition)
        if (star != null) {
            var raw = star.groupValues[1].trim().removeSurrounding("\"")
            if (raw.startsWith("UTF-8''", ignoreCase = true)) raw = raw.removePrefix("UTF-8''").removePrefix("utf-8''")
            return percentDecode(raw).trim().takeIf { it.isNotBlank() }
        }
        val plain = Regex("""filename\s*=\s*"?([^";]+)"?""").find(contentDisposition)
        return plain?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun percentDecode(input: String): String {
        val bytes = mutableListOf<Byte>()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '%' && i + 2 < input.length) {
                val hex = input.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    bytes += hex.toByte()
                    i += 3
                    continue
                }
            }
            c.toString().encodeToByteArray().forEach { bytes += it }
            i++
        }
        return bytes.toByteArray().decodeToString()
    }

    private fun fallbackFileName(title: String?, contentType: String): String {
        val base = title?.trim()?.takeIf { it.isNotBlank() } ?: "document"
        val ext = when {
            contentType.startsWith("image/png") -> "png"
            contentType.startsWith("image/jpeg") -> "jpg"
            contentType.startsWith("application/pdf") -> "pdf"
            contentType.startsWith("text/") -> "txt"
            contentType.startsWith("application/vnd.openxmlformats-officedocument.wordprocessingml") -> "docx"
            contentType.startsWith("application/vnd.openxmlformats-officedocument.spreadsheetml") -> "xlsx"
            contentType.startsWith("application/vnd.openxmlformats-officedocument.presentationml") -> "pptx"
            contentType.startsWith("image/") -> "img"
            else -> null
        }
        return if (ext != null) "$base.$ext" else base
    }
}
