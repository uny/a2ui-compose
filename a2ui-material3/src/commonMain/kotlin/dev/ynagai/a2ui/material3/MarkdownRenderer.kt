package dev.ynagai.a2ui.material3

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/**
 * How a `Text` draws its Markdown.
 *
 * The default, [Inline], is the parser-free subset [markdownText] describes: headings, emphasis,
 * code spans, escapes -- and lists, tables, block quotes and fenced code passed through as the
 * characters they are. That gap is a real one, and this seam is how a host closes it without
 * giving up the rest of `Text`: a host that already ships a Markdown renderer hands it in here and
 * keeps the style resolution, the caption variant and the leaf margin that `Text` would otherwise
 * make it reimplement. `a2ui-material3-markdown` ships one, `Material3MarkdownRenderer`, for a
 * host that has none and does not mind the parser it brings.
 *
 * ```kotlin
 * CompositionLocalProvider(
 *     LocalA2uiMarkdownRenderer provides A2uiMarkdownRenderer { source, style, color, modifier ->
 *         MyMarkdown(source, style = style, color = color, modifier = modifier)
 *     },
 * ) { A2uiSurface(/* ... */) }
 * ```
 *
 * A composable rather than a `String -> AnnotatedString`, because the constructs the default
 * lacks are the ones that cannot be spelled as spans: a table is a layout, not a string with
 * styles on it. A formatter seam would have let a host swap the inline decoration and nothing
 * else, which is not the gap.
 *
 * What the seam hands over is the whole of the text's drawing and nothing more. The [source] is
 * the agent's, unparsed and **unbounded**: the length cap and scan budget that keep [Inline]'s
 * parse from being made quadratic by its input are [Inline]'s own, so an implementation that
 * parses brings its own bound. The [style] and [color] are what `Text` resolved for the variant --
 * the caption's `bodySmall` and dimmed colour, and for body text `Color.Unspecified`, which means
 * "the style's, and `LocalContentColor` through it" rather than no colour, so a `Button`'s label
 * keeps the button's colour; an implementation that ignores them draws captions the size of body
 * text. The [modifier] already carries the leaf margin, and goes on the outermost thing drawn, as
 * [A2uiImageLoader]'s does.
 *
 * The specification's exclusions -- no HTML, images, or live links -- are the implementation's to
 * keep; nothing here can keep them on its behalf. [Inline] reduces a link to its label so an agent
 * has no way to open a URL that bypasses `openUrl` and its user-gesture rule, and a host renderer
 * that makes links tappable hands the agent exactly that.
 */
@Stable
public fun interface A2uiMarkdownRenderer {
    /** Draws [source], styled by [style] and [color], placed by [modifier]. */
    @Composable
    public fun Markdown(source: String, style: TextStyle, color: Color, modifier: Modifier)

    public companion object {
        /**
         * The default: [markdownText]'s subset in a single Material 3 `Text`.
         *
         * Public so a host can compose on top of it -- fall back to it for the sources its own
         * renderer declines, or wrap it -- rather than only replace it.
         */
        public val Inline: A2uiMarkdownRenderer = A2uiMarkdownRenderer { source, style, color, modifier ->
            val text = remember(source) { markdownText(source) }
            Text(text = text, modifier = modifier, style = style, color = color)
        }
    }
}

/**
 * The Markdown renderer in effect.
 *
 * Non-null, like [LocalA2uiStrings] and unlike [LocalA2uiImageLoader]: there is always something
 * reasonable to draw here, and it is what `Text` has always drawn.
 */
public val LocalA2uiMarkdownRenderer: ProvidableCompositionLocal<A2uiMarkdownRenderer> =
    staticCompositionLocalOf { A2uiMarkdownRenderer.Inline }
