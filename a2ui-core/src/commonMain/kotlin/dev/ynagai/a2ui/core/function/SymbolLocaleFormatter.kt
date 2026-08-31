package dev.ynagai.a2ui.core.function

/**
 * The [LocaleFormatter] the renderer ships: [LocaleData]'s symbols, this module's assembly.
 *
 * One instance holds one locale. The four functions are pure in their arguments — the data is read
 * once, at construction, so a formatter cannot start answering differently halfway through a frame
 * because the device's settings changed under it.
 *
 * @see LocaleData for why the assembly is shared across targets instead of delegated to each.
 */
public class SymbolLocaleFormatter(private val data: LocaleData) : LocaleFormatter {
    private val symbols: LocaleSymbols = data.symbols

    private val numbers = NumberSymbols(
        decimalSeparator = symbols.decimalSeparator,
        groupSeparator = symbols.groupSeparator,
        minusSign = symbols.minusSign,
        groupSizes = symbols.groupSizes,
    )

    private val dateNames = DateFieldNames(
        months = symbols.months,
        weekdays = symbols.weekdays,
        amPm = symbols.amPm,
    )

    /** The BCP 47 tag this formatter resolved to, which is not always the one that was asked for. */
    public val languageTag: String get() = symbols.languageTag

    override fun formatNumber(value: Double, decimals: Int?, grouping: Boolean): String =
        formatDecimal(value, decimals, grouping, numbers)

    /**
     * [value] in [currency], with the locale's affixes around it.
     *
     * The digits are formatted from the magnitude and the sign is carried by the affixes, because
     * that is where a locale puts it: `en-US` writes `-$1.00` but `nl-NL` writes `€ -1,00`, and
     * both come out of [CurrencyAffixes] rather than out of the number.
     *
     * With no [decimals] the currency's own minor-unit count is used — 2 for `USD`, 0 for `JPY` —
     * rather than [formatNumber]'s shortest round-tripping form. An amount of money has a
     * conventional precision where a bare number does not.
     */
    override fun formatCurrency(
        value: Double,
        currency: String,
        decimals: Int?,
        grouping: Boolean,
    ): String {
        val affixes = data.currency(currency)
        val negative = value < 0.0 || (value == 0.0 && 1.0 / value < 0.0)
        val digits = formatDecimal(
            value = if (negative) -value else value,
            decimals = decimals ?: affixes.fractionDigits,
            grouping = grouping,
            symbols = numbers,
        )
        return if (negative) {
            affixes.negativePrefix + digits + affixes.negativeSuffix
        } else {
            affixes.positivePrefix + digits + affixes.positiveSuffix
        }
    }

    /** @see formatUtcPattern — the instant is UTC here too, and for the same reason. */
    override fun formatDate(epochMillis: Long, pattern: String): String =
        formatUtcPattern(epochMillis, pattern, dateNames)

    override fun pluralCategory(value: Double): PluralCategory =
        CldrPluralRules.categoryFor(symbols.languageTag, value)
}

/**
 * A [LocaleFormatter] for [languageTag], a BCP 47 tag.
 *
 * The tag is honoured as far as the platform's own data goes and falls back the way that platform
 * falls back, so the formatter this returns may report a different [SymbolLocaleFormatter.languageTag]
 * than was asked for. Asking for a tag no platform knows yields that platform's root or default
 * data rather than an error, which is the degradation the specification asks of missing references.
 */
public fun localeFormatter(languageTag: String): LocaleFormatter =
    SymbolLocaleFormatter(platformLocaleData(languageTag))

/**
 * A [LocaleFormatter] for whatever locale the device is currently set to.
 *
 * **Opt-in, and deliberately not the default.** `A2uiRenderer` keeps [FallbackLocaleFormatter]
 * until a host passes something else, because a formatter that reads the device makes the four
 * functions depend on the environment: the same payload and the same test then produce different
 * strings on a developer's machine and in CI. A host that wants the device's locale is choosing
 * that, in one line, where it can be seen.
 *
 * The locale is read once, here. A device whose locale changes while a surface is on screen keeps
 * formatting with the old one until the host builds a new formatter.
 */
public fun systemLocaleFormatter(): LocaleFormatter =
    SymbolLocaleFormatter(platformLocaleData(languageTag = null))

/**
 * The platform's locale data for [languageTag], or for the device's current locale when it is null.
 *
 * The seven `actual`s are each a thin read of the platform's own tables — `java.text` on the JVM
 * and Android, `NSDateFormatter` and `NSNumberFormatter` on Apple platforms, `Intl` on the web.
 * None of them formats anything; see [LocaleData] for why the formatting stayed in common code.
 */
public expect fun platformLocaleData(languageTag: String?): LocaleData
