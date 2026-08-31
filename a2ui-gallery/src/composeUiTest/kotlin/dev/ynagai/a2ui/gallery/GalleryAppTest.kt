package dev.ynagai.a2ui.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Gallery's own chrome: that the three panes are there, and that its controls drive it.
 *
 * What [GalleryStateTest] cannot settle. Stepping is asserted there against the state object;
 * here the claim is that the button is wired to it, that the preview draws what the stepping
 * produced, and that a window too narrow for three columns still reaches all three panes -- none
 * of which has a value to return.
 */
@OptIn(ExperimentalTestApi::class)
class GalleryAppTest {
    @Test
    fun a_wide_window_shows_all_three_columns_at_once() = runComposeUiTest {
        setContent { Gallery() }
        onNodeWithTag(GalleryTags.SAMPLE_LIST).assertIsDisplayed()
        onNodeWithTag(GalleryTags.PREVIEW).assertIsDisplayed()
        onNodeWithTag(GalleryTags.MESSAGES).assertIsDisplayed()
        onNodeWithTag(GalleryTags.DATA_MODEL).assertIsDisplayed()
        onNodeWithTag(GalleryTags.ACTION_LOG).assertIsDisplayed()
    }

    @Test
    fun the_stepper_draws_the_example_one_message_at_a_time() = runComposeUiTest {
        val state = GalleryState(examples = listOf(simpleText))
        setContent { Gallery(state) }

        // Nothing yet -- and the pane says so rather than showing an empty box, which would look
        // the same as a surface that drew nothing.
        onNodeWithText("Nothing applied yet — press Advance.").assertIsDisplayed()

        onNodeWithTag(GalleryTags.ADVANCE).performClick()
        // The surface exists after `createSurface`, but its root has not arrived, so there is
        // still nothing to draw. This is the progressive-rendering step the blueprint asks the
        // stepper to make visible.
        onNodeWithText(SIMPLE_TEXT_BODY).assertDoesNotExist()

        onNodeWithTag(GalleryTags.ADVANCE).performClick()
        onNodeWithText(SIMPLE_TEXT_BODY).assertIsDisplayed()
        // And the stepper has run out, so its button says so.
        onNodeWithTag(GalleryTags.ADVANCE).assertIsNotEnabled()
    }

    @Test
    fun run_all_and_reset_are_the_two_ends_of_the_stepper() = runComposeUiTest {
        val state = GalleryState(examples = listOf(simpleText))
        setContent { Gallery(state) }

        onNodeWithTag(GalleryTags.ADVANCE_ALL).performClick()
        onNodeWithText(SIMPLE_TEXT_BODY).assertIsDisplayed()

        onNodeWithTag(GalleryTags.RESET).performClick()
        onNodeWithText(SIMPLE_TEXT_BODY).assertDoesNotExist()
        onNodeWithText("Nothing applied yet — press Advance.").assertIsDisplayed()
    }

    @Test
    fun choosing_a_sample_switches_what_the_preview_draws() = runComposeUiTest {
        val state = GalleryState(examples = listOf(simpleText, rowLayout))
        setContent { Gallery(state) }
        onNodeWithTag(GalleryTags.ADVANCE_ALL).performClick()
        onNodeWithText(SIMPLE_TEXT_BODY).assertIsDisplayed()

        onNodeWithTag(GalleryTags.sample(rowLayout.file)).performClick()
        // Switched, and back to nothing applied: the new example has to be stepped in its own
        // right rather than inheriting the previous one's cursor.
        onNodeWithText(SIMPLE_TEXT_BODY).assertDoesNotExist()
        // By tag, not by text: the sample list shows the same title, so a text match would find
        // two nodes and could not say which pane had changed.
        onNodeWithTag(GalleryTags.EXAMPLE_TITLE).assertTextEquals(rowLayout.name)
        onNodeWithTag(GalleryTags.ADVANCE_ALL).performClick()
        onNodeWithText(SIMPLE_TEXT_BODY).assertDoesNotExist()
        // The positive half. Without it every assertion above is a negative that also holds when
        // `select` left the old renderer in place, or when `Run all` applied nothing at all, or
        // when the preview drew its "nothing yet" text forever -- and the test's name is a claim
        // about what the preview *draws*.
        onNodeWithText(ROW_LAYOUT_LEFT).assertIsDisplayed()
        onNodeWithText(ROW_LAYOUT_RIGHT).assertIsDisplayed()
    }

