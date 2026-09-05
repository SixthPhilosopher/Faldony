package com.kiss.backend.util

import ai.docling.serve.api.chunk.response.ChunkDocumentResponse
import com.kiss.backend.temporal.workflow.NonRetryableDocumentException
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.ObjectProvider
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DoclingClient async-surface behavior tests against a real local HTTP stub
 * (com.sun.net.httpserver — JDK-native, no extra deps, exercises the full
 * multipart submit + GETs through the actual JdkClientHttpRequestFactory).
 */
class DoclingClientTest {

    private val provider = Mockito.mock(ObjectProvider::class.java)

    private fun clientAgainst(baseUrl: String): DoclingClient {
        @Suppress("UNCHECKED_CAST")
        val typed = provider as ObjectProvider<RestClient.Builder>
        Mockito.`when`(typed.getObject()).thenReturn(RestClient.builder())
        return DoclingClient(baseUrl, typed)
    }

    /** Boots a stub server answering [handler] per request path. */
    private fun withStub(handler: (String, String) -> Pair<Int, String>, block: (DoclingClient) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex: HttpExchange ->
            val path = ex.requestURI.path
            val body = ex.requestBody.readBytes().decodeToString().take(8000)
            val (status, json) = handler(path, body)
            val bytes = json.toByteArray(StandardCharsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block(clientAgainst("http://127.0.0.1:${server.address.port}"))
        } finally {
            server.stop(0)
        }
    }

    // ------------------------------------------------------------- submit

    @Test
    fun `submitAsync posts multipart and parses task_id`() {
        val captured = AtomicReference<String>()
        withStub({ path, body ->
            if (path.endsWith("/v1/chunk/hybrid/file/async")) {
                captured.set(body)
                200 to """{"task_id":"task-abc-123","task_type":"process"}"""
            } else {
                404 to """{"detail":"bad"}"""
            }
        }) { client ->
            val result = client.submitAsync("x".byteInputStream(), "file.pdf", "application/pdf")
            assertEquals("task-abc-123", result.taskId)
            val sent = captured.get()
            assertTrue(sent.contains("convert_ocr_lang"), "repeated part present in multipart body")
            assertTrue(sent.contains("convert_document_timeout"), "per-job timeout part present")
        }
    }

    @Test
    fun `submitAsync 4xx is a permanent NonRetryableDocumentException`() {
        withStub({ _, _ ->
            422 to """{"detail":"bad form field"}"""
        }) { client ->
            val thrown = runCatching {
                client.submitAsync("x".byteInputStream(), "file.pdf", "application/pdf")
            }.exceptionOrNull()
            assertTrue(thrown is NonRetryableDocumentException, "4xx must be non-retryable, got $thrown")
        }
    }

    // ------------------------------------------------------------- poll

    @Test
    fun `pollStatus parses pending and success`() {
        val calls = AtomicInteger(0)
        withStub({ _, _ ->
            val n = calls.incrementAndGet()
            if (n == 1) {
                200 to """{"task_id":"t1","task_status":"pending","task_position":3}"""
            } else {
                200 to """{"task_id":"t1","task_status":"success","task_position":0}"""
            }
        }) { client ->
            val first = client.pollStatus("t1")
            assertTrue(first.found && first.status == DoclingTaskStatus.PENDING)
            assertEquals(3, first.position)

            val second = client.pollStatus("t1")
            assertTrue(second.found && second.status == DoclingTaskStatus.SUCCESS)
        }
    }

    @Test
    fun `pollStatus 404 reports vanished instead of throwing`() {
        withStub({ _, _ ->
            404 to """{"detail":"Task not found."}"""
        }) { client ->
            val result = client.pollStatus("ghost")
            assertFalse(result.found)
        }
    }

    @Test
    fun `pollStatus failure carries the server error message`() {
        withStub({ _, _ ->
            200 to """{"task_id":"t1","task_status":"failure","error_message":"Converter crashed: OOM"}"""
        }) { client ->
            val result = client.pollStatus("t1")
            assertTrue(result.status == DoclingTaskStatus.FAILURE)
            assertEquals("Converter crashed: OOM", result.errorMessage)
        }
    }

    // ------------------------------------------------------------- result

    @Test
    fun `fetchResult parses ChunkDocumentResponse and toDoclingChunkResult`() {
        withStub({ _, _ ->
            200 to """{
                "chunks": [
                  {"text": "First", "page_numbers": [1]},
                  {"text": "Second", "page_numbers": [1, 2]},
                  {"text": "   ", "page_numbers": [2]}
                ],
                "documents": [{"status": "success"}],
                "processingTime": 0.4
              }"""
        }) { client ->
            val response: ChunkDocumentResponse = client.fetchResult("t1")
            val converted = client.toDoclingChunkResult(response)
            assertEquals(listOf("First", "Second"), converted.chunks, "blank chunks filtered")
            assertEquals(2, converted.pageCount, "page count from max page number")
            assertEquals("success", converted.status)
        }
    }
}