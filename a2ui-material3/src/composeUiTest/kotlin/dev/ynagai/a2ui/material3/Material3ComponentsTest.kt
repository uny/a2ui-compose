package dev.ynagai.a2ui.material3

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ynagai.a2ui.compose.A2uiPlaceholder
import dev.ynagai.a2ui.compose.A2uiPlaceholderReason
import dev.ynagai.a2ui.compose.A2uiRenderer
import dev.ynagai.a2ui.compose.A2uiSurface
import dev.ynagai.a2ui.compose.BasicCatalog
import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.ActionMessage
import dev.ynagai.a2ui.core.protocol.AgentToRendererMessage
import dev.ynagai.a2ui.core.protocol.RendererToAgentMessage
import dev.ynagai.a2ui.core.surface.JsonPointer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The five components, drawn.
 *
 * Every claim here is one that only a composition can settle: whether a container puts its
 * children on screen, whether a tap reaches the agent with the data model resolved as of the tap,
 * and whether a keystroke lands in the data model and comes back out somewhere else. None of them
 * has a return value to inspect.
 */
@OptIn(ExperimentalTestApi::class)
class Material3ComponentsTest {
    @Test
    fun a_text_renders_its_markdown_rather_than_its_markers() = runComposeUiTest {
        setContent { Surface(TEXTS) }
        onNodeWithText("Hello, Minimal Catalog!").assertIsDisplayed()
        onNodeWithText("a caption").assertIsDisplayed()
    }

    @Test
    fun a_row_and_a_column_put_their_children_on_screen() = runComposeUiTest {
        setContent { Surface(LAYOUT) }
        // The weighted child too: a `weight` Compose refuses raises rather than degrading, so the
        // one assertion covers both "it was laid out" and "the weight was applied without raising".
        for (label in listOf("left", "right", "top", "bottom")) {
            onNodeWithText(label).assertIsDisplayed()
        }
    }

    @Test
    fun a_nested_row_does_not_eat_the_width_its_parent_was_spreading() = runComposeUiTest {
        // The specification's own `01_flight-status` shape: a `spaceBetween` row holding a row and
        // a text. A renderer that fills every row's width unconditionally puts the inner row across
        // the whole parent and leaves the date at zero width, pinned to the left.
        setContent { Surface(NESTED_ROWS) }
        val root = onRoot().fetchSemanticsNode().boundsInRoot
        val date = onNodeWithText("date").fetchSemanticsNode().boundsInRoot
        assertTrue(date.width > 0f, "the trailing text should have been given room")
        assertTrue(
            date.left > root.left + root.width / 2f,
            "the trailing text should sit in the right half: $date within $root",
        )
    }

    @Test
    fun a_container_stretches_its_children_to_itself_rather_than_to_its_parent() {
        // The catalog's default `align` is `stretch`, and no test used to exercise it: both layout
        // fixtures set `align` explicitly. Stretch is the children filling the container's own
        // cross axis, and a `fillMaxHeight` taken inside a `Row` measures against whatever the
        // *parent* offered -- so an un-annotated row swelled to the full height of its column and
        // every later sibling measured at zero. The trailing text is the whole assertion.
        runComposeUiTest {
            setContent { Surface(DEFAULT_ALIGN) }
            val row = onNodeWithText("aaa").fetchSemanticsNode().boundsInRoot
            val after = onNodeWithText("AFTER").fetchSemanticsNode().boundsInRoot
            assertTrue(after.height > 0f, "the text after the row should have been drawn: $after")
            assertTrue(
                after.top >= row.bottom,
                "the text after the row should sit below it: $after under $row",
            )
        }
    }

    @Test
    fun a_column_inside_a_row_leaves_room_for_what_follows_it() {
        // The mirror of the above on the other axis: a column's stretch children fill its width,
        // and unbounded that width is the whole row's.
        runComposeUiTest {
            setContent { Surface(COLUMN_IN_ROW) }
            val date = onNodeWithText("date").fetchSemanticsNode().boundsInRoot
            assertTrue(date.width > 0f, "the trailing text should have been given room: $date")
        }
    }

