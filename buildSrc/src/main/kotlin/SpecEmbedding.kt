import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskProvider

/** The name the generated index takes, and therefore the one no document may take. */
private const val INDEX_NAME = "ALL"

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

/**
 * [text] split into [size]-unit chunks that never cut a surrogate pair in half.
 *
 * `String.chunked` counts UTF-16 code units, so a boundary landing between the halves of an astral
 * character leaves a lone surrogate at the end of one chunk and another at the start of the next.
 * `writeText` encodes each of those as `?`, so the embedded document would differ from the vendored
 * file by two characters -- while still compiling, still parsing as JSON, and still passing every
 * test. Silent, and exactly what embedding the file verbatim is supposed to rule out.
 *
 * The boundary is pushed out by one unit rather than pulled in, so a chunk always makes progress.
 */
private fun String.chunkedWholeCodePoints(size: Int): List<String> {
    // A non-positive width makes no progress -- `end == start` appends "" forever, and `size = 0`
    // indexes `this[-1]` on the way. Both hang or crash the daemon rather than failing the task.
    require(size >= 1) { "a chunk is at least one unit wide, not $size." }
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < length) {
        var end = minOf(start + size, length)
        if (end < length && this[end - 1].isHighSurrogate() && this[end].isLowSurrogate()) end++
        chunks += substring(start, end)
        start = end
    }
    return chunks
}

