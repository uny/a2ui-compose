package dev.ynagai.a2ui.core.function

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [CldrPluralRules] against ICU, for every language it claims and every shape of value.
 *
 * The expectations are not hand-written. They were produced by asking `Intl.PluralRules` — ICU
 * 78.2, CLDR/Unicode 17 — for each language and probe, and are checked in as a fixture so that all
 * seven targets verify the same table without any of them needing an ICU at run time. Four of them
 * have no plural API to ask.
 *
 * **This is what the rules were built against, and it found five languages placed in the wrong
 * family** — Danish, Icelandic, Hebrew, Kannada and Armenian — plus two operands the first version
 * did not have: CLDR's `t`, which Icelandic reads to put `0.1` in the singular and `0.5` in the
 * plural, and the Western Romance `many` for exact millions. Reading the categories off a rule
 * table by eye had missed all of them.
 *
 * **A failure here is not necessarily a defect.** CLDR revises cardinal rules between releases —
 * Icelandic's own fraction handling reads differently in older charts than in the one above. A
 * mismatch means this fixture and [CldrPluralRules] disagree with the CLDR the fixture was cut
 * from; regenerating it is a decision about which CLDR to follow, not a repair.
 */
class CldrPluralRulesFixtureTest {

    @Test
    fun every_covered_language_matches_icu_on_every_probe() {
        var checked = 0
        EXPECTED.forEach { (tag, encoded) ->
            assertEquals(PROBES.size, encoded.length, "$tag: the fixture row is the wrong length")
            PROBES.forEachIndexed { index, value ->
                assertEquals(
                    DECODE.getValue(encoded[index]),
                    CldrPluralRules.categoryFor(tag, value),
                    "$tag at $value",
                )
                checked++
            }
        }
        assertEquals(EXPECTED.size * PROBES.size, checked)
    }

    @Test
    fun the_fixture_covers_every_language_the_rules_claim() {
        // Otherwise a language could be added to `FAMILIES` and never compared against ICU at all.
        assertEquals(emptySet(), CldrPluralRules.languageTags - EXPECTED.map { it.first }.toSet())
    }
}

/** The values each row below is indexed by. */
private val PROBES: List<Double> = listOf(0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0, 12.0, 13.0, 14.0, 15.0, 19.0, 20.0, 21.0, 22.0, 25.0, 31.0, 99.0, 100.0, 101.0, 102.0, 103.0, 111.0, 112.0, 1000.0, 1001.0, 1000000.0, 1.5, 0.5, 2.5, 0.25, 10.5, 3.5, 21.5, 100.5, 0.001, 0.0001, 0.1, 1.001)

private val DECODE: Map<Char, PluralCategory> = mapOf(
    'z' to PluralCategory.ZERO,
    'o' to PluralCategory.ONE,
    't' to PluralCategory.TWO,
    'f' to PluralCategory.FEW,
    'm' to PluralCategory.MANY,
    'x' to PluralCategory.OTHER,
)

/** One row per language: a category code per entry of [PROBES], in order. */
private val EXPECTED: List<Pair<String, String>> = listOf(
    "ja" to "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "zh" to "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "ko" to "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "th" to "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "vi" to "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "id" to "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "ms" to "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "my" to "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "km" to "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "lo" to "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "bo" to "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "jv" to "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "yo" to "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "ig" to "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "en" to "xoxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "de" to "xoxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "nl" to "xoxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "sv" to "xoxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "no" to "xoxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "nb" to "xoxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "nn" to "xoxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "fi" to "xoxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "et" to "xoxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "el" to "xoxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "bg" to "xoxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "af" to "xoxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "sw" to "xoxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "eu" to "xoxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "gl" to "xoxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "fo" to "xoxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "lb" to "xoxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "ml" to "xoxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "mr" to "xoxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "ta" to "xoxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "te" to "xoxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "ur" to "xoxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "tr" to "xoxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "az" to "xoxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "kk" to "xoxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "ky" to "xoxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "uz" to "xoxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "hu" to "xoxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "ka" to "xoxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "ne" to "xoxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "hy" to "ooxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxooxoxxxxoooo",
    "it" to "xoxxxxxxxxxxxxxxxxxxxxxxxxxxxxxmxxxxxxxxxxxx",
    "ca" to "xoxxxxxxxxxxxxxxxxxxxxxxxxxxxxxmxxxxxxxxxxxx",
    "es" to "xoxxxxxxxxxxxxxxxxxxxxxxxxxxxxxmxxxxxxxxxxxx",
    "fr" to "ooxxxxxxxxxxxxxxxxxxxxxxxxxxxxxmooxoxxxxoooo",
    "pt" to "ooxxxxxxxxxxxxxxxxxxxxxxxxxxxxxmooxoxxxxoooo",
    "hi" to "ooxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxoxoxxxxooox",
    "bn" to "ooxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxoxoxxxxooox",
    "gu" to "ooxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxoxoxxxxooox",
    "fa" to "ooxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxoxoxxxxooox",
    "zu" to "ooxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxoxoxxxxooox",
    "kn" to "ooxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxoxoxxxxooox",
    "da" to "xoxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxooxoxxxxoxoo",
    "is" to "xoxxxxxxxxxxxxxxxxoxxoxxoxxxxxoxxxxxxxxxoxoo",
    "he" to "xotxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxoxoxxxxoxox",
    "ru" to "mofffmmmmmmmmmmmmmofmommoffmmmomxxxxxxxxxmxx",
    "uk" to "mofffmmmmmmmmmmmmmofmommoffmmmomxxxxxxxxxmxx",
    "be" to "mofffmmmmmmmmmmmmmofmommoffmmmomxxxxxxxxxmxx",
    "pl" to "mofffmmmmmmmmmmmmmmfmmmmmffmmmmmxxxxxxxxxmxx",
    "cs" to "xofffxxxxxxxxxxxxxxxxxxxxxxxxxxxmmmmmmmmmxmm",
    "sk" to "xofffxxxxxxxxxxxxxxxxxxxxxxxxxxxmmmmmmmmmxmm",
    "ar" to "zotffffffffmmmmmmmmmmmmxxxfmmxxxxxxxxxxxxzxx",
    "ro" to "fofffffffffffffffxxxxxxxfffffxfxffffffffffff",
)
