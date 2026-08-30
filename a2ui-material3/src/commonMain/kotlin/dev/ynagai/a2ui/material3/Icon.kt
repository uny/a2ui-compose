package dev.ynagai.a2ui.material3

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import dev.ynagai.a2ui.compose.ComponentRenderer
import dev.ynagai.a2ui.compose.rememberString
import kotlinx.serialization.json.JsonObject

/**
 * `Icon` -- one of the catalog's fifty-nine names, or a path the agent drew itself.
 *
 * The catalog types `name` three ways, and all three arrive here as the same property: a string
 * from the enum, an object carrying `svgPath`, or a data binding that resolves to one of the enum's
 * strings. The first and third are the same read -- `rememberString` resolves a binding and returns
 * the literal unchanged -- and the second is picked out ahead of it, because an object is not a
 * `DynamicString` and would resolve to null.
 *
 * **No colour of its own.** Material 3's `Icon` tints with `LocalContentColor`, which is exactly
 * §4's rule for leaves: an icon inside a filled `Button` picks up the button's content colour
 * without anything passing it down, and an icon that named its own colour would be the unreadable
 * case that section is about.
 *
 * A name that is neither in [ICON_PATHS] nor a readable path draws as an empty 24dp square rather
 * than as nothing. The catalog closes the enum, so an unknown name is a payload the schema already
 * refuses -- `CatalogValidator` is what reports it -- and a renderer that collapsed the space would
 * shift every sibling in the row as well.
 */
public val IconRenderer: ComponentRenderer = ComponentRenderer { scope, modifier ->
    // The `svgPath` form, read before the string form. Resolved through `dynamicString` rather
    // than read as a literal, because the catalog types `svgPath` as a `DynamicString` like any
    // other -- it sits nested inside an object property, which is the one place a binding needs
    // resolving by hand rather than by a typed accessor.
    val drawn = (scope.property("name") as? JsonObject)
        ?.get("svgPath")
        ?.let { scope.dynamicString(it) }
        ?.takeIf { it.length <= MAX_SVG_PATH }
    val named = scope.rememberString("name")
    val data = drawn ?: named?.let { ICON_PATHS[it] }
    // **Whose path it is decides the fill rule.** An agent's `svgPath` is SVG, and SVG's
    // `fill-rule` defaults to non-zero -- so a glyph whose contours overlap to make one solid
    // shape, which is the ordinary way to draw one, came out with the overlap punched into a hole.
    // The glyphs in [ICON_PATHS] are the other case: they are drawn here, against even-odd, and
    // their inner contours are meant as holes. Reading each under the rule it was authored with is
    // the only way one code path can serve both.
    val fill = if (drawn != null) PathFillType.NonZero else PathFillType.EvenOdd
    val vector = remember(data, fill) { data?.let { iconVector(it, fill) } }
    if (vector == null) {
        Spacer(modifier.leafMargin().size(ICON_SIZE))
    } else {
        Icon(imageVector = vector, contentDescription = null, modifier = modifier.leafMargin())
    }
}

/** The guide's suggested icon size, and the grid [ICON_PATHS] is drawn on. */
internal val ICON_SIZE = 24.dp

/**
 * The longest `svgPath` an agent may hand this renderer, in characters.
 *
 * Every other agent-controlled input in this library is bounded -- the evaluator caps a subject at
 * 2048 characters and a pattern at 1024, and the surface caps instances at 5000. A path arrives
 * through [A2uiComponentScope.property][dev.ynagai.a2ui.compose.A2uiComponentScope.property],
 * which reads the literal JSON and so passes none of those, and it is parsed and rasterised once
 * per instance that draws it. 8192 is roughly twenty times the longest glyph in [ICON_PATHS] and
 * so is not a bound any real icon meets; what it refuses is the payload that is not an icon.
 */
private const val MAX_SVG_PATH = 8192

/**
 * [pathData] as a 24dp vector under [fill], or null when it will not parse.
 *
 * The fill rule is the caller's to choose and is not a detail -- see [IconRenderer], where the
 * built-in glyphs and an agent's own path are read under different ones.
 *
 * The fill colour is black and is never seen: Material 3's `Icon` tints the whole vector with the
 * current content colour. Naming any other colour here would be naming one that gets overwritten.
 */
internal fun iconVector(pathData: String, fill: PathFillType = PathFillType.EvenOdd): ImageVector? {
    val nodes: List<PathNode> = runCatching {
        PathParser().parsePathString(pathData).toNodes()
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
    return ImageVector.Builder(
        defaultWidth = ICON_SIZE,
        defaultHeight = ICON_SIZE,
        viewportWidth = ICON_VIEWPORT,
        viewportHeight = ICON_VIEWPORT,
    ).apply {
        addPath(pathData = nodes, fill = SolidColor(Color.Black), pathFillType = fill)
    }.build()
}

private const val ICON_VIEWPORT = 24f
