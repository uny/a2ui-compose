package dev.ynagai.a2ui.core.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The `Dynamic*` unions decide their branch by key presence, which is the rule the specification's
 * `dynamic_value_validation` cases pin down. Reading them by "whichever branch happens to parse"
 * would accept a malformed binding as a literal object, so each rejection below is checked
 * alongside the literal it must not be confused with.
 */
class DynamicValueTest {
    private val json = A2uiJson.strict

    private fun value(text: String): DynamicValue =
        json.decodeFromString(DynamicValueSerializer, text)

    @Test
    fun `literal of every permitted json type is read as a literal`() {
        assertEquals(DynamicValue.Literal(JsonPrimitive("hello")), value(""""hello""""))
        assertEquals(DynamicValue.Literal(JsonPrimitive(42)), value("42"))
        assertEquals(DynamicValue.Literal(JsonPrimitive(true)), value("true"))
        assertEquals(
            DynamicValue.Literal(json.parseToJsonElement("""[1, "two", {"three": 3}]""")),
            value("""[1, "two", {"three": 3}]"""),
        )
    }

    @Test
    fun `object literal without a discriminator key is a literal`() {
        val vegaLite = """{"vega": "lite", "spec": {"data": {"values": []}}}"""
        assertEquals(DynamicValue.Literal(json.parseToJsonElement(vegaLite)), value(vegaLite))
    }

    @Test
    fun `null is not a permitted value`() {
        assertFailsWith<SerializationException> { value("null") }
    }

    @Test
    fun `path key selects DataBinding and call key selects FunctionCall`() {
        assertEquals(DataBinding("/my/data/pointer"), value("""{"path": "/my/data/pointer"}"""))
        assertEquals(
            FunctionCall("openUrl", args = mapOf("url" to JsonPrimitive("https://example.com"))),
            value("""{"call": "openUrl", "args": {"url": "https://example.com"}}"""),
        )
    }

    @Test
    fun `a malformed DataBinding is rejected rather than read as an object literal`() {
        assertFailsWith<SerializationException> {
            value("""{"path": "/my/data/pointer", "extra": "field"}""")
        }
    }

    @Test
    fun `a malformed FunctionCall is rejected rather than read as an object literal`() {
        assertFailsWith<SerializationException> {
            value("""{"call": "openUrl", "args": {"url": "https://x"}, "extra": "field"}""")
        }
    }

    @Test
    fun `an object carrying both discriminators is ambiguous and rejected`() {
        assertFailsWith<SerializationException> { value("""{"path": "/x", "call": "openUrl"}""") }
    }

    @Test
    fun `lenient tolerates the extra key that strict rejects`() {
        val decoded = A2uiJson.lenient.decodeFromString(
            DynamicValueSerializer,
            """{"path": "/my/data/pointer", "extra": "field"}""",
        )
        assertEquals(DataBinding("/my/data/pointer"), decoded)
    }

    @Test
    fun `typed unions accept their own literal and refuse the others`() {
        assertEquals(
            DynamicString.Literal("hi"),
            json.decodeFromString(DynamicStringSerializer, """"hi""""),
        )
        assertEquals(
            DynamicNumber.Literal(1.5),
            json.decodeFromString(DynamicNumberSerializer, "1.5"),
        )
        assertEquals(
            DynamicBoolean.Literal(true),
            json.decodeFromString(DynamicBooleanSerializer, "true"),
        )
        assertEquals(
            DynamicStringList.Literal(listOf("a", "b")),
            json.decodeFromString(DynamicStringListSerializer, """["a", "b"]"""),
        )

        assertFailsWith<SerializationException> {
            json.decodeFromString(DynamicStringSerializer, "42")
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString(DynamicNumberSerializer, """"42"""")
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString(DynamicBooleanSerializer, """"true"""")
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString(DynamicStringListSerializer, """["a", 2]""")
        }
    }

    @Test
    fun `every union routes a binding and a call through the same two branches`() {
        assertEquals(DataBinding("/x"), json.decodeFromString(DynamicStringSerializer, """{"path": "/x"}"""))
        assertEquals(DataBinding("/x"), json.decodeFromString(DynamicNumberSerializer, """{"path": "/x"}"""))
        assertEquals(DataBinding("/x"), json.decodeFromString(DynamicBooleanSerializer, """{"path": "/x"}"""))
        assertEquals(
            FunctionCall("f"),
            json.decodeFromString(DynamicStringListSerializer, """{"call": "f"}"""),
        )
    }

    @Test
    fun `an integral number survives a round trip as an integer`() {
        assertEquals("42", json.encodeToString(DynamicNumberSerializer, DynamicNumber.Literal(42.0)))
        assertEquals("1.5", json.encodeToString(DynamicNumberSerializer, DynamicNumber.Literal(1.5)))
    }

    @Test
    fun `absent args stays distinct from empty args`() {
        val absent = json.decodeFromString(FunctionCall.serializer(), """{"call": "ping"}""")
        val empty = json.decodeFromString(FunctionCall.serializer(), """{"call": "ping", "args": {}}""")
        assertEquals(null, absent.args)
        assertEquals(emptyMap(), empty.args)
        assertEquals("""{"call":"ping"}""", json.encodeToString(FunctionCall.serializer(), absent))
        assertEquals("""{"call":"ping","args":{}}""", json.encodeToString(FunctionCall.serializer(), empty))
    }

    @Test
    fun `unions round trip back to the json they came from`() {
        val cases = listOf(
            """"hello"""",
            "42",
            "true",
            """{"vega":"lite"}""",
            """{"path":"/a/b"}""",
            """{"call":"openUrl","args":{"url":"https://example.com"}}""",
        )
        for (case in cases) {
            assertEquals(case, json.encodeToString(DynamicValueSerializer, value(case)))
        }
    }

    @Test
    fun `a FunctionCall knows whether it names a system function`() {
        assertEquals(true, FunctionCall(FunctionCall.INDEX).isSystemFunction)
        assertEquals(false, FunctionCall("openUrl").isSystemFunction)
    }

    @Test
    fun `a bound value is readable on its own`() {
        val condition = json.decodeFromString(BoundValueSerializer, """{"call":"required"}""")
        assertEquals(FunctionCall("required"), condition)
        assertFailsWith<SerializationException> {
            json.decodeFromString(BoundValueSerializer, buildJsonObject { }.toString())
        }
    }
}
