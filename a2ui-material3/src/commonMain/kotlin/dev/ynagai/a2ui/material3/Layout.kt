package dev.ynagai.a2ui.material3

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
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
import dev.ynagai.a2ui.compose.AxisFit
import dev.ynagai.a2ui.compose.ComponentRegistry
import dev.ynagai.a2ui.compose.ComponentRenderer
import dev.ynagai.a2ui.compose.LayoutAxis
import dev.ynagai.a2ui.compose.LayoutTraits
import dev.ynagai.a2ui.compose.LocalA2uiRegistry
import dev.ynagai.a2ui.compose.RenderChild
import dev.ynagai.a2ui.compose.layoutTraitsOf
import dev.ynagai.a2ui.compose.rememberString
import dev.ynagai.a2ui.core.protocol.Component
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.ceil
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
 * ([AxisFit.Fill]) instead of letting it take the lot. Across the other axis -- a `Column` in a
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
 * word on how it fits the axis -- see [LayoutTraits] -- and [across] its word on the other one,
 * which is what `align: stretch` may do to it. [band] is the row or column that answers for it over
 * a range of widths -- see [QuerySession.band] -- or null when the child is asked at single widths.
 */
@Immutable
private data class LaidOutChild(
    val child: A2uiChild,
    val weight: Float,
    val traits: LayoutTraits,
    val across: AxisFit,
    val band: String?,
)

/**
 * Every child, paired with what [Flex] needs to know, recomposing the container only when that
 * changes.
 *
 * Reading `weight` means reading the surface's components, and a bare read would subscribe the
 * container to every write the surface takes -- including data model writes, which cannot change
 * a weight. Inside a `derivedStateOf` the recomputation still happens and the equal result is
 * discarded without invalidating anyone, which is the granularity the adapter layer buys and this
 * would otherwise spend. [Flex] recomposes on a components update as well, to forget refusals --
 * see [FlexMeasurePolicy.refused] -- and on a data model write still does not.
 */
@Composable
private fun A2uiComponentScope.rememberLaidOutChildren(axis: LayoutAxis): List<LaidOutChild> {
    val registry = LocalA2uiRegistry.current
    val value by remember(this, registry, axis) {
        derivedStateOf {
            allChildren().map { child ->
                val component = surface?.components?.get(child.componentId)
                LaidOutChild(
                    child = child,
                    weight = weightOf(component),
                    // What the child and everything it wraps say together: a card holding only an
                    // image that fills asks for a share, rather than for the width of its padding.
                    traits = layoutTraitsOf(child, registry, axis),
                    across = layoutTraitsOf(child, registry, axis.other()).fit,
                    band = flexAnswering(child.componentId, surface?.components, registry),
                )
            }
        }
    }
    return value
}

/**
 * The row or column that answers for [id] when it is asked its height over a range of widths --
 * see [QuerySession.band] -- or null when nothing can be trusted to carry the range there.
 *
 * This module's row and column answer for themselves. Its card holds its child inside padding,
 * which narrows every width by the same amount, so the range arrives shifted and whole. Anything
 * else -- a host's renderer, or one of these swapped for another -- may narrow a width by anything,
 * and is asked at single widths. Compared by identity, not by type name, because a host can
 * register a `Card` of its own. A card whose `child` is not a plain id -- which the catalog does not
 * allow -- is not followed, nor is one that leads back to a card already walked.
 */
private fun flexAnswering(id: String, components: Map<String, Component>?, registry: ComponentRegistry): String? {
    val walked = HashSet<String>()
    var current = id
    while (walked.add(current)) {
        val component = components?.get(current) ?: return null
        val renderer = registry[component.component]
        current = when {
            renderer === RowRenderer || renderer === ColumnRenderer -> return current
            renderer === CardRenderer -> component.enumProperty("child") ?: return null
            else -> return null
        }
    }
    return null
}

private fun LayoutAxis.other(): LayoutAxis =
    if (this == LayoutAxis.Horizontal) LayoutAxis.Vertical else LayoutAxis.Horizontal

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

/**
 * Where a child sits across the axis, as a fraction of the slack: `start` is 0, `end` is 1.
 * [Stretch] places at the start too -- what it changes is how the child is measured, and a child
 * it leaves alone sits where `start` would put it.
 */
private enum class CrossAlignment(val fraction: Float) { Start(0f), Center(0.5f), End(1f), Stretch(0f) }

/**
 * `align`, the cross axis, as CSS's `align-items`. `stretch` is the catalog's default, and a value
 * this side cannot read degrades to it, as every other enum in this module degrades to its
 * catalog default.
 *
 * Compose has no stretching `Alignment`: a child is stretched by being measured at least as large
 * as the line, which means knowing the line before measuring anyone -- see [Flex] for how that is
 * learned, and for the rows where it cannot be and `stretch` is drawn as `start`. The obvious
 * `fillMax*` is not it: a `fillMax*` taken inside a container resolves against the space the
 * *parent* offered rather than the container's line, and swelled a `Row` inside a `Column` to hide
 * everything below it.
 */
