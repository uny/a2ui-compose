package dev.ynagai.a2ui.material3.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The tree-to-block conversion, pinned where a wrong turn would still draw *something*.
 *
 * Each block kind has the one assertion that its mutation makes red: a table that dropped its
 * short row's padding, a list that forgot the number it started at, a fence that kept its
 * indent. The inline pins are the default renderer's subset, asserted again here because this
 * module replaces the default and a host that installs it should lose nothing.
 */
class MarkdownBlocksTest {
    @Test
    fun a_paragraph_keeps_its_emphasis_and_loses_its_markers() {
        val text = paragraph("Para *i* **b** ~~s~~ `c` plain")
        assertEquals("Para i b s c plain", text.text)
        assertEquals("i", text.spanned { it.fontStyle == FontStyle.Italic })
        assertEquals("b", text.spanned { it.fontWeight == FontWeight.Bold })
        assertEquals("s", text.spanned { it.textDecoration == TextDecoration.LineThrough })
        assertEquals("c", text.spanned { it.fontFamily == FontFamily.Monospace })
    }

    @Test
    fun a_link_is_its_label_and_an_image_its_alt_text() {
        // Every link shape the parser knows, and none of them carries a destination out. The
        // specification excludes links; the seam's KDoc says why a live one would be a hole.
        val source = "[l](http://x) ![alt](u) [r][ref] [ref] <http://auto> https://bare\n\n[ref]: http://y"
        val blocks = parseMarkdownBlocks(source)
        val text = (blocks.single() as MarkdownBlock.Paragraph).text
        assertEquals("l alt r ref http://auto https://bare", text.text)
        assertTrue(text.getLinkAnnotations(0, text.length).isEmpty(), "no link may be live: $text")
    }

    @Test
    fun html_is_not_drawn_inline_or_as_a_block() {
        assertEquals("h and a < b", paragraph("<b>h</b> and a < b").text)
        assertEquals(emptyList(), parseMarkdownBlocks("<div>\nraw\n</div>"))
    }

    @Test
    fun escapes_and_entities_resolve_and_unknown_ones_stay() {
        assertEquals("*c* & é \uD83D\uDE00 \\q &nosuch; �", paragraph("\\*c\\* &amp; &eacute; &#x1F600; \\q &nosuch; &#0;").text)
        // Decimal, and the three invalid shapes CommonMark replaces: zero, out of range, and a
        // lone surrogate, which would otherwise be half a character in the string.
        assertEquals("A \uFFFD \uFFFD \uFFFD", paragraph("&#65; &#0; &#1114112; &#xD800;").text)
    }

    @Test
    fun a_code_span_strips_one_space_from_each_end_and_only_then() {
        assertEquals("a", paragraph("` a `").text)
        assertEquals("  ", paragraph("`  `").text)
        assertEquals(" a", paragraph("` a`").text)
        // A line ending inside a span is a space.
        assertEquals("a b", paragraph("`a\nb`").text)
    }

    @Test
    fun a_soft_break_is_a_space_and_a_hard_break_a_newline() {
        assertEquals("one two\nthree", paragraph("one\ntwo  \nthree").text)
        // The other spelling of a hard break.
        assertEquals("one\ntwo", paragraph("one\\\ntwo").text)
    }

    @Test
    fun headings_carry_their_level_without_the_closing_run() {
        for (level in 1..6) {
            val block = parseMarkdownBlocks("#".repeat(level) + " Head #").single()
            assertEquals(MarkdownBlock.Heading(level, AnnotatedString("Head")), block)
        }
        assertEquals(MarkdownBlock.Heading(1, AnnotatedString("Setext")), parseMarkdownBlocks("Setext\n===").single())
        assertEquals(MarkdownBlock.Heading(2, AnnotatedString("Under")), parseMarkdownBlocks("Under\n---").single())
    }

