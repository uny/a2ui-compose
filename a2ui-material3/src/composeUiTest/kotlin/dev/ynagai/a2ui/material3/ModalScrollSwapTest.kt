package dev.ynagai.a2ui.material3

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ynagai.a2ui.compose.A2uiRenderer
import dev.ynagai.a2ui.compose.A2uiSurface
import dev.ynagai.a2ui.compose.BasicCatalog
import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.AgentToRendererMessage
import kotlin.test.Test

/**
 * An open `Modal` whose content is replaced -- the other renderer that could have met the
 * conditions [CardScrollSwapTest] pins.
 *
 * `ModalRenderer` reaches the same non-interactive Material 3 `Surface` that `CardRenderer` had to
 * give up, and draws its content through `RenderChild`. That is three of the four conditions. The
 * fourth -- an unbounded height -- was argued away in a code comment and had nothing behind it:
 * the content sits inside a `Dialog`, whose own window is what bounds it.
 *
 * This settles the argument by running it, in a scrolling host as well as a bounded one, with the
 * replacement being a `Card` -- the component that did crash. Both pass, so the `Dialog` boundary
 * holds and `Modal` needs no workaround of its own.
 *
 * **What this pins is the boundary, not the modal.** A future `ModalRenderer` that drew its content
 * outside a `Dialog`, or wrapped it in a scrollable, would meet all four conditions and take macOS
 * and iOS down with a `signal 11` that no other test in this module would notice.
 */
@OptIn(ExperimentalTestApi::class)
class ModalScrollSwapTest {
    @Test
    fun an_open_modal_may_have_its_content_replaced_by_a_card() =
        replaceModalContent(inAScrollingHost = true)

    @Test
    fun an_open_modal_may_have_its_content_replaced_inside_a_bounded_host() =
        replaceModalContent(inAScrollingHost = false)

    private fun replaceModalContent(inAScrollingHost: Boolean) = runComposeUiTest {
        val renderer = A2uiRenderer()
        renderer.applyAll(listOf(CREATE, COMPONENTS).map(::decode))
        setContent {
            MaterialTheme {
                val host = Modifier.requiredSize(400.dp, 600.dp).let {
                    if (inAScrollingHost) it.verticalScroll(rememberScrollState()) else it
                }
                Box(host) { A2uiSurface(renderer, SURFACE_ID, Material3Components.Basic) }
            }
        }
        onNodeWithText("open me").performClick()
        onNodeWithText("first body").assertIsDisplayed()

        renderer.apply(decode(REPLACEMENT))

        onNodeWithText("second body").assertIsDisplayed()
        // Replaced rather than added, as in `CardScrollSwapTest`: a modal that composed the new
        // content beside the old would keep this green while the condition went away.
        onNodeWithText("first body").assertDoesNotExist()
    }

    private fun decode(text: String) =
        A2uiJson.strict.decodeFromString<AgentToRendererMessage>(text)

    private companion object {
        const val SURFACE_ID = "s"

        val CREATE =
            """{"version":"v1.0","createSurface":{"surfaceId":"$SURFACE_ID","catalogId":"${BasicCatalog.id}"}}"""

        val COMPONENTS =
            """{"version":"v1.0","updateComponents":{"surfaceId":"$SURFACE_ID","components":[""" +
                """{"id":"root","component":"Modal","trigger":"tr","content":"body"},""" +
                """{"id":"tr","component":"Text","text":"open me"},""" +
                """{"id":"body","component":"Text","text":"first body"}]}}"""

        /** A `Card`, deliberately: it is the component the crash needed. */
        val REPLACEMENT =
            """{"version":"v1.0","updateComponents":{"surfaceId":"$SURFACE_ID","components":[""" +
                """{"id":"body","component":"Card","child":"inner"},""" +
                """{"id":"inner","component":"Text","text":"second body"}]}}"""
    }
}
