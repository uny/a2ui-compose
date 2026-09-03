package dev.ynagai.a2ui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.AgentToRendererMessage
import dev.ynagai.a2ui.core.surface.EvaluationScope
import dev.ynagai.a2ui.core.surface.RenderLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The instance budget, which is the only guard here that has to act before it descends.
 *
 * The cycle guard and the depth bound stop a composition that has gone somewhere it should not.
 * Neither sees the payload this is about: n layers of components that each name the same two
 * children expand to 2^n instances, and no path in that repeats an id or runs deep. Composition
 * does not raise when it gives way -- it hangs inside `setContent` -- so every test here is
 * written as "this finishes and says why", and would not finish at all without the guard.
 */
@OptIn(ExperimentalTestApi::class)
class A2uiRenderBudgetTest {
    @Test
    fun a_surface_that_expands_past_the_budget_is_refused_before_anything_is_drawn() =
        runComposeUiTest {
            val reasons = mutableListOf<A2uiPlaceholderReason>()
            val renderer = rendererFor(fanOut(20))
            setContent {
                A2uiSurface(renderer, SURFACE, TestRegistry, placeholder = recording(reasons))
            }
            // One placeholder standing in for the whole surface, not the first N instances of it.
            val refused: A2uiPlaceholderReason = A2uiPlaceholderReason.BudgetExceeded(
                ROOT_COMPONENT_ID,
                RenderLimits.DEFAULT.maxInstances,
            )
            assertEquals(listOf(refused), reasons)
        }

    @Test
    fun a_host_drawing_one_component_by_hand_passes_the_same_gate() = runComposeUiTest {
        // `A2uiComponent` is public, so a gate that only `A2uiSurface` went through would be a
        // gate with a documented way around it.
        val reasons = mutableListOf<A2uiPlaceholderReason>()
        val renderer = rendererFor(fanOut(20))
        setContent {
            CompositionLocalsFor(recording(reasons)) {
                A2uiComponent(renderer, SURFACE, ROOT_COMPONENT_ID, EvaluationScope.Root)
            }
        }
        assertTrue(
            reasons.singleOrNull() is A2uiPlaceholderReason.BudgetExceeded,
            "an unguarded entry point would have hung instead of reporting: $reasons",
        )
    }

    @Test
    fun a_template_longer_than_its_share_is_cut_and_says_so() = runComposeUiTest {
        // What the estimate taken before the descent cannot catch: how many instances a template
        // yields is the agent's data model talking, and the components alone do not say.
        val reasons = mutableListOf<A2uiPlaceholderReason>()
        val renderer = rendererFor(TEMPLATE, items = 50, limits = RenderLimits(maxInstances = 20))
        setContent {
            A2uiSurface(renderer, SURFACE, TestRegistry, placeholder = recording(reasons))
        }
        val cut = reasons.filterIsInstance<A2uiPlaceholderReason.TooManyChildren>()
        assertEquals(1, cut.size, "the cut should be reported once, by the container that made it")
        // 20 instances: the root, 18 rows, and the entry reporting the 32 that did not fit.
        assertEquals(A2uiPlaceholderReason.TooManyChildren(ROOT_COMPONENT_ID, 32), cut.single())
    }

    @Test
    fun nested_templates_cannot_spend_more_than_the_budget_they_were_handed() = runComposeUiTest {
        // The product a per-container cap would miss: 20 rows of 20 cells is 400 instances from
        // two templates, and each container on its own is asking for a reasonable 20.
        val reasons = mutableListOf<A2uiPlaceholderReason>()
        val drawn = mutableSetOf<Pair<String, EvaluationScope>>()
        val renderer = rendererFor(NESTED_TEMPLATE, items = 20, limits = RenderLimits(maxInstances = 20))
        setContent {
            A2uiSurface(renderer, SURFACE, counting(drawn), placeholder = recording(reasons))
        }
        // The root divides what it has among its rows; each row is then left with a budget of one,
        // which pays for the row and nothing under it.
        assertTrue(
            reasons.any { it is A2uiPlaceholderReason.TooManyChildren },
            "the descent should report where it stopped: $reasons",
        )
        // And the count is the assertion, not the placeholder. The root alone emits a marker here,
        // so `any { TooManyChildren }` holds however the budget is divided -- it would still hold
        // if `share` were the whole remainder and these two templates composed the 400 instances
        // they ask for. What has to be measured is what was actually drawn: every component
        // instance and every entry standing in for the ones that were not, against the bound.
        assertTrue(
            drawn.size + reasons.size <= 20,
            "20 instances were budgeted; ${drawn.size} components and ${reasons.size} placeholders were drawn",
        )
    }

