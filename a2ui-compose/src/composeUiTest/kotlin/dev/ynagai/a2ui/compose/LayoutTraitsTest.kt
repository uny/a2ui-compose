package dev.ynagai.a2ui.compose

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.AgentToRendererMessage
import dev.ynagai.a2ui.core.surface.RenderLimits
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What [layoutTraitsOf] answers for a wrapper, read straight off the walk rather than through a
 * layout that would have to draw a difference.
 */
@OptIn(ExperimentalTestApi::class)
class LayoutTraitsTest {
    @Test
    fun a_wrapper_reached_again_at_the_depth_bound_is_not_answered_from_higher_up() = runComposeUiTest {
        // `x` wraps a filler, and `top` reaches it twice: directly, one level down, and through a
        // chain that arrives at it exactly at the depth bound, where the walk stops and takes the
        // declaration. The chain is content-sized, so `top` is. Remembering `x` by id alone
        // answered the second visit from the first, and `top` came out a filler whose deep branch
        // the surface never draws.
        val answers = mutableListOf<LayoutTraits>()
        val registry = ComponentRegistry(
            mapOf(
                "Row" to ComponentRenderer { scope, _ ->
                    for (child in scope.rememberAllChildren()) {
                        answers += scope.layoutTraitsOf(child, LocalA2uiRegistry.current, LayoutAxis.Horizontal)
                    }
                },
                "Column" to ComponentRenderer { _, _ -> },
                "Divider" to ComponentRenderer(LayoutTraits.Fill) { _, _ -> },
            ),
        )
        setContent { A2uiSurface(rendererFor(sharedAtTheBound()), SURFACE, registry) }
        waitForIdle()
        assertEquals(LayoutTraits.Content, answers.last(), "the branch cut at the bound decides: $answers")
    }

    /** `top -> [x, d1 -> ... -> d(MAX_DEPTH - 1) -> x]`, `x -> leaf`, so the second `x` is at the bound. */
    private fun sharedAtTheBound(): String = buildString {
        append("""[{"id":"$ROOT_COMPONENT_ID","component":"Row","children":["top"]},""")
        append("""{"id":"top","component":"Column","children":["x","d1"]},""")
        for (level in 1 until MAX_DEPTH) {
            val next = if (level == MAX_DEPTH - 1) "x" else "d${level + 1}"
            append("""{"id":"d$level","component":"Column","children":["$next"]},""")
        }
        append("""{"id":"x","component":"Column","children":["leaf"]},""")
        append("""{"id":"leaf","component":"Divider"}]""")
    }

    private fun rendererFor(components: String): A2uiRenderer =
        A2uiRenderer(A2uiRendererConfig.Default.withClock({ "2026-08-27T00:00:00Z" })).also { renderer ->
            renderer.applyAll(
                listOf(
                    """{"version":"v1.0","createSurface":{"surfaceId":"$SURFACE","catalogId":"CATALOG_ID"}}""",
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
        val MAX_DEPTH = RenderLimits.DEFAULT.maxDepth
    }
}
