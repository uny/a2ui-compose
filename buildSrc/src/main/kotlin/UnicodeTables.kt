import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskProvider
import java.security.MessageDigest

/** The largest code point Unicode assigns, and therefore the widest a parsed range may be. */
private const val MAX_CODE_POINT = 0x10FFFF

/** A parsed `DerivedCoreProperties.txt` line: the inclusive code point range it covers. */
private data class CodePointRange(val start: Int, val end: Int)

/**
 * The SHA-256 `unicode/README.md` records for [file], as lowercase hex.
 *
 * Read out of the README's own table rather than passed in from the build script, for the reason
 * the version regex below gives: a digest named in a second place is a second place to update, and
 * the one that would keep vouching for a file that has moved on. `unicode/README.md` already names
 * itself as the row to update when the database is replaced, so this makes the value it records
 * load-bearing instead of decorative.
 */
private fun expectedDigest(readme: String, file: String): String =
    Regex("""\|\s*`${Regex.escape(file)}`\s*\|[^|\n]*\|[^|\n]*\|\s*`([0-9a-f]{64})`\s*\|""")
        .find(readme)
        ?.groupValues
        ?.get(1)
        ?: error("`unicode/README.md` has no `| \\`$file\\` | ... | <sha-256> |` row. See that file.")

/** [bytes] as lowercase hex, which is the form `unicode/README.md` and `shasum -a 256` both use. */
private fun hex(bytes: ByteArray): String =
    bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

/**
 * Every range `DerivedCoreProperties.txt` assigns [property], in ascending order and coalesced.
 *
 * The file's syntax is one range per line -- `0041..005A    ; XID_Start # ...` or the single code
 * point form `00AA          ; XID_Start # ...` -- with `#` starting a comment and blank lines
 * throughout. The property name is matched exactly rather than by prefix: `ID_Start` is a line in
 * the same file, and a `startsWith` would fold it into `XID_Start`'s table while still producing a
 * plausible-looking build.
 *
 * Coalescing matters for more than size. The lookup this feeds is a binary search over range
 * starts, and it is only correct if the ranges do not touch or overlap; the file lists them
 * separately when their general categories differ, so `0041..005A` and `005B..005C` can both
 * appear where one range would do.
 */
private fun parseProperty(text: String, property: String): List<CodePointRange> {
    val ranges = mutableListOf<CodePointRange>()
    for (line in text.lineSequence()) {
        val body = line.substringBefore('#').trim()
        if (body.isEmpty()) continue
        val fields = body.split(';').map { it.trim() }
        if (fields.size != 2 || fields[1] != property) continue
        val bounds = fields[0].split("..")
        require(bounds.size in 1..2) { "`${fields[0]}` is not a code point or a range." }
        val start = bounds[0].toInt(16)
        val end = bounds.last().toInt(16)
        require(start <= end) { "`${fields[0]}` runs backwards." }
        require(end <= MAX_CODE_POINT) { "`${fields[0]}` runs past U+10FFFF." }
        ranges += CodePointRange(start, end)
    }
    // A property that matched nothing means the file moved a name, not that Unicode dropped it --
    // and an empty table would ship as "no character is an identifier", which every test on the
    // reject side still passes.
    require(ranges.isNotEmpty()) { "`$property` matched no line. Check the property name." }
    ranges.sortBy { it.start }
    val coalesced = mutableListOf(ranges.first())
    for (range in ranges.drop(1)) {
        val last = coalesced.last()
        require(range.start > last.start) { "two ranges start at U+${range.start.toString(16)}." }
        if (range.start <= last.end + 1) {
            coalesced[coalesced.lastIndex] = CodePointRange(last.start, maxOf(last.end, range.end))
        } else {
            coalesced += range
        }
    }
    return coalesced
}

/**
 * [ranges] as the string the generated table decodes at first use.
 *
 * Base-36 pairs separated by a space: the gap from the previous range's end, then the span of this
 * one. Both numbers are small for all but a handful of ranges, so the whole of `XID_Continue` fits
 * in a few kilobytes of source.
 *
 * A string rather than `intArrayOf(...)`. An array literal of this size is initialized element by
 * element in the enclosing object's static initializer, which on the JVM is one method with a 64 KB
 * ceiling -- and the two tables together already sit close enough to it that the next Unicode
 * version could push a generated file nobody wrote past a limit whose error message names none of
 * this.
 */
private fun encode(ranges: List<CodePointRange>): String {
    val tokens = mutableListOf<String>()
    var previousEnd = -1
    for (range in ranges) {
        tokens += (range.start - previousEnd - 1).toString(36)
        tokens += (range.end - range.start).toString(36)
        previousEnd = range.end
    }
    return tokens.joinToString(" ")
}

/**
 * Generates the `XID_Start` and `XID_Continue` lookup tables from a vendored Unicode Character
 * Database file.
 *
 * A2UI v1.0 requires identifiers to match `^[\p{XID_Start}_][\p{XID_Continue}]*$`, and neither
 * half of that is answerable from Kotlin common code: `Regex` does not support the property on
 * Kotlin/Native or Kotlin/Wasm, and `java.lang.Character` is not reachable. The properties are
 * derived from the Unicode database, so the only way to answer the question the specification
 * actually asks is to carry the derivation's output.
 *
 * Generated from the vendored file rather than transcribed, for the reason `embedSpecDocuments`
 * gives about schemas: a hand-written table is wrong the first time Unicode assigns a character
 * and says nothing about it. Moving to a newer database is replacing one file and recording the
 * version, not re-deriving anything.
 *
 * @param taskName the task to register.
 * @param packageName the package the generated object goes in.
 * @param objectName the generated object's name.
 * @param file the vendored database file, relative to the module's `unicode/` directory.
 */
