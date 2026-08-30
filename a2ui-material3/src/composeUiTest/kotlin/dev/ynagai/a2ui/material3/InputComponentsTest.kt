package dev.ynagai.a2ui.material3

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ynagai.a2ui.compose.A2uiRenderer
import dev.ynagai.a2ui.compose.A2uiSurface
import dev.ynagai.a2ui.compose.BasicCatalog
import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.AgentToRendererMessage
import dev.ynagai.a2ui.core.surface.JsonPointer
import dev.ynagai.a2ui.core.surface.resolve
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The four input components, and what `checks` does to the two that were already here.
 *
 * Each claim is about the round trip rather than the picture: a gesture goes in, the data model
 * comes out, and a second component bound to the same path shows what landed. That second
 * component is what makes these tests hard to pass by accident -- an input that kept its answer to
 * itself would still look right on screen, and only the echo says the write happened.
 */
@OptIn(ExperimentalTestApi::class)
class InputComponentsTest {
    // ---- CheckBox -------------------------------------------------------------------------

    @Test
    fun a_checkbox_toggles_the_boolean_it_is_bound_to() = runComposeUiTest {
        val renderer = rendererFor(CHECKBOX)
        setContent { Surface(renderer) }
        onNodeWithText("false").assertIsDisplayed()
        // The label, not the box: the whole row is the target, which is the point of drawing it
        // with `toggleable` rather than as two independent things.
        onNodeWithText("Subscribe").performClick()
        assertEquals(JsonPrimitive(true), renderer.read("/subscribe"))
        onNodeWithText("true").assertIsDisplayed()
        onNodeWithText("Subscribe").performClick()
        assertEquals(JsonPrimitive(false), renderer.read("/subscribe"))
    }

    @Test
    fun a_checkbox_reads_its_state_out_of_the_model_rather_than_holding_it() = runComposeUiTest {
        val renderer = rendererFor(CHECKBOX)
        setContent { Surface(renderer) }
        onNodeWithText("Subscribe").assertIsOff()
        // Written from outside, as an `updateDataModel` from the agent would.
        renderer.write(SURFACE, JsonPointer.parse("/subscribe"), JsonPrimitive(true))
        onNodeWithText("Subscribe").assertIsOn()
    }

    @Test
    fun a_checkbox_with_nowhere_to_write_is_disabled_rather_than_springing_back() = runComposeUiTest {
        setContent { Surface(rendererFor(CHECKBOX)) }
        onNodeWithText("Literal").assertIsNotEnabled()
    }

    // ---- ChoicePicker ---------------------------------------------------------------------

    @Test
    fun an_exclusive_picker_replaces_the_selection_and_keeps_it_a_list() = runComposeUiTest {
        val renderer = rendererFor(PICKER)
        setContent { Surface(renderer) }
        onNodeWithText("Email").performClick()
        // A one-element array, not a bare string: the catalog types `value` as a list under both
        // variants, and writing a string would hand the agent back a differently shaped model.
        assertEquals(JsonArray(listOf(JsonPrimitive("email"))), renderer.read("/preference"))
        onNodeWithText("Phone").performClick()
        assertEquals(JsonArray(listOf(JsonPrimitive("phone"))), renderer.read("/preference"))
        onNodeWithText("Phone").assertIsSelected()
        // Tapping the selected one again clears it -- the only way back to no answer.
        onNodeWithText("Phone").performClick()
        assertEquals(JsonArray(emptyList()), renderer.read("/preference"))
    }

    @Test
    fun a_multiple_picker_toggles_within_the_selection() = runComposeUiTest {
        val renderer = rendererFor(PICKER)
        setContent { Surface(renderer) }
        onNodeWithText("Jazz").performClick()
        onNodeWithText("Rock").performClick()
        assertEquals(
            JsonArray(listOf(JsonPrimitive("jazz"), JsonPrimitive("rock"))),
            renderer.read("/genres"),
        )
        onNodeWithText("Jazz").performClick()
        assertEquals(JsonArray(listOf(JsonPrimitive("rock"))), renderer.read("/genres"))
    }

