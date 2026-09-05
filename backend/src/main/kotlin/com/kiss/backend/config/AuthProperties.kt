package com.kiss.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Google OAuth2 allow-list configuration (`faldony.auth.*`).
 *
 * Whitelist semantics:
 * - The canonical identity is the Google `sub` claim. Plain entries (no `@`)
 *   are matched against `sub`.
 * - An entry containing `@` is an *email convenience entry* and is matched
 *   against the `email` claim only (case-insensitive). Tradeoff: email can
 *   change, so prefer `sub` entries in production. `email` is never used as
 *   the stable identity key.
 * - An empty whitelist DENIES EVERY identity, always (fail closed; enforced
 *   by the ROLE_APPROVED authority mapping in
 *   [com.kiss.backend.config.SecurityConfig]).
 */
@ConfigurationProperties(prefix = "faldony.auth")
class AuthProperties(
    whitelist: List<String> = emptyList()
) {

    /**
     * Trimmed, blank entries dropped: environment variables are commonly bound
     * as an empty string (e.g. `FALDONY_AUTH_WHITELIST=""` → `[""]`), which
     * would silently flip "empty = deny all" into "deny all" — the same end
     * state, but normalized here so `[""]` behaves exactly like `[]`.
     */
    val whitelist: List<String> = whitelist.map { it.trim() }.filter { it.isNotBlank() }

    /**
     * @return true if [sub]/[email] is allowed. Identity matching: entries with
     * an `@` match only the email claim; plain entries match only the `sub`
     * claim.
     */
    fun isWhitelisted(sub: String?, email: String?): Boolean {
        email?.let { value ->
            if (whitelist.any { it.contains('@') && it.equals(value, ignoreCase = true) }) return true
        }
        sub?.let { value ->
            if (whitelist.any { !it.contains('@') && it == value }) return true
        }
        return false
    }
}