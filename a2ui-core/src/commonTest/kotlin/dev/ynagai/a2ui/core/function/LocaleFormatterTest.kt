package dev.ynagai.a2ui.core.function

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 2025-08-26T09:30:00Z, the instant the date cases below are written against. */
private const val REFERENCE: Long = 1_756_200_600_000L

private fun text(call: String, args: String): String {
    val result = context().evaluate(call("""{"call":"$call","args":$args}"""))
    return (result as JsonPrimitive).content
}

class LocaleFormatterTest {

    // ---- formatNumber -----------------------------------------------------------------

    @Test
    fun formatNumberGroupsByDefaultAndHonoursDecimals() {
        assertEquals("1,234,567.50", text("formatNumber", """{"value":1234567.5,"decimals":2}"""))
        assertEquals("1234567.5", text("formatNumber", """{"value":1234567.5,"grouping":false}"""))
        assertEquals("1,000", text("formatNumber", """{"value":1000}"""))
    }

    @Test
    fun formatNumberWithNoDecimalsKeepsTheValueItWasGiven() {
        // No locale means no basis for choosing a precision, so nothing is rounded away.
        assertEquals("0.125", text("formatNumber", """{"value":0.125}"""))
        assertEquals("7", text("formatNumber", """{"value":7.0}"""))
    }

    @Test
    fun formatNumberRoundsHalfAwayFromZero() {
        // Halves that a Double represents exactly, so what is under test is the rounding rule
        // rather than the binary value.
        assertEquals("0.13", text("formatNumber", """{"value":0.125,"decimals":2}"""))
        assertEquals("-0.13", text("formatNumber", """{"value":-0.125,"decimals":2}"""))
        assertEquals("3", text("formatNumber", """{"value":2.5,"decimals":0}"""))
        assertEquals("-3", text("formatNumber", """{"value":-2.5,"decimals":0}"""))
        // And a decimal that is not a half at all once it is a Double: 1.005 is really
        // 1.00499…, so it rounds down. Every IEEE-754 formatter does this, including `Intl`.
        assertEquals("1.00", text("formatNumber", """{"value":1.005,"decimals":2}"""))
    }

    @Test
    fun formatNumberPadsToTheRequestedPrecision() {
        assertEquals("1.000", text("formatNumber", """{"value":1,"decimals":3}"""))
        assertEquals("0.00", text("formatNumber", """{"value":0,"decimals":2}"""))
    }

    @Test
    fun formatNumberWritesOutMagnitudesThatDoubleToStringWouldExponentiate() {
        // `Double.toString` reaches for exponential notation at 1e7 on the JVM and Native and at
        // 1e21 in JavaScript. Neither threshold may show through, or the same payload renders
        // differently depending on which target the renderer was built for.
        assertEquals("10,000,000", text("formatNumber", """{"value":1e7}"""))
        assertEquals("9,999,999", text("formatNumber", """{"value":9999999}"""))
        assertEquals(
            "1,000,000,000,000,000,000,000",
            text("formatNumber", """{"value":1e21}"""),
        )
        assertEquals("0.0000001", text("formatNumber", """{"value":1e-7}"""))
        assertEquals("0.0000123", text("formatNumber", """{"value":1.23e-5}"""))
        assertEquals("123,450,000", text("formatNumber", """{"value":1.2345e8}"""))
    }

    @Test
    fun formatNumberGroupsEveryDigitCount() {
        assertEquals("100", text("formatNumber", """{"value":100}"""))
        assertEquals("1,000", text("formatNumber", """{"value":1000}"""))
        assertEquals("10,000", text("formatNumber", """{"value":10000}"""))
        assertEquals("-10,000", text("formatNumber", """{"value":-10000}"""))
    }

    // ---- formatCurrency ---------------------------------------------------------------

