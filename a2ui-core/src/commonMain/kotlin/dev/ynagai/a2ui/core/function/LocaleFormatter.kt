package dev.ynagai.a2ui.core.function

import kotlin.math.abs
import kotlin.math.floor

/**
 * The locale-sensitive half of the basic catalog, kept as a contract rather than an implementation.
 *
 * `formatNumber`, `formatCurrency`, `formatDate` and `pluralize` are specified in terms of "the
 * platform's native locale formatting" — `Intl.NumberFormat` on the web, `NumberFormatter` on
 * Apple platforms, `android.icu` on Android. `commonMain` has none of those and Kotlin's standard
 * library carries no locale data at all, so no implementation of this can live in `commonMain`
 * alone.
 *
 * The evaluator therefore depends on this interface and never on a platform. Two implementations
 * ship: [FallbackLocaleFormatter], which is locale-independent and is what a renderer gets until it
 * asks for otherwise, and [SymbolLocaleFormatter], which reads the platform's own symbols through
 * [LocaleData]. A host reaches the second through `localeFormatter` or `systemLocaleFormatter`.
 *
 * This is deliberately an interface rather than an `expect` declaration, and it stayed one after
 * the platform implementations landed: an `expect` would make every target's formatter the only
 * formatter, and the injection point is what lets a host substitute its own — an app that already
 * carries ICU, or a test that wants an instant to render the same in every environment.
 */
public interface LocaleFormatter {
    /**
     * [value] as a decimal string.
     *
     * [decimals] fixes both the minimum and the maximum number of fraction digits when present;
     * when absent the formatter chooses. [grouping] asks for the locale's group separators.
     */
    public fun formatNumber(value: Double, decimals: Int?, grouping: Boolean): String

    /** [value] as an amount in [currency], an ISO 4217 code. Arguments otherwise as [formatNumber]. */
    public fun formatCurrency(value: Double, currency: String, decimals: Int?, grouping: Boolean): String

    /**
     * The instant [epochMillis] rendered with the Unicode TR35 pattern [pattern].
     *
     * The instant is UTC. The specification says nothing about which zone a renderer formats in,
     * and a formatter that reads the device's zone cannot be a pure function of its arguments —
     * so the zone is left to the implementation to document, and this one does not shift.
     */
    public fun formatDate(epochMillis: Long, pattern: String): String

    /** Which CLDR plural category [value] falls into for this formatter's locale. */
    public fun pluralCategory(value: Double): PluralCategory
}

/** The CLDR plural categories, which are also the optional argument names of `pluralize`. */
public enum class PluralCategory(public val argumentName: String) {
    ZERO("zero"),
    ONE("one"),
    TWO("two"),
    FEW("few"),
    MANY("many"),
    OTHER("other"),
}

/**
 * A locale-independent [LocaleFormatter]: ASCII separators, English names, English plural rules.
 *
 * **This is not a locale implementation and does not claim to be one.** It is what the four
 * locale-sensitive functions do when nobody has chosen a locale, chosen so that their output is
 * predictable rather than plausible: `1234.5` is `1,234.5` here on every target and in every
 * environment, which is what makes the evaluator's own behaviour testable. [SymbolLocaleFormatter]
 * is what a host uses instead once it wants a locale.
 *
 * Its output is English-shaped rather than root-shaped on purpose. CLDR's root locale has a single
 * plural category and numeric month names, which would make `pluralize` and `formatDate` produce
 * output no renderer would ship. Approximating `en-US` at least produces something a developer can
 * read while recognising it as a placeholder.
 */
public object FallbackLocaleFormatter : LocaleFormatter {
    override fun formatNumber(value: Double, decimals: Int?, grouping: Boolean): String =
        formatDecimal(value, decimals, grouping)

    /** `USD 1,234.50` — the code and the amount, since no symbol table is available here. */
    override fun formatCurrency(
        value: Double,
        currency: String,
        decimals: Int?,
        grouping: Boolean,
    ): String = currency + " " + formatDecimal(value, decimals ?: CURRENCY_DECIMALS, grouping)

