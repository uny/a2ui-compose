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
 * A host swapping [LocalA2uiImageLoader] while an `Image` is drawn -- the same swap as
 * [MarkdownRendererSwapTest], behind [ImageRenderer]'s call. `VideoRenderer`'s poster goes through
 * the same interface and is keyed the same way; this pins the one that every image payload reaches.
 *
 * **Mutation-checked** on `macosArm64`: with [ImageRenderer]'s call unkeyed, this dies with "Test
 * running process exited unexpectedly". On JVM it passes either way.
 */
@OptIn(ExperimentalTestApi::class)
class ImageLoaderSwapTest {
    @Test
    fun an_image_loader_may_replace_another_at_runtime() = runComposeUiTest {
        var loader by mutableStateOf(
            A2uiImageLoader { url, _, _, _ ->
                val text = remember(url, "text") { "before" }
                val variant = remember(url, "variant") { "x" }
                BasicText(text + variant.take(0))
            },
        )
        val renderer = hostSwapSurfaceWith("""{"id":"root","component":"Image","url":"https://example.com/a.png"}""")
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalA2uiImageLoader provides loader) {
                    A2uiSurface(renderer, "s", Material3Components.Basic)
                }
            }
        }
        onNodeWithText("before").assertIsDisplayed()

        loader = A2uiImageLoader { url, _, _, _ ->
            val children = remember(url, "child") { listOf("after") }
            HostSwapWrapper { children.forEach { BasicText(it) } }
        }

        onNodeWithText("after").assertIsDisplayed()
        // Replaced, not composed alongside, or a swap that never happened would keep this green.
        onNodeWithText("before").assertDoesNotExist()
    }
}
