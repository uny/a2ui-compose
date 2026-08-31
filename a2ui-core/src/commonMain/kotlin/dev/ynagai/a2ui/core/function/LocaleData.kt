package dev.ynagai.a2ui.core.function

/**
 * The locale-dependent *data* a formatter needs, separated from the formatting itself.
 *
 * [LocaleFormatter]'s KDoc anticipated seven `actual` implementations of the four functions. This
 * is the narrower seam that replaced that plan: the platforms supply symbols -- separators, month
 * and weekday names, currency affixes -- and [SymbolLocaleFormatter] does the assembly once, in
 * common code, for every target.
 *
 * The reason is `formatDate`. Only two of the seven targets can consume a Unicode TR35 pattern
 * natively (`NSDateFormatter` and `android.icu.text.SimpleDateFormat`); the JVM's `java.text`
 * dialect is close but not the same, and `Intl.DateTimeFormat` does not take patterns at all. Seven
 * native implementations would therefore have been three or four different pattern engines, and the
 * same payload would have rendered differently depending on which one a host happened to run --
 * the divergence the digit handling in [FallbackLocaleFormatter] already exists to remove.
 *
 * What is given up is the part of each platform's formatting that is not expressible as symbols:
 * the numbering system stays Latin even where the locale's default is not, and the grouping is
 * whatever [LocaleSymbols.groupSizes] says rather than whatever the platform would have done.
 */
public interface LocaleData {
    /** The symbols shared by every value this locale formats. */
    public val symbols: LocaleSymbols

    /**
     * How this locale writes an amount in [code], an ISO 4217 currency code.
     *
     * Looked up per code rather than carried in [symbols] because the affixes depend on both: `USD`
     * is `$1.00` in `en-US` and `1,00 $US` in `fr-FR`. Implementations should expect to be asked
     * for codes they do not know and answer with the code itself as the symbol.
     */
    public fun currency(code: String): CurrencyAffixes
}

/**
 * The symbols one locale writes numbers and dates with.
 *
 * Every list is fixed-length and indexed positionally, which is what lets the pattern engine treat
 * them as data: [months] holds January first, [weekdays] holds Sunday first (matching TR35's own
 * numbering of `e`), and [amPm] holds AM then PM.
 */
public class LocaleSymbols(
    /** The BCP 47 tag this data was resolved for, which is not always the tag that was asked for. */
    public val languageTag: String,
    /** Between the integer and fraction parts -- `.` in `en-US`, `,` in `de-DE`. */
    public val decimalSeparator: String,
    /** Between groups of integer digits -- `,` in `en-US`, ` ` in `fr-FR`. */
    public val groupSeparator: String,
    /** Written before a negative number. Not always ASCII `-`; `sv-SE` uses `−`. */
    public val minusSign: String,
    /**
     * Digits per group, counting from the decimal separator outwards, with the last entry repeating.
     *
     * `[3]` is the common case. `[3, 2]` is the Indian grouping, `12,34,567`. A platform that
     * cannot express the second size reports `[3]`, which renders `12,34,567` as `1,234,567` --
     * wrong, but wrong in a way that is legible rather than corrupt.
     */
    public val groupSizes: List<Int>,
    /** January first. */
    public val months: DateNames,
    /** Sunday first. */
    public val weekdays: DateNames,
    /** AM then PM. */
    public val amPm: List<String>,
) {
    init {
        require(months.wide.size == MONTHS_IN_YEAR) { "months must hold 12 names per width." }
        require(weekdays.wide.size == DAYS_IN_WEEK) { "weekdays must hold 7 names per width." }
        require(amPm.size == 2) { "amPm must hold exactly the two period names." }
        require(groupSizes.isNotEmpty() && groupSizes.all { it > 0 }) {
            "groupSizes must be non-empty and positive, but was $groupSizes."
        }
    }

    internal companion object {
        const val MONTHS_IN_YEAR: Int = 12
        const val DAYS_IN_WEEK: Int = 7
    }
}

/**
 * One set of names at the three widths TR35 distinguishes.
 *
 * Kept as three independent lists rather than one list and a truncation rule, because outside
 * English the short forms are not prefixes of the long ones: `septembre` abbreviates to `sept.`,
 * and `日曜日` narrows to `日`. Truncating is what [FallbackLocaleFormatter] does,
 * and it is one of the things that makes it a placeholder.
 */
public class DateNames(
    /** `September`, `Wednesday`. Used by `MMMM` and `EEEE`. */
    public val wide: List<String>,
    /** `Sep`, `Wed`. Used by `MMM` and `E` through `EEE`. */
    public val abbreviated: List<String>,
    /** `S`, `W`. Used by `MMMMM` and `EEEEE`. */
    public val narrow: List<String>,
) {
    init {
        require(abbreviated.size == wide.size && narrow.size == wide.size) {
            "every width must hold the same number of names."
        }
    }
}

/**
 * Where a locale puts the currency sign, and how many fraction digits the currency has.
 *
 * Affixes rather than a symbol and a placement flag: a locale can put a non-breaking space between
 * the sign and the digits, move the sign for negatives, or use a different sign entirely for the
 * negative form, and every platform this reads from exposes the four affixes directly.
 */
public class CurrencyAffixes(
    public val positivePrefix: String,
    public val positiveSuffix: String,
    public val negativePrefix: String,
    public val negativeSuffix: String,
    /** ISO 4217's minor-unit count: 2 for `USD`, 0 for `JPY`, 3 for `KWD`. */
    public val fractionDigits: Int,
) {
    init {
        require(fractionDigits >= 0) { "fractionDigits must not be negative, but was $fractionDigits." }
    }
}
