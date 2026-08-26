package dev.ynagai.a2ui.core.surface

import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.AgentToRendererMessage
import dev.ynagai.a2ui.core.protocol.FunctionResponse
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The processor is where the specification's ordering MUSTs live — a surface cannot be created
 * twice, and cannot be updated or deleted before it exists. Everything catalog-shaped is checked
 * *not* to be rejected here, because rejecting it would break the progressive rendering the
 * adjacency-list model is built around.
 */
class MessageProcessorTest {
    private val json = A2uiJson.strict

    private fun message(text: String): AgentToRendererMessage =
        json.decodeFromString(AgentToRendererMessage.serializer(), text)

    private fun fold(vararg messages: String): ProcessResult =
        MessageProcessor.applyAll(RendererState(), messages.map(::message))

    private val created =
        """{"version": "v1.0", "createSurface": {"surfaceId": "s", "catalogId": "c"}}"""

    @Test
    fun `createSurface records the settings the message fixed`() {
        val result = fold(
            """{"version": "v1.0", "createSurface": {"surfaceId": "s", "catalogId": "c", "sendDataModel": true}}""",
        )
        val surface = result.state.surface("s")!!
        assertEquals("c", surface.catalogId)
        assertTrue(surface.sendDataModel)
        assertEquals(listOf(RendererEffect.SurfaceCreated("s")), result.effects)
    }

    @Test
    fun `sendDataModel defaults to false when the message omits it`() {
        assertTrue(!fold(created).state.surface("s")!!.sendDataModel)
    }

    @Test
    fun `createSurface may carry the opening components and data model inline`() {
        val result = fold(
            """
            {"version": "v1.0", "createSurface": {
              "surfaceId": "s",
              "components": [{"id": "root", "component": "Text", "text": "hi"}],
              "dataModel": {"user": {"name": "Jane"}}
            }}
            """.trimIndent(),
        )
        val surface = result.state.surface("s")!!
        assertTrue(surface.isRenderable)
        assertEquals(JsonPrimitive("Jane"), surface.read(JsonPointer.parse("/user/name")))
        assertEquals(
            listOf(
                RendererEffect.SurfaceCreated("s"),
                RendererEffect.ComponentsUpdated("s", listOf("root")),
                RendererEffect.DataModelUpdated("s", JsonPointer.ROOT),
            ),
            result.effects,
        )
    }

    @Test
    fun `creating a surface that already exists is rejected`() {
        val failure = assertFailsWith<A2uiStateException> { fold(created, created) }
        assertEquals("s", failure.surfaceId)
    }

    @Test
    fun `a surface may be recreated once it has been deleted`() {
        val result = fold(
            created,
            """{"version": "v1.0", "deleteSurface": {"surfaceId": "s"}}""",
            created,
        )
        assertEquals(setOf("s"), result.state.surfaces.keys)
    }

    @Test
    fun `updating a surface that was never created is rejected`() {
        assertFailsWith<A2uiStateException> {
            fold("""{"version": "v1.0", "updateComponents": {"surfaceId": "s", "components": [{"id": "root", "component": "Text"}]}}""")
        }
        assertFailsWith<A2uiStateException> {
            fold("""{"version": "v1.0", "updateDataModel": {"surfaceId": "s", "value": {}}}""")
        }
        assertFailsWith<A2uiStateException> {
            fold("""{"version": "v1.0", "deleteSurface": {"surfaceId": "s"}}""")
        }
    }

    @Test
    fun `components accumulate across messages and may arrive before their parent`() {
        val result = fold(
            created,
            """{"version": "v1.0", "updateComponents": {"surfaceId": "s", "components": [{"id": "label", "component": "Text", "text": "hi"}]}}""",
            """{"version": "v1.0", "updateComponents": {"surfaceId": "s", "components": [{"id": "root", "component": "Card", "child": "label"}]}}""",
        )
        val surface = result.state.surface("s")!!
        assertEquals(setOf("label", "root"), surface.components.keys)
        assertTrue(surface.isRenderable)
    }

    @Test
    fun `updateDataModel folds successive writes into one model`() {
        val result = fold(
            created,
            """{"version": "v1.0", "updateDataModel": {"surfaceId": "s", "path": "/user/firstName", "value": "Alice"}}""",
            """{"version": "v1.0", "updateDataModel": {"surfaceId": "s", "path": "/user/lastName", "value": "Smith"}}""",
            """{"version": "v1.0", "updateDataModel": {"surfaceId": "s", "path": "/user/firstName", "value": null}}""",
        )
        val surface = result.state.surface("s")!!
        assertEquals(JsonPrimitive("Smith"), surface.read(JsonPointer.parse("/user/lastName")))
        assertNull(surface.read(JsonPointer.parse("/user/firstName")))
    }

