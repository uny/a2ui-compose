package dev.ynagai.a2ui.material3

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Color
import dev.ynagai.a2ui.compose.ComponentRenderer
import dev.ynagai.a2ui.compose.LayoutTraits
import dev.ynagai.a2ui.compose.rememberString

/**
 * `Text` -- the component every example in the corpus uses.
 *
 * The text is drawn by [LocalA2uiMarkdownRenderer] -- by default the Markdown subset [markdownText]
 * covers, which is not optional polish: the specification's own simplest example is a single
 * `Text` whose entire content is `# Hello, Minimal Catalog!`, and a renderer without it draws that
 * hash. What this renderer keeps for itself is everything around the drawing: the variant's style
 * and colour, and the leaf margin. See [A2uiMarkdownRenderer] for where the line is.
 *
 * `variant="caption"` takes Material 3's `bodySmall` and dims the inherited content colour, which
 * is the "lighter/muted colour" the implementation guide offers as the alternative to italics.
 * Muted *relative to whatever is around it* rather than to a fixed `onSurfaceVariant`, because a
 * caption inside a filled `Button` sits on the primary colour and a surface colour there would be
 * unreadable.
 */
public val TextRenderer: ComponentRenderer = ComponentRenderer(LayoutTraits.Content) { scope, modifier ->
    val source = scope.rememberString("text")
    val caption = scope.rememberString("variant") == "caption"
    // Keyed on the Markdown renderer for #31's reason: a host swapping it at runtime swaps the
    // `fun interface` implementation behind this call, and on Kotlin/Native the arriving one could
    // pick up what the outgoing one remembered -- the default `Inline` remembers its parse.
    val markdown = LocalA2uiMarkdownRenderer.current
    key(markdown) {
        markdown.Markdown(
            source = source.orEmpty(),
            modifier = modifier.leafMargin(),
            style = with(MaterialTheme.typography) { if (caption) bodySmall else bodyLarge },
            // Unspecified is not "no colour": it tells the renderer -- the default's `Text` -- to
            // take the colour from the style, and from `LocalContentColor` through it. Naming one
            // here would break the inheritance a `Button` relies on to colour its label.
            color = if (caption) LocalContentColor.current.copy(alpha = CAPTION_ALPHA) else Color.Unspecified,
        )
    }
}

private const val CAPTION_ALPHA = 0.7f
