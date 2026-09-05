package com.kiss.backend.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.boot.ansi.AnsiColor
import org.springframework.boot.ansi.AnsiOutput
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * Slim request log: one INFO line per request with method, full URL
 * (query params included), HTTP status, duration, and a per-request ID.
 *
 * The request ID serves two purposes:
 *
 * 1. It is stored in SLF4J MDC so all application logs emitted while
 *    processing the request automatically contain the same ID.
 *
 * 2. It is echoed as X-Request-Id in the HTTP response so the ID is
 *    visible on the wire and can be used to correlate application logs
 *    with the full request/response in a packet capture.
 *
 * The filter does NOT buffer or log request/response bodies or headers.
 * Those remain available in the packet capture.
 *
 * Example PCAP lookup:
 *
 *   tshark -r /pcaps/stack-*.pcap -Y 'http contains "<rid>"'
 *   tcpdump -nn -A -r /pcaps/stack-*.pcap | grep "<rid>"
 */
@Component
class HttpLoggingFilter : OncePerRequestFilter() {

    private val logger = LoggerFactory.getLogger(HttpLoggingFilter::class.java)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val requestId = UUID.randomUUID().toString()
        val start = System.nanoTime()

        // Make the ID available to application code if needed.
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId)

        // Make the ID visible on the wire.
        response.setHeader(REQUEST_ID_HEADER, requestId)

        // Make the ID automatically available to all SLF4J logs
        // emitted during this request.
        MDC.put(MDC_REQUEST_ID, requestId)

        try {
            filterChain.doFilter(request, response)
        } finally {
            val durationMs = (System.nanoTime() - start) / 1_000_000

            val query = request.queryString
                ?.let { "?$it" }
                .orEmpty()

            logger.info(
                "HTTP {} {}{} status={} duration={}ms",
                request.method,
                request.requestURI,
                query,
                coloredStatus(response.status),
                durationMs,
            )

            // Servlet container threads are reused.
            // Always remove request-scoped MDC data.
            MDC.remove(MDC_REQUEST_ID)
        }
    }

    /**
     * Colors the status code in the console: 2xx green, 3xx bluish, anything
     * else red — 5xx red too. Uses Spring's AnsiOutput, which honors
     * `spring.output.ansi.enabled` (DETECT default): colors appear in an
     * ANSI-capable terminal (IntelliJ console, terminal), and are stripped
     * when the log is a pipe/file (compose logs stay clean).
     */
    private fun coloredStatus(status: Int): String = AnsiOutput.toString(
        when (status) {
            in 200..299 -> AnsiColor.BRIGHT_GREEN
            in 300..399 -> AnsiColor.BRIGHT_BLUE
            else -> AnsiColor.BRIGHT_RED
        },
        status.toString(),
    )

    companion object {
        const val REQUEST_ID_HEADER = "X-Request-Id"
        const val REQUEST_ID_ATTRIBUTE = "requestId"
        const val MDC_REQUEST_ID = "requestId"
    }
}