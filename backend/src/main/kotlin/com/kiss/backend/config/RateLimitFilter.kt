package com.kiss.backend.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.kiss.backend.model.dto.ApiError
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Global per-IP rate limit: 429 + Retry-After on breach.
 *
 * Health, docs and swagger are never limited so probes/UI keep working.
 * Remote address is the key — single trusted-instance deployment; behind a
 * proxy set `server.forward-headers-strategy` and key on the forwarded-for
 * header instead if ever needed.
 */
@Component
class RateLimitFilter(
    private val ipRateLimiter: IpRateLimiter,
    private val objectMapper: ObjectMapper
) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI
        return path.startsWith("/actuator") ||
            path.startsWith("/v3/api-docs") ||
            path.startsWith("/swagger-ui") ||
            path.startsWith("/swagger-resources")
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: jakarta.servlet.FilterChain
    ) {
        if (!ipRateLimiter.allow(request.remoteAddr ?: "unknown", 1)) {
            response.status = HttpStatus.TOO_MANY_REQUESTS.value()
            response.setHeader("Retry-After", "60")
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            objectMapper.writeValue(response.writer,
                ApiError(code = "RATE_LIMITED", message = "Rate limit exceeded. Try again shortly."))
            return
        }
        filterChain.doFilter(request, response)
    }
}