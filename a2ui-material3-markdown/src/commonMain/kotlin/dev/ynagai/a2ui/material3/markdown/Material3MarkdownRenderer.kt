package dev.ynagai.a2ui.material3.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.ynagai.a2ui.material3.A2uiMarkdownRenderer
import dev.ynagai.a2ui.material3.LocalA2uiMarkdownRenderer

/**
 * The Markdown renderer that draws the blocks the default cannot: lists, block quotes, tables,
 * and code, in Material 3.
 *
 * Installed through [LocalA2uiMarkdownRenderer], the seam `Text` already reads, and nothing else
 * changes:
 *
 * ```kotlin
 * CompositionLocalProvider(LocalA2uiMarkdownRenderer provides Material3MarkdownRenderer) {
 *     A2uiSurface(/* ... */)
 * }
 * ```
 *
 * Its own module, because it brings a parser (`org.jetbrains:markdown`) and `a2ui-material3`
 * declines to put one on every consumer's class path. A host that wants the default's subset
 * keeps it by not depending on this.
 *
 * What it keeps of [A2uiMarkdownRenderer]'s contract. The [style] and [color] `Text` resolved
 * for the variant are applied to every block, so a caption's list is `bodySmall` and dimmed
 * like the caption's prose, and headings are sized in `em` relative to that style rather than in
 * the theme's headline sizes -- the same ratios the default draws, so switching renderers
 * changes what a list looks like and not what a heading does. The bar beside a quote, the tint
 * behind code and the rules of a table are drawn in the text colour that resolves -- [color],
 * else the style's, else `LocalContentColor` -- so inside a filled `Button` they are the label's
 * colour and not a surface colour on the primary. The [modifier] goes on the outermost thing
 * drawn, which is the column of blocks.
 *
 * The specification's exclusions are kept here. Links reduce to their label and no
 * `LinkAnnotation` is ever attached, for the reason the seam gives: a link that opened would hand
 * the agent a way past `openUrl`. Images are their alt text. Raw HTML, inline or block, is not
 * drawn.
 *
 * Its bound is the default's: above [MAX_MARKDOWN_INPUT] characters the source is handed to
 * [A2uiMarkdownRenderer.Inline], which at that length draws it verbatim, so the point at which
 * an agent's text stops being parsed is the same whichever renderer is installed. Within the
 * bound the parser's cost is the parser's -- a pathological sixteen thousand characters of `[`
 * takes it under two seconds on a laptop JVM -- and nesting is bounded separately by
 * [MAX_BLOCK_DEPTH], since the parser accepts a depth the stack on wasmJs would not.
 *
 * Not a `Checkbox` for a GFM task item, not a clickable anything, and not the theme's headline
 * styles: each would be a decision about the agent's text that the agent did not make.
 */
public object Material3MarkdownRenderer : A2uiMarkdownRenderer {
    @Composable
    override fun Markdown(source: String, style: TextStyle, color: Color, modifier: Modifier) {
        if (source.length > MAX_MARKDOWN_INPUT) {
            A2uiMarkdownRenderer.Inline.Markdown(source, style, color, modifier)
            return
        }
        val blocks = remember(source) { parseMarkdownBlocks(source) }
        val resolved = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(BLOCK_GAP)) {
            Blocks(blocks, style, color, resolved)
        }
    }
}

/** The blocks in a column, each drawn by [Block]; the column is the caller's. */
@Composable
private fun Blocks(blocks: List<MarkdownBlock>, style: TextStyle, color: Color, resolved: Color) {
    for (block in blocks) Block(block, style, color, resolved)
}

@Composable
private fun Block(block: MarkdownBlock, style: TextStyle, color: Color, resolved: Color) {
    when (block) {
        is MarkdownBlock.Paragraph -> Text(block.text, style = style, color = color)

        is MarkdownBlock.Heading -> {
            val heading = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = HEADING_SCALE[block.level - 1])) {
                    append(block.text)
                }
            }
            Text(heading, style = style, color = color)
        }

        is MarkdownBlock.ListBlock -> ListBlock(block, style, color, resolved)

        is MarkdownBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(QUOTE_BAR_WIDTH)
                    .fillMaxHeight()
                    .background(resolved.copy(alpha = QUOTE_BAR_ALPHA)),
            )
            Column(Modifier.padding(start = INDENT), verticalArrangement = Arrangement.spacedBy(BLOCK_GAP)) {
                Blocks(block.blocks, style, color, resolved)
            }
        }

        is MarkdownBlock.Code -> Box(
            Modifier
                .fillMaxWidth()
                .background(resolved.copy(alpha = CODE_TINT_ALPHA), RoundedCornerShape(CODE_CORNER))
                .horizontalScroll(rememberScrollState())
                .padding(CODE_PADDING),
        ) {
            Text(
                block.text,
                style = style.copy(fontFamily = FontFamily.Monospace),
                color = color,
                softWrap = false,
            )
        }

        is MarkdownBlock.Table -> TableBlock(block, style, color, resolved)

        MarkdownBlock.Rule -> HorizontalDivider(color = resolved.copy(alpha = RULE_ALPHA))
    }
}

/**
 * A list: a marker column and the item's blocks beside it.
 *
 * The marker is text in the same style, so it sits on the item's first baseline and shrinks
 * with a caption. Ordered items count from the number the agent started at, which is CommonMark's
 * rule and what `3) three` means.
 */
@Composable
private fun ListBlock(list: MarkdownBlock.ListBlock, style: TextStyle, color: Color, resolved: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(ITEM_GAP)) {
        list.items.forEachIndexed { index, item ->
            Row {
                val marker = if (list.ordered) "${list.start + index}." else BULLET
                Text(marker, Modifier.padding(end = MARKER_GAP), style = style, color = color)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(BLOCK_GAP)) {
                    Blocks(item, style, color, resolved)
                }
            }
        }
    }
}

/**
 * A table as rows of equally weighted cells, with a rule under the header and between rows.
 *
 * Equal weights rather than measured columns: a column sized to its widest cell is the right
 * layout for a table and the wrong one for a phone, where the agent's four columns of prose
 * would push the last one off the edge. Equal shares wrap instead.
 */
@Composable
private fun TableBlock(table: MarkdownBlock.Table, style: TextStyle, color: Color, resolved: Color) {
    val rule = resolved.copy(alpha = RULE_ALPHA)
    Column(Modifier.fillMaxWidth()) {
        TableRow(table.header, table.alignments, style.copy(fontWeight = FontWeight.Bold), color)
        HorizontalDivider(color = rule)
        table.rows.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(color = rule.copy(alpha = RULE_ALPHA / 2f))
            TableRow(row, table.alignments, style, color)
        }
    }
}

@Composable
private fun TableRow(cells: List<AnnotatedString>, alignments: List<TextAlign>, style: TextStyle, color: Color) {
    Row(Modifier.fillMaxWidth()) {
        cells.forEachIndexed { index, cell ->
            Text(
                cell,
                Modifier.weight(1f).padding(CELL_PADDING),
                style = style,
                color = color,
                textAlign = alignments.getOrElse(index) { TextAlign.Start },
            )
        }
    }
}

/**
 * The default renderer's bound, restated rather than imported: `a2ui-material3` keeps its
 * constant internal, and this one is documented as equal to it. If the two ever differ the
 * comparison in [Material3MarkdownRenderer] still holds -- the source is handed over, and
 * `Inline` applies its own -- and only the sentence in the KDoc about "the same point" is wrong.
 */
internal const val MAX_MARKDOWN_INPUT = 16_384

/** The default renderer's ratios, so a heading is the same size whichever renderer draws it. */
private val HEADING_SCALE: List<TextUnit> =
    listOf(2.0.em, 1.5.em, 1.25.em, 1.1.em, 1.0.em, 0.9.em)

private const val BULLET = "•"
private val BLOCK_GAP = 8.dp
private val ITEM_GAP = 2.dp
private val INDENT = 12.dp
private val MARKER_GAP = 8.dp
private val QUOTE_BAR_WIDTH = 3.dp
private val CODE_CORNER = 4.dp
private val CODE_PADDING = 8.dp
private val CELL_PADDING = 4.dp
private const val QUOTE_BAR_ALPHA = 0.4f
private const val CODE_TINT_ALPHA = 0.08f
private const val RULE_ALPHA = 0.3f
