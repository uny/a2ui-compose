package dev.ynagai.a2ui.material3.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.html.entities.Entities
import org.intellij.markdown.parser.MarkdownParser

/**
 * What [Material3MarkdownRenderer] draws: the document reduced to the blocks it has a layout for.
 *
 * The parser's tree is not drawn directly, and the reason is the same as the module boundary's:
 * every `ASTNode` is read here and none leaves. A block is a value the tests can compare, so the
 * conversion -- which is where a table would lose a column or a nested list its indent -- is
 * pinned on every target from `commonTest`, and the composition only has to draw what it is
 * handed.
 */
internal sealed interface MarkdownBlock {
    data class Paragraph(val text: AnnotatedString) : MarkdownBlock

    /** [level] is 1 through 6, from `#` through `######` and the two setext underlines. */
    data class Heading(val level: Int, val text: AnnotatedString) : MarkdownBlock

    /** [start] is the first item's number when [ordered], as the agent wrote it. */
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<List<MarkdownBlock>>) : MarkdownBlock

    data class Quote(val blocks: List<MarkdownBlock>) : MarkdownBlock

    /** A fenced or indented block, with the fence and the indent removed and no Markdown read inside. */
    data class Code(val text: String) : MarkdownBlock

    /**
     * A GFM table. Every row has as many cells as [header]: a short row is padded with empty
     * cells and a long one truncated, which is the extension's own rule.
     */
    data class Table(
        val header: List<AnnotatedString>,
        val alignments: List<TextAlign>,
        val rows: List<List<AnnotatedString>>,
    ) : MarkdownBlock

    data object Rule : MarkdownBlock
}

/**
 * [source] as blocks.
 *
 * GFM rather than CommonMark, for the tables and strikethrough the specification's corpus uses.
 * The flavour's link options are irrelevant here: no destination is ever read, so there is
 * nothing to make safe.
 *
 * Nesting is bounded by [MAX_BLOCK_DEPTH] and [MAX_INLINE_DEPTH], not by the parser. The parser
 * copes with a quote eight thousand levels deep -- measured -- but a walk that recursed with it
 * would not on every target's stack, and the agent chooses the text. Past the bound a node is
 * emitted as the characters it was written with, which is the same degradation the default
 * renderer chooses for a delimiter it cannot match.
 */
internal fun parseMarkdownBlocks(source: String): List<MarkdownBlock> {
    val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source as CharSequence)
    return blocksOf(root.children, source, depth = 0)
}

private fun blocksOf(nodes: List<ASTNode>, source: String, depth: Int): List<MarkdownBlock> =
    nodes.flatMap { blocksOf(it, source, depth) }

