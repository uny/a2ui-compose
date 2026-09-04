package dev.ynagai.a2ui.core.validation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The identifier rule against the derived property tables, not against an approximation.
 *
 * The specification's own fixtures are covered by `CatalogValidatorTest`, and they pass under
 * either implementation -- which is the reason this file exists. Everything asserted here is a
 * character the previous `isLetter`/`isDigit` approximation got wrong, in one direction or the
 * other, plus the structural invariants a decoding bug would break.
 *
 * Every expectation below was read out of the vendored `DerivedCoreProperties.txt`, not recalled.
 */
class Uax31Test {

    // --- the direction that shipped non-conformance behind a green test run --------------------

    @Test
    fun rejects_a_letter_that_xid_start_excludes() {
        // U+037A GREEK YPOGEGRAMMENI is `Lm`, so `Char.isLetter` says yes and the approximation
        // accepted it. It is `ID_Start` but not `XID_Start`: `NFKC` folds it to U+03B9, so
        // admitting it would let two identifiers that normalize to one thing both be spelled.
        assertFalse(isUnicodeIdentifier("ͺ"))
        assertFalse(isUnicodeIdentifier("ͺname"))
        // Not in `XID_Continue` either, so its position makes no difference.
        assertFalse(isUnicodeIdentifier("nameͺ"))
    }

    // --- the direction that reached users ------------------------------------------------------

    @Test
    fun accepts_the_combining_marks_xid_continue_includes() {
        // U+0301 COMBINING ACUTE ACCENT is `Mn`: neither a letter nor a digit, so the
        // approximation refused it -- and refused it only in scripts that need it.
        assertTrue(isUnicodeIdentifier("á"))
        // A mark cannot lead, because `XID_Start` does not hold one.
        assertFalse(isUnicodeIdentifier("́a"))
    }

    @Test
    fun accepts_the_connector_and_extender_characters_xid_continue_includes() {
        // U+203F UNDERTIE is `Pc`, U+00B7 MIDDLE DOT and U+30FB KATAKANA MIDDLE DOT are `Po` that
        // `XID_Continue` admits by way of `Other_ID_Continue`.
        assertTrue(isUnicodeIdentifier("a‿b"))
        assertTrue(isUnicodeIdentifier("a·b"))
        assertTrue(isUnicodeIdentifier("a・b"))
    }

    @Test
    fun accepts_a_symbol_that_other_id_start_admits() {
        // U+2118 SCRIPT CAPITAL P is `Sm`, so `Char.isLetter` said no. `XID_Start` holds it
        // through `Other_ID_Start`, which exists to keep characters that were identifiers before
        // the derivation was tightened.
        assertTrue(isUnicodeIdentifier("℘"))
    }

    // --- code points, not UTF-16 units ---------------------------------------------------------

    @Test
    fun reads_astral_characters_as_one_code_point() {
        // U+10400 DESERET CAPITAL LETTER LONG I is `XID_Start`. Iterating `Char`s would see two
        // surrogates, neither of which is in any table, and reject every identifier written
        // outside the Basic Multilingual Plane.
        assertTrue(isUnicodeIdentifier("𐐀"))
        assertTrue(isUnicodeIdentifier("a𐐀"))
        // U+1F600 GRINNING FACE is in neither table, so an astral character is not admitted
        // wholesale by the pairing above.
        assertFalse(isUnicodeIdentifier("😀"))
        assertFalse(isUnicodeIdentifier("a😀"))
    }

    @Test
    fun rejects_an_unpaired_surrogate() {
        assertFalse(isUnicodeIdentifier("\uD801"))
        assertFalse(isUnicodeIdentifier("a\uD801"))
        assertFalse(isUnicodeIdentifier("\uDC00a"))
    }

    // --- the parts of the pattern that are not a property --------------------------------------

    @Test
    fun admits_underscore_as_a_leading_character() {
        // `_` is `XID_Continue` but not `XID_Start`; the pattern writes it into the first
        // character class by hand, so the rule is not "whatever the tables say" on its own.
        assertTrue(isUnicodeIdentifier("_"))
        assertTrue(isUnicodeIdentifier("_1"))
        assertTrue(isUnicodeIdentifier("a_b"))
    }

    @Test
    fun rejects_an_empty_name_and_a_leading_digit() {
        assertFalse(isUnicodeIdentifier(""))
        assertFalse(isUnicodeIdentifier("1a"))
        // U+0669 ARABIC-INDIC DIGIT NINE is `Nd`: `XID_Continue`, never `XID_Start`.
        assertTrue(isUnicodeIdentifier("a٩"))
        assertFalse(isUnicodeIdentifier("٩a"))
    }

    @Test
    fun rejects_the_punctuation_the_specification_names() {
        for (name in listOf("invalid-key-with-dashes", "has space", "a.b", "a\$b", "a/b")) {
            assertFalse(isUnicodeIdentifier(name), "`$name` should have been rejected")
        }
    }

    // --- invariants a decoding bug would break -------------------------------------------------

