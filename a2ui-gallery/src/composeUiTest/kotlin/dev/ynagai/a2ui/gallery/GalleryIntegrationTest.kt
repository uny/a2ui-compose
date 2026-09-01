package dev.ynagai.a2ui.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ynagai.a2ui.compose.A2uiPlaceholderReason
import dev.ynagai.a2ui.core.protocol.ActionMessage
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The five integration claims the framework adapter blueprint's §7 requires of every renderer,
 * plus the corpus load G2 is judged on.
 *
 * The blueprint asks for these to be made "utilizing the Gallery App's logic", and that is the
 * point of them rather than a formality: each one is a whole-stack assertion -- a payload from the
 * specification's own corpus goes in through [GalleryState], the shipped registry draws it, a
 * gesture goes to the drawn surface, and what comes back is read off the Gallery -- the data model
 * and the placeholder chips off the panes a developer reads, the action log off the object those
 * panes draw from. Nothing here reaches into the renderer to arrange a result.
 *
 * They are separate from [GalleryAppTest], which is about the Gallery's chrome. If one of these
 * fails, the renderer is wrong; if one of those fails, the tool around it is.
 */
@OptIn(ExperimentalTestApi::class)
class GalleryIntegrationTest {
    // ---- 1. Static Rendering ----------------------------------------------------------------

    /** "Opening 'Simple Text' renders correctly." */
    @Test
    fun static_rendering_opening_simple_text_draws_it() = runComposeUiTest {
        val state = GalleryState(examples = listOf(simpleText))
        setContent { Gallery(state) }
        onNodeWithTag(GalleryTags.ADVANCE_ALL).performClick()

        // The Markdown heading rendered as text rather than carried through with its marker.
        onNodeWithText("Hello, Minimal Catalog!").assertIsDisplayed()
        // And drawn whole: the corpus's own claim is that this needs no placeholder at all.
        assertEquals(emptyList(), placeholderChipsDrawn(), "nothing should be missing")
    }

    // ---- 2. Layout Integrity ----------------------------------------------------------------

    /**
     * "'Row Layout' places elements correctly."
     *
     * A position assertion rather than a presence one. Both texts are on screen even if the `Row`
     * were rendered as a `Column`, which is the failure this is here to catch: the two have to sit
     * side by side, in the order the payload lists them, with the gap `justify: spaceBetween` asks
     * for.
     */
    @Test
    fun layout_integrity_row_layout_puts_its_children_side_by_side() = runComposeUiTest {
        setContent { Gallery(GalleryState(examples = listOf(rowLayout))) }
        onNodeWithTag(GalleryTags.ADVANCE_ALL).performClick()

        val left = onNodeWithText("Left Content").fetchSemanticsNode().boundsInRoot
        val right = onNodeWithText("Right Content").fetchSemanticsNode().boundsInRoot

        // The main axis: a positive gap says the left text ends before the right one begins, in
        // that order, with room between them.
        assertTrue(
            right.left - left.right > 0f,
            "`spaceBetween` should leave a gap, in order: left=$left right=$right",
        )
        // The cross axis, and centres rather than mere overlap. Overlap cannot fail on its own --
        // a row is as tall as its tallest child, so the shorter of two texts lies inside the
        // taller's bounds under `top` and `bottom` alignment just as much as under `center`, and
        // the only layout that separates them is the `Column` the assertion above already
        // catches. Centres differ by half the line-height difference unless `align: center` was
        // honoured, so this one fails on its own for the reason it names.
        assertTrue(
            abs(left.center.y - right.center.y) < 1f,
            "`align: center` should centre both: left=$left right=$right",
        )
    }

    // ---- 3. Two-Way Binding -----------------------------------------------------------------

    /**
     * "Typing in a TextField updates both the UI and the Data Model viewer simultaneously."
     *
     * Both halves are asserted, and the second is the one that matters: a field that kept the
     * keystroke to itself would look identical on screen. The data model pane is read as the
     * Gallery draws it rather than through [GalleryState.dataModelJson], so what is checked is
     * what a developer would actually see.
     */
    @Test
    fun two_way_binding_a_keystroke_reaches_the_ui_and_the_data_model_pane() = runComposeUiTest {
        setContent { Gallery(GalleryState(examples = listOf(loginForm))) }
        onNodeWithTag(GalleryTags.ADVANCE_ALL).performClick()

        val fields = onAllNodes(hasSetTextAction())
        fields.assertCountEquals(2)
        assertTrue(
            "username" !in onNodeWithTag(GalleryTags.DATA_MODEL).textShown(),
            "the data model starts without the path this field writes",
        )

        fields[0].performTextReplacement("alice")

        fields[0].assertEditableTextEquals("alice")
        val pane = onNodeWithTag(GalleryTags.DATA_MODEL).textShown()
        assertTrue("\"username\"" in pane, "the pane should show the path that was written: $pane")
        assertTrue("\"alice\"" in pane, "the pane should show what was typed: $pane")
    }

