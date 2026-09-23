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
 * An open `Modal` whose content is replaced by a `Card` -- the swap [CardScrollSwapTest] makes,
 * landing inside a dialog.
 *
 * It segfaulted Kotlin/Native on macOS until `A2uiComponent` keyed its `Render` call on the
 * renderer (#31): a [CardRenderer] built on Material 3's `OutlinedCard` arriving as the content,
 * in a scrolling host and in a bounded one alike. The `Dialog` never protected it, whatever an
 * earlier note argued -- its window bounds the content's height, and height was not a condition.
 *
 * So these two tests hold the key on the modal's path, which a modal needs separately: its content
 * is drawn in a `Dialog`'s own window, and a crash there is the one a host sees least. Both
 * are **mutation-checked** on `macosArm64`: with the `key` taken off, each dies with "Test running
 * process exited unexpectedly" when run alone.
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
         * A `Card`, because it is what a real modal's content holds and because an `OutlinedCard`
         * arriving is the shape the key has to hold. A `Text` arriving hands no content lambda to
         * anything, so it would not meet the conditions the key is there for.
         */
        val REPLACEMENT =
            """{"version":"v1.0","updateComponents":{"surfaceId":"$SURFACE_ID","components":[""" +
                """{"id":"body","component":"Card","child":"inner"},""" +
                """{"id":"inner","component":"Text","text":"second body"}]}}"""
    }
}
