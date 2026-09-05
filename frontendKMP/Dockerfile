# ---- build stage: production wasm bundle ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

# Build-time auth config: the Google OAuth client id is baked into the KMP
# build (generateAuthConfig reads GOOGLE_CLIENT_ID). Public identifier, safe
# to ship; provided by compose from deploy/.env, fed to the gradle build env.
ARG GOOGLE_CLIENT_ID=
ENV GOOGLE_CLIENT_ID=$GOOGLE_CLIENT_ID

# The KGP-managed Node.js (v25) needs libatomic.so.1, which the temurin base
# lacks; apt-get installs it (the temurin images are debian-based).
RUN apt-get update && apt-get install -y --no-install-recommends gzip libatomic1 \
    && rm -rf /var/lib/apt/lists/*

# Layer 1: build metadata ONLY. This runs dependency resolution: KGP
# generates package.json, yarn installs node_modules, and the driver, node
# dist and npm packages are cached in the BuildKit gradle cache (~/.gradle).
# `kotlinWasmNpmInstall` needs the project's dependency config but NOT the
# source files, so this layer is reused on every source-only rebuild.
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
# Pinned yarn lockfile: makes npm install deterministic.
COPY kotlin-js-store ./kotlin-js-store
RUN chmod +x gradlew
RUN --mount=type=cache,id=faldony-frontend-gradle,target=/root/.gradle ./gradlew --no-daemon --console=plain kotlinWasmNpmInstall

# Layer 2: application sources. Compile to WebAssembly (wasm-opt) + webpack.
# The wasm pipeline replaces the pathological JS source-map/terser phase —
# builds complete in ~4 min. The distribution task assembles index.html +
# wasm + js + source map into build/dist/wasmJs/productionExecutable.
COPY src ./src
ENV NODE_OPTIONS=--max-old-space-size=4096
RUN --mount=type=cache,id=faldony-frontend-gradle,target=/root/.gradle ./gradlew --no-daemon --console=plain \
    --configuration-cache \
    wasmJsBrowserDistribution

# The Kotlin webpack output has a stable loader name even though the wasm
# files are content-addressed. Hash the loader and rewrite the HTML shell so a
# new deployment can never reuse an old loader. Production wasm has no Kotlin
# DWARF mapping, so its JS source map is not useful to users and is omitted.
# Pre-compressed files are served by nginx via gzip_static without spending
# CPU compressing the same large wasm binaries for every request.
RUN set -eux; \
    dist=/app/build/dist/wasmJs/productionExecutable; \
    loader="$dist/frontendKMP.js"; \
    sed -i '/^\/\/# sourceMappingURL=frontendKMP\.js\.map$/d' "$loader"; \
    hash="$(sha256sum "$loader" | cut -c1-12)"; \
    hashed_loader="frontendKMP.${hash}.js"; \
    mv "$loader" "$dist/$hashed_loader"; \
    sed -i "s|src=\"frontendKMP.js\"|src=\"$hashed_loader\"|" "$dist/index.html"; \
    grep -q "src=\"$hashed_loader\"" "$dist/index.html"; \
    find "$dist" -type f -name '*.map' -delete; \
    find "$dist" -type f \( -name '*.js' -o -name '*.wasm' -o -name '*.css' -o -name '*.json' \) \
        -exec gzip -9 -n -k {} +

# ---- runtime stage: static server + API proxy ----
FROM nginx:alpine@sha256:72ba65eb42c10344912a84ff42408db7d34f2feb642204570ab8fc5ffd29f1d3
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/build/dist/wasmJs/productionExecutable /usr/share/nginx/html
EXPOSE 80
