package dev.ynagai.a2ui.core.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The two-key envelope, and the ten messages that travel in it. */
class MessageTest {
    private val json = A2uiJson.strict

    private fun agent(text: String): AgentToRendererMessage = json.decodeFromString(text)

    private fun renderer(text: String): RendererToAgentMessage = json.decodeFromString(text)

    @Test
    fun `every agent-to-renderer message round trips`() {
        val cases = listOf(
            """{"version":"v1.0","createSurface":{"surfaceId":"s","catalogId":"c","components":[{"id":"root","component":"Text","text":"hi"}]}}""",
            """{"version":"v1.0","updateComponents":{"surfaceId":"s","components":[{"id":"root","component":"Text","text":"hi"}]}}""",
            """{"version":"v1.0","updateDataModel":{"surfaceId":"s","path":"/user/name","value":"Alice"}}""",
            """{"version":"v1.0","deleteSurface":{"surfaceId":"s"}}""",
            """{"version":"v1.0","callRendererFunction":{"functionCallId":"c1","callFunction":{"call":"openUrl","catalogId":"cat","args":{"url":"https://example.com"}}}}""",
            """{"version":"v1.0","agentFunctionResponse":{"functionCallId":"c1","value":42}}""",
        )
        for (case in cases) {
            assertEquals(case, json.encodeToString<AgentToRendererMessage>(agent(case)))
        }
    }

    @Test
    fun `every renderer-to-agent message round trips`() {
        val cases = listOf(
            """{"version":"v1.0","action":{"name":"submit","surfaceId":"main","sourceComponentId":"btn","timestamp":"2023-10-27T10:00:00Z","context":{"foo":"bar"}}}""",
            """{"version":"v1.0","callAgentFunction":{"surfaceId":"s","functionCallId":"c1","callFunction":{"call":"ping"}}}""",
            """{"version":"v1.0","rendererFunctionResponse":{"functionCallId":"c1","error":{"code":"E","message":"m"}}}""",
            """{"version":"v1.0","error":{"code":"VALIDATION_FAILED","surfaceId":"main","path":"/components/0/text","message":"Invalid type"}}""",
            """{"version":"v1.0","error":{"code":"FUNCTION_FAILED","message":"boom","functionCallId":"c1"}}""",
        )
        for (case in cases) {
            assertEquals(case, json.encodeToString<RendererToAgentMessage>(renderer(case)))
        }
    }

    @Test
    fun `the envelope requires version and exactly one message`() {
        assertFailsWith<SerializationException> { agent("""{"deleteSurface":{"surfaceId":"s"}}""") }
        assertFailsWith<SerializationException> {
            agent("""{"version":"v0.9","deleteSurface":{"surfaceId":"s"}}""")
        }
        assertFailsWith<SerializationException> { agent("""{"version":"v1.0"}""") }
        assertFailsWith<SerializationException> {
            agent("""{"version":"v1.0","deleteSurface":{"surfaceId":"s"},"updateDataModel":{"surfaceId":"s","value":1}}""")
        }
    }

    @Test
    fun `a renderer-to-agent envelope refuses an agent-to-renderer message`() {
        assertFailsWith<SerializationException> {
            renderer("""{"version":"v1.0","updateDataModel":{"surfaceId":"main","path":"/user/name","value":"Alice"}}""")
        }
    }

    @Test
    fun `a component keeps its catalog-defined properties without interpreting them`() {
        val message = agent(
            """{"version":"v1.0","createSurface":{"surfaceId":"s","components":[
               {"id":"root","component":"Text","text":"hi","accessibility":{"label":"Greeting"},
                "metadata":{"extensions":{"custom_theme":{"variant":"dark"}}}}]}}""",
        )
        val component = assertIs<CreateSurfaceMessage>(message).components!!.single()
        assertEquals("root", component.id)
        assertEquals("Text", component.component)
        assertEquals(
            AccessibilityAttributes(label = DynamicString.Literal("Greeting")),
            component.accessibility,
        )
        assertEquals(mapOf("text" to JsonPrimitive("hi")), component.properties)
        assertTrue(component.metadata?.extensions?.containsKey("custom_theme") == true)
    }

    @Test
    fun `a component requires an id and a type`() {
        assertFailsWith<SerializationException> {
            agent("""{"version":"v1.0","createSurface":{"surfaceId":"s","components":[{"component":"Text","text":"hi"}]}}""")
        }
        assertFailsWith<SerializationException> {
            agent("""{"version":"v1.0","createSurface":{"surfaceId":"s","components":[{"id":"root","text":"hi"}]}}""")
        }
    }

    @Test
    fun `a closed payload rejects an unknown key under strict and keeps it under lenient`() {
        val payload = """{"version":"v1.0","createSurface":{"surfaceId":"s","unexpected":true}}"""
        assertFailsWith<SerializationException> { agent(payload) }
        val lenient = A2uiJson.lenient.decodeFromString<AgentToRendererMessage>(payload)
        assertEquals("s", assertIs<CreateSurfaceMessage>(lenient).surfaceId)
    }

