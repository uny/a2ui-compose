package dev.ynagai.a2ui.material3

/**
 * The calendar arithmetic `DateTimeInput` needs, and nothing else.
 *
 * **Written here rather than taken from a date library.** This module's only date question is the
 * one Material's pickers ask: they speak in days-since-epoch and in hour/minute pairs, the
 * catalog speaks in ISO 8601 strings, and something has to convert. `kotlinx-datetime` would
 * answer it, but it would also put a second multiplatform runtime behind a design-system adapter
 * for two functions that fit on a screen -- the same trade `Image` refused when it declined to
 * carry an HTTP stack, and `Icon` when it drew its own glyphs rather than pull in a megabyte of
 * them.
 *
 * The two conversions are Howard Hinnant's `civil_from_days` and `days_from_civil`, which are
 * exact for every proleptic Gregorian date and need no table. They are inverses of each other, and
 * [Iso8601Test] holds them to that over a range of years.
 *
 * **No time zone anywhere.** Material's `DatePickerState` reports a selection as UTC midnight, and
 * this file reads and writes it as such, so a date makes the round trip it was picked in. What the
 * catalog asks for is a calendar date and a wall-clock time; shifting either into a local zone
 * would need a zone database this module does not have, and would turn "the 3rd" into "the 2nd"
 * for a user west of the meridian.
 */
internal object Iso8601 {
    /** Milliseconds in a day -- the unit Material's date picker reports its selection in. */
    const val DAY_MILLIS: Long = 86_400_000L

    /** `yyyy-MM-dd` for a day count since 1970-01-01, negative counts included. */
    fun date(epochDay: Long): String {
        val (year, month, day) = civilFromDays(epochDay)
        return "${pad(year, 4)}-${pad(month, 2)}-${pad(day, 2)}"
    }

    /** `HH:mm` on a 24-hour clock. */
    fun time(hour: Int, minute: Int): String = "${pad(hour, 2)}:${pad(minute, 2)}"

    /**
     * What a `DateTimeInput` writes back, given whichever halves it collected.
     *
     * The three shapes the catalog's `value` is allowed to take, and the rule for choosing between
     * them: a date and a time combine into a `date-time`, and either alone stands on its own. Kept
     * apart from the dialogs that produce the two halves because this is the part with an answer to
     * check -- the plumbing above it has only a picture.
     */
    fun combine(epochDay: Long?, time: String?): String? = when {
        epochDay != null && time != null -> "${date(epochDay)}T$time"
        epochDay != null -> date(epochDay)
        else -> time
    }

    /**
     * The date part of an ISO 8601 string as a day count, or null when there is not one.
     *
     * Deliberately forgiving about what follows: `2026-08-30`, `2026-08-30T09:15`, and
     * `2026-08-30T09:15:00Z` all yield the same day. The catalog's `min`/`max` are typed as any of
     * `date`, `time` or `date-time`, and a `value` written by an agent may carry an offset this
     * module has no way to apply -- so the leading date is read and the rest is left alone rather
     * than the whole string being refused.
     */
    fun epochDay(value: String): Long? {
        val year = value.substringBefore('-').toIntOrNull()?.takeIf { value.indexOf('-') >= 4 } ?: return null
        val rest = value.substringAfter('-', "")
        val month = rest.substringBefore('-').toIntOrNull()?.takeIf { it in 1..12 } ?: return null
        val day = rest.substringAfter('-', "").take(2).toIntOrNull()?.takeIf { it in 1..31 } ?: return null
        // The round trip is the calendar check. `1..31` lets February the 31st through, and
        // `daysFromCivil` is happy to convert it -- into March the 3rd, which a field would then
        // display and write back in place of what the agent sent. Converting and converting back
        // rejects exactly the days the month does not have, leap years included, without a table.
        return daysFromCivil(year, month, day).takeIf { civilFromDays(it) == Triple(year, month, day) }
    }

    /** The Gregorian year a day count falls in -- what a date picker's `yearRange` is measured in. */
    fun year(epochDay: Long): Int = civilFromDays(epochDay).first

    /** The `HH:mm` in an ISO 8601 string as an hour/minute pair, or null when there is not one. */
    fun hourMinute(value: String): Pair<Int, Int>? {
        // After the `T` for a date-time, and from the start for a bare time.
        val clock = if ('T' in value) value.substringAfter('T') else value
        val hour = clock.substringBefore(':').toIntOrNull()?.takeIf { it in 0..23 } ?: return null
        val minute = clock.substringAfter(':', "").take(2).toIntOrNull()?.takeIf { it in 0..59 } ?: return null
        return hour to minute
    }

    /** Zero-padded to [width], with a leading `-` kept outside the padding. */
    private fun pad(value: Int, width: Int): String {
        val text = value.toString().removePrefix("-")
        return (if (value < 0) "-" else "") + text.padStart(width, '0')
    }

    /**
     * `(year, month, day)` for a day count since 1970-01-01.
     *
     * Hinnant's algorithm: shift the epoch to 0000-03-01 so that the leap day lands at the end of
     * the year and the month lengths become a single linear formula, do the arithmetic in 400-year
     * eras, then shift back.
     */
    private fun civilFromDays(epochDay: Long): Triple<Int, Int, Int> {
        val z = epochDay + 719_468
        val era = (if (z >= 0) z else z - 146_096) / 146_097
        val dayOfEra = z - era * 146_097
        val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        val year = yearOfEra + era * 400
        val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        val mp = (5 * dayOfYear + 2) / 153
        val day = (dayOfYear - (153 * mp + 2) / 5 + 1).toInt()
        val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
        return Triple((if (month <= 2) year + 1 else year).toInt(), month, day)
    }

    /** The inverse of [civilFromDays]. */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yearOfEra = y - era * 400
        val mp = if (month > 2) month - 3 else month + 9
        val dayOfYear = (153 * mp + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097 + dayOfEra - 719_468
    }
}
