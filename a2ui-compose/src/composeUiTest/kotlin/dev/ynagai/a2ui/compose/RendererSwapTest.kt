package dev.ynagai.a2ui.compose

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.AgentToRendererMessage
import kotlin.test.Test

/**
 * A component replaced by one of another type, where the arriving renderer hands a capturing
 * content lambda to a composable -- the shape that segfaulted Kotlin/Native until [A2uiComponent]
 * keyed its `Render` call on the renderer (#31).
 *
 * [ComponentRenderer] is a `fun interface`, and every component reaches its renderer through the
 * one `Render` call at the end of [A2uiComponent]. Swapping the implementation behind that call
 * between compositions, with no key around it, crashed macOS and iOS -- never JVM or either web
 * target -- when three more things held: the outgoing renderer `remember`ed something, the
 * arriving one passed a `@Composable` lambda to a non-inline composable, and that lambda captured
 * something. The same swap reduced to nothing but Compose (`runtime`, `foundation`, `ui-test`)
 * crashes just the same, so this library is where it was met, not where it lives.
 *
 * **Those conditions are necessary, not sufficient**, which is why the renderer below is the
 * reduction's shape and not Material 3's. A `Text` → `Button` swap meets all of them and passes;
 * so does a host renderer built on `OutlinedCard` with a `Column` inside, under this file's
 * harness -- though `a2ui-material3`'s own `CardRenderer` in that form, under `CardScrollSwapTest`,
 * crashes without the key. What separates them is not known. The shape here is the one that
 * reliably dies, so it is the one that can say the key is doing its job.
 *
 * **Mutation-checked** on `macosArm64`: with the `key` taken off [A2uiComponent]'s `Render` call,
 * this dies with "Test running process exited unexpectedly". On JVM it passes either way, so it
 * only guards anything on the native targets.
 */
@OptIn(ExperimentalTestApi::class)
class RendererSwapTest {
    @Test
    fun a_renderer_passing_a_capturing_lambda_may_replace_one_that_remembered() =
        swapTextFor(
            ComponentRenderer { scope, _ ->
                val children = scope.rememberChildren("child")
                Wrapper { children.forEach { scope.RenderChild(it) } }
            },
        )

    private fun swapTextFor(card: ComponentRenderer) = runComposeUiTest {
        val registry = ComponentRegistry(mapOf("Text" to RememberingText, "Card" to card))
        var renderer by mutableStateOf(surfaceWith(TEXT_ROOT))
        setContent { A2uiSurface(renderer, SURFACE_ID, registry) }
        onNodeWithText("before").assertIsDisplayed()

        renderer = surfaceWith(CARD_ROOT)

        onNodeWithText("in a card").assertIsDisplayed()
        // Replaced, not composed alongside: a surface that stopped swapping anything would keep
        // this green while the condition it exists for went away.
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
        const val CARD_ROOT =
            """{"id":"root","component":"Card","child":"inner"},""" +
                """{"id":"inner","component":"Text","text":"in a card"}"""

        /** Two remembered values, as `a2ui-material3`'s `TextRenderer` has. */
        val RememberingText = ComponentRenderer { scope, _ ->
            val text = scope.rememberString("text")
            val variant = scope.rememberString("variant")
            BasicText(text.orEmpty() + variant.orEmpty().take(0))
        }
    }
}

/** Non-inline, as `Surface` is: the lambda handed to it is a remembered object, not inlined code. */
@Composable
private fun Wrapper(content: @Composable () -> Unit) {
    content()
}
