package dev.ynagai.a2ui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.AgentToRendererMessage
import kotlinx.serialization.json.JsonPrimitive
import dev.ynagai.a2ui.core.surface.JsonPointer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The half of the adapter that only exists inside a composition.
 *
 * Recursion, the cycle guard, the depth bound, and what happens to a component the registry cannot
 * draw. None of these can be checked without composing: they are decisions the tree makes on its
 * way down, and the thing that would go wrong -- an infinite recursion, a subtree that silently
 * never appears -- has no return value to inspect.
 */
@OptIn(ExperimentalTestApi::class)
class A2uiSurfaceTest {
    @Test
    fun a_tree_renders_from_the_root_down() = runComposeUiTest {
        setContent { A2uiSurface(rendererFor(NESTED), SURFACE, TestRegistry) }
        onNodeWithText("outer").assertIsDisplayed()
        onNodeWithText("inner").assertIsDisplayed()
    }

    @Test
    fun a_template_renders_one_subtree_per_item() = runComposeUiTest {
        setContent { A2uiSurface(rendererFor(TEMPLATE), SURFACE, TestRegistry) }
        // Each row reads a relative path, so three distinct strings appear only if each instance
        // got its own collection scope. One repeated three times would mean the scope was lost.
        onNodeWithText("one").assertIsDisplayed()
        onNodeWithText("two").assertIsDisplayed()
        onNodeWithText("three").assertIsDisplayed()
    }

    @Test
    fun a_write_to_the_data_model_reaches_the_component_that_reads_it() = runComposeUiTest {
        val renderer = rendererFor(NESTED)
        setContent { A2uiSurface(renderer, SURFACE, TestRegistry) }
        onNodeWithText("Ada").assertIsDisplayed()
        runOnIdle { renderer.write(SURFACE, JsonPointer.parse("/user/name"), JsonPrimitive("Grace")) }
        onNodeWithText("Grace").assertIsDisplayed()
    }

    @Test
    fun a_cycle_ends_the_descent_instead_of_hanging_it() = runComposeUiTest {
        // `a -> b -> a`. Without the guard this recurses until the composition dies, which is why
        // this test is written as "it finishes at all" rather than as an assertion about depth.
        val reasons = mutableListOf<A2uiPlaceholderReason>()
        val renderer = rendererFor(CYCLIC)
        val placeholder = recording(reasons)
        setContent { A2uiSurface(renderer, SURFACE, TestRegistry, placeholder = placeholder) }
        onNodeWithText("a").assertIsDisplayed()
        onNodeWithText("b").assertIsDisplayed()
        assertTrue(
            reasons.any { it is A2uiPlaceholderReason.Cycle },
            "a cycle should be reported as one, not merely survived: $reasons",
        )
    }

    @Test
    fun a_reference_that_has_not_arrived_yet_is_a_placeholder_rather_than_a_failure() =
        runComposeUiTest {
            // Progressive rendering: the specification requires a renderer to draw what it has and
            // keep going, because the agent is allowed to name a component before sending it.
            val reasons = mutableListOf<A2uiPlaceholderReason>()
            val renderer = rendererFor(DANGLING)
            val placeholder = recording(reasons)
            setContent { A2uiSurface(renderer, SURFACE, TestRegistry, placeholder = placeholder) }
            onNodeWithText("present").assertIsDisplayed()
            assertEquals(
                listOf(A2uiPlaceholderReason.MissingComponent("absent")),
                reasons.filterIsInstance<A2uiPlaceholderReason.MissingComponent>(),
            )
        }

    @Test
    fun a_component_type_the_registry_does_not_know_is_reported_as_one() = runComposeUiTest {
        val reasons = mutableListOf<A2uiPlaceholderReason>()
        val renderer = rendererFor(UNKNOWN)
        val placeholder = recording(reasons)
        setContent { A2uiSurface(renderer, SURFACE, ComponentRegistry.Empty, placeholder = placeholder) }
        assertEquals(
            listOf(A2uiPlaceholderReason.UnknownType("root", "Text")),
            reasons.filterIsInstance<A2uiPlaceholderReason.UnknownType>(),
        )
    }

