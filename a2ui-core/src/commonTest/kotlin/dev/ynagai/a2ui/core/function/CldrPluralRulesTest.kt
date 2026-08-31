package dev.ynagai.a2ui.core.function

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CldrPluralRulesTest {

    private fun category(tag: String, value: Double) = CldrPluralRules.categoryFor(tag, value)

    @Test
    fun englishSeparatesOneFromEverythingElse() {
        assertEquals(PluralCategory.ONE, category("en", 1.0))
        assertEquals(PluralCategory.OTHER, category("en", 0.0))
        assertEquals(PluralCategory.OTHER, category("en", 2.0))
        // CLDR's English `one` is `i = 1 and v = 0`, and `1.0` has no visible fraction digits,
        // so it is the same category as `1` rather than the `other` a written `1.0` would take.
        assertEquals(PluralCategory.ONE, category("en", 1.0))
        assertEquals(PluralCategory.OTHER, category("en", 1.5))
    }

    @Test
    fun englishTakesTheMagnitude() {
        // CLDR's operand `n` is "the absolute value of the source number", so `-1 item` reads as
        // a singular. This is the rule `FallbackLocaleFormatter` already documents.
        assertEquals(PluralCategory.ONE, category("en", -1.0))
    }

    @Test
    fun frenchPutsZeroInTheSingular() {
        assertEquals(PluralCategory.ONE, category("fr", 0.0))
        assertEquals(PluralCategory.ONE, category("fr", 1.0))
        assertEquals(PluralCategory.OTHER, category("fr", 2.0))
        // Portuguese shares the rule, and the region subtag does not change it.
        assertEquals(PluralCategory.ONE, category("pt-BR", 0.0))
    }

    @Test
    fun russianDrawsTheThreeIntegerBands() {
        assertEquals(PluralCategory.ONE, category("ru", 1.0))
        assertEquals(PluralCategory.ONE, category("ru", 21.0))
        assertEquals(PluralCategory.FEW, category("ru", 2.0))
        assertEquals(PluralCategory.FEW, category("ru", 23.0))
        assertEquals(PluralCategory.MANY, category("ru", 5.0))
        assertEquals(PluralCategory.MANY, category("ru", 11.0))
        // The teens are the exception both bands carve out: 11 and 12 are `many`, not `one`/`few`.
        assertEquals(PluralCategory.MANY, category("ru", 12.0))
        assertEquals(PluralCategory.MANY, category("ru", 111.0))
        // Fractions leave the integer bands entirely.
        assertEquals(PluralCategory.OTHER, category("ru", 1.5))
    }

    @Test
    fun polishSplitsOneOffTheBandRussianLeavesItIn() {
        assertEquals(PluralCategory.ONE, category("pl", 1.0))
        assertEquals(PluralCategory.FEW, category("pl", 2.0))
        // 21 is `many` in Polish where Russian calls it `one` -- the difference the two families
        // exist to keep apart.
        assertEquals(PluralCategory.MANY, category("pl", 21.0))
        assertEquals(PluralCategory.MANY, category("pl", 5.0))
    }

    @Test
    fun czechCallsEveryFractionMany() {
        assertEquals(PluralCategory.ONE, category("cs", 1.0))
        assertEquals(PluralCategory.FEW, category("cs", 3.0))
        assertEquals(PluralCategory.MANY, category("cs", 1.5))
        assertEquals(PluralCategory.OTHER, category("cs", 5.0))
    }

    @Test
    fun arabicUsesAllSixCategories() {
        assertEquals(PluralCategory.ZERO, category("ar", 0.0))
        assertEquals(PluralCategory.ONE, category("ar", 1.0))
        assertEquals(PluralCategory.TWO, category("ar", 2.0))
        assertEquals(PluralCategory.FEW, category("ar", 3.0))
        assertEquals(PluralCategory.FEW, category("ar", 103.0))
        assertEquals(PluralCategory.MANY, category("ar", 11.0))
        assertEquals(PluralCategory.OTHER, category("ar", 100.0))
    }

    @Test
    fun romanianPutsZeroAndTheTeensBandInFew() {
        assertEquals(PluralCategory.ONE, category("ro", 1.0))
        assertEquals(PluralCategory.FEW, category("ro", 0.0))
        assertEquals(PluralCategory.FEW, category("ro", 19.0))
        assertEquals(PluralCategory.OTHER, category("ro", 20.0))
    }

    @Test
    fun languagesWithOnePluralFormAlwaysAnswerOther() {
        listOf(0.0, 1.0, 2.0, 100.0).forEach {
            assertEquals(PluralCategory.OTHER, category("ja", it))
            assertEquals(PluralCategory.OTHER, category("zh-Hans-CN", it))
        }
    }

    @Test
    fun anUnknownLanguageAnswersOtherRatherThanGuessing() {
        // `other` is the one argument `pluralize` requires, so an unlisted language degrades to a
        // form the author has certainly supplied. `qqq` is not a language; `cy` is one this set
        // does not cover, and it has to answer the same way.
        assertEquals(PluralCategory.OTHER, category("qqq", 1.0))
        assertEquals(PluralCategory.OTHER, category("cy", 1.0))
        assertTrue("cy" !in CldrPluralRules.languageTags)
    }

    @Test
    fun theTagIsReadDownToItsLanguageSubtag() {
        assertEquals(PluralCategory.ONE, category("EN_US", 1.0))
        assertEquals(PluralCategory.ONE, category("en-Latn-GB", 1.0))
    }

    @Test
    fun nonFiniteValuesAnswerOther() {
        assertEquals(PluralCategory.OTHER, category("en", Double.NaN))
        assertEquals(PluralCategory.OTHER, category("ru", Double.POSITIVE_INFINITY))
    }

    @Test
    fun aHugeMagnitudeDoesNotOverflowTheModuloBands() {
        // The integer operand is bounded before the `%` runs; without that this raised or wrapped
        // into a band it does not belong to.
        assertEquals(PluralCategory.OTHER, category("en", 1.0e300))
        assertEquals(PluralCategory.MANY, category("ru", 1.0e300))
    }
}
