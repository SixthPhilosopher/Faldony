package com.kiss.backend.security

import com.kiss.backend.config.SecurityConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@ActiveProfiles("test")
@WebMvcTest(
    controllers = [AuthTestController::class],
    properties = [
        "faldony.auth.whitelist=",
        "spring.cache.type=simple"
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
internal class ProdFailClosedTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `empty whitelist denies every valid token`() {
        mockMvc.perform(
            get("/api/v1/ping")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${SecurityTestSupport.token()}")
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `actuator health stays public`() {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isNotFound)
    }
}