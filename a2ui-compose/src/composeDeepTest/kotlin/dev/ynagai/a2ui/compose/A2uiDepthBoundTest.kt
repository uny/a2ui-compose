package dev.ynagai.a2ui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.AgentToRendererMessage
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The depth bound, on the targets that can afford to reach it.
 *
 * Every other target but wasmJs: composing [MAX_RENDER_DEPTH] nested containers costs ~0.1s on the
 * JVM and is not measurable on the two native targets, but does not finish inside mocha's budget in
 * a browser -- the same test times out there at 60s. That is a fact about the bound rather than
 * about the test: a surface nesting 256 deep is one this renderer accepts, and on the web it would
 * hang the tab rather than draw. Recorded as its own source set so the exclusion is something the
 * build states, not something a runtime skip hides.
 */
@OptIn(ExperimentalTestApi::class)
class A2uiDepthBoundTest {
    @Test
    fun a_tree_deeper_than_the_bound_stops_at_it_rather_than_descending() = runComposeUiTest {
        // Without a test, deleting the guard -- or raising the bound to something that never trips
        // -- left every other test green, because the deepest other fixture nests three levels.
        val reasons = mutableListOf<A2uiPlaceholderReason>()
        val renderer = rendererFor(deepChain(MAX_RENDER_DEPTH + 8))
        val placeholder = A2uiPlaceholder { reason, _ -> reasons += reason }
        setContent { A2uiSurface(renderer, SURFACE, TestRegistry, placeholder = placeholder) }
        assertTrue(
            reasons.any { it is A2uiPlaceholderReason.TooDeep },
            "a chain past MAX_RENDER_DEPTH should stop as TooDeep: ${reasons.take(4)}",
        )
        // And it stops *at* the bound rather than somewhere past it.
        assertTrue(
            reasons.filterIsInstance<A2uiPlaceholderReason.TooDeep>()
                .any { it.componentId == "c$MAX_RENDER_DEPTH" },
            "the bound should trip on the component at depth MAX_RENDER_DEPTH",
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
        A2uiRenderer(clock = { "2026-08-27T00:00:00Z" }).also { renderer ->
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