    @Test
    fun a_surface_the_estimate_counted_exactly_is_not_rationed() = runComposeUiTest {
        // The division is for what the components cannot say, and a surface holding no template
        // says everything: the estimate counted the instances rather than bounding them, and it
        // fits. Dividing anyway took children from surfaces that were never going to exceed the
        // bound -- an even split gives this root's two children nine each, and the second wants
        // fifteen, so six of them used to vanish behind a placeholder on a surface costing 18 of
        // the 20 budgeted.
        val leaves = (0 until 15).joinToString(",") { """{"id":"c$it","component":"Text","text":"x"}""" }
        val ids = (0 until 15).joinToString(",") { "\"c$it\"" }
        val renderer = rendererFor(
            """[
                {"id":"$ROOT_COMPONENT_ID","component":"Column","children":["a","b"]},
                {"id":"a","component":"Text","text":"leaf"},
                {"id":"b","component":"Column","children":[$ids]},
                $leaves
            ]""",
            limits = RenderLimits(maxInstances = 20),
        )
        val reasons = mutableListOf<A2uiPlaceholderReason>()
        val drawn = mutableSetOf<Pair<String, EvaluationScope>>()
        setContent {
            A2uiSurface(renderer, SURFACE, counting(drawn), placeholder = recording(reasons))
        }
        assertEquals(emptyList<A2uiPlaceholderReason>(), reasons, "nothing here needed rationing")
        // root, `a`, `b`, and all fifteen leaves -- the exact count the estimate reported.
        assertEquals(18, drawn.size)
    }

    @Test
    fun a_row_keeps_its_scope_when_the_list_it_belongs_to_grows() = runComposeUiTest {
        // The share a row is handed is the remainder divided by how many rows there are, so an
        // append moves it for every sibling that was already there. While that share was one of
        // the scope's `remember` keys, appending a single item rebuilt every scope in the subtree
        // and discarded every `derivedStateOf` under it -- the granularity #12 exists to keep.
        val renderer = rendererFor(TEMPLATE, items = 3)
        val scopes = mutableMapOf<EvaluationScope, MutableList<A2uiComponentScope>>()
        val registry = ComponentRegistry(
            mapOf(
                "List" to StackingRenderer,
                "Text" to ComponentRenderer { scope, _ ->
                    scopes.getOrPut(scope.evaluationScope) { mutableListOf() }
                        .let { seen -> if (seen.none { it === scope }) seen += scope }
                },
            ),
        )
        setContent { A2uiSurface(renderer, SURFACE, registry) }
        val before = scopes.keys.toSet()
        assertEquals(3, before.size)

        renderer.applyAll(
            listOf(
                """{"version":"v1.0","updateDataModel":{"surfaceId":"$SURFACE","path":"/items/-",
                   "value":{"label":"row3","cells":[]}}}""",
            ).map { A2uiJson.strict.decodeFromString(AgentToRendererMessage.serializer(), it) },
        )
        waitForIdle()

        assertEquals(4, scopes.keys.size, "the appended row should have been drawn")
        // One scope object per row, still, for every row that was already there.
        for (scope in before) {
            assertEquals(1, scopes.getValue(scope).size, "row $scope was rebuilt by the append")
        }
    }

    @Test
    fun an_ordinary_surface_is_not_touched_by_any_of_this() = runComposeUiTest {
        // The bound has to be invisible to real content, which the official corpus puts at 7
        // levels and a handful of components. A guard that cost anything here would be the wrong
        // guard however well it stopped the payloads above.
        val reasons = mutableListOf<A2uiPlaceholderReason>()
        val renderer = rendererFor(TEMPLATE, items = 3)
        setContent {
            A2uiSurface(renderer, SURFACE, TestRegistry, placeholder = recording(reasons))
        }
        assertEquals(emptyList<A2uiPlaceholderReason>(), reasons)
    }

    @Test
    fun a_surface_drawn_inside_another_surface_passes_the_gate_too() = runComposeUiTest {
        // The gate opens on a null render path, and a surface composed from inside a component
        // renderer inherits the enclosing one's. Nothing resets it at the boundary, so this entry
        // used to skip the estimate entirely -- and the outer path already holding `root` made
        // this surface's own root look like a cycle. Both are the same missing reset.
        val reasons = mutableListOf<A2uiPlaceholderReason>()
        val outer = rendererFor("""[{"id":"$ROOT_COMPONENT_ID","component":"Column","children":["a"]},
            {"id":"a","component":"Text","text":"x"}]""")
        val inner = rendererFor(fanOut(20))
        val nesting = ComponentRegistry(
            mapOf(
                "Column" to StackingRenderer,
                // Stands in for a host component whose renderer embeds another surface.
                "Text" to ComponentRenderer { _, _ ->
                    A2uiSurface(inner, SURFACE, TestRegistry, placeholder = recording(reasons))
                },
            ),
        )
        setContent { A2uiSurface(outer, SURFACE, nesting) }
        // Refused as a whole, exactly as it is at the top level -- not drawn, and not mistaken
        // for a cycle against the outer surface's ids.
        assertEquals(
            listOf<A2uiPlaceholderReason>(
                A2uiPlaceholderReason.BudgetExceeded(
                    ROOT_COMPONENT_ID,
                    RenderLimits.DEFAULT.maxInstances,
                ),
            ),
            reasons,
        )
    }

