package dev.ynagai.a2ui.material3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The calendar arithmetic behind `DateTimeInput`.
 *
 * Worth its own test because it is the one piece of this module that is arithmetic rather than
 * drawing: the two conversions are inverses, and an off-by-one in either would show up as a field
 * that reads back a different day than the one the user picked -- a mistake a rendering test would
 * not notice, because the field would still look right.
 */
class Iso8601Test {
    @Test
    fun the_epoch_is_the_first_of_january_nineteen_seventy() {
        assertEquals("1970-01-01", Iso8601.date(0))
        assertEquals(0L, Iso8601.epochDay("1970-01-01"))
    }

    @Test
    fun the_two_conversions_are_inverses_across_a_century() {
        // Every day from 1970 through 2069, which covers the leap rules that differ: the ordinary
        // four-year one, and 2000, which is a leap year only because of the four-hundred-year rule.
        var day = 0L
        while (day < 36_524L) {
            val text = Iso8601.date(day)
            assertEquals(day, Iso8601.epochDay(text), "$text should read back as day $day")
            day++
        }
    }

    @Test
    fun a_day_before_the_epoch_still_converts() {
        assertEquals("1969-12-31", Iso8601.date(-1))
        assertEquals(-1L, Iso8601.epochDay("1969-12-31"))
        assertEquals("1900-01-01", Iso8601.date(Iso8601.epochDay("1900-01-01")!!))
    }

    @Test
    fun the_leap_day_lands_where_it_should() {
        // 2000 is a leap year, 1900 and 2100 are not -- the four-hundred-year rule and the
        // hundred-year one, which a naive `year % 4` gets wrong in opposite directions.
        assertEquals("2000-02-29", Iso8601.date(Iso8601.epochDay("2000-02-29")!!))
        assertEquals(1L, Iso8601.epochDay("2000-03-01")!! - Iso8601.epochDay("2000-02-29")!!)
        assertEquals(1L, Iso8601.epochDay("1900-03-01")!! - Iso8601.epochDay("1900-02-28")!!)
        assertEquals(1L, Iso8601.epochDay("2100-03-01")!! - Iso8601.epochDay("2100-02-28")!!)
    }

    @Test
    fun a_date_is_read_out_of_whatever_follows_it() {
        // The three shapes the catalog's `date`, `date-time` and an agent's own `value` can take.
        // The trailing offset is deliberately not applied -- see [Iso8601].
        val expected = Iso8601.epochDay("2026-08-30")
        assertEquals(expected, Iso8601.epochDay("2026-08-30T09:15"))
        assertEquals(expected, Iso8601.epochDay("2026-08-30T09:15:00Z"))
        assertEquals(expected, Iso8601.epochDay("2026-08-30T09:15:00+09:00"))
    }

    @Test
    fun a_string_with_no_date_in_it_is_null_rather_than_a_guess() {
        for (text in listOf("", "09:15", "not a date", "26-08-30", "2026", "2026-13-01", "2026-08-32")) {
            assertNull(Iso8601.epochDay(text), "`$text` should not read as a date")
        }
    }

    @Test
    fun a_time_is_read_from_either_a_bare_clock_or_a_date_time() {
        assertEquals(9 to 15, Iso8601.hourMinute("09:15"))
        assertEquals(9 to 15, Iso8601.hourMinute("2026-08-30T09:15"))
        assertEquals(9 to 15, Iso8601.hourMinute("2026-08-30T09:15:30Z"))
        assertEquals(0 to 0, Iso8601.hourMinute("00:00"))
        assertEquals(23 to 59, Iso8601.hourMinute("23:59"))
    }

    @Test
    fun a_string_with_no_time_in_it_is_null() {
        for (text in listOf("", "2026-08-30", "24:00", "12:60", "noon")) {
            assertNull(Iso8601.hourMinute(text), "`$text` should not read as a time")
        }
    }

    @Test
    fun the_two_halves_combine_into_the_shape_the_catalog_allows() {
        val day = Iso8601.epochDay("2026-08-30")
        // Both halves, which is what `enableDate` and `enableTime` together ask for.
        assertEquals("2026-08-30T09:15", Iso8601.combine(day, "09:15"))
        // Either alone -- the catalog types `value` as a `date`, a `time` or a `date-time`.
        assertEquals("2026-08-30", Iso8601.combine(day, null))
        assertEquals("09:15", Iso8601.combine(null, "09:15"))
        // Neither: nothing to write, rather than an empty string that would read as an answer.
        assertNull(Iso8601.combine(null, null))
    }

    @Test
    fun a_combined_value_reads_back_as_the_two_halves_it_was_made_of() {
        // The round trip a field makes when the agent echoes back what the user picked. A leap day
        // and a two-digit-padded time, because both are where a naive implementation loses a digit.
        val written = Iso8601.combine(Iso8601.epochDay("2024-02-29"), Iso8601.time(23, 5))
        assertEquals("2024-02-29T23:05", written)
        assertEquals(Iso8601.epochDay("2024-02-29"), Iso8601.epochDay(written!!))
        assertEquals(23 to 5, Iso8601.hourMinute(written))
    }

    @Test
    fun a_time_is_padded_to_two_digits() {
        assertEquals("09:05", Iso8601.time(9, 5))
        assertEquals("00:00", Iso8601.time(0, 0))
        assertEquals("23:59", Iso8601.time(23, 59))
    }
}
