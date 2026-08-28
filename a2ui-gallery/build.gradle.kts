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
        /**
         * Tests that draw the corpus, rather than only decoding it.
         *
         * The same targets `a2ui-compose` and `a2ui-material3` draw on, excluded for the same
         * reasons -- the Kotlin/JS harness cannot boot Skiko, and Android's host test task has no
         * composition to draw into. The rest of this module's tests stay in `commonTest` and run
         * everywhere, because decoding the corpus needs no screen.
         */
        val composeUiTest by creating { dependsOn(commonTest.get()) }

        jvmTest.get().dependsOn(composeUiTest)
        macosArm64Test.get().dependsOn(composeUiTest)
        iosArm64Test.get().dependsOn(composeUiTest)
        iosSimulatorArm64Test.get().dependsOn(composeUiTest)
        wasmJsTest.get().dependsOn(composeUiTest)

        commonMain {
            kotlin.srcDir(generateExampleSources)
        }
        commonMain.dependencies {
            // The Material 3 adapter rather than `a2ui-compose` directly. The Gallery is the one
            // place a browser actually runs this renderer, so what it should exercise is the whole
            // stack a host would take -- and `a2ui-material3` re-exposes `a2ui-compose`, and
            // `a2ui-core` through it, so nothing is lost by naming the top of it.
            api(projects.a2uiMaterial3)
            // Named even though they arrive transitively: the Compose compiler plugin is applied
            // to this module and refuses to run without the runtime on the compile class path.
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.compose.ui.test)
        }
        jvmTest.dependencies {
            // As in the other modules: Compose's JVM test harness draws through Skiko, whose
            // native library ships with the desktop artifact rather than with `ui-test`.
            implementation(compose.desktop.currentOs)
        }
    }
}