    @Test
    fun a_component_the_resolver_refuses_does_not_take_the_composition_with_it() =
        runComposeUiTest {
            // The estimate resolves children to count them, and `childrenOf` raises on payloads the
            // agent chooses: `Tabs` yields one reference per tab through the shipped catalog, so
            // five thousand tabs outgrow the resolver's own bounds. The descent this gates already
            // survives that -- `children()` resolves inside a `runCatching` and degrades to a
            // container with none -- so an estimate that raised instead would turn a payload the
            // renderer handles into a host crash, which is what this asserts it does not.
            val tabs = (0 until 5_000).joinToString(",") { """{"title":"t$it","child":"leaf"}""" }
            val renderer = rendererFor(
                """[
                    {"id":"$ROOT_COMPONENT_ID","component":"Tabs","tabs":[$tabs]},
                    {"id":"leaf","component":"Text","text":"x"}
                ]""",
            )
            val reasons = mutableListOf<A2uiPlaceholderReason>()
            setContent {
                A2uiSurface(renderer, SURFACE, TestRegistry, placeholder = recording(reasons))
            }
            // Drawn as a container with no children rather than refused, because the estimate had
            // no verdict to give: reaching this line at all is the assertion.
            assertEquals(emptyList<A2uiPlaceholderReason>(), reasons)
        }

    /** [A2uiComponent] reads its registry and placeholder from the composition, as a host would. */
    @Composable
    private fun CompositionLocalsFor(
        placeholder: A2uiPlaceholder,
        content: @Composable () -> Unit,
    ) {
        CompositionLocalProvider(
            LocalA2uiRegistry provides TestRegistry,
            LocalA2uiPlaceholder provides placeholder,
            content = content,
        )
    }

    private fun recording(into: MutableList<A2uiPlaceholderReason>) =
        A2uiPlaceholder { reason, _ -> into += reason }

    /**
     * [TestRegistry], with every component instance it draws recorded.
     *
     * Keyed by id *and* scope, because a template's instances share an id and differ only by the
     * item they render -- which is the half of the expansion the budget exists to bound -- and
     * because a set keyed that way counts instances rather than recompositions.
     */
    private fun counting(into: MutableSet<Pair<String, EvaluationScope>>) = ComponentRegistry(
        TestRegistry.types.associateWith { type ->
            val delegate = TestRegistry[type]!!
            ComponentRenderer { scope, modifier ->
                into += scope.component.id to scope.evaluationScope
                delegate.Render(scope, modifier)
            }
        },
    )

    /** `root -> {a0, b0} -> ... -> {a[n], b[n]}`: 2n + 1 components, 2^n instances. */
    private fun fanOut(layers: Int): String = buildList {
        add("""{"id":"$ROOT_COMPONENT_ID","component":"Column","children":["a0","b0"]}""")
        repeat(layers) { level ->
            val children = if (level == layers - 1) "[]" else """["a${level + 1}","b${level + 1}"]"""
            add("""{"id":"a$level","component":"Column","children":$children}""")
            add("""{"id":"b$level","component":"Column","children":$children}""")
        }
    }.joinToString(",", "[", "]")

    private fun rendererFor(
        components: String,
        items: Int = 0,
        limits: RenderLimits = RenderLimits.DEFAULT,
    ): A2uiRenderer =
        A2uiRenderer(A2uiRendererConfig.Default
            .withClock({ "2026-08-27T00:00:00Z" })
            .withRenderLimits(limits),
        ).also { renderer ->
            val rows = (0 until items).joinToString(",") { row ->
                val cells = (0 until items).joinToString(",") { """{"v":"c$row-$it"}""" }
                """{"label":"row$row","cells":[$cells]}"""
            }
            renderer.applyAll(
                listOf(
                    """{"version":"v1.0","createSurface":{"surfaceId":"$SURFACE","catalogId":"CATALOG_ID"}}""",
                    """{"version":"v1.0","updateComponents":{"surfaceId":"$SURFACE","components":$components}}""",
                    """{"version":"v1.0","updateDataModel":{"surfaceId":"$SURFACE","value":{"items":[$rows]}}}""",
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

        val TEMPLATE = """[
            {"id":"root","component":"List","children":{"componentId":"row","path":"/items"}},
            {"id":"row","component":"Text","text":{"path":"label"}}
        ]"""

        val NESTED_TEMPLATE = """[
            {"id":"root","component":"List","children":{"componentId":"row","path":"/items"}},
            {"id":"row","component":"List","children":{"componentId":"cell","path":"cells"}},
            {"id":"cell","component":"Text","text":{"path":"v"}}
        ]"""

        val StackingRenderer = ComponentRenderer { scope, modifier ->
            Column(modifier) {
                scope.rememberAllChildren().forEach { child -> scope.RenderChild(child) }
            }
        }

        val TestRegistry = ComponentRegistry(
            mapOf(
                "Text" to ComponentRenderer { scope, modifier ->
                    Text(scope.rememberString("text").orEmpty(), modifier)
                },
                "Column" to StackingRenderer,
                "List" to StackingRenderer,
                "Tabs" to StackingRenderer,
            ),
        )
    }
}
