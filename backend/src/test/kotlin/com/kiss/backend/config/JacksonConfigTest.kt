package com.kiss.backend.config

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

internal class JacksonConfigTest {

    private val mapper = JacksonConfig().objectMapper()

    private data class Sample(
        val id: Long,
        val title: String,
        val createdAt: Instant
    )

    @Test
    fun `round-trips kotlin data class with Instant`() {
        val original = Sample(42, "Hello & world", Instant.parse("2026-08-13T15:00:00Z"))

        val json = mapper.writeValueAsString(original)
        val restored = mapper.readValue(json, Sample::class.java)

        assertEquals(original, restored)
    }

    @Test
    fun `serializes instants as iso-8601`() {
        val json = mapper.writeValueAsString(Sample(1, "t", Instant.parse("2026-08-13T15:00:00Z")))
        assertEquals("""{"id":1,"title":"t","createdAt":"2026-08-13T15:00:00Z"}""", json)
    }
}