private fun blocksOf(node: ASTNode, source: String, depth: Int): List<MarkdownBlock> {
    // Composites only: the tokens between blocks at this depth are still bookkeeping, and a
    // quote marker emitted as a paragraph would put a `>` on screen beside the text it degraded to.
    if (depth > MAX_BLOCK_DEPTH && node.children.isNotEmpty()) {
        return listOf(MarkdownBlock.Paragraph(AnnotatedString(node.text(source).trim())))
    }
    return when (node.type) {
        MarkdownElementTypes.PARAGRAPH -> listOf(MarkdownBlock.Paragraph(inline(node.children, source)))

        MarkdownElementTypes.ATX_1, MarkdownElementTypes.ATX_2, MarkdownElementTypes.ATX_3,
        MarkdownElementTypes.ATX_4, MarkdownElementTypes.ATX_5, MarkdownElementTypes.ATX_6,
        -> {
            val content = node.findChildOfType(MarkdownTokenTypes.ATX_CONTENT)?.children.orEmpty()
            listOf(MarkdownBlock.Heading(ATX_LEVELS.getValue(node.type), inline(content.trimWhitespace(), source)))
        }

        MarkdownElementTypes.SETEXT_1, MarkdownElementTypes.SETEXT_2 -> {
            val content = node.findChildOfType(MarkdownTokenTypes.SETEXT_CONTENT)?.children.orEmpty()
            val level = if (node.type == MarkdownElementTypes.SETEXT_1) 1 else 2
            listOf(MarkdownBlock.Heading(level, inline(content.trimWhitespace(), source)))
        }

        MarkdownElementTypes.UNORDERED_LIST, MarkdownElementTypes.ORDERED_LIST -> {
            val items = node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }
            val ordered = node.type == MarkdownElementTypes.ORDERED_LIST
            val start = items.firstOrNull()
                ?.findChildOfType(MarkdownTokenTypes.LIST_NUMBER)
                ?.text(source)
                ?.takeWhile(Char::isDigit)
                // CommonMark allows nine digits, so this cannot overflow; a longer run is not
                // a list marker and the parser has already declined it.
                ?.toIntOrNull()
                ?: 1
            listOf(MarkdownBlock.ListBlock(ordered, start, items.map { listItem(it, source, depth + 1) }))
        }

        MarkdownElementTypes.BLOCK_QUOTE -> listOf(MarkdownBlock.Quote(blocksOf(node.children, source, depth + 1)))

        // GFM's `> [!NOTE]`: a quote whose first line names it. The name is kept as a bold
        // paragraph rather than an icon and a colour, which would be a design decision the
        // agent did not make.
        GFMElementTypes.ALERT -> {
            val title = node.findChildOfType(GFMTokenTypes.ALERT_TITLE)?.text(source)
                ?.removePrefix("[!")?.removeSuffix("]")
                ?.lowercase()?.replaceFirstChar(Char::titlecase)
            val heading = title?.let {
                MarkdownBlock.Paragraph(buildAnnotatedString { withStyle(BOLD) { append(it) } })
            }
            listOf(MarkdownBlock.Quote(listOfNotNull(heading) + blocksOf(node.children, source, depth + 1)))
        }

        MarkdownElementTypes.CODE_FENCE -> listOf(MarkdownBlock.Code(fenceText(node, source)))

        MarkdownElementTypes.CODE_BLOCK -> {
            val lines = node.children
                .filter { it.type == MarkdownTokenTypes.CODE_LINE }
                .map { it.text(source).removeIndent() }
            listOf(MarkdownBlock.Code(lines.joinToString("\n")))
        }

        MarkdownTokenTypes.HORIZONTAL_RULE -> listOf(MarkdownBlock.Rule)

        GFMElementTypes.TABLE -> listOf(table(node, source))

        // The specification's exclusions, and the parser's bookkeeping: a raw HTML block is not
        // drawn, a link definition defines something no link here will follow, and the tokens
        // between blocks are the newlines and the markers -- quote, bullet, number, task box --
        // that the block they belong to has already read.
        MarkdownElementTypes.HTML_BLOCK,
        MarkdownElementTypes.LINK_DEFINITION,
        MarkdownTokenTypes.EOL,
        MarkdownTokenTypes.WHITE_SPACE,
        MarkdownTokenTypes.BLOCK_QUOTE,
        MarkdownTokenTypes.LIST_BULLET,
        MarkdownTokenTypes.LIST_NUMBER,
        GFMTokenTypes.CHECK_BOX,
        GFMTokenTypes.ALERT_TITLE,
        -> emptyList()

        else -> if (node.children.isEmpty()) {
            listOf(MarkdownBlock.Paragraph(AnnotatedString(node.text(source))))
        } else {
            blocksOf(node.children, source, depth + 1)
        }
    }
}

/**
 * A list item's blocks, with its marker gone and a GFM task box kept as the characters it is.
 *
 * `[ ]` and `[x]` as text rather than a `Checkbox`: a box the user could tick would be state the
 * agent cannot see, and the catalog has a `CheckBox` component for the case where it wants one.
 */
