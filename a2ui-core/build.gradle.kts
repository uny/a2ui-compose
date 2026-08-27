plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.maven.publish)
}


/**
 * Embeds the vendored specification schemas (`spec/`) in the library as Kotlin source.
 *
 * A catalog's `$ref`s reach into `common_types.json`, so a renderer cannot resolve them without
 * the document. Generating it from the vendored file rather than transcribing it by hand is what
 * keeps the two from drifting: a hand-written copy of a schema is wrong the first time the
 * specification moves and says nothing about it.
 *
 * The text is embedded exactly as vendored, not reformatted, so that what ships can be diffed
 * against the specification's own file.
 */
/**
 * A constant name for the file at [path], derived so that adding a file to a scanned directory
 * does not also mean editing this build script. `initial_state_validation.json` becomes
 * `INITIAL_STATE_VALIDATION`.
 *
 * A filename the derivation cannot make an identifier out of is refused here, where the message
 * can name the file. `dynamic-values.json` would otherwise emit `const val DYNAMIC-VALUES` and
 * fail the Kotlin compiler on a generated file under `build/` that nobody wrote -- which is the
 * whole cost of scanning a directory instead of listing it, and it should be paid once, loudly.
 */
val identifier = Regex("[A-Z_][A-Z0-9_]*")

fun constantName(path: String): String {
    val file = path.substringAfterLast('/')
    val name = file.removeSuffix(".json").uppercase()
    require(Regex("[A-Z_][A-Z0-9_]*").matches(name)) {
        "`$file` does not name a Kotlin constant (`$name`). Rename the file, or list it by hand."
    }
    return name
}

/**
 * Embeds the vendored specification schemas (`spec/`) in the library as Kotlin source.
 *
 * A catalog's `$ref`s reach into `common_types.json`, so a renderer cannot resolve them without
 * the document. Generating it from the vendored file rather than transcribing it by hand is what
 * keeps the two from drifting: a hand-written copy of a schema is wrong the first time the
 * specification moves and says nothing about it.
 *
 * The text is embedded exactly as vendored, not reformatted, so that what ships can be diffed
 * against the specification's own file.
 */
fun embedSchemas(
    taskName: String,
    packageName: String,
    objectName: String,
    documents: Map<String, String> = emptyMap(),
    directory: String? = null,
) = tasks.register(taskName) {
    val specDir = layout.projectDirectory.dir("spec")
    val outputDir = layout.buildDirectory.dir("generated/$taskName/kotlin")
    inputs.dir(specDir).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(outputDir)
    doLast {
        val scanned = directory?.let { relative ->
            specDir.dir(relative).asFile.listFiles()
                .orEmpty()
                .filter { it.isFile && it.extension == "json" }
                // Sorted so the generated source is the same on every machine; a directory
                // listing is not ordered, and an unstable one makes the build non-reproducible.
                .sortedBy { it.name }
                .map { constantName(it.name) to "$relative/${it.name}" }
                .also { pairs ->
                    // `associate` would keep the last silently, and a dropped case file reads as
                    // a suite that simply has fewer assertions in it.
                    val collisions = pairs.groupBy { it.first }.filterValues { it.size > 1 }
                    require(collisions.isEmpty()) {
                        "files in `$relative` share a constant name: " +
                            collisions.values.joinToString { group -> group.map { it.second }.toString() }
                    }
                }
                .toMap()
        }.orEmpty()
        val documents = documents + scanned
        // `ALL` is keyed by bare filename, so two documents from different directories that share
        // one would collide in the generated `mapOf` -- which Kotlin accepts, last wins.
        val byFile = documents.values.groupBy { it.substringAfterLast('/') }.filterValues { it.size > 1 }
        require(byFile.isEmpty()) { "documents share a filename and would collide in `ALL`: $byFile" }
        val target = outputDir.get().asFile.resolve(packageName.replace('.', '/'))
        target.mkdirs()
        val quote = "\""
        val body = documents.entries.joinToString("\n\n") { (name, path) ->
            // Chunked before escaping, not after: splitting the escaped text can cut an escape
            // sequence in half and emit a source file that does not parse.
            val literal = specDir.file(path).asFile.readText().chunked(100).joinToString(
                quote + " +\n            " + quote,
            ) { chunk ->
                chunk.replace("\\", "\\\\")
                    .replace(quote, "\\" + quote)
                    .replace("$", "\\$")
                    .replace("\n", "\\n")
            }
            listOf(
                "    /** `" + path + "`, verbatim. See `spec/README.md` for its provenance. */",
                "    internal const val " + name + ": String =",
                "        " + quote + literal + quote,
            ).joinToString("\n")
        }
        // An index alongside the constants: a caller that has to run every one of them cannot
        // enumerate `const val`s, and listing them by hand in the test would be a second place to
        // forget when the specification adds a file.
        val index = listOf(
            "",
            "    /** Every document above, keyed by the file it was generated from. */",
            "    internal val ALL: Map<String, String> = mapOf(",
        ) + documents.entries.sortedBy { it.key }.map { (name, path) ->
            "        \"" + path.substringAfterLast('/') + "\" to " + name + ","
        } + listOf("    )")
        target.resolve("$objectName.kt").writeText(
            listOf(
                "// Generated by the `$taskName` task from `a2ui-core/spec`. Do not edit.",
                "package $packageName",
                "",
                "internal object $objectName {",
                body,
                index.joinToString("\n"),
                "}",
                "",
            ).joinToString("\n"),
        )
    }
}

/** The specification documents `a2ui-core` needs at runtime to resolve a catalog's `${'$'}ref`s. */
val generateProtocolSchemas = embedSchemas(
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
val generateCatalogFixtures = embedSchemas(
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
val generateConformanceCases = embedSchemas(
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
