package dev.ynagai.a2ui.material3

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ynagai.a2ui.compose.A2uiRenderer
import dev.ynagai.a2ui.compose.A2uiSurface
import dev.ynagai.a2ui.compose.BasicCatalog
import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.AgentToRendererMessage
import kotlin.test.Test

/**
 * A `Card` arriving in a surface that is redrawn inside a scrolling parent.
 *
 * This is the shape that segfaulted Kotlin/Native for as long as [CardRenderer] was built on
 * Material 3's `OutlinedCard` -- see that renderer's own note for the four conditions and for what
 * each of them alone does not do. The crash was an `EXC_BAD_ACCESS` inside
 * `AtomicInt.compareAndSet` with no unwindable stack, so nothing raised and nothing was reported:
 * the test process died, and on macOS the runner said only "signal 11".
 *
 * A test rather than a comment because of how it failed. A renderer written back onto a Material 3
 * `Surface` would look correct on JVM and on both web targets, pass every other test in this
 * module, and take an iOS app down in the one placement this library documents as usual. This is
 * the only thing standing between that change and a release.
 *
 * Reaching the assertions at all is most of the claim, but not all of it: they also have to say
 * that the swap *happened*, or a surface that quietly stopped replacing anything would keep this
 * pin green while the condition it exists to hold went away.
 */
@OptIn(ExperimentalTestApi::class)
class CardScrollSwapTest {
    @Test
    fun a_card_may_replace_a_drawn_surface_inside_a_scrolling_parent() = runComposeUiTest {
        var renderer by mutableStateOf(surfaceWith(TEXT_ROOT))
        setContent {
            MaterialTheme {
                // Unbounded height: the placement a host gives a surface it expects to scroll, and
                // where the crash was found. Not where it is confined, though -- see the note on
                // `CardRenderer`: with the `verticalScroll` taken off, an `OutlinedCard`-based
                // `CardRenderer` segfaults here just the same. Kept because it is the placement
                // this library documents as usual, not because the height is load-bearing.
                Box(Modifier.requiredSize(400.dp, 600.dp).verticalScroll(rememberScrollState())) {
                    A2uiSurface(renderer, SURFACE_ID, Material3Components.Basic)
                }
            }
        }
        onNodeWithText("before").assertIsDisplayed()

        renderer = surfaceWith(CARD_ROOT)

        onNodeWithText("in a card").assertIsDisplayed()
        // *Replaced*, which is the word in this test's name and the condition the crash needs.
        // Without this the test also passes on a surface that composed the new root alongside the
        // old one, and a swap that never happened is exactly the way this pin would go quiet.
        onNodeWithText("before").assertDoesNotExist()
    }

    private fun surfaceWith(components: String) = A2uiRenderer().also { renderer ->
        renderer.applyAll(
            listOf(
                """{"version":"v1.0","createSurface":{"surfaceId":"$SURFACE_ID","catalogId":"${BasicCatalog.id}"}}""",
                """{"version":"v1.0","updateComponents":{"surfaceId":"$SURFACE_ID","components":[$components]}}""",
            ).map { A2uiJson.strict.decodeFromString<AgentToRendererMessage>(it) }
        )
    }

    private companion object {
        const val SURFACE_ID = "s"
        const val TEXT_ROOT = """{"id":"root","component":"Text","text":"before"}"""

        /**
         * The child matters: a `Card` drawn with a literal child never crashed. It has to be one
         * `RenderChild` resolves, which is what every real payload's card holds.
         */
        const val CARD_ROOT =
            """{"id":"root","component":"Card","child":"inner"},""" +
                """{"id":"inner","component":"Text","text":"in a card"}"""
    }
}
