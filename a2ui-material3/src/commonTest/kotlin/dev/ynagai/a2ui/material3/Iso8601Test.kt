package dev.ynagai.a2ui.material3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun a_year_this_module_could_not_spell_back_is_refused() {
        // Everything downstream treats the day count as a magnitude. `DateTimeInput` multiplies it
        // by `DAY_MILLIS` to seed the picker -- which overflows `Long` past about year 292,000,000
        // and wraps to an unrelated date the user can then confirm over the agent's value -- and it
        // widens the picker's `yearRange` to span the year named, which Material sizes a list from.
        for (text in listOf("300000000-01-01", "2147483647-12-31", "10000-01-01", "0000-01-01")) {
            assertNull(Iso8601.epochDay(text), "`$text` is outside the four-digit year")
        }
        // And the ends of the range still read, so the bound is not off by one.
        assertEquals("0001-01-01", Iso8601.date(Iso8601.epochDay("0001-01-01")!!))
        assertEquals("9999-12-31", Iso8601.date(Iso8601.epochDay("9999-12-31")!!))
        // The invariant the bound exists for: what the parser accepts, the formatter can spell.
        val widest = Iso8601.epochDay("9999-12-31")!!
        assertTrue(
            widest * Iso8601.DAY_MILLIS / Iso8601.DAY_MILLIS == widest,
            "the widest accepted day must survive the millis conversion the picker needs",
        )
    }

    @Test
    fun a_day_the_month_does_not_have_is_refused_rather_than_rolled_forward() {
        // A day in `1..31` still has to exist in *its* month. Without the round-trip check
        // `2026-02-31` converts happily -- to March the 3rd, which a field would then display and
        // write back in place of what the agent sent.
        for (text in listOf("2026-02-30", "2026-02-31", "2026-04-31", "2026-06-31", "2026-11-31")) {
            assertNull(Iso8601.epochDay(text), "`$text` is not a day that exists")
        }
        // February the 29th is the case the check must not over-reject: real in 2024, not in 2026.
        assertNull(Iso8601.epochDay("2026-02-29"))
        assertEquals("2024-02-29", Iso8601.date(Iso8601.epochDay("2024-02-29")!!))
        // And the last day of every month of an ordinary year still reads.
        val lengths = listOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        lengths.forEachIndexed { index, length ->
            val month = (index + 1).toString().padStart(2, '0')
            val text = "2026-$month-$length"
            assertEquals(text, Iso8601.date(Iso8601.epochDay(text)!!), "`$text` should read")
        }
    }

    @Test
    fun a_year_is_read_back_off_a_day_count() {
        // What a date picker's `yearRange` is measured in -- a range that does not cover the
        // initial selection makes `DatePickerState` raise from inside the composition.
        assertEquals(1970, Iso8601.year(0))
        assertEquals(1969, Iso8601.year(-1))
        assertEquals(1890, Iso8601.year(Iso8601.epochDay("1890-07-04")!!))
        assertEquals(2150, Iso8601.year(Iso8601.epochDay("2150-12-31")!!))
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
    fun the_picker_s_year_range_widens_to_cover_what_the_payload_names() {
        // A picker cannot scroll outside its `yearRange`, and Material's default is 1900..2100 --
        // so a field bound to a date of birth in 1890 would show a value its own picker could not
        // reach. Widened rather than replaced: narrowing the range to whatever one payload happens
        // to name would take away every other year the user might want.
        assertEquals(1900..2100, yearsSpanning(null, null, null))
        assertEquals(1900..2100, yearsSpanning(Iso8601.epochDay("2026-08-30")))
        assertEquals(1890..2100, yearsSpanning(Iso8601.epochDay("1890-07-04")))
        assertEquals(1900..2150, yearsSpanning(null, null, Iso8601.epochDay("2150-12-31")))
        assertEquals(
            1890..2150,
            yearsSpanning(Iso8601.epochDay("1890-07-04"), null, Iso8601.epochDay("2150-12-31")),
        )
    }

    @Test
    fun a_time_is_padded_to_two_digits() {
        assertEquals("09:05", Iso8601.time(9, 5))
        assertEquals("00:00", Iso8601.time(0, 0))
        assertEquals("23:59", Iso8601.time(23, 59))
    }
}
