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
import dev.ynagai.a2ui.core.protocol.Component
import kotlinx.serialization.json.JsonElement
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
            if (child.weight > 0f) {
                childModifier = childModifier.weight(child.weight)
            } else if (child.claimsMainAxis("Row")) {
                // See [claimsMainAxis]: the child would fill the row's width, and that room has to
                // be granted as a share rather than taken from its siblings.
                childModifier = childModifier.weight(1f)
            }
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
            if (child.weight > 0f) {
                childModifier = childModifier.weight(child.weight)
            } else if (child.claimsMainAxis("Column")) {
                childModifier = childModifier.weight(1f)
            }
            scope.RenderChild(child.child, childModifier)
        }
    }
}

/**
 * A child of a row or a column, with what its container has to know about it to lay it out.
 *
 * `weight` is a property of the *child* but only a row or a column can act on it -- Compose's
 * `weight` modifier exists on `RowScope` and `ColumnScope` and nowhere else, which is why
 * [ComponentRenderer] takes the modifier its parent built rather than building its own. So the
 * container reads it off each child on the child's behalf.
 */
@Immutable
private data class LaidOutChild(
    val child: A2uiChild,
    val weight: Float,
    /** The child's own component type, and its `justify` -- what [claimsMainAxis] answers from. */
    val component: String?,
    val justify: JsonElement?,
    /**
     * `axis` and `variant`, the other two properties [claimsMainAxis] reads on a child's behalf.
     *
     * Read straight off the component like `weight` and for the same reason: the catalog types
     * both as plain string enums rather than as `DynamicString`, so there is no binding to resolve.
     */
    val axis: String?,
    val variant: String?,
)

/**
 * Whether this child will claim the whole main axis of a [direction] container it sits in.
 *
 * A container whose `justify` needs room spans its parent -- see [spansItsContainer] -- and a
 * `fillMax*` taken inside a `Row` or a `Column` resolves against the space the *parent* offered,
 * not against a share of it. So a nested row asking for `center` measured itself across the whole
 * outer row and left its siblings at zero width: the sibling vanished, and nothing reported it.
 *
 * The parent is the only one that can settle this, because only a `RowScope`/`ColumnScope` can
 * hand out a share. So the container grants the asking child a `weight` instead, which bounds it
 * to a slot it can then fill -- the child gets the room it asked for, and the siblings keep theirs.
 *
 * A container is not the only child that fills. `Divider` and `Image` are leaves that take a
 * `fillMax*` of their own, and they starve a sibling exactly as a nested container does. What all
 * three have in common is the axis: a child claims a container only when it fills *along* that
 * container's main axis, which is why each case below asks about [direction] rather than about the
 * child alone. A `Column` inside a `Row`, a divider drawn across a column, an avatar that named a
 * fixed square -- none of them takes width from anyone, and none of them is granted a share.
 */
private fun LaidOutChild.claimsMainAxis(direction: String): Boolean = when (component) {
    direction -> spansAlong(direction)
    // A `Divider` fills the axis it is drawn along -- Material's `HorizontalDivider` is a
    // `fillMaxWidth` box and `VerticalDivider` a `fillMaxHeight` one. Along a container running
    // the same way, "the width of the container" is the whole container, and a hairline that took
    // all of it left every sibling measuring at zero. A divider across a column, or down a row,
    // claims nothing and is left alone.
    "Divider" -> if ((axis ?: "horizontal") == "vertical") direction == "Column" else direction == "Row"
    // The `Image` variants that fill their container do the same to a row. Including the default:
    // `mediumFeature`'s 300dp cap bounds the image but does not save the sibling, because a
    // phone-width row has less than 300dp to give -- and inside a horizontal `List`, whose items are
    // capped at 280dp, it starves the sibling at every surface width. The three fixed-size
    // variants ask for a square and are not the problem.
    "Image" -> direction == "Row" && (variant ?: "mediumFeature") !in FIXED_SIZE_IMAGE_VARIANTS
    // A `Slider` is the third leaf that fills. Material's slider lays its track across the whole
    // width it is offered -- there is no intrinsic width a track could have -- so beside a `Text`
    // in a `Row` it took the row and left the label at zero. Down a `Column` it fills the cross
    // axis, which costs nobody anything, so only a row grants it a share.
    "Slider" -> direction == "Row"
    // The two media components and the tab strip are the rest of the same family. The guide asks
    // for both media frames to "span the full width of the parent's container" and this module
    // draws them that way, and a `Tabs` is a strip that measures itself across whatever it is
    // offered. Each takes a row's whole width for the same reason a slider does, and each is
    // harmless down a column, where filling costs a sibling nothing.
    "Video", "AudioPlayer", "Tabs" -> direction == "Row"
    else -> false
}

/** The `Image` variants that name a fixed square, and so cannot claim a row -- see [claimsMainAxis]. */
private val FIXED_SIZE_IMAGE_VARIANTS = setOf("icon", "avatar", "smallFeature")

