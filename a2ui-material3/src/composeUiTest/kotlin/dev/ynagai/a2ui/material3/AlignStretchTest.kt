package dev.ynagai.a2ui.material3

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ynagai.a2ui.compose.A2uiRenderer
import dev.ynagai.a2ui.compose.A2uiSurface
import dev.ynagai.a2ui.compose.BasicCatalog
import dev.ynagai.a2ui.compose.ComponentRegistry
import dev.ynagai.a2ui.compose.ComponentRenderer
import dev.ynagai.a2ui.compose.LayoutTraits
import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.AgentToRendererMessage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `align: stretch`, the catalog's default for a `Row` and a `Column`.
 *
 * Every surface here is hosted the way an app hosts one -- in a box that bounds it and imposes no
 * minimum -- rather than in the fixed-size box the other layout tests use. A fixed box hands the
 * root its width as a minimum, which is exactly the case where a column needs to learn nothing, and
 * a column that shrink-wrapped its children passed there.
 *
 * Each claim is checked against `align: start` on the same payload, so an assertion that stretching
 * holds cannot pass because the children happened to be the same size anyway.
 */
@OptIn(ExperimentalTestApi::class)
class AlignStretchTest {
    @Test
    fun a_row_stretches_its_children_to_the_tallest() = runComposeUiTest {
        setContent { Hosted(rowOf(align = null)) }
        val short = bounds("short")
        val tall = bounds(LONG)
        assertTrue(tall.height > LINE * 2, "the long text wraps to several lines: $tall")
        assertTrue(near(short.height, tall.height), "the short text is stretched to the line: $short beside $tall")
    }

    @Test
    fun a_row_aligned_to_the_start_leaves_its_children_their_own_height() = runComposeUiTest {
        setContent { Hosted(rowOf(align = "start")) }
        assertTrue(bounds("short").height * 2 < bounds(LONG).height, "the control: nothing is stretched")
    }

    @Test
    fun a_column_is_as_wide_as_it_is_offered_and_stretches_its_children_to_that() = runComposeUiTest {
        setContent { Hosted(columnOf(align = null)) }
        val a = bounds("a")
        val wide = bounds("a wider text")
        assertTrue(near(a.width, wide.width), "both texts span the column: $a, $wide")
        assertTrue(a.width >= WIDTH - 2 * LEAF_MARGIN - 1, "and the column spans the width it was offered: $a")
    }

    @Test
    fun a_column_aligned_to_the_start_leaves_its_children_their_own_width() = runComposeUiTest {
        setContent { Hosted(columnOf(align = "start")) }
        assertTrue(bounds("a").width < bounds("a wider text").width, "the control: nothing is stretched")
    }

    @Test
    fun a_column_inside_a_centred_column_is_as_wide_as_its_widest_child() = runComposeUiTest {
        // CSS shrink-wraps a flex item that is not stretched. A column that took the width it was
        // offered here would be as wide as the screen, and the centring would move nothing.
        setContent { Hosted(CENTRED_COLUMN) }
        val a = bounds("a")
        val wide = bounds("a wider text")
        assertTrue(near(a.width, wide.width), "the inner column still stretches its own children: $a, $wide")
        assertTrue(wide.width < WIDTH / 2, "the inner column is as wide as its widest child: $wide")
        assertTrue(wide.left > WIDTH / 4, "and is centred: $wide")
    }

    @Test
    fun a_column_two_levels_inside_a_centred_column_does_not_undo_the_centring() = runComposeUiTest {
        // The inner column is the child of one that shrink-wraps. Told to fill what that one was
        // offered, it is as wide as the screen, and so is everything around it.
        setContent { Hosted(TWICE_NESTED_CENTRED_COLUMN) }
        val wide = bounds("a wider text")
        assertTrue(wide.width < WIDTH / 2, "the nested columns are as wide as their widest child: $wide")
        assertTrue(wide.left > WIDTH / 4, "and are centred: $wide")
    }

