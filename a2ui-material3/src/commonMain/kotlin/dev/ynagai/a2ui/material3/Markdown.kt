package dev.ynagai.a2ui.material3

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em

/**
 * The subset of Markdown a `Text` renders, and what happens to the rest.
 *
 * The implementation guide asks for a Markdown parser "when possible", and falls back to raw text
 * with "common Markdown markers" stripped. There is no Markdown parser on this library's class
 * path and adding one would put a parser of somebody else's choosing into every consumer's
 * artifact, so what is implemented here is the second option done properly: the constructs the
 * specification's own example corpus actually uses, rendered rather than merely stripped.
 *
 * Covered: ATX headings (`# ` through `###### `), `**bold**`, `__bold__`, `*italic*`, `_italic_`,
 * `***bold italic***`, `___bold italic___`,
 * `~~strikethrough~~`, `` `code` ``, `[label](url)` reduced to its label, and backslash escapes.
 * Headings are sized relative to the surrounding text rather than in absolute units, so a host that
 * restyles the base text keeps the ratios.
 *
 * Not covered, and passed through as the literal characters: lists, block quotes, tables, images,
 * fenced code blocks, and reference links. That is a real gap rather than a rounding error --
 * `- List item` renders with its dash. It is the guide's own fallback, and it is legible, which is
 * what the guide asks the fallback to be.
 *
 * Links reduce to their label rather than becoming clickable because the specification says the
 * Markdown it supports is "without HTML, images, or links". A renderer that made them live would
 * be handing an agent a way to open URLs that bypasses `openUrl` and its user-gesture rule.
 */
internal fun markdownText(source: String): AnnotatedString {
    // Above this, the text is rendered verbatim. Every construct here is delimited, and a
    // delimiter with no closer costs a scan to the end of the string looking for one -- so a long
    // enough run of unmatched delimiters is quadratic, and this text is the agent's. The scan
    // budget below bounds that within a single parse; this bounds how much work one parse can be
    // asked to do at all.
    if (source.length > MAX_MARKDOWN_INPUT) return AnnotatedString(source)
    val budget = ScanBudget(SCAN_BUDGET_BASE + SCAN_BUDGET_PER_CHAR * source.length)
    return buildAnnotatedString {
        source.split('\n').forEachIndexed { index, line ->
            if (index > 0) append('\n')
            appendMarkdownLine(line, budget)
        }
    }
}

/** One line, with its heading marker read off the front if it has one. */
private fun AnnotatedString.Builder.appendMarkdownLine(line: String, budget: ScanBudget) {
    var level = 0
    while (level < line.length && level < HEADING_SCALE.size && line[level] == '#') level++
    // The space is required, which is CommonMark's rule and not a nicety: `#hashtag` is text, and
    // a run of seven hashes is text as well, because the sixth one is not followed by a space.
    if (level == 0 || level >= line.length || line[level] != ' ') {
        appendInline(line, budget, depth = 0)
        return
    }
    val style = SpanStyle(fontWeight = FontWeight.Bold, fontSize = HEADING_SCALE[level - 1])
    withStyle(style) { appendInline(line.substring(level + 1).trimStart(), budget, depth = 0) }
}

/**
 * The inline constructs, scanned left to right.
 *
 * [depth] bounds nesting. Emphasis recurses into its own content so `**bold with *italic*
 * inside**` works, and the agent chooses the text, so `*`*`*`*`... would otherwise recurse as deep
 * as it liked. Past the bound the delimiters are emitted as the characters they are.
 */
private fun AnnotatedString.Builder.appendInline(text: String, budget: ScanBudget, depth: Int) {
    var i = 0
    val mayNest = depth < MAX_NESTING
    while (i < text.length) {
        val c = text[i]
        i = when {
            c == '\\' && i + 1 < text.length && text[i + 1] in ESCAPABLE -> {
                append(text[i + 1])
                i + 2
            }

            c == '`' -> appendCode(text, i, budget)
            // Before `**`, because `indexOf("**")` inside a `***` run matches the run's own tail
            // and the span closes one character short: `***important***` rendered as a bold
            // `*important` followed by a stray `*`, which is the marker showing through that this
            // whole file exists to prevent.
            mayNest && text.startsWith("***", i) ->
                appendEmphasis(text, i, "***", BOLD_ITALIC, budget, depth)

            mayNest && text.startsWith("___", i) ->
                appendEmphasis(text, i, "___", BOLD_ITALIC, budget, depth)

            mayNest && text.startsWith("**", i) -> appendEmphasis(text, i, "**", BOLD, budget, depth)
            mayNest && text.startsWith("__", i) -> appendEmphasis(text, i, "__", BOLD, budget, depth)
            mayNest && text.startsWith("~~", i) -> appendEmphasis(text, i, "~~", STRIKE, budget, depth)
            mayNest && (c == '*' || c == '_') ->
                appendEmphasis(text, i, c.toString(), ITALIC, budget, depth)

            c == '[' -> appendLink(text, i, budget, depth)
            else -> {
                append(c)
                i + 1
            }
        }
    }
}

/**
 * A `` `code` `` span, whose content is literal -- no Markdown is read inside one.
 *
 * An empty pair is emitted as text, for [appendEmphasis]'s reason: styling nothing is
 * indistinguishable from styling nothing, and consuming the pair silently deleted two characters
 * the agent sent.
 */
