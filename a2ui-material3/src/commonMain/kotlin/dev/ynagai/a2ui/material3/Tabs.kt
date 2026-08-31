package dev.ynagai.a2ui.material3

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ynagai.a2ui.compose.A2uiComponentScope
import dev.ynagai.a2ui.compose.ComponentRenderer
import dev.ynagai.a2ui.compose.RenderChild
import dev.ynagai.a2ui.compose.rememberChildren
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * `Tabs` -- one child at a time, chosen by a strip of headers.
 *
 * **The selected index lives here and nowhere else.** The guide says so in as many words --
 * "maintain a local `selectedIndex` state (defaulting to 0)" -- and the catalog gives a `Tabs` no
 * property to bind it to, so this is one of the two components in the catalog whose state is the
 * renderer's rather than the data model's ([ModalRenderer] is the other). Which tab is open
 * therefore does not survive the component being replaced, and the agent cannot read it: a payload
 * that needs the selection to reach the agent has to build the strip out of `Button`s instead.
 *
 * **Only the selected child is drawn**, which is what the guide asks for and is also what keeps a
 * ten-tab surface from composing ten subtrees to show one. The hidden children are not composed at
 * all, so their state does not survive a tab switch either -- text typed into a field on one tab
 * and returned to is gone. That is the guide's behaviour rather than an oversight; a renderer
 * keeping every tab composed would be the trade in the other direction.
 *
 * **The guide and the catalog disagree about the shape, and the catalog wins.** The guide
 * describes "a horizontal row of interactive tab headers for the `titles`" with the `child`
 * corresponding by index, as if a `Tabs` carried two parallel arrays; `catalog.json` has a single
 * `tabs` array of `{title, child}` objects, which is what every example in the corpus sends and
 * what the child resolver already walks. Pairing by index inside one object cannot go out of step
 * the way two arrays of different lengths can, so nothing here has to decide what a title with no
 * child means.
 *
 * `weight` is the only other property, and a row or a column reads it off this component rather
 * than this renderer reading it -- see `Layout.kt`.
 */
public val TabsRenderer: ComponentRenderer = ComponentRenderer { scope, modifier ->
    val tabs = scope.rememberTabs()
    // **A re-sent `Tabs` starts again at the first tab.** The scope is remembered on the
    // `Component` itself (`A2uiSurface.kt`), so an `updateComponents` touching this component at
    // all -- a reworded title is enough -- builds a new scope, and this cell with it. A user
    // sitting on the third tab is returned to the first. Carrying the selection across that would
    // mean keying on the component's `id` instead of on the scope, which is a decision about how
    // much of a re-sent component is the same component, and is not this line's to make.
    var selected by remember(scope) { mutableIntStateOf(0) }
    // The clamp cannot fire while `tabs` and `selected` are keyed on the same `Component` -- they
    // move together. It is here so that changing either key cannot silently index past the end.
    val index = selected.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
    if (tabs.isEmpty()) return@ComponentRenderer
    Column(modifier) {
        // Scrollable rather than the fixed `PrimaryTabRow`, which divides the width evenly among
        // its tabs: the number of tabs is the agent's, and four or five titles of ordinary length
        // are already narrow enough to be clipped mid-word on a phone. Scrolling degrades to the
        // same thing when the titles do fit -- the strip simply does not move.
        PrimaryScrollableTabRow(selectedTabIndex = index, edgePadding = 0.dp) {
            tabs.forEachIndexed { at, tab ->
                Tab(
                    selected = at == index,
                    onClick = { selected = at },
                    text = {
                        Text(
                            text = tab.title,
                            // Bold *and* the indicator the tab row draws under it. The guide
                            // offers either; both together is what keeps the active tab legible
                            // for a reader who cannot separate the indicator's colour from the
                            // strip's.
                            fontWeight = if (at == index) FontWeight.Bold else null,
                            style = MaterialTheme.typography.titleSmall,
                        )
                    },
                )
            }
        }
        // The selected tab's child, by the exact property the child resolver named it under. A
        // `Tabs` carries one `Child` per element of `tabs`, so this is a list of at most one --
        // iterated rather than indexed for the reason `Button` iterates its own: an entry standing
        // in for a child the instance budget did not reach is drawn like any other.
        scope.rememberChildren(childProperty(index)).forEach { scope.RenderChild(it) }
    }
}

/**
 * Where the child of tab [index] sits inside the component, as the child resolver names it.
 *
 * The resolver walks the catalog's schema alongside the value and reports each reference by its
 * path within the component -- `trigger` for a `Modal`, `children` for a `Row`, and this for a tab.
 * Building the string here rather than filtering every child by position is what makes the pairing
 * exact: a tab whose `child` is missing contributes no reference at all, so the nth entry of the
 * expansion is not necessarily the nth tab's.
 */
private fun childProperty(index: Int): String = "tabs/$index/child"

/** One tab's header. The child is fetched by index when the tab is the selected one. */
@Immutable
private data class TabHeader(val title: String)

/**
 * The `tabs` array as this renderer reads its headers.
 *
 * Read off the raw property and walked by hand, the way `ChoicePicker` reads `options`: the
 * catalog types only the `title` inside each element as a `DynamicString`, so there is one part to
 * resolve and the rest is structure. A tab whose title will not resolve is kept with an empty
 * one -- dropping it would shift every tab after it away from the `child` the resolver paired it
 * with, which is the one error here that draws the wrong content rather than none.
 *
 * **Bounded, because nothing upstream bounds it.** The instance budget divides among a component's
 * *children*, and `tabs` is a property; the headers are all composed at once even though only one
 * child is. See [MAX_TABS].
 */
private fun A2uiComponentScope.tabHeaders(): List<TabHeader> =
    (property("tabs") as? JsonArray).orEmpty().take(MAX_TABS).map { entry ->
        val title = (entry as? JsonObject)?.get("title")?.let { dynamicString(it) }
        TabHeader(title.orEmpty())
    }

/** The headers, recomposing the strip only when a title changes. */
@Composable
private fun A2uiComponentScope.rememberTabs(): List<TabHeader> {
    val tabs by remember(this) { derivedStateOf { tabHeaders() } }
    return tabs
}

/**
 * The most tabs a strip will draw -- see [A2uiComponentScope.tabHeaders].
 *
 * Far beyond anything a person tabs through, and that is the point: the bound is not a design
 * opinion about how many tabs are reasonable, it is the ceiling that stops one `updateComponents`
 * message from composing an unbounded number of headers in a non-lazy strip. Silent, for the same
 * reason `ChoicePicker`'s is -- the placeholder machinery expands children, and this is a property.
 *
 * The children past it are unreachable rather than dropped: their tabs cannot be selected, so they
 * are never composed. They do still take a share of the subtree's instance budget, because the
 * budget is divided over every reference a component carries and not over the ones that draw.
 */
private const val MAX_TABS = 100
