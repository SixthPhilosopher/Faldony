# NOTICE

Faldony is licensed under the **GNU General Public License v3.0** (see
`LICENSE`).

The project logo (`assets/faldony.jpg`) was generated with Gemini Nano
Banana v3.

This product depends on open-source software developed by the organizations
listed below. Each component remains distributed under its own license; full
license texts are published by the respective projects and available at the
linked URLs. Where a component ships with its own NOTICE file, that notice is
preserved with the component.

## Third-party components

| Component | Used for | License                                                                                  |
|---|---|------------------------------------------------------------------------------------------|
| [Spring Framework / Spring Boot](https://spring.io/projects/spring-boot) | Application framework (web, data, security, actuation) | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0)                                |
| [Spring AI](https://spring.io/projects/spring-ai) | ONNX transformer embedding model integration | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0)                                |
| [Kotlin](https://kotlinlang.org) | Application language (backend + frontend) | [Apache-2.0](https://github.com/JetBrains/kotlin/blob/master/license/README.md)          |
| [Temporal Java SDK](https://github.com/temporalio/sdk-java) | Durable workflow orchestration (incl. Workflow Streams) | [Apache-2.0](https://github.com/temporalio/sdk-java/blob/master/LICENSE)                 |
| [Ktor](https://ktor.io) | Frontend HTTP client + SSE streaming | [Apache-2.0](https://github.com/ktorio/ktor/blob/main/LICENSE)                           |
| [FileKit](https://github.com/vinceglb/FileKit) | Platform-native file picking (WASM/JS) | [MIT](https://github.com/vinceglb/FileKit/blob/master/LICENSE)                            |
| [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform) | Shared UI layer (browser target) | [Apache-2.0](https://github.com/JetBrains/compose-multiplatform/blob/master/LICENSE.txt) |
| [AndroidX Lifecycle (multiplatform)](https://github.com/JetBrains/compose-multiplatform-core) | Frontend ViewModel/Compose lifecycle | [Apache-2.0](https://github.com/JetBrains/compose-multiplatform-core/blob/jb-main/LICENSE.txt) |
| [kotlin-wrappers](https://github.com/JetBrains/kotlin-wrappers) | Browser DOM wrappers (`kotlin-browser`): window/document bindings, media-query observation | [Apache-2.0](https://github.com/JetBrains/kotlin-wrappers/blob/master/LICENSE) |
| [kotlinx-datetime](https://github.com/Kotlin/kotlinx-datetime) | Frontend timestamp formatting | [Apache-2.0](https://github.com/Kotlin/kotlinx-datetime/blob/master/LICENSE.txt) |
| [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | Frontend JSON (de)serialization (with Ktor ContentNegotiation) | [Apache-2.0](https://github.com/Kotlin/kotlinx.serialization/blob/master/LICENSE.txt) |
| [multiplatform-settings](https://github.com/russhwolf/multiplatform-settings) | Persisted frontend token/theme storage | [Apache-2.0](https://github.com/russhwolf/multiplatform-settings/blob/main/LICENSE.txt) |
| [Jackson](https://github.com/FasterXML/jackson) | JSON (de)serialization | [Apache-2.0](https://github.com/FasterXML/jackson/blob/master/LICENSE)                   |
| [Caffeine](https://github.com/ben-manes/caffeine) | In-process query-embedding cache, rate-limit store | [Apache-2.0](https://github.com/ben-manes/caffeine/blob/master/LICENSE)                  |
| [Bucket4j](https://github.com/bucket4j/bucket4j) | Per-IP token-bucket rate limiting | [Apache-2.0](https://github.com/bucket4j/bucket4j/blob/develop/LICENSE)                  |
| [Apache Tika](https://tika.apache.org) | Upload content-type sniffing | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0)                                |
| [Flyway](https://flywaydb.org) | Database migrations (owner of the Postgres schema) | [Apache-2.0](https://github.com/flyway/flyway/blob/main/LICENSE.md)                      |
| [springdoc-openapi](https://springdoc.org) | OpenAPI / Swagger UI | [Apache-2.0](https://github.com/springdoc/springdoc-openapi/blob/main/LICENSE)           |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) | Embedding model inference (via DJL) | [MIT](https://github.com/microsoft/onnxruntime/blob/main/LICENSE)                        |
| [DJL](https://djl.ai) | ONNX engine integration for Spring AI | [Apache-2.0](https://github.com/deepjavalibrary/djl/blob/master/LICENSE)                 |
| [Docling](https://github.com/docling-project/docling) | Document conversion/chunking server (`docling-serve`) | [MIT](https://github.com/docling-project/docling/blob/main/LICENSE)                      |
| [Testcontainers](https://testcontainers.com) | Integration-test Postgres | [MIT](https://github.com/testcontainers/testcontainers-java/blob/main/LICENSE)           |
| [Mockito](https://github.com/mockito/mockito) | Unit-test mocking | [MIT](https://github.com/mockito/mockito/blob/main/LICENSE)                              |
| [PostgreSQL JDBC Driver](https://jdbc.postgresql.org) | Database connectivity | [BSD-2-Clause](https://jdbc.postgresql.org/license/)                                     |
| [pgvector](https://github.com/pgvector/pgvector) | Vector storage, HNSW index, `halfvec` opclass | [PostgreSQL License](https://github.com/pgvector/pgvector/blob/master/LICENSE)           |
| [intfloat/multilingual-e5-base](https://huggingface.co/intfloat/multilingual-e5-base) | On-device embedding model (768-dim, baked into the image) | [Apache-2.0](https://huggingface.co/intfloat/multilingual-e5-base)                                   |
| [Eclipse Temurin (OpenJDK)](https://adoptium.net) | Base runtime / build images (JDK 25) | [GPL-2.0 with Classpath Exception](https://openjdk.org/legal/gplv2+ce.html)              |

## Model card note

The embedding model used by this project, `intfloat/multilingual-e5-base`,
is downloaded at image build time from the Hugging Face Hub under the Apache
2.0 license (see link above).