    @Test
    fun formatCurrencyDefaultsToTwoDecimalsAndNamesTheCode() {
        assertEquals("USD 1,234.50", text("formatCurrency", """{"value":1234.5,"currency":"USD"}"""))
        assertEquals(
            "EUR 1234.5",
            text("formatCurrency", """{"value":1234.5,"currency":"EUR","decimals":1,"grouping":false}"""),
        )
    }

    @Test
    fun formatCurrencyRequiresItsCode() {
        assertFailsWith<A2uiFunctionException> { text("formatCurrency", """{"value":1}""") }
    }

    // ---- pluralize --------------------------------------------------------------------

    @Test
    fun pluralizeSelectsByCategoryAndFallsBackToOther() {
        val args = """{"value":1,"one":"1 item","other":"%d items"}"""
        assertEquals("1 item", text("pluralize", args))
        assertEquals("%d items", text("pluralize", """{"value":5,"one":"1 item","other":"%d items"}"""))
        // No `one` supplied: the fallback is what the guide specifies.
        assertEquals("%d items", text("pluralize", """{"value":1,"other":"%d items"}"""))
    }

    @Test
    fun pluralizeFailsWhenNeitherTheCategoryNorTheFallbackIsSupplied() {
        // The schema makes `other` required; the evaluator only has to notice when the value it
        // needs is missing, which for a count of 5 is `other` itself.
        assertFailsWith<A2uiFunctionException> { text("pluralize", """{"value":5,"one":"x"}""") }
        // With a count of 1 the same call resolves, because `one` is the category that applies.
        assertEquals("x", text("pluralize", """{"value":1,"one":"x"}"""))
    }

    // ---- formatDate -------------------------------------------------------------------

    @Test
    fun formatDateRendersTheDocumentedTokens() {
        fun at(pattern: String) = text("formatDate", """{"value":$REFERENCE,"format":"$pattern"}""")
        assertEquals("2025", at("yyyy"))
        assertEquals("25", at("yy"))
        assertEquals("8", at("M"))
        assertEquals("08", at("MM"))
        assertEquals("Aug", at("MMM"))
        assertEquals("August", at("MMMM"))
        assertEquals("26", at("dd"))
        assertEquals("Tue", at("E"))
        assertEquals("Tuesday", at("EEEE"))
        assertEquals("09", at("HH"))
        assertEquals("30", at("mm"))
        assertEquals("00", at("ss"))
        assertEquals("9", at("h"))
        assertEquals("AM", at("a"))
    }

    @Test
    fun formatDateRendersTheCatalogsOwnExamples() {
        fun at(pattern: String) = text("formatDate", """{"value":$REFERENCE,"format":"$pattern"}""")
        assertEquals("Aug 26, 2025", at("MMM dd, yyyy"))
        assertEquals("09:30", at("HH:mm"))
        assertEquals("9:30 AM", at("h:mm a"))
        assertEquals("Tuesday, 26 August", at("EEEE, d MMMM"))
    }

    @Test
    fun formatDateUses12ForMidnightAndNoonInThe12HourClock() {
        val midnight = 1_756_166_400_000L // 2025-08-26T00:00:00Z
        val noon = midnight + 12 * 60 * 60 * 1000L
        assertEquals("12:00 AM", text("formatDate", """{"value":$midnight,"format":"h:mm a"}"""))
        assertEquals("12:00 PM", text("formatDate", """{"value":$noon,"format":"h:mm a"}"""))
    }

    @Test
    fun formatDateReadsTheFractionOfASecondFromItsLeadingDigits() {
        // 2025-08-26T09:30:00.005Z — `S` is the first digit of `.005`, which is a zero.
        val wire = """{"value":${REFERENCE + 5},"format":"ss.S|ss.SS|ss.SSS|ss.SSSS"}"""
        assertEquals("00.0|00.00|00.005|00.0050", text("formatDate", wire))
    }

    @Test
    fun formatDateTreatsQuotedRunsAsLiterals() {
        val wire = """{"value":$REFERENCE,"format":"yyyy'T'MM''dd"}"""
        assertEquals("2025T08'26", text("formatDate", wire))
    }

