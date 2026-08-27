package dev.ynagai.a2ui.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.AgentToRendererMessage
import dev.ynagai.a2ui.core.protocol.RendererToAgentMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * That a component's scope survives the host recomposing.
 *
 * The scope is what `rememberString` and its siblings key their `derivedStateOf` on, and those
 * caches are the whole of this renderer's answer to recomposition granularity -- the cost it
 * accepted by not building a binder layer. If the scope is rebuilt, they all go with it, and the
 * granularity stops working with nothing failing and nothing logged.
 *
 * The way that happens in practice is a host writing `onMessage = { viewModel.send(it) }`, which
 * is a fresh lambda on every recomposition whenever the compiler cannot memoise it. So the test
 * passes a deliberately unmemoisable one and recomposes around it.
 */
@OptIn(ExperimentalTestApi::class)
class A2uiScopeStabilityTest {
    @Test
    fun a_new_onMessage_lambda_does_not_rebuild_the_component_scopes() = runComposeUiTest {
        var recompositions = 0
        var scopeBuilds = 0
        var tick by mutableStateOf(0)

        val registry = ComponentRegistry(
            mapOf(
                "Text" to ComponentRenderer { scope, _ ->
                    // Keyed on the scope, exactly as the property accessors are. Counting how often
                    // this initialiser runs is counting how often their caches were thrown away.
                    remember(scope) { scopeBuilds++ }
                },
            ),
        )

        setContent {
            recompositions++
            // Reading `tick` here is what makes this composable recompose; capturing it in the
            // lambda is what makes the lambda a new instance each time.
            val current = tick
            A2uiSurface(
                renderer = renderer,
                surfaceId = SURFACE,
                registry = registry,
                onMessage = { _: RendererToAgentMessage -> sent += current },
            )
        }

        assertEquals(1, scopeBuilds, "the scope should be built once to begin with")
        repeat(3) {
            runOnIdle { tick++ }
        }
        // A guard on the test rather than on the renderer: if the host never recomposed, the
        // assertion below would hold for a reason that has nothing to do with the fix. The exact
        // count is Compose's business -- what matters is that it happened more than once.
        assertTrue(recompositions > 1, "the host did not recompose, so this test proves nothing")
        assertEquals(
            1,
            scopeBuilds,
            "the scope was rebuilt by a host recomposition, so every derived-state cache under it " +
                "was discarded",
        )
    }

    private val sent = mutableListOf<Int>()

    // A `val`, not a `get()`: read inside `setContent`, a fresh renderer per recomposition would
    // change the `remember` key by itself and the test would pass or fail for the wrong reason.
    private val renderer: A2uiRenderer =
        A2uiRenderer(clock = { "2026-08-27T00:00:00Z" }).also {
            it.applyAll(
                listOf(
                    """{"version":"v1.0","createSurface":{"surfaceId":"$SURFACE","catalogId":"CATALOG_ID"}}""",
                    """{"version":"v1.0","updateComponents":{"surfaceId":"$SURFACE","components":[
                        {"id":"root","component":"Text","text":"hello"}
                    ]}}""",
                ).map { text ->
                    A2uiJson.strict.decodeFromString(
                        AgentToRendererMessage.serializer(),
                        text.replace("CATALOG_ID", BasicCatalog.id),
                    )
                },
            )
        }

    private companion object {
        const val SURFACE = "s"
    }
}
