package dev.ynagai.a2ui.core.function

import kotlin.test.Test
import kotlin.test.assertEquals

/** 2025-08-26T09:30:00Z — a Tuesday, which is what the weekday fields below are read against. */
private const val REFERENCE: Long = 1_756_200_600_000L

/**
 * Locale data written out by hand, so that what is under test is the assembly and not a platform.
 *
 * The symbols are deliberately not any one real locale: a German decimal comma sits next to Indian
 * group sizes and invented month names. A test built on `de-DE`'s real data would pass just as well
 * against a formatter that ignored [LocaleData] and hard-coded German.
 */
private fun fakeData(
    languageTag: String = "xx",
    decimalSeparator: String = ",",
    groupSeparator: String = ".",
    minusSign: String = "−",
    groupSizes: List<Int> = listOf(3),
    currency: CurrencyAffixes = CurrencyAffixes("", " ¤", "−", " ¤", 2),
): LocaleData = object : LocaleData {
    override val symbols = LocaleSymbols(
        languageTag = languageTag,
        decimalSeparator = decimalSeparator,
        groupSeparator = groupSeparator,
        minusSign = minusSign,
        groupSizes = groupSizes,
        months = DateNames(
            wide = (1..12).map { "month$it" },
            abbreviated = (1..12).map { "m$it" },
            narrow = (1..12).map { "$it" },
        ),
        weekdays = DateNames(
            wide = (0..6).map { "weekday$it" },
            abbreviated = (0..6).map { "w$it" },
            narrow = (0..6).map { "$it" },
        ),
        amPm = listOf("morning", "evening"),
    )

    override fun currency(code: String): CurrencyAffixes = currency
}

class SymbolLocaleFormatterTest {

    // ---- numbers ----------------------------------------------------------------------

    @Test
    fun theSeparatorsComeFromTheData() {
        val formatter = SymbolLocaleFormatter(fakeData())
        assertEquals("1.234.567,5", formatter.formatNumber(1234567.5, decimals = null, grouping = true))
        assertEquals("1234567,5", formatter.formatNumber(1234567.5, decimals = null, grouping = false))
    }

    @Test
    fun theMinusSignComesFromTheDataToo() {
        // Not every locale writes ASCII `-`; `sv-SE` uses U+2212. A formatter that hard-codes the
        // hyphen is wrong in a way no separator test would catch.
        val formatter = SymbolLocaleFormatter(fakeData())
        assertEquals("−1.000,25", formatter.formatNumber(-1000.25, decimals = 2, grouping = true))
    }

    @Test
    fun groupSizesAreCountedFromTheDecimalSeparatorOutwards() {
        // The Indian grouping: three digits closest to the separator, then two, repeating.
        val formatter = SymbolLocaleFormatter(
            fakeData(groupSeparator = ",", decimalSeparator = ".", groupSizes = listOf(3, 2)),
        )
        assertEquals("12,34,567", formatter.formatNumber(1234567.0, decimals = 0, grouping = true))
        assertEquals("1,23,45,678", formatter.formatNumber(12345678.0, decimals = 0, grouping = true))
        // Short enough that no separator is reached at all.
        assertEquals("567", formatter.formatNumber(567.0, decimals = 0, grouping = true))
    }

    @Test
    fun theRoundingRulesAreTheOnesTheFallbackAlreadyFixed() {
        // Half-expand, and no invented precision when `decimals` is absent -- the same behaviour
        // `LocaleFormatterTest` pins for `FallbackLocaleFormatter`, since it is the same code.
        val formatter = SymbolLocaleFormatter(fakeData())
        assertEquals("0,13", formatter.formatNumber(0.125, decimals = 2, grouping = true))
        assertEquals("0,125", formatter.formatNumber(0.125, decimals = null, grouping = true))
        assertEquals("7", formatter.formatNumber(7.0, decimals = null, grouping = true))
    }

    // ---- currency ---------------------------------------------------------------------

    @Test
    fun theAffixesCarryTheSignAndTheSymbolsPosition() {
        val formatter = SymbolLocaleFormatter(fakeData())
        assertEquals("1.234,50 ¤", formatter.formatCurrency(1234.5, "USD", null, grouping = true))
        // The negative form is the negative affixes, not a minus glued onto the positive one:
        // this locale puts its sign before the digits and its symbol after them.
        assertEquals("−1.234,50 ¤", formatter.formatCurrency(-1234.5, "USD", null, grouping = true))
    }

