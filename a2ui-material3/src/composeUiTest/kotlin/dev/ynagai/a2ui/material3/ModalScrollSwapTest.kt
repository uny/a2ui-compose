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
 * give up, and draws its content through `RenderChild`. So the argument that a modal is unaffected
 * has to come from somewhere else, and the code comment that made it named the `Dialog`: its own
 * window bounds the content, so the unbounded height the crash was thought to need never holds.
 *
 * **That argument does not survive being run, and this test does not rest on it.** A card built the
 * old way -- Material 3's `OutlinedCard`, its child through `RenderChild` -- registered over `Card`
 * as an open modal's content segfaults macOS in a bounded host as readily as in a scrolling one, so
 * the `Dialog` is not what keeps this green. The same swap with that card and no modal anywhere, in
 * a fixed 400x600 box, dies too, which puts the fault outside the height condition altogether. What
 * actually leaves `Modal` safe is narrower and worth saying plainly: **no component the catalog
 * draws is built on a `Surface` any more**, [CardRenderer] having been the last one.
 *
 * So the two tests below pass for that reason, and this file is a canary that cannot currently
 * fail -- with the shipped catalog there is nothing left to supply the `Surface` condition. It
 * earns its place against the day something does: a host registering its own `Surface`-based
 * renderer, which `ModalRenderer`'s note invites, or a renderer here being rebuilt on one. A modal
 * is where that would land first and least visibly. Until then read this as coverage of the modal
 * *swap* path -- open, replace, assert the content changed -- and not as evidence about a boundary.
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

        /**
         * A `Card`, still: it is what a real modal's content holds, and it was the component the
         * crash needed. It no longer carries that condition -- [CardRenderer] is a bordered `Box`
         * now -- so swapping it for a `Text` here would exercise the same path.
         */
        val REPLACEMENT =
            """{"version":"v1.0","updateComponents":{"surfaceId":"$SURFACE_ID","components":[""" +
                """{"id":"body","component":"Card","child":"inner"},""" +
                """{"id":"inner","component":"Text","text":"second body"}]}}"""
    }
}
