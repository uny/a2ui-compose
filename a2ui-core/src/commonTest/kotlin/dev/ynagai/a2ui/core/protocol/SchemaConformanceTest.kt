package dev.ynagai.a2ui.core.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Constraints checked against the published v1.0 JSON Schemas rather than against the prose.
 *
 * Each case below cites the keyword it comes from. They are grouped apart from [StrictnessTest]
 * because they are not about wrong-typed values: these are well-typed payloads that the schemas
 * nonetheless reject, and which this model can decide without a catalog in hand — which is what
 * puts them on this side of the protocol-model / validator line.
 */
class SchemaConformanceTest {
    private val json = A2uiJson.strict

    @Test
    fun `a catalog protocol version is held to the schema pattern`() {
        // catalog_definition.json: protocolVersion.pattern — semver, never `v`-prefixed. The
        // envelope's "v1.0" is a different spelling for a different field.
        assertFailsWith<SerializationException> {
            json.decodeFromString<CatalogDefinition>("""{"catalogId":"c","protocolVersion":"v1.0"}""")
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString<CatalogDefinition>("""{"catalogId":"c","protocolVersion":"1"}""")
        }
        for (good in listOf("1.0", "0.9", "1.2.3", "1.0.0-rc.1", "1.0.0+build.5")) {
            assertEquals(
                good,
                json.decodeFromString<CatalogDefinition>(
                    """{"catalogId":"c","protocolVersion":"$good"}""",
                ).effectiveProtocolVersion,
            )
        }
    }

    @Test
    fun `a function requiring user activation may only be renderer-callable`() {
        // catalog_definition.json: $defs.FunctionDefinition.allOf[2] if/then. Security-bearing —
        // an activation-gated function must not be reachable from the agent.
        assertFailsWith<SerializationException> {
            json.decodeFromString<CatalogDefinition>(
                """{"catalogId":"c","functions":{"f":{"returnType":"any","allowedCallers":"agentOnly","requiresUserActivation":true}}}""",
            )
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString<CatalogDefinition>(
                """{"catalogId":"c","functions":{"f":{"returnType":"any","allowedCallers":"rendererOrAgent","requiresUserActivation":true}}}""",
            )
        }
        // Allowed: activation with an explicit or defaulted rendererOnly.
        for (ok in listOf(""""allowedCallers":"rendererOnly",""", "")) {
            json.decodeFromString<CatalogDefinition>(
                """{"catalogId":"c","functions":{"f":{"returnType":"any",$ok"requiresUserActivation":true}}}""",
            )
        }
        // The schema's `if` omits a `required`, so read literally it fires for a definition that
        // omits the key too — which would make agentOnly unusable. Not the intent; still allowed.
        assertEquals(
            AllowedCallers.AGENT_ONLY,
            json.decodeFromString<CatalogDefinition>(
                """{"catalogId":"c","functions":{"f":{"returnType":"any","allowedCallers":"agentOnly"}}}""",
            ).functions.getValue("f").effectiveAllowedCallers,
        )
    }

    @Test
    fun `a composition keyword may not repeat a component type`() {
        // catalog_definition.json: $defs.ComponentDefinition allowedParents/allowedChildren
        // uniqueItems: true.
        for (key in listOf("allowedParents", "allowedChildren")) {
            assertFailsWith<SerializationException> {
                json.decodeFromString<CatalogDefinition>(
                    """{"catalogId":"c","components":{"T":{"$key":["Surface","Surface"]}}}""",
                )
            }
        }
        assertEquals(
            listOf("Surface", "Box"),
            json.decodeFromString<CatalogDefinition>(
                """{"catalogId":"c","components":{"T":{"allowedParents":["Surface","Box"]}}}""",
            ).components.getValue("T").allowedParents,
        )
    }

    @Test
    fun `a catalog may not define the reserved Surface component`() {
        // catalog_definition.json: components.propertyNames.not.const == "Surface".
        assertFailsWith<SerializationException> {
            json.decodeFromString<CatalogDefinition>(
                """{"catalogId":"c","components":{"Surface":{"type":"object"}}}""",
            )
        }
        json.decodeFromString<CatalogDefinition>(
            """{"catalogId":"c","components":{"SurfaceHeader":{"type":"object"}}}""",
        )
    }

    @Test
    fun `only the index system function is a system function`() {
        // common_types.json: FunctionCall.oneOf = [catalog function, IndexSystemFunction], whose
        // `call` is the constant "@index". The `@` namespace *is* reserved -- no catalog may
        // define into it -- but v1.0 populates it with `@index` alone, so a reserved prefix is
        // not a system function and every other `@` name is a call on nothing.
        assertEquals(true, FunctionCall(FunctionCall.INDEX).isSystemFunction)
        assertEquals(false, FunctionCall("@notDefined").isSystemFunction)
        assertEquals(false, FunctionCall("@").isSystemFunction)
        assertEquals(false, FunctionCall("openUrl").isSystemFunction)
    }

    @Test
    fun `an unmodelled key on the open capability objects is carried not refused`() {
        // agent_capabilities.json / renderer_capabilities.json set no additionalProperties:false
        // on their v1.0 object. This is the one place the model was stricter than the schema.
        val agentSource = """{"v1.0":{"supportedCatalogIds":["a"],"futureFlag":true}}"""
        val agent = json.decodeFromString<AgentCapabilities>(agentSource)
        assertEquals(mapOf("futureFlag" to JsonPrimitive(true)), agent.v1.additional)
        assertEquals(agentSource, json.encodeToString(agent))

        val rendererSource = """{"v1.0":{"supportedCatalogIds":["a"],"futureFlag":true}}"""
        val renderer = json.decodeFromString<RendererCapabilities>(rendererSource)
        assertEquals(mapOf("futureFlag" to JsonPrimitive(true)), renderer.v1.additional)
        assertEquals(rendererSource, json.encodeToString(renderer))
    }

    @Test
    fun `supportedCatalogIds is optional for an agent and required for a renderer`() {
        // The asymmetry is the schema's: only renderer_capabilities.json marks it required.
        assertNull(json.decodeFromString<AgentCapabilities>("""{"v1.0":{}}""").v1.supportedCatalogIds)
        assertFailsWith<SerializationException> {
            json.decodeFromString<RendererCapabilities>("""{"v1.0":{}}""")
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString<AgentCapabilities>("""{"v1.0":{"supportedCatalogIds":"a"}}""")
        }
    }

    @Test
    fun `an inline catalog still round trips through renderer capabilities`() {
        val source = """{"v1.0":{"supportedCatalogIds":["a"],"inlineCatalogs":[{"catalogId":"b","protocolVersion":"1.0"}]}}"""
        val decoded = json.decodeFromString<RendererCapabilities>(source)
        assertEquals("b", decoded.v1.inlineCatalogs?.single()?.catalogId)
        assertEquals(source, json.encodeToString(decoded))
    }
}
