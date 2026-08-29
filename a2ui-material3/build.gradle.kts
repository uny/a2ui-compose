plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.maven.publish)
}

kotlin {
    explicitApi()

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()

    withSourcesJar(publish = true)

    android {
        namespace = "dev.ynagai.a2ui.material3"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    jvm()

    iosArm64()
    iosSimulatorArm64()
    macosArm64()

    // `binaries.executable()` on both web targets, as in `a2ui-compose` and for the same reason:
    // without it webpack does not bundle the Skiko runtime and a Compose UI test loads something
    // that cannot start. See CMP-4906.
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
         * Tests that need a composition on screen -- every target but Kotlin/JS and Android.
         *
         * The exclusions are `a2ui-compose`'s, verbatim, and are explained there: the JS harness
         * cannot boot Skiko, and Android's host test task has no composition to draw into without
         * an instrumentation or Robolectric harness. Every renderer in this module draws, so
         * everything here lives in this source set.
         */
        val composeUiTest by creating { dependsOn(commonTest.get()) }

        jvmTest.get().dependsOn(composeUiTest)
        macosArm64Test.get().dependsOn(composeUiTest)
        iosArm64Test.get().dependsOn(composeUiTest)
        iosSimulatorArm64Test.get().dependsOn(composeUiTest)
        wasmJsTest.get().dependsOn(composeUiTest)

        commonMain.dependencies {
            api(projects.a2uiCompose)
            // `api` rather than `implementation`, and not for the usual reason -- no Material 3
            // type appears in this module's published signatures. It is `api` because a host
            // cannot *use* what this module draws without it: every renderer here reads
            // `MaterialTheme`, so a consumer has to put one above the surface, and a consumer who
            // got these renderers without a way to name `MaterialTheme` would have a registry that
            // throws on the first component it draws.
            api(libs.compose.material3)
            // Internal: `Arrangement`, `Alignment` and the `fillMax*` modifiers are read here and
            // never handed back out. Material 3 already exposes foundation transitively; declared
            // anyway so the dependency this module actually compiles against is written down.
            implementation(libs.compose.foundation)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.compose.ui.test)
        }
        jvmTest.dependencies {
            // As in `a2ui-compose`: Compose's JVM test harness draws through Skiko, whose native
            // library ships with the desktop artifact rather than with `ui-test`.
            implementation(compose.desktop.currentOs)
        }
    }
}