private fun AnnotatedString.Builder.appendCode(text: String, start: Int, budget: ScanBudget): Int {
    val end = find(text, start + 1, '`', budget)
    if (end < 0 || end == start + 1) {
        append('`')
        return start + 1
    }
    withStyle(CODE) { append(text, start + 1, end) }
    return end + 1
}

/**
 * A delimited emphasis span, or the delimiter as text when nothing opens or closes it.
 *
 * An empty span (`**` immediately followed by `**`) is also emitted as text: styling nothing is
 * indistinguishable from styling nothing, and treating it as a span would swallow the characters.
 */
private fun AnnotatedString.Builder.appendEmphasis(
    text: String,
    start: Int,
    delimiter: String,
    style: SpanStyle,
    budget: ScanBudget,
    depth: Int,
): Int {
    val from = start + delimiter.length
    val end = if (canOpen(text, start, delimiter)) findCloser(text, from, delimiter, budget) else -1
    if (end <= from) {
        append(text, start, from)
        return from
    }
    withStyle(style) { appendInline(text.substring(from, end), budget, depth + 1) }
    return end + delimiter.length
}

/**
 * Whether the delimiter run at [start] can open emphasis.
 *
 * CommonMark's flanking rules, and they are what stops ordinary prose from being read as markup.
 * A delimiter followed by a space does not open, so `2 * 3 * 4` is arithmetic rather than an
 * italic `3`. An underscore additionally cannot open from inside a word, so `snake_case_name` is
 * an identifier rather than an italic `case`. Asterisks are allowed to do that, which is why
 * `a*b*c` still emphasises the `b`.
 */
private fun canOpen(text: String, start: Int, delimiter: String): Boolean {
    val after = start + delimiter.length
    if (after >= text.length || text[after].isWhitespace()) return false
    if (delimiter[0] != '_') return true
    val before = start - 1
    return before < 0 || !text[before].isLetterOrDigit()
}

/** Whether the delimiter run ending a span at [end] can close it -- [canOpen]'s mirror. */
private fun canClose(text: String, end: Int, delimiter: String): Boolean {
    if (end <= 0 || text[end - 1].isWhitespace()) return false
    if (delimiter[0] != '_') return true
    val after = end + delimiter.length
    return after >= text.length || !text[after].isLetterOrDigit()
}

/**
 * The first delimiter run at or after [from] that can close a span.
 *
 * A candidate that cannot close is skipped rather than ending the search, so `*a *b*` emphasises
 * `a *b` instead of giving up at the space-preceded asterisk in the middle. Each skip costs
 * budget, so a string of candidates that never close still terminates.
 */
private fun findCloser(text: String, from: Int, delimiter: String, budget: ScanBudget): Int {
    var at = from
    while (at < text.length) {
        val index = find(text, at, delimiter, budget)
        if (index < 0) return -1
        if (canClose(text, index, delimiter)) return index
        at = index + delimiter.length
    }
    return -1
}

/** `[label](url)` reduced to `label`, or a literal `[` when the rest of the shape is absent. */
private fun AnnotatedString.Builder.appendLink(
    text: String,
    start: Int,
    budget: ScanBudget,
    depth: Int,
): Int {
    val close = find(text, start + 1, ']', budget)
    if (close < 0 || close + 1 >= text.length || text[close + 1] != '(') {
        append('[')
        return start + 1
    }
    val paren = find(text, close + 2, ')', budget)
    if (paren < 0) {
        append('[')
        return start + 1
    }
    if (depth < MAX_NESTING) {
        appendInline(text.substring(start + 1, close), budget, depth + 1)
    } else {
        append(text, start + 1, close)
    }
    return paren + 1
}

/**
 * How many characters this parse may still scan looking for closing delimiters.
 *
 * Shared across the whole parse rather than reset per line, because the input that costs is one
 * long line of unmatched delimiters. Once it runs out every remaining delimiter reads as
 * unmatched, so the rest of the text renders as itself -- degrading to the raw text the guide asks
 * for, rather than to a stall.
 */
private class ScanBudget(var left: Int)

private fun find(text: String, from: Int, needle: Char, budget: ScanBudget): Int =
    find(text, from, needle.toString(), budget)

private fun find(text: String, from: Int, needle: String, budget: ScanBudget): Int {
    if (budget.left <= 0) return -1
    val index = text.indexOf(needle, from)
    budget.left -= (if (index < 0) text.length - from else index - from) + 1
    return index
}

private val BOLD = SpanStyle(fontWeight = FontWeight.Bold)
private val ITALIC = SpanStyle(fontStyle = FontStyle.Italic)
private val BOLD_ITALIC = SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
private val STRIKE = SpanStyle(textDecoration = TextDecoration.LineThrough)
private val CODE = SpanStyle(fontFamily = FontFamily.Monospace)

/**
 * Heading sizes, as multiples of the surrounding text.
 *
 * `em` rather than `sp`: a host that hands `Text` a larger base style should get proportionally
 * larger headings, and absolute sizes here would quietly override its typography.
 */
private val HEADING_SCALE: List<TextUnit> =
    listOf(2.0.em, 1.5.em, 1.25.em, 1.1.em, 1.0.em, 0.9.em)

private const val ESCAPABLE = "\\`*_{}[]()#+-.!~"
/** Internal so the tests can pin the bound rather than restate its value. */
internal const val MAX_NESTING = 8
internal const val MAX_MARKDOWN_INPUT = 16_384
private const val SCAN_BUDGET_BASE = 1_024
private const val SCAN_BUDGET_PER_CHAR = 4
