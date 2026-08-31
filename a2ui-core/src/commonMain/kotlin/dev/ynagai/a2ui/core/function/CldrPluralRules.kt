package dev.ynagai.a2ui.core.function

import kotlin.math.abs

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
 * The operands are CLDR's, computed from the `Double` the function is given: `n` is its absolute
 * value, `i` the integer part, and `v` the number of fraction digits in the shortest representation
 * that round-trips. Reading `v` off the value rather than off a formatted string is what makes
 * `1.0` plural-equal to `1` here — a formatter asked for `1.00` would put it in `other` under
 * CLDR's own reading, and this signature never sees that request.
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

/** CLDR's plural operands, as far as a bare `Double` determines them. */
private class Operands(value: Double) {
    /** The absolute value of the source number. */
    val n: Double = abs(value)

    /** The integer digits, as a value. Bounded so a huge magnitude cannot overflow the modulo. */
    val i: Long = if (n >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE else n.toLong()

    /** Fraction digits in the shortest round-tripping form: `0` for `1.0`, `1` for `1.5`. */
    val v: Int = fractionDigits(n)

    fun iMod(divisor: Int): Long = i % divisor

    /** `n % divisor`, defined only where [n] is integral — every rule below guards on that. */
    fun nMod(divisor: Int): Long = i % divisor
}

private fun fractionDigits(magnitude: Double): Int {
    if (!magnitude.isFinite()) return 0
    val text = magnitude.toString()
    // An exponent form (`1.0E20`, `1.0E-7`) is not a fraction-digit count; both are integral or
    // far below the precision any plural rule distinguishes, so they read as none.
    if (text.any { it == 'e' || it == 'E' }) return 0
    val point = text.indexOf('.')
    if (point < 0) return 0
    val fraction = text.substring(point + 1)
    return if (fraction == "0") 0 else fraction.length
}

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
        "en", "de", "nl", "sv", "da", "no", "nb", "nn", "fi", "et", "el", "it", "bg", "ca",
        "af", "sw", "he", "eu", "gl", "fo", "is", "lb", "ml", "mr", "ta", "te", "ur", "kn",
    ).forEach { put(it, ONE_WHEN_I_IS_1) }

    listOf("es", "tr", "az", "kk", "ky", "uz", "hu", "ka", "hy", "ne").forEach {
        put(it, ONE_WHEN_N_IS_1)
    }

    listOf("fr", "pt").forEach { put(it, ONE_WHEN_I_IS_0_OR_1) }

    listOf("hi", "bn", "gu", "fa", "zu").forEach { put(it, ONE_WHEN_I_IS_0_OR_N_IS_1) }

    listOf("ru", "uk", "be").forEach { put(it, EAST_SLAVIC) }

    put("pl", POLISH)
    listOf("cs", "sk").forEach { put(it, WEST_SLAVIC) }
    put("ar", ARABIC)
    put("ro", ROMANIAN)
}
