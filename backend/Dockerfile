# ---- build stage ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

# Layer 1: build metadata + Gradle wrapper ONLY.
# Dependencies are cached in the BuildKit gradle cache (~/.gradle), which
# persists across builds even when the build files change — so rebuilds neither
# re-download the dependency tree nor re-send it in the context.
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts libs.versions.toml ./
COPY gradle ./gradle
RUN chmod +x gradlew
RUN --mount=type=cache,id=faldony-backend-gradle,target=/root/.gradle ./gradlew --no-daemon --console=plain dependencies

# Layer 2: application sources. Changes here reuse Layer 1 + the gradle cache,
# so only the recompile + jar repack actually run (bootJar is fast).
COPY src ./src
RUN --mount=type=cache,id=faldony-backend-gradle,target=/root/.gradle ./gradlew --no-daemon --console=plain bootJar

# ---- runtime stage ----
FROM eclipse-temurin:25-jre
WORKDIR /app

# curl is needed for the compose healthcheck.
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

# ONNX embedding model (intfloat/multilingual-e5-base, ~1.1 GB). Downloaded
# ONCE at build time; this layer is cached and reused on every rebuild, and the
# resulting image is fully self-contained.
RUN mkdir -p /models \
    && curl -fL --retry 3 -o /models/model.onnx \
         https://huggingface.co/intfloat/multilingual-e5-base/resolve/main/onnx/model.onnx \
    && curl -fL --retry 3 -o /models/tokenizer.json \
         https://huggingface.co/intfloat/multilingual-e5-base/resolve/main/onnx/tokenizer.json

COPY --from=build /app/build/libs/faldony.jar app.jar
EXPOSE 8081
# Model baked into the image: file:// URIs => no runtime download, and the
# Spring AI download cache is disabled so the weights are never copied into the
# data volume.
ENV ONNX_MODEL_URI=file:///models/model.onnx \
    ONNX_TOKENIZER_URI=file:///models/tokenizer.json \
    SPRING_AI_EMBEDDING_TRANSFORMER_CACHE_ENABLED=false
# Heap must fit the ~1.1 GB ONNX model: Spring AI reads it into a byte[] and
# ONNX Runtime then builds its session, so the old -Xmx2g ran out of heap.
# Native ONNX memory sits outside the heap on top of this.
ENTRYPOINT ["java", "-Xms1g", "-Xmx4g", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/app.jar"]