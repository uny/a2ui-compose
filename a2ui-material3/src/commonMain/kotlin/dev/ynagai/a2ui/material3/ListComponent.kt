package dev.ynagai.a2ui.material3

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 */
public val ListRenderer: ComponentRenderer = ComponentRenderer { scope, modifier ->
    val horizontal = scope.rememberString("direction") == "horizontal"
    val align = scope.rememberString("align")
    val children = scope.rememberAllChildren()
    val scroll = rememberScrollState()
    if (horizontal) {
        Row(
            modifier = modifier.horizontalScroll(scroll),
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
            modifier = modifier.verticalScroll(scroll),
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
 * The width a horizontal list's items are capped at.
 *
 * A number the guide leaves to the implementation ("a constrained max-width"). 280dp is a card's
 * width on a phone, which is what a horizontal list of cards is for.
 */
private val HORIZONTAL_ITEM_MAX = 280.dp
