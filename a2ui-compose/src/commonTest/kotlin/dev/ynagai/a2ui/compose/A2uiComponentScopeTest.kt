package dev.ynagai.a2ui.compose

import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.Action
import dev.ynagai.a2ui.core.protocol.ActionMessage
import dev.ynagai.a2ui.core.protocol.AgentToRendererMessage
import dev.ynagai.a2ui.core.protocol.RendererToAgentMessage
import dev.ynagai.a2ui.core.surface.EvaluationScope
import dev.ynagai.a2ui.core.surface.JsonPointer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a component renderer can ask its scope, checked without a composition.
 *
 * Everything here is the part of the adapter that is not Compose: resolving a property through the
 * core evaluator, expanding a template into one child per array item, turning a bound path into
 * somewhere to write, and building the message an event action sends. The composition-dependent
 * half -- recursion, the cycle guard, placeholders -- is checked separately, because a test that
 * needs a renderer on screen cannot say much about a value that never reaches one.
 */
class A2uiComponentScopeTest {
    @Test
    fun a_literal_property_resolves_to_itself() {
        val scope = scopeFor("title")
        assertEquals("Hello", scope.string("text"))
    }

    @Test
    fun a_bound_property_resolves_through_the_data_model() {
        val scope = scopeFor("bound")
        assertEquals("Ada", scope.string("text"))
    }

    @Test
    fun a_bound_property_follows_a_later_write() {
        val renderer = renderer()
        val scope = scopeFor("bound", renderer)
        assertEquals("Ada", scope.string("text"))
        renderer.write(SURFACE, JsonPointer.parse("/user/name"), JsonPrimitive("Grace"))
        // The same scope object, not a rebuilt one: it holds the surface id rather than the
        // surface, so a write is visible through it. A scope that had captured the model would
        // still say "Ada" here, and every bound property in a real surface would freeze likewise.
        assertEquals("Grace", scope.string("text"))
    }

    @Test
    fun an_absent_property_is_null_rather_than_an_error() {
        val scope = scopeFor("title")
        assertNull(scope.string("nosuch"))
        assertNull(scope.number("nosuch"))
        assertNull(scope.boolean("nosuch"))
    }

    @Test
    fun a_template_expands_to_one_child_per_item_each_in_its_own_scope() {
        val scope = scopeFor("list")
        val children = scope.children("children")
        assertEquals(3, children.size)
        assertTrue(children.all { it.componentId == "row" })
        // The scopes must differ, and differ by index: they are what a relative path inside the
        // template resolves against, so two identical ones would render the same item three times.
        assertEquals(listOf(0, 1, 2), children.map { (it.evaluationScope as EvaluationScope.Collection).index })
    }

    @Test
    fun a_child_in_a_template_reads_the_item_it_belongs_to() {
        val listScope = scopeFor("list")
        val rows = listScope.children("children").map { child ->
            A2uiComponentScope(
                renderer = listScope.renderer,
                surfaceId = SURFACE,
                component = listScope.renderer.state.surfaces.getValue(SURFACE).components.getValue("row"),
                evaluationScope = child.evaluationScope,
                onMessage = {},
            )
        }
        assertEquals(listOf("one", "two", "three"), rows.map { it.string("text") })
    }

    @Test
    fun a_fixed_child_list_keeps_the_order_the_agent_sent() {
        val scope = scopeFor("column")
        assertEquals(listOf("title", "bound"), scope.children("children").map { it.componentId })
    }

    @Test
    fun a_binding_names_where_to_write_and_a_literal_names_nowhere() {
        assertEquals(JsonPointer.parse("/user/name"), scopeFor("bound").binding("text"))
        assertNull(scopeFor("title").binding("text"))
    }

    @Test
    fun a_write_from_inside_a_template_lands_on_that_item() {
        val renderer = renderer()
        val listScope = scopeFor("list", renderer)
        val second = listScope.children("children")[1]
        val row = A2uiComponentScope(
            renderer = renderer,
            surfaceId = SURFACE,
            component = renderer.state.surfaces.getValue(SURFACE).components.getValue("row"),
            evaluationScope = second.evaluationScope,
            onMessage = {},
        )
        // A relative pointer inside a template instance means "within this item". Rebased against
        // the root instead, this would write `/label` and leave every row reading its old value --
        // the failure the blueprint calls the usual cause of empty data in nested components.
        row.write(JsonPointer.parse("label"), JsonPrimitive("changed"))
        assertEquals("changed", row.string("text"))
        assertEquals(
            listOf("one", "changed", "three"),
            listScope.children("children").map { child ->
                A2uiComponentScope(
                    renderer, SURFACE,
                    renderer.state.surfaces.getValue(SURFACE).components.getValue("row"),
                    child.evaluationScope, {},
                ).string("text")
            },
        )
    }

