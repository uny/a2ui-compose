package dev.ynagai.a2ui.core.surface

import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.ChildList
import dev.ynagai.a2ui.core.protocol.Component
import dev.ynagai.a2ui.core.protocol.ComponentSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The surface model is the adjacency list the specification describes, so what is checked here is
 * that it behaves like one: definitions may arrive in any order, `root` is what makes the surface
 * renderable, and a reference to something that has not arrived is skipped rather than raised.
 *
 * The resolver below stands in for the catalog. It reads `child` and `children`, which is right
 * for the components these tests build and deliberately not shipped in the library — see
 * [ChildResolver].
 */
class SurfaceModelTest {
    private val json = A2uiJson.strict

    private val resolver = ChildResolver { component ->
        buildList {
            component.properties["child"]?.let {
                add(ChildReference.Single("child", (it as JsonPrimitive).content))
            }
            component.properties["children"]?.let {
                add(json.decodeFromJsonElement(ChildList.serializer(), it).asReference("children"))
            }
        }
    }

    private fun component(text: String): Component =
        json.decodeFromString(ComponentSerializer, text)

    private fun surface(vararg components: String, data: String = "{}"): SurfaceModel =
        SurfaceModel(
            surfaceId = "s",
            dataModel = json.parseToJsonElement(data) as JsonObject,
        ).withComponents(components.map(::component))

    @Test
    fun `a surface is renderable only once root arrives`() {
        val buffered = surface("""{"id": "label", "component": "Text", "text": "hi"}""")
        assertTrue(!buffered.isRenderable)
        assertNull(buffered.root)
        // Components that arrived early are held, not dropped.
        assertEquals("Text", buffered.component("label")?.component)

        val rooted = buffered.withComponents(
            listOf(component("""{"id": "root", "component": "Card", "child": "label"}""")),
        )
        assertTrue(rooted.isRenderable)
    }

    @Test
    fun `updateComponents upserts by id rather than replacing the map`() {
        val first = surface("""{"id": "root", "component": "Card", "child": "a"}""")
        val second = first.withComponents(
            listOf(component("""{"id": "a", "component": "Text", "text": "one"}""")),
        )
        val third = second.withComponents(
            listOf(component("""{"id": "a", "component": "Text", "text": "two"}""")),
        )
        assertEquals(2, third.components.size)
        assertEquals(JsonPrimitive("two"), third.component("a")?.properties?.get("text"))
    }

    @Test
    fun `the walk follows id references in the order the resolver reports them`() {
        val model = surface(
            """{"id": "root", "component": "Column", "children": ["a", "b"]}""",
            """{"id": "a", "component": "Text", "text": "one"}""",
            """{"id": "b", "component": "Card", "child": "c"}""",
            """{"id": "c", "component": "Text", "text": "two"}""",
        )
        assertEquals(listOf("root", "a", "b", "c"), model.walk(resolver).map { it.first.id })
    }

    @Test
    fun `a reference to a component that has not arrived is skipped`() {
        val model = surface(
            """{"id": "root", "component": "Column", "children": ["a", "missing", "b"]}""",
            """{"id": "a", "component": "Text", "text": "one"}""",
            """{"id": "b", "component": "Text", "text": "two"}""",
        )
        assertEquals(listOf("root", "a", "b"), model.walk(resolver).map { it.first.id })
    }

    @Test
    fun `a cycle in the adjacency list ends the walk instead of hanging it`() {
        val model = surface(
            """{"id": "root", "component": "Card", "child": "a"}""",
            """{"id": "a", "component": "Card", "child": "root"}""",
        )
        assertEquals(listOf("root", "a"), model.walk(resolver).map { it.first.id })
    }

