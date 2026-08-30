package dev.ynagai.a2ui.material3

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.ynagai.a2ui.compose.ComponentRenderer
import dev.ynagai.a2ui.compose.RenderChild
import dev.ynagai.a2ui.compose.rememberAllChildren
import dev.ynagai.a2ui.compose.rememberString

/**
 * `List` -- a scrollable run of children, usually generated from a bound array.
 *
 * Every `List` in the specification's corpus binds a template rather than naming ids, and the
 * adapter layer has already expanded it by the time this runs: [rememberAllChildren] returns one
 * entry per item, each carrying the collection scope its relative paths resolve against. So this
 * renderer is a container and nothing more, and templating is not a thing it has to know about.
 *
 * **A scrolling `Column`/`Row`, not a `LazyColumn`/`LazyRow`, and the reason is measurement.** The
 * lazy lists are `SubcomposeLayout`s: they cannot answer intrinsic measurement queries, and they
 * fill the main axis they are given rather than wrapping their content. Either one breaks a list
 * nested in ordinary content -- a `LazyColumn` inside a `Column` claims the whole remaining height
 * and pushes its siblings off the surface, which is what the corpus's dashboards would do. A
 * scrolling `Column` wraps when its content fits and scrolls when it does not, which is the
 * behaviour the guide is describing, and it stays measurable by the `Row`/`Column` above it. See
 * the note on `verticalAlignment` in `Layout.kt`, which anticipated exactly this.
 *
 * What that trades away is virtualisation: every item composes, including the ones off screen. The
 * instance budget the adapter layer divides among children is what bounds that, rather than the
 * viewport -- an adversarial array cannot make this compose more instances than the surface was
 * allowed, it just composes the ones it was allowed all at once.
 *
 * **No margin and no padding**, which is §3's rule for the structural containers: `Row`, `Column`
 * and `List` contribute zero spacing so that nesting them does not multiply it. The items carry
 * their own.
 *
 * **`align` falls through to `start`, including the catalog's `stretch` default**, which is the
 * same divergence `Layout.kt` takes for `Row` and `Column` and is recorded here rather than left
 * to be rediscovered. Compose has no stretching `Alignment`: a cross axis is stretched by the
 * children filling it, and a `fillMax*` taken inside a scrolling container measures against what
 * the parent offered rather than against this list's own content. So a list's items keep their
 * natural width instead of squaring up to the widest -- visible as a ragged edge down a list of
 * `Card`s, and the one place this renderer knowingly does not draw what the catalog's default says.
 */
public val ListRenderer: ComponentRenderer = ComponentRenderer { scope, modifier ->
    val horizontal = scope.rememberString("direction") == "horizontal"
    val align = scope.rememberString("align")
    val children = scope.rememberAllChildren()
    // Keyed on the axis: one `ScrollState` shared across a `direction` flip carried the offset it
    // held on the old axis into the new one, so a list scrolled down and then re-sent as horizontal
    // opened already scrolled past its first items.
    val scroll = key(horizontal) { rememberScrollState() }
    if (horizontal) {
        Row(
            modifier = modifier.boundedScrollAxis(horizontal = true).horizontalScroll(scroll),
            verticalAlignment = when (align) {
                "center" -> Alignment.CenterVertically
                "end" -> Alignment.Bottom
                else -> Alignment.Top
            },
        ) {
            // "Children of a horizontal list should typically have a constrained max-width so they
            // do not stretch indefinitely." A horizontally scrolling row measures its children
            // with an unbounded width, so a child that fills the width it is offered -- a `Text`
            // long enough to want one, a `Card` around a column -- would otherwise take the whole
            // of it and leave the row one item wide.
            children.forEach { scope.RenderChild(it, Modifier.widthIn(max = HORIZONTAL_ITEM_MAX)) }
        }
    } else {
        Column(
            modifier = modifier.boundedScrollAxis(horizontal = false).verticalScroll(scroll),
            horizontalAlignment = when (align) {
                "center" -> Alignment.CenterHorizontally
                "end" -> Alignment.End
                else -> Alignment.Start
            },
        ) {
            children.forEach { scope.RenderChild(it) }
        }
    }
}

/**
 * Bounds the main axis before the scroll modifier measures against it.
 *
 * **A scroll container raises when it is measured with an unbounded main axis** -- Compose's own
 * `checkScrollableContainerConstraints`, which is an `IllegalStateException` out of the measure
 * pass and takes the whole surface down rather than degrading. Two ordinary things put this list
 * there, and neither is one it may refuse: a `List` inside another `List` (or inside a `Card` or
 * `Column` inside one), which the catalog permits and an agent can send; and a host that embeds
 * `A2uiSurface` in its own `Modifier.verticalScroll`, which is how a surface is usually placed in
 * a chat transcript. The renderer cannot see either from where it stands.
 *
 * So the constraint is bounded here instead. The inner list is then measured against a finite
 * height, wraps its content, never scrolls, and lets the outer scroller do the scrolling -- which
 * is the behaviour a nested list should have had anyway. What the number has to be is *finite*;
 * how large only decides when a pathological list starts scrolling inside its own box instead of
 * growing, and that degradation is bounded scrolling rather than a dead surface.
 */
private fun Modifier.boundedScrollAxis(horizontal: Boolean): Modifier = layout { measurable, constraints ->
    val bounded = when {
        horizontal && constraints.maxWidth == Constraints.Infinity ->
            constraints.copy(maxWidth = SCROLL_AXIS_MAX)
        !horizontal && constraints.maxHeight == Constraints.Infinity ->
            constraints.copy(maxHeight = SCROLL_AXIS_MAX)
        else -> constraints
    }
    val placeable = measurable.measure(bounded)
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/**
 * The finite main axis an otherwise-unbounded list is measured against, in pixels.
 *
 * Chosen for what `Constraints` can pack rather than for a layout reason: 32767 fits the 15-bit
 * bucket, which leaves the cross axis its own 16 bits, so bounding one axis here cannot make the
 * other unrepresentable. It is some thirteen screens of content, and a list longer than that
 * scrolls within it.
 */
private const val SCROLL_AXIS_MAX = 32767

/**
 * The width a horizontal list's items are capped at.
 *
 * A number the guide leaves to the implementation ("a constrained max-width"). 280dp is a card's
 * width on a phone, which is what a horizontal list of cards is for.
 */
private val HORIZONTAL_ITEM_MAX = 280.dp