    @Test
    fun an_event_action_sends_a_message_with_its_context_resolved() {
        val sent = mutableListOf<RendererToAgentMessage>()
        val scope = scopeFor("button", onMessage = { sent += it })
        val action = A2uiJson.strict.decodeFromString(
            Action.serializer(),
            """{"event":{"name":"submit","context":{"who":{"path":"/user/name"},"lit":"x"}}}""",
        )
        scope.dispatch(action)
        val message = assertNotNull(sent.singleOrNull() as? ActionMessage)
        assertEquals("submit", message.name)
        assertEquals(SURFACE, message.surfaceId)
        assertEquals("button", message.sourceComponentId)
        // Resolved at dispatch, not at render: the specification has the action carry the values as
        // they stand at the moment of the interaction.
        assertEquals("Ada", message.context.getValue("who").jsonPrimitive.content)
        assertEquals("x", message.context.getValue("lit").jsonPrimitive.content)
        assertEquals(FIXED_TIMESTAMP, message.timestamp)
    }

    @Test
    fun a_surface_whose_catalog_is_absent_reports_no_children_rather_than_guessing() {
        // No fallback resolver that reads `child` and `children`: those names are the basic
        // catalog's, and guessing them would silently drop `Modal.trigger` and the child inside
        // each `Tabs.tabs` entry even within that catalog.
        val renderer = A2uiRenderer(catalogs = emptyList(), clock = { FIXED_TIMESTAMP })
        renderer.applyAll(MESSAGES)
        val scope = A2uiComponentScope(
            renderer = renderer,
            surfaceId = SURFACE,
            component = renderer.state.surfaces.getValue(SURFACE).components.getValue("column"),
            evaluationScope = EvaluationScope.Root,
            onMessage = {},
        )
        assertEquals(emptyList(), scope.children("children"))
    }

    private fun renderer(): A2uiRenderer =
        A2uiRenderer(clock = { FIXED_TIMESTAMP }).also { it.applyAll(MESSAGES) }

    private fun scopeFor(
        componentId: String,
        renderer: A2uiRenderer = renderer(),
        onMessage: (RendererToAgentMessage) -> Unit = {},
    ) = A2uiComponentScope(
        renderer = renderer,
        surfaceId = SURFACE,
        component = renderer.state.surfaces.getValue(SURFACE).components.getValue(componentId),
        evaluationScope = EvaluationScope.Root,
        onMessage = onMessage,
    )

    private companion object {
        const val SURFACE = "s"
        const val FIXED_TIMESTAMP = "2026-08-27T00:00:00Z"

        val MESSAGES: List<AgentToRendererMessage> = listOf(
            """{"version":"v1.0","createSurface":{"surfaceId":"$SURFACE","catalogId":"CATALOG_ID"}}""",
            """
            {"version":"v1.0","updateComponents":{"surfaceId":"$SURFACE","components":[
              {"id":"root","component":"Column","children":["title","bound"]},
              {"id":"column","component":"Column","children":["title","bound"]},
              {"id":"title","component":"Text","text":"Hello"},
              {"id":"bound","component":"Text","text":{"path":"/user/name"}},
              {"id":"list","component":"List","children":{"componentId":"row","path":"/items"}},
              {"id":"row","component":"Text","text":{"path":"label"}},
              {"id":"button","component":"Button","child":"title","action":{"event":{"name":"noop"}}}
            ]}}
            """.trimIndent(),
            """
            {"version":"v1.0","updateDataModel":{"surfaceId":"$SURFACE","value":{
              "user":{"name":"Ada"},
              "items":[{"label":"one"},{"label":"two"},{"label":"three"}]
            }}}
            """.trimIndent(),
        ).map { text ->
            A2uiJson.strict.decodeFromString(
                AgentToRendererMessage.serializer(),
                text.replace("CATALOG_ID", BasicCatalog.id),
            )
        }
    }
}
