package dev.ynagai.a2ui.material3

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * The least width an input reports it can shrink to, when a row is short of room.
 *
 * Material's text field is 280dp wide unless given less, and it never insists: handed a narrower
 * maximum it takes the maximum. What it does insist on is its *intrinsic* minimum, which is that
 * 280dp -- so a `Row` sharing its width from its children's minimums would pin every row a field
 * sits in at a width no phone has, and shrink the text beside it for a floor the field does not
 * actually hold. A browser's `<input>` reports a small minimum and shrinks with `flex: 0 1 auto`
 * like anything else, which is what the specification's web renderers draw; this is the same
 * floor, chosen to keep a label and a few characters legible.
 */
internal val INPUT_MIN_WIDTH: Dp = 120.dp

/**
 * Report a minimum intrinsic width of at most [min] -- the content's own if that is smaller --
 * leaving the preferred width, and what the node actually measures to, untouched.
 */
internal fun Modifier.shrinkableTo(min: Dp): Modifier = this.then(ShrinkableElement(min))

private data class ShrinkableElement(val min: Dp) : ModifierNodeElement<ShrinkableNode>() {
    override fun create() = ShrinkableNode(min)
    override fun update(node: ShrinkableNode) { node.min = min }
    override fun InspectorInfo.inspectableProperties() {
        name = "shrinkableTo"
        value = min
    }
}

private class ShrinkableNode(var min: Dp) : LayoutModifierNode, Modifier.Node() {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int): Int =
        min(min.roundToPx(), measurable.minIntrinsicWidth(height))

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int): Int =
        measurable.maxIntrinsicWidth(height)

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int): Int =
        measurable.minIntrinsicHeight(width)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int): Int =
        measurable.maxIntrinsicHeight(width)
}

/**
 * Refuse intrinsic measurement queries, the way a `SubcomposeLayout` does.
 *
 * For a component whose layout *answers* a query but should not be asked: Material's scrollable
 * tab row subcomposes its tabs and the query takes it through a subcomposition that invalidates
 * the layout it is answering for, and a `Card` around it asked once per measure never settles.
 * A `Row` or `Column` that meets the refusal measures the child as it comes and does not ask
 * again -- see `FlexMeasurePolicy.refused` -- which is the contract every other subcomposed
 * layout already holds it to.
 */
internal fun Modifier.refusesIntrinsics(): Modifier = this.then(RefusesIntrinsicsElement)

private object RefusesIntrinsicsElement : ModifierNodeElement<RefusesIntrinsicsNode>() {
    override fun create() = RefusesIntrinsicsNode()
    override fun update(node: RefusesIntrinsicsNode) = Unit
    override fun hashCode(): Int = 0x5eabe1
    override fun equals(other: Any?): Boolean = other === this
    override fun InspectorInfo.inspectableProperties() { name = "refusesIntrinsics" }
}

private class RefusesIntrinsicsNode : LayoutModifierNode, Modifier.Node() {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

    private fun refuse(): Nothing = throw IllegalStateException(
        "Asking for intrinsic measurements of this layout is not supported: it subcomposes, and " +
            "a container that shares its axis from intrinsic sizes measures it without asking.",
    )

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int): Int = refuse()
    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int): Int = refuse()
    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int): Int = refuse()
    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int): Int = refuse()
}
