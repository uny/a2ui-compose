package dev.ynagai.a2ui.core.function

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 2025-08-26T09:30:00Z, a Tuesday. */
private const val REFERENCE: Long = 1_756_200_600_000L

/**
 * What the seven `actual`s must agree on, run on every target that has a test task.
 *
 * The assertions are the ones every platform's tables can be held to across the CLDR versions they
 * ship: full month and weekday names, the ASCII separators of `en-US` and `de-DE`, and the minor
 * units of two currencies. Abbreviations are left out on purpose -- CLDR has changed English ones
 * within the range of runtimes this builds for, and a test that pins them fails on a JDK upgrade
 * rather than on a defect. What is being checked is that each target reads *its own* tables into
 * the same shape, not that the tables match.
 *
 * Android is absent, and not by choice: the module has no host test task on that target. Its
 * implementation is the same file the JVM runs (`jvmSharedMain`), which is what makes that
 * tolerable rather than a hole -- and it is a real gap all the same.
 */
class PlatformLocaleDataTest {

    @Test
    fun englishReadsTheSeparatorsAndNamesEveryPlatformAgreesOn() {
        val data = platformLocaleData("en-US")
        assertEquals(".", data.symbols.decimalSeparator)
        assertEquals(",", data.symbols.groupSeparator)
        assertEquals(listOf(3), data.symbols.groupSizes)
        assertEquals("January", data.symbols.months.wide[0])
        assertEquals("September", data.symbols.months.wide[8])
        assertEquals("Sunday", data.symbols.weekdays.wide[0])
        assertEquals("Saturday", data.symbols.weekdays.wide[6])
        assertEquals("AM", data.symbols.amPm[0])
        assertEquals("PM", data.symbols.amPm[1])
    }

    @Test
    fun germanReadsTheSeparatorsTheOtherWayRound() {
        // The pair that catches a formatter which read the tables and then ignored them: German
        // swaps the roles of `.` and `,` that every assertion above depends on.
        val data = platformLocaleData("de-DE")
        assertEquals(",", data.symbols.decimalSeparator)
        assertEquals(".", data.symbols.groupSeparator)
        assertEquals("Januar", data.symbols.months.wide[0])
        assertEquals("Sonntag", data.symbols.weekdays.wide[0])
    }

    @Test
    fun japaneseReadsNamesThatAreNotPrefixesOfEachOther() {
        val data = platformLocaleData("ja-JP")
        assertEquals("1月", data.symbols.months.wide[0])
        assertEquals("日曜日", data.symbols.weekdays.wide[0])
    }

    @Test
    fun everyLocaleFillsAllSevenAndTwelveSlots() {
        listOf("en-US", "de-DE", "ja-JP", "ar-EG", "hi-IN").forEach { tag ->
            val symbols = platformLocaleData(tag).symbols
            listOf(symbols.months.wide, symbols.months.abbreviated, symbols.months.narrow)
                .forEach { assertEquals(12, it.size, "$tag months") }
            listOf(symbols.weekdays.wide, symbols.weekdays.abbreviated, symbols.weekdays.narrow)
                .forEach { assertEquals(7, it.size, "$tag weekdays") }
            assertEquals(2, symbols.amPm.size, "$tag amPm")
            assertTrue(symbols.amPm.all { it.isNotEmpty() }, "$tag amPm must not be blank")
            assertTrue(symbols.groupSizes.all { it > 0 }, "$tag groupSizes")
        }
    }

    @Test
    fun currencyMinorUnitsComeFromTheCode() {
        val data = platformLocaleData("en-US")
        assertEquals("\$", data.currency("USD").positivePrefix)
        assertEquals(2, data.currency("USD").fractionDigits)
        // The currency with no minor unit, which is the case a hard-coded 2 gets wrong.
        assertEquals(0, data.currency("JPY").fractionDigits)
    }

    @Test
    fun anUnknownCurrencyCodeDegradesToTheCodeItself() {
        // No platform knows `ZZZ`, and the specification gives `formatCurrency` no error path, so
        // every target has to write the code out rather than raise or drop it.
        val formatted = localeFormatter("en-US").formatCurrency(1.0, "ZZZ", null, grouping = false)
        assertContains(formatted, "ZZZ")
        assertContains(formatted, "1")
    }

    // ---- the whole formatter, end to end ----------------------------------------------

    @Test
    fun englishFormatsTheWayTheCorpusExpects() {
        val formatter = localeFormatter("en-US")
        assertEquals("1,234,567.50", formatter.formatNumber(1234567.5, 2, grouping = true))
        assertEquals("\$1,234.50", formatter.formatCurrency(1234.5, "USD", null, grouping = true))
        assertEquals("-\$1,234.50", formatter.formatCurrency(-1234.5, "USD", null, grouping = true))
        // The two pattern shapes the 43 examples actually use, including a quoted literal.
        assertEquals("Tuesday, August 26", formatter.formatDate(REFERENCE, "EEEE, MMMM d"))
        assertEquals(
            "Tuesday, August 26, 2025 at 9:30 AM",
            formatter.formatDate(REFERENCE, "EEEE, MMMM d, yyyy 'at' h:mm a"),
        )
    }

    @Test
    fun germanFormatsTheSameInstantWithItsOwnSymbols() {
        val formatter = localeFormatter("de-DE")
        assertEquals("1.234.567,50", formatter.formatNumber(1234567.5, 2, grouping = true))
        assertEquals("Dienstag, 26. August 2025", formatter.formatDate(REFERENCE, "EEEE, d. MMMM yyyy"))
    }

    @Test
    fun pluralsFollowTheRequestedLocaleOnEveryTarget() {
        // The categories come from this module's own table rather than from the platform -- four
        // of the seven targets have no plural API -- so this is what stops that table from being
        // wired to the wrong locale.
        assertEquals(PluralCategory.ONE, localeFormatter("en-US").pluralCategory(1.0))
        assertEquals(PluralCategory.OTHER, localeFormatter("en-US").pluralCategory(0.0))
        assertEquals(PluralCategory.ONE, localeFormatter("fr-FR").pluralCategory(0.0))
        assertEquals(PluralCategory.MANY, localeFormatter("ru-RU").pluralCategory(5.0))
    }

    @Test
    fun theSystemFormatterAnswersWithoutKnowingWhichLocaleItGot() {
        // Deliberately shallow: the device's locale is whatever the runner is set to, so the only
        // claim this can make is that reading it works at all and produces a usable formatter.
        val formatter = systemLocaleFormatter() as SymbolLocaleFormatter
        assertTrue(formatter.languageTag.isNotEmpty())
        assertTrue(formatter.formatNumber(1.0, 0, grouping = false).isNotEmpty())
        assertTrue(formatter.formatDate(REFERENCE, "yyyy").isNotEmpty())
    }
}