private fun listItem(item: ASTNode, source: String, depth: Int): List<MarkdownBlock> {
    val blocks = blocksOf(item.children, source, depth)
    val box = item.findChildOfType(GFMTokenTypes.CHECK_BOX)?.text(source)?.trim() ?: return blocks
    val first = blocks.firstOrNull() as? MarkdownBlock.Paragraph
        ?: return listOf(MarkdownBlock.Paragraph(AnnotatedString(box))) + blocks
    val prefixed = buildAnnotatedString {
        append(box)
        append(' ')
        append(first.text)
    }
    return listOf(MarkdownBlock.Paragraph(prefixed)) + blocks.drop(1)
}

/**
 * The lines between the fences.
 *
 * Assembled from the tokens rather than sliced from the source, because inside a list item the
 * parser hands the indent back as `WHITE_SPACE` tokens before each content line and folded into
 * the closing fence, and neither is the code. The line the opening fence sits on is always the
 * first one and always empty, so it is dropped; a fence the agent never closed ends with the
 * last line it wrote.
 */
private fun fenceText(node: ASTNode, source: String): String {
    val lines = mutableListOf<String>()
    val current = StringBuilder()
    for (child in node.children) {
        when (child.type) {
            MarkdownTokenTypes.CODE_FENCE_CONTENT -> current.append(child.text(source))
            MarkdownTokenTypes.EOL -> {
                lines += current.toString()
                current.setLength(0)
            }
        }
    }
    if (current.isNotEmpty()) lines += current.toString()
    return lines.drop(1).joinToString("\n")
}

private fun table(node: ASTNode, source: String): MarkdownBlock.Table {
    val header = node.findChildOfType(GFMElementTypes.HEADER)?.cells(source).orEmpty()
    val columns = header.size
    // The delimiter row, which is where the columns' alignment lives: `:--` starts, `--:` ends,
    // `:-:` centres, and a bare `---` is the default.
    val separator = node.children
        .firstOrNull { it.type == GFMTokenTypes.TABLE_SEPARATOR }
        ?.text(source)
        .orEmpty()
    val alignments = separator.trim().trim('|').split('|').map { spec ->
        val cell = spec.trim()
        when {
            cell.startsWith(':') && cell.endsWith(':') -> TextAlign.Center
            cell.endsWith(':') -> TextAlign.End
            else -> TextAlign.Start
        }
    }.let { specs -> List(columns) { specs.getOrElse(it) { TextAlign.Start } } }
    val rows = node.children
        .filter { it.type == GFMElementTypes.ROW }
        .map { row ->
            val cells = row.cells(source)
            List(columns) { cells.getOrElse(it) { AnnotatedString("") } }
        }
    return MarkdownBlock.Table(header, alignments, rows)
}

private fun ASTNode.cells(source: String): List<AnnotatedString> =
    children.filter { it.type == GFMTokenTypes.CELL }.map { inline(it.children.trimWhitespace(), source) }

/** The inline run [nodes] make, with the markers gone and the emphasis real. */
private fun inline(nodes: List<ASTNode>, source: String): AnnotatedString =
    buildAnnotatedString { appendInline(nodes, source, depth = 0) }

private fun AnnotatedString.Builder.appendInline(nodes: List<ASTNode>, source: String, depth: Int) {
    var afterLineBreak = false
    var afterHardBreak = false
    for (node in nodes) {
        // A continuation line's leading indent and quote marker are the line's, not the text's:
        // `> a\n> b` is the paragraph `a b`, and the space after the second `>` is what would
        // otherwise double up.
        if (afterLineBreak && (node.type == MarkdownTokenTypes.WHITE_SPACE || node.type == MarkdownTokenTypes.BLOCK_QUOTE)) {
            continue
        }
        afterLineBreak = false
        if (node.type != MarkdownTokenTypes.EOL) afterHardBreak = false
        when (node.type) {
            MarkdownTokenTypes.EOL -> {
                // A soft break, which CommonMark renders as a space. A hard break has already
                // appended its newline, and this is the newline that followed it.
                if (!afterHardBreak) append(' ')
                afterLineBreak = true
                afterHardBreak = false
            }

            MarkdownTokenTypes.HARD_LINE_BREAK -> {
                append('\n')
                afterHardBreak = true
            }

            // The specification excludes HTML, and a tag is not text. `a < b` is unaffected: a
            // lone `<` is its own token, and falls through to the text below.
            MarkdownTokenTypes.HTML_TAG, MarkdownTokenTypes.BLOCK_QUOTE -> Unit

            MarkdownTokenTypes.TEXT, MarkdownTokenTypes.ESCAPED_BACKTICKS -> append(unescape(node.text(source)))

            else -> appendInlineNode(node, source, depth)
        }
    }
}

