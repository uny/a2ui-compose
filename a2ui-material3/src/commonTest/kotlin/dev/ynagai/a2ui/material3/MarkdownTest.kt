package dev.ynagai.a2ui.material3

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a `Text` makes of the Markdown the specification's corpus actually contains.
 *
 * Seventeen of the forty-three examples put Markdown in a `Text`, and the simplest example in the
 * whole corpus is one heading and nothing else -- so "the markers are gone and the emphasis is
 * real" is a claim about whether this renderer draws the corpus at all, not about polish.
 */
class MarkdownTest {
    @Test
    fun a_heading_loses_its_marker_and_gains_its_size() {
        val text = markdownText("# Hello, Minimal Catalog!")
        assertEquals("Hello, Minimal Catalog!", text.text)
        val style = text.spanStyles.single()
        assertEquals(FontWeight.Bold, style.item.fontWeight)
        assertEquals(2.0.em, style.item.fontSize)
        assertEquals(0, style.start)
        assertEquals(text.text.length, style.end)
    }

    @Test
    fun heading_levels_get_smaller() {
        val sizes = (1..6).map { level ->
            markdownText("#".repeat(level) + " x").spanStyles.single().item.fontSize
        }
        assertEquals(2.0.em, sizes.first())
        // By value: `TextUnit` is not `Comparable`, and every one of these is in `em`.
        val values = sizes.map { it.value }
        assertEquals(values.sortedDescending(), values, "deeper headings should not be larger")
    }

    @Test
    fun a_hash_without_a_space_is_not_a_heading() {
        // `#hashtag` is text, and so is a run of seven hashes -- the sixth is not followed by a
        // space, so there is no level left to read. Both are CommonMark's rules, and both appear
        // in ordinary prose an agent might send.
        for (source in listOf("#hashtag", "####### seven", "#")) {
            val text = markdownText(source)
            assertEquals(source, text.text, "`$source` should render as itself")
            assertTrue(text.spanStyles.isEmpty(), "`$source` should carry no heading style")
        }
    }

    @Test
    fun inline_emphasis_is_rendered_rather_than_stripped() {
        val text = markdownText("This is **bold** text and *italic* text.")
        assertEquals("This is bold text and italic text.", text.text)
        val bold = text.spanStyles.single { it.item.fontWeight == FontWeight.Bold }
        assertEquals("bold", text.text.substring(bold.start, bold.end))
        val italic = text.spanStyles.single { it.item.fontStyle == FontStyle.Italic }
        assertEquals("italic", text.text.substring(italic.start, italic.end))
    }

