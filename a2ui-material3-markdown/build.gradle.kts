import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.dokka)
}

kotlin {
    explicitApi()

    // The block form, with `enabled` set: the no-argument `abiValidation()` overload is Kotlin 2.4
    // only, and on 2.3 the bare `abiValidation {}` leaves `checkLegacyAbi` SKIPPED (#64).
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation { enabled.set(true) }

    android {
        namespace = "dev.ynagai.a2ui.material3.markdown"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        // Runs `commonTest` on the JVM against the Android variant. Without it the warning the
        // build prints is literal: **not one test had ever run on the target whose `.aar` is
        // published.** The suite was green on five targets and silent on the sixth.
        //
        // Host tests, not instrumentation: these are the tests that need no screen. The Compose UI
        // tests stay out -- `composeUiTest` is wired to the other targets, and drawing on Android
        // needs Robolectric or a device, neither of which this repository has. That gap is real
        // and is recorded in the README rather than papered over.
        withHostTest {}
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
         * an instrumentation or Robolectric harness. The block conversion is a pure function and
         * is pinned from `commonTest` on every target; what lives here is only the drawing.
         */
        val composeUiTest by creating { dependsOn(commonTest.get()) }

        jvmTest.get().dependsOn(composeUiTest)
        macosArm64Test.get().dependsOn(composeUiTest)
        iosArm64Test.get().dependsOn(composeUiTest)
        iosSimulatorArm64Test.get().dependsOn(composeUiTest)
        wasmJsTest.get().dependsOn(composeUiTest)

        commonMain.dependencies {
            // `api`: the one public type here implements `A2uiMarkdownRenderer`, and a host names
            // `LocalA2uiMarkdownRenderer` to install it -- both are `a2ui-material3`'s, so a
            // consumer of this module is a consumer of that one.
            api(projects.a2uiMaterial3)
            // `implementation`, and kept that way on purpose: no `ASTNode` or element type
            // reaches a public signature, so the parser is this module's choice and not one it
            // makes for every consumer -- which is the reason `a2ui-material3` declines to
            // carry a parser at all (see `markdownText`'s KDoc there). `checkLegacyAbi` is
            // what would show a type leaking.
            implementation(libs.jetbrains.markdown)
            // Internal, as in `a2ui-material3`: `Column`, `Row`, `padding` and `drawBehind` are
            // read here and never handed back out.
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

/**
 * Maven Central.
 *
 * `signAllPublications` rather than a conditional: an unsigned artifact is rejected by the
 * portal's validation, so making it depend on a key being present would turn a missing secret
 * into a failure at the end of a release run instead of the start of one.
 *
 * The exemption is for a `-SNAPSHOT` version, **not** for a local publish -- so at a release
 * version `publishToMavenLocal` signs too, and needs the key. Measured with no key present:
 * `-PVERSION_NAME=0.1.0-reviewprobe` fails with `signAndroidPublication FAILED / No configured
 * signatory`, while the same command at `0.1.0-reviewprobe-SNAPSHOT` succeeds with every `sign*`
 * task SKIPPED. `cd.yml` therefore passes the signing secrets to its local-publish step as
 * well, which is also what makes the fail-early property above true rather than aspirational.
 *
 * The javadoc jar is a real one. Central requires the artifact either way, and the KDoc in this
 * module carries the reasoning behind its own rules -- why a link is its label, where the depth
 * bound sits and why -- which is the part a consumer cannot re-derive from the signatures.
 *
 * The sources jar is registered here rather than by `withSourcesJar(publish = true)` in the
 * `kotlin` block. Both were tried: the KMP helper registers one empty jar per target, each writing
 * to the same `-sources.jar` path the publication reads, and the publish task declares no
 * dependency on any of them -- so Gradle refuses the build for an implicit dependency it cannot
 * order. Asking the publishing plugin for it leaves one producer.
 */
mavenPublishing {
    configure(
        KotlinMultiplatform(
            javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
            sourcesJar = true,
        ),
    )
    publishToMavenCentral()
    signAllPublications()
}