    private fun recording(into: MutableList<A2uiPlaceholderReason>) =
        A2uiPlaceholder { reason, _ -> into += reason }

    private fun rendererFor(components: String): A2uiRenderer =
        A2uiRenderer(clock = { "2026-08-27T00:00:00Z" }).also { renderer ->
            renderer.applyAll(
                listOf(
                    """{"version":"v1.0","createSurface":{"surfaceId":"$SURFACE","catalogId":"CATALOG_ID"}}""",
                    """{"version":"v1.0","updateComponents":{"surfaceId":"$SURFACE","components":$components}}""",
                    """{"version":"v1.0","updateDataModel":{"surfaceId":"$SURFACE","value":{
                        "user":{"name":"Ada"},
                        "items":[{"label":"one"},{"label":"two"},{"label":"three"}]
                    }}}""",
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

        val NESTED = """[
            {"id":"root","component":"Column","children":["a","b","c"]},
            {"id":"a","component":"Text","text":"outer"},
            {"id":"b","component":"Text","text":"inner"},
            {"id":"c","component":"Text","text":{"path":"/user/name"}}
        ]"""

        val TEMPLATE = """[
            {"id":"root","component":"List","children":{"componentId":"row","path":"/items"}},
            {"id":"row","component":"Text","text":{"path":"label"}}
        ]"""

        val CYCLIC = """[
            {"id":"root","component":"Column","children":["a"]},
            {"id":"a","component":"Column","children":["labelA","b"]},
            {"id":"b","component":"Column","children":["labelB","a"]},
            {"id":"labelA","component":"Text","text":"a"},
            {"id":"labelB","component":"Text","text":"b"}
        ]"""

        val DANGLING = """[
            {"id":"root","component":"Column","children":["here","absent"]},
            {"id":"here","component":"Text","text":"present"}
        ]"""

        val UNKNOWN = """[{"id":"root","component":"Text","text":"nothing draws me"}]"""

        /** Stacks whatever children the catalog says this component has. */
        val StackingRenderer = ComponentRenderer { scope, modifier ->
            Column(modifier) {
                scope.rememberAllChildren().forEach { child -> scope.RenderChild(child) }
            }
        }

        /**
         * The same descent with no layout node around it.
         *
         * For the depth test only. That fixture is a chain of 264 containers, and with
         * [StackingRenderer] almost all of its cost is 264 nested `Column`s being measured and laid
         * out -- which is not what the depth guard does. In a browser it overran mocha's budget
         * outright, where the guard itself is a list membership test per level.
         *
         * The component type stays `Column`, because the child resolver reads the *catalog* to find
         * children: a type the basic catalog does not define would resolve none at all, the chain
         * would end at its first link, and the test would pass for the wrong reason.
         */
        val BareRenderer = ComponentRenderer { scope, _ ->
            scope.rememberAllChildren().forEach { child -> scope.RenderChild(child) }
        }

        /**
         * Just enough of a catalog to exercise the adapter: a `Text` that draws its resolved
         * string and two containers that stack their children.
         *
         * Deliberately not Material 3 widgets. This suite is about the descent -- which components
         * are reached, in what scope, and what happens when one cannot be -- and a real widget
         * would put its own layout and theming between the assertion and the thing being tested.
         *
         * Declared after [StackingRenderer] on purpose: an object's properties initialise in
         * source order, so referring to it from above would read null here and fail with no
         * mention of ordering.
         */
        /** [TestRegistry] with the containers stripped of their layout. See [BareRenderer]. */
        val DeepRegistry: ComponentRegistry
            get() = TestRegistry.with(mapOf("Column" to BareRenderer, "List" to BareRenderer))

        val TestRegistry = ComponentRegistry(
            mapOf(
                "Text" to ComponentRenderer { scope, modifier ->
                    Text(scope.rememberString("text").orEmpty(), modifier)
                },
                "Column" to StackingRenderer,
                "List" to StackingRenderer,
            ),
        )
    }
}
