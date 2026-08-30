package dev.ynagai.a2ui.material3

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
import dev.ynagai.a2ui.compose.ComponentRenderer
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
 * The ten components, drawn.
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
    fun a_nested_row_whose_justify_is_bound_is_granted_room_too() {
        // The parent reads the child's `justify` off the component, and the child resolves it
        // through `rememberString` -- so a bound `justify` was resolved by the child, which then
        // filled the width, while the parent saw nothing to grant a share for and the sibling
        // starved exactly as before. A `justify` this side cannot read now counts as spanning.
        runComposeUiTest {
            setContent { Surface(BOUND_JUSTIFY_NESTED_ROW) }
            val date = onNodeWithText("date").fetchSemanticsNode().boundsInRoot
            assertTrue(date.width > 0f, "the trailing text should have been given room: $date")
        }
    }

    @Test
    fun a_subcomposed_child_does_not_bring_the_surface_down() {
        // A host may register anything, and the eight components still to be written include
        // ones a `LazyColumn` is the natural body for. An earlier fix for the sibling-starvation
        // bug put `height(IntrinsicSize.Min)` on every default-`align` row, which asks each
        // descendant for an intrinsic measurement -- and `SubcomposeLayout` raises rather than
        // answering. Composing at all is the assertion.
        runComposeUiTest {
            val registry = Material3Components.Basic.with(
                mapOf("Card" to ComponentRenderer { _, m -> LazyColumn(m) { item { Text("lazy") } } }),
            )
            setContent {
                MaterialTheme {
                    A2uiSurface(rendererFor(LAZY_CHILD), SURFACE, registry)
                }
            }
            onNodeWithText("lazy").assertIsDisplayed()
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
    fun the_eight_components_this_registry_does_not_draw_say_so() = runComposeUiTest {
        // The registry's coverage claim, asserted rather than documented. A `Tabs` drawn as
        // nothing would be indistinguishable from a `Tabs` drawn correctly and empty.
        val reasons = mutableListOf<A2uiPlaceholderReason>()
        setContent { Surface(UNDRAWN, placeholder = { reason, _ -> reasons += reason }) }
        assertTrue(
            reasons.any { it is A2uiPlaceholderReason.UnknownType && it.component == "Tabs" },
            "an undrawn component type should be reported as one: $reasons",
        )
    }

    @Test
    fun a_card_draws_its_child_inside_itself() = runComposeUiTest {
        setContent { Surface(CARD) }
        onNodeWithText("inside").assertIsDisplayed()
    }

    @Test
    fun a_card_insets_its_child_rather_than_letting_it_touch_the_outline() = runComposeUiTest {
        // The guide's 16dp inner padding, which is the half of a card's spacing that is not the
        // Leaf-Margin Strategy: without it the text sits on the border it is meant to be framed by.
        // Measured against an uncarded twin in the same column rather than against the card's own
        // bounds, because a card draws no semantics node of its own to measure.
        setContent { Surface(CARDED_AND_BARE) }
        val carded = onNodeWithText("carded").fetchSemanticsNode().boundsInRoot
        val bare = onNodeWithText("bare").fetchSemanticsNode().boundsInRoot
        assertTrue(
            carded.left > bare.left,
            "the carded text should be inset by the card's frame and padding: $carded vs $bare",
        )
    }

    @Test
    fun a_list_draws_every_instance_of_its_template() = runComposeUiTest {
        // Every `List` in the corpus is a template over a bound array. The adapter layer expands
        // it; what this asserts is that the container draws all of what it was handed, each in the
        // collection scope that makes a relative path resolve to that item.
        setContent { Surface(TEMPLATED_LIST) }
        for (item in listOf("one", "two", "three")) {
            onNodeWithText(item).assertIsDisplayed()
        }
    }

    @Test
    fun a_list_leaves_room_for_what_follows_it() = runComposeUiTest {
        // Why this is a scrolling `Column` and not a `LazyColumn`: a lazy list fills the main axis
        // it is offered rather than wrapping its content, so it would claim the whole column and
        // push the text below it off the surface.
        setContent { Surface(LIST_THEN_TEXT) }
        val after = onNodeWithText("AFTER").fetchSemanticsNode().boundsInRoot
        assertTrue(after.height > 0f, "the text after the list should have been drawn: $after")
    }

    @Test
    fun a_divider_takes_room_between_the_things_it_separates() = runComposeUiTest {
        // Both pairs in one composition, and the comparison inside it. Two `runComposeUiTest`
        // blocks in a row would have been the natural way to write this and is wrong on the web
        // targets: there the call returns before its body has run, so an assertion after it reads
        // whatever the variables were initialised to -- which is how this first failed, on wasmJs
        // alone, comparing 0.0 with 0.0 while every other target agreed it passed.
        setContent { Surface(DIVIDED_AND_NOT) }
        val divided = onNodeWithText("below").fetchSemanticsNode().boundsInRoot.top -
            onNodeWithText("above").fetchSemanticsNode().boundsInRoot.bottom
        val plain = onNodeWithText("under").fetchSemanticsNode().boundsInRoot.top -
            onNodeWithText("over").fetchSemanticsNode().boundsInRoot.bottom
        // The same two leaves with the same margins either side, so the difference is the divider.
        assertTrue(divided > plain, "the divider should have taken room of its own: $divided vs $plain")
    }

    @Test
    fun an_icon_takes_the_room_its_glyph_needs() = runComposeUiTest {
        // An icon carries no text and, having no `contentDescription` to give -- the catalog gives
        // an `Icon` no accessibility property -- no semantics node either. So it is measured by
        // what it displaces: the same label, in the same row, with and without an icon before it.
        setContent { Surface(ICON_AND_BARE_ROWS) }
        val after = onNodeWithText("after icon").fetchSemanticsNode().boundsInRoot
        val bare = onNodeWithText("no icon").fetchSemanticsNode().boundsInRoot
        assertTrue(
            after.left > bare.left,
            "the icon should have taken room before its label: $after vs $bare",
        )
    }

    @Test
    fun an_icon_whose_name_is_not_in_the_catalog_holds_its_place() = runComposeUiTest {
        // The enum is closed, so an unknown name is a payload the schema already refuses. What a
        // renderer owes is a layout that does not shift: collapsing the icon would move every
        // sibling in the row, turning one bad property into a rearranged surface.
        setContent { Surface(UNKNOWN_ICON_ROWS) }
        val after = onNodeWithText("after icon").fetchSemanticsNode().boundsInRoot
        val bare = onNodeWithText("no icon").fetchSemanticsNode().boundsInRoot
        assertTrue(
            after.left > bare.left,
            "an unknown icon should still hold its 24dp: $after vs $bare",
        )
    }

    @Test
    fun an_image_without_a_loader_still_carries_its_description() = runComposeUiTest {
        // The placeholder is not a blank: this module fetches nothing, and the description is the
        // one part of the image it can still deliver.
        setContent { Surface(IMAGE) }
        onNodeWithContentDescription("a cat").assertIsDisplayed()
    }

    @Test
    fun an_image_is_drawn_by_the_loader_the_host_provided() = runComposeUiTest {
        // The extension point, exercised. A host that provides a loader gets its own composable
        // called with the resolved URL -- which is how a real image reaches the screen without
        // this module owning an HTTP stack.
        val urls = mutableListOf<String>()
        setContent {
            CompositionLocalProvider(
                LocalA2uiImageLoader provides A2uiImageLoader { url, _, _, modifier ->
                    urls += url
                    Text("drawn", modifier)
                },
            ) { Surface(IMAGE) }
        }
        onNodeWithText("drawn").assertIsDisplayed()
        assertEquals(listOf("https://example.test/cat.png"), urls)
    }

    @Test
    fun a_leaf_carries_the_margin_the_spacing_strategy_asks_for() = runComposeUiTest {
        // §3's Leaf-Margin Strategy, asserted where it is visible: two texts in a column are
        // separated by twice the margin, and neither touches the surface's edge. Asserting the gap
        // rather than the number keeps this a test of the strategy rather than of `8.dp`.
        setContent { Surface(TEXTS) }
        val heading = onNodeWithText("Hello, Minimal Catalog!").fetchSemanticsNode().boundsInRoot
        val caption = onNodeWithText("a caption").fetchSemanticsNode().boundsInRoot
        assertTrue(heading.top > 0f, "the first leaf should be inset from the top: $heading")
        assertTrue(
            caption.top > heading.bottom,
            "the leaves' margins should separate them: $heading then $caption",
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
                        "user":{"name":"Ada"},"typed":"","justify":"center",
                        "items":[{"label":"one"},{"label":"two"},{"label":"three"}]
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

        val BOUND_JUSTIFY_NESTED_ROW = """[
            {"id":"root","component":"Row","children":["inner","date"],
             "justify":"spaceBetween","align":"center"},
            {"id":"inner","component":"Row","children":["origin"],
             "justify":{"path":"/justify"},"align":"center"},
            {"id":"origin","component":"Text","text":"origin"},
            {"id":"date","component":"Text","text":"date"}
        ]"""

        val LAZY_CHILD = """[
            {"id":"root","component":"Row","children":["lz"]},
            {"id":"lz","component":"Card","child":"x"},
            {"id":"x","component":"Text","text":"x"}
        ]"""


        val CARD = """[
            {"id":"root","component":"Card","child":"inner"},
            {"id":"inner","component":"Text","text":"inside"}
        ]"""

        val TEMPLATED_LIST = """[
            {"id":"root","component":"List","children":{"path":"/items","componentId":"item"}},
            {"id":"item","component":"Text","text":{"path":"label"}}
        ]"""

        val LIST_THEN_TEXT = """[
            {"id":"root","component":"Column","children":["lst","after"]},
            {"id":"lst","component":"List","children":{"path":"/items","componentId":"item"}},
            {"id":"item","component":"Text","text":{"path":"label"}},
            {"id":"after","component":"Text","text":"AFTER"}
        ]"""

        val DIVIDED_AND_NOT = """[
            {"id":"root","component":"Column","children":["above","rule","below","over","under"]},
            {"id":"above","component":"Text","text":"above"},
            {"id":"rule","component":"Divider"},
            {"id":"below","component":"Text","text":"below"},
            {"id":"over","component":"Text","text":"over"},
            {"id":"under","component":"Text","text":"under"}
        ]"""

        val CARDED_AND_BARE = """[
            {"id":"root","component":"Column","children":["card","bare"]},
            {"id":"card","component":"Card","child":"carded"},
            {"id":"carded","component":"Text","text":"carded"},
            {"id":"bare","component":"Text","text":"bare"}
        ]"""

        val ICON_AND_BARE_ROWS = """[
            {"id":"root","component":"Column","children":["with","without"]},
            {"id":"with","component":"Row","children":["star","after"]},
            {"id":"star","component":"Icon","name":"star"},
            {"id":"after","component":"Text","text":"after icon"},
            {"id":"without","component":"Row","children":["plain"]},
            {"id":"plain","component":"Text","text":"no icon"}
        ]"""

        val UNKNOWN_ICON_ROWS = """[
            {"id":"root","component":"Column","children":["with","without"]},
            {"id":"with","component":"Row","children":["odd","after"]},
            {"id":"odd","component":"Icon","name":"notAnIconInThisCatalog"},
            {"id":"after","component":"Text","text":"after icon"},
            {"id":"without","component":"Row","children":["plain"]},
            {"id":"plain","component":"Text","text":"no icon"}
        ]"""

        val IMAGE = """[
            {"id":"root","component":"Column","children":["pic"]},
            {"id":"pic","component":"Image","url":"https://example.test/cat.png",
             "description":"a cat","fit":"cover"}
        ]"""

        val UNDRAWN = """[
            {"id":"root","component":"Column","children":["tabs"]},
            {"id":"tabs","component":"Tabs","titles":["one"],"children":["inner"]},
            {"id":"inner","component":"Text","text":"inside"}
        ]"""
    }
}
