package dev.ynagai.a2ui.core.function

import java.text.DateFormatSymbols
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** Reads `java.text`'s tables, which the JVM and Android both carry. @see LocaleData */
public actual fun platformLocaleData(languageTag: String?): LocaleData =
    JavaTextLocaleData(
        if (languageTag == null) Locale.getDefault(Locale.Category.FORMAT)
        else Locale.forLanguageTag(languageTag),
    )

private class JavaTextLocaleData(private val locale: Locale) : LocaleData {
    override val symbols: LocaleSymbols by lazy { readSymbols(locale) }

    override fun currency(code: String): CurrencyAffixes = readCurrency(locale, code)
}

private fun readSymbols(locale: Locale): LocaleSymbols {
    val numbers = DecimalFormatSymbols.getInstance(locale)
    val dates = DateFormatSymbols.getInstance(locale)
    // `getInstance` is documented to return a `DecimalFormat` for every locale the JDK ships, but
    // the type is `NumberFormat` and a provider may substitute another. Three digits is then the
    // grouping, which is what all but the Indic locales use.
    val grouping = (NumberFormat.getInstance(locale) as? DecimalFormat)?.groupingSize ?: DEFAULT_GROUP_SIZE

    return LocaleSymbols(
        languageTag = locale.toLanguageTag(),
        decimalSeparator = numbers.decimalSeparator.toString(),
        groupSeparator = numbers.groupingSeparator.toString(),
        minusSign = numbers.minusSign.toString(),
        groupSizes = listOf(if (grouping > 0) grouping else DEFAULT_GROUP_SIZE),
        months = names(dates.months, dates.shortMonths, from = 0, count = MONTHS_IN_YEAR),
        // `DateFormatSymbols` indexes weekdays by `Calendar.SUNDAY`, which is 1, and leaves slot 0
        // empty. `LocaleSymbols` counts from Sunday at 0, so the read starts one in.
        weekdays = names(dates.weekdays, dates.shortWeekdays, from = 1, count = DAYS_IN_WEEK),
        amPm = dates.amPmStrings.take(2),
    )
}

/**
 * [wide] and [abbreviated] as a [DateNames], with the narrow width taken as a first character.
 *
 * `java.text` has no narrow forms — they arrived with `java.time`, which Android does not carry
 * below API 26 and this module's `minSdk` is 24. Deriving them is wrong wherever a locale's narrow
 * form is not the first character of its abbreviation (Chinese numbers its months, so `MMMMM` is
 * `1` and not `一`), and it is the one place these two targets are less faithful than the other
 * five. It does not reach the catalog: no `MMMMM` or `EEEEE` appears in any of the 43 examples.
 */
private fun names(wide: Array<String>, abbreviated: Array<String>, from: Int, count: Int): DateNames {
    val wideNames = wide.toList().subList(from, from + count)
    val shortNames = abbreviated.toList().subList(from, from + count)
    return DateNames(
        wide = wideNames,
        abbreviated = shortNames,
        narrow = shortNames.map { it.take(1) },
    )
}

private fun readCurrency(locale: Locale, code: String): CurrencyAffixes {
    val format = NumberFormat.getCurrencyInstance(locale) as? DecimalFormat
        ?: return unknownCurrency(code)
    val currency = runCatching { Currency.getInstance(code) }.getOrNull()
        ?: return unknownCurrency(code)
    format.currency = currency
    return CurrencyAffixes(
        positivePrefix = format.positivePrefix,
        positiveSuffix = format.positiveSuffix,
        negativePrefix = format.negativePrefix,
        negativeSuffix = format.negativeSuffix,
        // `-1` for the codes with no minor unit defined, such as `XXX`. Two digits is the
        // convention for an amount whose currency is unknown, and matches `FallbackLocaleFormatter`.
        fractionDigits = currency.defaultFractionDigits.takeIf { it >= 0 } ?: DEFAULT_FRACTION_DIGITS,
    )
}

/**
 * A code the platform does not know, written out as itself.
 *
 * The specification has no error path for `formatCurrency` with an unrecognised code, and refusing
 * would take a whole surface down over one string, so the code stands in for the symbol — which is
 * also what [FallbackLocaleFormatter] does with every code.
 */
private fun unknownCurrency(code: String): CurrencyAffixes = CurrencyAffixes(
    positivePrefix = "$code ",
    positiveSuffix = "",
    negativePrefix = "-$code ",
    negativeSuffix = "",
    fractionDigits = DEFAULT_FRACTION_DIGITS,
)

private const val MONTHS_IN_YEAR: Int = 12
private const val DAYS_IN_WEEK: Int = 7
private const val DEFAULT_GROUP_SIZE: Int = 3
private const val DEFAULT_FRACTION_DIGITS: Int = 2
