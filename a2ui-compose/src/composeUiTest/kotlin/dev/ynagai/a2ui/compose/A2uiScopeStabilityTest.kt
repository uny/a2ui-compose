package dev.ynagai.a2ui.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.Action
import dev.ynagai.a2ui.core.protocol.AgentToRendererMessage
import dev.ynagai.a2ui.core.protocol.RendererToAgentMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * That a component's scope survives the host recomposing, and that dispatch still reaches the
 * newest callback.
 *
 * The scope is what `rememberString` and its siblings key their `derivedStateOf` on, and those
 * caches are the whole of this renderer's answer to recomposition granularity -- the cost it
 * accepted by not building a binder layer. If the scope is rebuilt, they all go with it, and the
 * granularity stops working with nothing failing and nothing logged.
 *
 * The way that happens in practice is a host writing `onMessage = { viewModel.send(it) }`, which
 * is a fresh lambda on every recomposition whenever the compiler cannot memoise it. So the tests
 * pass a deliberately unmemoisable one and recompose around it.
 *
 * **Two levels, not one.** Keeping the scope is only half the fix, and each half fails in a way
 * the other's assertion cannot see:
 *
 * - The scope has to survive at every level, and a child's scope is reached through [RenderChild]
 *   rather than through the same call the root takes. A one-node surface leaves that path
 *   uncomposed entirely, so anything that destabilises a *child's* `remember` keys -- its
 *   `evaluationScope`, or the child list `rememberAllChildren` hands back -- discards every nested
 *   cache with no counter moving. Hence `leaf`, which is live: restoring the `onMessage` key takes
 *   both counters to 4 across the three ticks below, not just `root`.
 * - Not keying on `onMessage` means the scope now holds an indirection rather than the caller's
 *   lambda, so *reaching the newest callback* becomes a property that can break on its own. Drop
 *   the `rememberUpdatedState` and capture `onMessage` directly and the tree is just as stable --
 *   and every tap for the rest of the surface's life runs the first composition's closure.
 *
 * **Mutation-checked.** Restoring `onMessage` as a `remember` key fails
 * [a_new_onMessage_lambda_does_not_rebuild_the_component_scopes]; capturing `onMessage` instead of
 * reading it through `rememberUpdatedState` fails [a_dispatch_reaches_the_newest_onMessage]; and
 * re-providing `LocalA2uiRegistry` per recomposition fails the first test's `bodyRuns` assertion.
 *
 * One mutation that does *not* discriminate, recorded so it is not attempted again as a check:
 * making [RenderChild] forward `{ m -> onMessage(m) }` instead of `onMessage`. It looks like it
 * should destabilise every level below, and it does not -- the compiler memoises a lambda declared
 * in a composable against its captures, and the capture here is already stable, so the forwarded
 * lambda is stable too. Nothing in this file moves.
 */
@OptIn(ExperimentalTestApi::class)
class A2uiScopeStabilityTest {
    @Test
    fun a_new_onMessage_lambda_does_not_rebuild_the_component_scopes() = runComposeUiTest {
        var recompositions = 0
        val scopeBuilds = mutableMapOf<String, Int>()
        val bodyRuns = mutableMapOf<String, Int>()
        var tick by mutableStateOf(0)
        // Hoisted: `LocalA2uiRegistry` is a *static* composition local, so a fresh registry per
        // recomposition would invalidate the whole subtree and `bodyRuns` would count that
        // instead of what this test is about.
        val registry = registryCounting(scopeBuilds, bodyRuns)

        setContent {
            recompositions++
            // Reading `tick` here is what makes this composable recompose; capturing it in the
            // lambda is what makes the lambda a new instance each time.
            val current = tick
            A2uiSurface(
                renderer = renderer,
                surfaceId = SURFACE,
                registry = registry,
                onMessage = { _: RendererToAgentMessage -> sink += current },
            )
        }

        assertEquals(
            mapOf("root" to 1, "leaf" to 1),
            scopeBuilds,
            "each scope should be built once to begin with",
        )
        repeat(3) { runOnIdle { tick++ } }
        // `runOnIdle` synchronises *before* its action, so without this the last `tick++` would
        // still be in flight while the assertions below read the counters.
        waitForIdle()
        // A guard on the test rather than on the renderer: if the host never recomposed, the
        // assertion below would hold for a reason that has nothing to do with the fix. The exact
        // count is Compose's business -- what matters is that it happened more than once.
        assertTrue(recompositions > 1, "the host did not recompose, so this test proves nothing")
        assertEquals(
            mapOf("root" to 1, "leaf" to 1),
            scopeBuilds,
            "a scope was rebuilt by a host recomposition, so every derived-state cache under it " +
                "was discarded",
        )
        // The payoff, and a property the scope counts above cannot see. Keeping the scope keeps
        // the *caches*; every level also skipping is a second property, and it fails on its own --
        // anything the host hands down that changes identity per recomposition re-runs the whole
        // subtree while the scopes, and so the assertion above, stay perfectly stable. Measured:
        // passing a freshly built `ComponentRegistry` into `A2uiSurface` (a *static* composition
        // local, so re-providing it invalidates everything below) turns this into {root=4, leaf=4}
        // across the three ticks above.
        assertEquals(
            mapOf("root" to 1, "leaf" to 1),
            bodyRuns,
            "a component recomposed although nothing it reads changed: something handed down " +
                "from the host changes identity on every recomposition",
        )
    }

