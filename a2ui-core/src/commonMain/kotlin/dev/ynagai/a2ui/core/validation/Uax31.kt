package dev.ynagai.a2ui.core.validation

/**
 * The canonical UAX #31 identifier pattern, exactly as `common_types.json` writes it.
 *
 * Matched by text rather than compiled. `\p{XID_Start}` is not a property `Regex` supports on
 * Kotlin/Native or Kotlin/Wasm at all, and the JVM's `java.lang.Character` methods that would
 * answer it are not reachable from common code -- so handing this pattern to a regex engine
 * produces a different answer on each of the five targets this library ships to, and fails
 * outright on two of them.
 */
internal const val UAX31_IDENTIFIER_PATTERN: String = "^[\\p{XID_Start}_][\\p{XID_Continue}]*$"

/** The Unicode version [isUnicodeIdentifier] answers against. */
internal val UNICODE_VERSION: String get() = XidTableSources.VERSION

/**
 * The `XID_Start` and `XID_Continue` code point ranges, decoded from the generated tables.
 *
 * Held as a flat `IntArray` of `[start, end, start, end, ...]` in ascending order, which is what
 * lets [contains] binary search it. Decoding is lazy because the tables cost a few thousand
 * integers to build and a host that never validates an identifier should not pay for them.
 */
private class CodePointTable(encoded: String) {
    private val bounds: IntArray = decode(encoded)

    /** Whether [codePoint] falls in any range. */
    fun contains(codePoint: Int): Boolean {
        var low = 0
        var high = bounds.size / 2 - 1
        while (low <= high) {
            val middle = (low + high) / 2
            when {
                codePoint < bounds[middle * 2] -> high = middle - 1
                codePoint > bounds[middle * 2 + 1] -> low = middle + 1
                else -> return true
            }
        }
        return false
    }

    private companion object {
        /**
         * The generator's format read back: base-36 pairs separated by a space, each pair the gap
         * from the previous range's end and then this range's span.
         *
         * Parsed by hand rather than with `split` and `toInt(36)` so that decoding a table of a few
         * thousand ranges does not allocate a list of a few thousand strings on the way. The format
         * is the generator's own and holds nothing but base-36 digits and spaces, so a character
         * outside that set means the two sides have drifted -- which is worth failing on rather
         * than folding into a table that is merely wrong.
         */
        fun decode(encoded: String): IntArray {
            val bounds = IntArray(encoded.count { it == ' ' } + 1)
            var index = 0
            var value = 0
            var digits = 0
            var previousEnd = -1
            var pendingStart = -1
            for (position in 0..encoded.length) {
                val character = if (position == encoded.length) ' ' else encoded[position]
                if (character != ' ') {
                    val digit = when (character) {
                        in '0'..'9' -> character - '0'
                        in 'a'..'z' -> character - 'a' + 10
                        else -> error("`$character` is not a base-36 digit in a generated table.")
                    }
                    value = value * 36 + digit
                    digits++
                    continue
                }
                check(digits > 0) { "a generated table holds an empty field at $position." }
                if (pendingStart < 0) {
                    pendingStart = previousEnd + 1 + value
                } else {
                    previousEnd = pendingStart + value
                    bounds[index++] = pendingStart
                    bounds[index++] = previousEnd
                    pendingStart = -1
                }
                value = 0
                digits = 0
            }
            check(pendingStart < 0 && index == bounds.size) {
                "a generated table ended mid-range ($index of ${bounds.size} bounds)."
            }
            return bounds
        }
    }
}

private val xidStart by lazy { CodePointTable(XidTableSources.XID_START) }
private val xidContinue by lazy { CodePointTable(XidTableSources.XID_CONTINUE) }

/**
 * Whether [value] is a Unicode identifier as UAX #31 defines one.
 *
 * This is the rule the specification's canonical regex states -- a leading character in
 * `XID_Start` or an underscore, then characters in `XID_Continue` -- answered from the derived
 * property tables generated out of the vendored Unicode Character Database. It is the same answer
 * on all six targets, because nothing about it is delegated to a platform.
 *
 * It replaced an approximation over [Char.isLetter] and [Char.isDigit], which was wrong in both
 * directions and wrong in ways the specification's own test data could not see:
 *
 * - **Too permissive.** `U+037A` GREEK YPOGEGRAMMENI is `ID_Start` but is excluded from
 *   `XID_Start`, because `NFKC` normalization folds it away. It is a letter by general category,
 *   so the approximation accepted an identifier the specification rejects -- the direction that
 *   ships non-conformance behind a green test run.
 * - **Too strict.** `XID_Continue` includes combining marks (`Mn`, `Mc`) and connector
 *   punctuation, which are neither letters nor digits. An identifier written in a script that
 *   needs them was rejected here and valid per the specification, and it was refused only in
 *   scripts whose writers are not the author.
 *
 * Both directions are covered by tests rather than by this comment; the specification's own
 * fixtures -- `wellsky_über`, `wellsky_τάξις` accepted; `invalid-key-with-dashes`,
 * `123start_with_number` rejected -- pass either way, which is exactly why they are not enough.
 *
 * Astral characters are handled by code point, not by `Char`. A surrogate is in neither table, so
 * iterating UTF-16 units would reject every identifier written outside the Basic Multilingual
 * Plane -- silently, and only for those scripts.
 */
internal fun isUnicodeIdentifier(value: String): Boolean {
    if (value.isEmpty()) return false
    var index = 0
    var first = true
    while (index < value.length) {
        val character = value[index]
        val codePoint: Int
        if (character.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate()) {
            codePoint = 0x10000 + ((character.code - 0xD800) shl 10) + (value[index + 1].code - 0xDC00)
            index += 2
        } else {
            // A lone surrogate is passed through as its own code point rather than rejected here.
            // Neither table holds one, so it is refused a line later, by the same rule as any other
            // character that is not an identifier character -- and refusing it here would be a
            // second place stating the same verdict.
            codePoint = character.code
            index++
        }
        val allowed = if (first) {
            codePoint == UNDERSCORE || xidStart.contains(codePoint)
        } else {
            xidContinue.contains(codePoint)
        }
        if (!allowed) return false
        first = false
    }
    return true
}

/** `_`, which the pattern admits as a leading character alongside all of `XID_Start`. */
private const val UNDERSCORE: Int = '_'.code