    @Test
    fun `omitting the path replaces the whole data model`() {
        val result = fold(
            created,
            """{"version": "v1.0", "updateDataModel": {"surfaceId": "s", "path": "/a", "value": 1}}""",
            """{"version": "v1.0", "updateDataModel": {"surfaceId": "s", "value": {"b": 2}}}""",
        )
        assertEquals(
            json.parseToJsonElement("""{"b": 2}""") as JsonObject,
            result.state.surface("s")!!.dataModel,
        )
    }

    @Test
    fun `a relative path is rejected as a write address`() {
        val failure = assertFailsWith<A2uiStateException> {
            fold(
                created,
                """{"version": "v1.0", "updateDataModel": {"surfaceId": "s", "path": "user/name", "value": "x"}}""",
            )
        }
        assertEquals("s", failure.surfaceId)
    }

    @Test
    fun `a malformed path is rejected as a state error naming the surface`() {
        // `JsonPointer.parse` signals this as `A2uiFormatException`, which is a
        // `SerializationException` and shares no supertype with `A2uiStateException` — a
        // renderer catching the latter to answer with a surface-scoped `error` would otherwise
        // not catch a malformed path at all.
        val failure = assertFailsWith<A2uiStateException> {
            fold(
                created,
                """{"version": "v1.0", "updateDataModel": {"surfaceId": "s", "path": "/a~2b", "value": 1}}""",
            )
        }
        assertEquals("s", failure.surfaceId)
    }

    @Test
    fun `a path deeper than the maximum is rejected instead of overflowing the stack`() {
        val deep = "/a".repeat(JsonPointer.MAX_TOKENS + 1)
        val failure = assertFailsWith<A2uiStateException> {
            fold(
                created,
                """{"version": "v1.0", "updateDataModel": {"surfaceId": "s", "path": "$deep", "value": 1}}""",
            )
        }
        assertEquals("s", failure.surfaceId)
    }

    @Test
    fun `a write failure names the surface it happened on`() {
        val failure = assertFailsWith<A2uiStateException> {
            fold(
                created,
                """{"version": "v1.0", "updateDataModel": {"surfaceId": "s", "value": 5}}""",
            )
        }
        assertEquals("s", failure.surfaceId)
    }

    @Test
    fun `deleteSurface removes the components and data with the surface`() {
        val result = fold(
            created,
            """{"version": "v1.0", "updateDataModel": {"surfaceId": "s", "path": "/a", "value": 1}}""",
            """{"version": "v1.0", "deleteSurface": {"surfaceId": "s"}}""",
        )
        assertNull(result.state.surface("s"))
        assertEquals(RendererEffect.SurfaceDeleted("s"), result.effects.last())
    }

    @Test
    fun `surfaces are kept apart from one another`() {
        val result = fold(
            created,
            """{"version": "v1.0", "createSurface": {"surfaceId": "t"}}""",
            """{"version": "v1.0", "updateDataModel": {"surfaceId": "s", "path": "/a", "value": 1}}""",
        )
        assertEquals(JsonPrimitive(1), result.state.surface("s")!!.read(JsonPointer.parse("/a")))
        assertNull(result.state.surface("t")!!.read(JsonPointer.parse("/a")))
    }

    @Test
    fun `callRendererFunction is reported rather than folded into state`() {
        val result = fold(
            """{"version": "v1.0", "callRendererFunction": {"functionCallId": "1", "callFunction": {"call": "openUrl", "catalogId": "c"}}}""",
        )
        assertEquals(RendererState(), result.state)
        val effect = assertIs<RendererEffect.RendererFunctionRequested>(result.effects.single())
        assertEquals("1", effect.functionCallId)
        assertEquals("openUrl", effect.call.call)
    }

    @Test
    fun `agentFunctionResponse is reported rather than folded into state`() {
        val result = fold(
            """{"version": "v1.0", "agentFunctionResponse": {"functionCallId": "1", "value": 42}}""",
        )
        assertEquals(RendererState(), result.state)
        val effect = assertIs<RendererEffect.AgentFunctionResponded>(result.effects.single())
        assertIs<FunctionResponse.Success>(effect.response)
    }

    @Test
    fun `a component the catalog would reject is still folded into state`() {
        // The protocol cannot tell a catalog-defined property from an unknown one, so refusing
        // here would refuse every real component. The validator resolves this against the catalog.
        val result = fold(
            created,
            """{"version": "v1.0", "updateComponents": {"surfaceId": "s", "components": [{"id": "root", "component": "NoSuchComponent", "whatever": true}]}}""",
        )
        assertEquals("NoSuchComponent", result.state.surface("s")!!.root?.component)
    }
}
