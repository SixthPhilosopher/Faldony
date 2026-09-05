package com.kiss.backend.security

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** Minimal authenticated endpoint used by the security tests. */
@RestController
class AuthTestController {

    @GetMapping("/api/v1/ping")
    fun ping(): String = "pong"
}