fun Project.generateXidTables(
    taskName: String,
    packageName: String,
    objectName: String,
    file: String = "DerivedCoreProperties.txt",
): TaskProvider<Task> = tasks.register(taskName) {
    // Resolved while configuring, for the reason `embedSpecDocuments` documents: `project` inside
    // `doLast` is a configuration-cache violation at execution time.
    val moduleName = this@generateXidTables.name
    val unicodeDir = layout.projectDirectory.dir("unicode")
    val outputDir = layout.buildDirectory.dir("generated/$taskName/kotlin")
    inputs.dir(unicodeDir).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("file", file)
    inputs.property("moduleName", moduleName)
    inputs.property("packageName", packageName)
    inputs.property("objectName", objectName)
    outputs.dir(outputDir)
    doLast {
        val source = unicodeDir.file(file).asFile
        require(source.isFile) { "`$file` is not in `${unicodeDir.asFile}`. See `unicode/README.md`." }
        // The digest is checked before a byte of the file is interpreted. Nothing downstream can
        // tell a corrupted database from a real one: `parseProperty` accepts any well-formed range,
        // and the `XID_Continue` superset check below only catches ranges that went *missing*, so a
        // file that gained a line would widen the identifier rule and leave the suite green. This is
        // the control that makes `unicode/README.md`'s recorded SHA-256 mean something -- and it is
        // what turns a CRLF-rewritten checkout into a named build failure rather than a silently
        // different table (see the `a2ui-core/unicode/**` rule in `.gitattributes`).
        val readme = unicodeDir.file("README.md").asFile
        require(readme.isFile) { "`README.md` is not in `${unicodeDir.asFile}`; it records the digest." }
        val expected = expectedDigest(readme.readText(), file)
        val actual = hex(MessageDigest.getInstance("SHA-256").digest(source.readBytes()))
        require(actual == expected) {
            "`$file` does not match the SHA-256 `unicode/README.md` records for it.\n" +
                "  expected $expected\n" +
                "  actual   $actual\n" +
                "Either the file was replaced without updating the README row, or the checkout " +
                "rewrote its line endings."
        }
        val text = source.readText()
        // The version comes out of the file's own first line -- `# DerivedCoreProperties-17.0.0.txt`
        // -- rather than being passed in. A version named in a build script is a second place to
        // update, and the one that would silently keep saying 17.0.0 after the file moved on.
        val version = Regex("""^# DerivedCoreProperties-([0-9.]+)\.txt""")
            .find(text)
            ?.groupValues
            ?.get(1)
            ?: error("`$file` does not open with the `# DerivedCoreProperties-<version>.txt` line.")
        val start = parseProperty(text, "XID_Start")
        val continues = parseProperty(text, "XID_Continue")
        // `XID_Continue` is defined as a superset of `XID_Start`. Asserting it here is what catches
        // the two tables being parsed from different properties, or one of them half-read: both
        // failures otherwise produce tables that look reasonable and reject real identifiers.
        for (range in start) {
            val covering = continues.lastOrNull { it.start <= range.start }
            require(covering != null && covering.end >= range.end) {
                "U+${range.start.toString(16)}..U+${range.end.toString(16)} is `XID_Start` but " +
                    "not wholly `XID_Continue`, which cannot be true of a Unicode database."
            }
        }

        val root = outputDir.get().asFile
        require(root.deleteRecursively()) { "could not clear `$root` of the previous run's output." }
        val target = root.resolve(packageName.replace('.', '/'))
        target.mkdirs()
        target.resolve("$objectName.kt").writeText(
            """
            |// Generated by the `$taskName` task from `$moduleName/unicode/$file`. Do not edit.
            |package $packageName
            |
            |/**
            | * The `XID_Start` and `XID_Continue` code point ranges of Unicode $version.
            | *
            | * Each table is base-36 pairs separated by a space: the gap from the previous range's
            | * end, then the span of this one. Decoded into a searchable form on first use.
            | */
            |internal object $objectName {
            |    /** The Unicode version these tables were derived from. */
            |    const val VERSION: String = "$version"
            |
            |    /** ${start.size} ranges. */
            |    const val XID_START: String =
            |        ${literalOf(encode(start))}
            |
            |    /** ${continues.size} ranges. */
            |    const val XID_CONTINUE: String =
            |        ${literalOf(encode(continues))}
            |}
            |
            """.trimMargin(),
        )
    }
}

/**
 * [text] as a Kotlin string literal split across lines, so no single source line grows unbounded.
 *
 * The encoding is base-36 digits and spaces, so nothing here needs escaping; the split is placed on
 * a space and the space kept at the end of the preceding chunk, which is what makes concatenating
 * the pieces reproduce the original exactly.
 */
private fun literalOf(text: String): String {
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        val limit = minOf(start + 96, text.length)
        // Break after the last space in the window, so a token is never cut in half. A window with
        // no space at all -- which the encoding cannot produce, but a future one might -- falls
        // back to the hard limit rather than looping forever on no progress.
        val boundary = if (limit == text.length) {
            limit
        } else {
            text.lastIndexOf(' ', limit).takeIf { it > start }?.plus(1) ?: limit
        }
        chunks += text.substring(start, boundary)
        start = boundary
    }
    return chunks.joinToString("\" +\n        \"", prefix = "\"", postfix = "\"")
}
