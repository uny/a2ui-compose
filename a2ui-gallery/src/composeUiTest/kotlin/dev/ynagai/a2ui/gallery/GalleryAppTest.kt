package dev.ynagai.a2ui.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
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
     */
    @Composable
    private fun Gallery(
        state: GalleryState = GalleryState(examples = listOf(simpleText)),
        width: Dp = 1400.dp,
        height: Dp = 900.dp,
    ) {
        Box(Modifier.size(width, height)) {
            GalleryApp(state = state)
        }
    }

    private companion object {
        val simpleText = EXAMPLES.single { it.file == "00_simple-text.json" }
        val rowLayout = EXAMPLES.single { it.file == "00_row-layout.json" }

        /** `00_simple-text.json`'s one line, with the Markdown heading marker rendered away. */
        const val SIMPLE_TEXT_BODY = "Hello, Minimal Catalog!"
    }
}
