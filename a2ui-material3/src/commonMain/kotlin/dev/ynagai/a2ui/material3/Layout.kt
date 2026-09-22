package dev.ynagai.a2ui.material3

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.LayoutIdParentData
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import dev.ynagai.a2ui.compose.A2uiChild
import dev.ynagai.a2ui.compose.A2uiComponentScope
import dev.ynagai.a2ui.compose.ComponentRenderer
import dev.ynagai.a2ui.compose.LayoutAxis
import dev.ynagai.a2ui.compose.LayoutTraits
import dev.ynagai.a2ui.compose.LocalA2uiRegistry
import dev.ynagai.a2ui.compose.MainAxisFit
import dev.ynagai.a2ui.compose.RenderChild
import dev.ynagai.a2ui.compose.answersIntrinsics
import dev.ynagai.a2ui.compose.rememberString
import dev.ynagai.a2ui.core.protocol.Component
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * `Row` -- children laid out horizontally.
 *
 * `justify` is the main axis and `align` the cross axis, which for a row means
 * `horizontalArrangement` and `verticalAlignment` -- the implementation guide names both Compose
 * properties directly. The layout itself is [Flex] rather than Compose's `Row`, for the reason
 * given there.
 */
public val RowRenderer: ComponentRenderer = ComponentRenderer(
    traits = { component, axis -> containerTraits(component, axis, LayoutAxis.Horizontal) },
) { scope, modifier ->
    val justify = scope.rememberString("justify")
    val align = scope.rememberString("align")
    val children = scope.rememberLaidOutChildren(LayoutAxis.Horizontal)
    Flex(
        axis = LayoutAxis.Horizontal,
        modifier = if (spansItsContainer(justify)) modifier.fillMaxWidth() else modifier,
        arrangement = horizontalArrangement(justify),
        crossAlignment = crossAlignment(align),
        children = children,
        scope = scope,
    )
}

/**
 * `Column` -- children laid out vertically.
 *
 * The mirror of [RowRenderer] in every respect, [spansItsContainer] included: a column whose
 * `justify` spreads its children needs a bounded *height* to spread them within, exactly as a row
 * needs a bounded width.
 */
public val ColumnRenderer: ComponentRenderer = ComponentRenderer(
    traits = { component, axis -> containerTraits(component, axis, LayoutAxis.Vertical) },
) { scope, modifier ->
    val justify = scope.rememberString("justify")
    val align = scope.rememberString("align")
    val children = scope.rememberLaidOutChildren(LayoutAxis.Vertical)
    Flex(
        axis = LayoutAxis.Vertical,
        modifier = if (spansItsContainer(justify)) modifier.fillMaxHeight() else modifier,
        arrangement = verticalArrangement(justify),
        crossAlignment = crossAlignment(align),
        children = children,
        scope = scope,
    )
}

/**
 * The [LayoutTraits] of a `Row` or `Column` running along [own], seen from a container running
 * along [axis].
 *
 * A container whose `justify` needs room spans its parent -- see [spansItsContainer] -- and a
 * `fillMax*` taken inside another container resolves against the space the *parent* offered, not
 * against a share of it. So a nested row asking for `center` measured itself across the whole outer
 * row and left its siblings at zero width: the sibling vanished, and nothing reported it. The
 * parent is the only one that can settle this, and it does so by giving the asking child a share
 * ([MainAxisFit.Fill]) instead of letting it take the lot. Across the other axis -- a `Column` in a
 * `Row` -- spanning takes width from nobody, and the container is content-sized like anything else.
 *
 * A `justify` that is not a plain string is one this side cannot read. The catalog types it as a
 * string enum, so that is already a payload the schema refuses -- but the child's own renderer
 * reads it through `rememberString`, which resolves a data binding regardless, and a child that
 * reached `fillMaxWidth` while its parent granted it nothing is the starvation this exists to stop.
 * Unreadable therefore counts as spanning: a share that was not needed costs the container some
 * slack, and withholding one costs a sibling entirely.
 */
