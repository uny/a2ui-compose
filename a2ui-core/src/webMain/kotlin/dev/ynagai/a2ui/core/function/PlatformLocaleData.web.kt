package dev.ynagai.a2ui.core.function

/**
 * Reads `Intl`'s tables, which Kotlin/JS and Kotlin/Wasm both reach the same way. @see LocaleData
 *
 * `Intl` answers in objects and arrays, and the two web targets marshal those differently — a
 * `dynamic` on one, a `JsAny` on the other. So the bridge below returns one flat string per query
 * and the unpacking happens in Kotlin, where it is the same code for both targets and can be read
 * without knowing either interop.
 */
public actual fun platformLocaleData(languageTag: String?): LocaleData =
    IntlLocaleData(languageTag ?: "")

private class IntlLocaleData(private val tag: String) : LocaleData {
    override val symbols: LocaleSymbols by lazy { unpackSymbols(intlSymbols(tag)) }

    override fun currency(code: String): CurrencyAffixes = unpackCurrency(intlCurrency(tag, code), code)
}

/** Between the fields of a bundle. Chosen outside anything `Intl` can put in a symbol. */
private const val FIELD: Char = '\u0001'

/** Between the entries of a list field. */
private const val ITEM: Char = '\u0002'

private fun unpackSymbols(bundle: String): LocaleSymbols {
    val f = bundle.split(FIELD)
    return LocaleSymbols(
        languageTag = f[0],
        decimalSeparator = f[1],
        groupSeparator = f[2],
        minusSign = f[3],
        groupSizes = f[4].split(ITEM).mapNotNull { it.toIntOrNull()?.takeIf { size -> size > 0 } }
            .ifEmpty { listOf(DEFAULT_GROUP_SIZE) },
        months = DateNames(f[5].split(ITEM), f[6].split(ITEM), f[7].split(ITEM)),
        weekdays = DateNames(f[8].split(ITEM), f[9].split(ITEM), f[10].split(ITEM)),
        amPm = listOf(f[11], f[12]),
    )
}

private fun unpackCurrency(bundle: String, code: String): CurrencyAffixes {
    val f = bundle.split(FIELD)
    return CurrencyAffixes(
        positivePrefix = f[0],
        positiveSuffix = f[1],
        negativePrefix = f[2],
        negativeSuffix = f[3],
        fractionDigits = f[4].toIntOrNull()?.takeIf { it >= 0 } ?: DEFAULT_FRACTION_DIGITS,
    )
}

/**
 * The locale's symbols, packed as described by [unpackSymbols].
 *
 * An empty [tag] means the runtime's own default locale, which is what `undefined` asks `Intl` for.
 *
 * The separators and the grouping are read off one formatted probe rather than requested field by
 * field, because `Intl` has no accessor for them: `formatToParts(-12345678.9)` names every piece it
 * emitted, so the group sizes fall out of the integer runs — three then two for the Indian
 * grouping, three repeating for everything else.
 */
private fun intlSymbols(tag: String): String = js(
    """
    (function () {
        var loc = tag === '' ? undefined : tag;
        var FIELD = '\u0001';
        var ITEM = '\u0002';
        var nf = new Intl.NumberFormat(loc);
        var parts = nf.formatToParts(-12345678.9);
        var dec = '.', grp = ',', minus = '-', ints = [];
        for (var i = 0; i < parts.length; i++) {
            var p = parts[i];
            if (p.type === 'decimal') dec = p.value;
            else if (p.type === 'group') grp = p.value;
            else if (p.type === 'minusSign') minus = p.value;
            else if (p.type === 'integer') ints.push(p.value);
        }
        var sizes = [];
        if (ints.length >= 2) {
            sizes.push(ints[ints.length - 1].length);
            if (ints.length >= 3 && ints[ints.length - 2].length !== sizes[0]) {
                sizes.push(ints[ints.length - 2].length);
            }
        } else {
            sizes.push(3);
        }

        // Whole-string `format` rather than the `month` / `weekday` part: a locale's name for a
        // month is not always the part `Intl` labels `month`. Japanese formats January as `1月`,
        // of which `formatToParts` calls only the `1` the month and the `月` a literal, so
        // reading the part yielded a bare digit where the name was wanted.
        function field(date, options) {
            // Bound to a variable rather than called on the constructor expression: the compiler
            // that inlines this reads `new A.B(x).c(y)` as `new (A.B(x).c)(y)`, which throws.
            var f = new Intl.DateTimeFormat(loc, options);
            return f.format(date);
        }
        function months(width) {
            var out = [];
            for (var m = 0; m < 12; m++) {
                out.push(field(Date.UTC(2021, m, 15), { month: width, timeZone: 'UTC' }));
            }
            return out.join(ITEM);
        }
        function weekdays(width) {
            var out = [];
            // 2021-08-01 was a Sunday, which is index 0 in `LocaleSymbols`.
            for (var d = 0; d < 7; d++) {
                out.push(field(Date.UTC(2021, 7, 1 + d), { weekday: width, timeZone: 'UTC' }));
            }
            return out.join(ITEM);
        }
        function period(hour, fallback) {
            var options = { hour: 'numeric', hour12: true, timeZone: 'UTC' };
            var f = new Intl.DateTimeFormat(loc, options);
            var ps = f.formatToParts(Date.UTC(2021, 0, 1, hour));
            for (var k = 0; k < ps.length; k++) if (ps[k].type === 'dayPeriod') return ps[k].value;
            return fallback;
        }

        return [
            nf.resolvedOptions().locale, dec, grp, minus, sizes.join(ITEM),
            months('long'), months('short'), months('narrow'),
            weekdays('long'), weekdays('short'), weekdays('narrow'),
            period(9, 'AM'), period(21, 'PM')
        ].join(FIELD);
    })()
    """,
)

/**
 * The locale's affixes for [code], packed as described by [unpackCurrency].
 *
 * `Intl` exposes no affixes either, so they are read as the text on each side of the digits of a
 * formatted probe. A code `Intl` rejects raises a `RangeError` before anything is formatted; the
 * code itself then stands in for the symbol, the same stand-in the other targets make.
 */
private fun intlCurrency(tag: String, code: String): String = js(
    """
    (function () {
        var loc = tag === '' ? undefined : tag;
        var FIELD = '\u0001';
        var numeric = { integer: 1, group: 1, decimal: 1, fraction: 1 };
        var cf;
        try {
            cf = new Intl.NumberFormat(loc, { style: 'currency', currency: code });
        } catch (e) {
            return [code + ' ', '', '-' + code + ' ', '', '2'].join(FIELD);
        }
        function affixes(value) {
            var ps = cf.formatToParts(value);
            var first = -1, last = -1;
            for (var i = 0; i < ps.length; i++) {
                if (numeric[ps[i].type]) { if (first < 0) first = i; last = i; }
            }
            if (first < 0) return ['', ''];
            var prefix = '', suffix = '';
            for (var j = 0; j < first; j++) prefix += ps[j].value;
            for (var k = last + 1; k < ps.length; k++) suffix += ps[k].value;
            return [prefix, suffix];
        }
        var positive = affixes(1);
        var negative = affixes(-1);
        var digits = cf.resolvedOptions().maximumFractionDigits;
        return [positive[0], positive[1], negative[0], negative[1], String(digits)].join(FIELD);
    })()
    """,
)

private const val DEFAULT_GROUP_SIZE: Int = 3
private const val DEFAULT_FRACTION_DIGITS: Int = 2