    @Test
    fun theCurrencysOwnPrecisionIsUsedWhenNoneIsAsked() {
        val yen = fakeData(currency = CurrencyAffixes("¥", "", "-¥", "", 0))
        val formatter = SymbolLocaleFormatter(yen)
        assertEquals("¥1.235", formatter.formatCurrency(1234.5, "JPY", null, grouping = true))
        // An explicit `decimals` still wins over the currency's minor units.
        assertEquals("¥1.234,50", formatter.formatCurrency(1234.5, "JPY", 2, grouping = true))
    }

    @Test
    fun negativeZeroTakesTheNegativeAffixes() {
        // `-0.0` compares equal to `0.0`, so the sign has to be read off the reciprocal. Without
        // that this fell through to the positive affixes and lost a sign the caller wrote.
        val formatter = SymbolLocaleFormatter(fakeData())
        assertEquals("−0,00 ¤", formatter.formatCurrency(-0.0, "USD", null, grouping = true))
    }

    // ---- dates ------------------------------------------------------------------------

    @Test
    fun theNamesComeFromTheDataAtEveryWidth() {
        val formatter = SymbolLocaleFormatter(fakeData())
        assertEquals("month8", formatter.formatDate(REFERENCE, "MMMM"))
        assertEquals("m8", formatter.formatDate(REFERENCE, "MMM"))
        assertEquals("8", formatter.formatDate(REFERENCE, "MMMMM"))
        assertEquals("08", formatter.formatDate(REFERENCE, "MM"))
        // 2025-08-26 was a Tuesday, index 2 counting from Sunday.
        assertEquals("weekday2", formatter.formatDate(REFERENCE, "EEEE"))
        assertEquals("w2", formatter.formatDate(REFERENCE, "E"))
        assertEquals("2", formatter.formatDate(REFERENCE, "EEEEE"))
    }

    @Test
    fun theShortWidthsAreDataRatherThanTruncations() {
        // The point of carrying three lists: outside English the abbreviation is not a prefix of
        // the wide form. Here `m8` is not a prefix of `month8`'s first three characters (`mon`),
        // so a formatter that truncated would fail this.
        val formatter = SymbolLocaleFormatter(fakeData())
        assertEquals("m8", formatter.formatDate(REFERENCE, "MMM"))
        assertEquals("w2", formatter.formatDate(REFERENCE, "EEE"))
    }

    @Test
    fun theDayPeriodComesFromTheData() {
        val formatter = SymbolLocaleFormatter(fakeData())
        assertEquals("morning", formatter.formatDate(REFERENCE, "a"))
        // 21:30Z on the same day.
        assertEquals("evening", formatter.formatDate(REFERENCE + 12 * 3_600_000L, "a"))
    }

    @Test
    fun theRestOfTheTr35InterpretationIsUnchanged() {
        // Quoted literals, the two-digit year and the 12-hour clock are locale-independent, and
        // this is the one date engine the module has -- so they have to behave here exactly as
        // `LocaleFormatterTest` pins them for the fallback.
        val formatter = SymbolLocaleFormatter(fakeData())
        assertEquals("weekday2, month8 26 at 9:30 morning", formatter.formatDate(REFERENCE, "EEEE, MMMM d 'at' h:mm a"))
        assertEquals("25", formatter.formatDate(REFERENCE, "yy"))
        assertEquals("2025", formatter.formatDate(REFERENCE, "yyyy"))
    }

    // ---- plurals ----------------------------------------------------------------------

    @Test
    fun thePluralCategoryFollowsTheResolvedLanguageTag() {
        assertEquals(PluralCategory.ONE, SymbolLocaleFormatter(fakeData("fr-FR")).pluralCategory(0.0))
        assertEquals(PluralCategory.OTHER, SymbolLocaleFormatter(fakeData("en-US")).pluralCategory(0.0))
        // The synthetic tag is not a language, and it degrades rather than raising.
        assertEquals(PluralCategory.OTHER, SymbolLocaleFormatter(fakeData()).pluralCategory(1.0))
    }

    @Test
    fun theResolvedTagIsReportedRatherThanTheOneAskedFor() {
        assertEquals("fr-FR", SymbolLocaleFormatter(fakeData("fr-FR")).languageTag)
    }
}
