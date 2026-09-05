package com.kiss.backend.config

import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaAuditing

/**
 * JPA auditing (BaseEntity timestamps) spun off from FaldonyApplication so
 * neither the app class nor slice tests pull the auditing infrastructure
 * into context (a @WebMvcTest with the app class on the chain would otherwise
 * create JpaAuditingHandler and fail on an empty JPA metamodel).
 */
@Configuration
@EnableJpaAuditing
class JpaAuditingConfig