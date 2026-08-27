plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.maven.publish)
}


/** The specification documents `a2ui-core` needs at runtime to resolve a catalog's `${'$'}ref`s. */
val generateProtocolSchemas = embedSpecDocuments(
    taskName = "generateProtocolSchemas",
    packageName = "dev.ynagai.a2ui.core.protocol",
    objectName = "ProtocolSchemaSources",
    documents = mapOf(
        "COMMON_TYPES" to "v1_0/common_types.json",
        "AGENT_TO_RENDERER" to "v1_0/agent_to_renderer.json",
        "RENDERER_TO_AGENT" to "v1_0/renderer_to_agent.json",
        "CATALOG_DEFINITION" to "v1_0/catalog_definition.json",
    ),
)

/**
 * Catalogs the tests check against.
 *
 * The published basic catalog rather than one written for the occasion: the checker's whole job is
 * to apply what a real catalog says, and a catalog authored alongside the checker would agree with
 * it by construction. Generated into test sources rather than read from disk because the tests run
 * on Kotlin/JS and Kotlin/Wasm in a browser, where there is no disk to read.
 */
val generateCatalogFixtures = embedSpecDocuments(
    taskName = "generateCatalogFixtures",
    packageName = "dev.ynagai.a2ui.core.validation",
    objectName = "CatalogFixtures",
    documents = mapOf(
        "BASIC" to "v1_0/catalogs/basic.json",
        "TESTING" to "v1_0/catalogs/testing.json",
    ),
)

/**
 * The specification's own conformance cases, embedded so `commonTest` can run them.
 *
 * Scanned rather than listed, so that taking a newer specification revision is a matter of
 * replacing the directory. The suite files name the schema and the catalog each one is checked
 * against, and the harness reads both from the file rather than assuming -- three of them bind the
 * `catalog.json` placeholder to the testing catalog rather than to the basic one.
 */
val generateConformanceCases = embedSpecDocuments(
    taskName = "generateConformanceCases",
    packageName = "dev.ynagai.a2ui.core.conformance",
    objectName = "ConformanceSources",
    directory = "v1_0/cases",
)

kotlin {
    explicitApi()

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()

    withSourcesJar(publish = true)

    android {
        namespace = "dev.ynagai.a2ui.core"
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
        commonMain {
            kotlin.srcDir(generateProtocolSchemas)
        }
        commonTest {
            kotlin.srcDir(generateCatalogFixtures)
            kotlin.srcDir(generateConformanceCases)
        }
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