    override fun formatDate(epochMillis: Long, pattern: String): String =
        formatUtcPattern(epochMillis, pattern)

    /**
     * The English rule, `|n| == 1` → [PluralCategory.ONE].
     *
     * CLDR's English rule is `i == 1 and v == 0`, which puts `1.0` written with a fraction digit
     * into `other`. That distinction needs the formatted string rather than the number, which this
     * signature does not carry, so it is not drawn.
     *
     * The magnitude is taken because CLDR defines its operand `n` as "the absolute value of the
     * source number": `-1` is `one`, so `pluralize(value: -1, one: "item", other: "items")` reads
     * "-1 item" rather than "-1 items".
     */
    override fun pluralCategory(value: Double): PluralCategory =
        if (abs(value) == 1.0) PluralCategory.ONE else PluralCategory.OTHER

    private const val CURRENCY_DECIMALS: Int = 2
}

/** Fraction digits beyond which the scaled-integer rounding below stops being exact. */
private const val MAX_EXACT_DECIMALS: Int = 15

/** The largest magnitude a rounded, scaled [Double] still represents every integer below. */
private const val EXACT_INTEGER_LIMIT: Double = 9.0e15

private const val GROUP_SIZE: Int = 3

/** The separators a locale-independent formatter writes with, and the default for [formatDecimal]. */
internal val AsciiNumberSymbols: NumberSymbols = NumberSymbols(".", ",", "-", listOf(GROUP_SIZE))

/** Just the parts of [LocaleSymbols] that writing a number out needs. */
internal class NumberSymbols(
    val decimalSeparator: String,
    val groupSeparator: String,
    val minusSign: String,
    val groupSizes: List<Int>,
)

/**
 * [value] as digits, with [decimals] fraction digits when given and [symbols]' group separator
 * between integer digit groups when [grouping].
 *
 * With no [decimals] the shortest representation that round-trips is used rather than a made-up
 * precision: a formatter with no locale has no basis for choosing 2 over 3, and silently rounding
 * a value the caller did not ask to round loses information that the caller cannot get back.
 *
 * The digits themselves are always Latin, on every locale. A locale's own numbering system is not
 * expressible as a symbol table -- it is a per-digit mapping, and some systems are not positional
 * at all -- so it is out of the seam [LocaleData] draws. This is the visible edge of that choice.
 */
internal fun formatDecimal(
    value: Double,
    decimals: Int?,
    grouping: Boolean,
    symbols: NumberSymbols = AsciiNumberSymbols,
): String {
    if (value.isNaN() || value.isInfinite()) return value.toString()
    val negative = value < 0.0 || (value == 0.0 && 1.0 / value < 0.0)
    val magnitude = abs(value)

    val digits = when {
        decimals == null -> shortestDigits(magnitude)
        decimals < 0 -> throw A2uiFunctionException("`decimals` must not be negative, but was $decimals.")
        // Out of the range that rounds exactly. The requested precision cannot be honoured, but
        // the value can still be written out: returning `value.toString()` here gave up the
        // grouping and the sign handling below as well, and put back the exponent form that the
        // JVM and JavaScript disagree about — `formatCurrency(123456789012345.67, "USD")` read
        // `USD 1.2345678901234567E14` on one target and a plain digit string on another, which is
        // the divergence `shortestDigits` exists to remove.
        else -> fixedDigits(magnitude, decimals) ?: shortestDigits(magnitude)
    }

    val point = digits.indexOf('.')
    val integerPart = if (point < 0) digits else digits.substring(0, point)
    val fractionPart =
        if (point < 0) "" else symbols.decimalSeparator + digits.substring(point + 1)
    val grouped = if (grouping) group(integerPart, symbols) else integerPart
    return if (negative) symbols.minusSign + grouped + fractionPart else grouped + fractionPart
}