    @Test
    fun a_dispatch_reaches_the_newest_onMessage() = runComposeUiTest {
        var tick by mutableStateOf(0)
        var leaf: A2uiComponentScope? = null
        // Counters unused here; this test is about where the message lands, not about rebuilds.
        val registry = registryCounting(mutableMapOf(), onLeaf = { scope -> leaf = scope })

        setContent {
            val current = tick
            A2uiSurface(
                renderer = renderer,
                surfaceId = SURFACE,
                registry = registry,
                onMessage = { _: RendererToAgentMessage -> sink += current },
            )
        }

        repeat(3) { runOnIdle { tick++ } }
        waitForIdle()
        // From the *leaf*, so this exercises the whole chain: the leaf's indirection forwards to
        // the root's, which reads the host's newest lambda. A break at any link sends the wrong
        // `current`.
        runOnIdle { assertNotNull(leaf, "the leaf never composed").dispatch(EVENT) }
        assertEquals(
            listOf(3),
            sink,
            "the tap ran an outdated `onMessage`: the scope holds an indirection now, so the " +
                "callback has to be read at dispatch rather than captured when it was built",
        )
    }

    /**
     * `Text` and `Column`, each counting how often a scope-keyed `remember` re-initialises.
     *
     * Keyed on the scope, exactly as the property accessors are. Counting how often these
     * initialisers run is counting how often their caches were thrown away.
     *
     * Built fresh per call rather than held as a property, because each test wants its own
     * counters. Every caller hoists the result out of `setContent`: `LocalA2uiRegistry` is a
     * *static* composition local, so handing `A2uiSurface` a new registry per recomposition would
     * invalidate the whole subtree, and `bodyRuns` would then be counting that instead.
     */
    private fun registryCounting(
        builds: MutableMap<String, Int>,
        bodyRuns: MutableMap<String, Int> = mutableMapOf(),
        onLeaf: (A2uiComponentScope) -> Unit = {},
    ) = ComponentRegistry(
        mapOf(
            "Text" to ComponentRenderer { scope, _ ->
                bodyRuns.merge(scope.component.id, 1, Int::plus)
                remember(scope) { builds.merge(scope.component.id, 1, Int::plus) }
                onLeaf(scope)
            },
            "Column" to ComponentRenderer { scope, _ ->
                bodyRuns.merge(scope.component.id, 1, Int::plus)
                remember(scope) { builds.merge(scope.component.id, 1, Int::plus) }
                scope.rememberAllChildren().forEach { child -> scope.RenderChild(child) }
            },
        ),
    )

    private val sink = mutableListOf<Int>()

    // A `val`, not a `get()`: read inside `setContent`, a fresh renderer per recomposition would
    // change the `remember` key by itself and the test would pass or fail for the wrong reason.
    private val renderer: A2uiRenderer =
        A2uiRenderer(clock = { "2026-08-27T00:00:00Z" }).also {
            it.applyAll(
                listOf(
                    """{"version":"v1.0","createSurface":{"surfaceId":"$SURFACE","catalogId":"CATALOG_ID"}}""",
                    """{"version":"v1.0","updateComponents":{"surfaceId":"$SURFACE","components":[
                        {"id":"root","component":"Column","children":["leaf"]},
                        {"id":"leaf","component":"Text","text":"hello"}
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

        /** An event action, because that is the branch of `dispatch` that reaches `onMessage`. */
        val EVENT: Action = A2uiJson.strict.decodeFromString(
            Action.serializer(),
            """{"event":{"name":"tapped"}}""",
        )
    }
}