private fun AnnotatedString.Builder.appendInlineNode(node: ASTNode, source: String, depth: Int) {
    if (node.children.isEmpty()) {
        append(node.text(source))
        return
    }
    if (depth > MAX_INLINE_DEPTH) {
        append(node.text(source))
        return
    }
    val children = node.children
    when (node.type) {
        MarkdownElementTypes.EMPH ->
            withStyle(ITALIC) { appendInline(children.drop(1).dropLast(1), source, depth + 1) }

        MarkdownElementTypes.STRONG ->
            withStyle(BOLD) { appendInline(children.drop(2).dropLast(2), source, depth + 1) }

        GFMElementTypes.STRIKETHROUGH ->
            withStyle(STRIKE) { appendInline(children.drop(2).dropLast(2), source, depth + 1) }

        MarkdownElementTypes.CODE_SPAN -> withStyle(CODE) { append(codeSpanText(children, source)) }

        // Links reduce to their label, for the reason `A2uiMarkdownRenderer`'s documentation
        // gives: a link that opened would hand the agent a way past `openUrl`. Nothing here
        // reads a destination, so there is no `LinkAnnotation` to forget to add. An image is
        // its alt text, which is what its label is for.
        MarkdownElementTypes.INLINE_LINK, MarkdownElementTypes.FULL_REFERENCE_LINK -> {
            val label = node.findChildOfType(MarkdownElementTypes.LINK_TEXT)
            if (label != null) appendInline(label.children.unbracketed(), source, depth + 1) else append(node.text(source))
        }

        MarkdownElementTypes.SHORT_REFERENCE_LINK -> {
            val label = node.findChildOfType(MarkdownElementTypes.LINK_LABEL)
            if (label != null) appendInline(label.children.unbracketed(), source, depth + 1) else append(node.text(source))
        }

        MarkdownElementTypes.IMAGE -> {
            val link = children.firstOrNull { it.type != MarkdownTokenTypes.EXCLAMATION_MARK }
            if (link != null) appendInlineNode(link, source, depth + 1) else append(node.text(source))
        }

        // `<https://example.com>`: the URL is the label. The angle brackets are the syntax.
        MarkdownElementTypes.AUTOLINK -> appendInline(
            children.filter { it.type != MarkdownTokenTypes.LT && it.type != MarkdownTokenTypes.GT },
            source,
            depth + 1,
        )

        else -> appendInline(children, source, depth + 1)
    }
}

/**
 * A code span's content: the text between the backtick runs, its line endings made spaces, and
 * one space stripped from each end when both are there and the content is not all spaces --
 * CommonMark's rule, so that `` ` `` `` can contain a backtick.
 */
private fun codeSpanText(children: List<ASTNode>, source: String): String {
    val inner = children.drop(1).dropLast(1).joinToString("") { child ->
        if (child.type == MarkdownTokenTypes.EOL) " " else child.text(source)
    }
    val stripped = inner.length >= 2 && inner.first() == ' ' && inner.last() == ' ' && inner.any { it != ' ' }
    return if (stripped) inner.substring(1, inner.length - 1) else inner
}

/**
 * Backslash escapes and entity references, resolved.
 *
 * The parser leaves both in its `TEXT` tokens -- `\*` is two characters and `&amp;` is five --
 * and its own converter turns them into HTML, which is the wrong direction for a `Text`. An
 * escape before ASCII punctuation drops the backslash; anything else keeps it, which is
 * CommonMark's rule. A named entity the parser's table does not know is left as written.
 */