    /**
     * The action log is wired to the surface.
     *
     * `onMessage = state::record` is the whole of the right-hand pane's input, and nothing else in
     * this suite crosses it: every other assertion about the log passes with `onMessage = {}`. So
     * this one goes through the rendered surface -- clicking the agent's own `Button` -- rather
     * than calling `record` directly, which [GalleryStateTest] already covers.
     */
    @Test
    fun an_action_from_the_surface_reaches_the_action_log() = runComposeUiTest {
        val state = GalleryState(examples = listOf(interactiveButton))
        setContent { Gallery(state) }
        onNodeWithTag(GalleryTags.ADVANCE_ALL).performClick()
        onNodeWithText(INTERACTIVE_BUTTON_LABEL).assertIsDisplayed()
        assertEquals(emptyList(), state.actionLog.toList(), "nothing has been clicked yet")

        onNodeWithText(INTERACTIVE_BUTTON_LABEL).performClick()

        val entry = state.actionLog.single()
        assertEquals("button_clicked", entry.label)
        assertEquals("action_button", entry.sourceComponentId)
        // And the pane counted it. By the heading rather than by the entry's own text: the action's
        // name also appears in the message-stream JSON behind it, so a text match on the label
        // finds three nodes and cannot say which pane moved.
        onNodeWithTag(GalleryTags.ACTION_LOG).assertIsDisplayed()
        onNodeWithText("Actions (1)").assertIsDisplayed()
    }

    @Test
    fun a_narrow_window_reaches_the_same_three_panes_one_at_a_time() = runComposeUiTest {
        setContent { Gallery(width = 480.dp, height = 800.dp) }

        // The render pane first: on a narrow screen it is the one worth opening on.
        onNodeWithTag(GalleryTags.PREVIEW).assertIsDisplayed()
        onNodeWithTag(GalleryTags.SAMPLE_LIST).assertDoesNotExist()

        onNodeWithText("Samples").performClick()
        onNodeWithTag(GalleryTags.SAMPLE_LIST).assertIsDisplayed()
        onNodeWithTag(GalleryTags.PREVIEW).assertDoesNotExist()

        onNodeWithText("Inspect").performClick()
        onNodeWithTag(GalleryTags.DATA_MODEL).assertIsDisplayed()
        onNodeWithTag(GalleryTags.ACTION_LOG).assertIsDisplayed()
    }

    /**
     * The Gallery at a fixed size.
     *
     * A size rather than the harness's default: which layout [GalleryApp] chooses is a function of
     * the width it is given, and a default that happened to sit either side of the threshold would
     * make these tests pass or fail for a reason none of them is about.
     *
     * `requiredSize`, not `size`. `size` enforces the incoming constraints, so it can only shrink
     * the box -- it never grows it past the harness root, which is 1024x768. Asking for 1400dp
     * through `size` therefore measured at 1024dp and pinned nothing; a harness default that ever
     * dropped below the Gallery's three-column threshold would have swapped these tests onto the
     * narrow layout in silence, which is the exact confusion this fixed size exists to prevent. The
     * values below sit above the threshold and inside the harness, so the box is honoured whole.
     */
    @Composable
    private fun Gallery(
        state: GalleryState = GalleryState(examples = listOf(simpleText)),
        width: Dp = 1000.dp,
        height: Dp = 760.dp,
    ) {
        Box(Modifier.requiredSize(width, height)) {
            GalleryApp(state = state)
        }
    }

    private companion object {
        val simpleText = EXAMPLES.single { it.file == "00_simple-text.json" }
        val rowLayout = EXAMPLES.single { it.file == "00_row-layout.json" }
        val interactiveButton = EXAMPLES.single { it.file == "00_interactive-button.json" }

        /** `00_simple-text.json`'s one line, with the Markdown heading marker rendered away. */
        const val SIMPLE_TEXT_BODY = "Hello, Minimal Catalog!"

        /** `00_row-layout.json`'s two cells. */
        const val ROW_LAYOUT_LEFT = "Left Content"
        const val ROW_LAYOUT_RIGHT = "Right Content"

        /** `00_interactive-button.json`'s `Button`, by the label its child `Text` carries. */
        const val INTERACTIVE_BUTTON_LABEL = "Click Me"
    }
}
