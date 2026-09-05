package com.kiss.backend.config

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import io.github.bucket4j.Refill
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Per-client-IP token bucket (single-instance deployment — in-memory is
 * correct; a multi-instance fleet would lift this to the edge/gateway).
 *
 * Each IP gets a lazy bucket: [capacity] tokens, refilled greedily at
 * [capacity] per minute. Idle clients are evicted after 10 minutes so the map
 * stays bounded (a hostile IP flood cannot grow memory without limit).
 */
@Component
class IpRateLimiter(
    @Value("\${faldony.rate-limit.capacity}") private val capacity: Int,
    @Value("\${faldony.rate-limit.enabled}") private val enabled: Boolean
) {

    private val buckets: Cache<String, Bucket> =
        Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterAccess(Duration.ofMinutes(10))
            .build()

    fun allow(ip: String, requestCount: Long): Boolean {
        if (!enabled) return true
        val bucket = buckets.get(ip) { _ ->
            val bandwidth = Bandwidth.classic(
                capacity.toLong(),
                Refill.greedy(capacity.toLong(), Duration.ofMinutes(1))
            )
            Bucket.builder().addLimit(bandwidth).build()
        }
        return bucket.tryConsume(requestCount)
    }
}