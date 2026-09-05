package com.kiss.backend.security

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimNames
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.time.Instant
import java.util.Date

/**
 * Shared fixtures for the Google OAuth2 resource-server tests.
 *
 * The decoder used by tests is built with a LOCAL RSA key (no network) but
 * mirrors the production rules — signature + `iss` + `aud` == client id +
 * timestamps — exactly like the auto-configured decoder that
 * `spring.security.oauth2.resourceserver.jwt.issuer-uri` + `audiences`
 * produce in production (see SecurityConfig class doc: no custom decoder in
 * the runtime path). Tokens are minted for real with Nimbus JOSE so
 * signature/expiry/wrong-key scenarios are exercised end-to-end.
 */
internal object SecurityTestSupport {

    const val ISSUER = "https://accounts.google.com"
    const val CLIENT_ID = "test-client-id"
    const val WHITELISTED_SUB = "sub-whitelisted"
    const val EMAIL = "owner@example.com"

    val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA")
        .apply { initialize(2048) }
        .generateKeyPair()

    private val now: Instant = Instant.now()

    fun validator(): OAuth2TokenValidator<Jwt> =
        DelegatingOAuth2TokenValidator(
            JwtValidators.createDefaultWithIssuer(ISSUER),
            JwtClaimValidator<List<String>>(JwtClaimNames.AUD) { aud -> aud != null && aud.contains(CLIENT_ID) }
        )

    fun decoder(): JwtDecoder =
        NimbusJwtDecoder.withPublicKey(keyPair.public as RSAPublicKey).build()
            .apply { setJwtValidator(validator()) }

    fun token(
        issuer: String = ISSUER,
        audience: String = CLIENT_ID,
        subject: String = WHITELISTED_SUB,
        email: String? = EMAIL,
        expiresAt: Instant = now.plus(Duration.ofHours(1)),
        notBefore: Instant = now.minus(Duration.ofHours(1)),
        key: java.security.PrivateKey = keyPair.private
    ): String {
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject(subject)
            .audience(listOf(audience))
            .issueTime(Date.from(now))
            .expirationTime(Date.from(expiresAt))
            .notBeforeTime(Date.from(notBefore))
            .apply { if (email != null) claim("email", email) }
            .build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).build(), claims)
            .apply { sign(RSASSASigner(key)) }
            .serialize()
    }
}