    @Test
    fun `a template is instantiated once per item — each in its own collection scope`() {
        val model = surface(
            """{"id": "root", "component": "List", "children": {"componentId": "row", "path": "/employees"}}""",
            """{"id": "row", "component": "Text", "text": {"path": "name"}}""",
            data = """{"employees": [{"name": "Alice"}, {"name": "Bob"}]}""",
        )
        val walked = model.walk(resolver)
        assertEquals(listOf("root", "row", "row"), walked.map { it.first.id })

        val (_, firstScope) = walked[1]
        val (_, secondScope) = walked[2]
        assertEquals(0, firstScope.currentIndex())
        assertEquals(1, secondScope.currentIndex())
        // A relative binding measures from the item; an absolute one still reads the root.
        assertEquals(
            JsonPrimitive("Alice"),
            model.read(JsonPointer.parse("name"), firstScope),
        )
        assertEquals(
            JsonPrimitive("Bob"),
            model.read(JsonPointer.parse("name"), secondScope),
        )
    }

    @Test
    fun `a template bound to something that is not an array renders nothing`() {
        val model = surface(
            """{"id": "root", "component": "List", "children": {"componentId": "row", "path": "/nope"}}""",
            """{"id": "row", "component": "Text", "text": "x"}""",
        )
        assertEquals(listOf("root"), model.walk(resolver).map { it.first.id })
    }

    @Test
    fun `an absolute binding inside a template reaches back out to the root scope`() {
        val model = surface(data = """{"company": "Acme", "employees": [{"name": "Alice"}]}""")
        val scope = EvaluationScope.Root.iterate(JsonPointer.parse("/employees"), 0)
        assertEquals(JsonPrimitive("Acme"), model.read(JsonPointer.parse("/company"), scope))
        assertEquals(JsonPrimitive("Alice"), model.read(JsonPointer.parse("name"), scope))
    }

    @Test
    fun `nested templates measure from the innermost item`() {
        val outer = EvaluationScope.Root.iterate(JsonPointer.parse("/rows"), 2)
        val inner = outer.iterate(JsonPointer.parse("cells"), 1)
        assertEquals(JsonPointer.parse("/rows/2/cells/1/text"), inner.rebase(JsonPointer.parse("text")))
        assertEquals(1, inner.currentIndex())
    }

    @Test
    fun `a relative binding outside a template resolves from the root`() {
        val model = surface(data = """{"name": "Alice"}""")
        assertEquals(JsonPrimitive("Alice"), model.read(JsonPointer.parse("name")))
    }

    @Test
    fun `a shared child renders once per parent that references it`() {
        // The adjacency list is a graph: deduplicating by id here would drop the second Text.
        val model = surface(
            """{"id": "root", "component": "Column", "children": ["a", "b"]}""",
            """{"id": "a", "component": "Card", "child": "shared"}""",
            """{"id": "b", "component": "Card", "child": "shared"}""",
            """{"id": "shared", "component": "Text", "text": "x"}""",
        )
        assertEquals(
            listOf("root", "a", "shared", "b", "shared"),
            model.walk(resolver).map { it.first.id },
        )
    }

    @Test
    fun `a diamond graph that expands exponentially is stopped by the limit`() {
        // Sixteen layers each referencing the same two children is 2^16 instances from 32
        // components. No path repeats an id, so the cycle guard does not bound this.
        val layers = 16
        val components = buildList {
            add("""{"id": "root", "component": "Column", "children": ["l0a", "l0b"]}""")
            repeat(layers) { i ->
                val next = if (i == layers - 1) "[]" else """["l${i + 1}a", "l${i + 1}b"]"""
                add("""{"id": "l${i}a", "component": "Column", "children": $next}""")
                add("""{"id": "l${i}b", "component": "Column", "children": $next}""")
            }
        }
        val model = surface(*components.toTypedArray())
        val failure = assertFailsWith<A2uiStateException> { model.walk(resolver) }
        assertEquals("s", failure.surfaceId)
    }

    @Test
    fun `a deeply nested chain does not overflow the call stack`() {
        val depth = DEFAULT_WALK_LIMIT - 1
        val components = buildList {
            add("""{"id": "root", "component": "Card", "child": "c0"}""")
            repeat(depth) { i ->
                val child = if (i == depth - 1) "" else ""","child": "c${i + 1}""""
                add("""{"id": "c$i", "component": "Card"$child}""")
            }
        }
        assertEquals(depth + 1, surface(*components.toTypedArray()).walk(resolver).size)
    }
}
