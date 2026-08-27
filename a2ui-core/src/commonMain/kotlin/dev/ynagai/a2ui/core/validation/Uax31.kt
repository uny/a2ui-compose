package dev.ynagai.a2ui.core.validation

/**
 * The canonical UAX #31 identifier pattern, exactly as `common_types.json` writes it.
 *
 * Matched by text rather than compiled. `\p{XID_Start}` is not a property `Regex` supports on
 * Kotlin/Native or Kotlin/Wasm at all, and the JVM's `java.lang.Character` methods that would
 * answer it are not reachable from common code — so handing this pattern to a regex engine
 * produces a different answer on each of the five targets this library ships to, and fails
 * outright on two of them.
 */
internal const val UAX31_IDENTIFIER_PATTERN: String = "^[\\p{XID_Start}_][\\p{XID_Continue}]*$"

/**
 * Whether [value] is a Unicode identifier, **approximately**.
 *
 * This is not UAX #31 and does not claim to be. The real definition is a pair of derived character
 * properties — `XID_Start` and `XID_Continue` — computed from the Unicode database, and matching
 * them needs the tables. Generating those is out of scope for the core library and is tracked as a
 * requirement for publishing rather than as a nicety: until it is done, this library does not claim
 * conformance on extension key names.
 *
 * What this does instead is ask [Char.isLetter] and [Char.isDigit], which Kotlin defines over the
 * Unicode general categories on every target. That is close to the real thing and wrong in both
 * directions at the margins:
 *
 * - **Too permissive.** `XID_Start` excludes characters that are letters by general category —
 *   most visibly the ones `NFKC` normalization would fold away. This accepts them.
 * - **Too strict.** `XID_Continue` includes combining marks (`Mn`, `Mc`) and connector
 *   punctuation, which are neither letters nor digits. An identifier written in a script that
 *   needs them is rejected here and valid per the specification.
 *
 * Both directions matter, and the second is the one that reaches users: it refuses a key the
 * specification allows, and it refuses it only in scripts whose writers are not the author.
 *
 * The specification's own test data — `wellsky_über`, `wellsky_τάξις` accepted;
 * `invalid-key-with-dashes`, `123start_with_number` rejected — passes on this approximation, which
 * is exactly why the gap has to be written down rather than discovered later from a green test
 * run.
 */
internal fun isUnicodeIdentifier(value: String): Boolean {
    if (value.isEmpty()) return false
    val first = value[0]
    if (!first.isLetter() && first != '_') return false
    for (index in 1 until value.length) {
        val character = value[index]
        if (!character.isLetter() && !character.isDigit() && character != '_') return false
    }
    return true
}
