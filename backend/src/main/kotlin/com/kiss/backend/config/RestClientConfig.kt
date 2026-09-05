package com.kiss.backend.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

/**
 * RestClient.Builder bean.
 *
 * Boot 4 no longer auto-exposes a `RestClient.Builder` (Boot 3 used to).
 * DoclingClient and the reconciler's docling health check consume one via
 * ObjectProvider / constructor injection. UTF-8-capable HTTP/1.1 builder with
 * a 30s connect timeout; callers override read timeouts per call.
 */
@Configuration
class RestClientConfig {

    @Bean
    fun restClientBuilder(): RestClient.Builder =
        RestClient.builder()
            .requestFactory(
                JdkClientHttpRequestFactory(
                    HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(30))
                        .build()
                ).apply {
                    setReadTimeout(Duration.ofMinutes(2))
                }
            )
}