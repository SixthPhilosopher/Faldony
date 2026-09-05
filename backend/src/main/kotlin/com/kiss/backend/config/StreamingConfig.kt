package com.kiss.backend.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/**
 * Dedicated executor for long-running SSE streams (search results).
 *
 * Streaming must NEVER run on a Tomcat request thread (a long stream would
 * occupy a worker connection indefinitely); this small pool absorbs the
 * ranking + per-batch DB work and the emitter writes while Tomcat threads
 * stay free for regular requests. Backpressure: an unbounded queue gives
 * clients generous tolerance; threads are capped so a burst of streams cannot
 * saturate the machine.
 */
@Configuration
class StreamingConfig {

    @Bean("documentStreamExecutor")
    fun documentStreamExecutor(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 2
        maxPoolSize = 4
        queueCapacity = 64
        setThreadNamePrefix("doc-stream-")
        initialize()
    }
}