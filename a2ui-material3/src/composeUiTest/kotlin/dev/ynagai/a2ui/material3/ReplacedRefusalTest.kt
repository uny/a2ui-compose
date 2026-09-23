package dev.ynagai.a2ui.material3

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import dev.ynagai.a2ui.compose.A2uiRenderer
import dev.ynagai.a2ui.compose.A2uiSurface
import dev.ynagai.a2ui.compose.BasicCatalog
import dev.ynagai.a2ui.compose.ComponentRegistry
import dev.ynagai.a2ui.compose.ComponentRenderer
import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.AgentToRendererMessage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A container forgets that a child could not be asked its size once what is drawn there changes
 * (#95).
 *
 * A child with a `SubcomposeLayout` beneath it refuses intrinsic queries, and a row or a column
 * remembers the refusal rather than paying the exception on every pass -- and passes it up, so the
 * container above remembers the one below. Each test here replaces what refused, under the same
 * id, weight and traits, and draws the same final tree fresh beside it: the two must match.
 */
@OptIn(ExperimentalTestApi::class)
class ReplacedRefusalTest {

    @Test
    fun a_text_that_replaced_a_feed_is_asked() = runComposeUiTest {
        // Held to the refusal, the text is measured as one with nothing to say and takes what
        // its sibling's minimum leaves, and the long text beside it is squeezed to a word or two.
        assertDrawnAsIfFresh(
            before = """[
                {"id":"root","component":"Row","children":["a","b"]},
                {"id":"a","component":"Feed","text":"$LONG_A"},
                {"id":"b","component":"Text","text":"$LONG_B"}
            ]""",
            update = """[{"id":"a","component":"Text","text":"$LONG_A"}]""",
            texts = listOf(LONG_A, LONG_B),
        )
    }

    @Test
    fun a_row_holding_a_replaced_feed_is_asked() = runComposeUiTest {
        // The inner row refused because its feed did, and the outer row remembers the inner one.
        // The outer row's children are what they were, so forgetting with them is not enough.
        assertDrawnAsIfFresh(
            before = """[
                {"id":"root","component":"Row","children":["inner","b"]},
                {"id":"inner","component":"Row","children":["a"]},
                {"id":"a","component":"Feed","text":"$LONG_A"},
                {"id":"b","component":"Text","text":"$LONG_B"}
            ]""",
            update = """[{"id":"a","component":"Text","text":"$LONG_A"}]""",
            texts = listOf(LONG_A, LONG_B),
        )
    }

    @Test
    fun a_card_holding_a_replaced_feed_is_asked() = runComposeUiTest {
        assertDrawnAsIfFresh(
            before = """[
                {"id":"root","component":"Row","children":["card","b"]},
                {"id":"card","component":"Card","child":"a"},
                {"id":"a","component":"Feed","text":"$LONG_A"},
                {"id":"b","component":"Text","text":"$LONG_B"}
            ]""",
            update = """[{"id":"a","component":"Text","text":"$LONG_A"}]""",
            texts = listOf(LONG_A, LONG_B),
        )
    }

    @Test
    fun a_replacement_that_keeps_its_containers_size_is_still_asked() = runComposeUiTest {
        // The refusing tile and the plain one are the same size, so the inner row is too, and
        // nothing about the swap makes the outer row measure again on its own account. Held to
        // the refusal of the row inside it, the outer row measures that row before it knows its
        // line and leaves it unstretched, so the word beside the tile keeps its own height rather
        // than reaching the long text's.
        assertDrawnAsIfFresh(
            before = """[
                {"id":"root","component":"Row","children":["inner","b"]},
                {"id":"inner","component":"Row","children":["a","w"]},
                {"id":"a","component":"RefusingTile"},
                {"id":"w","component":"Text","text":"word"},
                {"id":"b","component":"Text","text":"$LONG_A"}
            ]""",
            update = """[{"id":"a","component":"Tile"}]""",
            texts = listOf("word", LONG_A),
        )
    }

    @Test
    fun a_feed_whose_renderer_is_replaced_by_one_that_can_be_asked_is_asked() = runComposeUiTest {
        // No component changes; the host hands over a registry that draws `Feed` without a lazy
        // list. The rows' children compare equal across the two registries.
        val answering = registry.with(mapOf("Feed" to ComponentRenderer { scope, m -> Text(scope.string("text").orEmpty(), m) }))
        assertDrawnAsIfFresh(
            before = """[
                {"id":"root","component":"Row","children":["a","b"]},
                {"id":"a","component":"Feed","text":"$LONG_A"},
                {"id":"b","component":"Text","text":"$LONG_B"}
            ]""",
            update = null,
            texts = listOf(LONG_A, LONG_B),
            after = answering,
        )
    }

    @Test
    fun a_feed_that_replaced_a_text_is_measured_as_one_that_cannot_be_asked() = runComposeUiTest {
        // The other way round: the refusal is found and remembered afresh, rather than the
        // text's answers standing in for the feed.
        assertDrawnAsIfFresh(
            before = """[
                {"id":"root","component":"Row","children":["a","b"]},
                {"id":"a","component":"Text","text":"$LONG_A"},
                {"id":"b","component":"Text","text":"$LONG_B"}
            ]""",
            update = """[{"id":"a","component":"Feed","text":"$LONG_A"}]""",
            texts = listOf(LONG_A, LONG_B),
        )
    }

    @Test
    fun a_refusal_is_not_asked_again_until_the_components_change() = runComposeUiTest {
        // Forgetting costs an exception per refusing child, so it happens on a components update
        // and not on every pass: a narrower host and a data model write re-measure the row without
        // asking the refusing child again.
        var asked = 0
        val counting = registry.with(mapOf("Refusing" to ComponentRenderer { _, m -> Layout(m, measurePolicy = Refusing { asked++ }) }))
        val renderer = rendererFor(
            """[
                {"id":"root","component":"Row","children":["a","b"]},
                {"id":"a","component":"Refusing"},
                {"id":"b","component":"Text","text":"$LONG_A"}
            ]""",
        )
        var width by mutableStateOf(WIDTH)
        setContent {
            Box(Modifier.size(width, HEIGHT)) {
                MaterialTheme { A2uiSurface(renderer, SURFACE, counting) }
            }
        }
        waitForIdle()
        val first = asked
        assertTrue(first > 0, "the refusing child is asked at all")

        width = WIDTH - 40.dp
        waitForIdle()
        renderer.apply(message("""{"version":"v1.0","updateDataModel":{"surfaceId":"$SURFACE","value":{"n":1}}}"""))
        waitForIdle()
        assertEquals(first, asked, "neither a narrower host nor a data model write asks again")

        renderer.apply(message("""{"version":"v1.0","updateComponents":{"surfaceId":"$SURFACE","components":[{"id":"b","component":"Text","text":"$LONG_B"}]}}"""))
        waitForIdle()
        val second = asked
        assertTrue(second > first, "a components update asks again")

        width = WIDTH
        waitForIdle()
        assertEquals(second, asked, "and remembers the new refusal as it did the first")
    }

    /**
     * Draws [before] and applies [update] (and swaps to [after]) on one surface, draws the final
     * tree fresh with [after] on another beside it, and asserts [texts] sit alike in both.
     */
    private fun ComposeUiTest.assertDrawnAsIfFresh(
        before: String,
        update: String?,
        texts: List<String>,
        after: ComponentRegistry = registry,
    ) {
        val swapped = rendererFor(before)
        val fresh = rendererFor(before).also { r -> update?.let { r.apply(updateOf(it)) } }
        var current by mutableStateOf(registry)
        setContent {
            MaterialTheme {
                Column {
                    Box(Modifier.size(WIDTH, HEIGHT).testTag(SWAPPED)) { A2uiSurface(swapped, SURFACE, current) }
                    Box(Modifier.size(WIDTH, HEIGHT).testTag(FRESH)) { A2uiSurface(fresh, SURFACE, after) }
                }
            }
        }
        waitForIdle()
        update?.let { swapped.apply(updateOf(it)) }
        current = after
        waitForIdle()
        for (text in texts) {
            val got = boundsIn(SWAPPED, text)
            val want = boundsIn(FRESH, text)
            assertTrue(got.alike(want), "\"${text.take(12)}\" is drawn at $got after the swap, and at $want drawn fresh")
        }
    }

    private fun ComposeUiTest.boundsIn(tag: String, text: String): Rect {
        val host = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val node = onNode(hasText(text) and hasAnyAncestor(hasTestTag(tag))).fetchSemanticsNode().boundsInRoot
        return node.translate(-host.left, -host.top)
    }

    private fun Rect.alike(other: Rect) =
        abs(left - other.left) <= 1f && abs(top - other.top) <= 1f &&
            abs(right - other.right) <= 1f && abs(bottom - other.bottom) <= 1f

    /** Refuses every intrinsic query, as a `SubcomposeLayout` does, counting each one. */
    private class Refusing(private val onAsked: () -> Unit) : MeasurePolicy {
        override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult =
            40.dp.roundToPx().let { side -> layout(constraints.constrainWidth(side), constraints.constrainHeight(side)) {} }

        private fun refuse(): Nothing {
            onAsked()
            throw IllegalStateException("cannot be asked")
        }

        override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int = refuse()
        override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int = refuse()
        override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int = refuse()
        override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int = refuse()
    }

    private fun rendererFor(components: String): A2uiRenderer = A2uiRenderer().also { renderer ->
        renderer.applyAll(
            listOf(
                message("""{"version":"v1.0","createSurface":{"surfaceId":"$SURFACE","catalogId":"${BasicCatalog.id}"}}"""),
                message("""{"version":"v1.0","updateComponents":{"surfaceId":"$SURFACE","components":$components}}"""),
            ),
        )
    }

    private fun updateOf(components: String) =
        message("""{"version":"v1.0","updateComponents":{"surfaceId":"$SURFACE","components":$components}}""")

    private fun message(json: String) = A2uiJson.strict.decodeFromString(AgentToRendererMessage.serializer(), json)

    private companion object {
        const val SURFACE = "s"
        const val SWAPPED = "swapped"
        const val FRESH = "fresh"
        val WIDTH = 320.dp
        val HEIGHT = 300.dp
        const val LONG_A = "A first sentence that is comfortably longer than half a phone screen"
        const val LONG_B = "And a second one that is longer still, so neither fits beside the other"

        /** Host renderers: one built on a lazy list and a tile, neither of which can be asked, and a tile the same size that can. */
        val registry = Material3Components.Basic.with(
            mapOf(
                "Feed" to ComponentRenderer { scope, m -> LazyColumn(m) { item { Text(scope.string("text").orEmpty()) } } },
                "RefusingTile" to ComponentRenderer { _, m -> Layout(m, measurePolicy = Refusing {}) },
                "Tile" to ComponentRenderer { _, m -> Box(m.size(40.dp)) },
            ),
        )
    }
}
