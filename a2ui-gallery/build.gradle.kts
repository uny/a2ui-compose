plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
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

/** The catalog every example names in its `createSurface`. */
val generateGalleryCatalog = embedSpecDocuments(
    taskName = "generateGalleryCatalog",
    packageName = "dev.ynagai.a2ui.gallery",
    objectName = "GalleryCatalog",
    documents = mapOf("BASIC" to "v1_0/basic.json"),
)

kotlin {
    jvm()

    iosArm64()
    iosSimulatorArm64()
    macosArm64()

    js { browser() }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser() }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateExampleSources)
            kotlin.srcDir(generateGalleryCatalog)
        }
        commonMain.dependencies {
            api(projects.a2uiCore)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