    @Test
    fun a_filter_hides_an_option_without_deselecting_it() = runComposeUiTest {
        // A focused field blinks its cursor, and that animation never ends -- see the obscured
        // field's test in [Material3ComponentsTest] for the same dance.
        mainClock.autoAdvance = false
        val renderer = rendererFor(PICKER)
        setContent { Surface(renderer) }
        onNodeWithText("Jazz").performClick()
        assertEquals(JsonArray(listOf(JsonPrimitive("jazz"))), renderer.read("/genres"))
        // The filter box is the one field on this surface that accepts text.
        onAllNodes(hasSetTextAction()).assertCountEquals(1)
        onNode(hasSetTextAction()).performTextReplacement("roc")
        mainClock.advanceTimeByFrame()
        onNodeWithText("Rock").assertIsDisplayed()
        // "Jazz" is filtered out of the list even though it is the selected one.
        onAllNodes(hasTextExactly("Jazz")).assertCountEquals(0)
        // Still selected, and still in the model: the filter is this renderer's own state and the
        // agent never hears about it, so hiding an option must not answer for the user.
        assertEquals(JsonArray(listOf(JsonPrimitive("jazz"))), renderer.read("/genres"))
    }

    @Test
    fun a_chips_picker_selects_the_same_way_the_rows_do() = runComposeUiTest {
        // `displayStyle` chooses the control and `variant` chooses the arithmetic, and the two are
        // independent -- so the chips form has to toggle exactly as the checkbox form does. Drawn
        // as chips it is a different composable entirely, which is what makes this worth its own
        // test rather than a variant of the one above.
        val renderer = rendererFor(CHIPS)
        setContent { Surface(renderer) }
        onNodeWithText("Jazz").performClick()
        onNodeWithText("Rock").performClick()
        assertEquals(
            JsonArray(listOf(JsonPrimitive("jazz"), JsonPrimitive("rock"))),
            renderer.read("/genres"),
        )
        onNodeWithText("Jazz").assertIsSelected()
        onNodeWithText("Jazz").performClick()
        assertEquals(JsonArray(listOf(JsonPrimitive("rock"))), renderer.read("/genres"))
    }

    @Test
    fun a_bound_option_label_follows_a_later_write() = runComposeUiTest {
        // The catalog types `options[].label` as a `DynamicString`, so it may be a data binding --
        // and a renderer that cached the option list against the *unresolved* property would show
        // whatever the model said when the picker was first drawn and never change again. The
        // property is identical JSON before and after the write, so only a value-derived read
        // notices.
        val renderer = rendererFor(BOUND_LABEL)
        setContent { Surface(renderer) }
        onNodeWithText("Ada").assertIsDisplayed()
        renderer.write(SURFACE, JsonPointer.parse("/who"), JsonPrimitive("Grace"))
        onNodeWithText("Grace").assertIsDisplayed()
        // And the option still selects under its new name, by the value it always had.
        onNodeWithText("Grace").performClick()
        assertEquals(JsonArray(listOf(JsonPrimitive("a"))), renderer.read("/picked"))
    }

    // ---- Slider ---------------------------------------------------------------------------