    @Test
    fun emphasis_nests() {
        val text = markdownText("**bold with *italic* inside**")
        assertEquals("bold with italic inside", text.text)
        assertTrue(text.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        val italic = text.spanStyles.single { it.item.fontStyle == FontStyle.Italic }
        assertEquals("italic", text.text.substring(italic.start, italic.end))
    }

    @Test
    fun a_code_span_is_literal_inside() {
        // No emphasis is read inside code, which is the whole point of a code span: `**` there is
        // two asterisks a user wanted to see.
        val text = markdownText("call `a**b**c` please")
        assertEquals("call a**b**c please", text.text)
        assertTrue(text.spanStyles.none { it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun a_delimiter_with_no_closer_is_the_character_it_is() {
        for (source in listOf("2 * 3 * 4", "a ** b", "snake_case_name", "unclosed `code")) {
            assertEquals(source, markdownText(source).text, "`$source` should survive verbatim")
        }
    }

    @Test
    fun an_escape_produces_the_character_it_escapes() {
        assertEquals("*not emphasis*", markdownText("\\*not emphasis\\*").text)
        assertEquals("# not a heading", markdownText("\\# not a heading").text)
    }

    @Test
    fun a_link_is_reduced_to_its_label() {
        // The specification's Markdown is "without HTML, images, or links". Making one live would
        // hand an agent a way to open a URL around `openUrl` and its user-gesture rule.
        assertEquals(
            "Link to Google and more",
            markdownText("[Link to Google](https://google.com) and more").text,
        )
        assertEquals("[not a link", markdownText("[not a link").text)
    }

    @Test
    fun lines_are_kept() {
        val text = markdownText("# Heading\n\nBody **here**")
        assertEquals("Heading\n\nBody here", text.text)
    }

    @Test
    fun a_text_too_large_to_parse_is_rendered_as_itself() {
        // The bound exists because every construct here is delimited and an unmatched delimiter
        // costs a scan for a closer that is not there. The agent chooses this string, so the
        // degradation has to be to the raw text rather than to a stall.
        //
        // The markup at the end is what makes this test able to fail. A string of bare asterisks
        // parses to itself either way -- every pair comes back out as the literal characters -- so
        // a fixture without it passed just as happily with the size cap deleted.
        val source = "x".repeat(MAX_MARKDOWN_INPUT) + "**bold**"
        val text = markdownText(source)
        assertEquals(source, text.text, "past the cap the source is not parsed at all")
        assertTrue(text.spanStyles.isEmpty())
        // And one character under it, the same string is parsed.
        val parsed = markdownText("x".repeat(MAX_MARKDOWN_INPUT - "**bold**".length) + "**bold**")
        assertTrue(parsed.spanStyles.isNotEmpty(), "under the cap the markup should still be read")
    }

    @Test
    fun a_run_of_unmatched_openers_exhausts_the_budget_rather_than_the_machine() {
        // The shape the scan budget exists for, which a run of `*` is not: every `[` starts a scan
        // to the end of the string looking for a `]` that is not there, so an unbudgeted parse is
        // quadratic -- 8,000 openers over 8,000 characters. The assertion is that the text comes
        // back whole; that it comes back at all is the other half.
        val source = "[".repeat(8_000)
        assertEquals(source, markdownText(source).text)
    }

    @Test
    fun nesting_past_the_bound_is_emitted_as_characters() {
        // `MAX_NESTING` stops emphasis from recursing without limit on agent-chosen text. Past it
        // the delimiters are the characters they are, so the text survives whole either way -- and
        // the styles are what say the bound is still there.
        val source = "*".repeat(MAX_NESTING + 4) + "x" + "*".repeat(MAX_NESTING + 4)
        val text = markdownText(source)
        assertTrue(text.text.contains("x"), "the content survives")
        assertTrue(
            text.spanStyles.size <= MAX_NESTING,
            "no more spans than the nesting bound allows: ${text.spanStyles.size}",
        )
    }

    @Test
    fun a_long_run_of_unmatched_delimiters_still_finishes() {
        // Under the size cap, so this one is parsed rather than waved through -- what bounds it is
        // the scan budget. The assertion is that every character survives; that it returns at all
        // is the other half.
        val source = "*".repeat(8_000)
        assertEquals(source, markdownText(source).text)
    }

    @Test
    fun a_triple_run_is_bold_and_italic_at_once() {
        // Read before `**`, because `indexOf("**")` inside a `***` run matches the run's own tail:
        // the span closed a character short and `***important***` rendered as `*important*` with
        // the markers showing.
        for (source in listOf("***important***", "___important___")) {
            val text = markdownText(source)
            assertEquals("important", text.text, "`$source` should lose its markers")
            val style = text.spanStyles.single().item
            assertEquals(FontWeight.Bold, style.fontWeight, "`$source` should be bold")
            assertEquals(FontStyle.Italic, style.fontStyle, "`$source` should be italic")
        }
        // And a double run still reads as bold alone.
        assertEquals(null, markdownText("**b**").spanStyles.single().item.fontStyle)
    }

    @Test
    fun the_other_delimiters_are_read_too() {
        // `__bold__` and `~~strikethrough~~` are both named in the header comment as covered, and
        // neither had a fixture -- so either branch could be deleted with the suite still green.
        assertEquals(FontWeight.Bold, markdownText("__b__").spanStyles.single().item.fontWeight)
        assertEquals(
            TextDecoration.LineThrough,
            markdownText("~~s~~").spanStyles.single().item.textDecoration,
        )
    }

    @Test
    fun an_empty_span_keeps_its_characters() {
        // Both delimiters, for the same reason: consuming a pair that styles nothing deletes two
        // characters the agent sent, and a renderer that quietly drops input is worse than one
        // that shows a marker.
        for (source in listOf("a **** b", "a `` b", "a ____ b")) {
            assertEquals(source, markdownText(source).text, "`$source` should keep every character")
        }
    }

    @Test
    fun an_underscore_run_inside_a_word_closes_nothing() {
        // `canClose`'s underscore rule, which `snake_case_name` does not reach -- that one is
        // refused by `canOpen`. Here the opener is legal and only the closer's rule stands between
        // an identifier and an italicised fragment of one.
        assertEquals("_foo_bar", markdownText("_foo_bar").text)
        assertTrue(markdownText("_foo_bar").spanStyles.isEmpty())
    }
}