/** [text] as a Kotlin string literal, chunked so no single source line grows unbounded. */
private fun literal(text: String, indent: String): String {
    val quote = "\""
    // Chunked before escaping, not after: splitting the escaped text can cut an escape sequence
    // in half and emit a source file that does not parse.
    val chunks = text.chunkedWholeCodePoints(100).joinToString(quote + " +\n" + indent + quote) { chunk ->
        chunk.replace("\\", "\\\\")
            .replace(quote, "\\" + quote)
            .replace("$", "\\$")
            // Both line terminators, not just LF. A vendored file saved with CRLF would otherwise
            // put a bare carriage return inside a Kotlin line string, which does not compile --
            // and the file that broke the build would be one under `build/` that nobody wrote.
            .replace("\r", "\\r")
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
    // Read while configuring, not while running. Inside `doLast` the nearest receiver is the task,
    // so `project` there means `Task.getProject()` -- which the configuration cache refuses at
    // execution time. `gradle.properties` turns that cache off only until KGP supports it, so a
    // task that reaches for a `Project` while running is a second blocker this build would own.
    val moduleName = this@embedSpecDocuments.name
    val specDir = layout.projectDirectory.dir("spec")
    val outputDir = layout.buildDirectory.dir("generated/$taskName/kotlin")
    inputs.dir(specDir).withPathSensitivity(PathSensitivity.RELATIVE)
    // The vendored files are not the only thing the output depends on. Without these, changing
    // which documents are listed -- or flipping `namedConstants` -- leaves the task up to date with
    // stale generated source in place, and what fails is a later compile somewhere else.
    inputs.property("documents", documents)
    inputs.property("moduleName", moduleName)
    // Optional rather than `orEmpty()`: "scan nothing" and "scan `spec/` itself" are different
    // configurations, and collapsing both to `""` leaves the task up to date across that change
    // with the previous run's generated source still in place.
    inputs.property("directory", directory).optional(true)
    inputs.property("namedConstants", namedConstants)
    inputs.property("packageName", packageName)
    inputs.property("objectName", objectName)
    outputs.dir(outputDir)
    doLast {
        val scanned = directory?.let { relative ->
            specDir.dir(relative).asFile.listFiles()
                .orEmpty()
                .filter { it.isFile && it.extension == "json" }
                .also { files ->
                    // A directory that is missing, renamed, or empty yields `null` or an empty
                    // array, and this task would then succeed with an empty `ALL` -- the same
                    // silent shortfall the collision check below refuses, arrived at from the
                    // other side. The corpus's own count assertion would catch it eventually, in
                    // a test, far from the path that was actually wrong.
                    require(files.isNotEmpty()) {
                        "`$relative` holds no `.json` files (looked in " +
                            "`${specDir.dir(relative).asFile}`). Check the path."
                    }
                }
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
        // Checked before the merge, because `+` resolves a collision by keeping the scanned entry
        // -- so a hand-listed document that a scanned filename happens to shadow would vanish, and
        // a vanished document reads as one the specification simply does not have.
        val shadowed = documents.keys intersect scanned.keys
        require(shadowed.isEmpty()) {
            "listed documents are shadowed by files in `$directory`: " +
                shadowed.joinToString { "$it (${documents[it]} vs ${scanned[it]})" }
        }
        val all = documents + scanned
        // `ALL` is keyed by bare filename, so two documents from different directories that share
        // one would collide in the generated `mapOf` -- which Kotlin accepts, last wins.
        val byFile = all.values.groupBy { it.substringAfterLast('/') }.filterValues { it.size > 1 }
        require(byFile.isEmpty()) { "documents share a filename and would collide in `ALL`: $byFile" }
        // `ALL` is the index's own name. A document that claims it emits both `const val ALL` and
        // `val ALL` into one object, and the generated file then fails to compile on conflicting
        // declarations -- a build error in a file nobody wrote, which is what every other check
        // here exists to convert into a sentence naming the document.
        require(!namedConstants || INDEX_NAME !in all.keys) {
            "`${all[INDEX_NAME]}` takes the name `$INDEX_NAME`, which the generated index uses."
        }
        require(all.isNotEmpty()) {
            "`$taskName` was given no documents and no directory, so `$objectName.ALL` would be " +
                "empty -- which reads downstream as a corpus the specification does not have."
        }
        // Cleared, not just overwritten. Gradle does not empty a task's output directory between
        // runs, so renaming `objectName` or `packageName` leaves the previous file beside the new
        // one -- and a stale generated object compiles perfectly well and ships. Every check above
        // runs first: a failed one must leave the previous output alone rather than clear it and
        // then refuse to write a replacement.
        val root = outputDir.get().asFile
        // `deleteRecursively` reports a partial delete by returning false rather than throwing, and
        // a survivor here is exactly the stale object this call exists to remove -- so an ignored
        // result would hide the failure behind a check that looks like it ran.
        require(root.deleteRecursively()) { "could not clear `$root` of the previous run's output." }
        val target = root.resolve(packageName.replace('.', '/'))
        target.mkdirs()

        val body = if (namedConstants) {
            all.entries.joinToString("\n\n") { (name, path) ->
                // Hand-listed keys go through the same check as derived ones. Skipping them is how
                // `documents = mapOf("basic-catalog" to ...)` reaches the compiler as
                // `const val basic-catalog` -- a build failure in a generated file nobody wrote,
                // which is precisely what `constantName` exists to convert into a named message.
                require(Regex("[A-Z_][A-Z0-9_]*").matches(name)) {
                    "`$name` (listed for `$path`) does not name a Kotlin constant."
                }
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
            if (namedConstants) {
                "    /** Every document above, keyed by the file it was generated from. */"
            } else {
                // There is nothing above when the constants are suppressed, and a comment that
                // points at absent declarations reads as a generator that only half ran.
                "    /** Every document, keyed by the file it was generated from. */"
            },
            "    internal val $INDEX_NAME: Map<String, String> = mapOf(",
        ) + all.entries.sortedBy { it.key }.map { (name, path) ->
            val value = if (namedConstants) name else literal(specDir.file(path).asFile.readText(), "            ")
            // The key goes through `literal` too. A filename is not a Kotlin string literal --
            // a POSIX name may hold a quote, a backslash or a newline -- and pasting one in raw
            // either breaks the generated file or, worse, leaves `ALL` keyed by something that is
            // not the filename any more.
            "        " + literal(path.substringAfterLast('/'), "            ") + " to " + value + ","
        } + listOf("    )")

        target.resolve("$objectName.kt").writeText(
            listOfNotNull(
                "// Generated by the `$taskName` task from `$moduleName/spec`. Do not edit.",
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