    @Test
    fun a_slider_writes_the_value_it_is_moved_to() = runComposeUiTest {
        val renderer = rendererFor(SLIDER)
        setContent { Surface(renderer) }
        val slider = onAllNodes(hasProgressRange()).onFirst()
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(75f) }
        assertEquals(75.0, renderer.read("/volume")?.let { (it as JsonPrimitive).content.toDouble() })
        // The echo: a `Text` bound to the same path, which only says 75 if the write landed.
        onNodeWithText("75", substring = true).assertIsDisplayed()
    }

    @Test
    fun a_slider_reports_the_range_the_catalog_gave_it() = runComposeUiTest {
        setContent { Surface(rendererFor(SLIDER)) }
        val info = onAllNodes(hasProgressRange()).onFirst()
            .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(0f, info.range.start)
        assertEquals(100f, info.range.endInclusive)
        // The catalog's four divisions are Compose's three interior stops. Off by one either way
        // and the slider snaps to the wrong numbers -- which nothing on screen would show.
        assertEquals(3, info.steps)
    }

    @Test
    fun a_slider_with_no_usable_range_draws_nothing_rather_than_raising() = runComposeUiTest {
        // Compose refuses an empty range from inside the composition, which would take the whole
        // surface down. The sibling text is the assertion that the rest of the surface survived.
        setContent { Surface(rendererFor(BAD_SLIDER)) }
        onNodeWithText("after").assertIsDisplayed()
        onAllNodes(hasProgressRange()).assertCountEquals(0)
    }

    @Test
    fun a_slider_bound_to_a_non_finite_number_rests_at_its_minimum_rather_than_crashing() = runComposeUiTest {
        // `"NaN"` is a legal JSON string, and `number()` reads a primitive's text -- so this is
        // what a bound field holds the moment a user types those three letters into it. NaN
        // survives `coerceIn` untouched -- both of its comparisons are false -- and Material then
        // raises "current must not be NaN" building the slider's own `ProgressBarRangeInfo`,
        // which takes the surface down. Without the guard this test fails with exactly that.
        //
        // The range is still readable here, so the slider is still drawn and still usable -- only
        // the unreadable half degrades, and the thumb rests at `min`. The sibling text is the
        // assertion that the surface survived at all.
        setContent { Surface(rendererFor(NAN_SLIDER)) }
        onNodeWithText("after").assertIsDisplayed()
        val info = onAllNodes(hasProgressRange()).onFirst()
            .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(0f, info.current)
        assertEquals(0f, info.range.start)
        assertEquals(100f, info.range.endInclusive)
    }

    @Test
    fun a_slider_with_more_divisions_than_material_can_allocate_still_draws() = runComposeUiTest {
        // Compose's `steps` is an array size: Material builds `FloatArray(steps + 2)`. Passing the
        // agent's number straight through asks for `Int.MAX_VALUE + 2`, which wraps negative and
        // raises `NegativeArraySizeException` during composition. Clamped, the slider still draws
        // and still reports a usable range.
        setContent { Surface(rendererFor(HUGE_STEPS_SLIDER)) }
        onNodeWithText("after").assertIsDisplayed()
        val info = onAllNodes(hasProgressRange()).onFirst()
            .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(0f, info.range.start)
        assertEquals(100f, info.range.endInclusive)
        assertTrue(info.steps in 0..1000, "divisions should be bounded, was ${info.steps}")
    }

    @Test
    fun a_slider_beside_a_text_does_not_take_the_whole_row() = runComposeUiTest {
        // A slider's track has no intrinsic width, so it fills whatever it is offered -- and inside
        // a `Row` that is the parent's width, not a share of it. The label beside it measured at
        // zero. Drawn narrow on purpose: at the harness's own width the starvation does not
        // reproduce, so the test would pass with or without the fix.
        setContent { Surface(rendererFor(SLIDER_IN_ROW), PHONE_WIDTH) }
        val label = onNodeWithText("after").fetchSemanticsNode().boundsInRoot
        val slider = onAllNodes(hasProgressRange()).onFirst().fetchSemanticsNode().boundsInRoot
        // A magnitude, not "greater than zero": without the weight the container grants, this text
        // measures at exactly 0 x 0, and a hairline of a label would be the same bug. Thirty
        // pixels is comfortably under the word's own width and far above the starved case.
        assertTrue(label.width > 30f, "the text beside the slider should hold its word: $label")
        // And it is beside the slider rather than pushed off the end of the surface.
        assertTrue(
            label.left >= slider.right && label.right <= PHONE_WIDTH.value,
            "the text should sit after the slider and inside the surface: $label after $slider",
        )
    }

    // ---- DateTimeInput --------------------------------------------------------------------

    @Test
    fun a_datetime_field_shows_the_iso_string_and_refuses_typing() = runComposeUiTest {
        // Not covered here: driving Material's own date and time pickers, which open in a dialog
        // this harness could not reach. What the two of them then produce is [Iso8601Test]'s
        // `combine`, `date` and `time`; the plumbing between the dialog and that call is not
        // exercised by any test in this suite.
        setContent { Surface(rendererFor(DATE_TIME)) }
        onNodeWithText("2026-08-30").assertIsDisplayed()
        // Read-only rather than disabled, which `assertIsNotEnabled` cannot tell apart -- the
        // absence of a set-text action is what actually says a field will not accept typing.
        onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test
    fun a_datetime_field_out_of_the_pickers_default_year_range_still_opens() = runComposeUiTest {
        // Material's default `yearRange` is 1900..2100, and a date of birth in 1890 falls outside
        // it. The Android documentation says `DatePickerState` raises `IllegalArgumentException`
        // for an initial selection outside its range -- which would take the whole surface down
        // from inside the composition. **Compose Multiplatform 1.9.0 does not raise**, as this
        // test shows: it opens the picker on 1890 and the surface is still there. The test is kept
        // as the guard for the version that starts enforcing it, and as the record of what this
        // one does.
        //
        // What the widened range is actually for is reachability: a picker cannot scroll outside
        // its `yearRange`, so without it the user is shown a field reading 1890 above a picker
        // that cannot reach 1890. That claim is [Iso8601Test]'s `yearsSpanning`, because checking
        // it here would mean driving Material's own year grid.
        setContent { Surface(rendererFor(FAR_DATE)) }
        onNodeWithText("1890-07-04").assertIsDisplayed()
        // The state is only built when the dialog opens, so the button has to be pressed: a test
        // that merely drew the field would never construct it.
        onNodeWithContentDescription("Born").performClick()
        onNodeWithText("after").assertIsDisplayed()
    }

    @Test
    fun a_datetime_field_names_its_button_after_the_field() = runComposeUiTest {
        // The only name for an icon-only button that came out of the payload. Without it a screen
        // reader announces nothing at all for the one control the field has.
        setContent { Surface(rendererFor(DATE_TIME)) }
        onNodeWithContentDescription("When").assertExists()
    }

    @Test
    fun a_date_picked_in_the_dialog_reaches_the_data_model_as_iso_8601() = runComposeUiTest {
        // The whole round trip: open the picker, choose a different day, confirm, and read the
        // model. This is what makes `Iso8601`'s arithmetic more than a unit test -- it is the only
        // check that the day the grid reports and the string the field writes are the same day.
        val renderer = rendererFor(DATE_TIME)
        setContent { Surface(renderer) }
        onNodeWithContentDescription("When").performClick()
        // The fifteenth day cell. **By position, not by text**: Material's picker draws each cell's
        // semantics in the machine's own locale -- on this one they read "2026年8月15日土曜日" --
        // so a test that matched the day number would pass here and fail wherever CI runs. The day
        // cells are the dialog's only selectable nodes, and August 2026 begins on the 1st, so the
        // fifteenth of them is the 15th.
        onAllNodes(isSelectable())[DAY_15].performClick()
        onNodeWithText("OK").performClick()
        assertEquals(JsonPrimitive("2026-08-15"), renderer.read("/when"))
    }

    @Test
    fun a_dialog_dismissed_leaves_the_model_as_it_found_it() = runComposeUiTest {
        val renderer = rendererFor(DATE_TIME)
        setContent { Surface(renderer) }
        onNodeWithContentDescription("When").performClick()
        onAllNodes(isSelectable())[DAY_15].performClick()
        // Choosing inside the dialog is not answering: only the confirm button writes.
        onNodeWithText("Cancel").performClick()
        assertEquals(JsonPrimitive("2026-08-30"), renderer.read("/when"))
    }

    @Test
    fun a_datetime_field_with_nowhere_to_write_is_disabled() = runComposeUiTest {
        setContent { Surface(rendererFor(DATE_TIME)) }
        onNodeWithText("Literal date").assertIsNotEnabled()
    }

    // ---- the words this module supplies itself ----------------------------------------------

    @Test
    fun a_picker_dialog_names_its_two_buttons() = runComposeUiTest {
        // A dialog whose only two controls announce nothing cannot be operated without sight, and
        // the catalog gives this module no text to borrow for them -- so they are the one place it
        // supplies its own, from [LocalA2uiStrings].
        setContent { Surface(rendererFor(DATE_TIME)) }
        onNodeWithContentDescription("When").performClick()
        onNodeWithText("OK").assertIsDisplayed()
        onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun a_host_replaces_the_words_this_module_supplies() = runComposeUiTest {
        // The point of the composition local: the agent chose the surface's language, and a
        // renderer that hardcoded English would be answering a question nobody asked it.
        setContent {
            CompositionLocalProvider(
                LocalA2uiStrings provides A2uiStrings(confirm = "決定", cancel = "取消", filter = "絞り込み"),
            ) {
                Surface(rendererFor(DATE_TIME))
            }
        }
        onNodeWithContentDescription("When").performClick()
        onNodeWithText("決定").assertIsDisplayed()
        onNodeWithText("取消").assertIsDisplayed()
        onAllNodes(hasTextExactly("OK")).assertCountEquals(0)
    }

    @Test
    fun a_filter_field_is_named_even_once_it_holds_a_query() = runComposeUiTest {
        // A `placeholder` would vanish the moment the field had a value, leaving it unnamed for
        // exactly as long as it was in use. The label persists.
        mainClock.autoAdvance = false
        setContent { Surface(rendererFor(PICKER)) }
        onNodeWithText("Filter").assertIsDisplayed()
        onNode(hasSetTextAction()).performTextReplacement("roc")
        mainClock.advanceTimeByFrame()
        onNodeWithText("Filter").assertIsDisplayed()
    }

    // ---- checks ---------------------------------------------------------------------------

    @Test
    fun a_failing_check_disables_the_button_and_a_passing_one_restores_it() = runComposeUiTest {
        val renderer = rendererFor(VALIDATED)
        setContent { Surface(renderer) }
        // `/email` starts empty, so `required` fails and the protocol's rule applies: "If any
        // check fails, the button is automatically disabled."
        onNodeWithText("Sign in").assertIsNotEnabled()
        renderer.write(SURFACE, JsonPointer.parse("/email"), JsonPrimitive("ada@example.com"))
        onNodeWithText("Sign in").assertIsEnabled()
    }

    @Test
    fun a_failing_check_captions_the_field_it_is_on() = runComposeUiTest {
        val renderer = rendererFor(VALIDATED)
        setContent { Surface(renderer) }
        onNodeWithText("Email is required").assertIsDisplayed()
        renderer.write(SURFACE, JsonPointer.parse("/email"), JsonPrimitive("ada@example.com"))
        onAllNodes(hasTextExactly("Email is required")).assertCountEquals(0)
    }

    @Test
    fun a_warning_says_something_without_taking_the_action_away() = runComposeUiTest {
        // The severity distinction, which is the whole reason `hasError` is not `isNotEmpty`.
        setContent { Surface(rendererFor(WARNED)) }
        onNodeWithText("Looks unusual").assertIsDisplayed()
        onNodeWithText("Submit").assertIsEnabled()
    }

    // ---- harness --------------------------------------------------------------------------

    @Composable
    private fun Surface(renderer: A2uiRenderer) {
        MaterialTheme {
            A2uiSurface(renderer = renderer, surfaceId = SURFACE, registry = Material3Components.Basic)
        }
    }

    /** The surface at a chosen width, for the claims that only hold on a small screen. */
    @Composable
    private fun Surface(renderer: A2uiRenderer, width: Dp) {
        Box(Modifier.size(width, SURFACE_HEIGHT)) { Surface(renderer) }
    }

    private fun A2uiRenderer.read(path: String): JsonElement? =
        state.surfaces.getValue(SURFACE).dataModel.resolve(JsonPointer.parse(path))

    private fun rendererFor(components: String): A2uiRenderer =
        A2uiRenderer(clock = { CLOCK }).also { renderer ->
            renderer.applyAll(
                listOf(
                    """{"version":"v1.0","createSurface":{"surfaceId":"$SURFACE","catalogId":"CATALOG_ID"}}""",
                    """{"version":"v1.0","updateDataModel":{"surfaceId":"$SURFACE","value":$DATA}}""",
                    """{"version":"v1.0","updateComponents":{"surfaceId":"$SURFACE","components":$components}}""",
                ).map {
                    A2uiJson.strict.decodeFromString(
                        AgentToRendererMessage.serializer(),
                        it.replace("CATALOG_ID", BasicCatalog.id),
                    )
                },
            )
        }

    private companion object {
        const val SURFACE = "s"
        const val CLOCK = "2026-08-30T00:00:00Z"

        /** The index of August 2026's fifteenth day among the picker's selectable cells. */
        const val DAY_15 = 14

        /** A phone, which is the width a filling child starves its siblings at. */
        val PHONE_WIDTH = 320.dp

        /** Tall enough that nothing under test is height-constrained. */
        val SURFACE_HEIGHT = 800.dp

        val DATA = """
            {
              "subscribe": false,
              "preference": [],
              "genres": [],
              "volume": 20,
              "when": "2026-08-30",
              "who": "Ada",
              "born": "1890-07-04",
              "picked": [],
              "email": "",
              "amount": 1,
              "unusual": {"valid": false, "severity": "warning", "message": "Looks unusual"},
              "notANumber": "NaN"
            }
        """.trimIndent()

        /**
         * A slider whose bound value is the string `"NaN"`, which is ordinary strict JSON.
         *
         * `A2uiComponentScope.number` reads a primitive's text, so this resolves to `Double.NaN` --
         * the same value a user gets by typing "NaN" into a `TextField` bound to the same pointer.
         */
        val NAN_SLIDER = """[
          {"id":"root","component":"Column","children":["vol","after"]},
          {"id":"vol","component":"Slider","label":"Volume","min":0,"max":100,
           "value":{"path":"/notANumber"}},
          {"id":"after","component":"Text","text":"after"}
        ]"""

        /** `steps` past what Material can allocate a tick array for -- see `MAX_DIVISIONS`. */
        val HUGE_STEPS_SLIDER = """[
          {"id":"root","component":"Column","children":["vol","after"]},
          {"id":"vol","component":"Slider","label":"Volume","min":0,"max":100,"steps":2147483647,
           "value":{"path":"/volume"}},
          {"id":"after","component":"Text","text":"after"}
        ]"""

        val CHECKBOX = """[
          {"id":"root","component":"Column","children":["box","echo","literal"]},
          {"id":"box","component":"CheckBox","label":"Subscribe","value":{"path":"/subscribe"}},
          {"id":"echo","component":"Text","text":{"call":"formatString","args":{"value":"${'$'}{/subscribe}"}}},
          {"id":"literal","component":"CheckBox","label":"Literal","value":false}
        ]"""

        val PICKER = """[
          {"id":"root","component":"Column","children":["one","many"]},
          {"id":"one","component":"ChoicePicker","variant":"mutuallyExclusive",
           "value":{"path":"/preference"},
           "options":[{"label":"Email","value":"email"},{"label":"Phone","value":"phone"}]},
          {"id":"many","component":"ChoicePicker","variant":"multipleSelection","filterable":true,
           "value":{"path":"/genres"},
           "options":[{"label":"Jazz","value":"jazz"},{"label":"Rock","value":"rock"}]}
        ]"""

        val SLIDER = """[
          {"id":"root","component":"Column","children":["vol","echo"]},
          {"id":"vol","component":"Slider","label":"Volume","min":0,"max":100,"steps":4,
           "value":{"path":"/volume"}},
          {"id":"echo","component":"Text","text":{"call":"formatString","args":{"value":"${'$'}{/volume}"}}}
        ]"""

        val BAD_SLIDER = """[
          {"id":"root","component":"Column","children":["vol","after"]},
          {"id":"vol","component":"Slider","label":"Volume","min":10,"max":10,"value":{"path":"/volume"}},
          {"id":"after","component":"Text","text":"after"}
        ]"""

        val SLIDER_IN_ROW = """[
          {"id":"root","component":"Row","children":["vol","after"],"align":"center"},
          {"id":"vol","component":"Slider","min":0,"max":100,"value":{"path":"/volume"}},
          {"id":"after","component":"Text","text":"after"}
        ]"""

        val BOUND_LABEL = """[
          {"id":"root","component":"Column","children":["picker"]},
          {"id":"picker","component":"ChoicePicker","variant":"mutuallyExclusive",
           "value":{"path":"/picked"},
           "options":[{"label":{"path":"/who"},"value":"a"}]}
        ]"""

        val CHIPS = """[
          {"id":"root","component":"Column","children":["many"]},
          {"id":"many","component":"ChoicePicker","variant":"multipleSelection","displayStyle":"chips",
           "value":{"path":"/genres"},
           "options":[{"label":"Jazz","value":"jazz"},{"label":"Rock","value":"rock"}]}
        ]"""

        val DATE_TIME = """[
          {"id":"root","component":"Column","children":["day","literal"]},
          {"id":"day","component":"DateTimeInput","label":"When","enableDate":true,
           "value":{"path":"/when"}},
          {"id":"literal","component":"DateTimeInput","label":"Literal date","enableDate":true,
           "value":"2026-01-01"}
        ]"""

        val FAR_DATE = """[
          {"id":"root","component":"Column","children":["born","after"]},
          {"id":"born","component":"DateTimeInput","label":"Born","enableDate":true,
           "value":{"path":"/born"}},
          {"id":"after","component":"Text","text":"after"}
        ]"""

        val VALIDATED = """[
          {"id":"root","component":"Column","children":["email","submit"]},
          {"id":"email","component":"TextField","label":"Email","value":{"path":"/email"},
           "checks":[{"condition":{"call":"required","args":{"value":{"path":"/email"}}},
                      "message":"Email is required"}]},
          {"id":"submit","component":"Button","child":"submit_label","variant":"primary",
           "action":{"event":{"name":"signIn"}},
           "checks":[{"condition":{"call":"required","args":{"value":{"path":"/email"}}},
                      "message":"Fix the errors first"}]},
          {"id":"submit_label","component":"Text","text":"Sign in"}
        ]"""

        val WARNED = """[
          {"id":"root","component":"Column","children":["amount","submit"]},
          {"id":"amount","component":"TextField","label":"Amount","value":{"path":"/email"},
           "checks":[{"condition":{"path":"/unusual"}}]},
          {"id":"submit","component":"Button","child":"submit_label",
           "action":{"event":{"name":"submit"}},
           "checks":[{"condition":{"path":"/unusual"}}]},
          {"id":"submit_label","component":"Text","text":"Submit"}
        ]"""
    }
}

/** A node that reports a progress range -- what a `Slider` looks like to the semantics tree. */
private fun hasProgressRange() = androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(
    SemanticsProperties.ProgressBarRangeInfo,
)

/** Exact text, for asserting that a message is *gone* rather than that some node lacks it. */
private fun hasTextExactly(text: String) = androidx.compose.ui.test.SemanticsMatcher(
    "text is exactly '$text'",
) { node ->
    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
}
