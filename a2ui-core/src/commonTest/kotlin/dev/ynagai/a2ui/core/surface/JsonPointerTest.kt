package dev.ynagai.a2ui.core.surface

import dev.ynagai.a2ui.core.protocol.A2uiFormatException
import dev.ynagai.a2ui.core.protocol.A2uiJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JSON Pointer is the whole of A2UI's data binding, and `updateDataModel` uses the same syntax as
 * a write address. The cases below pin the two places A2UI departs from RFC 6901 — `/` means the
 * root, and the relative form exists at all — plus the upsert and delete semantics the
 * specification spells out for `updateDataModel`.
 */
class JsonPointerTest {
    private val json = A2uiJson.strict

    private fun model(text: String) = json.parseToJsonElement(text) as JsonObject

    @Test
    fun `empty and slash both address the whole data model`() {
        assertTrue(JsonPointer.parse("").isRoot)
        assertTrue(JsonPointer.parse("/").isRoot)
        assertEquals(JsonPointer.ROOT, JsonPointer.parse("/"))
    }

    @Test
    fun `a leading slash makes a pointer absolute and its absence makes it relative`() {
        val absolute = JsonPointer.parse("/user/name")
        assertTrue(absolute.isAbsolute)
        assertEquals(listOf("user", "name"), absolute.tokens)

        val relative = JsonPointer.parse("name")
        assertTrue(!relative.isAbsolute)
        assertEquals(listOf("name"), relative.tokens)
    }

    @Test
    fun `escapes round trip in the order the RFC fixes`() {
        // `~1` must decode before `~0`, or the encoded form of `~1` would become a separator.
        val pointer = JsonPointer.parse("/a~1b/c~0d")
        assertEquals(listOf("a/b", "c~d"), pointer.tokens)
        assertEquals("/a~1b/c~0d", pointer.toString())
        assertEquals("m~0n", JsonPointer.parse("m~0n").toString())
    }

    @Test
    fun `a dangling escape is rejected rather than read literally`() {
        assertFailsWith<A2uiFormatException> { JsonPointer.parse("/a~2b") }
        assertFailsWith<A2uiFormatException> { JsonPointer.parse("/trailing~") }
    }

    @Test
    fun `an empty reference token addresses the empty-string key`() {
        // RFC 6901: "/" is root per A2UI, but "//a" still has one empty token before "a".
        assertEquals(listOf("", "a"), JsonPointer.parse("//a").tokens)
        assertEquals(JsonPrimitive(1), model("""{"": {"a": 1}}""").resolve(JsonPointer.parse("//a")))
    }

    @Test
    fun `resolution walks objects and arrays and returns null for what is absent`() {
        val data = model("""{"employees": [{"name": "Alice"}, {"name": "Bob"}]}""")
        assertEquals(JsonPrimitive("Bob"), data.resolve(JsonPointer.parse("/employees/1/name")))
        assertNull(data.resolve(JsonPointer.parse("/employees/9/name")))
        assertNull(data.resolve(JsonPointer.parse("/missing/deeply")))
    }

    @Test
    fun `a leading-zero index is not an array index`() {
        // Reading `01` as 1 would let two spellings address the same element.
        val data = model("""{"xs": [10, 20]}""")
        assertEquals(JsonPrimitive(20), data.resolve(JsonPointer.parse("/xs/1")))
        assertNull(data.resolve(JsonPointer.parse("/xs/01")))
    }

    @Test
    fun `writing upserts and creates the containers on the way`() {
        val written = JsonObject(emptyMap())
            .write(JsonPointer.parse("/user/name"), JsonPrimitive("Jane"))
        assertEquals("""{"user":{"name":"Jane"}}""", json.encodeToString(JsonObject.serializer(), written))

        val replaced = written.write(JsonPointer.parse("/user/name"), JsonPrimitive("Alice"))
        assertEquals(JsonPrimitive("Alice"), replaced.resolve(JsonPointer.parse("/user/name")))
    }

    @Test
    fun `an absent container is created as an object even when the token looks like an index`() {
        val written = JsonObject(emptyMap()).write(JsonPointer.parse("/items/0"), JsonPrimitive("a"))
        assertEquals("""{"items":{"0":"a"}}""", json.encodeToString(JsonObject.serializer(), written))
    }

    @Test
    fun `an explicit null deletes rather than stores`() {
        val data = model("""{"user": {"name": "Jane", "temp": 1}}""")
        val deleted = data.write(JsonPointer.parse("/user/temp"), JsonNull)
        assertEquals("""{"user":{"name":"Jane"}}""", json.encodeToString(JsonObject.serializer(), deleted))
    }

    @Test
    fun `deleting from an array removes the element and shifts the rest`() {
        val data = model("""{"xs": [1, 2, 3]}""")
        val deleted = data.write(JsonPointer.parse("/xs/1"), JsonNull)
        assertEquals("""{"xs":[1,3]}""", json.encodeToString(JsonObject.serializer(), deleted))
    }

    @Test
    fun `writing at the end of an array or at the append token appends`() {
        val data = model("""{"xs": [1]}""")
        assertEquals(
            """{"xs":[1,2]}""",
            json.encodeToString(
                JsonObject.serializer(),
                data.write(JsonPointer.parse("/xs/1"), JsonPrimitive(2)),
            ),
        )
        assertEquals(
            """{"xs":[1,9]}""",
            json.encodeToString(
                JsonObject.serializer(),
                data.write(JsonPointer.parse("/xs/-"), JsonPrimitive(9)),
            ),
        )
    }

    @Test
    fun `an index past the end of an array is rejected rather than leaving a gap`() {
        val data = model("""{"xs": [1]}""")
        assertFailsWith<A2uiStateException> {
            data.write(JsonPointer.parse("/xs/5"), JsonPrimitive(2))
        }
    }

    @Test
    fun `a scalar in the way is overwritten because updateDataModel is an upsert`() {
        val data = model("""{"user": "anonymous"}""")
        val written = data.write(JsonPointer.parse("/user/name"), JsonPrimitive("Jane"))
        assertEquals(JsonPrimitive("Jane"), written.resolve(JsonPointer.parse("/user/name")))
    }

    @Test
    fun `a root write replaces the whole model and a root null empties it`() {
        val data = model("""{"a": 1}""")
        val replaced = data.write(JsonPointer.ROOT, model("""{"b": 2}"""))
        assertEquals("""{"b":2}""", json.encodeToString(JsonObject.serializer(), replaced))
        assertEquals(JsonObject(emptyMap()), data.write(JsonPointer.ROOT, JsonNull))
    }

    @Test
    fun `a root write of a non-object is rejected`() {
        assertFailsWith<A2uiStateException> {
            JsonObject(emptyMap()).write(JsonPointer.ROOT, JsonPrimitive(5))
        }
    }

    @Test
    fun `a relative pointer has no meaning as a write address`() {
        assertFailsWith<IllegalArgumentException> {
            JsonObject(emptyMap()).write(JsonPointer.parse("name"), JsonPrimitive("x"))
        }
    }
}
