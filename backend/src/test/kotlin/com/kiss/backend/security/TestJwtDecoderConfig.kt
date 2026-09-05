package com.kiss.backend.security

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.stereotype.Component

import org.springframework.boot.test.context.TestConfiguration

/**
 * Test-only JwtDecoder: local RSA key + the production validator rules.
 * Marked @Primary so the resource server uses it instead of the
 * network-backed Google decoder. The web slice does not auto-configure a
 * Jackson ObjectMapper, so one is supplied here (error JSON responses only).
 */
@TestConfiguration
class TestJwtDecoderConfig {

    @Bean
    @Primary
    fun testJwtDecoder(): JwtDecoder = SecurityTestSupport.decoder()

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    fun objectMapper(): ObjectMapper = ObjectMapper()
}