plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.kotlin.jpa)
}

group = "com.kiss"
version = "0.0.1-SNAPSHOT"
description = "Faldony backend"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(libs.spring.ai.bom))

    // Jackson 2 pipeline: Boot 4 defaults to Jackson 3, which has no Kotlin
    // module yet. Exclude starter-jackson (Jackson 3) and use Jackson 2
    // (databind + kotlin + jsr310), configured via JacksonConfig.
    implementation(libs.spring.boot.starter.web) {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-jackson")
    }
    implementation(libs.spring.boot.starter.cache)
    implementation(libs.caffeine)
    implementation(libs.commons.codec)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.oauth2.resource.server)

    implementation(libs.spring.ai.starter.model.transformers) {
        exclude(group = "ai.djl.pytorch")
    }
    // Provide DJL with the ONNX engine instead of PyTorch so it doesn't crash on Windows
    runtimeOnly("ai.djl.onnxruntime:onnxruntime-engine:0.32.0")

implementation(libs.temporal.spring.boot.starter)
    implementation(libs.temporal.sdk)
    implementation(libs.temporal.workflowstreams)
    implementation(libs.postgresql)
    implementation(libs.pgvector)
    implementation(libs.bucket4j.core)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    // Boot 4 ships the Flyway autoconfiguration in its own module.
    implementation(libs.spring.boot.flyway)

    implementation(libs.springdoc.openapi)

//    implementation(libs.commons.csv)

    implementation(libs.docling.serve.api)
    implementation(libs.docling.serve.client)
    implementation(libs.tika.core)

    implementation(libs.kotlin.reflect)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.webmvc.test)
    testImplementation(libs.temporal.testing)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.mockito.kotlin)
    testRuntimeOnly(libs.junit.platform.launcher)
    implementation(libs.kotlin.stdlib)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Resolve the env-driven placeholders (application.yaml) for context tests
    // WITHOUT docker-compose/shell env: system properties are consulted by
    // placeholder resolution — including the early logging-system init that
    // never sees profile-specific yaml files.
    systemProperty("FALDONY_DATA_DIR", layout.buildDirectory.dir("data-test").get().asFile.absolutePath)
    systemProperty("FALDONY_AUTH_WHITELIST", "test-sub")
    systemProperty("GOOGLE_CLIENT_ID", "test-client-id")
    systemProperty("GOOGLE_ISSUER_URI", "https://accounts.google.com")
    systemProperty("DOCLING_SERVE_URL", "http://localhost:9999")
    systemProperty("ONNX_MODEL_URI", "file:///nonexistent/model.onnx")
    systemProperty("ONNX_TOKENIZER_URI", "file:///nonexistent/tokenizer.json")
    systemProperty("TEMPORAL_NAMESPACE", "default")
    systemProperty("TEMPORAL_CONNECTION_TARGET", "localhost:7233")
    systemProperty("DB_URL", "jdbc:postgresql://localhost:5432/test")
    systemProperty("DB_USERNAME", "test")
    systemProperty("DB_PASSWORD", "test")
    systemProperty("SERVER_PORT", "0")
    systemProperty("FALDONY_VERIFY_ON_DOWNLOAD", "false")
    systemProperty("FALDONY_MAX_UPLOAD_BYTES", "10485760")
    systemProperty("FALDONY_RATE_LIMIT_ENABLED", "false")
    systemProperty("FALDONY_RATE_LIMIT_CAPACITY", "10")
    systemProperty("FALDONY_SEARCH_WINDOW", "100")
    systemProperty("FALDONY_SEARCH_STREAM_BATCH_SIZE", "10")
    systemProperty("FALDONY_EMBEDDING_MODEL", "test-model")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("faldony.jar")
}