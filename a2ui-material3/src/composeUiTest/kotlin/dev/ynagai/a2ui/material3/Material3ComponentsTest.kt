package dev.ynagai.a2ui.material3

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsProperties
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
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ynagai.a2ui.compose.A2uiPlaceholder
import dev.ynagai.a2ui.compose.A2uiPlaceholderReason
import dev.ynagai.a2ui.compose.A2uiRenderer
import dev.ynagai.a2ui.compose.A2uiRendererConfig
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
    fun a_long_text_in_a_row_shares_the_width_rather_than_taking_it() = runComposeUiTest {
        // The specification's `13_coffee-order` shape -- a `spaceBetween` row holding a column of
        // texts and a price -- with an item name long enough to wrap. Compose's own `Row` measures
        // the column first against the whole width, the name wraps at that width, and the price
        // measures at zero. The upstream issue (a2ui-project/a2ui#2710) calls it main-axis space
        // starvation, and it is the case `claimsMainAxis` could not reach: nothing here fills.
        setContent { Surface(LONG_NAME_BESIDE_PRICE, width = PHONE_WIDTH) }
        val root = onRoot().fetchSemanticsNode().boundsInRoot
        val price = onNodeWithText("$4.50").fetchSemanticsNode().boundsInRoot
        assertTrue(price.width > 0f, "the price should have been given room: $price")
        assertTrue(
            price.right <= root.right && price.left > root.left + root.width / 2f,
            "the price should sit in the right half, on screen: $price within $root",
        )
    }

    @Test
    fun a_long_text_does_not_starve_a_leaf_that_comes_after_it() = runComposeUiTest {
        // The same failure with a `Button` as the sibling. A leaf with a size of its own is exactly
        // what a sequential measure hands nothing to once a wrapping text has taken the width first.
        setContent { Surface(LONG_TEXT_THEN_BUTTON, width = PHONE_WIDTH) }
        val root = onRoot().fetchSemanticsNode().boundsInRoot
        val button = onNodeWithText("Go").fetchSemanticsNode().boundsInRoot
        assertTrue(button.width > 0f && button.right <= root.right, "the button should be drawn on screen: $button in $root")
    }

    @Test
    fun two_long_texts_in_a_row_both_get_room() = runComposeUiTest {
        // Neither has a claim on the other: both wrap, so both shrink, and the second is not left
        // with what the first did not want. Flexbox's answer, which is the web renderers'.
        setContent { Surface(TWO_LONG_TEXTS, width = PHONE_WIDTH) }
        val root = onRoot().fetchSemanticsNode().boundsInRoot
        val first = onNodeWithText(LONG_TEXT_A).fetchSemanticsNode().boundsInRoot
        val second = onNodeWithText(LONG_TEXT_B).fetchSemanticsNode().boundsInRoot
        assertTrue(first.width > 0f && second.width > 0f, "both texts should be drawn: $first, $second")
        assertTrue(second.right <= root.right + 1f, "the second text should not run off the row: $second in $root")
        assertTrue(second.left >= first.right, "the texts should not overlap: $first then $second")
    }

    @Test
    fun a_card_around_a_banner_image_is_not_measured_to_nothing() = runComposeUiTest {
        // A `largeFeature` image fills whatever width it is given, so the card around it has no
        // preferred width to report -- an intrinsic query answers zero. A layout that shared the
        // row from preferred sizes alone measured the card to zero and it vanished, text and all.
        setContent { Surface(CARD_OF_BANNER_BESIDE_TEXT, width = PHONE_WIDTH) }
        val caption = onNodeWithText("in the card").fetchSemanticsNode().boundsInRoot
        val beside = onNodeWithText("beside the card").fetchSemanticsNode().boundsInRoot
        assertTrue(caption.width > 0f, "the card's own text should be drawn: $caption")
        assertTrue(beside.width > 0f && beside.left >= caption.right, "the text beside it should keep its place: $beside after $caption")
    }

    @Test
    fun a_field_in_a_wide_row_keeps_its_natural_width() = runComposeUiTest {
        // The other half of a field filling a row: on a screen with room, it takes Material's
        // 280dp and not the whole row, which is what the web renderers draw for a weightless
        // input (`flex: 0 1 auto`) and what leaves `justify` something to arrange. Drawn at the
        // harness's own width, which is wide.
        setContent { Surface(FIELD_AND_BUTTON) }
        val field = onNodeWithText("Search").fetchSemanticsNode().boundsInRoot
        // Material's `TextFieldDefaults.MinWidth`, at the harness's density of one.
        assertTrue(field.width <= 280f, "a field with room should take its natural width, not the row: $field")
    }

    @Test
    fun a_row_under_a_right_to_left_locale_puts_its_first_child_on_the_right() = runComposeUiTest {
        // `Arrangement.Horizontal.arrange` already takes the layout direction and hands back
        // physical x positions; placing those relatively mirrored them a second time, and an RTL
        // row read left-to-right with `start` and `end` swapped.
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Surface(TWO_SHORT_TEXTS, width = PHONE_WIDTH)
            }
        }
        val first = onNodeWithText("first").fetchSemanticsNode().boundsInRoot
        val second = onNodeWithText("second").fetchSemanticsNode().boundsInRoot
        assertTrue(first.left >= second.right, "in RTL the first child sits to the right of the second: $first, $second")
    }

    @Test
    fun an_enormous_weight_does_not_crash_the_row() = runComposeUiTest {
        // `1e38` passes `weightOf` -- it is a finite `Float` -- and multiplied by the free width it
        // overflowed to infinity, which `roundToInt` saturates to `Int.MAX_VALUE`, a width
        // `Constraints` cannot hold. The share arithmetic is done in doubles now.
        setContent { Surface(HUGE_WEIGHT_BESIDE_ONE, width = PHONE_WIDTH) }
        val root = onRoot().fetchSemanticsNode().boundsInRoot
        val heavy = onNodeWithText("heavy").fetchSemanticsNode().boundsInRoot
        // The unit-weighted sibling's share is genuinely nothing; the assertion is that the row
        // measured and the heavy child took the row rather than an unrepresentable width.
        assertTrue(
            heavy.width > root.width * 0.8f && heavy.right <= root.right + 1f,
            "the weighted child takes the row and stays on screen: $heavy in $root",
        )
    }

    @Test
    fun two_very_long_texts_still_share_the_row_fairly() = runComposeUiTest {
        // Long enough that the deficit times a preferred width passes `Int.MAX_VALUE`: the
        // proportional cut was computed in `Int` and wrapped, so nobody was pinned, the loop
        // stopped, and the rounding clean-up drained the first text to its floor.
        setContent { Surface(TWO_VERY_LONG_TEXTS, width = PHONE_WIDTH) }
        val first = onNodeWithText(VERY_LONG_TEXT_A).fetchSemanticsNode().boundsInRoot
        val second = onNodeWithText(VERY_LONG_TEXT_B).fetchSemanticsNode().boundsInRoot
        assertTrue(
            first.width > 60f && second.width > 60f,
            "both texts should keep a fair share of the row: $first, $second",
        )
    }

    @Test
    fun an_unbreakable_token_wider_than_constraints_can_hold_does_not_crash_the_row() = runComposeUiTest {
        // A text's minimum intrinsic width is its longest word, and an agent can make that any
        // length. `Constraints` holds at most 2^18 - 2 in one dimension, so a floor past that has
        // to be clamped before it becomes a measurement constraint.
        setContent { Surface(UNBREAKABLE_TOKEN_BESIDE_TEXT, width = PHONE_WIDTH) }
        val token = onNodeWithText("x".repeat(40_000)).fetchSemanticsNode().boundsInRoot
        val beside = onNodeWithText("beside").fetchSemanticsNode().boundsInRoot
        assertTrue(token.width > 0f && beside.width > 0f, "both children measured: $token, $beside")
    }

    @Test
    fun a_host_markdown_renderer_that_cannot_be_asked_does_not_crash_a_row() = runComposeUiTest {
        // What a `Text` draws is the host's. A renderer built on a `SubcomposeLayout` --
        // `BoxWithConstraints` is one -- raises on the intrinsic query, from inside the row's own
        // measure pass; the row catches it, remembers, and measures the text without asking.
        setContent {
            CompositionLocalProvider(LocalA2uiMarkdownRenderer provides SubcomposingMarkdown) {
                Surface(TWO_SHORT_TEXTS, width = PHONE_WIDTH)
            }
        }
        val first = onNodeWithText("first").fetchSemanticsNode().boundsInRoot
        val second = onNodeWithText("second").fetchSemanticsNode().boundsInRoot
        assertTrue(first.width > 0f && second.width > 0f, "both texts drawn: $first, $second")
    }

    @Test
    fun a_host_image_loader_that_cannot_be_asked_does_not_crash_a_row() = runComposeUiTest {
        // The same seam for `Image` and a `Video`'s poster: Coil's `SubcomposeAsyncImage` is a
        // `SubcomposeLayout`, and the row survives the refusal the same way. A filling image is
        // never asked directly, so the fixture asks through a fixed-size one and a card.
        setContent {
            CompositionLocalProvider(LocalA2uiImageLoader provides SubcomposingImageLoader) {
                Surface(IMAGE_AND_VIDEO_BESIDE_TEXT, width = PHONE_WIDTH)
            }
        }
        val beside = onNodeWithText("beside").fetchSemanticsNode().boundsInRoot
        assertTrue(beside.width > 0f, "the text beside the media is drawn: $beside")
    }

    @Test
    fun a_tabs_inside_a_row_leaves_room_for_the_text_beside_it() = runComposeUiTest {
        // A tab strip measures itself across whatever it is offered, so in a row it is a filler.
        // It sits in a row beside a text, and inside a card in the same row, so that the container
        // above it and the one above that both have to share around it.
        setContent { Surface(TABS_IN_A_ROW, width = PHONE_WIDTH) }
        val beside = onNodeWithText("beside").fetchSemanticsNode().boundsInRoot
        assertTrue(beside.width > 0f, "the text beside the tabs is drawn: $beside")
    }

    @Test
    fun a_spaced_column_under_a_right_to_left_locale_keeps_its_children_in_order() = runComposeUiTest {
        // `Arrangement.SpaceBetween` and its kin are both `Horizontal` and `Vertical`, so a
        // dispatch on the arrangement's type sent a column's through the horizontal overload,
        // which mirrors under RTL -- and the column read bottom to top.
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Surface(SPACED_COLUMN, width = PHONE_WIDTH)
            }
        }
        val first = onNodeWithText("first").fetchSemanticsNode().boundsInRoot
        val second = onNodeWithText("second").fetchSemanticsNode().boundsInRoot
        assertTrue(first.bottom <= second.top, "the first child stays above the second: $first, $second")
    }

    @Test
    fun two_children_that_cannot_be_asked_share_the_row_between_them() = runComposeUiTest {
        // Two host renderers built on a `LazyColumn`, side by side. Neither can be asked its
        // size, so the row measures them as they come -- but sharing what is left between the
        // ones still to come, not handing the first the lot: measured in order against the whole
        // width, the first wraps its text at the row and the second measures at zero, which is the
        // starvation this layout exists to end, back again by a different door.
        val registry = Material3Components.Basic.with(
            mapOf(
                "Feed" to ComponentRenderer { scope, m ->
                    LazyColumn(m) { item { Text(scope.string("text").orEmpty()) } }
                },
            ),
        )
        setContent {
            Box(Modifier.size(PHONE_WIDTH, SURFACE_HEIGHT)) {
                MaterialTheme { A2uiSurface(rendererFor(TWO_FEEDS), SURFACE, registry) }
            }
        }
        val root = onRoot().fetchSemanticsNode().boundsInRoot
        val first = onNodeWithText(LONG_TEXT_A).fetchSemanticsNode().boundsInRoot
        val second = onNodeWithText(LONG_TEXT_B).fetchSemanticsNode().boundsInRoot
        assertTrue(first.width > 0f && second.width > 0f, "both feeds drawn: $first, $second")
        assertTrue(second.right <= root.right + 1f, "the second feed stays on the row: $second in $root")
        assertTrue(second.left >= first.right, "the feeds do not overlap: $first then $second")
    }

    @Test
    fun a_field_beside_a_long_text_shrinks_with_it_rather_than_vanishing() = runComposeUiTest {
        // A field is content-sized like the web's `<input>`: 280dp when there is room, less when
        // there is not, in proportion with the text beside it. Measured as a filler it would have
        // been handed what the text left, and a text that wraps at the row leaves nothing.
        setContent { Surface(FIELD_BESIDE_LONG_TEXT, width = PHONE_WIDTH) }
        val root = onRoot().fetchSemanticsNode().boundsInRoot
        val field = onNodeWithText("Search").fetchSemanticsNode().boundsInRoot
        val text = onNodeWithText(LONG_TEXT_A).fetchSemanticsNode().boundsInRoot
        assertTrue(field.width >= 100f, "the field keeps a usable width: $field")
        assertTrue(text.width > 0f && text.right <= root.right + 1f, "the text is drawn beside it: $text in $root")
    }

    @Test
    fun an_empty_row_does_not_take_a_share_from_a_weighted_sibling() = runComposeUiTest {
        // A child whose preferred size is nothing is measured as a filler, up to a share -- and
        // an empty row takes none of it. What it did not take goes to the weighted sibling, not
        // to nobody: the shares are cut from what is left after the fillers, not alongside them.
        setContent { Surface(EMPTY_ROW_AND_WEIGHTED_TEXT, width = PHONE_WIDTH) }
        val root = onRoot().fetchSemanticsNode().boundsInRoot
        val weighted = onNodeWithText("takes the rest").fetchSemanticsNode().boundsInRoot
        assertTrue(weighted.width >= root.width * 0.8f, "the weighted text should get nearly the row: $weighted in $root")
    }

    @Test
    fun a_column_short_of_height_sizes_a_row_by_the_text_as_it_will_wrap() = runComposeUiTest {
        // A column that is short of height asks each row how tall it would like to be. Asked at
        // the column's full width, a row answers for a text laid out on that width -- two lines --
        // while the text, given its share of the row beside a price, wraps to four. A row sized
        // from the first answer cuts the text; the plan the row measures by is the plan it
        // answers by, so the height is the four-line one.
        setContent { Surface(WRAPPING_ROW_IN_A_SHORT_COLUMN, width = PHONE_WIDTH, height = 200.dp) }
        val text = onNodeWithText(LONG_TEXT_B).fetchSemanticsNode().boundsInRoot
        val after = onNodeWithText("after").fetchSemanticsNode().boundsInRoot
        // `after` is one line, so it is the ruler: the wrapped text should stand at least three of it.
        assertTrue(text.height >= after.height * 3f - 1f, "the text should be laid out on the lines its share needs, not cut: $text against a line of ${after.height}")
        assertTrue(after.top >= text.bottom - 1f, "the text after the row sits below all of it: $after under $text")
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
    fun a_component_from_outside_the_basic_catalog_says_so() = runComposeUiTest {
        // The registry's coverage claim, from the other side. It now draws all eighteen of the
        // basic catalog, so what is left to assert is that a type it does not know still reports
        // itself: a component drawn as nothing would be indistinguishable from one drawn correctly
        // and empty, which is the failure the placeholder machinery exists to make visible.
        val reasons = mutableListOf<A2uiPlaceholderReason>()
        setContent { Surface(UNDRAWN, placeholder = { reason, _ -> reasons += reason }) }
        assertTrue(
            reasons.any { it is A2uiPlaceholderReason.UnknownType && it.component == "Sparkline" },
            "an undrawn component type should be reported as one: $reasons",
        )
    }

    @Test
    fun the_registry_draws_every_component_the_basic_catalog_defines() {
        // Pinned by name rather than by count, so a rename or a drop has to say which one.
        assertEquals(
            setOf(
                "Text", "Row", "Column", "Button", "TextField", "Card", "Divider", "List",
                "Icon", "Image", "CheckBox", "ChoicePicker", "Slider", "DateTimeInput",
                "Tabs", "Modal", "Video", "AudioPlayer",
            ),
            Material3Components.Basic.types,
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
        // The *size* of the inset, not merely its sign. Both texts carry the leaf margin and the
        // card adds a 1dp outline, so `carded.left > bare.left` holds by 9dp with the card's own
        // padding deleted -- the assertion passed under the regression it names. The padding is
        // the only part of the difference worth 16dp.
        val inset = carded.left - bare.left
        assertTrue(
            inset >= CARD_PADDING_PX,
            "the card's inner padding should inset its child by ~16dp, was ${inset}px: $carded vs $bare",
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
        // The same two leaves with the same margins either side, so the difference is the divider:
        // its own two margins plus the hairline. Asserted against the margins alone, because
        // `divided > plain` is already true of a divider that draws nothing at all -- the leaf
        // margin carries 16 of the 17dp, and this test used to pass with the hairline deleted.
        val hairline = divided - plain - LEAF_MARGIN_PX * 2
        assertTrue(
            hairline > 0f,
            "the divider itself should have taken room, not just its margins: $divided vs $plain",
        )
    }

    @Test
    fun an_icon_holds_a_24dp_slot_in_the_row_it_sits_in() = runComposeUiTest {
        // **This asserts placement, not drawing, and is named for what it can actually settle.**
        // An icon carries no text and, having no `contentDescription` to give -- the catalog gives
        // an `Icon` no accessibility property -- no semantics node either, so the only thing a
        // composition test can read is what it displaces. Both branches of `IconRenderer` displace
        // the same 24dp, which is deliberate: the sibling test below asserts that the *unknown*
        // name holds the identical slot. An earlier name for this test claimed it proved the glyph
        // drew, and it passed with every glyph emptied out. That the glyphs resolve at all is
        // `IconGlyphsTest`'s job, where it is checkable.
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
    fun a_url_the_loader_should_not_fetch_never_reaches_it() = runComposeUiTest {
        // The allowlist `A2uiImageLoader` promises. An image loader resolves `file://` and, on
        // Android, `content://`, so without this a payload could name a local path and read back
        // from the drawing whether it was there. Refused draws the placeholder, which still
        // carries the description -- so the assertion is both halves: not fetched, still described.
        val urls = mutableListOf<String>()
        setContent {
            CompositionLocalProvider(
                LocalA2uiImageLoader provides A2uiImageLoader { url, _, _, modifier ->
                    urls += url
                    Text("drawn", modifier)
                },
            ) { Surface(LOCAL_FILE_IMAGE) }
        }
        onNodeWithContentDescription("a private file").assertIsDisplayed()
        assertEquals(emptyList(), urls, "a file:// URL should never have reached the loader")
    }

    @Test
    fun a_text_is_drawn_by_the_markdown_renderer_the_host_provided() = runComposeUiTest {
        // The other extension point. The host's renderer is handed the source as the agent wrote
        // it -- marker and all -- because parsing is the whole of what it is there to replace; and
        // it is handed the style and colour `Text` resolved for the variant, because those are
        // what it is there to keep. Both halves are asserted, since a seam that dropped the
        // caption's style would draw every caption the size of body text.
        val drawn = mutableListOf<Triple<String, TextStyle, Color>>()
        var typography: Typography? = null
        setContent {
            typography = MaterialTheme.typography
            CompositionLocalProvider(
                LocalA2uiMarkdownRenderer provides A2uiMarkdownRenderer { source, style, color, modifier ->
                    drawn += Triple(source, style, color)
                    Text("host:$source", modifier)
                },
            ) { Surface(TEXTS) }
        }
        onNodeWithText("host:# Hello, Minimal Catalog!").assertIsDisplayed()
        onNodeWithText("host:a caption").assertIsDisplayed()
        // Every draw of a source, not the first: a recomposition that handed the wrong style the
        // second time would otherwise hide behind a correct first record.
        val theme = checkNotNull(typography)
        val headings = drawn.filter { it.first == "# Hello, Minimal Catalog!" }
        val captions = drawn.filter { it.first == "a caption" }
        assertTrue(headings.isNotEmpty() && captions.isNotEmpty(), "both texts should have been drawn: $drawn")
        for ((_, style, color) in headings) {
            assertEquals(theme.bodyLarge, style)
            assertEquals(Color.Unspecified, color, "body text inherits its colour")
        }
        for ((_, style, color) in captions) {
            assertEquals(theme.bodySmall, style)
            assertTrue(color.isSpecified && color.alpha < 1f, "a caption should be handed the dimmed colour: $color")
        }
    }

    @Test
    fun a_host_markdown_renderer_still_gets_the_leaf_margin() = runComposeUiTest {
        // The margin is `Text`'s, not the renderer's -- the modifier handed over already carries
        // it, as `A2uiImageLoader`'s does. A host renderer that puts the modifier on what it draws
        // is therefore spaced like every other leaf, without knowing the strategy exists.
        setContent {
            CompositionLocalProvider(
                LocalA2uiMarkdownRenderer provides A2uiMarkdownRenderer { source, _, _, modifier ->
                    Text(source, modifier)
                },
            ) { Surface(TEXTS) }
        }
        val heading = onNodeWithText("# Hello, Minimal Catalog!").fetchSemanticsNode().boundsInRoot
        val caption = onNodeWithText("a caption").fetchSemanticsNode().boundsInRoot
        assertTrue(heading.top > 0f && heading.left > 0f, "the leaf should be inset: $heading")
        assertTrue(caption.top > heading.bottom, "the margins should separate them: $heading then $caption")
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

    @Test
    fun a_list_inside_a_list_does_not_bring_the_surface_down() = runComposeUiTest {
        // A scroll container measured with an unbounded main axis raises out of the measure pass,
        // and a `List` inside a `List` is exactly that: the outer one offers infinite height. The
        // catalog permits the payload, so the renderer owes it a drawing rather than an exception.
        setContent { Surface(NESTED_LISTS) }
        onNodeWithText("deep").assertIsDisplayed()
    }

    @Test
    fun a_list_survives_a_host_that_scrolls_the_surface_itself() = runComposeUiTest {
        // The other way into the same crash, and the likelier one: a host putting the surface in
        // its own scrolling column, which is how a surface sits in a chat transcript.
        setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) { Surface(TEMPLATED_LIST) }
            }
        }
        onNodeWithText("one").assertIsDisplayed()
    }

    @Test
    fun a_divider_does_not_take_the_row_it_sits_in() = runComposeUiTest {
        // `HorizontalDivider` is a `fillMaxWidth` box, so a divider that named no axis took the
        // whole row and left its sibling measuring at zero -- the starvation `claimsMainAxis`
        // grants a share to avoid. Asserted on the sibling, which is what disappeared.
        setContent { Surface(DIVIDER_IN_ROW) }
        val sibling = onNodeWithText("beside the rule").fetchSemanticsNode().boundsInRoot
        assertTrue(sibling.width > 0f, "the divider should not have taken the row: $sibling")
    }

    @Test
    fun an_image_does_not_take_the_row_it_sits_in() = runComposeUiTest {
        // The default `mediumFeature` variant, not one of the banner ones. **The surface is given
        // a phone's width on purpose**: the variant's 300dp cap does save the sibling on a wide
        // window, so a test drawn at the harness's default size passes whether the fix is there or
        // not. 320dp is where the cap stops covering for it, and is also every phone.
        setContent { Surface(IMAGE_IN_ROW, width = PHONE_WIDTH) }
        val sibling = onNodeWithText("beside the picture").fetchSemanticsNode().boundsInRoot
        assertTrue(sibling.width > 0f, "the image should not have taken the row: $sibling")
    }

    @Test
    fun an_image_that_named_a_fixed_size_is_not_granted_a_share() = runComposeUiTest {
        // The other half of that rule, and the reason it is a rule about the *variant* rather than
        // about `Image`. An `avatar` is a 40dp square that claims nothing, so it must keep its
        // size rather than be stretched across a share of the row. Same narrow surface, so that a
        // granted share would be visible as a label pushed away from the left.
        setContent { Surface(AVATAR_IN_ROW, width = PHONE_WIDTH) }
        val label = onNodeWithText("beside the avatar").fetchSemanticsNode().boundsInRoot.left
        assertTrue(
            label < AVATAR_LABEL_MAX_LEFT,
            "an avatar should stay a 40dp square, so its label starts near the left: $label",
        )
    }

    @Composable
    private fun Surface(
        components: String,
        placeholder: A2uiPlaceholder = A2uiPlaceholder { _, _ -> },
    ) = Surface(rendererFor(components), placeholder)

    /**
     * The surface drawn at a chosen width.
     *
     * For the layout claims that only hold on a *small* screen. The harness's own window is wide
     * enough that a component with a max-width cap still leaves its siblings room, so a starvation
     * test drawn at the default size passes with or without the fix that makes it true.
     */
    @Composable
    private fun Surface(components: String, width: Dp, height: Dp = SURFACE_HEIGHT) {
        Box(Modifier.size(width, height)) { Surface(components) }
    }

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
        A2uiRenderer(A2uiRendererConfig.Default
            .withClock({ "2026-08-28T00:00:00Z" }),
        ).also { renderer ->
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

        /** A phone, which is the width the max-width caps stop covering for. */
        val PHONE_WIDTH = 320.dp

        /** Tall enough that nothing under test is height-constrained. */
        val SURFACE_HEIGHT = 600.dp

        /**
         * `Card`'s inner padding and the leaf margin, in the pixels a test reads back.
         *
         * The composeUiTest harness draws at a density of 1, so a dp is a pixel here. Named rather
         * than inlined because two assertions below subtract them from a measured gap.
         */
        const val CARD_PADDING_PX = 16f
        const val LEAF_MARGIN_PX = 8f

        /**
         * How far from the left an avatar's label may start: its 40dp square plus the margins
         * either side of it, and nothing like a share of a 320dp row.
         */
        const val AVATAR_LABEL_MAX_LEFT = 100f

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

        const val LONG_TEXT_A = "A first sentence that is comfortably longer than half a phone screen"
        const val LONG_TEXT_B = "And a second one that is longer still, so neither fits beside the other"

        val LONG_NAME_BESIDE_PRICE = """[
            {"id":"root","component":"Row","children":["item_details","item_price"],
             "justify":"spaceBetween","align":"start"},
            {"id":"item_details","component":"Column","children":["item_name","item_size"]},
            {"id":"item_name","component":"Text","variant":"body",
             "text":"Caramel Macchiato with oat milk, extra shot, no foam, light ice, in a reusable cup"},
            {"id":"item_size","component":"Text","text":"Large","variant":"caption"},
            {"id":"item_price","component":"Text","text":"${'$'}4.50","variant":"body"}
        ]"""

        val LONG_TEXT_THEN_BUTTON = """[
            {"id":"root","component":"Row","children":["long","go"]},
            {"id":"long","component":"Text","text":"$LONG_TEXT_A"},
            {"id":"go","component":"Button","child":"go_label","action":{"event":{"name":"go"}}},
            {"id":"go_label","component":"Text","text":"Go"}
        ]"""

        val TWO_SHORT_TEXTS = """[
            {"id":"root","component":"Row","children":["a","b"]},
            {"id":"a","component":"Text","text":"first"},
            {"id":"b","component":"Text","text":"second"}
        ]"""

        val VERY_LONG_TEXT_A = (1..900).joinToString(" ") { "alpha" }
        val VERY_LONG_TEXT_B = (1..900).joinToString(" ") { "omega" }

        val HUGE_WEIGHT_BESIDE_ONE = """[
            {"id":"root","component":"Row","children":["a","b"]},
            {"id":"a","component":"Text","text":"heavy","weight":1e38},
            {"id":"b","component":"Text","text":"light","weight":1}
        ]"""

        val TWO_VERY_LONG_TEXTS = """[
            {"id":"root","component":"Row","children":["a","b"]},
            {"id":"a","component":"Text","text":"$VERY_LONG_TEXT_A"},
            {"id":"b","component":"Text","text":"$VERY_LONG_TEXT_B"}
        ]"""

        val UNBREAKABLE_TOKEN_BESIDE_TEXT = """[
            {"id":"root","component":"Row","children":["a","b"]},
            {"id":"a","component":"Text","text":"${"x".repeat(40_000)}"},
            {"id":"b","component":"Text","text":"beside"}
        ]"""

        // An avatar is content-sized, so a row asks it; a video fills, so a row does not ask it
        // directly -- but the card around it is content, and the query recurses through the card.
        val IMAGE_AND_VIDEO_BESIDE_TEXT = """[
            {"id":"root","component":"Row","children":["pic","card","beside"]},
            {"id":"pic","component":"Image","url":"https://example.invalid/a.png","variant":"avatar"},
            {"id":"card","component":"Card","child":"clip"},
            {"id":"clip","component":"Video","url":"https://example.invalid/a.mp4","posterUrl":"https://example.invalid/p.png"},
            {"id":"beside","component":"Text","text":"beside"}
        ]"""

        /** A Markdown renderer whose layout is a `SubcomposeLayout`, as a host's may be. */
        val SubcomposingMarkdown = A2uiMarkdownRenderer { source, style, color, modifier ->
            BoxWithConstraints(modifier) { Text(source, style = style, color = color) }
        }

        /** An image loader whose layout is a `SubcomposeLayout`, as Coil's `SubcomposeAsyncImage` is. */
        val SubcomposingImageLoader = A2uiImageLoader { _, _, _, modifier ->
            BoxWithConstraints(modifier) { Box(Modifier.size(maxWidth, 10.dp)) }
        }

        val TABS_IN_A_ROW = """[
            {"id":"root","component":"Row","children":["tabs","card","beside"]},
            {"id":"tabs","component":"Tabs","tabs":[{"title":"One","child":"one"}]},
            {"id":"one","component":"Text","text":"one"},
            {"id":"card","component":"Card","child":"inner_tabs"},
            {"id":"inner_tabs","component":"Tabs","tabs":[{"title":"Two","child":"two"}]},
            {"id":"two","component":"Text","text":"two"},
            {"id":"beside","component":"Text","text":"beside"}
        ]"""

        val SPACED_COLUMN = """[
            {"id":"root","component":"Column","children":["a","b"],"justify":"spaceBetween"},
            {"id":"a","component":"Text","text":"first"},
            {"id":"b","component":"Text","text":"second"}
        ]"""

        val TWO_LONG_TEXTS = """[
            {"id":"root","component":"Row","children":["a","b"]},
            {"id":"a","component":"Text","text":"$LONG_TEXT_A"},
            {"id":"b","component":"Text","text":"$LONG_TEXT_B"}
        ]"""

        val CARD_OF_BANNER_BESIDE_TEXT = """[
            {"id":"root","component":"Row","children":["card","beside"]},
            {"id":"card","component":"Card","child":"card_col"},
            {"id":"card_col","component":"Column","children":["banner","caption"]},
            {"id":"banner","component":"Image","url":"https://example.invalid/banner.png","variant":"largeFeature"},
            {"id":"caption","component":"Text","text":"in the card"},
            {"id":"beside","component":"Text","text":"beside the card"}
        ]"""

        val TWO_FEEDS = """[
            {"id":"root","component":"Row","children":["a","b"]},
            {"id":"a","component":"Feed","text":"$LONG_TEXT_A"},
            {"id":"b","component":"Feed","text":"$LONG_TEXT_B"}
        ]"""

        val FIELD_BESIDE_LONG_TEXT = """[
            {"id":"root","component":"Row","children":["f","t"]},
            {"id":"f","component":"TextField","label":"Search","value":{"path":"/typed"}},
            {"id":"t","component":"Text","text":"$LONG_TEXT_A"}
        ]"""

        val EMPTY_ROW_AND_WEIGHTED_TEXT = """[
            {"id":"root","component":"Row","children":["empty","w"]},
            {"id":"empty","component":"Row","children":[]},
            {"id":"w","component":"Text","text":"takes the rest","weight":1}
        ]"""

        val WRAPPING_ROW_IN_A_SHORT_COLUMN = """[
            {"id":"root","component":"Column","children":["row","after"]},
            {"id":"row","component":"Row","children":["long","price"],"justify":"spaceBetween"},
            {"id":"long","component":"Text","text":"$LONG_TEXT_B"},
            {"id":"price","component":"Text","text":"${'$'}4.50 per portion, which is a wide price"},
            {"id":"after","component":"Text","text":"after"}
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

        val NESTED_LISTS = """[
            {"id":"root","component":"List","children":["inner"]},
            {"id":"inner","component":"List","children":["deep"]},
            {"id":"deep","component":"Text","text":"deep"}
        ]"""

        val DIVIDER_IN_ROW = """[
            {"id":"root","component":"Row","children":["rule","beside"]},
            {"id":"rule","component":"Divider"},
            {"id":"beside","component":"Text","text":"beside the rule"}
        ]"""

        val IMAGE_IN_ROW = """[
            {"id":"root","component":"Row","children":["pic","beside"]},
            {"id":"pic","component":"Image","url":"https://example.test/cat.png"},
            {"id":"beside","component":"Text","text":"beside the picture"}
        ]"""

        val AVATAR_IN_ROW = """[
            {"id":"root","component":"Row","children":["pic","beside"]},
            {"id":"pic","component":"Image","url":"https://example.test/cat.png","variant":"avatar"},
            {"id":"beside","component":"Text","text":"beside the avatar"}
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

        val LOCAL_FILE_IMAGE = """[
            {"id":"root","component":"Column","children":["pic"]},
            {"id":"pic","component":"Image","url":"file:///etc/passwd","description":"a private file"}
        ]"""

        val UNDRAWN = """[
            {"id":"root","component":"Column","children":["chart"]},
            {"id":"chart","component":"Sparkline","values":[1,2,3]}
        ]"""
    }
}
