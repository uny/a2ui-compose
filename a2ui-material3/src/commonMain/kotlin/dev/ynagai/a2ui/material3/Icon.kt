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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
    // The `svgPath` form, read before the string form. Only a literal path is drawn: `svgPath` is
    // typed as a `DynamicString`, and resolving a *nested* binding needs an evaluation context that
    // the scope exposes only for its own top-level properties. An agent that binds it gets the
    // empty square below, which is the same degradation an unknown name gets.
    val drawn = (scope.property("name") as? JsonObject)
        ?.let { (it["svgPath"] as? JsonPrimitive)?.contentOrNull }
    val named = scope.rememberString("name")
    val data = drawn ?: named?.let { ICON_PATHS[it] }
    val vector = remember(data) { data?.let { iconVector(it) } }
    if (vector == null) {
        Spacer(modifier.leafMargin().size(ICON_SIZE))
    } else {
        Icon(imageVector = vector, contentDescription = null, modifier = modifier.leafMargin())
    }
}

/** The guide's suggested icon size, and the grid [ICON_PATHS] is drawn on. */
private val ICON_SIZE = 24.dp

/**
 * [pathData] as a 24dp vector, or null when it will not parse.
 *
 * Filled even-odd rather than non-zero, which is what makes the holes holes: the paths here draw a
 * shape and then the shape cut out of it -- the ring of `accountCircle`, the lens of `settings` --
 * in the same winding direction, and non-zero would fill both as one solid blob.
 *
 * The fill is black and is never seen: Material 3's `Icon` tints the whole vector with the current
 * content colour. Naming any other colour here would be naming one that gets overwritten.
 */
private fun iconVector(pathData: String): ImageVector? {
    val nodes: List<PathNode> = runCatching {
        PathParser().parsePathString(pathData).toNodes()
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
    return ImageVector.Builder(
        defaultWidth = ICON_SIZE,
        defaultHeight = ICON_SIZE,
        viewportWidth = ICON_VIEWPORT,
        viewportHeight = ICON_VIEWPORT,
    ).apply {
        addPath(pathData = nodes, fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd)
    }.build()
}

private const val ICON_VIEWPORT = 24f
