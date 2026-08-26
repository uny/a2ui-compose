package dev.ynagai.a2ui.core.function

/**
 * A civil date and time of day in UTC, decomposed from an epoch-millisecond instant.
 *
 * Kotlin's standard library has no calendar, and `kotlinx-datetime` is not a dependency of this
 * module: the only calendar arithmetic the basic catalog needs is this decomposition, and taking a
 * dependency for it would also pull in a time-zone database that [FallbackLocaleFormatter]
 * deliberately does not consult.
 */
internal data class CivilDateTime(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
    val milli: Int,
    /** 0 is Sunday, matching the order [DAY_NAMES] is written in. */
    val dayOfWeek: Int,
) {
    internal companion object {
        /**
         * [epochMillis] as a UTC civil date and time.
         *
         * The days-to-civil step is Howard Hinnant's `civil_from_days`, shifted to an era starting
         * on 0000-03-01 so that the leap day falls at the end of a year and the month lengths
         * become a single linear formula. Floor division is used throughout because instants
         * before 1970 are negative and truncating division would round them towards the epoch,
         * putting 1969-12-31T23:00Z on the wrong day.
         */
        fun ofEpochMillis(epochMillis: Long): CivilDateTime {
            val days = epochMillis.floorDiv(MILLIS_PER_DAY)
            val millisOfDay = epochMillis.mod(MILLIS_PER_DAY)

            val shifted = days + DAYS_FROM_ERA_TO_EPOCH
            val era = shifted.floorDiv(DAYS_PER_ERA)
            val dayOfEra = shifted - era * DAYS_PER_ERA
            val yearOfEra =
                (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146096) / 365
            val eraYear = yearOfEra + era * 400
            val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
            val monthIndex = (5 * dayOfYear + 2) / 153
            val day = dayOfYear - (153 * monthIndex + 2) / 5 + 1
            val month = if (monthIndex < 10) monthIndex + 3 else monthIndex - 9
            val year = if (month <= 2) eraYear + 1 else eraYear

            return CivilDateTime(
                year = year.toInt(),
                month = month.toInt(),
                day = day.toInt(),
                hour = (millisOfDay / MILLIS_PER_HOUR).toInt(),
                minute = (millisOfDay / MILLIS_PER_MINUTE % MINUTES_PER_HOUR).toInt(),
                second = (millisOfDay / MILLIS_PER_SECOND % SECONDS_PER_MINUTE).toInt(),
                milli = (millisOfDay % MILLIS_PER_SECOND).toInt(),
                // 1970-01-01 was a Thursday, which is index 4 counting from Sunday.
                dayOfWeek = (days + 4).mod(DAYS_PER_WEEK).toInt(),
            )
        }

        /** The inverse of the decomposition above, for parsing. */
        fun toEpochMillis(
            year: Int,
            month: Int,
            day: Int,
            hour: Int,
            minute: Int,
            second: Int,
            milli: Int,
        ): Long {
            val y = (if (month <= 2) year - 1 else year).toLong()
            val era = y.floorDiv(400)
            val yearOfEra = y - era * 400
            val monthIndex = if (month > 2) month - 3 else month + 9
            val dayOfYear = (153 * monthIndex + 2) / 5 + day - 1
            val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
            val days = era * DAYS_PER_ERA + dayOfEra - DAYS_FROM_ERA_TO_EPOCH
            return days * MILLIS_PER_DAY +
                hour * MILLIS_PER_HOUR +
                minute * MILLIS_PER_MINUTE +
                second * MILLIS_PER_SECOND +
                milli
        }
    }
}

private const val MILLIS_PER_SECOND: Long = 1000L
private const val SECONDS_PER_MINUTE: Long = 60L
private const val MINUTES_PER_HOUR: Long = 60L
private const val MILLIS_PER_MINUTE: Long = MILLIS_PER_SECOND * SECONDS_PER_MINUTE
private const val MILLIS_PER_HOUR: Long = MILLIS_PER_MINUTE * MINUTES_PER_HOUR
private const val MILLIS_PER_DAY: Long = MILLIS_PER_HOUR * 24L
private const val DAYS_PER_WEEK: Long = 7L
private const val DAYS_PER_ERA: Long = 146097L

