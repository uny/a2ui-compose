package dev.ynagai.a2ui.compose

import dev.ynagai.a2ui.core.function.EvaluationLimits
import dev.ynagai.a2ui.core.function.FallbackLocaleFormatter
import dev.ynagai.a2ui.core.function.LocaleFormatter
import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.Action
import dev.ynagai.a2ui.core.protocol.ActionMessage
import dev.ynagai.a2ui.core.protocol.AgentToRendererMessage
import dev.ynagai.a2ui.core.protocol.RendererToAgentMessage
import dev.ynagai.a2ui.core.surface.EvaluationScope
import dev.ynagai.a2ui.core.surface.JsonPointer
import dev.ynagai.a2ui.core.surface.RenderLimits
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
                budget = { BUDGET },
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
            budget = { BUDGET },
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
                    child.evaluationScope, { BUDGET }, {},
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
    fun an_invoke_action_runs_with_the_provenance_a_user_gesture_gives_it() {
        // `openUrl` is the one function that can tell the two invocation contexts apart -- the
        // specification requires it to refuse anything a user gesture did not cause. So it is what
        // pins `dispatch`'s choice of USER_ACTION: swap that for RENDER and the evaluator throws
        // before the opener is reached, leaving `opened` empty.
        val opened = mutableListOf<String>()
        val renderer = A2uiRenderer(A2uiRendererConfig.Default
            .withClock({ FIXED_TIMESTAMP })
            .withUrlOpener({ opened += it }),
        )
            .also { it.applyAll(MESSAGES) }
        val scope = scopeFor("button", renderer)
        val action = A2uiJson.strict.decodeFromString(
            Action.serializer(),
            """{"functionCall":{"call":"openUrl","args":{"url":"https://example.com/a"}}}""",
        )
        scope.dispatch(action)
        assertEquals(listOf("https://example.com/a"), opened)
    }

    @Test
    fun a_write_the_data_model_cannot_take_leaves_it_unchanged_rather_than_raising() {
        val renderer = renderer()
        val before = renderer.state.surfaces.getValue(SURFACE).dataModel
        // `/items` holds three entries, so index 9 would leave a gap and `JsonObject.write` refuses
        // it. The address is the agent's -- it arrives as a component's `path` -- and this is what
        // an input callback calls on every keystroke, so raising here would turn one malformed
        // binding into a crash on the first character typed.
        renderer.write(SURFACE, JsonPointer.parse("/items/9/label"), JsonPrimitive("x"))
        assertEquals(before, renderer.state.surfaces.getValue(SURFACE).dataModel)
        // A root write of a non-object is the other branch that refuses.
        renderer.write(SURFACE, JsonPointer.parse("/"), JsonPrimitive("not an object"))
        assertEquals(before, renderer.state.surfaces.getValue(SURFACE).dataModel)
    }

    @Test
    fun a_registry_does_not_follow_the_map_it_was_built_from() {
        // `@Immutable` promises Compose the contents never change, and `LocalA2uiRegistry` is a
        // *static* composition local, so a registry that tracked its caller's map would change
        // lookups with nothing invalidated.
        val backing = mutableMapOf<String, ComponentRenderer>()
        val registry = ComponentRegistry(backing)
        backing["Text"] = ComponentRenderer { _, _ -> }
        assertNull(registry["Text"])
        assertEquals(emptySet(), registry.types)
    }

    @Test
    fun a_component_that_names_its_own_catalog_resolves_against_it() {
        // `createSurface` may carry no `catalogId` at all, and a component may name one itself.
        // Deciding the resolver from the surface default alone dropped every child of a component
        // whose own catalog this renderer does hold -- a silently childless tree, not an error.
        val renderer = A2uiRenderer(A2uiRendererConfig.Default
            .withClock({ FIXED_TIMESTAMP }),
        )
        renderer.applyAll(
            listOf(
                """{"version":"v1.0","createSurface":{"surfaceId":"$SURFACE"}}""",
                """{"version":"v1.0","updateComponents":{"surfaceId":"$SURFACE","components":[
                  {"id":"root","component":"Column","catalogId":"CATALOG_ID","children":["t1","t2"]},
                  {"id":"t1","component":"Text","catalogId":"CATALOG_ID","text":"one"},
                  {"id":"t2","component":"Text","catalogId":"CATALOG_ID","text":"two"}
                ]}}""",
            ).map {
                A2uiJson.strict.decodeFromString(
                    AgentToRendererMessage.serializer(),
                    it.replace("CATALOG_ID", BasicCatalog.id),
                )
            },
        )
        val scope = A2uiComponentScope(
            renderer = renderer,
            surfaceId = SURFACE,
            component = renderer.state.surfaces.getValue(SURFACE).components.getValue("root"),
            evaluationScope = EvaluationScope.Root,
            budget = { BUDGET },
            onMessage = {},
        )
        assertEquals(listOf("t1", "t2"), scope.children("children").map { it.componentId })
    }

    @Test
    fun a_budget_below_one_buys_no_children_however_far_below_it_is() {
        // `budget` is a public parameter of `A2uiComponent`, so every Int reaches this. The
        // subtraction that leaves room for the children has to be clamped before it happens:
        // `Int.MIN_VALUE - 1` wraps to `Int.MAX_VALUE`, and coercing after the wrap reads the one
        // input furthest below the bound as room for everything.
        val renderer = renderer()
        for (budget in listOf(Int.MIN_VALUE, -1, 0, 1)) {
            val scope = A2uiComponentScope(
                renderer = renderer,
                surfaceId = SURFACE,
                component = renderer.state.surfaces.getValue(SURFACE).components.getValue("list"),
                evaluationScope = EvaluationScope.Root,
                budget = { budget },
                onMessage = {},
            )
            assertEquals(emptyList(), scope.allChildren(), "budget $budget bought children")
        }
    }

    @Test
    fun one_gesture_cannot_open_more_than_the_evaluator_allows() {
        // An event's context is read, not performed. Evaluated with user-action authority each
        // field got its own evaluator and so its own "one open per expression" budget, and this
        // payload opened three windows from a single tap.
        val opened = mutableListOf<String>()
        val renderer = A2uiRenderer(A2uiRendererConfig.Default
            .withClock({ FIXED_TIMESTAMP })
            .withUrlOpener({ opened += it }),
        )
            .also { it.applyAll(MESSAGES) }
        val sent = mutableListOf<RendererToAgentMessage>()
        val scope = scopeFor("button", renderer, onMessage = { sent += it })
        scope.dispatch(
            A2uiJson.strict.decodeFromString(
                Action.serializer(),
                """{"event":{"name":"go","context":{
                  "a":{"call":"openUrl","args":{"url":"https://example.com/1"}},
                  "b":{"call":"openUrl","args":{"url":"https://example.com/2"}},
                  "c":{"call":"openUrl","args":{"url":"https://example.com/3"}}
                }}}""",
            ),
        )
        assertEquals(emptyList(), opened)
        // The event still reaches the agent; only the side effect is refused, and the field that
        // asked for it reports null rather than taking the whole message down with it.
        val message = assertNotNull(sent.singleOrNull() as? ActionMessage)
        assertEquals(setOf("a", "b", "c"), message.context.keys)
    }

    @Test
    fun a_surface_whose_catalog_is_absent_reports_no_children_rather_than_guessing() {
        // No fallback resolver that reads `child` and `children`: those names are the basic
        // catalog's, and guessing them would silently drop `Modal.trigger` and the child inside
        // each `Tabs.tabs` entry even within that catalog.
        val renderer = A2uiRenderer(
            A2uiRendererConfig.Default
                .withCatalogs(emptyList())
                .withClock { FIXED_TIMESTAMP },
        )
        renderer.applyAll(MESSAGES)
        val scope = A2uiComponentScope(
            renderer = renderer,
            surfaceId = SURFACE,
            component = renderer.state.surfaces.getValue(SURFACE).components.getValue("column"),
            evaluationScope = EvaluationScope.Root,
            budget = { BUDGET },
            onMessage = {},
        )
        assertEquals(emptyList(), scope.children("children"))
    }

    @Test
    fun the_renderers_locale_is_what_a_formatting_function_runs_on() {
        // The scope builds its `EvaluationContext` by derivation now, and `.withLocale(...)` is one
        // link in that chain. `A2uiRendererConfigTest` pins that a non-default locale reaches
        // `renderer.locale`; nothing but this pins that it reaches a *formatting function*, so
        // deleting that link leaves every other test green -- a host that asked for
        // `systemLocaleFormatter()` would have silently kept formatting through the placeholder.
        val renderer = A2uiRenderer(
            A2uiRendererConfig.Default
                .withLocale(SHOUTING)
                .withClock { FIXED_TIMESTAMP },
        ).also { it.applyAll(MESSAGES) }
        assertEquals("MONEY:1234.5/JPY", scopeFor("money", renderer).string("text"))
    }

    @Test
    fun the_renderers_evaluation_limits_are_what_bounds_a_payload() {
        // `.withLimits(...)` is the same kind of link, and bounding a hostile payload is what it
        // carries. `maxResultLength = 1` refuses a result the default bound admits, so this fails
        // if the renderer's limits stop reaching the evaluator.
        val bounded = A2uiRenderer(
            A2uiRendererConfig.Default
                .withClock { FIXED_TIMESTAMP }
                .withEvaluationLimits(EvaluationLimits(maxResultLength = 1)),
        ).also { it.applyAll(MESSAGES) }
        assertNull(scopeFor("money", bounded).string("text"))
        // ...and it is the bound doing it, not the payload being broken: the same component
        // resolves under the default limits.
        assertNotNull(scopeFor("money", renderer()).string("text"))
    }

    private fun renderer(): A2uiRenderer =
        A2uiRenderer(A2uiRendererConfig.Default.withClock { FIXED_TIMESTAMP })
            .also { it.applyAll(MESSAGES) }

    private fun scopeFor(
        componentId: String,
        renderer: A2uiRenderer = renderer(),
        onMessage: (RendererToAgentMessage) -> Unit = {},
    ) = A2uiComponentScope(
        renderer = renderer,
        surfaceId = SURFACE,
        component = renderer.state.surfaces.getValue(SURFACE).components.getValue(componentId),
        evaluationScope = EvaluationScope.Root,
        budget = { BUDGET },
        onMessage = onMessage,
    )

    private companion object {
        const val SURFACE = "s"

        /**
         * A formatter whose output no other formatter produces, so a test can tell whether this
         * one is the one that ran. It is not a locale implementation and does not pretend to be.
         */
        val SHOUTING = object : LocaleFormatter by FallbackLocaleFormatter {
            override fun formatCurrency(
                value: Double,
                currency: String,
                decimals: Int?,
                grouping: Boolean,
            ): String = "MONEY:$value/$currency"
        }

        /** The whole surface's budget: these tests are about what a scope resolves, not bounds. */
        val BUDGET = RenderLimits.DEFAULT.maxInstances
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
              {"id":"button","component":"Button","child":"title","action":{"event":{"name":"noop"}}},
              {"id":"money","component":"Text","text":{"call":"formatCurrency","args":{"value":1234.5,"currency":"JPY"}}}
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