    // ---- 4. Reactive Logic ------------------------------------------------------------------

    /**
     * "Changes in one component dynamically update dependent components."
     *
     * `30_live-invitation-builder.json` is the corpus's own case for this: an editor column of
     * inputs beside a preview card whose `Text`s bind to the same paths. Nothing sends the preview
     * an update -- it re-reads the data model the field wrote.
     */
    @Test
    fun reactive_logic_a_field_updates_a_component_that_was_never_told() = runComposeUiTest {
        setContent { Gallery(GalleryState(examples = listOf(invitationBuilder))) }
        onNodeWithTag(GalleryTags.ADVANCE_ALL).performClick()

        // Two `Text`s and one `TextField` would answer to the typed string if the preview were
        // already showing it, so the starting state is pinned first.
        onAllNodesWithText(PARTY_NAME).assertCountEquals(0)

        // The first field of the editor column is `event_name_input`, bound to `/event/name`;
        // `invite_event_name` in the preview card binds to the same path. Pinned by its label
        // rather than assumed: the count below would also read as two if index 0 were the next
        // field, `guest_input`, whose own preview `Text` binds `/event/guest` -- so without this
        // the test proves a field reached a `Text`, not that it reached *that* one.
        onAllNodes(hasSetTextAction())[0].assertTextContains("Event Name")
        onAllNodes(hasSetTextAction())[0].performTextReplacement(PARTY_NAME)

        // Two now: the field itself, and the preview `Text` that was never sent anything.
        onAllNodesWithText(PARTY_NAME).assertCountEquals(2)
    }

    // ---- 5. Action Context Scoping ----------------------------------------------------------

    /**
     * "Actions emitted from nested templates (like Lists) contain correctly resolved data scopes."
     *
     * `00_incremental.json` templates a `Column` over `/restaurants` and, in its last message,
     * gives each instance a `Button` whose action context binds the *relative* path `title`. Four
     * identical buttons are on screen; the third has to say the third restaurant's name.
     *
     * The third rather than the first on purpose: a renderer that resolved every instance against
     * the root scope, or against the first element, would pass a test that only ever clicked the
     * first button.
     */
    @Test
    fun action_context_scoping_a_templated_button_carries_its_own_row() = runComposeUiTest {
        val state = GalleryState(examples = listOf(incremental))
        setContent { Gallery(state) }
        onNodeWithTag(GalleryTags.ADVANCE_ALL).performClick()

        val buttons = onAllNodesWithText("Book now")
        // One per element of `/restaurants`, which the fifth message extended to four.
        buttons.assertCountEquals(4)

        buttons[2].performScrollTo().performClick()

        val entry = state.actionLog.single()
        val action = assertNotNull(entry.message as? ActionMessage, "the log should hold an action")
        assertEquals("book_now", action.name)
        assertEquals("rc_button", action.sourceComponentId)
        assertEquals(
            JsonPrimitive("Pizzeria Roma"),
            action.context["restaurantName"],
            "the context should resolve against the clicked instance's element, not the root",
        )
    }

    // ---- The corpus -------------------------------------------------------------------------

    /**
     * Every one of the forty-three examples loads and draws through the Gallery.
     *
     * `ExampleRenderTest` already draws the corpus straight from an
     * [dev.ynagai.a2ui.compose.A2uiRenderer], with the messages applied before anything is composed.
     * This is the same claim made the way a person makes it: chosen from the Gallery's own sample
     * list, in one long-lived composition, stepped one message at a time into the preview pane.
     *
     * The difference is not cosmetic. `applyAll` before the first frame and N separate `apply`s
     * afterwards are different code, and so is a surface *replacing* one already on screen -- which
     * is what selecting the next sample does forty-two times here. That last path is the one that
     * segfaulted Kotlin/Native until `CardRenderer` stopped being built on a Material 3 `Surface`;
     * this test is what walks it for the whole corpus rather than for the one pair
     * `CardScrollSwapTest` pins. Named rather than linked: that class lives in `a2ui-material3`'s
     * own test source set, which is not on this module's compile classpath, so the reference
     * would not resolve.
     */
    @Test
    fun every_example_loads_one_message_at_a_time_and_draws_whole() = runComposeUiTest {
        val state = GalleryState(examples = EXAMPLES)
        setContent { Gallery(state) }

        for (example in EXAMPLES) {
            state.select(example)
            waitForIdle()
            while (state.canAdvance) {
                state.advance()
                waitForIdle()
            }
            assertNotNull(state.surfaceId, "${example.file}: should leave a renderable surface")
            // Read off the pane rather than off the renderer. Both assertions above this one are
            // pure model state -- true with no composition at all -- so without these two the loop
            // asserts that 43 payloads *decoded*, and would stay green over a blank preview.
            assertTrue(
                onNodeWithTag(GalleryTags.PREVIEW).onChildren().fetchSemanticsNodes().isNotEmpty(),
                "${example.file}: the preview should have drawn something",
            )
            assertEquals(
                emptyList(),
                placeholderChipsDrawn(),
                "${example.file}: the registry covers this example, so it should draw whole",
            )
        }
    }

