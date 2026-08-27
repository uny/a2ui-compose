import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskProvider

/**
 * A constant name for the file at [path], derived so that adding a file to a scanned directory
 * does not also mean editing a build script. `initial_state_validation.json` becomes
 * `INITIAL_STATE_VALIDATION`.
 *
 * A filename the derivation cannot make an identifier out of is refused here, where the message
 * can name the file. `dynamic-values.json` would otherwise emit `const val DYNAMIC-VALUES` and
 * fail the Kotlin compiler on a generated file under `build/` that nobody wrote -- which is the
 * whole cost of scanning a directory instead of listing it, and it should be paid once, loudly.
 *
 * A corpus whose filenames are not identifiers at all is not a mistake to be renamed around; it
 * is a corpus that wants [SpecEmbedding.namedConstants] off.
 */
private fun constantName(path: String): String {
    val file = path.substringAfterLast('/')
    val name = file.removeSuffix(".json").uppercase()
    require(Regex("[A-Z_][A-Z0-9_]*").matches(name)) {
        "`$file` does not name a Kotlin constant (`$name`). Rename the file, list it by hand, or " +
            "generate this corpus with `namedConstants = false`."
    }
    return name
}

/** [text] as a Kotlin string literal, chunked so no single source line grows unbounded. */
private fun literal(text: String, indent: String): String {
    val quote = "\""
    // Chunked before escaping, not after: splitting the escaped text can cut an escape sequence
    // in half and emit a source file that does not parse.
    val chunks = text.chunked(100).joinToString(quote + " +\n" + indent + quote) { chunk ->
        chunk.replace("\\", "\\\\")
            .replace(quote, "\\" + quote)
            .replace("$", "\\$")
            .replace("\n", "\\n")
    }
    return quote + chunks + quote
}

/**
 * Embeds vendored specification documents in a module as Kotlin source.
 *
 * A catalog's `$ref`s reach into `common_types.json`, so a renderer cannot resolve them without
 * the document. Generating it from the vendored file rather than transcribing it by hand is what
 * keeps the two from drifting: a hand-written copy of a schema is wrong the first time the
 * specification moves and says nothing about it. The same reasoning covers example payloads, which
 * is why this lives here rather than in one module's build script -- a second copy of the embedder
 * would be the very thing vendoring exists to prevent, applied to the build instead of the schema.
 *
 * The text is embedded exactly as vendored, not reformatted, so that what ships can be diffed
 * against the specification's own file.
 *
 * @param documents constant name to path, relative to the module's `spec/` directory.
 * @param directory a path under `spec/` whose `.json` files are all embedded, in addition to
 *   [documents].
 * @param namedConstants whether to emit a `const val` per document. A corpus that is only ever
 *   iterated does not need them, and the specification's example filenames (`00_simple-text.json`)
 *   are not identifiers.
 */
fun Project.embedSpecDocuments(
    taskName: String,
    packageName: String,
    objectName: String,
    documents: Map<String, String> = emptyMap(),
    directory: String? = null,
    namedConstants: Boolean = true,
): TaskProvider<Task> = tasks.register(taskName) {
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
                .map { (if (namedConstants) constantName(it.name) else it.name) to "$relative/${it.name}" }
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
        val all = documents + scanned
        // `ALL` is keyed by bare filename, so two documents from different directories that share
        // one would collide in the generated `mapOf` -- which Kotlin accepts, last wins.
        val byFile = all.values.groupBy { it.substringAfterLast('/') }.filterValues { it.size > 1 }
        require(byFile.isEmpty()) { "documents share a filename and would collide in `ALL`: $byFile" }
        val target = outputDir.get().asFile.resolve(packageName.replace('.', '/'))
        target.mkdirs()

        val body = if (namedConstants) {
            all.entries.joinToString("\n\n") { (name, path) ->
                listOf(
                    "    /** `$path`, verbatim. See `spec/README.md` for its provenance. */",
                    "    internal const val $name: String =",
                    "        " + literal(specDir.file(path).asFile.readText(), "            "),
                ).joinToString("\n")
            }
        } else {
            ""
        }

        // An index alongside the constants: a caller that has to run every one of them cannot
        // enumerate `const val`s, and listing them by hand in the test would be a second place to
        // forget when the specification adds a file.
        val index = listOf(
            "",
            "    /** Every document above, keyed by the file it was generated from. */",
            "    internal val ALL: Map<String, String> = mapOf(",
        ) + all.entries.sortedBy { it.key }.map { (name, path) ->
            val value = if (namedConstants) name else literal(specDir.file(path).asFile.readText(), "            ")
            "        \"" + path.substringAfterLast('/') + "\" to " + value + ","
        } + listOf("    )")

        target.resolve("$objectName.kt").writeText(
            listOfNotNull(
                "// Generated by the `$taskName` task from `${project.name}/spec`. Do not edit.",
                "package $packageName",
                "",
                "internal object $objectName {",
                body.ifEmpty { null },
                index.joinToString("\n"),
                "}",
                "",
            ).joinToString("\n"),
        )
    }
}
