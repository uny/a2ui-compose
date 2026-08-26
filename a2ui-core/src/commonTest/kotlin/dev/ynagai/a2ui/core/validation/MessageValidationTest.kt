package dev.ynagai.a2ui.core.validation

import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.CatalogDefinition
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val BASIC: CatalogDefinition =
    A2uiJson.strict.decodeFromString(CatalogDefinition.serializer(), CatalogFixtures.BASIC)

private val TESTING: CatalogDefinition =
    A2uiJson.strict.decodeFromString(CatalogDefinition.serializer(), CatalogFixtures.TESTING)

private val VALIDATOR = CatalogValidator.of(listOf(BASIC, TESTING))

private fun check(
    payload: String,
    direction: MessageDirection = MessageDirection.AGENT_TO_RENDERER,
    catalogId: String? = BASIC.catalogId,
): SchemaValidation =
    VALIDATOR.validateMessage(Json.parseToJsonElement(payload), direction, catalogId)

/**
 * Whole messages, checked the way the conformance harness will check them.
 *
 * The cases here are the ones no per-element check can reach: they are constraints on the envelope
 * or on a list, not on any one component or call.
 */
class MessageValidationTest {
    @Test
    fun accepts_a_create_surface_message() {
        val result = check(
            """
            {"version": "v1.0", "createSurface": {
              "surfaceId": "s",
              "catalogId": "${BASIC.catalogId}",
              "components": [{"id": "root", "component": "Text", "text": "hi"}]
            }}
            """.trimIndent(),
        )
        assertTrue(result.isValid, result.violations.toString())
        assertEquals(emptySet(), result.unsupportedKeywords)
    }

    @Test
    fun rejects_a_create_surface_whose_component_list_is_empty() {
        // `initial_state_validation` #6. `minItems` on the list -- there is no component to check,
        // which is exactly why a per-component pass cannot see it.
        val result = check(
            """
            {"version": "v1.0", "createSurface": {
              "surfaceId": "s", "catalogId": "${BASIC.catalogId}", "components": []
            }}
            """.trimIndent(),
        )
        assertFalse(result.isValid)
    }

    @Test
    fun rejects_a_message_creating_the_reserved_container() {
        // `initial_state_validation` #9 and #10 -- once through `createSurface`, once through
        // `updateComponents`, and the rule is the same both times.
        for (payload in listOf(
            """{"version": "v1.0", "createSurface": {"surfaceId": "s",
                "catalogId": "${BASIC.catalogId}",
                "components": [{"id": "root", "component": "Surface"}]}}""",
            """{"version": "v1.0", "updateComponents": {"surfaceId": "s",
                "components": [{"id": "x", "component": "Surface"}]}}""",
        )) {
            assertFalse(check(payload).isValid, payload)
        }
    }

    @Test
    fun rejects_an_unexpected_key_on_the_component_envelope() {
        // `initial_state_validation` #19.
        val result = check(
            """
            {"version": "v1.0", "updateComponents": {"surfaceId": "s", "components": [
              {"id": "t", "component": "Text", "text": "hi", "unexpected": true}
            ]}}
            """.trimIndent(),
        )
        assertFalse(result.isValid)
    }

    @Test
    fun rejects_an_extension_key_that_is_not_an_identifier() {
        // `initial_state_validation` #13 at the surface level, #16 at the component level.
        for (payload in listOf(
            """{"version": "v1.0", "createSurface": {"surfaceId": "s",
                "catalogId": "${BASIC.catalogId}",
                "metadata": {"extensions": {"bad-key": 1}},
                "components": [{"id": "root", "component": "Text", "text": "x"}]}}""",
            """{"version": "v1.0", "updateComponents": {"surfaceId": "s", "components": [
                {"id": "t", "component": "Text", "text": "x",
                 "metadata": {"extensions": {"123bad": 1}}}]}}""",
        )) {
            assertFalse(check(payload).isValid, payload)
        }
    }

