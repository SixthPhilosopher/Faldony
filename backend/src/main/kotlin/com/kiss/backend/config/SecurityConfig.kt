package com.kiss.backend.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * Stateless Google OAuth2 resource server, using Spring Security NATIVE
 * mechanisms (no custom decoder, no hand-rolled claim filter):
 *
 * - Decoder: the auto-configured **Nimbus** decoder (`NimbusJwtDecoder`,
 *   spring-security-oauth2-jose) validates signature + `exp`/`nbf` + `iss`;
 *   the `aud` claim is validated natively via the `audiences` property in
 *   application.yaml. Issuer/JWKS come from `GOOGLE_ISSUER_URI` — in
 *   production that is Google's real OIDC endpoint, so only tokens signed by
 *   Google are ever accepted. There is no mock decoder anywhere: every
 *   environment validates real Google tokens.
 * - Token lifecycle: the backend issues no tokens and tracks no sessions.
 *   Every request carries a Google-issued id_token (≈1 h `exp`) as a Bearer
 *   credential; validation happens per request against Google's JWKS. When
 *   the token's `exp` passes, Nimbus rejects it (401) and the client re-runs
 *   Google sign-in — there is no refresh flow and no backend-side TTL.
 * - Whitelist: translated into a native GrantedAuthority via
 *   [jwtAuthenticationConverter] — an identity that passes the allow-list gets
 *   ROLE_APPROVED, and the filter chain simply requires it
 *   (`anyRequest().hasRole("APPROVED")`). Unauthorized → 401 (entry point),
 *   authenticated-but-not-whitelisted → 403 (access-denied handler).
 * - Fail closed ALWAYS: an empty allow-list denies every identity (the
 *   whitelist is developer-managed, and auth must never silently open).
 * - CORS: handled INSIDE the security chain (`http.cors { }`) with a
 *   CorsConfigurationSource bean. Previously CORS lived in a WebMvcConfigurer,
 *   which runs AFTER the security filter chain — preflight OPTIONS could be
 *   blocked with 401 before MVC ever saw them.
 * - No HTTP sessions; CSRF disabled (Bearer tokens, no cookies).
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AuthProperties::class)
class SecurityConfig(
    private val auth: AuthProperties,
    private val objectMapper: ObjectMapper
) {

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        jwtAuthenticationConverter: JwtAuthenticationConverter,
        corsConfigurationSource: CorsConfigurationSource
    ): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors { it.configurationSource(corsConfigurationSource) }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { authorize ->
                // API is authenticated; only health/swagger stay public. The
                // allow-list is expressed as an authority (see
                // jwtAuthenticationConverter).
                authorize
                    .requestMatchers("/actuator/**", "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                    .anyRequest().hasRole("APPROVED")
            }
            .headers { headers ->
                headers
                    .contentSecurityPolicy { csp -> csp.policyDirectives("default-src 'self'") }
                    .frameOptions { it.sameOrigin() }
                    .xssProtection {}
                    .referrerPolicy { it.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER) }
            }
            .oauth2ResourceServer { resourceServer ->
                resourceServer
                    .authenticationEntryPoint(restAuthenticationEntryPoint())
                    .accessDeniedHandler(restAccessDeniedHandler())
                    .jwt { jwt ->
                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)
                    }
            }
        return http.build()
    }

    /**
     * Maps the allow-list to a native authority: whitelisted identities get
     * ROLE_APPROVED (required by every authenticated endpoint); everyone else
     * gets no authority → 403 by the chain. An EMPTY allow-list denies every
     * identity, always (fail closed).
     */
    @Bean
    fun jwtAuthenticationConverter(): JwtAuthenticationConverter {
        val converter = JwtAuthenticationConverter()
        converter.setJwtGrantedAuthoritiesConverter { jwt ->
            val allowed = auth.isWhitelisted(jwt.subject, jwt.getClaimAsString("email"))
            if (allowed) listOf(SimpleGrantedAuthority("ROLE_APPROVED")) else emptyList()
        }
        return converter
    }

    @Bean
    fun restAuthenticationEntryPoint(): AuthenticationEntryPoint =
        AuthenticationEntryPoint { _, response, _ ->
            response.status = HttpStatus.UNAUTHORIZED.value()
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            objectMapper.writeValue(
                response.writer,
                mapOf("code" to "UNAUTHORIZED", "message" to "Authentication required")
            )
        }

    /**
     * 403 in the same JSON shape as the entry point, used when an identity is
     * authenticated but not allow-listed (no ROLE_APPROVED).
     */
    @Bean
    fun restAccessDeniedHandler(): AccessDeniedHandler =
        AccessDeniedHandler { _, response, _ ->
            response.status = HttpStatus.FORBIDDEN.value()
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            objectMapper.writeValue(
                response.writer,
                mapOf("code" to "FORBIDDEN", "message" to "Account is not whitelisted")
            )
        }

    /**
     * CORS inside the security chain (see class doc). Origins mirror the old
     * WebMvcConfigurer mapping.
     */
    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val config = CorsConfiguration().apply {
            // Origin PATTERNS (wildcards): any local dev-server port is allowed,
            // plus the production domain.
            allowedOriginPatterns = listOf(
                "http://localhost:*",
                "http://127.0.0.1:*",
                "https://yourdomain.com", // Production domain
            )
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            // Cache-Control: browsers (DevTools "Disable cache") and fetch
            // engines append it to requests; the preflight then demands it.
            allowedHeaders = listOf("Content-Type", "Authorization", "Cache-Control")
            allowCredentials = true
            maxAge = 3600
        }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/api/**", config)
        }
    }
}