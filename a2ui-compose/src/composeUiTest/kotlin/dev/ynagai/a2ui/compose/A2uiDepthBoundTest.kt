package dev.ynagai.a2ui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.AgentToRendererMessage
import dev.ynagai.a2ui.core.surface.RenderLimits
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The depth bound.
 *
 * This ran on every target but wasmJs while the bound was core's 256, which a browser cannot
 * compose inside mocha's budget -- the fact that sent [RenderLimits.maxDepth] to be measured and
 * set separately. At 24 the browser reaches it like everything else, so the source set that
 * recorded the exclusion is gone and the bound is now checked where it matters most.
 */
@OptIn(ExperimentalTestApi::class)
class A2uiDepthBoundTest {
    @Test
    fun a_tree_deeper_than_the_bound_stops_at_it_rather_than_descending() = runComposeUiTest {
        // Without a test, deleting the guard -- or raising the bound to something that never trips
        // -- left every other test green, because the deepest other fixture nests three levels.
        val reasons = mutableListOf<A2uiPlaceholderReason>()
        val renderer = rendererFor(deepChain(MAX_DEPTH + 8))
        val placeholder = A2uiPlaceholder { reason, _ -> reasons += reason }
        setContent { A2uiSurface(renderer, SURFACE, TestRegistry, placeholder = placeholder) }
        assertTrue(
            reasons.any { it is A2uiPlaceholderReason.TooDeep },
            "a chain past the depth bound should stop as TooDeep: ${reasons.take(4)}",
        )
        // And it stops *at* the bound rather than somewhere past it.
        assertTrue(
            reasons.filterIsInstance<A2uiPlaceholderReason.TooDeep>()
                .any { it.componentId == "c$MAX_DEPTH" },
            "the bound should trip on the component at the depth bound",
        )
    }

    /** `root -> c1 -> ... -> c[depth]`, each a `Column` holding exactly the next. */
    private fun deepChain(depth: Int): String =
        (0..depth).joinToString(",", "[", "]") { level ->
            val id = if (level == 0) ROOT_COMPONENT_ID else "c$level"
            if (level == depth) """{"id":"$id","component":"Text","text":"bottom"}"""
            else """{"id":"$id","component":"Column","children":["c${level + 1}"]}"""
        }

    private fun rendererFor(components: String): A2uiRenderer =
        A2uiRenderer(A2uiRendererConfig.Default
            .withClock({ "2026-08-27T00:00:00Z" }),
        ).also { renderer ->
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

        /** Containers only: this suite is about how far down the descent goes, not what it draws. */
        val TestRegistry = ComponentRegistry(
            mapOf(
                "Column" to ComponentRenderer { scope, modifier ->
                    Column(modifier) { scope.rememberAllChildren().forEach { scope.RenderChild(it) } }
                },
                "Text" to ComponentRenderer { _, _ -> },
            ),
        )
    }
}
