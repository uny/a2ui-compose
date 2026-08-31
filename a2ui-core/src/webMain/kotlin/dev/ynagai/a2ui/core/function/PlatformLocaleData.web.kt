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

/** How many fields [intlSymbols] packs. Read positionally below, so it is checked before that. */
private const val SYMBOL_FIELDS: Int = 13

private fun unpackSymbols(bundle: String): LocaleSymbols {
    val f = bundle.split(FIELD)
    // Positional reads follow. A bundle of the wrong arity means the bridge and this function have
    // drifted apart, and without this the drift arrives as an `IndexOutOfBoundsException` raised
    // inside a `lazy` -- which is neither a failure this module models nor one its message names.
    require(f.size == SYMBOL_FIELDS) {
        "Intl returned ${f.size} symbol fields, expected $SYMBOL_FIELDS."
    }
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
        // A tag ECMA-402 reads as structurally ill-formed raises a `RangeError` out of every
        // `Intl` constructor below -- `en_US`, the identifier form Apple's `NSLocale` takes and
        // this module's own `languageSubtag` is written to strip, is one of them. The other five
        // targets degrade for a tag they cannot use (`Locale.forLanguageTag` yields `und`), and
        // `localeFormatter`'s contract is that degradation. Unguarded, the throw surfaced from
        // inside `symbols`' `lazy` on the first `formatNumber` rather than at construction, and
        // took the surface down on the two web targets alone.
        try {
            new Intl.NumberFormat(loc);
        } catch (e) {
            // `_` for `-` before giving up. `ru_RU` is ICU's identifier form -- the one `NSLocale`
            // takes and the one `CldrPluralRules.languageSubtag` is written to strip -- and
            // ECMA-402 reads it as ill-formed. Dropping it outright answered in the runtime's
            // default locale, which is a different wrong answer from the JVM's root and from
            // Apple's `ru-RU`; normalising first makes all three say `ru-RU`.
            loc = loc.replace(/_/g, '-');
            try {
                new Intl.NumberFormat(loc);
            } catch (e2) {
                loc = undefined;
            }
        }
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
        // Digits are counted by code point, not by UTF-16 length: a numbering system in the
        // supplementary plane (`-u-nu-mathbold`) writes each digit as a surrogate pair, and
        // `.length` then reported a three-digit group as six.
        function digits(run) {
            return Array.from(run).length;
        }
        var sizes = [];
        if (ints.length >= 2) {
            sizes.push(digits(ints[ints.length - 1]));
            if (ints.length >= 3 && digits(ints[ints.length - 2]) !== sizes[0]) {
                sizes.push(digits(ints[ints.length - 2]));
            }
        } else {
            sizes.push(3);
        }

        // Every date probe below pins `calendar: 'gregory'`. A locale's default calendar is not
        // always the Gregorian one -- `fa-IR` resolves to `persian` -- and the pattern engine that
        // consumes these names is proleptic Gregorian, so an unpinned probe filed a Persian month
        // under a Gregorian index: `fa-IR`'s January read `دی`, its own tenth month, where every
        // other target reads `ژانویه`.
        // `name` rather than `field`, which is the function just below: a parameter of that name
        // would shadow it here, and the next edit to this helper would reach for the wrong one.
        function options(width, name, withDay) {
            var o = { calendar: 'gregory', timeZone: 'UTC' };
            o[name] = width;
            // A second field is what asks the locale for its FORMAT names rather than its
            // stand-alone ones -- see `contextual` below.
            if (withDay) o.day = 'numeric';
            if (withDay && name === 'weekday') o.month = 'numeric';
            return o;
        }
        function field(date, opts) {
            // Bound to a variable rather than called on the constructor expression: the compiler
            // that inlines this reads `new A.B(x).c(y)` as `new (A.B(x).c)(y)`, which throws.
            var f = new Intl.DateTimeFormat(loc, opts);
            return f.format(date);
        }
        // The *format* name -- the one `MMMM` and `EEEE` substitute, and the one the other five
        // targets read (`DateFormatSymbols.getMonths`, `NSDateFormatter.monthSymbols`). Asking
        // `Intl` for a month or a weekday with no other field gives the STAND-ALONE name instead,
        // and in an inflecting language those are different words: `ru` writes `август` alone but
        // `августа` inside a date, and `de` abbreviates Monday `Mo` alone and `Mo.` in one. So
        // `d MMMM` rendered `26 август` here and `26 августа` on every other target -- the
        // divergence this whole seam exists to remove.
        //
        // The format name is the labelled part of a format that carries a second field, except
        // where the locale carries the name in a literal beside a numeric part -- `ja` labels only
        // the `1` of `1月` the month, `zh` only the `8` of `八月`. A part holding no letter is that
        // case, and there the stand-alone string is the whole name. Measured against `java.text`
        // over 35 locales at both widths, this agrees on 814 of 840 month names and 477 of 490
        // weekday names, where the lone-field read agreed on 645 and 449; what remains is locales
        // whose two platforms disagree whichever way this is read.
        function contextual(date, width, name) {
            var alone = field(date, options(width, name, false));
            var f = new Intl.DateTimeFormat(loc, options(width, name, true));
            var ps = f.formatToParts(date);
            for (var i = 0; i < ps.length; i++) {
                if (ps[i].type === name) {
                    return /\p{L}/u.test(ps[i].value) ? ps[i].value : alone;
                }
            }
            return alone;
        }
        function months(width) {
            var out = [];
            for (var m = 0; m < 12; m++) {
                out.push(contextual(Date.UTC(2021, m, 15), width, 'month'));
            }
            return out.join(ITEM);
        }
        function weekdays(width) {
            var out = [];
            // 2021-08-01 was a Sunday, which is index 0 in `LocaleSymbols`.
            for (var d = 0; d < 7; d++) {
                out.push(contextual(Date.UTC(2021, 7, 1 + d), width, 'weekday'));
            }
            return out.join(ITEM);
        }
        function period(hour, fallback) {
            // Named `opts`, not `options`: that name is the helper above, and shadowing it here
            // would leave the next edit to this function silently reading an object.
            var opts = { hour: 'numeric', hour12: true, calendar: 'gregory', timeZone: 'UTC' };
            var f = new Intl.DateTimeFormat(loc, opts);
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
        // The same ill-formed-tag fallback `intlSymbols` makes, and for the same reason: without
        // it a bad tag lands in the catch below, which is the *unknown currency* degradation --
        // so `USD` in a locale this runtime cannot parse came back as `USD 1.00` rather than as
        // the default locale's `$1.00`, and the two failures were indistinguishable.
        try {
            new Intl.NumberFormat(loc);
        } catch (e) {
            // `_` for `-` before giving up. `ru_RU` is ICU's identifier form -- the one `NSLocale`
            // takes and the one `CldrPluralRules.languageSubtag` is written to strip -- and
            // ECMA-402 reads it as ill-formed. Dropping it outright answered in the runtime's
            // default locale, which is a different wrong answer from the JVM's root and from
            // Apple's `ru-RU`; normalising first makes all three say `ru-RU`.
            loc = loc.replace(/_/g, '-');
            try {
                new Intl.NumberFormat(loc);
            } catch (e2) {
                loc = undefined;
            }
        }
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