    @Test
    fun `a null component in the list is rejected`() {
        assertFailsWith<SerializationException> {
            agent("""{"version":"v1.0","createSurface":{"surfaceId":"s","components":[null]}}""")
        }
    }

    @Test
    fun `an explicit null value in updateDataModel is a delete rather than an absent value`() {
        val deletion = assertIs<UpdateDataModelMessage>(
            agent("""{"version":"v1.0","updateDataModel":{"surfaceId":"s","path":"/a","value":null}}"""),
        )
        assertEquals(JsonNull, deletion.value)
        assertFailsWith<SerializationException> {
            agent("""{"version":"v1.0","updateDataModel":{"surfaceId":"s","path":"/a"}}""")
        }
    }

    @Test
    fun `callRendererFunction requires the call to name its catalog`() {
        assertFailsWith<SerializationException> {
            agent("""{"version":"v1.0","callRendererFunction":{"functionCallId":"c1","callFunction":{"call":"openUrl"}}}""")
        }
    }

    @Test
    fun `a function response carries a value or an error but never both and never neither`() {
        val nullValue = assertIs<AgentFunctionResponseMessage>(
            agent("""{"version":"v1.0","agentFunctionResponse":{"functionCallId":"c1","value":null}}"""),
        )
        assertEquals(FunctionResponse.Success("c1", JsonNull), nullValue.response)

        assertFailsWith<SerializationException> {
            agent("""{"version":"v1.0","agentFunctionResponse":{"functionCallId":"c1","value":1,"error":{"code":"E","message":"m"}}}""")
        }
        assertFailsWith<SerializationException> {
            agent("""{"version":"v1.0","agentFunctionResponse":{"functionCallId":"c1"}}""")
        }
        assertFailsWith<SerializationException> {
            agent("""{"version":"v1.0","agentFunctionResponse":{"value":1}}""")
        }
    }

    @Test
    fun `a generic error names either its surface or its call but never both and never neither`() {
        val onCall = assertIs<RendererErrorMessage.Generic>(
            renderer("""{"version":"v1.0","error":{"code":"FUNCTION_FAILED","functionCallId":"c1","message":"boom"}}"""),
        )
        assertEquals(RendererErrorMessage.Scope.OnCall("c1"), onCall.scope)

        assertFailsWith<SerializationException> {
            renderer("""{"version":"v1.0","error":{"code":"FUNCTION_FAILED","functionCallId":"c1","surfaceId":"main","message":"boom"}}""")
        }
        assertFailsWith<SerializationException> {
            renderer("""{"version":"v1.0","error":{"code":"FUNCTION_FAILED","message":"boom"}}""")
        }
    }

    @Test
    fun `a reserved code takes the structured error shape`() {
        val validation = assertIs<RendererErrorMessage.Validation>(
            renderer("""{"version":"v1.0","error":{"code":"UNALLOWED_PARENT","surfaceId":"main","path":"/components/1","message":"nope"}}"""),
        )
        assertEquals(ValidationErrorCode.UNALLOWED_PARENT, validation.validationCode)
        assertFailsWith<SerializationException> {
            renderer("""{"version":"v1.0","error":{"code":"UNALLOWED_CHILD","surfaceId":"main","message":"nope"}}""")
        }
        assertFailsWith<A2uiFormatException> {
            RendererErrorMessage.Generic(
                code = "VALIDATION_FAILED",
                message = "m",
                scope = RendererErrorMessage.Scope.OnSurface("main"),
            )
        }
    }

    @Test
    fun `an action keeps the keys the schema leaves open`() {
        val action = assertIs<ActionMessage>(
            renderer(
                """{"version":"v1.0","action":{"name":"submit","surfaceId":"main","sourceComponentId":"btn",
                   "timestamp":"2023-10-27T10:00:00Z","context":{"foo":"bar"},"vendorKey":1}}""",
            ),
        )
        assertEquals(mapOf("vendorKey" to JsonPrimitive(1)), action.additional)
        assertEquals(buildJsonObject { put("foo", JsonPrimitive("bar")) }, action.context)
    }

    @Test
    fun `an action requires the fields the schema marks required`() {
        assertFailsWith<SerializationException> {
            renderer("""{"version":"v1.0","action":{"name":"submit","surfaceId":"main","sourceComponentId":"btn","timestamp":"t"}}""")
        }
    }

    @Test
    fun `an action rejects a metadata object the schema closes`() {
        assertFailsWith<SerializationException> {
            renderer(
                """{"version":"v1.0","action":{"name":"submit","surfaceId":"main","sourceComponentId":"btn",
                   "timestamp":"t","context":{},"metadata":{"unknownProperty":"value"}}}""",
            )
        }
    }

    @Test
    fun `the initial data model must be an object`() {
        assertEquals(
            JsonObject(emptyMap()),
            assertIs<CreateSurfaceMessage>(
                agent("""{"version":"v1.0","createSurface":{"surfaceId":"s","dataModel":{}}}"""),
            ).dataModel,
        )
        assertFailsWith<SerializationException> {
            agent("""{"version":"v1.0","createSurface":{"surfaceId":"s","dataModel":"not an object"}}""")
        }
    }
}