private fun crossAlignment(align: String?): CrossAlignment = when (align) {
    "start" -> CrossAlignment.Start
    "center" -> CrossAlignment.Center
    "end" -> CrossAlignment.End
    else -> CrossAlignment.Stretch
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
 * 3. Children that fill -- a renderer that says it [AxisFit.Fill]s, or one whose preferred
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
 * `stretch` measures every child at least as large across the axis as the line, the largest of
 * them, which has to be known before the first is measured. CSS sizes the two axes differently,
 * and so does this:
 *
 * - A column's width is the width it is offered, when that is bounded and whoever offered it meant
 *   it to be filled -- the root of a surface, a card there, a column that stretches and is a
 *   block itself, a dialog, a tab. That is CSS's block width, it holds whatever the column's own `align`, and it needs no
 *   question put to anyone. Inside a column that aligns its children `start`, `center` or `end`,
 *   and in a row's content-sized child, a column is as wide as its widest child, as CSS
 *   shrink-wraps a flex item -- a row has already given that child the width the plan chose, and
 *   a column that took whatever it was offered instead would take a cap the plan meant as a limit
 *   and leave a weighted sibling nothing. In a row's weighted child it fills the share.
 *   [LocalFillsOfferedWidth] carries which it is, per child. A row is never block-wide: at the root
 *   of a surface it is as wide as its children, as it was before `stretch`.
 * - A row's height is its tallest child, so the line is found by asking: the plan is run over the
 *   children's intrinsics once the ones that cannot be asked have been measured, and each child
 *   asked how tall it is at the width the plan gives it. Where the plan cannot foretell what the
 *   children will take -- two fillers, a filler or a shrunk row beside weighted children, a child
 *   that refuses a question -- the answer would be a guess, and a guess too tall leaves a gap
 *   under every child while one too short leaves them ragged. That row is drawn as `start`
 *   instead. So is a column shrink-wrapping its children under the same conditions, and a
 *   container whose children all fill across it, which leaves nobody to say how large the line
 *   is.
 *
 * A child that [AxisFit.Fill]s across the axis -- a vertical divider in a row -- is measured *up
 * to* the line and does not count towards it, so it spans the row rather than whatever the row was
 * offered. One that is [AxisFit.Fixed] counts towards the line and is left at its own size. A
 * child that cannot be asked is measured before the line is known and is not stretched unless the
 * line was known without asking; it still counts towards it.
 *
 * The layout answers its parent's questions from the same plan. Along the axis it is a sum --
 * preferred sizes for the maximum, minimums for the minimum, where a filling or weighted child
 * counts for nothing in the minimum because it is measured to what its siblings leave. Across the
 * axis it is the largest child *at the width the plan would give it*: a text that wraps in its
 * share is taller than the same text asked at the row's full width, and a column sizing its rows
 * from the latter would cut them short. Where the plan cannot foretell that width -- a filler that
 * may take less than its share, a shrunk text that may wrap narrower than its target -- a row's
 * height is the tallest each child is drawn at over every width it can be given; see
 * [FlexMeasurePolicy.largestCross].
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
    // The outermost container of a tree opens the session, and every one nested inside it shares
    // it; a second surface, on another window or another thread, has one of its own.
    val session = LocalQuerySession.current ?: remember { QuerySession() }
    val fillsWidth = axis == LayoutAxis.Vertical && LocalFillsOfferedWidth.current
    // What is drawn beneath this container, as far as the policy's refusals are concerned: the
    // surface's components and the renderers that draw them. A new policy forgets its refusals --
    // see [FlexMeasurePolicy.refused] -- and a new one is taken whenever either is replaced.
    val components by remember(scope) { derivedStateOf { ByIdentity(scope.surface?.components) } }
    val registry = LocalA2uiRegistry.current
    val id = scope.component.id
    val policy = remember(axis, arrangement, crossAlignment, children, session, fillsWidth, components, registry, id) {
        FlexMeasurePolicy(axis, arrangement, crossAlignment, children, session, fillsWidth, id)
    }
    Layout(
        content = {
            CompositionLocalProvider(LocalQuerySession provides session) {
                // The index is the id, and the policy reads it back: a renderer that dropped the
                // modifier it was handed is measured as a child that said nothing about itself.
                children.forEachIndexed { index, child ->
                    CompositionLocalProvider(LocalFillsOfferedWidth provides child.fillsOfferedWidth(axis, crossAlignment, fillsWidth)) {
                        scope.RenderChild(child.child, Modifier.layoutId(index))
                    }
                }
            }
        },
        modifier = modifier,
        measurePolicy = policy,
    )
}

/**
 * Whether a column inside this child is as wide as the width the child is offered -- see [Flex].
 *
 * A stretching column that is itself as wide as it is offered hands each child that width to fill.
 * One that shrink-wraps does not: what it was offered is a limit, not its width, and a column
 * inside that took it would take the cap its parent meant it to stay within, and drag the
 * shrink-wrapping column out to it -- a weighted sibling of the outer one left nothing, a centred
 * one pinned to the edge. Its children reach its width through the line instead. A row hands a content-sized child
 * the width the plan chose for it, which a column reaches by its own content -- and a column that
 * took the width instead would take a cap the plan meant as a limit, when the row could not ask
 * it. A weighted share, though, is a width the row means to be filled: a weighted card's column is
 * as wide as the card, not as its text. A filling child is not told to fill -- it fills *up to*
 * its share, and a column holding only a capped image is as wide as the image. A column aligning
 * its children anywhere else lets them be as wide as they are.
 */
