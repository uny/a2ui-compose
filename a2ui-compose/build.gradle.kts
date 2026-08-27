plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.maven.publish)
}

/**
 * The basic catalog, embedded in the published artifact.
 *
 * A renderer needs it at runtime rather than only in tests: the catalog names the property that
 * carries each component's children, so `CatalogChildResolver` cannot walk a tree without it. See
 * `spec/README.md`.
 */
val generateBasicCatalog = embedSpecDocuments(
    taskName = "generateBasicCatalog",
    packageName = "dev.ynagai.a2ui.compose",
    objectName = "BasicCatalogSource",
    documents = mapOf("BASIC" to "v1_0/basic.json"),
)

kotlin {
    explicitApi()

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()

    withSourcesJar(publish = true)

    android {
        namespace = "dev.ynagai.a2ui.compose"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    jvm()

    iosArm64()
    iosSimulatorArm64()
    macosArm64()

    // `binaries.executable()` is required by the Compose plugin even for a library: without it the
    // Skiko runtime is not bundled by webpack and Compose UI tests cannot load. See CMP-4906.
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
         * Tests that need a composition on screen.
         *
         * Every target but Kotlin/JS and Android: Compose's UI test harness cannot boot Skiko on JS
         * -- every
         * test dies in `Surface`'s initialiser with `org_jetbrains_skia_Surface__1nMakeRasterN32Premul
         * is not defined`, before any composition happens. The runtime is present in the test
         * resources, and `wasmJs` runs the same tests from the same source, so this is the JS
         * harness rather than anything here.
         *
         * A source set rather than a runtime skip: a test that returns early on one target still
         * reports as passing there, which is a claim this cannot make. What the build says instead
         * is that these tests do not run on JS at all.
         *
         * Android is left out for a different and weaker reason: its host test task has no
         * composition to draw into without an instrumentation or Robolectric harness, neither of
         * which this module carries. So the descent, the cycle guard and the placeholder paths ship
         * in the `.aar` with nothing executing them on that target. That is a real gap rather than
         * a justified exclusion -- recorded here so it is not mistaken for one.
         *
         * Rendering under Kotlin/JS is a separate question from testing under it, and that one is
         * settled: Compose Multiplatform 1.12.0 publishes a `js` (IR) variant of `compose.ui`, so
         * the target is supported and the artifacts are real. What is missing is a way to drive a
         * composition from a JS test, and the Gallery is where that gap gets covered instead --
         * it is the only place a browser actually runs this renderer.
         */
        val composeUiTest by creating { dependsOn(commonTest.get()) }

        /**
         * The one UI test that has to reach [MAX_RENDER_DEPTH], which wasmJs cannot afford.
         *
         * Composing 256 nested containers costs about 0.1s on the JVM and is not measurable on
         * either native target; in a browser the same test does not finish inside mocha's budget
         * and times out at 60s. That is a fact about the bound rather than about the test -- a
         * surface nesting 256 deep is one this renderer accepts and the web would hang on -- so it
         * is recorded as its own source set rather than hidden behind a runtime skip, and the bound
         * itself is left as a question for review.
         */
        val composeDeepTest by creating { dependsOn(composeUiTest) }

        jvmTest.get().dependsOn(composeDeepTest)
        macosArm64Test.get().dependsOn(composeDeepTest)
        iosArm64Test.get().dependsOn(composeDeepTest)
        iosSimulatorArm64Test.get().dependsOn(composeDeepTest)
        wasmJsTest.get().dependsOn(composeUiTest)

        commonMain {
            kotlin.srcDir(generateBasicCatalog)
        }
        commonMain.dependencies {
            api(projects.a2uiCore)
            // `api`, not `implementation`: the published surface is built out of these. A
            // `ComponentRenderer` is `(A2uiComponentScope, Modifier) -> Unit` marked `@Composable`,
            // and `LocalA2uiRegistry` is a `ProvidableCompositionLocal`, so `Modifier`, `Composer`
            // and the composition-local types all appear in `api/`'s dumps. Scoped
            // `implementation` they would reach a consumer's runtime classpath but not its compile
            // classpath, and writing a renderer against the published artifact would not compile.
            api(libs.compose.runtime)
            api(libs.compose.ui)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.compose.ui.test)
            // Only the UI tests draw anything -- `TestRegistry` stacks children in a `Column` and
            // draws text with Material 3. Nothing in `commonMain` imports either, so declaring them
            // there would put Material 3 in every consumer's runtime classpath to satisfy a test.
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
        }
        jvmTest.dependencies {
            // Compose's JVM test harness draws through Skiko, and the native library ships with the
            // desktop artifact rather than with `ui-test`. Without it every UI test fails in
            // `SkikoComposeUiTest`'s initialiser, before any composition happens. The other targets
            // carry their own renderer, so this is scoped to the JVM.
            implementation(compose.desktop.currentOs)
        }
    }
}
