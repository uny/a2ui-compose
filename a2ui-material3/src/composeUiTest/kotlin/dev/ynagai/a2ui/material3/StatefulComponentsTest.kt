package dev.ynagai.a2ui.material3

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ynagai.a2ui.compose.A2uiRenderer
import dev.ynagai.a2ui.compose.A2uiSurface
import dev.ynagai.a2ui.compose.BasicCatalog
import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.ActionMessage
import dev.ynagai.a2ui.core.protocol.AgentToRendererMessage
import dev.ynagai.a2ui.core.protocol.RendererToAgentMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * `Tabs` and `Modal`, whose state is the renderer's, and the two media components, whose is not.
 *
 * The four are here together because they are the four the registry gained last, but the claims
 * split cleanly: the first two are about a gesture changing what is on screen without anything
 * reaching the data model, and the last two are about drawing a medium this module cannot play
 * without pretending otherwise.
 */
@OptIn(ExperimentalTestApi::class)
class StatefulComponentsTest {
    // ---- Tabs -----------------------------------------------------------------------------

    @Test
    fun a_tab_strip_draws_every_title_and_only_the_selected_child() = runComposeUiTest {
        setContent { Surface(rendererFor(TABS)) }
        onNodeWithText("Overview").assertIsDisplayed()
        onNodeWithText("Details").assertIsDisplayed()
        onNodeWithText("first tab").assertIsDisplayed()
        // The claim that costs something to get wrong. A renderer drawing every child would look
        // right on a strip of one and wrong on every other, and nothing would say so.
        onAllNodes(hasText("second tab")).assertCountEquals(0)
    }

    @Test
    fun tapping_a_header_swaps_which_child_is_drawn() = runComposeUiTest {
        setContent { Surface(rendererFor(TABS)) }
        onNodeWithText("Details").performClick()
        onNodeWithText("second tab").assertIsDisplayed()
        onAllNodes(hasText("first tab")).assertCountEquals(0)
    }

    @Test
    fun the_selected_header_reports_itself_as_selected() = runComposeUiTest {
        setContent { Surface(rendererFor(TABS)) }
        // Not a colour assertion. The indicator and the bold weight are the visual half; this is
        // the half a screen reader gets, and a strip that moved its underline without moving the
        // selection state would pass a pixel test and fail a blind user.
        onAllNodes(isSelectable())[0].assertIsSelected()
        onNodeWithText("Details").performClick()
        onAllNodes(isSelectable())[1].assertIsSelected()
        // The half that a strip reporting every tab as selected would still pass without.
        onAllNodes(isSelectable())[0].assertIsNotSelected()
    }

    @Test
    fun a_tab_title_bound_to_the_data_model_resolves() = runComposeUiTest {
        setContent { Surface(rendererFor(BOUND_TABS)) }
        onNodeWithText("From the model").assertIsDisplayed()
    }

    @Test
    fun a_tab_whose_title_will_not_resolve_keeps_its_place() = runComposeUiTest {
        // The one error in this renderer that draws the *wrong* content rather than none. A tab
        // is paired with its child by position -- `tabs/<i>/child` -- so a header dropped for an
        // unresolvable title would shift every header after it while the children stayed put, and
        // the second tab would draw the first one's body. Kept with an empty title instead, which
        // is a blank header rather than a lie.
        setContent { Surface(rendererFor(UNRESOLVED_TITLE_TABS)) }
        onAllNodes(isSelectable()).assertCountEquals(2)
        onNodeWithText("Second").performClick()
        onNodeWithText("second tab").assertIsDisplayed()
    }

    @Test
    fun a_tab_strip_stops_at_the_bound_rather_than_composing_whatever_arrived() = runComposeUiTest {
        // `tabs` is a property, so the surface's instance budget does not reach it -- the headers
        // would all compose. See `MAX_TABS`, which is the bound the budget cannot supply.
        setContent { Surface(rendererFor(manyTabs(count = 130))) }
        onAllNodes(isSelectable()).assertCountEquals(100)
    }

    // ---- Modal ----------------------------------------------------------------------------

    @Test
    fun a_closed_modal_draws_its_trigger_and_nothing_of_its_content() = runComposeUiTest {
        setContent { Surface(rendererFor(MODAL)) }
        onNodeWithText("Open").assertIsDisplayed()
        onAllNodes(hasText("the content")).assertCountEquals(0)
    }

