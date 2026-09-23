package dev.ynagai.a2ui.compose

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.AgentToRendererMessage
import kotlin.test.Test

/**
 * A host swapping the [A2uiPlaceholder] it hands [A2uiSurface] while the surface is drawn -- the
 * same `fun interface` swap as [RendererSwapTest]'s, behind the placeholder's call sites instead
 * of the renderer's (#31).
 *
 * The placeholders are the reduction's shapes: the outgoing one remembers, the arriving one hands
 * a capturing lambda to a non-inline composable. An unknown component type reaches the
 * `UnknownType` call site; the others in [A2uiComponent], and the one in `RenderChild`, go through
 * the same keyed helper.
 *
 * **Mutation-checked** on `macosArm64`: with the placeholder's calls unkeyed, this dies with "Test
 * running process exited unexpectedly". On JVM it passes either way.
 */
@OptIn(ExperimentalTestApi::class)
class PlaceholderSwapTest {
    @Test
    fun a_placeholder_passing_a_capturing_lambda_may_replace_one_that_remembered() = runComposeUiTest {
        var placeholder by mutableStateOf(Remembering)
        val renderer = A2uiRenderer().also { renderer ->
            renderer.applyAll(
                listOf(
                    """{"version":"v1.0","createSurface":{"surfaceId":"s","catalogId":"${BasicCatalog.id}"}}""",
                    """{"version":"v1.0","updateComponents":{"surfaceId":"s","components":[{"id":"root","component":"Nope"}]}}""",
                ).map { A2uiJson.strict.decodeFromString<AgentToRendererMessage>(it) }
            )
        }
        setContent { A2uiSurface(renderer, "s", ComponentRegistry(emptyMap()), placeholder = placeholder) }
        onNodeWithText("before").assertIsDisplayed()

        placeholder = Capturing

        onNodeWithText("after").assertIsDisplayed()
        // Replaced, not composed alongside, or a swap that never happened would keep this green.
        onNodeWithText("before").assertDoesNotExist()
    }

    private companion object {
        val Remembering = A2uiPlaceholder { reason, _ ->
            val text = remember(reason, "text") { "before" }
            val variant = remember(reason, "variant") { "x" }
            BasicText(text + variant.take(0))
        }
        val Capturing = A2uiPlaceholder { reason, _ ->
            val children = remember(reason, "child") { listOf("after") }
            Wrapper { children.forEach { BasicText(it) } }
        }
    }
}

@Composable
private fun Wrapper(content: @Composable () -> Unit) {
    content()
}