private fun containerTraits(component: Component, axis: LayoutAxis, own: LayoutAxis): LayoutTraits {
    if (axis != own) return LayoutTraits.Content
    val justify = component.properties["justify"] ?: return LayoutTraits.Content
    val name = (justify as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: return LayoutTraits.Fill
    return if (spansItsContainer(name)) LayoutTraits.Fill else LayoutTraits.Content
}

/**
 * A child of a row or a column, with what its container has to know about it to lay it out.
 *
 * `weight` is a property of the *child* but only a row or a column can act on it, which is why the
 * container reads it off each child on the child's behalf. [traits] are the child renderer's own
 * word on how it fits the axis, and [answersIntrinsics] whether the whole subtree under it keeps
 * that word -- see [LayoutTraits] for why a container needs both.
 */
@Immutable
private data class LaidOutChild(
    val child: A2uiChild,
    val weight: Float,
    val traits: LayoutTraits,
    val answersIntrinsics: Boolean,
)

/**
 * Every child, paired with what [Flex] needs to know, recomposing the container only when that
 * changes.
 *
 * Reading `weight` means reading the surface's components, and a bare read would subscribe the
 * container to every write the surface takes -- including data model writes, which cannot change
 * a weight. Inside a `derivedStateOf` the recomputation still happens and the equal result is
 * discarded without invalidating anyone, which is the granularity the adapter layer buys and this
 * would otherwise spend. The subtree walk behind [answersIntrinsics] is inside the same derivation
 * for the same reason: it reads the component graph and nothing else.
 */
@Composable
private fun A2uiComponentScope.rememberLaidOutChildren(axis: LayoutAxis): List<LaidOutChild> {
    val registry = LocalA2uiRegistry.current
    val value by remember(this, registry, axis) {
        derivedStateOf {
            allChildren().map { child ->
                val component = surface?.components?.get(child.componentId)
                val renderer = component?.let { registry[it.component] }
                LaidOutChild(
                    child = child,
                    weight = weightOf(component),
                    // A component the surface does not hold, or a type the registry cannot draw,
                    // is drawn as a placeholder -- plain layout, content-sized.
                    traits = if (component != null && renderer != null) {
                        renderer.layoutTraits(component, axis)
                    } else {
                        LayoutTraits.Content
                    },
                    answersIntrinsics = answersIntrinsics(child, registry),
                )
            }
        }
    }
    return value
}

/**
 * The `weight` [component] declared, or 0 when it declared none.
 *
 * Zero rather than a null, because zero is what "no share" already means to the layout. A
 * non-finite or non-positive number from the agent is read as absent -- one malformed property
 * costing its own layout hint rather than the surface.
 *
 * Read straight off the component rather than through `number`, because the catalog types `weight`
 * as a plain number: it is not a `DynamicValue`, so there is no binding to resolve.
 */
private fun weightOf(component: Component?): Float {
    val declared = (component?.properties?.get("weight") as? JsonPrimitive)?.doubleOrNull ?: return 0f
    // Checked *after* the narrowing, not before it. `1e39` is a finite `Double` and becomes
    // `Float.POSITIVE_INFINITY` on the way to a `Float` weight, so a guard that asked
    // `declared.isFinite()` passed exactly the value it was written to refuse -- and the free space
    // is divided by the weight total, so the sibling of an infinitely weighted child measured at
    // zero and disappeared.
    val weight = declared.toFloat()
    return if (weight.isFinite() && weight > 0f) weight else 0f
}

/**
 * A plain string enum read off a [Component] without resolving anything.
 *
 * The catalog types `axis` and `variant` as string enums rather than as `DynamicString`, so there
 * is no binding here to evaluate -- and a value that is not a plain string is one the schema
 * already refuses. Null then reads as "the catalog's default", which is what each caller does.
 * For the renderers' [LayoutTraits], which are asked off the component before anything is drawn.
 */
internal fun Component.enumProperty(name: String): String? =
    (properties[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

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
 * stretching along the main axis is what `weight` does, and a weightless child has no size to
 * stretch to. A child that wants it says so with `weight`.
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

/** Where a child sits across the axis, as a fraction of the slack: `start` is 0, `end` is 1. */
private enum class CrossAlignment(val fraction: Float) { Start(0f), Center(0.5f), End(1f) }

/**
 * `align`, the cross axis. `stretch` -- the catalog's default -- falls through to `start`, and
 * that is a decision rather than an omission.
 *
 * Compose has no stretching `Alignment`: a cross axis is stretched by the children filling it, and
 * a `fillMax*` taken inside a container resolves against the space the *parent* offered rather
 * than against this container's content. Written the obvious way it therefore swelled the
 * container to its parent's full extent and left every later sibling measuring at zero -- a `Row`
 * inside a `Column` hid everything below it, and a `Column` inside a `Row` hid everything after it.
 *
 * The Compose idiom that fixes that is `height(IntrinsicSize.Min)` on the container, which makes
 * *every descendant* answer intrinsic measurement queries; a `SubcomposeLayout` cannot, and raises.
 * [Flex] now knows which children can be asked -- that is what [LayoutTraits.answersIntrinsics]
 * is for -- so a stretch that asks only where asking is safe, and degrades to `start` elsewhere,
 * is the next step and not this one. Until then the children keep their natural cross-axis size
 * and the container wraps them, and a column of cards squares up to the widest only by accident of
 * its content.
 */
private fun crossAlignment(align: String?): CrossAlignment = when (align) {
    "center" -> CrossAlignment.Center
    "end" -> CrossAlignment.End
    else -> CrossAlignment.Start
}

/**
 * A `Row` or `Column` that shares its main axis the way the specification's web renderers do.
 *
 * The official renderers are CSS flexbox: a weightless child is `flex: 0 1 auto` -- laid out at
 * its preferred size, shrunk in proportion when the container is short of room, never below its
 * minimum -- and a weighted child grows into what is left. Compose's own `Row` cannot do the
 * first half. It measures weightless children in order, each against whatever its predecessors
 * left, so a `Column` of text that wraps at the row's full width leaves the price beside it
 * measuring at zero: the specification's `13_coffee-order` with a long item name, and every
 * `label`/`value` pair in the corpus once the label is long enough. A parent cannot measure a
 * child, find it too big, and measure it again -- Compose measures once -- so fairness has to be
 * decided *before* measuring, from the sizes the children would like.
 *
 * That is what intrinsic measurement is for, and what [LayoutTraits.answersIntrinsics] gates:
 *
 * 1. Children that cannot be asked are measured first, in order, each capped so that the children
 *    that *can* be asked keep at least their minimum. They take what they take.
 * 2. Children that can be asked report their preferred and minimum sizes along the axis. If the
 *    preferred sizes fit in what is left, each gets its preferred size; if not, the deficit is
 *    taken from each in proportion to its preferred size, no child going below its minimum, the
 *    ones pinned at their minimum dropping out and the rest absorbing their share -- the
 *    flex-shrink algorithm. Order plays no part.
 * 3. Weighted children divide what remains by weight. One with an explicit `weight` is measured
 *    to exactly its share, as Compose's own `weight` does and as `flex: N` grows on the web. One
 *    that fills only because its renderer says it [MainAxisFit.Fill]s -- a text field, a slider,
 *    an image -- is measured *up to* its share: a field takes its 280dp when the row has it and
 *    the share when it does not, which is the web's `flex: 0 1 auto` for the same input, and
 *    leaves `justify` something to arrange when the row is wide. So is a content child that
 *    reports no preferred size at all: a `Card` around a banner image has nothing to say about
 *    its width because the image fills whatever it is given, and a basis of zero would have
 *    measured it to nothing.
 *
 * Along an unbounded axis -- a horizontally scrolling `List` -- nothing is short of room, so every
 * child gets its preferred size and a weighted one is measured loosely. `justify` is applied
 * through Compose's own `Arrangement`s, so the spacing semantics are the ones `Row` has; `align`
 * is [crossAlignment]. The layout answers intrinsic queries of its own by summing (along the
 * axis) or taking the largest (across it) of its children's, which is what lets a nested `Row`
 * take part in its parent's sharing.
 */
@Composable
private fun Flex(
    axis: LayoutAxis,
    modifier: Modifier,
    arrangement: Any,
    crossAlignment: CrossAlignment,
    children: List<LaidOutChild>,
    scope: A2uiComponentScope,
) {
    val policy = remember(axis, arrangement, crossAlignment, children) {
        FlexMeasurePolicy(axis, arrangement, crossAlignment, children)
    }
    Layout(
        content = {
            // The index is the id, and the policy reads it back: a renderer that dropped the
            // modifier it was handed is measured as a child that said nothing about itself.
            children.forEachIndexed { index, child ->
                scope.RenderChild(child.child, Modifier.layoutId(index))
            }
        },
        modifier = modifier,
        measurePolicy = policy,
    )
}

private class FlexMeasurePolicy(
    axis: LayoutAxis,
    private val arrangement: Any,
    private val crossAlignment: CrossAlignment,
    private val children: List<LaidOutChild>,
) : MeasurePolicy {
    private val horizontal = axis == LayoutAxis.Horizontal

    /** What the policy knows about one measurable: its child's declaration, or nothing. */
    private class Spec(val weight: Float, val fill: Boolean, val askable: Boolean) {
        /** Weighted, explicitly or by filling: measured last, to a share of what is left. */
        val share: Float get() = if (weight > 0f) weight else if (fill) 1f else 0f

        /** The share a filler counts for when it joined the weighted late -- see the fillers in `measure`. */
        val shareOrOne: Float get() = if (share > 0f) share else 1f
    }

    private fun specOf(measurable: IntrinsicMeasurable): Spec {
        val child = ((measurable.parentData as? LayoutIdParentData)?.layoutId as? Int)?.let(children::getOrNull)
            ?: return Spec(weight = 0f, fill = false, askable = false)
        return Spec(
            weight = child.weight,
            fill = child.traits.fit == MainAxisFit.Fill,
            askable = child.traits.answersIntrinsics && child.answersIntrinsics,
        )
    }

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val specs = measurables.map(::specOf)
        val mainMax = if (horizontal) constraints.maxWidth else constraints.maxHeight
        val crossMax = if (horizontal) constraints.maxHeight else constraints.maxWidth
        val bounded = mainMax != Constraints.Infinity
        val placeables = arrayOfNulls<Placeable>(measurables.size)
        var remaining = mainMax

        fun measure(index: Int, min: Int, max: Int) {
            // `fitPrioritizing*` rather than the constructor, which throws past 2^18 - 2 in a
            // dimension. A text's minimum intrinsic width is its longest word, and the agent
            // chooses the words; a floor that outgrows what `Constraints` can hold is clamped to
            // it -- the text is measured as wide as anything can be, and wraps from there.
            val c = if (horizontal) {
                Constraints.fitPrioritizingWidth(minWidth = min, maxWidth = max, minHeight = 0, maxHeight = crossMax)
            } else {
                Constraints.fitPrioritizingHeight(minWidth = 0, maxWidth = crossMax, minHeight = min, maxHeight = max)
            }
            val placeable = measurables[index].measure(c)
            placeables[index] = placeable
            if (bounded) remaining = max(0, remaining - placeable.main())
        }

        val content = specs.indices.filter { specs[it].share == 0f }.toMutableList()
        // A child that can be asked and answers "nothing" -- a wrapper around something that fills
        // whatever it is given -- has no size to be fair about, and joins the fillers.
        val fillers = content.filter { specs[it].askable && measurables[it].maxIntrinsicMain(crossMax) == 0 }
        content -= fillers.toSet()
        val (askable, unasked) = content.partition { specs[it].askable }

        // 1. The children that cannot be asked, capped so the askable ones keep their minimum.
        val minimums = askable.associateWith { measurables[it].minIntrinsicMain(crossMax) }
        val reserve = minimums.values.sum()
        for (index in unasked) {
            measure(index, 0, if (bounded) max(0, remaining - reserve) else Constraints.Infinity)
        }

        // 2. The askable ones, at their preferred size or a fair shrink of it.
        if (askable.isNotEmpty()) {
            val basis = IntArray(askable.size) { measurables[askable[it]].maxIntrinsicMain(crossMax) }
            val floor = IntArray(askable.size) { minimums.getValue(askable[it]) }
            val targets = if (bounded && basis.sum() > remaining) shrink(basis, floor, remaining) else basis
            askable.forEachIndexed { slot, index -> measure(index, 0, targets[slot]) }
        }

        // 3. The weighted ones, to their share of what is left: exactly, for an explicit weight;
        //    up to, for a child that only fills.
        val weighted = specs.indices.filter { specs[it].share > 0f } + fillers
        if (weighted.isNotEmpty()) {
            if (bounded) {
                val free = remaining
                val total = weighted.sumOf { specs[it].shareOrOne.toDouble() }
                var handed = 0
                weighted.forEachIndexed { slot, index ->
                    val share = if (slot == weighted.lastIndex) {
                        max(0, free - handed)
                    } else {
                        // In doubles: a weight is any finite `Float`, and `free` times a large one
                        // overflows a `Float` to infinity, which rounds to `Int.MAX_VALUE`.
                        (free.toDouble() * specs[index].shareOrOne / total).roundToInt().also { handed += it }
                    }
                    measure(index, if (specs[index].weight > 0f) share else 0, share)
                }
            } else {
                for (index in weighted) measure(index, 0, Constraints.Infinity)
            }
        }

        val sizes = IntArray(placeables.size) { placeables[it]!!.main() }
        val mainSize = if (horizontal) constraints.constrainWidth(sizes.sum()) else constraints.constrainHeight(sizes.sum())
        val crossUsed = placeables.maxOfOrNull { it!!.cross() } ?: 0
        val crossSize = if (horizontal) constraints.constrainHeight(crossUsed) else constraints.constrainWidth(crossUsed)
        val positions = IntArray(sizes.size)
        when (arrangement) {
            is Arrangement.Horizontal -> with(arrangement) { arrange(mainSize, sizes, layoutDirection, positions) }
            is Arrangement.Vertical -> with(arrangement) { arrange(mainSize, sizes, positions) }
        }
        val width = if (horizontal) mainSize else crossSize
        val height = if (horizontal) crossSize else mainSize
        return layout(width, height) {
            placeables.forEachIndexed { index, placeable ->
                val across = ((crossSize - placeable!!.cross()) * crossAlignment.fraction).roundToInt()
                // A horizontal arrangement has already taken the layout direction into account and
                // handed back physical x positions, so they are placed as they are; `placeRelative`
                // would mirror them a second time and turn an RTL row back into an LTR one. A
                // column's cross axis is the one still in need of mirroring, so that `align: start`
                // sits on the right under RTL as it does in Compose's own `Column`.
                if (horizontal) placeable.place(positions[index], across)
                else placeable.placeRelative(across, positions[index])
            }
        }
    }

    private fun Placeable.main() = if (horizontal) width else height
    private fun Placeable.cross() = if (horizontal) height else width

    private fun IntrinsicMeasurable.minIntrinsicMain(cross: Int) =
        if (horizontal) minIntrinsicWidth(cross) else minIntrinsicHeight(cross)

    private fun IntrinsicMeasurable.maxIntrinsicMain(cross: Int) =
        if (horizontal) maxIntrinsicWidth(cross) else maxIntrinsicHeight(cross)

    // The container's own intrinsics: a sum along the axis, the largest across it. A parent asks
    // these only when this container's subtree answers them, which `answersIntrinsics` decided.
    //
    // Along the axis, a weighted child contributes nothing to the *minimum*. It is measured to
    // whatever is left once its siblings have taken theirs, so the least this container needs is
    // what those siblings need -- and a text field's own minimum (Material's is 280dp) would
    // otherwise pin every container above it at a width no phone has, and the row beside it
    // would be shrunk to make room for a floor the field never insists on when it is given less.
    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measurables.along(horizontal, least = true) { it.minIntrinsicWidth(height) }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measurables.along(horizontal, least = false) { it.maxIntrinsicWidth(height) }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        measurables.along(!horizontal, least = true) { it.minIntrinsicHeight(width) }

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        measurables.along(!horizontal, least = false) { it.maxIntrinsicHeight(width) }

    private inline fun List<IntrinsicMeasurable>.along(
        sum: Boolean,
        least: Boolean,
        query: (IntrinsicMeasurable) -> Int,
    ): Int = if (sum) {
        sumOf { if (least && specOf(it).share > 0f) 0 else query(it) }
    } else {
        maxOfOrNull(query) ?: 0
    }
}

/**
 * CSS flex-shrink: take the deficit from each child in proportion to its [basis], never below its
 * [floor]; a child that hits its floor is pinned there and the others absorb what it could not.
 *
 * Returns sizes summing to at most [available] when the floors allow it, and to the floors' sum
 * when they do not -- the container overflows then, as a flex container does, rather than any
 * child disappearing.
 */
private fun shrink(basis: IntArray, floor: IntArray, available: Int): IntArray {
    val target = IntArray(basis.size) { max(basis[it], floor[it]) }
    val pinned = BooleanArray(basis.size)
    while (true) {
        val deficit = target.sum() - available
        if (deficit <= 0) break
        val open = target.indices.filter { !pinned[it] }
        if (open.isEmpty()) break
        val weight = open.sumOf { basis[it].toDouble() }
        var pinnedThisPass = false
        for (i in open) {
            // `deficit * basis[i]` in `Int` wraps for two long texts -- each a few thousand pixels
            // wide unwrapped -- and a wrapped cut pins nobody, so the loop ends with the deficit
            // unabsorbed and the rounding clean-up below drains one child to its floor.
            val cut = if (weight > 0.0) deficit.toDouble() * basis[i] / weight else deficit / open.size.toDouble()
            val next = (target[i] - cut).roundToInt()
            if (next <= floor[i]) {
                target[i] = floor[i]
                pinned[i] = true
                pinnedThisPass = true
            } else {
                target[i] = min(next, target[i])
            }
        }
        // A pass that pinned somebody left a deficit the others have not yet absorbed, so it goes
        // round again; a pass that pinned nobody has cut the proportional amount, and whatever is
        // left is rounding -- the same arithmetic would give the same answer.
        if (!pinnedThisPass) break
    }
    // Rounding can leave a pixel or two over. Take them from the largest child still above its
    // floor rather than let the container overflow by an amount nobody asked for.
    var over = target.sum() - available
    while (over > 0) {
        val i = target.indices.filter { target[it] > floor[it] }.maxByOrNull { target[it] } ?: break
        val give = min(over, target[i] - floor[i])
        target[i] -= give
        over -= give
    }
    return target
}