    @Test
    fun a_field_beside_a_button_does_not_take_the_whole_row() {
        // A `TextField` used to fill the width whatever its parent was, so the button next to it
        // measured at zero and drew nothing -- a submit button that is on screen and invisible.
        runComposeUiTest {
            setContent { Surface(FIELD_AND_BUTTON) }
            val go = onNodeWithText("GO").fetchSemanticsNode().boundsInRoot
            assertTrue(go.width > 0f, "the button beside the field should have been drawn: $go")
        }
    }

    @Test
    fun a_nested_row_that_asks_to_spread_is_granted_room_rather_than_taking_it() {
        // `a_nested_row_does_not_eat_the_width_its_parent_was_spreading` covers the `start` case.
        // This is the same shape with the inner row asking for `center`, which used to reach
        // `fillMaxWidth` and starve the sibling exactly as the unconditional rule had.
        runComposeUiTest {
            setContent { Surface(CENTERED_NESTED_ROW) }
            val root = onRoot().fetchSemanticsNode().boundsInRoot
            val date = onNodeWithText("date").fetchSemanticsNode().boundsInRoot
            assertTrue(date.width > 0f, "the trailing text should have been given room: $date")
            assertTrue(
                date.left > root.left + root.width / 2f,
                "the trailing text should sit in the right half: $date within $root",
            )
        }
    }

    @Test
    fun a_weight_too_large_for_a_float_is_read_as_absent() {
        // `1e39` is a finite `Double` and an infinite `Float`, so a guard that asked the `Double`
        // whether it was finite passed the one value it was written to refuse. Compose divides the
        // free space by the weight total, so the unweighted sibling measured at zero.
        runComposeUiTest {
            setContent { Surface(OVERFLOWING_WEIGHT) }
            val bbb = onNodeWithText("bbb").fetchSemanticsNode().boundsInRoot
            assertTrue(bbb.width > 0f, "the sibling of an over-large weight should be drawn: $bbb")
        }
    }

    @Test
    fun an_obscured_field_does_not_show_what_was_typed() {
        // No test used to set a `variant` at all, so the branch that hides a password could have
        // been deleted with the suite still green -- and the failure mode is a password drawn in
        // clear text.
        runComposeUiTest {
            mainClock.autoAdvance = false
            val renderer = rendererFor(OBSCURED_FIELD)
            setContent { Surface(renderer) }
            onNode(hasSetTextAction()).performTextReplacement("hunter2")
            mainClock.advanceTimeByFrame()
            // Both halves. The field still writes what was typed -- without this the assertion
            // below would also pass for a field that had simply stopped accepting input.
            runOnIdle {
                assertEquals(
                    JsonPrimitive("hunter2"),
                    (renderer.state.surfaces[SURFACE]?.dataModel as JsonObject)["typed"],
                )
            }
            // `EditableText` is what the field draws; `InputText` keeps the raw value for the IME
            // and for accessibility, which is Compose's own contract rather than this renderer's.
            // The assertion is therefore on the drawn text, and it is what the visual
            // transformation -- the branch nothing used to exercise -- produces.
            val drawn = onNode(hasSetTextAction()).fetchSemanticsNode()
                .config[SemanticsProperties.EditableText].text
            assertEquals("\u2022".repeat("hunter2".length), drawn, "the field should draw a mask")
        }
    }

    @Test
    fun a_button_whose_action_will_not_decode_still_draws_and_does_not_raise() {
        // The documented degradation, asserted rather than described: `action()` swallows a decode
        // failure so one malformed property costs its own handler rather than the surface. Without
        // this, dropping the `runCatching` passes every test and throws out of composition on the
        // first payload an agent gets wrong.
        val sent = mutableListOf<RendererToAgentMessage>()
        runComposeUiTest {
            setContent { Surface(rendererFor(MALFORMED_ACTION), onMessage = { sent += it }) }
            onNodeWithText("Send").assertIsDisplayed()
            onNodeWithText("Send").performClick()
            runOnIdle { assertEquals(emptyList(), sent, "there is nothing to dispatch") }
        }
    }

