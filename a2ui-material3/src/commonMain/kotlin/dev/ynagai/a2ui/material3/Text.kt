package dev.ynagai.a2ui.material3

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import dev.ynagai.a2ui.compose.ComponentRenderer
import dev.ynagai.a2ui.compose.rememberString

/**
 * `Text` -- the component every example in the corpus uses.
 *
 * The text is parsed as the Markdown subset [markdownText] covers, which is not optional polish:
 * the specification's own simplest example is a single `Text` whose entire content is
 * `# Hello, Minimal Catalog!`, and a renderer without it draws that hash.
 *
 * `variant="caption"` takes Material 3's `bodySmall` and dims the inherited content colour, which
 * is the "lighter/muted colour" the implementation guide offers as the alternative to italics.
 * Muted *relative to whatever is around it* rather than to a fixed `onSurfaceVariant`, because a
 * caption inside a filled `Button` sits on the primary colour and a surface colour there would be
 * unreadable.
 */
public val TextRenderer: ComponentRenderer = ComponentRenderer { scope, modifier ->
    val source = scope.rememberString("text")
    val caption = scope.rememberString("variant") == "caption"
    val text = remember(source) { markdownText(source.orEmpty()) }
    Text(
        text = text,
        modifier = modifier,
        style = with(MaterialTheme.typography) { if (caption) bodySmall else bodyLarge },
        // Unspecified is not "no colour": it tells `Text` to take the colour from the style, and
        // from `LocalContentColor` through it. Naming one here would break the inheritance a
        // `Button` relies on to colour its label.
        color = if (caption) LocalContentColor.current.copy(alpha = CAPTION_ALPHA) else Color.Unspecified,
    )
}

private const val CAPTION_ALPHA = 0.7f
