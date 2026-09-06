/*
 * A consumer, not a subproject.
 *
 * This is a separate Gradle build with its own `settings.gradle.kts`, and it depends on
 * `dev.ynagai.a2ui` by *coordinate* rather than by project. That is the whole point: a project
 * dependency resolves through the producer's own configurations and so never reads the `.module`
 * metadata, the POM, or the per-target coordinates a real consumer resolves. The failure this
 * guards against is the one that does not show up until someone else tries to use the release --
 * publish succeeds, and the consumer cannot resolve.
 *
 * It declares every target the library publishes. Resolution is per-target, so a variant that was
 * published wrong, or not published at all, fails here and nowhere else.
 */
plugins {
    kotlin("multiplatform") version "2.4.10"
    id("com.android.kotlin.multiplatform.library") version "9.3.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
    id("org.jetbrains.compose") version "1.12.0"
}

kotlin {
    android {
        namespace = "dev.ynagai.a2ui.smoketest"
        compileSdk = 37
        minSdk = 24
    }

    jvm()

    iosArm64()
    iosSimulatorArm64()
    macosArm64()

    js { browser() }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser() }

    sourceSets {
        commonMain.dependencies {
            // The version under test, passed in by the release workflow. It defaults to the
            // snapshot so the check is runnable by hand after a `publishToMavenLocal`.
            val a2ui = (findProperty("a2uiVersion") as String?) ?: "0.1.0-SNAPSHOT"
            implementation("dev.ynagai.a2ui:a2ui-core:$a2ui")
            implementation("dev.ynagai.a2ui:a2ui-compose:$a2ui")
            implementation("dev.ynagai.a2ui:a2ui-material3:$a2ui")
        }
    }
}