    @Test
    fun formatDateRefusesAPatternLetterItDoesNotImplement() {
        val failure = assertFailsWith<A2uiFunctionException> {
            text("formatDate", """{"value":$REFERENCE,"format":"yyyy G"}""")
        }
        assertTrue(failure.message!!.contains("TR35"))
    }

    @Test
    fun formatDateReadsAnIsoStringAsWellAsAnInstant() {
        assertEquals(
            "2025-08-26 09:30",
            text("formatDate", """{"value":"2025-08-26T09:30:00Z","format":"yyyy-MM-dd HH:mm"}"""),
        )
        assertEquals(
            "2025-08-26 00:00",
            text("formatDate", """{"value":"2025-08-26","format":"yyyy-MM-dd HH:mm"}"""),
        )
    }

    @Test
    fun formatDateAppliesAnOffsetRatherThanIgnoringIt() {
        assertEquals(
            "09:30",
            text("formatDate", """{"value":"2025-08-26T18:30:00+09:00","format":"HH:mm"}"""),
        )
        assertEquals(
            "12:00",
            text("formatDate", """{"value":"2025-08-26T07:00:00-0500","format":"HH:mm"}"""),
        )
    }

    @Test
    fun formatDateRefusesAValueThatIsNeitherFormat() {
        assertFailsWith<A2uiFunctionException> {
            text("formatDate", """{"value":"last Tuesday","format":"yyyy"}""")
        }
    }

    @Test
    fun formatDateHandlesInstantsBeforeTheEpoch() {
        // Truncating division would put this on 1970-01-01 rather than the day before.
        assertEquals(
            "1969-12-31 23:00:00",
            text("formatDate", """{"value":-3600000,"format":"yyyy-MM-dd HH:mm:ss"}"""),
        )
    }

    @Test
    fun formatDateHandlesALeapDay() {
        // 2024-02-29T00:00:00Z
        assertEquals(
            "Thursday 29 February 2024",
            text("formatDate", """{"value":1709164800000,"format":"EEEE d MMMM yyyy"}"""),
        )
    }
}

class Iso8601Test {

    @Test
    fun aDateWithNoTimeIsMidnightUtc() {
        assertEquals(1_756_166_400_000L, parseIso8601("2025-08-26"))
    }

    @Test
    fun fractionalSecondsAreTruncatedToMilliseconds() {
        assertEquals(
            1_756_200_600_123L,
            parseIso8601("2025-08-26T09:30:00.1239Z"),
        )
    }

    @Test
    fun aTimeWithNoOffsetIsReadAsUtc() {
        assertEquals(parseIso8601("2025-08-26T09:30:00Z"), parseIso8601("2025-08-26T09:30:00"))
    }

    @Test
    fun malformedInputIsRejectedRatherThanGuessed() {
        assertNull(parseIso8601("2025-13-01"))
        assertNull(parseIso8601("2025/08/26"))
        assertNull(parseIso8601("2025-08-26T25:00:00Z"))
        assertNull(parseIso8601("2025-08-26T09:30:00+99:00"))
        assertNull(parseIso8601(""))
        // Truncated where the seconds field begins: too short to hold it, but with the separator
        // in place, so a guard that checked only the separator would read past the end.
        assertNull(parseIso8601("2025-08-26T09:30:"))
        assertNull(parseIso8601("2025-08-26T09:30:0"))
    }

    @Test
    fun theDecompositionInvertsTheComposition() {
        for (millis in listOf(0L, REFERENCE, -1L, -62_135_596_800_000L, 4_102_444_800_000L)) {
            val at = CivilDateTime.ofEpochMillis(millis)
            assertEquals(
                millis,
                CivilDateTime.toEpochMillis(
                    at.year,
                    at.month,
                    at.day,
                    at.hour,
                    at.minute,
                    at.second,
                    at.milli,
                ),
            )
        }
    }
}
