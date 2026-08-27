plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    // Applied even though nothing here is `@Composable` yet: this module depends on Compose, and
    // it is the Compose plugin that wires the Skiko runtime into the web test bundles. Without it
    // the wasm test task loads an executable that cannot start and reports no tests at all --
    // which the Gradle test task then refuses, rather than passing with zero.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

/**
 * The specification's own example corpus, embedded so tests and the Gallery can read it on every
 * target. Scanned rather than listed, so that taking a newer specification revision is a matter of
 * replacing the directory.
 *
 * Without named constants: the specification's filenames (`00_simple-text.json`) are not Kotlin
 * identifiers, and nothing here reaches for one example by name -- the corpus is iterated.
 */
val generateExampleSources = embedSpecDocuments(
    taskName = "generateExampleSources",
    packageName = "dev.ynagai.a2ui.gallery",
    objectName = "ExampleSources",
    directory = "v1_0/examples",
    namedConstants = false,
)

kotlin {
    // As in `a2ui-core`. The sources already write every modifier by hand; without this, the next
    // declaration added without one joins the module's API silently.
    explicitApi()

    jvm()

    iosArm64()
    iosSimulatorArm64()
    macosArm64()

    // `binaries.executable()` on both web targets, as in `a2ui-compose`. The Compose plugin
    // checks for it and says why (CMP-4906): without an executable binary webpack does not bundle
    // the Skiko runtime, so a web test loads something that cannot start. On JS the symptom
    // arrives earlier still, as a duplicate `org.w3c.dom.events.EventListener` at compile time.
    js {
        browser()
        binaries.executable()
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateExampleSources)
        }
        commonMain.dependencies {
            api(projects.a2uiCompose)
            // `a2ui-compose` keeps its Compose dependencies `implementation`, so they do not reach
            // here through the `api` above -- and the Compose compiler plugin, now applied to this
            // module, refuses to run without the runtime on the class path.
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
