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
import java.util.Properties

plugins {
    // From the producer's own version catalog, read across the build boundary in
    // `settings.gradle.kts`. A consumer pinned to a different Kotlin than the library was built
    // with is not a consumer this gate should be testing.
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

/**
 * The version under test, passed in by the release workflow as `-Pa2uiVersion`.
 *
 * With no property it falls back to the producer's own `VERSION_NAME`, read across the build
 * boundary rather than copied. A literal default here would keep naming `0.1.0-SNAPSHOT` after the
 * producer moved on, and because `mavenLocal()` still holds that older snapshot the by-hand run
 * would go green against artifacts that are not the ones just published.
 */
val a2uiVersion: String =
    (findProperty("a2uiVersion") as String?)?.takeIf { it.isNotBlank() }
        ?: Properties()
            .apply { file("../gradle.properties").inputStream().use { load(it) } }
            .getProperty("VERSION_NAME")
        ?: error("No -Pa2uiVersion, and VERSION_NAME is not set in ../gradle.properties")

kotlin {
    android {
        namespace = "dev.ynagai.a2ui.smoketest"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
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
            implementation("dev.ynagai.a2ui:a2ui-core:$a2uiVersion")
            implementation("dev.ynagai.a2ui:a2ui-compose:$a2uiVersion")
            implementation("dev.ynagai.a2ui:a2ui-material3:$a2uiVersion")
        }
    }
}
