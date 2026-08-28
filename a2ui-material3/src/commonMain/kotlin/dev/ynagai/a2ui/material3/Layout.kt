package dev.ynagai.a2ui.material3

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.ynagai.a2ui.compose.A2uiChild
import dev.ynagai.a2ui.compose.A2uiComponentScope
import dev.ynagai.a2ui.compose.ComponentRenderer
import dev.ynagai.a2ui.compose.RenderChild
import dev.ynagai.a2ui.compose.rememberString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * `Row` -- children laid out horizontally.
 *
 * `justify` is the main axis and `align` the cross axis, which for a row means
 * `horizontalArrangement` and `verticalAlignment` -- the implementation guide names both Compose
 * properties directly.
 */
public val RowRenderer: ComponentRenderer = ComponentRenderer { scope, modifier ->
    val justify = scope.rememberString("justify")
    val align = scope.rememberString("align")
    val children = scope.rememberLaidOutChildren()
    Row(
        modifier = if (spansItsContainer(justify)) modifier.fillMaxWidth() else modifier,
        horizontalArrangement = horizontalArrangement(justify),
        verticalAlignment = verticalAlignment(align),
    ) {
        children.forEach { child ->
            var childModifier: Modifier = Modifier
            if (child.weight > 0f) childModifier = childModifier.weight(child.weight)
            if (isStretch(align)) childModifier = childModifier.fillMaxHeight()
            scope.RenderChild(child.child, childModifier)
        }
    }
}

/**
 * `Column` -- children laid out vertically.
 *
 * The mirror of [RowRenderer] in every respect, [spansItsContainer] included: a column whose
 * `justify` spreads its children needs a bounded *height* to spread them within, exactly as a row
 * needs a bounded width.
 */
public val ColumnRenderer: ComponentRenderer = ComponentRenderer { scope, modifier ->
    val justify = scope.rememberString("justify")
    val align = scope.rememberString("align")
    val children = scope.rememberLaidOutChildren()
    Column(
        modifier = if (spansItsContainer(justify)) modifier.fillMaxHeight() else modifier,
        verticalArrangement = verticalArrangement(justify),
        horizontalAlignment = horizontalAlignment(align),
    ) {
        children.forEach { child ->
            var childModifier: Modifier = Modifier
            if (child.weight > 0f) childModifier = childModifier.weight(child.weight)
            if (isStretch(align)) childModifier = childModifier.fillMaxWidth()
            scope.RenderChild(child.child, childModifier)
        }
    }
}

/**
 * A child of a row or a column, with the `weight` it declared.
 *
 * `weight` is a property of the *child* but only a row or a column can act on it -- Compose's
 * `weight` modifier exists on `RowScope` and `ColumnScope` and nowhere else, which is why
 * [ComponentRenderer] takes the modifier its parent built rather than building its own. So the
 * container reads it off each child on the child's behalf.
 */
@Immutable
private data class LaidOutChild(val child: A2uiChild, val weight: Float)

/**
 * Every child, paired with its weight, recomposing the container only when that layout changes.
 *
 * Reading `weight` means reading the surface's components, and a bare read would subscribe the
 * container to every write the surface takes -- including data model writes, which cannot change
 * a weight. Inside a `derivedStateOf` the recomputation still happens and the equal result is
 * discarded without invalidating anyone, which is the granularity the adapter layer buys and this
 * would otherwise spend.
 */
@Composable
private fun A2uiComponentScope.rememberLaidOutChildren(): List<LaidOutChild> {
    val value by remember(this) {
        derivedStateOf { allChildren().map { LaidOutChild(it, weightOf(it)) } }
    }
    return value
}

/**
 * The `weight` [child] declared, or 0 when it declared none.
 *
 * Zero rather than a null, because zero is what "do not apply a weight modifier" already means to
 * the caller. Compose requires a positive weight and raises on anything else, so a non-finite or
 * non-positive number from the agent is read as absent -- one malformed property costing its own
 * layout hint rather than the surface.
 *
 * Read straight off the component rather than through `number`, because the catalog types `weight`
 * as a plain number: it is not a `DynamicValue`, so there is no binding to resolve.
 */
private fun A2uiComponentScope.weightOf(child: A2uiChild): Float {
    val component = surface?.components?.get(child.componentId) ?: return 0f
    val declared = (component.properties["weight"] as? JsonPrimitive)?.doubleOrNull ?: return 0f
    return if (declared.isFinite() && declared > 0.0) declared.toFloat() else 0f
}

/**
 * Whether `align` asks for the cross axis to be stretched.
 *
 * Null included, because `stretch` is the catalog's default for both containers. Compose has no
 * stretching `Alignment` -- the cross axis is stretched by the *children* filling it, so this
 * answers a question about the child modifier rather than about the container's alignment.
 */
private fun isStretch(align: String?): Boolean = align == null || align == "stretch"

/**
 * Whether this container has to span its parent along its own main axis.
 *
 * The implementation guide asks a row to "fill the available width", and taken literally that is
 * wrong for a row inside a row: the inner one claims the whole width and the outer one's
 * `spaceBetween` has nothing left to spread. The specification's own `01_flight-status` is that
 * payload -- a `spaceBetween` row holding a row and a date -- so this is a layout the corpus
 * breaks, not a hypothetical.
 *
 * What the guide is actually protecting is the arrangements that need a container to spread
 * within: `center`, `end` and the three `space*` values all do nothing in a container shrunk to
 * its contents. `start` does not, and `stretch` is the children's job. So a container spans when
 * spanning changes where its children land, and wraps when it would not -- which leaves the outer
 * row's own spread intact, because a nested row is almost always `start`.
 *
 * A nested container that *does* ask for a spreading arrangement still spans, and that is the
 * agent asking for it rather than this rule guessing.
 */
private fun spansItsContainer(justify: String?): Boolean = when (justify) {
    "center", "end", "spaceAround", "spaceBetween", "spaceEvenly" -> true
    else -> false
}

/**
 * `justify` on a horizontal main axis.
 *
 * `stretch` falls through to `start`, and that is the honest reading rather than an oversight:
 * stretching along the main axis is what `weight` does in Compose, and a weightless child has no
 * size to stretch to. A child that wants it says so with `weight`.
 */
private fun horizontalArrangement(justify: String?): Arrangement.Horizontal = when (justify) {
    "center" -> Arrangement.Center
    "end" -> Arrangement.End
    "spaceAround" -> Arrangement.SpaceAround
    "spaceBetween" -> Arrangement.SpaceBetween
    "spaceEvenly" -> Arrangement.SpaceEvenly
    else -> Arrangement.Start
}

/** `justify` on a vertical main axis. `stretch` falls through to `start`, as in a row. */
private fun verticalArrangement(justify: String?): Arrangement.Vertical = when (justify) {
    "center" -> Arrangement.Center
    "end" -> Arrangement.Bottom
    "spaceAround" -> Arrangement.SpaceAround
    "spaceBetween" -> Arrangement.SpaceBetween
    "spaceEvenly" -> Arrangement.SpaceEvenly
    else -> Arrangement.Top
}

/** `align` on a row's cross axis. `stretch` is the children's job -- see [isStretch]. */
private fun verticalAlignment(align: String?): Alignment.Vertical = when (align) {
    "center" -> Alignment.CenterVertically
    "end" -> Alignment.Bottom
    else -> Alignment.Top
}

/** `align` on a column's cross axis. `stretch` is the children's job -- see [isStretch]. */
private fun horizontalAlignment(align: String?): Alignment.Horizontal = when (align) {
    "center" -> Alignment.CenterHorizontally
    "end" -> Alignment.End
    else -> Alignment.Start
}
