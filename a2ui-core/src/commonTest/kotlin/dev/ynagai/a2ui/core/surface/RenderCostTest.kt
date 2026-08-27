package dev.ynagai.a2ui.core.surface

import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.ChildList
import dev.ynagai.a2ui.core.protocol.Component
import dev.ynagai.a2ui.core.protocol.ComponentSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a surface would cost to draw, answered without drawing it.
 *
 * The bound [walk] carries exists on the path that *checks* a surface. This is the same traversal
 * asked the same question by the path that *draws* one, where the answer has to arrive before any
 * of it is composed -- composition does not raise when it gives way, it hangs.
 */
class RenderCostTest {
    @Test
    fun `layers naming the same two children are counted as the product they expand to`() {
        // The payload the whole bound exists for: 2n components, 2^n instances. No path repeats an
        // id and none of them is deeper than n, so neither the cycle guard nor the depth bound
        // sees anything wrong -- and a count of *components* would report 41.
        val layers = 20
        val components = buildList {
            add("""{"id": "root", "component": "Column", "children": ["a0", "b0"]}""")
            repeat(layers) { level ->
                val children =
                    if (level == layers - 1) "[]" else """["a${level + 1}", "b${level + 1}"]"""
                add("""{"id": "a$level", "component": "Column", "children": $children}""")
                add("""{"id": "b$level", "component": "Column", "children": $children}""")
            }
        }
        assertEquals(41, surface(*components.toTypedArray()).components.size)
        assertEquals(
            RenderCost.Exceeds(RenderLimits.DEFAULT.maxInstances),
            surface(*components.toTypedArray()).renderCost(resolver),
        )
    }

    @Test
    fun `references naming components that never arrived are charged for their placeholders`() {
        // `MAX_REFERENCES` bounds how many references a component carries, not how many ids one of
        // them holds. A list of ids the agent never defined costs nothing to find and one
        // placeholder each to draw, so counting only the components that resolve would wave
        // through exactly the cheapest payload to send.
        val ids = (0 until 500).joinToString(",") { "\"ghost$it\"" }
        val model = surface("""{"id": "root", "component": "Column", "children": [$ids]}""")
        assertEquals(1, model.components.size)
        assertEquals(RenderCost.Fits(501, exact = true), model.renderCost(resolver))
        assertEquals(RenderCost.Exceeds(100), model.renderCost(resolver, RenderLimits(maxInstances = 100)))
    }

    @Test
    fun `a template counts as its fanout rather than as its data`() {
        // Deliberately not the array's length. The estimate has to survive every data model write
        // to be worth caching across them, and what a template really yields is bounded when it is
        // expanded rather than here.
        val items = (0 until 1_000).joinToString(",") { "{}" }
        val model = surface(
            """{"id": "root", "component": "List", "children": {"componentId": "row", "path": "/xs"}}""",
            """{"id": "row", "component": "Text", "text": "x"}""",
            data = """{"xs": [$items]}""",
        )
        assertEquals(RenderCost.Fits(1 + RenderLimits.DEFAULT.templateFanout, exact = false), model.renderCost(resolver))
    }

    @Test
    fun `nested templates multiply rather than sum`() {
        // Why counting a template as one instance was rejected: three nested templates counted
        // that way come to four, and draw the product of what each is bound to.
        val model = surface(
            """{"id": "root", "component": "List", "children": {"componentId": "row", "path": "/xs"}}""",
            """{"id": "row", "component": "List", "children": {"componentId": "cell", "path": "ys"}}""",
            """{"id": "cell", "component": "List", "children": {"componentId": "leaf", "path": "zs"}}""",
            """{"id": "leaf", "component": "Text", "text": "x"}""",
        )
        // 1 + 2 + 4 + 8, with the fanout of 2 applied at every level.
        assertEquals(RenderCost.Fits(15, exact = false), model.renderCost(resolver))
    }

    @Test
    fun `the depth bound is charged and stops the descent rather than raising`() {
        // Where this parts company with `walk`: a renderer that refused the surface outright would
        // draw nothing for a tree whose first 24 levels are perfectly drawable, and the
        // specification asks it to degrade. The instance that trips the bound still costs a
        // placeholder, so it is counted.
        val depth = 100
        val model = surface(
            *buildList {
                add("""{"id": "root", "component": "Card", "child": "c0"}""")
                repeat(depth) { i ->
                    val child = if (i == depth - 1) "" else ""","child": "c${i + 1}""""
                    add("""{"id": "c$i", "component": "Card"$child}""")
                }
            }.toTypedArray(),
        )
        val limits = RenderLimits.DEFAULT
        assertEquals(RenderCost.Fits(limits.maxDepth + 1, exact = true), model.renderCost(resolver, limits))
    }

    @Test
    fun `a cycle costs the placeholder that breaks it and nothing beyond`() {
        val model = surface(
            """{"id": "root", "component": "Card", "child": "a"}""",
            """{"id": "a", "component": "Card", "child": "root"}""",
        )
        // root, a, and the instance of `root` that the cycle guard turns into a placeholder.
        assertEquals(RenderCost.Fits(3, exact = true), model.renderCost(resolver))
    }

    @Test
    fun `a surface may be costed from a component other than its root`() {
        // What a host embedding one component without a surface around it asks.
        val model = surface(
            """{"id": "root", "component": "Column", "children": ["a", "b"]}""",
            """{"id": "a", "component": "Card", "child": "b"}""",
            """{"id": "b", "component": "Text", "text": "x"}""",
        )
        assertEquals(RenderCost.Fits(4, exact = true), model.renderCost(resolver))
        assertEquals(RenderCost.Fits(2, exact = true), model.renderCost(resolver, from = "a"))
    }

    private companion object {
        private val json = A2uiJson.strict

        /** Stands in for the catalog, as in [SurfaceModelTest] and for the same reason. */
        val resolver = ChildResolver { component ->
            buildList {
                component.properties["child"]?.let {
                    add(ChildReference.Single("child", (it as JsonPrimitive).content))
                }
                component.properties["children"]?.let {
                    add(json.decodeFromJsonElement(ChildList.serializer(), it).asReference("children"))
                }
            }
        }

        fun component(text: String): Component = json.decodeFromString(ComponentSerializer, text)

        fun surface(vararg components: String, data: String = "{}"): SurfaceModel =
            SurfaceModel(
                surfaceId = "s",
                dataModel = json.parseToJsonElement(data) as JsonObject,
            ).withComponents(components.map(::component))
    }
}