/** [magnitude] with exactly [decimals] fraction digits, or null when it cannot be rounded exactly. */
private fun fixedDigits(magnitude: Double, decimals: Int): String? {
    if (decimals > MAX_EXACT_DECIMALS) return null
    var scale = 1.0
    repeat(decimals) { scale *= 10.0 }
    val scaled = magnitude * scale
    // Read before the multiplication is trusted rather than after: past this bound consecutive
    // doubles are further than 1 apart, so `round` returns a value whose decimal expansion is not
    // the one the caller wrote, and padding it produces confidently wrong digits.
    if (!scaled.isFinite() || scaled >= EXACT_INTEGER_LIMIT) return null
    // `floor(x + 0.5)` rather than `round`, which breaks ties towards the even integer: the
    // rule ECMA-402 and CLDR specify for number formatting is half-expand, so 0.125 at two
    // fraction digits is 0.13 and not 0.12. `scaled` is a magnitude, so this is away from zero.
    val rounded = floor(scaled + 0.5).toLong().toString().padStart(decimals + 1, '0')
    if (decimals == 0) return rounded
    return rounded.dropLast(decimals) + "." + rounded.takeLast(decimals)
}

/**
 * [magnitude] in its shortest round-tripping form, written out in full.
 *
 * `Double.toString` switches to exponential notation, and the two thresholds it switches at are
 * not the same on every target: the JVM and Native do it from 1e7 upwards, JavaScript not until
 * 1e21. Passing that form through would make `formatNumber(10000000)` render as `1.0E7` on one
 * target and `10,000,000` on another, from the same payload — which is the divergence a
 * locale-independent formatter exists to remove. So the exponent is applied to the digits here.
 *
 * `internal` rather than private because [CldrPluralRules] needs the same expansion: CLDR's `v`
 * and `t` are counts of *written* fraction digits, so reading them off `Double.toString` made them
 * depend on which target's threshold had switched to an exponent. One expander, one answer.
 */
internal fun shortestDigits(magnitude: Double): String {
    val text = magnitude.toString()
    val marker = text.indexOfFirst { it == 'e' || it == 'E' }
    if (marker < 0) return if (text.endsWith(".0")) text.dropLast(2) else text

    val exponent = text.substring(marker + 1).toIntOrNull() ?: return text
    val mantissa = text.substring(0, marker)
    val point = mantissa.indexOf('.')
    val whole = if (point < 0) mantissa else mantissa.substring(0, point)
    val fraction = if (point < 0) "" else mantissa.substring(point + 1)
    val digits = whole + fraction
    // Where the decimal point lands once the exponent is applied. `magnitude` is never negative,
    // so there is no sign to carry through.
    val at = whole.length + exponent

    val expanded = when {
        at <= 0 -> "0." + "0".repeat(-at) + digits
        at >= digits.length -> digits + "0".repeat(at - digits.length)
        else -> digits.substring(0, at) + "." + digits.substring(at)
    }
    return if ('.' in expanded) expanded.trimEnd('0').trimEnd('.') else expanded
}

/**
 * [integerPart] split into groups and joined with [NumberSymbols.groupSeparator].
 *
 * Walked from the decimal separator outwards rather than from the left, because that is the
 * direction CLDR's sizes are defined in and the only direction that renders `[3, 2]` -- the Indian
 * grouping -- as `12,34,567` rather than `123,45,67`. The last size repeats for everything beyond
 * the ones named.
 */
private fun group(integerPart: String, symbols: NumberSymbols): String {
    val sizes = symbols.groupSizes
    val chunks = ArrayList<String>(integerPart.length / sizes.last() + 1)
    var end = integerPart.length
    var index = 0
    while (end > 0) {
        val size = sizes[if (index < sizes.size) index else sizes.size - 1]
        val start = if (end - size > 0) end - size else 0
        chunks.add(integerPart.substring(start, end))
        end = start
        index++
    }
    return chunks.asReversed().joinToString(symbols.groupSeparator)
}
