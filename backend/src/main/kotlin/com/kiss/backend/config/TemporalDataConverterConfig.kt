package com.kiss.backend.config

import com.fasterxml.jackson.databind.ObjectMapper
import io.temporal.common.converter.DataConverter
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Temporal's DataConverter, built on OUR Jackson 2 ObjectMapper (Kotlin module
 * + JavaTime, configured in JacksonConfig).
 *
 * Why this bean exists: Temporal's DEFAULT converter (STANDARD_INSTANCE) uses
 * a plain Jackson mapper WITHOUT the Kotlin module — it cannot construct any
 * Kotlin data-class payload (no no-arg constructor => `InvalidDefinitionException:
 * cannot construct instance of <...> (no Creators exist)`). Every activity
 * result crossing the history (e.g. DoclingPollResult) blew up the workflow
 * task and caused infinite workflow-task retries (seen in the logs as
 * "Critical attempts processing workflow task ... attempt=8/9/...").
 *
 * The Temporal Spring Boot starter injects a `DataConverter` bean into both
 * the client and the workers automatically; sharing our Kotlin-aware mapper
 * also keeps the payload wire format consistent with the HTTP JSON layer.
 */
@Configuration
class TemporalDataConverterConfig {

    @Bean
    fun temporalDataConverter(objectMapper: ObjectMapper): DataConverter =
        DefaultDataConverter(JacksonJsonPayloadConverter(objectMapper))
}