/**
 * A plain string enum read off [Component] without resolving anything.
 *
 * The catalog types `axis` and `variant` as string enums rather than as `DynamicString`, so there
 * is no binding here to evaluate -- and a value that is not a plain string is one the schema
 * already refuses. Null then reads as "the catalog's default", which is what each caller does.
 */
private fun Component?.enumProperty(name: String): String? =
    (this?.properties?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * Whether a container child of this same type will claim the whole main axis -- see
 * [claimsMainAxis], which is the only caller.
 */
private fun LaidOutChild.spansAlong(direction: String): Boolean {
    if (component != direction) return false
    // A `justify` that is not a plain string is one this side cannot read. The catalog types it as
    // a string enum, so that is already a payload the schema refuses -- but the child's own
    // renderer reads it through `rememberString`, which resolves a data binding regardless, and a
    // child that reached `fillMaxWidth` while its parent granted it nothing is the starvation this
    // function exists to stop. Unreadable therefore counts as spanning: granting a share that was
    // not needed costs a container some slack, and withholding one costs a sibling entirely.
    val justify = justify ?: return false
    val name = (justify as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: return true
    return spansItsContainer(name)
}

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
        derivedStateOf {
            allChildren().map { child ->
                val component = surface?.components?.get(child.componentId)
                LaidOutChild(
                    child = child,
                    weight = weightOf(component),
                    component = component?.component,
                    justify = component?.properties?.get("justify"),
                    axis = component.enumProperty("axis"),
                    variant = component.enumProperty("variant"),
                )
            }
        }
    }
    return value
}

/**
 * The `weight` [component] declared, or 0 when it declared none.
 *
 * Zero rather than a null, because zero is what "do not apply a weight modifier" already means to
 * the caller. Compose requires a positive weight and raises on anything else, so a non-finite or
 * non-positive number from the agent is read as absent -- one malformed property costing its own
 * layout hint rather than the surface.
 *
 * Read straight off the component rather than through `number`, because the catalog types `weight`
 * as a plain number: it is not a `DynamicValue`, so there is no binding to resolve.
 */
private fun weightOf(component: Component?): Float {
    val declared = (component?.properties?.get("weight") as? JsonPrimitive)?.doubleOrNull ?: return 0f
    // Checked *after* the narrowing, not before it. `1e39` is a finite `Double` and becomes
    // `Float.POSITIVE_INFINITY` on the way to a `Float` weight, so a guard that asked
    // `declared.isFinite()` passed exactly the value it was written to refuse -- and Compose
    // divides the free space by the weight total, so the sibling of an infinitely weighted child
    // measured at zero and disappeared.
    val weight = declared.toFloat()
    return if (weight.isFinite() && weight > 0f) weight else 0f
}

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

/**
 * `stretch` -- the catalog's default `align` -- is not forced on the children, and that is a
 * decision rather than an omission.
 *
 * Compose has no stretching `Alignment`: a cross axis is stretched by the children filling it, and
 * a `fillMax*` taken inside a `Row` or a `Column` resolves against the space the *parent* offered
 * rather than against this container's content. Written the obvious way it therefore swelled the
 * container to its parent's full extent and left every later sibling measuring at zero -- a `Row`
 * inside a `Column` hid everything below it, and a `Column` inside a `Row` hid everything after it.
 *
 * The Compose idiom that fixes that is `height(IntrinsicSize.Min)` on the container, and it was
 * tried: it works for all five components here, and it makes *every descendant* answer intrinsic
 * measurement queries. `SubcomposeLayout` cannot -- so a host registering a `LazyColumn`-backed
 * renderer, or the `List` and `Tabs` still to be written, would raise
 * `IllegalStateException: Asking for intrinsic measurements of SubcomposeLayout layouts is not
 * supported` from inside a plain `Row`. Trading a hidden sibling for a crashed surface, in exactly
 * the components that make stretching visible at all, is not a trade worth taking.
 *
 * So the children keep their natural cross-axis size and the container wraps them.
 *
 * **That was invisible while this module drew five components and is not any more.** The argument
 * here used to be that none of them painted a background or a border, which is what an equal-height
 * row is for. `Card` paints an outline and `Image` a placeholder background, so the ragged edge is
 * now something a surface can show: a column of cards squares up to the widest only by accident of
 * its content. The trade is still the one taken -- an `IllegalStateException` out of a
 * `SubcomposeLayout` descendant is worse than a ragged edge -- but it is now a visible cost rather
 * than a theoretical one, and the fix, when it comes, is a container that measures its children
 * before it sizes them rather than one that asks them how big they would like to be.
 */
private fun verticalAlignment(align: String?): Alignment.Vertical = when (align) {
    "center" -> Alignment.CenterVertically
    "end" -> Alignment.Bottom
    else -> Alignment.Top
}

/** `align` on a column's cross axis. `stretch` falls through to `start` -- see above. */
private fun horizontalAlignment(align: String?): Alignment.Horizontal = when (align) {
    "center" -> Alignment.CenterHorizontally
    "end" -> Alignment.End
    else -> Alignment.Start
}