    @Test
    fun a_button_dispatches_its_action_with_the_context_resolved_at_the_tap() = runComposeUiTest {
        val sent = mutableListOf<RendererToAgentMessage>()
        val renderer = rendererFor(BUTTON)
        setContent { Surface(renderer, onMessage = { sent += it }) }
        // Moved before the tap, so a button that had captured its context at composition time
        // would send "Ada" and fail here.
        runOnIdle { renderer.write(SURFACE, JsonPointer.parse("/user/name"), JsonPrimitive("Grace")) }
        onNodeWithText("Send").performClick()
        runOnIdle {
            val message = sent.single() as ActionMessage
            assertEquals("submitted", message.name)
            assertEquals("action_button", message.sourceComponentId)
            assertEquals(
                JsonPrimitive("Grace"),
                message.context?.get("who"),
                "the action's context should resolve when it is dispatched, not when it is drawn",
            )
        }
    }

    @Test
    fun typing_into_a_field_reaches_everything_else_bound_to_the_same_path() = runComposeUiTest {
        // A focused text field blinks its cursor, and that is an animation that never ends -- so
        // `waitForIdle`, which every assertion below calls, would wait for an idle clock forever.
        // Stopping the clock lets recomposition still run and settle, which is the state under
        // test; the cursor is not.
        mainClock.autoAdvance = false
        // Two-way binding, end to end: the field holds no text of its own, so the `Text` below it
        // can only change if the keystroke went through the data model.
        val renderer = rendererFor(FIELD)
        setContent { Surface(renderer) }
        onNode(hasSetTextAction()).performTextReplacement("Grace")
        // The keystroke reaches the data model straight away -- that is a plain state write, and
        // it is the half of two-way binding this component owns.
        runOnIdle {
            assertEquals(
                JsonPrimitive("Grace"),
                (renderer.state.surfaces[SURFACE]?.dataModel as JsonObject)["typed"],
            )
        }
        // Drawing it again is the half the paused clock holds back: with `autoAdvance` off,
        // `waitForIdle` stops recomposing, so the frame has to be asked for.
        mainClock.advanceTimeByFrame()
        onNodeWithText("You typed: Grace").assertIsDisplayed()
    }