private fun unescape(text: String): String {
    if ('\\' !in text && '&' !in text) return text
    return ENTITY_OR_ESCAPE.replace(text) { match ->
        val escaped = match.groupValues[1]
        if (escaped.isNotEmpty()) return@replace escaped
        val named = match.groupValues[2]
        val decimal = match.groupValues[3]
        val hex = match.groupValues[4]
        val code = when {
            named.isNotEmpty() -> Entities.map[match.value]
            decimal.isNotEmpty() -> decimal.toIntOrNull()
            hex.isNotEmpty() -> hex.toIntOrNull(16)
            else -> null
        }
        // `&#0;` and anything past the code point range are U+FFFD in CommonMark; the rest as-is.
        when {
            code == null -> match.value
            code == 0 || code > MAX_CODE_POINT -> "\uFFFD"
            else -> buildString { appendCodePoint(code) }
        }
    }
}

private fun StringBuilder.appendCodePoint(code: Int) {
    if (code < SURROGATE_BASE) {
        append(code.toChar())
    } else {
        val offset = code - SURROGATE_BASE
        append(((offset shr 10) + HIGH_SURROGATE_START).toChar())
        append(((offset and 0x3FF) + LOW_SURROGATE_START).toChar())
    }
}

private fun ASTNode.text(source: String): String = getTextInNode(source).toString()

private fun List<ASTNode>.trimWhitespace(): List<ASTNode> =
    dropWhile { it.type == MarkdownTokenTypes.WHITE_SPACE }.dropLastWhile { it.type == MarkdownTokenTypes.WHITE_SPACE }

/** A link text's or label's children without the `[` and `]` around them. */
private fun List<ASTNode>.unbracketed(): List<ASTNode> =
    filterIndexed { index, node ->
        !(index == 0 && node.type == MarkdownTokenTypes.LBRACKET) &&
            !(index == lastIndex && node.type == MarkdownTokenTypes.RBRACKET)
    }

/** An indented code line without the four spaces (or the tab) that made it one. */
private fun String.removeIndent(): String = when {
    startsWith("    ") -> substring(4)
    startsWith("\t") -> substring(1)
    else -> trimStart()
}

private val ATX_LEVELS: Map<IElementType, Int> = mapOf(
    MarkdownElementTypes.ATX_1 to 1,
    MarkdownElementTypes.ATX_2 to 2,
    MarkdownElementTypes.ATX_3 to 3,
    MarkdownElementTypes.ATX_4 to 4,
    MarkdownElementTypes.ATX_5 to 5,
    MarkdownElementTypes.ATX_6 to 6,
)

private val BOLD = SpanStyle(fontWeight = FontWeight.Bold)
private val ITALIC = SpanStyle(fontStyle = FontStyle.Italic)
private val STRIKE = SpanStyle(textDecoration = TextDecoration.LineThrough)
private val CODE = SpanStyle(fontFamily = FontFamily.Monospace)

private val ENTITY_OR_ESCAPE =
    Regex("""\\([!"#$%&'()*+,\-./:;<=>?@\[\\\]^_`{|}~])|&(?:([a-zA-Z][a-zA-Z0-9]{0,31})|#([0-9]{1,7})|#[xX]([a-fA-F0-9]{1,6}));""")

private const val MAX_CODE_POINT = 0x10FFFF
private const val SURROGATE_BASE = 0x10000
private const val HIGH_SURROGATE_START = 0xD800
private const val LOW_SURROGATE_START = 0xDC00

/**
 * How deep a container may nest before its content is emitted as the text it was written as.
 *
 * Internal so the tests can pin the bound rather than restate its value, as `MAX_NESTING` is in
 * `a2ui-material3`. Eight levels of quote-in-list-in-quote is more than prose uses and less
 * than a stack on wasmJs minds.
 */
internal const val MAX_BLOCK_DEPTH = 8
internal const val MAX_INLINE_DEPTH = 8
