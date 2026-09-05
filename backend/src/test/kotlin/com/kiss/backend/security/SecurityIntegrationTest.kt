package com.kiss.backend.security

import com.kiss.backend.config.SecurityConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.security.KeyPairGenerator
import java.time.Duration
import java.time.Instant

@ActiveProfiles("test")
@WebMvcTest(
    controllers = [AuthTestController::class],
    properties = [
        "faldony.auth.whitelist=sub-whitelisted", "spring.cache.type=simple"
    ],
    excludeAutoConfiguration = [
        org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration::class
    ]
)
@Import(
    SecurityConfig::class,
    TestJwtDecoderConfig::class,
    com.kiss.backend.config.IpRateLimiter::class,
    com.kiss.backend.config.RateLimitFilter::class
)
@ImportAutoConfiguration(
    org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration::class,
    CacheAutoConfiguration::class,
)
internal class SecurityIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    private fun token(
        issuer: String = SecurityTestSupport.ISSUER,
        audience: String = SecurityTestSupport.CLIENT_ID,
        subject: String = SecurityTestSupport.WHITELISTED_SUB,
        email: String? = SecurityTestSupport.EMAIL,
        expiresAt: Instant = Instant.now().plus(Duration.ofHours(1)),
        notBefore: Instant = Instant.now().minus(Duration.ofHours(1)),
        key: java.security.PrivateKey = SecurityTestSupport.keyPair.private
    ): String = SecurityTestSupport.token(
        issuer, audience, subject, email, expiresAt, notBefore, key
    )

    @Test
    fun `valid whitelisted token is accepted`() {
        mockMvc.perform(
            get("/api/v1/ping")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
        ).andExpect(status().isOk)
    }

    @Test
    fun `expired token is rejected`() {
        mockMvc.perform(
            get("/api/v1/ping")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(expiresAt = Instant.now().minus(Duration.ofHours(1)))}")
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `token signed with a different key is rejected`() {
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        mockMvc.perform(
            get("/api/v1/ping")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(key = other.private)}")
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `token with a different issuer is rejected`() {
        mockMvc.perform(
            get("/api/v1/ping")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(issuer = "https://evil.example.com")}")
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `token with a different audience is rejected`() {
        mockMvc.perform(
            get("/api/v1/ping")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(audience = "some-other-client")}")
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `valid token with non-whitelisted sub is rejected`() {
        mockMvc.perform(
            get("/api/v1/ping")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(subject = "sub-stranger")}")
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `missing token is rejected`() {
        mockMvc.perform(get("/api/v1/ping")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `actuator health stays public`() {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isNotFound)
    }
}