    @Test
    fun a_fixed_size_child_is_not_stretched() = runComposeUiTest {
        // An avatar is a 40dp square. Stretched to the text beside it, it is a pill; stretched to
        // the column, a bar.
        setContent { Hosted(AVATAR_BESIDE_AND_ABOVE_LONG_TEXT) }
        val inRow = onNodeWithContentDescription("avatar-in-row").fetchSemanticsNode().boundsInRoot
        val inColumn = onNodeWithContentDescription("avatar-in-column").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds(LONG).height > LINE * 2, "the text beside it is taller than it: ${bounds(LONG)}")
        assertTrue(near(inRow.height, AVATAR), "the row's avatar keeps its height: $inRow")
        assertTrue(near(inColumn.width, AVATAR), "the column's avatar keeps its width: $inColumn")
    }

    @Test
    fun a_host_component_that_says_it_is_fixed_is_left_at_its_size() = runComposeUiTest {
        for ((traits, stretched) in listOf(LayoutTraits.Content to true, LayoutTraits.Fixed to false)) {
            setContent { Hosted(PROBE_BESIDE_LONG_TEXT, registry = probeRegistry(traits)) }
            val probe = onNodeWithTag(PROBE).fetchSemanticsNode().boundsInRoot
            // The text's bounds are inside its margin; the line is the text with its margin.
            val line = bounds(LONG).height + 2 * LEAF_MARGIN
            assertTrue(near(probe.height, line) == stretched, "${traits.fit}: $probe beside a line of $line")
        }
    }

    @Test
    fun a_slider_under_a_label_keeps_the_column_width() = runComposeUiTest {
        // A slider fills whatever width it is given. Stretched to the line of a column that
        // shrink-wrapped its children, it would be as wide as its label.
        setContent { Hosted(LABELLED_SLIDER) }
        val slider = onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .fetchSemanticsNode().boundsInRoot
        assertTrue(slider.width >= WIDTH - 2 * LEAF_MARGIN - 1, "the slider spans the column: $slider")
    }

    @Test
    fun a_vertical_divider_spans_the_row_rather_than_what_the_row_was_offered() = runComposeUiTest {
        // A vertical divider fills the height it is given, and a row at the root of a surface is
        // given the surface's. Measured up to the line instead, it is as tall as the text.
        for (align in listOf(null, "start")) {
            setContent { Hosted(dividerRow(align)) }
            val row = onNodeWithTag(HOST).fetchSemanticsNode().boundsInRoot
            val text = bounds("two\nlines").height + 2 * LEAF_MARGIN
            if (align == null) {
                assertTrue(near(row.height, text), "the row is as tall as its text: $row for $text")
            } else {
                // The control: unstretched, the divider takes what the row was offered.
                assertTrue(near(row.height, HEIGHT), "the divider takes the surface: $row")
            }
        }
    }

    @Test
    fun a_row_whose_line_cannot_be_foreseen_is_drawn_as_start() = runComposeUiTest {
        // An empty row fills, and a weighted text beside it gets whatever the empty row leaves --
        // which cannot be asked, so neither can the height of the text. A line learned from the
        // plan's guess would stretch the short text to a height the weighted one never reaches.
        setContent { Hosted(UNFORESEEABLE_ROW) }
        assertTrue(bounds("short").height * 2 < bounds(LONG).height, "nothing is stretched")
    }

    @Test
    fun a_row_with_two_fillers_is_drawn_as_start() = runComposeUiTest {
        // What the second filler gets depends on what the first one takes, which cannot be asked.
        setContent { Hosted(TWO_FILLERS_BESIDE_TEXTS) }
        assertTrue(bounds("short").height * 1.5f < bounds("two\nlines").height, "nothing is stretched")
    }

    @Test
    fun a_row_holding_a_child_that_cannot_be_asked_still_draws_everything() = runComposeUiTest {
        // Tabs are a `SubcomposeLayout`, and fill a row: the line cannot be learned from them, so
        // the row is drawn as `start` -- the short text beside them keeps its own height. The
        // feed cannot be asked either and is measured first.
        val registry = Material3Components.Basic.with(
            mapOf(
                "Feed" to ComponentRenderer { _, m ->
                    LazyColumn(m.heightIn(max = 200.dp)) { items(listOf("line one", "line two")) { Text(it) } }
                },
            ),
        )
        for (payload in listOf(TABS_BESIDE_TEXT, FEED_BESIDE_TEXT)) {
            setContent { Hosted(payload, registry = registry) }
            assertTrue(bounds("beside").width > 0f, "the text beside is drawn: $payload")
        }
        setContent { Hosted(TABS_BESIDE_TEXT, registry = registry) }
        assertTrue(bounds("beside").height * 2 < bounds(LONG).height, "nothing is stretched beside the tabs")
    }

    @Test
    fun a_row_with_one_filler_beside_shrunk_children_is_drawn_as_start() = runComposeUiTest {
        // A text shrunk to a width may wrap narrower than it, and the filler after it gets the
        // difference -- so the video's height, and the line, cannot be asked.
        setContent { Hosted(SHRUNK_TEXTS_BESIDE_A_VIDEO) }
        assertTrue(bounds("short").height * 2 < bounds("$LONG $LONG").height, "nothing is stretched")
    }

    @Test
    fun a_column_in_a_row_that_cannot_be_asked_leaves_a_weighted_sibling_its_share() = runComposeUiTest {
        // A column that cannot be asked is measured first, against what the row can spare, and
        // the row reserves nothing for its weighted children. A column as wide as it was offered
        // took all of it; one as wide as its content leaves the weighted text the rest.
        for (align in listOf(null, "start")) {
            setContent { Hosted(refusingColumnBesideWeightedText(align), registry = refuserRegistry) }
            assertTrue(bounds("rest").width > WIDTH / 2, "the weighted text keeps its share ($align): ${bounds("rest")}")
        }
    }

    @Test
    fun a_column_nested_in_a_row_that_cannot_be_asked_leaves_a_weighted_sibling_its_share() = runComposeUiTest {
        // The same, one column further down. The outer column is as wide as its content, and a
        // column inside it has no wider width to take than that: taking what the outer one was
        // offered is taking the row's whole cap again.
        for (align in listOf(null, "start")) {
            setContent { Hosted(nestedRefusingColumnBesideWeightedText(align), registry = refuserRegistry) }
            assertTrue(bounds("rest").width > WIDTH / 2, "the weighted text keeps its share ($align): ${bounds("rest")}")
        }
    }

    @Test
    fun a_column_placed_by_a_list_is_as_wide_as_its_content() = runComposeUiTest {
        // A list centring its items has to be able to move them: a column item that filled the
        // list's width would leave its text at the left edge.
        setContent { Hosted(CENTRED_LIST_OF_A_COLUMN) }
        assertTrue(bounds("item").left > WIDTH / 4, "the item is centred: ${bounds("item")}")
    }

    @Test
    fun a_column_in_a_horizontal_list_is_as_wide_as_its_content() = runComposeUiTest {
        // A horizontal list caps each item's width, and a column that took the cap would leave
        // the list one wide item where the agent sent a few small ones.
        setContent { Hosted(HORIZONTAL_LIST_OF_A_COLUMN) }
        assertTrue(bounds("a wider text").width < WIDTH / 2, "the item is as wide as its text: ${bounds("a wider text")}")
    }

    @Test
    fun a_column_holding_only_a_capped_image_in_a_row_is_as_wide_as_the_image() = runComposeUiTest {
        // A filler takes up to its share. A column around a 300dp image, told to fill the share
        // instead, is as wide as the share, and the weighted text beside it loses the difference.
        setContent { Hosted(CAPPED_IMAGE_COLUMN_BESIDE_WEIGHTED_TEXT, width = WIDE) }
        val rest = bounds("rest")
        // The image and its margins, then the text's own.
        assertTrue(rest.width > WIDE - 300f - 4 * LEAF_MARGIN - 1, "the weighted text gets what the image leaves: $rest")
    }

    @Test
    fun a_column_holding_only_a_banner_in_a_row_still_draws_it() = runComposeUiTest {
        // Nothing in the column says how wide its line is -- the banner fills across it -- so the
        // column does not stretch, and the banner fills the column's share as it did.
        setContent { Hosted(BANNER_COLUMN_BESIDE_TEXT) }
        val banner = onNodeWithContentDescription("banner").fetchSemanticsNode().boundsInRoot
        assertTrue(banner.width > WIDTH / 4, "the banner is drawn: $banner")
    }

    @Test
    fun a_column_in_a_tab_fills_the_tab_whatever_holds_the_tabs() = runComposeUiTest {
        // A tab gives its child the tab's width, as a block does. The centred column around the
        // tabs is not what the column inside them answers to.
        setContent { Hosted(TABS_IN_A_CENTRED_COLUMN) }
        val tabs = onNodeWithText("One").fetchSemanticsNode().boundsInRoot
        val text = bounds("in the tab")
        assertTrue(text.width > WIDTH / 2, "the column in the tab spans it: $text (tab $tabs)")
    }

    @Test
    fun a_column_in_a_weighted_card_fills_the_card() = runComposeUiTest {
        // A weighted card is as wide as its share, and a column inside it is a block there: its
        // centred text sits in the middle of the card, not at the left of a column its own width.
        setContent { Hosted(CENTRED_TEXT_IN_A_WEIGHTED_CARD) }
        val text = bounds("mid")
        assertTrue(text.left > WIDTH / 4, "the text is centred in the card: $text")
    }

    @Test
    fun a_column_in_a_dialog_fills_the_dialog_whatever_holds_the_trigger() = runComposeUiTest {
        // The close button's row spans the dialog. A column that fills the dialog too puts its
        // `end`-aligned text under the button; one as wide as the text leaves it where it starts.
        setContent { Hosted(MODAL_IN_A_CENTRED_COLUMN) }
        onNodeWithText("open").performClick()
        waitForIdle()
        val close = onNodeWithContentDescription("Close").fetchSemanticsNode().boundsInRoot
        val text = bounds("x")
        assertTrue(close.right - text.right < DIALOG_EDGE, "the text reaches the dialog's end: $text, close $close")
    }

    private val refuserRegistry = Material3Components.Basic.with(
        mapOf("Refuser" to ComponentRenderer { _, m -> BoxWithConstraints(m) { Box(Modifier.size(40.dp)) } }),
    )

    private fun ComposeUiTest.bounds(text: String): Rect =
        onNodeWithText(text).fetchSemanticsNode().boundsInRoot

    private fun near(a: Float, b: Float): Boolean = abs(a - b) <= 1f

    @Composable
    private fun Hosted(
        components: String,
        registry: ComponentRegistry = Material3Components.Basic,
        width: Float = WIDTH,
    ) {
        // Bounded, and no minimum: the constraints an app's `Column` or `Scaffold` hands a surface.
        Box(Modifier.testTag(HOST).widthIn(max = width.dp).heightIn(max = HEIGHT.dp)) {
            MaterialTheme { A2uiSurface(surfaceWith(components), SURFACE_ID, registry) }
        }
    }

    private fun probeRegistry(traits: LayoutTraits) = Material3Components.Basic.with(
        mapOf("Probe" to ComponentRenderer(traits) { _, m -> Box(m.testTag(PROBE).size(10.dp)) }),
    )

    private fun surfaceWith(components: String) = A2uiRenderer().also { renderer ->
        renderer.applyAll(
            listOf(
                """{"version":"v1.0","createSurface":{"surfaceId":"$SURFACE_ID","catalogId":"${BasicCatalog.id}"}}""",
                """{"version":"v1.0","updateComponents":{"surfaceId":"$SURFACE_ID","components":$components}}""",
            ).map { A2uiJson.strict.decodeFromString<AgentToRendererMessage>(it) },
        )
    }

    private companion object {
        const val SURFACE_ID = "s"
        const val PROBE = "probe"
        const val HOST = "host"

        /** A phone. The harness draws at a density of 1, so a dp is a pixel. */
        const val WIDTH = 320f
        const val HEIGHT = 600f

        /** A width at which a 300dp image cap leaves a share unfilled. */
        const val WIDE = 800f
        const val LEAF_MARGIN = 8f

        /** One line of body text, and an avatar's side. */
        const val LINE = 24f
        const val AVATAR = 40f

        /** A text's margin and an icon button's inset, with room to spare, and far short of a dialog. */
        const val DIALOG_EDGE = 60f

        /** Long enough to wrap to several lines in half of [WIDTH]. */
        const val LONG = "a text long enough to wrap onto several lines beside a short one"

        fun align(value: String?) = if (value == null) "" else ""","align":"$value""""

        fun rowOf(align: String?) = """[
            {"id":"root","component":"Row","children":["short","long"]${align(align)}},
            {"id":"short","component":"Text","text":"short"},
            {"id":"long","component":"Text","text":"$LONG"}
        ]"""

        fun columnOf(align: String?) = """[
            {"id":"root","component":"Column","children":["a","wide"]${align(align)}},
            {"id":"a","component":"Text","text":"a"},
            {"id":"wide","component":"Text","text":"a wider text"}
        ]"""

        val CENTRED_COLUMN = """[
            {"id":"root","component":"Column","children":["inner"],"align":"center"},
            {"id":"inner","component":"Column","children":["a","wide"]},
            {"id":"a","component":"Text","text":"a"},
            {"id":"wide","component":"Text","text":"a wider text"}
        ]"""

        val TWICE_NESTED_CENTRED_COLUMN = """[
            {"id":"root","component":"Column","children":["outer"],"align":"center"},
            {"id":"outer","component":"Column","children":["inner"]},
            {"id":"inner","component":"Column","children":["a","wide"]},
            {"id":"a","component":"Text","text":"a"},
            {"id":"wide","component":"Text","text":"a wider text"}
        ]"""

        val TWO_FILLERS_BESIDE_TEXTS = """[
            {"id":"root","component":"Row","children":["one","two","short","lines"]},
            {"id":"one","component":"Row","children":[]},
            {"id":"two","component":"Row","children":[]},
            {"id":"short","component":"Text","text":"short"},
            {"id":"lines","component":"Text","text":"two\nlines"}
        ]"""

        val HORIZONTAL_LIST_OF_A_COLUMN = """[
            {"id":"root","component":"List","children":["column"],"direction":"horizontal"},
            {"id":"column","component":"Column","children":["a","wide"]},
            {"id":"a","component":"Text","text":"a"},
            {"id":"wide","component":"Text","text":"a wider text"}
        ]"""

        val AVATAR_BESIDE_AND_ABOVE_LONG_TEXT = """[
            {"id":"root","component":"Column","children":["row","avatar2"]},
            {"id":"row","component":"Row","children":["avatar1","long"]},
            {"id":"avatar1","component":"Image","url":"","variant":"avatar","description":"avatar-in-row"},
            {"id":"long","component":"Text","text":"$LONG"},
            {"id":"avatar2","component":"Image","url":"","variant":"avatar","description":"avatar-in-column"}
        ]"""

        val PROBE_BESIDE_LONG_TEXT = """[
            {"id":"root","component":"Row","children":["probe","long"]},
            {"id":"probe","component":"Probe"},
            {"id":"long","component":"Text","text":"$LONG"}
        ]"""

        val LABELLED_SLIDER = """[
            {"id":"root","component":"Column","children":["label","slider"]},
            {"id":"label","component":"Text","text":"Volume"},
            {"id":"slider","component":"Slider","value":5,"max":10}
        ]"""

        fun dividerRow(align: String?) = """[
            {"id":"root","component":"Row","children":["text","divider"]${align(align)}},
            {"id":"text","component":"Text","text":"two\nlines"},
            {"id":"divider","component":"Divider","axis":"vertical"}
        ]"""

        val UNFORESEEABLE_ROW = """[
            {"id":"root","component":"Row","children":["empty","short","long"]},
            {"id":"empty","component":"Row","children":[]},
            {"id":"short","component":"Text","text":"short"},
            {"id":"long","component":"Text","text":"$LONG","weight":1}
        ]"""

        val SHRUNK_TEXTS_BESIDE_A_VIDEO = """[
            {"id":"root","component":"Row","children":["short","long","video"]},
            {"id":"short","component":"Text","text":"short"},
            {"id":"long","component":"Text","text":"$LONG $LONG"},
            {"id":"video","component":"Video","url":"https://example.invalid/v.mp4"}
        ]"""

        fun refusingColumnBesideWeightedText(align: String?) = """[
            {"id":"root","component":"Row","children":["column","rest"]${align(align)}},
            {"id":"column","component":"Column","children":["refuser"]${align(align)}},
            {"id":"refuser","component":"Refuser"},
            {"id":"rest","component":"Text","text":"rest","weight":1}
        ]"""

        fun nestedRefusingColumnBesideWeightedText(align: String?) = """[
            {"id":"root","component":"Row","children":["outer","rest"]${align(align)}},
            {"id":"outer","component":"Column","children":["inner"]${align(align)}},
            {"id":"inner","component":"Column","children":["refuser"]${align(align)}},
            {"id":"refuser","component":"Refuser"},
            {"id":"rest","component":"Text","text":"rest","weight":1}
        ]"""

        val CENTRED_LIST_OF_A_COLUMN = """[
            {"id":"root","component":"Column","children":["list"]},
            {"id":"list","component":"List","children":["column"],"align":"center"},
            {"id":"column","component":"Column","children":["item"],"align":"start"},
            {"id":"item","component":"Text","text":"item"}
        ]"""

        val CAPPED_IMAGE_COLUMN_BESIDE_WEIGHTED_TEXT = """[
            {"id":"root","component":"Row","children":["column","rest"]},
            {"id":"column","component":"Column","children":["image"]},
            {"id":"image","component":"Image","url":"","description":"image"},
            {"id":"rest","component":"Text","text":"rest","weight":1}
        ]"""

        val BANNER_COLUMN_BESIDE_TEXT = """[
            {"id":"root","component":"Row","children":["column","x"]},
            {"id":"column","component":"Column","children":["banner"]},
            {"id":"banner","component":"Image","url":"","description":"banner"},
            {"id":"x","component":"Text","text":"x"}
        ]"""

        val CENTRED_TEXT_IN_A_WEIGHTED_CARD = """[
            {"id":"root","component":"Row","children":["card"]},
            {"id":"card","component":"Card","child":"column","weight":1},
            {"id":"column","component":"Column","children":["mid"],"align":"center"},
            {"id":"mid","component":"Text","text":"mid"}
        ]"""

        val MODAL_IN_A_CENTRED_COLUMN = """[
            {"id":"root","component":"Column","children":["modal"],"align":"center"},
            {"id":"modal","component":"Modal","trigger":"open","content":"column"},
            {"id":"open","component":"Text","text":"open"},
            {"id":"column","component":"Column","children":["text","wide"],"align":"end"},
            {"id":"text","component":"Text","text":"x"},
            {"id":"wide","component":"Text","text":"wider"}
        ]"""

        val TABS_IN_A_CENTRED_COLUMN = """[
            {"id":"root","component":"Column","children":["tabs"],"align":"center"},
            {"id":"tabs","component":"Tabs","tabs":[{"title":"One","child":"column"}]},
            {"id":"column","component":"Column","children":["text"]},
            {"id":"text","component":"Text","text":"in the tab"}
        ]"""

        val TABS_BESIDE_TEXT = """[
            {"id":"root","component":"Row","children":["tabs","beside","long"]},
            {"id":"tabs","component":"Tabs","tabs":[{"title":"One","child":"one"}]},
            {"id":"one","component":"Text","text":"one"},
            {"id":"beside","component":"Text","text":"beside"},
            {"id":"long","component":"Text","text":"$LONG"}
        ]"""

        val FEED_BESIDE_TEXT = """[
            {"id":"root","component":"Row","children":["feed","beside"]},
            {"id":"feed","component":"Feed"},
            {"id":"beside","component":"Text","text":"beside"}
        ]"""
    }
}
