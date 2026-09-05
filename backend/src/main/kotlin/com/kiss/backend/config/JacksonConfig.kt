package com.kiss.backend.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Explicit Jackson 2 ObjectMapper.
 *
 * Boot 4.1 defaults to Jackson 3 (`tools.jackson`), which has no Kotlin module
 * released yet, so Kotlin DTO binding would not work. We exclude the Jackson 3
 * starter and provide a Jackson 2 mapper configured for Kotlin data classes
 * (the Boot Jackson-2 HTTP converters pick up this bean).
 */
@Configuration
class JacksonConfig {

    @Bean
    fun objectMapper(): ObjectMapper =
        JsonMapper.builder()
            .addModule(KotlinModule.Builder().build())
            .addModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build()
}