    @Test
    fun a_field_with_nowhere_to_write_is_read_only_rather_than_silently_lossy() = runComposeUiTest {
        setContent { Surface(LITERAL_FIELD) }
        // The field is drawn -- its label is on screen -- and it offers no way to set text. That
        // absence *is* the read-only state as Compose reports it: a read-only field keeps its
        // node and drops the editing action, rather than being disabled.
        onNodeWithText("Fixed").assertIsDisplayed()
        onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test
    fun the_thirteen_components_this_registry_does_not_draw_say_so() = runComposeUiTest {
        // The registry's coverage claim, asserted rather than documented. A `Card` drawn as
        // nothing would be indistinguishable from a `Card` drawn correctly and empty.
        val reasons = mutableListOf<A2uiPlaceholderReason>()
        setContent { Surface(CARD, placeholder = { reason, _ -> reasons += reason }) }
        assertTrue(
            reasons.any { it is A2uiPlaceholderReason.UnknownType && it.component == "Card" },
            "an undrawn component type should be reported as one: $reasons",
        )
    }

    @Composable
    private fun Surface(
        components: String,
        placeholder: A2uiPlaceholder = A2uiPlaceholder { _, _ -> },
    ) = Surface(rendererFor(components), placeholder)

    @Composable
    private fun Surface(
        renderer: A2uiRenderer,
        placeholder: A2uiPlaceholder = A2uiPlaceholder { _, _ -> },
        onMessage: (RendererToAgentMessage) -> Unit = {},
    ) {
        // Every renderer in this module reads the theme, so the theme is part of the harness
        // rather than part of a test.
        MaterialTheme {
            A2uiSurface(
                renderer = renderer,
                surfaceId = SURFACE,
                registry = Material3Components.Basic,
                placeholder = placeholder,
                onMessage = onMessage,
            )
        }
    }

    private fun rendererFor(components: String): A2uiRenderer =
        A2uiRenderer(clock = { "2026-08-28T00:00:00Z" }).also { renderer ->
            renderer.applyAll(
                listOf(
                    """{"version":"v1.0","createSurface":{"surfaceId":"$SURFACE","catalogId":"CATALOG_ID"}}""",
                    """{"version":"v1.0","updateDataModel":{"surfaceId":"$SURFACE","value":{
                        "user":{"name":"Ada"},"typed":""
                    }}}""",
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

        val TEXTS = """[
            {"id":"root","component":"Column","children":["heading","caption"]},
            {"id":"heading","component":"Text","text":"# Hello, Minimal Catalog!"},
            {"id":"caption","component":"Text","text":"a caption","variant":"caption"}
        ]"""

        val LAYOUT = """[
            {"id":"root","component":"Column","children":["row","top","bottom"],
             "justify":"spaceBetween","align":"center"},
            {"id":"row","component":"Row","children":["left","right"],
             "justify":"spaceBetween","align":"center"},
            {"id":"left","component":"Text","text":"left","weight":1},
            {"id":"right","component":"Text","text":"right","weight":2},
            {"id":"top","component":"Text","text":"top"},
            {"id":"bottom","component":"Text","text":"bottom"}
        ]"""

        val NESTED_ROWS = """[
            {"id":"root","component":"Row","children":["header_left","date"],
             "justify":"spaceBetween","align":"center"},
            {"id":"header_left","component":"Row","children":["origin"]},
            {"id":"origin","component":"Text","text":"origin"},
            {"id":"date","component":"Text","text":"date"}
        ]"""

        val BUTTON = """[
            {"id":"root","component":"Column","children":["action_button"]},
            {"id":"action_button","component":"Button","child":"label","variant":"primary",
             "action":{"event":{"name":"submitted","context":{"who":{"path":"/user/name"}}}}},
            {"id":"label","component":"Text","text":"Send"}
        ]"""

        val FIELD = """[
            {"id":"root","component":"Column","children":["input","echo"]},
            {"id":"input","component":"TextField","label":"Type something:","value":{"path":"/typed"}},
            {"id":"echo","component":"Text",
             "text":{"call":"formatString","args":{"value":"You typed: ${'$'}{/typed}"}}}
        ]"""

        val LITERAL_FIELD = """[
            {"id":"root","component":"Column","children":["input"]},
            {"id":"input","component":"TextField","label":"Fixed","value":"not a binding"}
        ]"""

        val DEFAULT_ALIGN = """[
            {"id":"root","component":"Column","children":["r","after"]},
            {"id":"r","component":"Row","children":["aaa","bbb"]},
            {"id":"aaa","component":"Text","text":"aaa"},
            {"id":"bbb","component":"Text","text":"bbb"},
            {"id":"after","component":"Text","text":"AFTER"}
        ]"""

        val COLUMN_IN_ROW = """[
            {"id":"root","component":"Row","children":["col","date"]},
            {"id":"col","component":"Column","children":["a"]},
            {"id":"a","component":"Text","text":"aaa"},
            {"id":"date","component":"Text","text":"date"}
        ]"""

        val FIELD_AND_BUTTON = """[
            {"id":"root","component":"Row","children":["f","btn"]},
            {"id":"f","component":"TextField","label":"Search","value":{"path":"/typed"}},
            {"id":"btn","component":"Button","child":"lbl","action":{"event":{"name":"go"}}},
            {"id":"lbl","component":"Text","text":"GO"}
        ]"""

        val CENTERED_NESTED_ROW = """[
            {"id":"root","component":"Row","children":["inner","date"],
             "justify":"spaceBetween","align":"center"},
            {"id":"inner","component":"Row","children":["origin"],
             "justify":"center","align":"center"},
            {"id":"origin","component":"Text","text":"origin"},
            {"id":"date","component":"Text","text":"date"}
        ]"""

        val OVERFLOWING_WEIGHT = """[
            {"id":"root","component":"Row","children":["aaa","bbb"],"align":"center"},
            {"id":"aaa","component":"Text","text":"aaa","weight":1e39},
            {"id":"bbb","component":"Text","text":"bbb"}
        ]"""

        val OBSCURED_FIELD = """[
            {"id":"root","component":"Column","children":["input"]},
            {"id":"input","component":"TextField","label":"Password",
             "value":{"path":"/typed"},"variant":"obscured"}
        ]"""

        val MALFORMED_ACTION = """[
            {"id":"root","component":"Column","children":["action_button"]},
            {"id":"action_button","component":"Button","child":"label",
             "action":{"neither":"an invoke nor an event"}},
            {"id":"label","component":"Text","text":"Send"}
        ]"""

        val CARD = """[
            {"id":"root","component":"Card","child":"inner"},
            {"id":"inner","component":"Text","text":"inside"}
        ]"""
    }
}
