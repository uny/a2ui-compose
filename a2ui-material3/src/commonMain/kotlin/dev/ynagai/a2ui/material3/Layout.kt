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
 * word on how it fits the axis -- see [LayoutTraits].
 */
@Immutable
private data class LaidOutChild(
    val child: A2uiChild,
    val weight: Float,
    val traits: LayoutTraits,
)

/**
 * Every child, paired with what [Flex] needs to know, recomposing the container only when that
 * changes.
 *
 * Reading `weight` means reading the surface's components, and a bare read would subscribe the
 * container to every write the surface takes -- including data model writes, which cannot change
 * a weight. Inside a `derivedStateOf` the recomputation still happens and the equal result is
 * discarded without invalidating anyone, which is the granularity the adapter layer buys and this
 * would otherwise spend.
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
 * [Flex] now learns which children can be asked and remembers the ones that cannot, so a stretch
 * that asks only where asking is safe, and degrades to `start` elsewhere, is the next step and
 * not this one. Until then the children keep their natural cross-axis size
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
 * That is what intrinsic measurement is for, and [FlexMeasurePolicy.distribute] is the plan it
 * yields, shared by the measure pass and by this layout's own answers to its parent:
 *
 * 1. Children that cannot be asked -- a `SubcomposeLayout` somewhere beneath raised on the
 *    question, and the container remembers -- are measured first, in order, sharing what is left
 *    once the askable children's minimums are set aside, so that no two of them can starve each
 *    other either. They take what they take.
 * 2. Children that can be asked report their preferred and minimum sizes along the axis. If the
 *    preferred sizes fit in what is left, each gets its preferred size; if not, the deficit is
 *    taken from each in proportion to its preferred size, no child going below its minimum, the
 *    ones pinned at their minimum dropping out and the rest absorbing their share -- the
 *    flex-shrink algorithm. Order plays no part.
 * 3. Children that fill -- a renderer that says it [MainAxisFit.Fill]s, or one whose preferred
 *    size is nothing at all, like a `Card` around a banner image -- are measured *up to* a share of
 *    what remains: a filler takes the room when the row has it and its share when it does not,
 *    which is the web's `flex: 0 1 auto` for something whose preferred size is the room, and
 *    leaves `justify` something to arrange when the row is wide.
 * 4. Children with an explicit `weight` divide what is left after that by weight, each measured
 *    to exactly its share, as Compose's own `weight` does. The web would also hold such a child
 *    at its min-content; this layout does not, on purpose -- see the note in the code.
 *
 * Along an unbounded axis -- a horizontally scrolling `List` -- nothing is short of room, so every
 * child gets its preferred size and a filling or weighted one is measured loosely. `justify` is
 * applied through Compose's own `Arrangement`s, so the spacing semantics are the ones `Row` has;
 * `align` is [crossAlignment].
 *
 * The layout answers its parent's questions from the same plan. Along the axis it is a sum --
 * preferred sizes for the maximum, minimums for the minimum, where a filling or weighted child
 * counts for nothing in the minimum because it is measured to what its siblings leave. Across the
 * axis it is the largest child *at the width the plan would give it*: a text that wraps in its
 * share is taller than the same text asked at the row's full width, and a column sizing its rows
 * from the latter would cut them short.
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

    /**
     * The children that raised on an intrinsic query, by index, and are not asked again.
     *
     * A `SubcomposeLayout` anywhere beneath a child -- a `LazyColumn`, a `BoxWithConstraints`,
     * Coil's `SubcomposeAsyncImage`, whatever a host's renderer is built on -- raises
     * `IllegalStateException` from the query, and does so without touching any layout state: the
     * query is refused, not half-answered. So the container asks once, remembers the refusal for
     * as long as it has these children (a new list is a new policy), and measures that child as
     * one with nothing to say. The one exception per child is the whole cost, and it is what
     * makes a wrong guess about a renderer's layout a lost fair share rather than a crashed
     * surface.
     */
    private val refused = HashSet<Int>()

    /** What the policy knows about one measurable: its child's declaration, or nothing. */
    private class Spec(val weight: Float, val fill: Boolean)

    private fun specOf(measurable: IntrinsicMeasurable): Spec {
        val child = ((measurable.parentData as? LayoutIdParentData)?.layoutId as? Int)?.let(children::getOrNull)
            ?: return Spec(weight = 0f, fill = false)
        return Spec(weight = child.weight, fill = child.traits.fit == MainAxisFit.Fill)
    }

    private fun IntrinsicMeasurable.ask(index: Int, query: IntrinsicMeasurable.() -> Int): Int? {
        if (index in refused) return null
        return try {
            query()
        } catch (refusal: IllegalStateException) {
            refused += index
            null
        }
    }

    private fun IntrinsicMeasurable.minMain(index: Int, cross: Int): Int? =
        ask(index) { if (horizontal) minIntrinsicWidth(cross) else minIntrinsicHeight(cross) }

    private fun IntrinsicMeasurable.maxMain(index: Int, cross: Int): Int? =
        ask(index) { if (horizontal) maxIntrinsicWidth(cross) else maxIntrinsicHeight(cross) }

    private fun IntrinsicMeasurable.minCross(index: Int, main: Int): Int? =
        ask(index) { if (horizontal) minIntrinsicHeight(main) else minIntrinsicWidth(main) }

    private fun IntrinsicMeasurable.maxCross(index: Int, main: Int): Int? =
        ask(index) { if (horizontal) maxIntrinsicHeight(main) else maxIntrinsicWidth(main) }

    /**
     * The plan: hands each child, in the order it must be measured, the least and the most it may
     * take along the axis, and learns from [take] what it took. See [Flex] for the four steps.
     *
     * [take] is the measure pass's `measure`, or, when a parent is asking this layout's intrinsic
     * size, an estimate from the child's own intrinsics -- the plan is the same either way, which
     * is the point.
     */
    private fun distribute(
        measurables: List<IntrinsicMeasurable>,
        mainMax: Int,
        crossMax: Int,
        take: (index: Int, min: Int, max: Int) -> Int,
    ) {
        val specs = measurables.map(::specOf)
        val bounded = mainMax != Constraints.Infinity
        var remaining = mainMax
        val spend = { taken: Int -> if (bounded) remaining = max(0, remaining - taken) }

        // Ask everyone who is content-sized, once. A child whose preferred size is nothing joins
        // the fillers: a wrapper around something that fills whatever it is given has no size to
        // be fair about, and a basis of zero would have measured it to nothing.
        val preferred = HashMap<Int, Int>()
        val minimum = HashMap<Int, Int>()
        val unasked = ArrayList<Int>()
        val fillers = ArrayList<Int>()
        val askable = ArrayList<Int>()
        for (index in specs.indices) {
            val spec = specs[index]
            if (spec.weight > 0f) continue
            if (spec.fill) {
                fillers += index
                continue
            }
            val most = measurables[index].maxMain(index, crossMax)
            val least = if (most == null) null else measurables[index].minMain(index, crossMax)
            when {
                most == null || least == null -> unasked += index
                most == 0 -> fillers += index
                else -> {
                    askable += index
                    preferred[index] = most
                    minimum[index] = least
                }
            }
        }

        // 1. The children that cannot be asked, sharing what the askable ones' minimums leave.
        val reserve = askable.sumOf { minimum.getValue(it).toLong() }
        unasked.forEachIndexed { slot, index ->
            val left = unasked.size - slot
            val cap = if (bounded) ((remaining - reserve).coerceAtLeast(0L) / left).toInt() else Constraints.Infinity
            spend(take(index, 0, cap))
        }

        // 2. The askable ones, at their preferred size or a fair shrink of it.
        if (askable.isNotEmpty()) {
            val basis = IntArray(askable.size) { preferred.getValue(askable[it]) }
            val floor = IntArray(askable.size) { minimum.getValue(askable[it]) }
            val targets = if (bounded && basis.sumOf { it.toLong() } > remaining) shrink(basis, floor, remaining) else basis
            askable.forEachIndexed { slot, index -> spend(take(index, 0, targets[slot])) }
        }

        // 3. The fillers, each up to an equal share of what is left -- counted against the
        //    weighted children's shares too, so that a filler that takes less leaves the rest to
        //    them rather than to nobody.
        val weighted = specs.indices.filter { specs[it].weight > 0f }
        if (fillers.isNotEmpty()) {
            val totalWeight = weighted.sumOf { specs[it].weight.toDouble() }
            fillers.forEachIndexed { slot, index ->
                val left = fillers.size - slot
                val cap = if (bounded) (remaining / (left + totalWeight)).roundToInt() else Constraints.Infinity
                spend(take(index, 0, cap))
            }
        }

        // 4. The weighted ones, to exactly their share of what is left. Cumulative rounding, so the
        //    shares sum to what there is: rounding each on its own hands out a pixel per child too
        //    many, and the last child pays for all of them.
        if (weighted.isNotEmpty()) {
            if (bounded) {
                val free = remaining
                val total = weighted.sumOf { specs[it].weight.toDouble() }
                var cumulative = 0.0
                var handed = 0
                for (index in weighted) {
                    cumulative += specs[index].weight
                    // In doubles: a weight is any finite `Float`, and `free` times a large one
                    // overflows a `Float` to infinity, which rounds to `Int.MAX_VALUE`.
                    val upTo = (free.toDouble() * cumulative / total).roundToInt().coerceIn(0, free)
                    val share = upTo - handed
                    handed = upTo
                    // Exactly the share, even below the child's own minimum. The web would hold a
                    // `flex: N` child at its min-content (`min-width: auto`) and let the row
                    // overflow; here the agent asked for proportions -- the specification's
                    // `33_financial-data-grid` is four weighted columns -- and a grid whose widest
                    // figure pushes the row off a phone is worse than a cell that wraps its figure.
                    // A deliberate departure, and the one place this layout is not flexbox.
                    take(index, share, share)
                }
            } else {
                for (index in weighted) take(index, 0, Constraints.Infinity)
            }
        }
    }

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val mainMax = if (horizontal) constraints.maxWidth else constraints.maxHeight
        val crossMax = if (horizontal) constraints.maxHeight else constraints.maxWidth
        val placeables = arrayOfNulls<Placeable>(measurables.size)

        distribute(measurables, mainMax, crossMax) { index, min, max ->
            // `fitPrioritizing*` rather than the constructor, which throws past 2^18 - 2 in a
            // dimension. A text's minimum intrinsic width is its longest word, and the agent
            // chooses the words; a floor that outgrows what `Constraints` can hold is clamped to
            // it -- the text is measured as wide as anything can be, and wraps from there.
            val c = if (horizontal) {
                Constraints.fitPrioritizingWidth(minWidth = min, maxWidth = max, minHeight = 0, maxHeight = crossMax)
            } else {
                Constraints.fitPrioritizingHeight(minWidth = 0, maxWidth = crossMax, minHeight = min, maxHeight = max)
            }
            measurables[index].measure(c).also { placeables[index] = it }.main()
        }

        val sizes = IntArray(placeables.size) { placeables[it]!!.main() }
        val used = sizes.sumOf { it.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val mainSize = if (horizontal) constraints.constrainWidth(used) else constraints.constrainHeight(used)
        val crossUsed = placeables.maxOfOrNull { it!!.cross() } ?: 0
        val crossSize = if (horizontal) constraints.constrainHeight(crossUsed) else constraints.constrainWidth(crossUsed)
        val positions = IntArray(sizes.size)
        // Dispatched on the axis, not on the arrangement's type: `Center` and the three `space*`
        // arrangements are both `Horizontal` and `Vertical`, and the horizontal overload mirrors
        // under RTL -- a column sent through it read bottom to top.
        if (horizontal) {
            with(arrangement as Arrangement.Horizontal) { arrange(mainSize, sizes, layoutDirection, positions) }
        } else {
            with(arrangement as Arrangement.Vertical) { arrange(mainSize, sizes, positions) }
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

    // This layout's own answers. Along the axis, sums; across it, the largest child at the size
    // the plan would give it. A child that cannot be asked counts for nothing -- it is the child
    // the plan measures as it comes, and there is no number to sum.
    private fun List<IntrinsicMeasurable>.sumMain(cross: Int, least: Boolean): Int = indices.sumOf { index ->
        val spec = specOf(this[index])
        val counts = !least || (spec.weight == 0f && !spec.fill)
        if (!counts) 0L else (if (least) this[index].minMain(index, cross) else this[index].maxMain(index, cross))?.toLong() ?: 0L
    }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun List<IntrinsicMeasurable>.largestCross(main: Int, least: Boolean): Int {
        var largest = 0
        distribute(this, main, Constraints.Infinity) { index, _, max ->
            // The room the plan gives this child, and what the child would take of it: its
            // preferred size when that fits, the room when it does not, all of it for a filler.
            val given = if (max == Constraints.Infinity) this[index].maxMain(index, Constraints.Infinity) ?: 0 else max
            val across = if (least) this[index].minCross(index, given) else this[index].maxCross(index, given)
            largest = max(largest, across ?: 0)
            given
        }
        return largest
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        if (horizontal) measurables.sumMain(height, least = true) else measurables.largestCross(height, least = true)

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        if (horizontal) measurables.sumMain(height, least = false) else measurables.largestCross(height, least = false)

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        if (horizontal) measurables.largestCross(width, least = true) else measurables.sumMain(width, least = true)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        if (horizontal) measurables.largestCross(width, least = false) else measurables.sumMain(width, least = false)
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