    @Test
    fun rejects_a_renderer_function_call_that_omits_its_catalog_id() {
        // `CallRendererFunctionMessage` composes `FunctionCall` with `required: ["catalogId"]`.
        // The call is well-formed on its own; only the message says the id is not optional here.
        val result = check(
            """
            {"version": "v1.0", "callRendererFunction": {
              "functionCallId": "c1",
              "callFunction": {"call": "required", "args": {"value": "a"}}
            }}
            """.trimIndent(),
        )
        assertFalse(result.isValid)
    }

    @Test
    fun binds_the_catalog_placeholder_from_the_argument_not_from_the_payload() {
        // `call_function_message` #6, which is the case that shows the two are different. The
        // payload names the basic catalog, but the suite binds the placeholder to the testing
        // catalog -- and that one defines only `openUrl` and `pingAgent`, so `required` is not a
        // function `anyFunction` reaches and the message is refused.
        val payload = """
            {"version": "v1.0", "callRendererFunction": {
              "functionCallId": "c1",
              "callFunction": {"call": "required", "catalogId": "${BASIC.catalogId}",
                               "args": {"value": {"nested": "object"}}}
            }}
        """.trimIndent()
        assertFalse(check(payload, catalogId = TESTING.catalogId).isValid)
        // Bound to the basic catalog instead, the same payload is accepted -- `required` takes an
        // unconstrained `value`, so a literal object satisfies it.
        assertTrue(check(payload, catalogId = BASIC.catalogId).isValid)
    }

    @Test
    fun checks_a_renderer_to_agent_message_against_the_other_document() {
        val result = check(
            """
            {"version": "v1.0", "userAction": {
              "surfaceId": "s", "componentId": "b", "action": {"name": "go"}
            }}
            """.trimIndent(),
            direction = MessageDirection.RENDERER_TO_AGENT,
        )
        assertEquals(emptySet(), result.unsupportedKeywords)
    }

    @Test
    fun rejects_a_message_that_names_no_known_type() {
        assertFalse(check("""{"version": "v1.0", "somethingElse": {}}""").isValid)
    }

    @Test
    fun rejects_a_message_at_the_wrong_protocol_version() {
        assertFalse(
            check(
                """{"version": "v0.9", "updateComponents": {"surfaceId": "s",
                    "components": [{"id": "t", "component": "Text", "text": "x"}]}}""",
            ).isValid,
        )
    }

    // --- accessibility ---------------------------------------------------------------------

    @Test
    fun accepts_the_accessibility_attributes_the_protocol_defines() {
        for (attributes in listOf(
            """{"label": "Submit"}""",
            """{"label": {"path": "/labels/submit"}}""",
            """{"description": "Sends the form", "live": "polite"}""",
            """{"hidden": true}""",
            """{"hidden": {"call": "not", "catalogId": "${BASIC.catalogId}",
                 "args": {"value": true}}}""",
            """{"label": "x", "description": "y", "live": "assertive", "hidden": false}""",
        )) {
            val result = check(
                """{"version": "v1.0", "updateComponents": {"surfaceId": "s", "components": [
                    {"id": "t", "component": "Text", "text": "x", "accessibility": $attributes}]}}""",
            )
            assertTrue(result.isValid, "$attributes -> ${result.violations}")
        }
    }

    @Test
    fun rejects_accessibility_attributes_the_protocol_does_not_define() {
        for (attributes in listOf(
            """{"live": "shouty"}""",
            """{"hidden": "yes"}""",
            """{"label": 7}""",
            """{"role": "button"}""",
        )) {
            val result = check(
                """{"version": "v1.0", "updateComponents": {"surfaceId": "s", "components": [
                    {"id": "t", "component": "Text", "text": "x", "accessibility": $attributes}]}}""",
            )
            assertFalse(result.isValid, "$attributes should have been rejected")
        }
    }
}
