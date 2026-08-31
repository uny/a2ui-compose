plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    // The Gallery is Compose, so the compiler plugin is needed for its own sake. It was applied
    // here before that was true, and for a second reason that still holds: it is this plugin that
    // wires the Skiko runtime into the web test bundles. Without it the wasm test task loads an
    // executable that cannot start and reports no tests at all -- which the Gradle test task then
    // refuses, rather than passing with zero.
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
    macosArm64 {
        // A native macOS window rather than a library: `main.macos.kt` is the Gallery's entry
        // point on this target, and without an executable binary nothing links it.
        binaries.executable {
            entryPoint = "dev.ynagai.a2ui.gallery.main"
        }
    }

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

        /**
         * The Gallery's entry points, grouped where two targets share one.
         *
         * The two iOS targets share a `MainViewController`, and the two web targets share a
         * `main` -- the web pair can, because the container is named by id rather than reached
         * through a DOM type, which is the one thing Kotlin/JS and Kotlin/Wasm do differently
         * here. JVM and macOS each get their own, because a desktop `application {}` and an
         * `NSApplication` run loop have nothing in common to share.
         */
        val iosMain by creating { dependsOn(commonMain.get()) }
        iosArm64Main.get().dependsOn(iosMain)
        iosSimulatorArm64Main.get().dependsOn(iosMain)

        val webMain by creating { dependsOn(commonMain.get()) }
        jsMain.get().dependsOn(webMain)
        wasmJsMain.get().dependsOn(webMain)

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
        jvmMain.dependencies {
            // `Window` and `application` are the desktop artifact's, not `compose.ui`'s. Named
            // here rather than only in `jvmTest`, which already had it for Skiko: the entry point
            // is production code on this target.
            implementation(compose.desktop.currentOs)
        }
    }
}

/**
 * The desktop entry point, so `./gradlew :a2ui-gallery:run` opens the Gallery.
 *
 * Only `mainClass` is set. Packaging (`packageDistributionForCurrentOS` and friends) would need a
 * distribution name, a version and an icon per platform, and nothing asks for an installer: this
 * module is a development tool that is not published.
 */
compose.desktop {
    application {
        mainClass = "dev.ynagai.a2ui.gallery.GalleryMain"
    }
}