/** Days from 0000-03-01 to 1970-01-01, the shift that puts the leap day last in the year. */
private const val DAYS_FROM_ERA_TO_EPOCH: Long = 719468L

private val MONTH_NAMES = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

private val DAY_NAMES = listOf(
    "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday",
)

private const val ABBREVIATION_LENGTH: Int = 3
private const val WIDE_COUNT: Int = 4
private const val TWO_DIGIT_COUNT: Int = 2
private const val MILLI_DIGITS: Int = 3
private const val HOURS_PER_HALF_DAY: Int = 12

/**
 * [epochMillis] rendered with the Unicode TR35 pattern [pattern], in UTC and with English names.
 *
 * The subset implemented is the one the catalog's own token reference documents — `y M d E h H m
 * s a` — plus `S` and TR35's single-quote literals, where `''` stands for one quote. Any other ASCII
 * letter raises rather than passing through: TR35 reserves the whole letter range for future
 * fields, so emitting an unrecognised one literally would silently produce a date string with a
 * stray letter in it, and a renderer would have no way to tell that from an intended literal.
 */
internal fun formatUtcPattern(epochMillis: Long, pattern: String): String {
    val at = CivilDateTime.ofEpochMillis(epochMillis)
    val out = StringBuilder(pattern.length)
    var i = 0
    while (i < pattern.length) {
        val c = pattern[i]
        when {
            c == '\'' -> i = appendQuoted(pattern, i, out)
            c in 'a'..'z' || c in 'A'..'Z' -> {
                var end = i
                while (end < pattern.length && pattern[end] == c) end++
                out.append(field(c, end - i, at))
                i = end
            }
            else -> {
                out.append(c)
                i++
            }
        }
    }
    return out.toString()
}

/** Appends the literal run starting at the quote at [start]; returns the index just past it. */
private fun appendQuoted(pattern: String, start: Int, out: StringBuilder): Int {
    if (start + 1 < pattern.length && pattern[start + 1] == '\'') {
        out.append('\'')
        return start + 2
    }
    var i = start + 1
    while (i < pattern.length) {
        if (pattern[i] == '\'') {
            if (i + 1 < pattern.length && pattern[i + 1] == '\'') {
                out.append('\'')
                i += 2
                continue
            }
            return i + 1
        }
        out.append(pattern[i])
        i++
    }
    throw A2uiFunctionException("formatDate: pattern `$pattern` has an unterminated quoted literal.")
}

private fun field(letter: Char, count: Int, at: CivilDateTime): String = when (letter) {
    // TR35 gives `yy` the special meaning "the low two digits", not "pad to two".
    'y' -> if (count == TWO_DIGIT_COUNT) pad(at.year.mod(100), TWO_DIGIT_COUNT)
    else pad(at.year, count)
    'M' -> name(MONTH_NAMES[at.month - 1], count) ?: pad(at.month, count)
    'd' -> pad(at.day, count)
    'E' -> name(DAY_NAMES[at.dayOfWeek], if (count < ABBREVIATION_LENGTH) ABBREVIATION_LENGTH else count)
        ?: DAY_NAMES[at.dayOfWeek].take(ABBREVIATION_LENGTH)
    'h' -> pad(if (at.hour % HOURS_PER_HALF_DAY == 0) HOURS_PER_HALF_DAY else at.hour % HOURS_PER_HALF_DAY, count)
    'H' -> pad(at.hour, count)
    'm' -> pad(at.minute, count)
    's' -> pad(at.second, count)
    // TR35 reads `S` as the leading digits of the fraction of a second, so the value is padded
    // to its own three-digit width first and only then truncated: 5 milliseconds is `.005`, whose
    // first digit is `0`, not `5`.
    'S' -> at.milli.toString().padStart(MILLI_DIGITS, '0')
        .let { if (count <= MILLI_DIGITS) it.take(count) else it.padEnd(count, '0') }
    'a' -> if (at.hour < HOURS_PER_HALF_DAY) "AM" else "PM"
    else -> throw A2uiFunctionException(
        "formatDate: `$letter` is not a supported TR35 pattern letter " +
            "(supported: y M d E h H m s S a; quote it as '$letter' to emit it literally).",
    )
}

