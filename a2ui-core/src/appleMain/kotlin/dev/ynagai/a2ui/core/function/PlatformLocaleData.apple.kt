package dev.ynagai.a2ui.core.function

import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarIdentifierGregorian
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterCurrencyStyle
import platform.Foundation.NSNumberFormatterDecimalStyle
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier

/** Reads `NSDateFormatter` and `NSNumberFormatter`'s symbols. @see LocaleData */
public actual fun platformLocaleData(languageTag: String?): LocaleData =
    FoundationLocaleData(
        if (languageTag == null) NSLocale.currentLocale
        else NSLocale(localeIdentifier = languageTag),
    )

private class FoundationLocaleData(private val locale: NSLocale) : LocaleData {
    override val symbols: LocaleSymbols by lazy { readSymbols(locale) }

    override fun currency(code: String): CurrencyAffixes = readCurrency(locale, code)
}

private fun readSymbols(locale: NSLocale): LocaleSymbols {
    val numbers = NSNumberFormatter().apply {
        setLocale(locale)
        setNumberStyle(NSNumberFormatterDecimalStyle)
    }
    val dates = NSDateFormatter().apply {
        setLocale(locale)
        // The calendar is pinned rather than inherited. `NSDateFormatter` takes it from the
        // locale, and a locale's default calendar is not always the Gregorian one -- `fa_IR`
        // resolves to `persian` -- while the pattern engine that consumes these names decomposes
        // a UTC instant as proleptic Gregorian. Inherited, `fa_IR`'s `monthSymbols[0]` was
        // `فروردین` -- Farvardin, the first month of the Persian year, which begins at the March
        // equinox -- filed under the index the engine uses for January.
        setCalendar(NSCalendar(calendarIdentifier = NSCalendarIdentifierGregorian))
    }

    val primary = numbers.groupingSize.toInt().takeIf { it > 0 } ?: DEFAULT_GROUP_SIZE
    val secondary = numbers.secondaryGroupingSize.toInt()

    return LocaleSymbols(
        // `localeIdentifier` is ICU's form (`en_US`), which BCP 47 writes with a hyphen. Only the
        // language subtag is read anywhere downstream, but reporting a tag means reporting one.
        languageTag = locale.localeIdentifier.replace('_', '-'),
        decimalSeparator = numbers.decimalSeparator,
        groupSeparator = numbers.groupingSeparator,
        minusSign = numbers.minusSign,
        // The secondary size is reported as 0 by every locale that does not have one, and as the
        // same value as the primary by some that do; both mean "one repeating size".
        groupSizes = if (secondary > 0 && secondary != primary) listOf(primary, secondary)
        else listOf(primary),
        months = names(dates.monthSymbols, dates.shortMonthSymbols, dates.veryShortMonthSymbols),
        // Foundation's weekday symbols start at Sunday, which is the order `LocaleSymbols` wants.
        weekdays = names(dates.weekdaySymbols, dates.shortWeekdaySymbols, dates.veryShortWeekdaySymbols),
        amPm = listOf(dates.AMSymbol, dates.PMSymbol),
    )
}

/** Foundation types these as `List<*>`; every element is an `NSString`. */
private fun names(wide: List<*>, abbreviated: List<*>, narrow: List<*>): DateNames = DateNames(
    wide = wide.map { it as String },
    abbreviated = abbreviated.map { it as String },
    narrow = narrow.map { it as String },
)

private fun readCurrency(locale: NSLocale, code: String): CurrencyAffixes {
    val format = NSNumberFormatter().apply {
        setLocale(locale)
        setNumberStyle(NSNumberFormatterCurrencyStyle)
        setCurrencyCode(code)
    }
    return CurrencyAffixes(
        positivePrefix = format.positivePrefix,
        positiveSuffix = format.positiveSuffix,
        negativePrefix = format.negativePrefix,
        negativeSuffix = format.negativeSuffix,
        // The currency style sets both fraction bounds to the code's minor-unit count, so reading
        // the maximum reads that count. A code Foundation does not know keeps the style's own
        // default of two, which is the same stand-in the other targets make.
        fractionDigits = format.maximumFractionDigits.toInt(),
    )
}

private const val DEFAULT_GROUP_SIZE: Int = 3
