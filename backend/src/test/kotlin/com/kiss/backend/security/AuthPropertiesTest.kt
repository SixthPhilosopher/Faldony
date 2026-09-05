package com.kiss.backend.security

import com.kiss.backend.config.AuthProperties
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class AuthPropertiesTest {

    private fun props(whitelist: List<String>) =
        AuthProperties(whitelist)

    @Test
    fun `plain sub entries match the sub claim`() {
        assertTrue(props(listOf("sub-1")).isWhitelisted("sub-1", null))
        assertTrue(props(listOf("sub-1", "sub-2")).isWhitelisted("sub-2", "x@y.z"))
    }

    @Test
    fun `at-entries match the email claim case-insensitively`() {
        assertTrue(props(listOf("Owner@Example.COM")).isWhitelisted(null, "owner@example.com"))
    }

    @Test
    fun `plain sub entry never matches the email claim`() {
        assertFalse(props(listOf("some-sub")).isWhitelisted(null, "some-sub@example.com"))
    }

    @Test
    fun `email entry does not match a sub claim`() {
        assertFalse(props(listOf("owner@example.com")).isWhitelisted("owner@example.com", null))
    }

    @Test
    fun `unknown identity is denied`() {
        assertFalse(props(listOf("sub-1")).isWhitelisted("sub-9", "nope@example.com"))
    }

    @Test
    fun `empty whitelist never matches on its own`() {
        assertFalse(props(emptyList()).isWhitelisted("sub-1", "x@y.z"))
    }
}