package dev.ynagai.a2ui.core.function

import kotlin.math.abs
import kotlin.math.floor

/**
 * CLDR's cardinal plural rules for a bounded set of languages, evaluated in common code.
 *
 * This is the one part of [SymbolLocaleFormatter] that does not come from the platform, and not by
 * preference: **four of the seven targets have no plural-rules API at all.** Android has
 * `android.icu.text.PluralRules` and the web has `Intl.PluralRules`; the JVM ships neither (ICU4J
 * is not on this module's classpath) and Apple's Foundation exposes plural selection only through
 * `.stringsdict` resources, which is a build-time facility rather than a callable one. Delegating
 * where it exists would have made `pluralize` answer differently on iOS than on Android from the
 * same payload, so the rules live here and every target reads the same ones.
 *
 * **The set is a subset, and an unknown language answers [PluralCategory.OTHER].** That is the
 * category `pluralize` requires an author to supply, so an unlisted language degrades to the form
 * that is always present rather than to a missing argument. [languageTags] says which are covered.
 *
 * The operands are CLDR's — see [Operands] for how a bare `Double` is read into them.
 *
 * **The table is checked against ICU rather than transcribed.** `CldrPluralRulesFixtureTest` holds
 * a category for every language here at forty-four probe values, cut from `Intl.PluralRules`. That
 * comparison is what found five languages sitting in the wrong family and two operands this did
 * not have; nothing in the rules below should be changed without re-running it.
 */
public object CldrPluralRules {
    /** The language subtags this object has rules for. Everything else is [PluralCategory.OTHER]. */
    public val languageTags: Set<String> get() = FAMILIES.keys

    /**
     * Which category [value] falls into for [languageTag].
     *
     * Only the language subtag is read: CLDR keys its cardinal rules by language, and the handful
     * of regional splits it draws (`pt` against `pt-PT`) are finer than what this covers.
     */
    public fun categoryFor(languageTag: String, value: Double): PluralCategory {
        if (!value.isFinite()) return PluralCategory.OTHER
        val family = FAMILIES[languageSubtag(languageTag)] ?: return PluralCategory.OTHER
        return family(Operands(value))
    }

    /** The lowercased language subtag of [languageTag] — `pt` from `pt-BR`, `en` from `en_US`. */
    private fun languageSubtag(languageTag: String): String =
        languageTag.substringBefore('-').substringBefore('_').lowercase()
}

/**
 * CLDR's plural operands, as far as a bare `Double` determines them.
 *
 * The value is rounded to [MAX_FRACTION_DIGITS] first, and that is not an approximation but the
 * rule. CLDR derives its operands from the number *as written*, and a `Double` has not been written
 * yet; ECMA-402 resolves the same ambiguity by taking the number as its own formatter would render
 * it, whose default maximum is three fraction digits. Following that is what makes `1e-7` here
 * agree with `Intl.PluralRules` — it renders as `0`, so it selects like zero rather than like a
 * seven-digit fraction. Reading the raw magnitude instead disagreed with ICU on seven of the
 * languages below.
 */
private class Operands(value: Double) {
    /** The absolute value of the source number, as its default rendering would write it. */
    val n: Double = roundToFractionDigits(abs(value), MAX_FRACTION_DIGITS)

