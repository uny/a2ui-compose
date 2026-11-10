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
 * root, and the relative form exists at all — plus the upsert and delete semantics of the
 * upstream data models, which the conformance suite's `data_model.yaml` and `data_deletion.yaml`
 * pin and `DataModelConformanceTest` runs.
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
    fun `empty reference tokens are dropped as upstream does`() {
        // Not RFC 6901, under which "//a" has an empty token before "a". data_model.yaml
        // `test_data_model_normalizes_empty_path_segments` and `..._trailing_slash`.
        assertEquals(listOf("a"), JsonPointer.parse("//a").tokens)
        assertEquals(JsonPointer.parse("/a/b"), JsonPointer.parse("//a///b//"))
        assertEquals(JsonPointer.parse("/foo"), JsonPointer.parse("/foo/"))
        assertTrue(JsonPointer.parse("///").isRoot)
        assertEquals(JsonPrimitive(1), model("""{"a": 1}""").resolve(JsonPointer.parse("//a")))
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

    // The five cases below are where 0.3.0 and the upstream data models disagreed; each one is
    // pinned to the upstream behaviour and names the conformance case or blueprint rule it follows.

    @Test
    fun `an absent container is created as an array when the next token is an index`() {
        // data_model.yaml `test_data_model_list_append`; blueprint JSON Pointer rule 2.
        val written = JsonObject(emptyMap()).write(JsonPointer.parse("/items/0"), JsonPrimitive("a"))
        assertEquals("""{"items":["a"]}""", json.encodeToString(JsonObject.serializer(), written))
        // A non-numeric token still creates an object, and so does a leading-zero one, which is
        // not an index (see `a leading-zero index is not an array index`).
        val keyed = JsonObject(emptyMap()).write(JsonPointer.parse("/items/01"), JsonPrimitive("a"))
        assertEquals("""{"items":{"01":"a"}}""", json.encodeToString(JsonObject.serializer(), keyed))
    }

    @Test
    fun `an explicit null deletes rather than stores`() {
        val data = model("""{"user": {"name": "Jane", "temp": 1}}""")
        val deleted = data.write(JsonPointer.parse("/user/temp"), JsonNull)
        assertEquals("""{"user":{"name":"Jane"}}""", json.encodeToString(JsonObject.serializer(), deleted))
    }

    @Test
    fun `deleting from an array empties the element and keeps the length`() {
        // data_deletion.yaml `test_data_deletion_array_index_preservation`; blueprint rule 6.
        // The indices after the deleted element do not move, so a binding to `/xs/2` still reads
        // what it read before.
        val data = model("""{"xs": [1, 2, 3]}""")
        val deleted = data.write(JsonPointer.parse("/xs/1"), JsonNull)
        assertEquals("""{"xs":[1,null,3]}""", json.encodeToString(JsonObject.serializer(), deleted))
        assertEquals(JsonPrimitive(3), deleted.resolve(JsonPointer.parse("/xs/2")))
    }

    @Test
    fun `writing at the end of an array appends`() {
        val data = model("""{"xs": [1]}""")
        assertEquals(
            """{"xs":[1,2]}""",
            json.encodeToString(
                JsonObject.serializer(),
                data.write(JsonPointer.parse("/xs/1"), JsonPrimitive(2)),
            ),
        )
    }

    @Test
    fun `the JSON Patch append token is refused on an array`() {
        // `-` is a non-numeric segment on an array to `web_core`, Python `a2ui_core` and Swift
        // `A2UICore` alike, and all three raise. The prose does not mention it.
        val data = model("""{"xs": [1]}""")
        assertFailsWith<A2uiStateException> {
            data.write(JsonPointer.parse("/xs/-"), JsonPrimitive(9))
        }
        assertFailsWith<A2uiStateException> {
            data.write(JsonPointer.parse("/xs/-/a"), JsonPrimitive(9))
        }
        assertNull(data.resolve(JsonPointer.parse("/xs/-")))
    }

    @Test
    fun `an index past the end of an array pads the gap with nulls`() {
        // data_model.yaml `test_data_model_sparse_list_write_pads_with_null`.
        val data = model("""{"xs": [1, 2]}""")
        assertEquals(
            """{"xs":[1,2,null,null,null,9]}""",
            json.encodeToString(
                JsonObject.serializer(),
                data.write(JsonPointer.parse("/xs/5"), JsonPrimitive(9)),
            ),
        )
        // Padding on the way to a nested write, too.
        assertEquals(
            """{"data":{"lists":[null,{"value":42}]}}""",
            json.encodeToString(
                JsonObject.serializer(),
                JsonObject(emptyMap()).write(JsonPointer.parse("/data/lists/1/value"), JsonPrimitive(42)),
            ),
        )
    }

    @Test
    fun `an index past the maximum is refused rather than padded to`() {
        // data_model.yaml `test_data_model_rejects_excessive_list_index`.
        val data = model("""{"xs": []}""")
        assertFailsWith<A2uiStateException> {
            data.write(JsonPointer.parse("/xs/${JsonPointer.MAX_ARRAY_INDEX + 1}"), JsonPrimitive(1))
        }
        assertFailsWith<A2uiStateException> {
            JsonObject(emptyMap()).write(
                JsonPointer.parse("/ys/${JsonPointer.MAX_ARRAY_INDEX + 1}/a"),
                JsonPrimitive(1),
            )
        }
        // Past Int as well: still an index, still refused, never an object key.
        assertFailsWith<A2uiStateException> {
            JsonObject(emptyMap()).write(JsonPointer.parse("/ys/99999999999"), JsonPrimitive(1))
        }
        assertFailsWith<A2uiStateException> {
            data.write(JsonPointer.parse("/xs/99999999999"), JsonPrimitive(1))
        }
    }

    @Test
    fun `a primitive in the way is an error rather than overwritten`() {
        // data_model.yaml `test_data_model_rejects_write_through_primitive` and
        // `..._list_primitive`; blueprint rule 3.
        val data = model("""{"user": "anonymous", "xs": [1]}""")
        val error = assertFailsWith<A2uiStateException> {
            data.write(JsonPointer.parse("/user/name"), JsonPrimitive("Jane"))
        }
        assertTrue("/user" in error.message.orEmpty(), error.message)
        assertFailsWith<A2uiStateException> {
            data.write(JsonPointer.parse("/xs/0/a"), JsonPrimitive("Jane"))
        }
        // A JSON null in the way is absent, not a primitive, and is replaced.
        val withNull = model("""{"user": null}""")
        assertEquals(
            JsonPrimitive("Jane"),
            withNull.write(JsonPointer.parse("/user/name"), JsonPrimitive("Jane"))
                .resolve(JsonPointer.parse("/user/name")),
        )
        // Replacing the primitive itself is still an ordinary upsert.
        assertEquals(
            """{"user":{"name":"Jane"},"xs":[1]}""",
            json.encodeToString(
                JsonObject.serializer(),
                data.write(JsonPointer.parse("/user"), model("""{"name": "Jane"}""")),
            ),
        )
    }

    @Test
    fun `a forbidden segment is refused on reads and writes`() {
        // data_model.yaml `test_data_model_rejects_forbidden_path_segments`; blueprint rule 4.
        for (token in listOf("__proto__", "constructor", "prototype")) {
            assertFailsWith<A2uiFormatException> { JsonPointer.parse("/$token/x") }
            assertFailsWith<A2uiStateException> {
                JsonObject(emptyMap()).write(JsonPointer.of(token), JsonPrimitive(1))
            }
            assertNull(model("""{"$token": 1}""").resolve(JsonPointer.of(token)))
        }
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
    fun `a pointer deeper than the maximum is rejected rather than recursed on`() {
        // `write` recurses once per token and the path is the agent's to choose, so an
        // unbounded pointer lets the agent pick the renderer's stack depth. A StackOverflowError
        // is an Error, so nothing in the message loop would catch it.
        val atMax = "/a".repeat(JsonPointer.MAX_TOKENS)
        assertEquals(JsonPointer.MAX_TOKENS, JsonPointer.parse(atMax).tokens.size)
        assertFailsWith<A2uiFormatException> {
            JsonPointer.parse("/a".repeat(JsonPointer.MAX_TOKENS + 1))
        }
        // Assembled rather than parsed, so `write` refuses it too.
        var built = JsonPointer.ROOT
        repeat(JsonPointer.MAX_TOKENS + 1) { built = built.child("a") }
        assertFailsWith<A2uiStateException> {
            JsonObject(emptyMap()).write(built, JsonPrimitive(1))
        }
    }

    @Test
    fun `deleting something that is not there changes nothing`() {
        // A delete must not materialize the containers on the way to an absent member: a
        // binding on `/user` would flip from absent-so-placeholder to a present empty object.
        assertEquals(
            JsonObject(emptyMap()),
            JsonObject(emptyMap()).write(JsonPointer.parse("/user/name"), JsonNull),
        )
        // Nor may it replace whatever stands in the path.
        val scalar = model("""{"user": "anonymous"}""")
        assertEquals(scalar, scalar.write(JsonPointer.parse("/user/name"), JsonNull))
        // Nor raise over a token the write would have refused, since it never gets that far.
        val xs = model("""{"xs": [1]}""")
        assertEquals(xs, xs.write(JsonPointer.parse("/xs/-/a"), JsonNull))
        // An out-of-range array delete does nothing, at the end and past it alike: it does not
        // pad the array out to an index that was never set.
        assertEquals(xs, xs.write(JsonPointer.parse("/xs/1"), JsonNull))
        assertEquals(xs, xs.write(JsonPointer.parse("/xs/9"), JsonNull))
        // The delete that does address something still deletes, keeping the length.
        assertEquals(
            model("""{"xs": [null]}"""),
            xs.write(JsonPointer.parse("/xs/0"), JsonNull),
        )
    }

    @Test
    fun `a nested write into an array element keeps its siblings`() {
        val data = model("""{"xs": [{"a": 1}, {"a": 2}]}""")
        assertEquals(
            """{"xs":[{"a":1,"b":9},{"a":2}]}""",
            json.encodeToString(
                JsonObject.serializer(),
                data.write(JsonPointer.parse("/xs/0/b"), JsonPrimitive(9)),
            ),
        )
    }

    @Test
    fun `a relative pointer has no meaning as a write address`() {
        assertFailsWith<IllegalArgumentException> {
            JsonObject(emptyMap()).write(JsonPointer.parse("name"), JsonPrimitive("x"))
        }
    }
}