    /**
     * The placeholder chips the preview actually drew, by the text each one carries.
     *
     * Read off the drawn tree because that is the only place the whole answer exists. Deriving it
     * from the surface model instead can only ever reconstruct [A2uiPlaceholderReason.UnknownType]
     * -- and even that vacuously, since [DrawableExamplesTest] already pins every example's
     * component types as a subset of the registry's, so a model-derived list is `emptyList()` for
     * this corpus whatever the renderer does. The other five reasons leave no trace in the model at
     * all: `MissingComponent` names an id the model does not hold, and `Cycle`, `TooDeep`,
     * `BudgetExceeded` and `TooManyChildren` are decisions the *descent* makes about components the
     * model holds happily.
     *
     * Collecting from the [dev.ynagai.a2ui.compose.A2uiPlaceholder] callback, as `ExampleRenderTest`
     * does, is not available here: [GalleryApp] hard-wires its own chip and takes no parameter to
     * override it. Matching what that chip draws is the same question asked of the same surface.
     *
     * Scoped under [GalleryTags.PREVIEW] on purpose -- the message stream beside it renders every
     * payload as raw JSON, so an unscoped text match would answer about the agent's own strings.
     *
     * The strings are `A2uiPlaceholderReason.describe`'s in [GalleryApp]; a rename there should
     * fail here, which is the point of asserting on what a developer actually reads.
     */
    private fun SemanticsNodeInteractionsProvider.placeholderChipsDrawn(): List<String> =
        PLACEHOLDER_CHIPS
            .flatMap { marker ->
                onAllNodes(
                    hasText(marker, substring = true) and
                        hasAnyAncestor(hasTestTag(GalleryTags.PREVIEW)),
                ).fetchSemanticsNodes()
            }
            .mapNotNull { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
            }

    /** A node's rendered text, joined. The data model pane draws its whole JSON as one node. */
    private fun SemanticsNodeInteraction.textShown(): String =
        fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .joinToString("") { it.text }

    private fun SemanticsNodeInteraction.assertEditableTextEquals(expected: String) {
        val actual = fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.EditableText)
            ?.text
        assertEquals(expected, actual, "the field should show what was typed")
    }

    /**
     * The Gallery at a size that keeps the three columns.
     *
     * Same reasoning as [GalleryAppTest]'s helper: `requiredSize` because `size` cannot grow the
     * box past the harness root, and values inside 1024x768 so the box is honoured whole.
     */
    @Composable
    private fun Gallery(state: GalleryState) {
        Box(Modifier.requiredSize(1000.dp, 760.dp)) {
            GalleryApp(state = state)
        }
    }

    private companion object {
        val simpleText = EXAMPLES.single { it.file == "00_simple-text.json" }
        val rowLayout = EXAMPLES.single { it.file == "00_row-layout.json" }
        val loginForm = EXAMPLES.single { it.file == "00_simple-login-form.json" }
        val incremental = EXAMPLES.single { it.file == "00_incremental.json" }
        val invitationBuilder = EXAMPLES.single { it.file == "30_live-invitation-builder.json" }

        /** A string no example contains, so its appearance can only be what was typed. */
        const val PARTY_NAME = "Ynagai's Housewarming"

        /**
         * One marker per [A2uiPlaceholderReason], from the chip `GalleryApp` draws for it.
         *
         * All six, not the one a model-derived check could see. Each is the narrowest substring
         * that is still unique to its chip: `BudgetExceeded` and `TooManyChildren` interpolate a
         * number *before* the words that identify them, so the phrase has to start after it.
         */
        val PLACEHOLDER_CHIPS = listOf(
            "not yet defined: ",
            "no renderer for ",
            "cycle at ",
            "too deep at ",
            "-instance budget at ",
            " children dropped from ",
        )
    }
}