/** The textual form for a field of width [count], or null when [count] asks for digits. */
private fun name(full: String, count: Int): String? = when {
    count < ABBREVIATION_LENGTH -> null
    count == ABBREVIATION_LENGTH -> full.take(ABBREVIATION_LENGTH)
    count == WIDE_COUNT -> full
    else -> full.take(1)
}

private fun pad(value: Int, width: Int): String {
    val digits = if (value < 0) (-value).toString() else value.toString()
    val padded = digits.padStart(width, '0')
    return if (value < 0) "-$padded" else padded
}

/**
 * [text] read as an ISO 8601 date or date-time, in milliseconds from the epoch, or null when it is
 * not one.
 *
 * A date with no time of day is midnight, and a date-time with no offset is read as UTC — the same
 * reading [FallbackLocaleFormatter] formats back in, so a value that comes in without an offset
 * comes out unshifted rather than moved by whatever zone the device happens to be in.
 */
internal fun parseIso8601(text: String): Long? {
    val s = text.trim()
    if (s.length < DATE_LENGTH) return null
    val year = s.substring(0, 4).toIntOrNull() ?: return null
    if (s[4] != '-' || s[7] != '-') return null
    val month = s.substring(5, 7).toIntOrNull()?.takeIf { it in 1..12 } ?: return null
    val day = s.substring(8, 10).toIntOrNull()?.takeIf { it in 1..31 } ?: return null
    if (s.length == DATE_LENGTH) return CivilDateTime.toEpochMillis(year, month, day, 0, 0, 0, 0)

    if (s[10] != 'T' && s[10] != 't' && s[10] != ' ') return null
    var rest = s.substring(11)

    var offsetMillis = 0L
    if (rest.endsWith("Z") || rest.endsWith("z")) {
        rest = rest.dropLast(1)
    } else {
        val sign = rest.indexOfLast { it == '+' || it == '-' }
        if (sign > 0) {
            val offset = parseOffset(rest.substring(sign)) ?: return null
            offsetMillis = offset
            rest = rest.substring(0, sign)
        }
    }

    if (rest.length < TIME_MIN_LENGTH || rest[2] != ':') return null
    val hour = rest.substring(0, 2).toIntOrNull()?.takeIf { it in 0..23 } ?: return null
    val minute = rest.substring(3, 5).toIntOrNull()?.takeIf { it in 0..59 } ?: return null
    var second = 0
    var milli = 0
    if (rest.length > TIME_MIN_LENGTH) {
        // The length is read before the substring, not only the separator: `09:30:` and `09:30:0`
        // both put a `:` at index 5 while being too short to hold the field, and `substring(6, 8)`
        // on them raises `IndexOutOfBoundsException` — which is neither the null this function
        // documents nor anything the evaluator's caller is written to catch.
        if (rest.length < SECONDS_LENGTH || rest[5] != ':') return null
        second = rest.substring(6, 8).toIntOrNull()?.takeIf { it in 0..60 } ?: return null
        // A leap second is folded onto :59 rather than rejected; the epoch scale has no room for it.
        if (second == 60) second = 59
        if (rest.length > SECONDS_LENGTH) {
            if (rest[8] != '.' && rest[8] != ',') return null
            val fraction = rest.substring(9)
            if (fraction.isEmpty() || !fraction.all { it.isDigit() }) return null
            milli = fraction.take(3).padEnd(3, '0').toInt()
        }
    }
    return CivilDateTime.toEpochMillis(year, month, day, hour, minute, second, milli) - offsetMillis
}

/** `+HH:mm`, `+HHmm` or `+HH` as milliseconds to subtract from the civil reading. */
private fun parseOffset(text: String): Long? {
    val sign = if (text[0] == '-') -1L else 1L
    val digits = text.substring(1).filter { it != ':' }
    if (digits.length != 2 && digits.length != 4) return null
    if (!digits.all { it.isDigit() }) return null
    val hours = digits.substring(0, 2).toInt()
    val minutes = if (digits.length == 4) digits.substring(2, 4).toInt() else 0
    if (hours > 23 || minutes > 59) return null
    return sign * (hours * MILLIS_PER_HOUR + minutes * MILLIS_PER_MINUTE)
}

private const val DATE_LENGTH: Int = 10
private const val TIME_MIN_LENGTH: Int = 5
private const val SECONDS_LENGTH: Int = 8
