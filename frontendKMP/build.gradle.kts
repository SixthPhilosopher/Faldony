import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

group = "org.kiss"

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

// ---------------------------------------------------------------------------
// AuthConfig generation: the Google OAuth client id is baked into the build
// from the REQUIRED GOOGLE_CLIENT_ID env var (prod: compose build arg from
// deploy/.env; dev: container env passed into the gradle dev-server run).
// The API base is NOT baked: both prod (nginx) and dev (webpack-dev-server)
// proxy /api to the private backend container, so the app stays same-origin
// (FaldonyApi defaults baseUrl = "").
// Missing GOOGLE_CLIENT_ID fails the build fast on purpose.
// ---------------------------------------------------------------------------
val googleClientIdProvider = providers.environmentVariable("GOOGLE_CLIENT_ID").orElse("")
if (googleClientIdProvider.get().isBlank()) {
    throw GradleException("GOOGLE_CLIENT_ID must be set (env var) — build fails fast on purpose")
}

val generatedSrcDir = layout.buildDirectory.dir("generated/authConfig")

val generateAuthConfig = tasks.register("generateAuthConfig") {
    val outDir = generatedSrcDir
    inputs.property("googleClientId", googleClientIdProvider)
    outputs.dir(outDir)
    doLast {
        // Re-read inside the task: do not capture the script-level provider in
        // the closure (config-cache-incompatible: "this$0 is null").
        val id = System.getenv("GOOGLE_CLIENT_ID") ?: ""
        val dir = outDir.get().asFile.resolve("kotlin/org/kiss/data/auth")
        dir.mkdirs()
        dir.resolve("AuthConfig.kt").writeText(
            "package org.kiss.data.auth\n\n" +
                "/** Build-time auth config; see build.gradle.kts (GOOGLE_CLIENT_ID). */\n" +
                "object AuthConfig {\n" +
                "    const val GOOGLE_CLIENT_ID: String = \"$id\"\n" +
                "}\n"
        )
    }
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(generatedSrcDir.get().asFile.resolve("kotlin"))
            dependencies {
                implementation(libs.compose.runtime)
                implementation(libs.compose.foundation)
                implementation(libs.compose.material3)
                implementation(libs.compose.ui)
                implementation(libs.compose.components.resources)
                implementation(libs.compose.materialIconsExtended)
                implementation(libs.kotlinx.datetime)
                implementation(libs.multiplatform.settings.no.arg)
                implementation(libs.androidx.lifecycle.viewmodelCompose)
                implementation(libs.androidx.lifecycle.runtimeCompose)
                implementation(libs.wrappers.browser)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.filekit.core)
                implementation(libs.filekit.dialogs)
                implementation(libs.filekit.dialogs.compose)
            }
        }
        val wasmJsMain by getting {
            dependencies {
                implementation(libs.ktor.client.js)
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(generateAuthConfig)
}
tasks.matching { it.name == "compileKotlinWasmJs" || it.name == "compileKotlinJs" }.configureEach {
    dependsOn(generateAuthConfig)
}