    @Test
    fun a_list_keeps_its_order_its_start_and_its_nesting() {
        val blocks = parseMarkdownBlocks("- a\n- b\n  - nested\n\n3) three\n4) four")
        val (unordered, ordered) = blocks.map { assertIs<MarkdownBlock.ListBlock>(it) }
        assertEquals(false, unordered.ordered)
        assertEquals(listOf(MarkdownBlock.Paragraph(AnnotatedString("a"))), unordered.items[0])
        val nested = MarkdownBlock.ListBlock(false, 1, listOf(listOf(MarkdownBlock.Paragraph(AnnotatedString("nested")))))
        assertEquals(listOf(MarkdownBlock.Paragraph(AnnotatedString("b")), nested), unordered.items[1])
        assertEquals(true, ordered.ordered)
        assertEquals(3, ordered.start, "the number the agent started at is the number drawn")
        assertEquals(2, ordered.items.size)
    }

    @Test
    fun a_loose_item_holds_every_block_it_was_given() {
        val list = parseMarkdownBlocks("1. a\n\n   para2\n\n   ```\n   code\n   ```\n2. b").single() as MarkdownBlock.ListBlock
        assertEquals(
            listOf(
                MarkdownBlock.Paragraph(AnnotatedString("a")),
                MarkdownBlock.Paragraph(AnnotatedString("para2")),
                // The item's indent is not the code's.
                MarkdownBlock.Code("code"),
            ),
            list.items[0],
        )
    }

    @Test
    fun a_task_box_is_text_in_front_of_its_item() {
        val list = parseMarkdownBlocks("- [ ] todo\n- [x] done").single() as MarkdownBlock.ListBlock
        assertEquals("[ ] todo", (list.items[0].single() as MarkdownBlock.Paragraph).text.text)
        assertEquals("[x] done", (list.items[1].single() as MarkdownBlock.Paragraph).text.text)
    }

    @Test
    fun a_quote_joins_its_lines_and_nests() {
        val quote = parseMarkdownBlocks("> q1\n> q2\n>\n> > inner").single() as MarkdownBlock.Quote
        assertEquals(
            listOf(
                MarkdownBlock.Paragraph(AnnotatedString("q1 q2")),
                MarkdownBlock.Quote(listOf(MarkdownBlock.Paragraph(AnnotatedString("inner")))),
            ),
            quote.blocks,
        )
    }

    @Test
    fun an_alert_is_a_quote_that_names_itself() {
        val quote = parseMarkdownBlocks("> [!NOTE]\n> alert").single() as MarkdownBlock.Quote
        assertEquals(listOf("Note", "alert"), quote.blocks.map { (it as MarkdownBlock.Paragraph).text.text })
        assertEquals("Note", (quote.blocks[0] as MarkdownBlock.Paragraph).text.spanned { it.fontWeight == FontWeight.Bold })
    }

    @Test
    fun code_keeps_its_lines_and_loses_the_fence_the_language_and_the_indent() {
        assertEquals(MarkdownBlock.Code("val x\n\n  y"), parseMarkdownBlocks("```kt\nval x\n\n  y\n```").single())
        assertEquals(MarkdownBlock.Code("indented\ncode"), parseMarkdownBlocks("    indented\n    code").single())
        // Unclosed: what the agent wrote so far, which is what a streaming agent's fence is
        // until its closing line arrives.
        assertEquals(MarkdownBlock.Code("\n  lead"), parseMarkdownBlocks("```\n\n  lead\n").single())
        // No Markdown inside.
        assertEquals(MarkdownBlock.Code("**not bold**"), parseMarkdownBlocks("```\n**not bold**\n```").single())
    }

    @Test
    fun a_table_pads_short_rows_truncates_long_ones_and_reads_its_alignment() {
        val table = parseMarkdownBlocks(
            "| h1 | h2 | h3 |\n|:--|:-:|--:|\n| a | **b** | c |\n| short |\n| x | y | z | extra |",
        ).single() as MarkdownBlock.Table
        assertEquals(listOf("h1", "h2", "h3"), table.header.map { it.text })
        assertEquals(listOf(TextAlign.Start, TextAlign.Center, TextAlign.End), table.alignments)
        assertEquals(3, table.rows.size)
        assertEquals(listOf("a", "b", "c"), table.rows[0].map { it.text })
        assertEquals("b", table.rows[0][1].spanned { it.fontWeight == FontWeight.Bold })
        assertEquals(listOf("short", "", ""), table.rows[1].map { it.text })
        assertEquals(listOf("x", "y", "z"), table.rows[2].map { it.text })
    }

