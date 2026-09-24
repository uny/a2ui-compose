package dev.ynagai.a2ui.material3

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ynagai.a2ui.compose.A2uiRenderer
import dev.ynagai.a2ui.compose.A2uiSurface
import dev.ynagai.a2ui.compose.BasicCatalog
import dev.ynagai.a2ui.compose.ComponentRenderer
import dev.ynagai.a2ui.compose.LayoutTraits
import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.AgentToRendererMessage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A row answers how tall it can be over every width its children can be offered (#103).
 *
 * A row beside a filler cannot foretell what the filler takes, and a weighted column of a long text
 * and a video is taller at some widths between the two extremes than at either: its text is still
 * long there and its video already wide. Each tree here puts such a row in a column above a text,
 * on a surface tall enough that the column is not short of room -- so the row is measured to its
 * answer, and an answer short of what it draws leaves the video over the text. The row is also
 * drawn on its own, with nothing asking it anything, and each video must sit where it does there.
 */
@OptIn(ExperimentalTestApi::class)
class RowBandTest {

    @Test
    fun a_weighted_column_beside_an_image_is_not_drawn_over_the_text_below() = runComposeUiTest {
        assertRowFollowedClosely(
            row = """{"id":"row","component":"Row","children":["image","col"]},""",
            parts = """
                {"id":"image","component":"Image","url":"https://example.invalid/i.png","description":"image"},
                {"id":"col","component":"Column","weight":1,"children":["long","video"]},
                $LONG_AND_VIDEO
            """,
        )
    }

    @Test
    fun a_weighted_card_beside_an_image_is_not_drawn_over_the_text_below() = runComposeUiTest {
        // The card's padding narrows what its column is offered by the same amount at every width.
        // Wider than the issue's tree, where the padding moves the text's wrapping out of the way.
        assertRowFollowedClosely(
            width = 880.dp,
            row = """{"id":"row","component":"Row","children":["image","card"]},""",
            parts = """
                {"id":"image","component":"Image","url":"https://example.invalid/i.png","description":"image"},
                {"id":"card","component":"Card","weight":1,"child":"col"},
                {"id":"col","component":"Column","children":["long","video"]},
                $LONG_AND_VIDEO
            """,
        )
    }

    @Test
    fun a_row_inside_a_weighted_column_is_not_drawn_over_the_text_below() = runComposeUiTest {
        // The widths reach the text and the video through a column and a second row.
        assertRowFollowedClosely(
            row = """{"id":"row","component":"Row","children":["image","outer"]},""",
            parts = """
                {"id":"image","component":"Image","url":"https://example.invalid/i.png","description":"image"},
                {"id":"outer","component":"Column","weight":1,"children":["inner"]},
                {"id":"inner","component":"Row","children":["col"]},
                {"id":"col","component":"Column","weight":1,"children":["long","video"]},
                $LONG_AND_VIDEO
            """,
        )
    }

    @Test
    fun unevenly_weighted_children_beside_an_image_are_not_drawn_over_the_text_below() = runComposeUiTest {
        // The column's share is a third of what the image leaves, and its sibling's two thirds.
        // A shorter text than the issue's, at the width where its wrapping holds between the two
        // extremes of what the column is offered.
        assertRowFollowedClosely(
            width = 1900.dp,
            row = """{"id":"row","component":"Row","children":["image","col","b"]},""",
            parts = """
                {"id":"image","component":"Image","url":"https://example.invalid/i.png","description":"image"},
                {"id":"col","component":"Column","weight":1,"children":["long","video"]},
                {"id":"b","component":"Text","weight":2,"text":"b"},
                {"id":"long","component":"Text","text":"${long(20)}"},
                {"id":"video","component":"Video","url":"https://example.invalid/v.mp4"}
            """,
        )
    }

    @Test
    fun a_weighted_child_tallest_at_the_share_the_plan_gives_it_keeps_that_height() = runComposeUiTest {
        // A guard, true before #103 too: the row is asked at the width its plan gives each child,
        // and whatever it asks besides, it still answers at least that. A host component that is
        // tall at exactly its share and short either side of it is measured there.
        assertPeakAboveText("""{"id":"row","component":"Row","children":["fills","peak"]},{"id":"peak","component":"Peak","weight":1},""")
    }

    @Test
    fun a_weighted_column_of_a_child_tallest_at_its_share_keeps_that_height() = runComposeUiTest {
        // The same through a column, which is asked over the widths its share can be and still
        // answers for the one the plan gives it.
        assertPeakAboveText(
            """{"id":"row","component":"Row","children":["fills","col"]},
               {"id":"col","component":"Column","weight":1,"children":["peak"]},
               {"id":"peak","component":"Peak"},""",
        )
    }

    /**
     * Draws a column of [row] above a text, and [row] on its own with nothing asking it anything,
     * and asserts every video sits alike in both and the text starts where the row drawn on its own
     * ends: below it, with nothing opened between them.
     */
    private fun ComposeUiTest.assertRowFollowedClosely(row: String, parts: String, width: Dp = WIDTH) {
        val inColumn = rendererFor("""[
            {"id":"root","component":"Column","children":["row","after"]},
            $row
            $parts,
            {"id":"after","component":"Text","text":"$AFTER"}
        ]""")
        val alone = rendererFor("""[${row.replace("\"id\":\"row\"", "\"id\":\"root\"")} $parts]""")
        var asked = 0
        var drawn = 0
        setContent {
            MaterialTheme {
                // Stacked rather than side by side, and required rather than offered: the test
                // window is smaller than either, and a surface squeezed into what is left of it is
                // not the one under test.
                Box {
                    Box(Modifier.requiredSize(width, HEIGHT).testTag(IN_COLUMN)) {
                        Answering({ asked = it }) { A2uiSurface(inColumn, SURFACE, Material3Components.Basic) }
                    }
                    Box(Modifier.requiredSize(width, HEIGHT).testTag(ALONE)) {
                        Drawn({ drawn = it }) { A2uiSurface(alone, SURFACE, Material3Components.Basic) }
                    }
                }
            }
        }
        waitForIdle()
        // The column must have the room its children ask for, or it shares by their answers and
        // the answer under test stops deciding where anything is drawn.
        assertTrue(asked <= HEIGHT_PX, "the column is not short of room: it asks for $asked of $HEIGHT_PX")

        val videos = videosIn(IN_COLUMN)
        val reference = videosIn(ALONE)
        assertEquals(reference.size, videos.size)
        videos.zip(reference).forEach { (got, want) ->
            assertTrue(got.alike(want), "a video is drawn at $got under the column, and at $want on its own")
        }
        val after = boundsIn(IN_COLUMN, hasText(AFTER))
        val expected = drawn + LEAF_MARGIN_PX
        assertTrue(abs(after.top - expected) <= 1f, "the text starts where the row drawn on its own ends, at $expected: $after")
    }

    private fun ComposeUiTest.assertPeakAboveText(row: String) {
        val renderer = rendererFor("""[
            {"id":"root","component":"Column","children":["row","after"]},
            $row
            {"id":"fills","component":"Fills"},
            {"id":"after","component":"Text","text":"$AFTER"}
        ]""")
        setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(PEAK_WIDTH, HEIGHT).testTag(IN_COLUMN)) { A2uiSurface(renderer, SURFACE, peakRegistry) }
            }
        }
        val peak = boundsIn(IN_COLUMN, hasTestTag("peak"))
        val after = boundsIn(IN_COLUMN, hasText(AFTER))
        assertEquals(PEAK_HEIGHT_PX, peak.height, "the peak is drawn at its share: $peak")
        assertTrue(after.top >= peak.bottom - 1f, "the text starts below it: $after under $peak")
    }

    /** Reports its content's maximum intrinsic height at the width it is offered, then draws it. */
    @Composable
    private fun Answering(report: (Int) -> Unit, content: @Composable () -> Unit) {
        Layout(content) { measurables, constraints ->
            report(measurables.sumOf { it.maxIntrinsicHeight(constraints.maxWidth) })
            val placeables = measurables.map { it.measure(constraints) }
            layout(constraints.maxWidth, constraints.maxHeight) { placeables.forEach { it.place(0, 0) } }
        }
    }

    /** Reports the height its content is drawn at, with nothing asking it anything first. */
    @Composable
    private fun Drawn(report: (Int) -> Unit, content: @Composable () -> Unit) {
        Layout(content) { measurables, constraints ->
            val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
            report(placeables.maxOfOrNull { it.height } ?: 0)
            layout(constraints.maxWidth, constraints.maxHeight) { placeables.forEach { it.place(0, 0) } }
        }
    }

    private fun ComposeUiTest.videosIn(tag: String): List<Rect> =
        onAllNodes(hasContentDescription("Video") and hasAnyAncestor(hasTestTag(tag)))
            .fetchSemanticsNodes()
            .map { it.boundsInRoot.translate(-host(tag).left, -host(tag).top) }
            .sortedBy { it.left }

    private fun ComposeUiTest.host(tag: String): Rect = onNode(hasTestTag(tag)).fetchSemanticsNode().boundsInRoot

    private fun ComposeUiTest.boundsIn(tag: String, matcher: SemanticsMatcher): Rect =
        onNode(matcher and hasAnyAncestor(hasTestTag(tag))).fetchSemanticsNode().boundsInRoot
            .translate(-host(tag).left, -host(tag).top)

    private fun Rect.alike(other: Rect) =
        abs(left - other.left) <= 1f && abs(top - other.top) <= 1f &&
            abs(right - other.right) <= 1f && abs(bottom - other.bottom) <= 1f

    /** As tall as [PEAK_HEIGHT_PX] when offered exactly [PEAK_AT_PX] wide, and short at every other width. */
    private object Peak : MeasurePolicy {
        fun heightAt(width: Int) = if (width == PEAK_AT_PX) PEAK_HEIGHT_PX.toInt() else 20

        override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult =
            layout(constraints.maxWidth, heightAt(constraints.maxWidth)) {}

        override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) = 0
        override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) = 0
        override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) = heightAt(width)
        override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) = heightAt(width)
    }

    private fun rendererFor(components: String): A2uiRenderer = A2uiRenderer().also { renderer ->
        renderer.applyAll(
            listOf(
                message("""{"version":"v1.0","createSurface":{"surfaceId":"$SURFACE","catalogId":"${BasicCatalog.id}"}}"""),
                message("""{"version":"v1.0","updateComponents":{"surfaceId":"$SURFACE","components":$components}}"""),
            ),
        )
    }

    private fun message(json: String) = A2uiJson.strict.decodeFromString(AgentToRendererMessage.serializer(), json)

    private companion object {
        const val SURFACE = "s"
        const val IN_COLUMN = "inColumn"
        const val ALONE = "alone"

        /** The issue's width: the image takes 316 of it, and the weighted column is offered anything from 380 to 760 and drawn at 444. */
        val WIDTH = 760.dp

        /** Tall enough that the column around the row has the room its children ask for; checked, not assumed. */
        val HEIGHT: Dp = 1600.dp
        const val HEIGHT_PX = 1600

        /** The harness draws at a density of 1, so a dp is a pixel. */
        const val LEAF_MARGIN_PX = 8f

        fun long(words: Int) = List(words) { "alphaalphaalphaalpha" }.joinToString(" ")
        val LONG = long(36)
        val AFTER = List(16) { "after" }.joinToString(" ")
        val LONG_AND_VIDEO = """
            {"id":"long","component":"Text","text":"$LONG"},
            {"id":"video","component":"Video","url":"https://example.invalid/v.mp4"}
        """

        /** A row [PEAK_WIDTH] wide gives a filler and one weighted child 200 each. */
        val PEAK_WIDTH = 400.dp
        const val PEAK_AT_PX = 200
        const val PEAK_HEIGHT_PX = 300f

        val peakRegistry = Material3Components.Basic.with(
            mapOf(
                "Fills" to ComponentRenderer(traits = { _, _ -> LayoutTraits.Fill }) { _, m -> Box(m.fillMaxSize()) },
                "Peak" to ComponentRenderer { _, m -> Layout({}, m.testTag("peak"), Peak) },
            ),
        )
    }
}
