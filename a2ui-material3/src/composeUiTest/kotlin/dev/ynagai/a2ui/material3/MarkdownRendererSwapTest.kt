package dev.ynagai.a2ui.material3

import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ynagai.a2ui.compose.A2uiSurface
import kotlin.test.Test

/**
 * A host swapping [LocalA2uiMarkdownRenderer] while a `Text` is drawn: the default
 * [A2uiMarkdownRenderer.Inline], which remembers its parse, replaced by one handing a capturing
 * lambda to a non-inline composable -- the `fun interface` swap of `RendererSwapTest` (#31), behind
 * [TextRenderer]'s call instead.
 *
 * **Mutation-checked** on `macosArm64`: with that call unkeyed, this dies with "Test running
 * process exited unexpectedly". On JVM it passes either way.
 */
@OptIn(ExperimentalTestApi::class)
class MarkdownRendererSwapTest {
    @Test
    fun a_markdown_renderer_may_replace_the_default_at_runtime() = runComposeUiTest {
        var markdown by mutableStateOf(A2uiMarkdownRenderer.Inline)
        val renderer = hostSwapSurfaceWith("""{"id":"root","component":"Text","text":"before"}""")
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalA2uiMarkdownRenderer provides markdown) {
                    A2uiSurface(renderer, "s", Material3Components.Basic)
                }
            }
        }
        onNodeWithText("before").assertIsDisplayed()

        markdown = A2uiMarkdownRenderer { source, _, _, _ ->
            val children = remember(source) { listOf("$source, after") }
            HostSwapWrapper { children.forEach { BasicText(it) } }
        }

        onNodeWithText("before, after").assertIsDisplayed()
        // Replaced, not composed alongside, or a swap that never happened would keep this green.
        onNodeWithText("before").assertDoesNotExist()
    }
}
