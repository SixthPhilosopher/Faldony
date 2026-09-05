package com.kiss.backend

import org.springframework.boot.Banner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.cache.annotation.EnableCaching
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * @EnableCaching + spring-boot-starter-cache + Caffeine (see application.yaml:
 * `spring.cache.*`): the TTL/max-size policy for caches like `queryEmbeddings`
 * lives in configuration, not in code.
 *
 * JPA auditing (@CreatedDate/@LastModifiedDate on BaseEntity) lives in
 * JpaAuditingConfig — kept off this class on purpose (see its doc).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableCaching
class FaldonyApplication

fun main(args: Array<String>) {
    // Universal UTC lock before Hibernate/PostgreSQL driver init
    TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.UTC))

    runApplication<FaldonyApplication>(*args) {
        setBannerMode(Banner.Mode.OFF) // Clean log startup
        setHeadless(true)              // Server environment optimization
    }
}