    /** The integer digits, as a value. Bounded so a huge magnitude cannot overflow the modulo. */
    val i: Long = if (n >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE else n.toLong()

    /** Fraction digits in that rendering: `0` for `1.0`, `1` for `1.5`, `0` for `1e-7`. */
    val v: Int = fractionDigits(n)

    /**
     * Those digits as a number, with trailing zeros dropped — CLDR's `t`.
     *
     * `1` for `0.1` and also for `0.001`, `5` for `0.5`, `25` for `0.25`. Icelandic is the rule
     * here that needs it: it puts `0.1` in the singular and `0.5` in the plural, a distinction
     * neither [v] nor [n] draws.
     */
    val t: Long = trailingTrimmedFraction(n)

    fun iMod(divisor: Int): Long = i % divisor

    fun tMod(divisor: Int): Long = t % divisor

    /** `n % divisor`, defined only where [n] is integral — every rule below guards on that. */
    fun nMod(divisor: Int): Long = i % divisor
}

/** At most [digits] fraction digits, leaving magnitudes too large to scale exactly alone. */
private fun roundToFractionDigits(magnitude: Double, digits: Int): Double {
    if (!magnitude.isFinite() || magnitude >= EXACT_INTEGER_LIMIT) return magnitude
    var scale = 1.0
    repeat(digits) { scale *= 10.0 }
    return floor(magnitude * scale + 0.5) / scale
}

/**
 * [magnitude] written out in full, which is the form `v` and `t` are counts over.
 *
 * `Double.toString` is not that form: the JVM and Native switch to an exponent from 1e7 upwards
 * and JavaScript not until 1e21, so reading the operands off it made `12345678.5` carry one
 * fraction digit on the web and none on every other target -- and `pluralCategory("cs", …)` then
 * answered `MANY` on one and `OTHER` on the other from the same payload. [shortestDigits] is the
 * expansion [formatDecimal] already applies for exactly that reason; sharing it is what keeps the
 * two readings of one number from drifting apart.
 */
private fun plainDigits(magnitude: Double): String = shortestDigits(magnitude)

private fun fractionDigits(magnitude: Double): Int {
    if (!magnitude.isFinite()) return 0
    val text = plainDigits(magnitude)
    // Only reachable if the expansion could not parse its own exponent, which leaves the value far
    // beyond anything a count reaches; there is no fraction to report either way.
    if (text.any { it == 'e' || it == 'E' }) return 0
    return plainFractionDigits(text)
}

/** The fraction digits of [magnitude] as a number, trailing zeros dropped. @see Operands.t */
private fun trailingTrimmedFraction(magnitude: Double): Long {
    if (!magnitude.isFinite()) return 0L
    val text = plainDigits(magnitude)
    if (text.any { it == 'e' || it == 'E' }) return 0L
    val point = text.indexOf('.')
    if (point < 0) return 0L
    val fraction = text.substring(point + 1).trimEnd('0')
    return if (fraction.isEmpty()) 0L else fraction.toLongOrNull() ?: 0L
}

/** Fraction digits of a form with no exponent, where a lone `0` after the point counts as none. */
private fun plainFractionDigits(text: String): Int {
    val point = text.indexOf('.')
    if (point < 0) return 0
    val fraction = text.substring(point + 1)
    return if (fraction == "0") 0 else fraction.length
}

/** ECMA-402's default `maximumFractionDigits`, which is what fixes this reading of the operands. */
private const val MAX_FRACTION_DIGITS: Int = 3

/** The largest magnitude a scaled, rounded [Double] still represents every integer below. */
private const val EXACT_INTEGER_LIMIT: Double = 9.0e15

private typealias Rule = (Operands) -> PluralCategory

private val ONLY_OTHER: Rule = { PluralCategory.OTHER }

/** `one: n = 1` — the rule for languages that count the value rather than its written form. */
private val ONE_WHEN_N_IS_1: Rule = { if (it.n == 1.0) PluralCategory.ONE else PluralCategory.OTHER }

/** `one: i = 1 and v = 0` — English and its neighbours, where `1.0` is plural but `1` is not. */
private val ONE_WHEN_I_IS_1: Rule = {
    if (it.i == 1L && it.v == 0) PluralCategory.ONE else PluralCategory.OTHER
}

/** `one: i = 0..1` — French and Brazilian Portuguese, where zero takes the singular. */
private val ONE_WHEN_I_IS_0_OR_1: Rule = {
    if (it.i in 0L..1L) PluralCategory.ONE else PluralCategory.OTHER
}

/** `one: i = 0 or n = 1` — Hindi and its relatives. */
private val ONE_WHEN_I_IS_0_OR_N_IS_1: Rule = {
    if (it.i == 0L || it.n == 1.0) PluralCategory.ONE else PluralCategory.OTHER
}

/** Danish: `one: n = 1 or t != 0 and i = 0,1` — a fraction below two is singular. */
private val DANISH: Rule = {
    if (it.n == 1.0 || (it.v != 0 && it.i in 0L..1L)) PluralCategory.ONE else PluralCategory.OTHER
}

/**
 * Icelandic: `one: t = 0 and i % 10 = 1 and i % 100 != 11 or t % 10 = 1 and t % 100 != 11`.
 *
 * The integer half is the `one` band East Slavic draws — 21 and 101 singular, 11 not. The second
 * half applies the same band to the fraction digits, which is why `0.1` and `0.001` are singular
 * while `0.5` and `0.25` are not.
 */
private val ICELANDIC: Rule = {
    val whole = it.t == 0L && it.iMod(10) == 1L && it.iMod(100) != 11L
    val fraction = it.tMod(10) == 1L && it.tMod(100) != 11L
    if (whole || fraction) PluralCategory.ONE else PluralCategory.OTHER
}

/**
 * `many` for an exact multiple of a million, layered over [base].
 *
 * CLDR gave the Western Romance languages a category for the round large numbers that compact
 * notation writes as `1 million`: `many: i != 0 and i % 1000000 = 0 and v = 0`. It sits in front of
 * the language's own rule rather than replacing it, which is why it is written as a wrapper.
 */
private fun withRomanceMillions(base: Rule): Rule = { operands ->
    if (operands.v == 0 && operands.i != 0L && operands.i % MILLION == 0L) PluralCategory.MANY
    else base(operands)
}

private const val MILLION: Long = 1_000_000L

/**
 * Hebrew, which keeps a dual.
 *
 * `one: i = 1 and v = 0 or i = 0 and v != 0`, `two: i = 2 and v = 0`. The second half of `one` is
 * what puts `0.5` in the singular while `0` stays `other`.
 */
private val HEBREW: Rule = {
    when {
        (it.i == 1L && it.v == 0) || (it.i == 0L && it.v != 0) -> PluralCategory.ONE
        it.i == 2L && it.v == 0 -> PluralCategory.TWO
        else -> PluralCategory.OTHER
    }
}

/** Russian and Ukrainian: one/few/many on the last two integer digits, fractions always other. */
private val EAST_SLAVIC: Rule = {
    val mod10 = it.iMod(10)
    val mod100 = it.iMod(100)
    when {
        it.v != 0 -> PluralCategory.OTHER
        mod10 == 1L && mod100 != 11L -> PluralCategory.ONE
        mod10 in 2L..4L && mod100 !in 12L..14L -> PluralCategory.FEW
        else -> PluralCategory.MANY
    }
}

/** Polish, which splits `1` off from the `many` bucket that Russian leaves it in. */
private val POLISH: Rule = {
    val mod10 = it.iMod(10)
    val mod100 = it.iMod(100)
    when {
        it.i == 1L && it.v == 0 -> PluralCategory.ONE
        it.v != 0 -> PluralCategory.OTHER
        mod10 in 2L..4L && mod100 !in 12L..14L -> PluralCategory.FEW
        else -> PluralCategory.MANY
    }
}

/** Czech and Slovak, where every fractional value is `many` rather than `other`. */
private val WEST_SLAVIC: Rule = {
    when {
        it.i == 1L && it.v == 0 -> PluralCategory.ONE
        it.i in 2L..4L && it.v == 0 -> PluralCategory.FEW
        it.v != 0 -> PluralCategory.MANY
        else -> PluralCategory.OTHER
    }
}

/** Arabic, the one language here that uses all six categories. */
private val ARABIC: Rule = {
    val mod100 = it.nMod(100)
    when {
        it.v != 0 -> PluralCategory.OTHER
        it.n == 0.0 -> PluralCategory.ZERO
        it.n == 1.0 -> PluralCategory.ONE
        it.n == 2.0 -> PluralCategory.TWO
        mod100 in 3L..10L -> PluralCategory.FEW
        mod100 in 11L..99L -> PluralCategory.MANY
        else -> PluralCategory.OTHER
    }
}

/** Romanian, whose `few` covers zero and the whole `x01`–`x19` band. */
private val ROMANIAN: Rule = {
    val mod100 = it.iMod(100)
    when {
        it.i == 1L && it.v == 0 -> PluralCategory.ONE
        it.v != 0 || it.n == 0.0 || (it.n != 1.0 && mod100 in 1L..19L) -> PluralCategory.FEW
        else -> PluralCategory.OTHER
    }
}

/**
 * Language subtag to rule.
 *
 * Grouped by rule rather than listed alphabetically so that adding a language is a matter of
 * finding the family it shares, and so that a language in the wrong family is visible as one.
 */
private val FAMILIES: Map<String, Rule> = buildMap {
    listOf(
        "ja", "zh", "ko", "th", "vi", "id", "ms", "my", "km", "lo", "bo", "jv", "yo", "ig",
    ).forEach { put(it, ONLY_OTHER) }

    listOf(
        "en", "de", "nl", "sv", "no", "nb", "nn", "fi", "et", "el", "bg",
        "af", "sw", "eu", "gl", "fo", "lb", "ml", "mr", "ta", "te", "ur",
    ).forEach { put(it, ONE_WHEN_I_IS_1) }

    listOf("tr", "az", "kk", "ky", "uz", "hu", "ka", "ne").forEach { put(it, ONE_WHEN_N_IS_1) }

    put("hy", ONE_WHEN_I_IS_0_OR_1)

    // The Western Romance group, which shares the millions rule but not the rest.
    listOf("it", "ca").forEach { put(it, withRomanceMillions(ONE_WHEN_I_IS_1)) }
    put("es", withRomanceMillions(ONE_WHEN_N_IS_1))
    listOf("fr", "pt").forEach { put(it, withRomanceMillions(ONE_WHEN_I_IS_0_OR_1)) }

    listOf("hi", "bn", "gu", "fa", "zu", "kn").forEach { put(it, ONE_WHEN_I_IS_0_OR_N_IS_1) }

    put("da", DANISH)
    put("is", ICELANDIC)
    put("he", HEBREW)

    listOf("ru", "uk", "be").forEach { put(it, EAST_SLAVIC) }

    put("pl", POLISH)
    listOf("cs", "sk").forEach { put(it, WEST_SLAVIC) }
    put("ar", ARABIC)
    put("ro", ROMANIAN)
}
