package com.kiss.backend.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.assertEquals

class RateLimitFilterTest {

    private val mapper = Mockito.mock(ObjectMapper::class.java)

    /** A real limiter: capacity 100 allows, capacity 1 drained once denies. */
    private fun limiter(drain: Boolean): IpRateLimiter {
        val limiter = IpRateLimiter(if (drain) 1 else 100, true)
        if (drain) limiter.allow("127.0.0.1", 1)
        return limiter
    }

    private fun filter(drain: Boolean) = RateLimitFilter(
        ipRateLimiter = limiter(drain),
        objectMapper = mapper
    )

    @Test
    fun `rejects with 429 and Retry-After when limit exceeded`() {
        val response = MockHttpServletResponse()
        filter(drain = true).doFilter(
            MockHttpServletRequest().apply { requestURI = "/api/v1/tags" },
            response,
            MockFilterChain()
        )
        assertEquals(429, response.status)
        assertEquals("60", response.getHeader("Retry-After"))
    }

    @Test
    fun `proceeds when request is allowed`() {
        val response = MockHttpServletResponse()
        filter(drain = false).doFilter(
            MockHttpServletRequest().apply { requestURI = "/api/v1/tags" },
            response,
            MockFilterChain()
        )
        assertEquals(200, response.status, "filter must not touch a passed request")
    }

    @Test
    fun `never limits actuator docs or swagger paths`() {
        for (path in listOf("/actuator/health", "/v3/api-docs", "/swagger-ui/index.html")) {
            val response = MockHttpServletResponse()
            filter(drain = true).doFilter(
                MockHttpServletRequest().apply { requestURI = path },
                response,
                MockFilterChain()
            )
            assertEquals(200, response.status, "$path must never be limited")
        }
    }
}