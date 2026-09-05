package com.kiss.backend.config

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IpRateLimiterTest {

    private fun limiter(capacity: Int, enabled: Boolean = true) = IpRateLimiter(capacity, enabled)

    @Test
    fun `allows up to capacity requests per bucket then rejects`() {
        val limiter = limiter(3)
        assertTrue(limiter.allow("1.2.3.4", 1))
        assertTrue(limiter.allow("1.2.3.4", 1))
        assertTrue(limiter.allow("1.2.3.4", 1))
        assertFalse(limiter.allow("1.2.3.4", 1), "bucket exhausted after capacity")
    }

    @Test
    fun `ips are independently bucketed`() {
        val limiter = limiter(2)
        assertTrue(limiter.allow("1.2.3.4", 1))
        assertTrue(limiter.allow("1.2.3.4", 1))
        assertFalse(limiter.allow("1.2.3.4", 1))
        assertTrue(limiter.allow("5.6.7.8", 1), "another ip must be unaffected")
    }

    @Test
    fun `single request can consume multiple tokens`() {
        val limiter = limiter(5)
        assertTrue(limiter.allow("1.2.3.4", 5))
        assertFalse(limiter.allow("1.2.3.4", 1), "bulk consume drains the bucket")
    }

    @Test
    fun `disabled limiter always allows`() {
        val limiter = limiter(1, enabled = false)
        repeat(100) { assertTrue(limiter.allow("1.2.3.4", 1)) }
    }
}