    @Test
    fun a_rule_is_a_rule_and_a_link_definition_is_nothing() {
        assertEquals(listOf(MarkdownBlock.Rule), parseMarkdownBlocks("* * *"))
        assertEquals(emptyList(), parseMarkdownBlocks("[ref]: http://x"))
        assertEquals(emptyList(), parseMarkdownBlocks(""))
    }

    @Test
    fun nesting_past_the_bound_degrades_to_the_text_and_never_to_a_stack() {
        // The parser accepts this depth; the bound is the walk's. Eight thousand levels is the
        // deepest quote that fits under the length cap, and it is run here on every target --
        // wasmJs included, whose stack is the one this bound is for.
        val deepest = parseMarkdownBlocks("> ".repeat(8_000) + "x")
        var depth = 0
        var block: MarkdownBlock = deepest.single()
        while (block is MarkdownBlock.Quote) {
            depth++
            block = block.blocks.single()
        }
        assertEquals(MAX_BLOCK_DEPTH + 1, depth, "one quote per level up to the bound")
        val leaf = assertIs<MarkdownBlock.Paragraph>(block)
        assertTrue(leaf.text.text.startsWith("> > "), "past the bound the quote is its own characters: ${leaf.text.text.take(20)}")
        assertTrue(leaf.text.text.endsWith("x"))
    }

    @Test
    fun inline_nesting_past_the_bound_degrades_to_the_text() {
        val open = "*a ".repeat(MAX_INLINE_DEPTH + 4)
        val close = " b*".repeat(MAX_INLINE_DEPTH + 4)
        val text = paragraph(open + "c" + close)
        assertTrue('*' in text.text, "the innermost delimiters should have been emitted as text: ${text.text}")
        // One italic span per level up to the bound, and none past it: the count is what
        // distinguishes the walk's bound from the parser having stopped nesting on its own.
        assertEquals(MAX_INLINE_DEPTH + 1, text.spanStyles.count { it.item.fontStyle == FontStyle.Italic })
    }

    @Test
    fun past_the_bound_the_exclusions_still_hold() {
        // The degraded text is the agent's characters, but not all of them: a tag and a link's
        // destination are dropped there too, or "raw HTML is not drawn" would have a depth
        // carve-out that nine `>` characters could reach.
        val deep = parseMarkdownBlocks("> ".repeat(MAX_BLOCK_DEPTH + 2) + "<b>x</b> [l](https://e.com) ![a](u)")
        var block: MarkdownBlock = deep.single()
        while (block is MarkdownBlock.Quote) block = block.blocks.single()
        val leaf = assertIs<MarkdownBlock.Paragraph>(block).text.text
        assertTrue("<b>" !in leaf && "e.com" !in leaf, "the tag and the destination should be gone: $leaf")
        assertTrue("x" in leaf && "[l]" in leaf && "[a]" in leaf, "the text and the labels should remain: $leaf")
        val inline = paragraph("*a ".repeat(MAX_INLINE_DEPTH + 2) + "<b>x</b> [l](https://e.com)" + " b*".repeat(MAX_INLINE_DEPTH + 2)).text
        assertTrue("<b>" !in inline && "e.com" !in inline, "inline too: $inline")
    }

    private fun paragraph(source: String): AnnotatedString =
        (parseMarkdownBlocks(source).single() as MarkdownBlock.Paragraph).text

    /** The text under the one span that [matches], so a test names the styled word rather than offsets. */
    private fun AnnotatedString.spanned(matches: (androidx.compose.ui.text.SpanStyle) -> Boolean): String {
        val span = spanStyles.single { matches(it.item) }
        return text.substring(span.start, span.end)
    }
}
