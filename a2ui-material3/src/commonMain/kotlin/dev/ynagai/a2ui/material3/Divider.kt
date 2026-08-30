package dev.ynagai.a2ui.material3

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
import dev.ynagai.a2ui.compose.ComponentRenderer
import dev.ynagai.a2ui.compose.rememberString

/**
 * `Divider` -- a hairline separating content.
 *
 * Material 3's two dividers are already what the guide describes: a 1dp line in the theme's
 * outline-variant colour, the horizontal one spanning the width it is given and the vertical one
 * the height. Both are leaves in the Leaf-Margin sense, so both take [leafMargin] -- a divider
 * pressed against the text above it is exactly the accumulated-spacing problem §3 is about, seen
 * from the other side.
 *
 * `axis` is read as a string and anything but `vertical` draws horizontal, which is the catalog's
 * default and the reading a malformed value degrades to.
 */
public val DividerRenderer: ComponentRenderer = ComponentRenderer { scope, modifier ->
    val axis = scope.rememberString("axis")
    if (axis == "vertical") {
        // "Spanning the height of the container" is what `VerticalDivider` does on its own: it
        // fills the height it is offered. Inside a `Row` that is the row's height, and inside an
        // unbounded parent it falls back to its own minimum rather than raising -- so a vertical
        // divider in a wrap-height row is thin and short rather than a crash.
        VerticalDivider(modifier = modifier.leafMargin())
    } else {
        HorizontalDivider(modifier = modifier.leafMargin())
    }
}