    @Test
    fun both_tables_hold_exactly_the_code_points_the_database_assigns() {
        // `XID_Continue` is a superset of `XID_Start` by definition. A decoder that lost a range,
        // shifted one by a code point, or read the two tables from one property would break this
        // somewhere across the whole assigned range, while still answering plausibly for ASCII.
        //
        // The superset relation alone is not enough, and asserting only it is how this file was
        // wrong before: it constrains `XID_Continue` from *below*, so every continue-only range --
        // the combining marks, the digits, the connectors, roughly 3,300 code points that no
        // `XID_Start` character vouches for -- was pinned at six characters and no more. Dropping
        // `093E..094F` from the generator was measured to leave every test here green while every
        // Devanagari vowel sign stopped continuing an identifier: the exact "refused only in
        // scripts whose writers are not the author" failure `isUnicodeIdentifier` claims to fix.
        //
        // Cardinality is not enough either, and that is the subtler half. A count constrains how
        // many code points a table holds, never which -- so a range that *moves* passes it. Shifting
        // `200C..200D` to `200B..200C` was measured to hold both counts at their exact expected
        // values while U+200B ZERO WIDTH SPACE became a valid identifier character and U+200D ZERO
        // WIDTH JOINER stopped being one. Two invisible characters trading places inside a
        // validation boundary, behind a green run, is the worst version of this bug, so membership
        // is pinned directly: an order-sensitive fingerprint over every accepted code point, which
        // no shift, swap or substitution survives. `Int` is 32-bit two's complement and wraps
        // identically on all six targets, so the value below is the same everywhere.
        var starts = 0
        var continues = 0
        var startFingerprint = 0
        var continueFingerprint = 0
        for (codePoint in 0..0x10FFFF) {
            val character = codePointString(codePoint)
            val leads = isUnicodeIdentifier(character)
            val continued = isUnicodeIdentifier("a$character")
            if (continued) {
                continues++
                continueFingerprint = continueFingerprint * 31 + codePoint
            }
            if (!leads) continue
            starts++
            startFingerprint = startFingerprint * 31 + codePoint
            // Built only on failure. `assertTrue`'s message parameter is a `String`, not a lambda,
            // so formatting it inline would run ~146,000 times per target on the passing path.
            if (!continued) {
                fail("U+${codePoint.toString(16).uppercase()} leads an identifier but cannot continue one")
            }
        }
        // The counts are the database's own -- `XID_Start` plus `_`, which the pattern admits by
        // hand, and `XID_Continue`, which already holds `_`. Asserting them is what keeps this test
        // from passing on an empty table: every loop body above is skipped when nothing is an
        // identifier character, and the test then asserts nothing at all. They are also the two
        // numbers a human can check against the database by hand, which the fingerprint is not.
        assertEquals(XID_START_CODE_POINTS + 1, starts)
        assertEquals(XID_CONTINUE_CODE_POINTS, continues)
        assertEquals(
            TABLE_FINGERPRINT,
            fingerprint(startFingerprint, continueFingerprint),
            "the tables no longer hold the code points they held. If the Unicode database was " +
                "deliberately replaced, this is the value to record in `TABLE_FINGERPRINT`",
        )
    }

    /** [start] and [continues] as the one string [TABLE_FINGERPRINT] records, unsigned and hex. */
    private fun fingerprint(start: Int, continues: Int): String {
        fun hex(value: Int) = (value.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')
        return "${hex(start)}:${hex(continues)}"
    }

    @Test
    fun reports_the_unicode_version_it_was_generated_from() {
        // Not a version assertion -- moving the database is a one-line change to `unicode/README.md`
        // and this file. It asserts the generated constant reached the library at all, which is
        // the one thing `unicode/README.md` cannot say for itself.
        assertTrue(Regex("""\d+\.\d+\.\d+""").matches(UNICODE_VERSION), UNICODE_VERSION)
    }

    private fun codePointString(codePoint: Int): String = if (codePoint <= 0xFFFF) {
        codePoint.toChar().toString()
    } else {
        val offset = codePoint - 0x10000
        charArrayOf(
            (0xD800 + (offset shr 10)).toChar(),
            (0xDC00 + (offset and 0x3FF)).toChar(),
        ).concatToString()
    }

    private companion object {
        /**
         * How many code points `DerivedCoreProperties-17.0.0.txt` assigns `XID_Start`.
         *
         * Counted from the vendored file. It has to be updated with the database, which is the
         * point: a table regenerated from a new revision should not slip in unnoticed.
         */
        const val XID_START_CODE_POINTS = 145_893

        /**
         * How many code points the same file assigns `XID_Continue`, counted the same way.
         *
         * Both counts are asserted, not just this one's superset relation to the other. See
         * [both_tables_hold_exactly_the_code_points_the_database_assigns] for what the missing
         * half let through.
         */
        const val XID_CONTINUE_CODE_POINTS = 149_221

        /**
         * An order-sensitive fingerprint over every code point the two tables accept.
         *
         * The counts above say how many; this says which. A range that moves without changing
         * size -- `200C..200D` becoming `200B..200C`, say -- satisfies both counts and the
         * superset relation while silently trading one character for another, and only this
         * catches it. Recorded rather than derived on purpose: a value recomputed from the same
         * tables it checks would agree with anything.
         *
         * Regenerate by replacing the database and taking the value the failure message prints.
         */
        const val TABLE_FINGERPRINT = "aa165066:3bf8e27c"
    }
}