private fun LaidOutChild.fillsOfferedWidth(axis: LayoutAxis, crossAlignment: CrossAlignment, fillsWidth: Boolean): Boolean =
    if (axis == LayoutAxis.Vertical) fillsWidth && crossAlignment == CrossAlignment.Stretch else weight > 0f

private class FlexMeasurePolicy(
    axis: LayoutAxis,
    private val arrangement: Any,
    private val crossAlignment: CrossAlignment,
    private val children: List<LaidOutChild>,
    private val session: QuerySession,
    /** A column that is as wide as it is offered -- see [Flex]. */
    private val fillsWidth: Boolean,
    /** This container's component, which a range of widths is addressed to -- see [QuerySession.band]. */
    private val id: String,
) : MeasurePolicy {
    private val horizontal = axis == LayoutAxis.Horizontal

    /**
     * The children that raised on an intrinsic query, by [keyOf], and are not asked again.
     *
     * A `SubcomposeLayout` anywhere beneath a child -- a `LazyColumn`, a `BoxWithConstraints`,
     * Coil's `SubcomposeAsyncImage`, whatever a host's renderer is built on -- raises
     * `IllegalStateException` from the query, and does so without touching any layout state: the
     * query is refused, not half-answered. So the container asks once, remembers the refusal, and
     * measures that child as one with nothing to say. The one exception per child is the whole
     * cost, and it is what makes a wrong guess about a renderer's layout a lost fair share rather
     * than a crashed surface.
     *
     * Remembered for as long as this policy lives, and [Flex] takes a new one when its children,
     * the surface's components or the registry are replaced. Not the children alone: a refusal
     * comes from anywhere beneath a child, and is passed up as the container's own, so a feed
     * replaced by a text inside a card or a nested row leaves this container's children as they
     * were while the refusal it holds is no longer true. An `updateComponents` therefore costs
     * every container on the surface a new policy, a measure pass, and one exception again for
     * each child that still refuses; a data model write costs none of that. What this does not
     * notice is a subtree that changes without either being replaced -- a host renderer choosing
     * a `LazyColumn` or a plain column from its data, a template whose items come and go, a
     * surface drawn inside one of this surface's components. Such a child is asked again at the
     * next components update, and until then keeps the share of one that cannot be asked.
     */
    private val refused = HashSet<Int>()

    /**
     * The child's own index, read back from its layout id, rather than its position among the
     * measurables. A child that has emitted nothing yet -- a component the surface does not hold,
     * drawn by the default placeholder as nothing -- is absent from that list, so the positions
     * of everything after it shift when it arrives, while the children list, and with it this
     * policy, stay the same. A refusal remembered by position would then mark the newcomer.
     */
    private fun IntrinsicMeasurable.keyOf(index: Int): Int =
        ((parentData as? LayoutIdParentData)?.layoutId as? Int) ?: (-1 - index)

    /** What the policy knows about one measurable: its child's declaration, or nothing. */
    private class Spec(val weight: Float, val along: AxisFit, val across: AxisFit, val band: String?) {
        val fill: Boolean get() = along == AxisFit.Fill
    }

    private fun specOf(measurable: IntrinsicMeasurable): Spec {
        val child = ((measurable.parentData as? LayoutIdParentData)?.layoutId as? Int)?.let(children::getOrNull)
            ?: return Spec(weight = 0f, along = AxisFit.Content, across = AxisFit.Content, band = null)
        return Spec(weight = child.weight, along = child.traits.fit, across = child.across, band = child.band)
    }

    // [band] rides along with this one question and no other: set for its duration, and put back
    // however it ends, a refusal included. Every other question clears it, so that a range meant
    // for one child never reaches a sibling or anything asked after it.
    private fun IntrinsicMeasurable.ask(index: Int, query: Query, arg: Int, band: Band? = null): Int? {
        val key = keyOf(index)
        if (key in refused) return null
        val answers = session.answers
        val cacheKey = if (answers == null) null else Asked(this, query, arg, band?.slack ?: 0)
        cacheKey?.let { answers!![it] }?.let { return it }
        val held = session.band
        session.band = band
        return try {
            query.of(this, arg).also { if (cacheKey != null) answers!![cacheKey] = it }
        } catch (refusal: IllegalStateException) {
            refused += key
            null
        } finally {
            session.band = held
        }
    }

    private fun IntrinsicMeasurable.minMain(index: Int, cross: Int): Int? =
        ask(index, if (horizontal) Query.MinWidth else Query.MinHeight, cross)

    private fun IntrinsicMeasurable.maxMain(index: Int, cross: Int): Int? =
        ask(index, if (horizontal) Query.MaxWidth else Query.MaxHeight, cross)

    /** The size along the axis a child takes whatever it is offered, or null if it has none. */
    private fun IntrinsicMeasurable.ownMain(index: Int, cross: Int): Int? {
        val most = maxMain(index, cross) ?: return null
        return if (minMain(index, cross) == most) most else null
    }

    // Asked at no more than a child can be measured at. A plan that shrinks a row to its children's
    // minimums hands a text the width of its longest word, which the agent chooses, and a text
    // asked its height at a width `Constraints` cannot hold raises -- not as a refusal, and not
    // caught. The measure pass clamps the same width the same way, so the answer is for the size
    // the child is drawn at.
    private fun IntrinsicMeasurable.minCross(index: Int, main: Int): Int? =
        ask(index, if (horizontal) Query.MinHeight else Query.MinWidth, drawable(main))

    private fun IntrinsicMeasurable.maxCross(index: Int, main: Int): Int? =
        ask(index, if (horizontal) Query.MaxHeight else Query.MaxWidth, drawable(main))

    private fun drawable(main: Int): Int = if (main == Constraints.Infinity) main else min(main, LARGEST_DIMENSION)

    /**
     * The plan: hands each child, in the order it must be measured, the least and the most it may
     * take along the axis, and learns from [take] what it took. See [Flex] for the four steps.
     *
     * [take] is the measure pass's `measure`, or, when a parent is asking this layout's intrinsic
     * size, an estimate from the child's own intrinsics -- the plan is the same either way, which
     * is the point. With [fillersTakeNothing] the plan counts each filler as taking none of its
     * share, the other extreme of what a filler may do; see [largestCross]. [measured] runs once
     * the children that cannot be asked are measured and before anyone else is.
     *
     * Returns whether the plan is *foreseeable*: whether what each child will take follows from
     * the questions it answered, so that running the plan over those answers gives the widths the
     * children will be drawn at. It does not when two fillers or a filler and a weighted child
     * share the room -- how much a filler takes cannot be asked, and it decides what the next one
     * gets -- nor when shrunk children sit beside a filler or weighted ones, since a text shrunk to
     * a width may wrap narrower than it and leave the difference to them.
     */
    private fun distribute(
        measurables: List<IntrinsicMeasurable>,
        mainMax: Int,
        crossMax: Int,
        fillersTakeNothing: Boolean = false,
        measured: () -> Unit = {},
        take: (index: Int, min: Int, max: Int) -> Int,
    ): Boolean {
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
        measured()

        // 2. The askable ones, at their preferred size or a fair shrink of it.
        var shrunk = false
        if (askable.isNotEmpty()) {
            val basis = IntArray(askable.size) { preferred.getValue(askable[it]) }
            val floor = IntArray(askable.size) { minimum.getValue(askable[it]) }
            shrunk = bounded && basis.sumOf { it.toLong() } > remaining
            val targets = if (shrunk) shrink(basis, floor, remaining) else basis
            askable.forEachIndexed { slot, index -> spend(take(index, 0, targets[slot])) }
        }

        // 3. The fillers, each up to an equal share of what is left -- counted against the
        //    weighted children's shares too, so that a filler that takes less leaves the rest to
        //    them rather than to nobody.
        val weighted = specs.indices.filter { specs[it].weight > 0f }
        val foreseeable = when {
            fillers.size > 1 -> false
            fillers.size == 1 -> weighted.isEmpty() && !shrunk
            else -> weighted.isEmpty() || !shrunk
        }
        if (fillers.isNotEmpty()) {
            val totalWeight = weighted.sumOf { specs[it].weight.toDouble() }
            fillers.forEachIndexed { slot, index ->
                val left = fillers.size - slot
                val cap = if (bounded) (remaining / (left + totalWeight)).roundToInt() else Constraints.Infinity
                val taken = take(index, 0, cap)
                if (!fillersTakeNothing) spend(taken)
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
        return foreseeable
    }

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val mainMax = if (horizontal) constraints.maxWidth else constraints.maxHeight
        val crossMin = if (horizontal) constraints.minHeight else constraints.minWidth
        val crossMax = if (horizontal) constraints.maxHeight else constraints.maxWidth
        val placeables = arrayOfNulls<Placeable>(measurables.size)

        // The line `stretch` measures children to, when it is known before anyone is measured --
        // see [Flex] -- or else learned once the children that cannot be asked are measured.
        // Null leaves every child to its own size, which is `start`.
        val stretch = crossAlignment == CrossAlignment.Stretch
        val bounded = crossMax != Constraints.Infinity
        // A column as wide as it is offered is that wide however it aligns its children: `center`
        // centres them across that width, as it does across a block in CSS.
        val offered = if (fillsWidth && bounded) crossMax else 0
        var line: Int? = if (stretch && bounded && (crossMin == crossMax || fillsWidth)) crossMax else null
        val learn = stretch && line == null
        // A row's children that fill its height, when nothing has said how tall the row is: the
        // height they are offered is the answer the row gave its parent, and that answer is the
        // tallest the row could be -- see [largestCross] -- not the height its content is drawn
        // at. A vertical divider stretched to it and left a gap under a pair of videos. So one
        // that says its width is its own -- fixed along the row, filling across it -- keeps that
        // width in the plan and is measured last, to the height the others drew. One whose width
        // is not -- a column spreading its children, a host's component filling both ways -- is
        // still measured in its turn, to the height offered: what it takes decides what the next
        // child gets, and the next child's height is what it would be measured to. Rows only: the
        // mirror, a horizontal divider in a column a row sized, is left as it was.
        val deferred = LinkedHashMap<Int, Int>()

        distribute(
            measurables,
            mainMax,
            crossMax,
            measured = { if (learn) line = measurables.line(mainMax, crossMax, placeables)?.coerceIn(crossMin, crossMax) },
        ) { index, min, max ->
            val drawnTo = line
            val spec = specOf(measurables[index])
            if (drawnTo == null && horizontal && spec.along == AxisFit.Fixed && spec.across == AxisFit.Fill) {
                val width = measurables[index].ownMain(index, crossMax)
                if (width != null && width in min..max) {
                    deferred[index] = width
                    return@distribute width
                }
            }
            var least = 0
            var most = crossMax
            if (drawnTo != null) {
                when (spec.across) {
                    AxisFit.Content -> least = drawnTo
                    AxisFit.Fill -> most = drawnTo
                    AxisFit.Fixed -> Unit
                }
            }
            // `fitPrioritizing*` rather than the constructor, which throws past 2^18 - 2 in a
            // dimension. A text's minimum intrinsic width is its longest word, and the agent
            // chooses the words; a floor that outgrows what `Constraints` can hold is clamped to
            // it -- the text is measured as wide as anything can be, and wraps from there.
            val c = if (horizontal) {
                Constraints.fitPrioritizingWidth(minWidth = min, maxWidth = max, minHeight = least, maxHeight = most)
            } else {
                Constraints.fitPrioritizingHeight(minWidth = least, maxWidth = most, minHeight = min, maxHeight = max)
            }
            measurables[index].measure(c).also { placeables[index] = it }.main()
        }
        if (deferred.isNotEmpty()) {
            // Nobody else drawn leaves them the height offered, as before: a row of dividers alone.
            val drawn = placeables.filterNotNull()
            val to = if (drawn.isEmpty()) crossMax else drawn.maxOf { it.cross() }.coerceIn(crossMin, crossMax)
            for ((index, width) in deferred) {
                val c = Constraints.fitPrioritizingWidth(minWidth = width, maxWidth = width, minHeight = 0, maxHeight = to)
                placeables[index] = measurables[index].measure(c)
            }
        }

        val sizes = IntArray(placeables.size) { placeables[it]!!.main() }
        val used = sizes.sumOf { it.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val mainSize = if (horizontal) constraints.constrainWidth(used) else constraints.constrainHeight(used)
        val crossUsed = maxOf(placeables.maxOfOrNull { it!!.cross() } ?: 0, line ?: 0, offered)
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

    /**
     * The line a stretching row or column measures its children to, learned by running the plan
     * over the children's answers: the largest of them across the axis, each asked at the size
     * along it that the plan will give it. The children that cannot be asked are already measured
     * in [placeables] and count as they were drawn. A child that fills across the axis is not
     * asked, having no size there of its own -- a video would answer with the height of the width
     * it was offered.
     *
     * Null when the answer would be a guess: the plan is not foreseeable, or a child refused a
     * question the measure pass had not put to it. Null too when every child fills across the
     * axis and nobody is left to say how large the line is -- a column holding only a banner
     * would otherwise draw it at no width at all.
     */
    private fun List<Measurable>.line(mainMax: Int, crossMax: Int, placeables: Array<Placeable?>): Int? {
        var answered = true
        var counted = false
        val largest = session.run {
            var largest = 0
            val foreseeable = distribute(this, mainMax, crossMax) { index, _, max ->
                val drawn = placeables[index]
                val across = specOf(this[index]).across
                if (drawn != null) {
                    if (across != AxisFit.Fill) {
                        largest = max(largest, drawn.cross())
                        counted = true
                    }
                    return@distribute drawn.main()
                }
                val given = if (max == Constraints.Infinity) this[index].maxMain(index, Constraints.Infinity) else max
                if (given == null) {
                    answered = false
                    return@distribute 0
                }
                if (across != AxisFit.Fill) {
                    val size = this[index].maxCross(index, given)
                    if (size == null) answered = false else largest = max(largest, size)
                    counted = true
                }
                given
            }
            if (!foreseeable) answered = false
            largest
        }
        return if (answered && counted) largest else null
    }

    private fun Placeable.main() = if (horizontal) width else height
    private fun Placeable.cross() = if (horizontal) height else width

    // This layout's own answers. Along the axis, sums; across it, the largest child at the size
    // the plan would give it. A child that cannot be asked leaves this layout without an answer
    // either: it is the child the plan measures as it comes, and there is no number to sum. So
    // the refusal is passed up -- a parent that shares from intrinsic sizes measures this layout
    // as it comes too, the way it would the child itself. Counting the child as nothing instead
    // was an answer that read as a preferred size, and a `Column` measuring a row to its answer
    // cut a three-line feed to the one line of the text beside it.
    private fun refuse(): Nothing = throw IllegalStateException(
        "This layout holds a child whose intrinsic size cannot be asked, so its own cannot be either.",
    )

    // A column asked over a range of widths -- [slack] below [cross] -- offers every child the
    // same range, and each child counts at the tallest it is drawn anywhere in it.
    private fun List<IntrinsicMeasurable>.sumMain(cross: Int, least: Boolean, slack: Int = 0): Int {
        val banded = slack > 0 && cross != Constraints.Infinity
        val preferredOf = { index: Int ->
            if (banded) this[index].tallestOver(index, cross - slack, cross) else this[index].maxMain(index, cross)
        }
        var sum = 0L
        // The weighted children are measured to their shares, so what they need together is the
        // size at which the share of the most demanding one reaches its preferred size -- the
        // largest preferred-size-per-unit-of-weight, times the weight there is -- and not the
        // sum of their preferred sizes, which hands a `weight: 1` child beside a `weight: 9` one
        // a tenth of what it asked for. Compose's own `Row` answers the same way.
        var perUnit = 0.0
        var totalWeight = 0.0
        for (index in indices) {
            val spec = specOf(this[index])
            if (spec.weight > 0f) {
                if (least) continue
                val preferred = preferredOf(index) ?: refuse()
                perUnit = max(perUnit, preferred / spec.weight.toDouble())
                totalWeight += spec.weight
                continue
            }
            if (least && spec.fill) continue
            sum += (if (least) this[index].minMain(index, cross) else preferredOf(index))?.toLong() ?: refuse()
        }
        // Rounded up: a size the most demanding child needs, one pixel short, is a line cut short.
        if (totalWeight > 0.0) sum += ceil(perUnit * totalWeight).coerceAtMost(Int.MAX_VALUE.toDouble()).toLong()
        return sum.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun List<IntrinsicMeasurable>.largestCross(main: Int, least: Boolean, slack: Int = 0): Int {
        var largest = 0
        val ask = { index: Int, _: Int, max: Int ->
            // The room the plan gives this child, and what the child would take of it: its
            // preferred size when that fits, the room when it does not, all of it for a filler.
            val given = if (max == Constraints.Infinity) this[index].maxMain(index, Constraints.Infinity) ?: refuse() else max
            val across = if (least) this[index].minCross(index, given) else this[index].maxCross(index, given)
            largest = max(largest, across ?: refuse())
            given
        }
        distribute(this, main, Constraints.Infinity, take = ask)
        // The plan counts a filler at its whole share, and drawn it may take less -- an empty row
        // takes nothing, the default image stops at 300dp -- which leaves the next filler, and
        // the weighted children, wider than the plan said. A video twice as wide is twice as
        // tall, and a column that sized a row by the plan drew its video over the text below.
        // How much a filler takes cannot be asked. Nor can what a shrunk text takes of its
        // target, which it may wrap narrower than. So a row's height is also the tallest each
        // child is drawn at over every width it can be given -- see [tallestOverBand] -- and it
        // is kept at least as tall as the plan's two extremes, every filler taking its share and
        // every filler taking none, whatever that finds: the widths the plan names are asked
        // as they are, and a child that is tallest at exactly one of them is still answered for.
        //
        // An upper bound as long as each leaf grows or shrinks steadily with its width, and a
        // loose one: a column over a range counts each child at its own tallest, a text at the
        // narrowest and a video at the widest, which no single width draws. Too tall costs a
        // column with room nothing -- it measures the row to the answer and spends what the row
        // draws -- and costs one short of room a share its siblings would have had. Inside the row
        // it costs a gap under anything that fills the height it is given: a child that does and
        // whose width is its own -- a vertical divider -- is measured last, to the height the
        // others drew; see [measure]. One whose width is not -- a column spreading its children
        // -- still takes the answer. The minimum stays with the plan.
        if (!least) {
            distribute(this, main, Constraints.Infinity, fillersTakeNothing = true, take = ask)
            if (horizontal && main != Constraints.Infinity) largest = max(largest, tallestOverBand(main, slack))
        }
        return largest
    }

    /**
     * The tallest [index] is drawn when offered any width from [from] to [to].
     *
     * A row or a column of this module answers for the whole range itself, handed it through
     * [QuerySession.band]; anything else is asked at both ends, which is the range for a leaf
     * whose height only grows or only shrinks with its width -- a text, a video, an image.
     */
    private fun IntrinsicMeasurable.tallestOver(index: Int, from: Int, to: Int): Int? {
        val widest = drawable(to)
        val narrowest = drawable(max(0, min(from, to)))
        if (narrowest >= widest) return ask(index, Query.MaxHeight, widest)
        specOf(this).band?.let { target ->
            return ask(index, Query.MaxHeight, widest, Band(widest - narrowest, target))
        }
        val atNarrowest = ask(index, Query.MaxHeight, narrowest) ?: return null
        val atWidest = ask(index, Query.MaxHeight, widest) ?: return null
        return max(atNarrowest, atWidest)
    }

    /**
     * The tallest this row is drawn when offered any width from `main - slack` to [main]: the
     * tallest child over every width the plan can give it there.
     *
     * The plan hands a child the most it may take, and a child is as tall as what it is handed,
     * not as what it takes of it -- a text handed 400 wraps at 400 however narrow its widest line
     * -- so the range to cover is of what each child is handed:
     *
     * - A content-sized child is handed its preferred width, or its share of a shrink, which
     *   depends on nothing but the room. Over a range of rooms it is found by running the shrink
     *   at each: the shrink's own rounding is not monotone -- a pixel more room can hand a child
     *   two pixels less -- and it is plain arithmetic, cheaper than the question it saves. Past
     *   [LARGEST_BAND] rooms it is bounded instead, by the floor and the preferred width either
     *   side of it.
     * - A filler is handed an equal share of what is left. Least when the content-sized children
     *   took all they were handed and the fillers before it all of theirs; most when a shrunk
     *   child took only its minimum -- a text wraps narrower than its target, and leaves the
     *   difference behind it -- and the fillers before it nothing.
     * - A weighted child is handed its share of what the fillers leave, by the same two extremes,
     *   and a pixel either side: the shares are rounded cumulatively, so that they sum to the
     *   room, and a share is within a pixel of its exact value but not monotone in the room.
     *
     * A content-sized child that was not shrunk is taken to take what it is handed: its preferred
     * width. One that takes less anyway leaves this short -- and the plan's own extremes, asked
     * beside it, are the floor under that.
     */
    private fun List<IntrinsicMeasurable>.tallestOverBand(main: Int, slack: Int): Int {
        val specs = map(::specOf)
        val from = max(0, main - slack)
        val askable = ArrayList<Int>()
        val fillers = ArrayList<Int>()
        val weighted = ArrayList<Int>()
        val basisOf = ArrayList<Int>()
        val floorOf = ArrayList<Int>()
        // Sorted as [distribute] sorts them, in the same order.
        for (index in indices) {
            val spec = specs[index]
            when {
                spec.weight > 0f -> weighted += index
                spec.fill -> fillers += index
                else -> {
                    val most = this[index].maxMain(index, Constraints.Infinity) ?: refuse()
                    val least = this[index].minMain(index, Constraints.Infinity) ?: refuse()
                    if (most == 0) {
                        fillers += index
                    } else {
                        askable += index
                        basisOf += most
                        floorOf += least
                    }
                }
            }
        }

        // What the content-sized children are handed over the range, and the least and the most
        // they leave behind them.
        val basis = basisOf.toIntArray()
        val floor = floorOf.toIntArray()
        val narrowest = IntArray(basis.size) { Int.MAX_VALUE }
        val widest = IntArray(basis.size)
        var leftLeast = Int.MAX_VALUE
        var leftMost = 0
        val visit = { room: Int, targets: IntArray, shrunk: Boolean ->
            var took = 0L
            var tookLeast = 0L
            for (slot in targets.indices) {
                narrowest[slot] = min(narrowest[slot], targets[slot])
                widest[slot] = max(widest[slot], targets[slot])
                took += targets[slot]
                tookLeast += if (shrunk) min(floor[slot], targets[slot]) else targets[slot]
            }
            leftLeast = min(leftLeast, (room - took).coerceAtLeast(0L).toInt())
            leftMost = max(leftMost, (room - tookLeast).coerceAtLeast(0L).toInt())
        }
        val preferred = basis.sumOf { it.toLong() }
        // Every room from the preferred sum up hands out the preferred widths, and leaves the
        // most at the widest room and the least at the narrowest of them.
        if (main >= preferred) {
            visit(max(from.toLong(), preferred).toInt(), basis, false)
            visit(main, basis, false)
        }
        val shrunkTo = min(main.toLong(), preferred - 1).toInt()
        if (from <= shrunkTo) {
            if (shrunkTo - from < LARGEST_BAND) {
                for (room in from..shrunkTo) visit(room, shrink(basis, floor, room), true)
            } else {
                // Too many rooms to run: whatever the shrink hands a child lies between its floor
                // and its preferred width -- it starts at the larger, only ever cuts, and never
                // below the floor.
                val low = IntArray(basis.size) { min(basis[it], floor[it]) }
                val high = IntArray(basis.size) { max(basis[it], floor[it]) }
                visit(from, high, true)
                visit(shrunkTo, low, true)
                for (slot in basis.indices) narrowest[slot] = min(narrowest[slot], low[slot])
            }
        }

        var tallest = 0
        askable.forEachIndexed { slot, index ->
            tallest = max(tallest, this[index].tallestOver(index, narrowest[slot], widest[slot]) ?: refuse())
        }
        val totalWeight = weighted.sumOf { specs[it].weight.toDouble() }
        var freeLeast = leftLeast
        fillers.forEachIndexed { slot, index ->
            val left = fillers.size - slot
            val least = (freeLeast / (left + totalWeight)).roundToInt()
            val most = (leftMost / (left + totalWeight)).roundToInt()
            freeLeast = max(0, freeLeast - least)
            tallest = max(tallest, this[index].tallestOver(index, least, most) ?: refuse())
        }
        // A room that cannot vary hands out exactly its shares, and a pixel either side of one
        // would ask a text a width it is never drawn at.
        val rounding = if (freeLeast == leftMost) 0 else 1
        var cumulative = 0.0
        for (index in weighted) {
            val before = cumulative
            cumulative += specs[index].weight
            val atLeast = share(freeLeast, before, cumulative, totalWeight)
            val atMost = share(leftMost, before, cumulative, totalWeight)
            val least = (min(atLeast, atMost) - rounding).coerceAtLeast(0)
            val most = (max(atLeast, atMost) + rounding).coerceAtMost(leftMost)
            tallest = max(tallest, this[index].tallestOver(index, least, most) ?: refuse())
        }
        return tallest
    }

    // Only a maximum height is ever asked over a range of widths. The other three questions clear
    // one that reached them, which no question this module puts does, and answer at a single width.
    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int = session.run {
        session.claim(null)
        if (horizontal) measurables.sumMain(height, least = true) else measurables.largestCross(height, least = true)
    }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int = session.run {
        session.claim(null)
        if (horizontal) measurables.sumMain(height, least = false) else measurables.largestCross(height, least = false)
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int = session.run {
        session.claim(null)
        if (horizontal) measurables.largestCross(width, least = true) else measurables.sumMain(width, least = true)
    }

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int = session.run {
        val slack = session.claim(id)
        if (horizontal) measurables.largestCross(width, least = false, slack) else measurables.sumMain(width, least = false, slack)
    }
}

/** The largest size `Constraints` holds in one dimension while the other is small: 2^18 - 2. */
private const val LARGEST_DIMENSION = (1 shl 18) - 2

/** The most rooms [FlexMeasurePolicy.tallestOverBand] runs the shrink at before it bounds it instead. */
private const val LARGEST_BAND = 4096

/**
 * The share of [free] a weighted child between [before] and [after] of [total] weight is handed:
 * cumulative rounding, as the plan's own step 4 does it.
 */
private fun share(free: Int, before: Double, after: Double, total: Double): Int {
    fun upTo(cumulative: Double) = (free.toDouble() * cumulative / total).roundToInt().coerceIn(0, free)
    return upTo(after) - upTo(before)
}

/** One of the four intrinsic questions, so that an answer can be remembered under it. */
private enum class Query {
    MinWidth, MaxWidth, MinHeight, MaxHeight;

    fun of(measurable: IntrinsicMeasurable, arg: Int): Int = when (this) {
        MinWidth -> measurable.minIntrinsicWidth(arg)
        MaxWidth -> measurable.maxIntrinsicWidth(arg)
        MinHeight -> measurable.minIntrinsicHeight(arg)
        MaxHeight -> measurable.maxIntrinsicHeight(arg)
    }
}

/**
 * Whether a column drawn here is as wide as the width it is offered, rather than as its widest
 * child -- CSS's block width against its shrink-to-fit width. True outside any [Flex], at the root
 * of a surface as for a block; each [Flex] sets it for what it holds, so it reaches a column
 * through a card, which has no width of its own to give. A component that does give its content
 * a width of its own -- a dialog, a tab, a list's items -- sets it again. See [Flex].
 */
internal val LocalFillsOfferedWidth = staticCompositionLocalOf { true }

/**
 * [value], equal to another only when it is the same instance. A surface's components are a map a
 * components update replaces -- unless it carries none -- and a data model write never does, so
 * identity is the question, and it is answered without walking the map as `equals` would, in
 * every container on every update.
 */
private class ByIdentity(val value: Any?) {
    override fun equals(other: Any?): Boolean = other is ByIdentity && other.value === value
    override fun hashCode(): Int = 0
}

/** The [QuerySession] of the outermost [Flex] above, or none at the top of a tree. */
private val LocalQuerySession = staticCompositionLocalOf<QuerySession?> { null }

/**
 * A question put to one child, as the key its answer is remembered under for the session: the
 * same height over a range of widths is another question than at the widest of them.
 */
private data class Asked(val measurable: IntrinsicMeasurable, val query: Query, val arg: Int, val slack: Int)

/**
 * A range of widths, handed to the one row or column [target] through the single-width
 * question a parent can put -- "how tall at the widest of them", with [slack] the widths
 * below it it may also be given.
 *
 * Relative on purpose: a card's padding narrows every width by the same amount, so the widest
 * arrives narrowed and the range beneath it whole. Addressed, so that a container the range was
 * not meant for answers at a single width, and cleared on the way in -- see [QuerySession.claim]
 * -- so that it reaches nothing below its target. Carried by [QuerySession.band], and declared
 * here rather than inside it: the Compose compiler publishes a nested class's stability into the
 * module's ABI, and a private top-level one's it does not.
 */
private class Band(val slack: Int, val target: String)

/**
 * The answers gathered while one intrinsic query works its way down through nested [Flex]es.
 *
 * A container asked its cross-axis size plans the axis first, which asks each child three
 * questions; a child that is itself a container does the same for its own, and so on down. Every
 * question repeats the ones the container above already asked, so the work grows as three to the
 * depth: a text under eighteen single-child rows and columns took forty seconds to answer, and a
 * surface may nest to twenty-four. Nothing changes while one question is being answered, so the
 * outermost container to be asked opens a session, every container beneath it reads and writes
 * the one map, and the session closes with the answer -- each child is asked each question once,
 * and the work is the size of the tree. Not kept across queries: the next one may come after the
 * children have changed. One session belongs to one tree of containers, handed down through
 * [LocalQuerySession], because layout runs on the thread that composed the tree: two surfaces laid
 * out on two threads at once -- two windows, two tests running side by side -- never share a map.
 */
private class QuerySession {
    var answers: HashMap<Asked, Int>? = null
        private set

    /** The [Band] handed with the question being asked, if it carries one. */
    var band: Band? = null

    /** Takes the range handed to [id], or none, and clears it either way. */
    fun claim(id: String?): Int {
        val handed = band
        band = null
        return if (handed != null && handed.target == id) handed.slack else 0
    }

    inline fun run(query: () -> Int): Int {
        val opened = answers == null
        if (opened) answers = HashMap()
        try {
            return query()
        } finally {
            if (opened) answers = null
        }
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
        val deficit = target.sumOf { it.toLong() } - available
        if (deficit <= 0L) break
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
    var over = target.sumOf { it.toLong() } - available
    while (over > 0L) {
        val i = target.indices.filter { target[it] > floor[it] }.maxByOrNull { target[it] } ?: break
        val give = min(over, (target[i] - floor[i]).toLong()).toInt()
        target[i] -= give
        over -= give
    }
    return target
}