    @Test
    fun tapping_the_trigger_opens_the_content_in_a_dialog() = runComposeUiTest {
        setContent { Surface(rendererFor(MODAL)) }
        onNodeWithText("Open").performClick()
        // Grabbed by text rather than through `isDialog()`, which finds nothing on skiko even
        // while the dialog is open -- the composition simply gains a second root.
        onNodeWithText("the content").assertIsDisplayed()
    }

    @Test
    fun the_close_button_dismisses_the_dialog() = runComposeUiTest {
        setContent { Surface(rendererFor(MODAL)) }
        onNodeWithText("Open").performClick()
        onNodeWithText("the content").assertIsDisplayed()
        onNodeWithContentDescription(A2uiStrings().close).performClick()
        onAllNodes(hasText("the content")).assertCountEquals(0)
    }

    @Test
    fun the_close_button_takes_its_name_from_the_host() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalA2uiStrings provides A2uiStrings(close = "閉じる")) {
                Surface(rendererFor(MODAL))
            }
        }
        onNodeWithText("Open").performClick()
        onNodeWithContentDescription("閉じる").assertIsDisplayed()
    }

    @Test
    fun the_triggers_own_action_does_not_dispatch_when_the_modal_takes_the_tap() = runComposeUiTest {
        // The documented cost of intercepting in the `Initial` pass, asserted so it stays a
        // decision rather than becoming a surprise. The trigger in the corpus's own `36_modal`
        // carries an `openModalEvent`, and this is what happens to it.
        val sent = mutableListOf<RendererToAgentMessage>()
        setContent { Surface(rendererFor(MODAL), onMessage = { sent += it }) }
        onNodeWithText("Open").performClick()
        onNodeWithText("the content").assertIsDisplayed()
        assertEquals(emptyList(), sent.filterIsInstance<ActionMessage>())
    }

    @Test
    fun a_scroll_that_begins_on_the_trigger_scrolls_instead_of_opening() = runComposeUiTest {
        // The interception takes the *release* and nothing before it, and this is the reason. An
        // earlier version consumed the press and every move in the `Initial` pass, which made the
        // trigger's whole area unscrollable -- a `List` is a `verticalScroll` `Column`, so a
        // `Modal` inside one had a dead zone -- and then opened the dialog when the finger that
        // had been trying to scroll lifted. Both halves are asserted, because fixing only the
        // scroll would leave the surface opening a modal at the end of every swipe.
        lateinit var scroll: ScrollState
        setContent {
            scroll = rememberScrollState()
            MaterialTheme {
                Column(Modifier.verticalScroll(scroll)) {
                    Surface(rendererFor(MODAL))
                    Spacer(Modifier.fillMaxWidth().height(TALLER_THAN_THE_WINDOW))
                }
            }
        }
        onNodeWithText("Open").performTouchInput { swipeUp() }
        assertTrue(scroll.value > 0, "a swipe from the trigger should scroll: ${scroll.value}")
        onAllNodes(hasText("the content")).assertCountEquals(0)
    }

    @Test
    fun a_press_that_slides_off_the_trigger_does_not_open() = runComposeUiTest {
        // The other half of taking only the release: `waitForUpOrCancellation` gives up when the
        // pointer leaves this node, so a press that slid off the trigger before lifting is a
        // cancelled press rather than a tap. The loop this replaced counted any release anywhere,
        // which meant a modal could open from a finger that had already left it.
        setContent { Surface(rendererFor(MODAL)) }
        onNodeWithText("Open").performTouchInput {
            down(center)
            moveTo(center + Offset(0f, height * 4f))
            up()
        }
        onAllNodes(hasText("the content")).assertCountEquals(0)
    }

    // ---- Video and AudioPlayer -------------------------------------------------------------

    @Test
    fun an_audio_player_shows_its_description_and_is_named_by_it() = runComposeUiTest {
        setContent { Surface(rendererFor(AUDIO)) }
        onNodeWithText("Episode 12").assertIsDisplayed()
        // The same string twice on purpose: visible for a sighted reader, and the accessible name
        // for everyone else. A bar with a transport glyph and no name is what this rules out.
        onNodeWithContentDescription("Episode 12").assertIsDisplayed()
    }

    @Test
    fun a_video_hands_its_poster_to_the_loader_and_never_its_url() = runComposeUiTest {
        val asked = mutableListOf<String>()
        setContent {
            CompositionLocalProvider(LocalA2uiImageLoader provides recordingLoader(asked)) {
                Surface(rendererFor(VIDEO))
            }
        }
        // The poster, and only the poster. The `url` is a video this module cannot play, and
        // handing it to an image loader would be a fetch made on the agent's say-so for no reason.
        assertEquals(listOf("https://example.test/poster.png"), asked)
    }

    @Test
    fun a_video_poster_with_a_refused_scheme_never_reaches_the_loader() = runComposeUiTest {
        val asked = mutableListOf<String>()
        setContent {
            CompositionLocalProvider(LocalA2uiImageLoader provides recordingLoader(asked)) {
                Surface(rendererFor(LOCAL_POSTER_VIDEO))
            }
        }
        // `Image`'s allowlist, applied to the same loader through a different component. A
        // `file://` poster that resolved would tell the agent from the pixels whether a path on
        // the viewer's machine exists.
        assertEquals(emptyList(), asked)
    }

    @Test
    fun a_video_without_a_loader_still_occupies_its_frame() = runComposeUiTest {
        // Nothing inside the frame is named, so the claim is made from the outside: the caption
        // under a video sits lower than the same caption without one. A `Video` that collapsed to
        // nothing would draw a surface whose layout jumped the moment a host wired up a loader.
        setContent { Surface(rendererFor(CAPTION_ONLY)) }
        val alone = onNodeWithText("caption").fetchSemanticsNode().boundsInRoot
        setContent { Surface(rendererFor(VIDEO_AND_CAPTION)) }
        val below = onNodeWithText("caption").fetchSemanticsNode().boundsInRoot
        assertTrue(
            below.top > alone.top + MIN_VIDEO_HEIGHT,
            "the frame should take room even with nothing to play: $below vs $alone",
        )
    }

    @Test
    fun an_audio_player_says_its_description_once_rather_than_twice() = runComposeUiTest {
        setContent { Surface(rendererFor(AUDIO)) }
        // `semantics` without `mergeDescendants` leaves the bar's description and the `Text`
        // under it as two nodes carrying the same string, and a screen reader stops at both and
        // says it twice. One count across *both* properties, because the unmerged tree holds one
        // node of each kind -- a test that counted them separately would find one of each and
        // pass, which is exactly what the first version of this test did.
        onAllNodes(hasText("Episode 12") or hasContentDescription("Episode 12"))
            .assertCountEquals(1)
    }

    @Test
    fun a_video_poster_is_given_the_whole_frame_to_crop_into() = runComposeUiTest {
        // What the loader is handed, measured rather than assumed. A width-only modifier leaves a
        // loader resolving its own height -- for an image that is the source's ratio, and for the
        // empty box this stand-in draws it is nothing at all -- so the poster stops filling the
        // 16:9 frame it is supposed to be the backdrop of. Asserted through a loader that draws
        // the modifier it was given, because the recording loader discards it.
        setContent {
            CompositionLocalProvider(LocalA2uiImageLoader provides drawingLoader()) {
                Surface(rendererFor(VIDEO), width = PHONE_WIDTH)
            }
        }
        val poster = onNodeWithTag(POSTER).fetchSemanticsNode().boundsInRoot
        assertTrue(poster.height > 0f, "the poster should fill the frame, not collapse: $poster")
        assertTrue(
            abs(poster.width / poster.height - VIDEO_RATIO) < RATIO_TOLERANCE,
            "the poster should be the frame's own 16:9, not the source's ratio: $poster",
        )
    }

    @Test
    fun a_video_does_not_take_the_row_it_sits_in() = runComposeUiTest {
        // `claimsMainAxis`'s new arm, asserted on the sibling that disappears without it: a
        // `Video` frame is a `fillMaxWidth`, and inside a `Row` a `fillMax*` resolves against the
        // width the *parent* offered rather than against this child's share of it. Drawn at a
        // phone's width for the same reason `an_image_does_not_take_the_row_it_sits_in` is --
        // there is no cap here to cover for a missing share on a wide window either.
        setContent { Surface(rendererFor(VIDEO_IN_ROW), width = PHONE_WIDTH) }
        val sibling = onNodeWithText("beside the video").fetchSemanticsNode().boundsInRoot
        assertTrue(sibling.width > 0f, "the video should not have taken the row: $sibling")
    }

    // ---- Harness ---------------------------------------------------------------------------

    @Composable
    private fun Surface(
        renderer: A2uiRenderer,
        onMessage: (RendererToAgentMessage) -> Unit = {},
    ) {
        MaterialTheme {
            A2uiSurface(
                renderer = renderer,
                surfaceId = SURFACE,
                registry = Material3Components.Basic,
                onMessage = onMessage,
            )
        }
    }

    /** The surface drawn at a chosen width, for the claims that are about a share of a row. */
    @Composable
    private fun Surface(renderer: A2uiRenderer, width: Dp) {
        Box(Modifier.size(width, SURFACE_HEIGHT)) { Surface(renderer) }
    }

    /** A loader that draws nothing but the modifier it was handed, so its size can be measured. */
    private fun drawingLoader() =
        A2uiImageLoader { _, _, _: ContentScale, modifier: Modifier ->
            Box(modifier.testTag(POSTER))
        }

    private fun recordingLoader(into: MutableList<String>) =
        A2uiImageLoader { url, _, _: ContentScale, _: Modifier -> into += url }

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

        val DATA = """{"title":"From the model","episode":"Episode 12"}"""

        val TABS = """[
            {"id":"root","component":"Tabs","tabs":[
                {"title":"Overview","child":"one"},
                {"title":"Details","child":"two"}
            ]},
            {"id":"one","component":"Text","text":"first tab"},
            {"id":"two","component":"Text","text":"second tab"}
        ]"""

        val BOUND_TABS = """[
            {"id":"root","component":"Tabs","tabs":[
                {"title":{"path":"/title"},"child":"one"}
            ]},
            {"id":"one","component":"Text","text":"first tab"}
        ]"""

        /** The first tab's title binds to a path the data model does not have. */
        val UNRESOLVED_TITLE_TABS = """[
            {"id":"root","component":"Tabs","tabs":[
                {"title":{"path":"/nothing/here"},"child":"one"},
                {"title":"Second","child":"two"}
            ]},
            {"id":"one","component":"Text","text":"first tab"},
            {"id":"two","component":"Text","text":"second tab"}
        ]"""

        val MODAL = """[
            {"id":"root","component":"Modal","trigger":"open","content":"body"},
            {"id":"open","component":"Button","child":"open_label",
             "action":{"event":{"name":"openModalEvent"}}},
            {"id":"open_label","component":"Text","text":"Open"},
            {"id":"body","component":"Text","text":"the content"}
        ]"""

        val AUDIO = """[
            {"id":"root","component":"AudioPlayer","url":"https://example.test/a.mp3",
             "description":{"path":"/episode"}}
        ]"""

        val VIDEO = """[
            {"id":"root","component":"Video","url":"https://example.test/v.mp4",
             "posterUrl":"https://example.test/poster.png"}
        ]"""

        /** Less than a 16:9 frame at any width the harness runs at, and more than a caption. */
        const val MIN_VIDEO_HEIGHT = 80f

        val CAPTION_ONLY = """[
            {"id":"root","component":"Column","children":["cap"]},
            {"id":"cap","component":"Text","text":"caption"}
        ]"""

        val VIDEO_AND_CAPTION = """[
            {"id":"root","component":"Column","children":["vid","cap"]},
            {"id":"vid","component":"Video","url":"https://example.test/v.mp4"},
            {"id":"cap","component":"Text","text":"caption"}
        ]"""

        val LOCAL_POSTER_VIDEO = """[
            {"id":"root","component":"Video","url":"https://example.test/v.mp4",
             "posterUrl":"file:///etc/hosts"}
        ]"""

        val VIDEO_IN_ROW = """[
            {"id":"root","component":"Row","children":["vid","cap"]},
            {"id":"vid","component":"Video","url":"https://example.test/v.mp4"},
            {"id":"cap","component":"Text","text":"beside the video"}
        ]"""

        const val POSTER = "poster"

        /** The frame's own ratio, which is what the poster inside it should measure. */
        const val VIDEO_RATIO = 16f / 9f

        /** Slack for the rounding a dp-to-pixel frame picks up at any one density. */
        const val RATIO_TOLERANCE = 0.05f

        /** Every phone, and narrow enough that a missing share starves the sibling outright. */
        val PHONE_WIDTH = 320.dp

        /** Tall enough that nothing under test is height-constrained. */
        val SURFACE_HEIGHT = 600.dp

        /** Enough content below the surface that the scroll container has somewhere to go. */
        val TALLER_THAN_THE_WINDOW = 4_000.dp

        /** A payload with more tabs than [MAX_TABS] lets through -- see the bound's own test. */
        fun manyTabs(count: Int): String {
            val tabs = (0 until count).joinToString(",") {
                """{"title":"t$it","child":"c"}"""
            }
            return """[
                {"id":"root","component":"Tabs","tabs":[$tabs]},
                {"id":"c","component":"Text","text":"body"}
            ]"""
        }
    }
}
