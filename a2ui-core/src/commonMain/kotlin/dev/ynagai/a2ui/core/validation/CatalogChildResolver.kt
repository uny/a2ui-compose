package dev.ynagai.a2ui.core.validation

import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.CatalogDefinition
import dev.ynagai.a2ui.core.protocol.Component
import dev.ynagai.a2ui.core.protocol.ComponentId
import dev.ynagai.a2ui.core.surface.A2uiStateException
import dev.ynagai.a2ui.core.surface.ChildReference
import dev.ynagai.a2ui.core.surface.ChildResolver
import dev.ynagai.a2ui.core.surface.JsonPointer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Finds a component's children by reading the catalog, rather than by knowing property names.
 *
 * The protocol reserves the *shapes* — `common_types.json` defines `Child` and `ChildList` — but
 * leaves the property names carrying them to the catalog. In the basic catalog alone they are
 * `child`, `children`, `trigger`, `content`, and one nested inside each element of `Tabs.tabs`, so
 * a resolver that hard-coded a name or two would silently drop half the tree — silently, because a
 * child that is never found renders as a component with no children rather than as an error.
 *
 * So the schema is walked alongside the value, and a reference is whatever sits where the schema
 * says a `Child` or a `ChildList` goes. That is why this reads the catalog and why it is bounded:
 * the schema is as agent-controlled as the payload once a catalog may be inlined.
 */
public class CatalogChildResolver private constructor(
    private val registry: SchemaRegistry,
    private val catalogUri: String?,
    private val surfaceDefault: String?,
    private val limits: ValidationLimits,
) : ChildResolver {
    /**
     * @throws A2uiStateException when the component's schema or value outgrows [limits], or when
     *   it carries more than [MAX_REFERENCES] references. The bounds refuse rather than truncate;
     *   a shortened list is a container drawn with children missing and nothing said about it.
     */
    override fun childrenOf(component: Component): List<ChildReference> {
        val definition = definitionFor(component) ?: return emptyList()
        val encoded = A2uiJson.strict.encodeToJsonElement(Component.serializer(), component)
        val found = mutableListOf<ChildReference>()
        Walk(found).run(definition.schema, definition.location, encoded, Path.ROOT, depth = 0)
        return found
    }

    private fun definitionFor(component: Component): SchemaRegistry.Resolved? {
        // A component may override the surface's catalog, and then its children are that catalog's
        // business rather than this one's. Naming a catalog nothing holds does *not* fall back to
        // the surface default: the fallback would read one catalog's property names off another's
        // component of the same name, which is a wrong tree rather than a missing one.
        val named = component.catalogId ?: surfaceDefault
        val uri = when {
            named == null -> catalogUri ?: return null
            registry.document(named) != null -> named
            else -> return null
        }
        return registry.resolve("$uri#/components/${component.component.escapePointer()}", ROOT_OF)
    }

    /**
     * One traversal of one component.
     *
     * The visited set is keyed by (subschema, place in the value) exactly as the evaluator's is,
     * and for the same reason: a catalog's references are cyclic, and a component's schema reaches
     * `anyComponent` through its own children.
     */
    private inner class Walk(private val out: MutableList<ChildReference>) {
        private var steps = 0
        private val active = mutableSetOf<Pair<SchemaLocation, String>>()

        fun run(
            schema: JsonElement,
            location: SchemaLocation,
            value: JsonElement,
            path: Path,
            depth: Int,
        ) {
            // Raising rather than returning what was found so far, for the reason
            // [dev.ynagai.a2ui.core.surface.walk] raises: a resolver that quietly stops short
            // reports a container with fewer children than it has, and the renderer draws that
            // without complaint. A surface that refuses to draw is the better failure.
            if (depth > limits.maxDepth) {
                throw A2uiStateException("a component's schema nests deeper than ${limits.maxDepth}.")
            }
            if (++steps > limits.maxSteps) {
                throw A2uiStateException(
                    "a component's schema did not settle within ${limits.maxSteps} steps.",
                )
            }
            if (out.size >= MAX_REFERENCES) {
                throw A2uiStateException(
                    "a component carries more than $MAX_REFERENCES child references.",
                )
            }
            val obj = schema as? JsonObject ?: return

            (obj["\$ref"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.let { ref ->
                val resolved = registry.resolve(ref, location)
                when {
                    resolved == null -> Unit
                    resolved.location == CHILD -> emitSingle(value, path)
                    resolved.location == CHILD_LIST -> emitList(value, path)
                    else -> {
                        val key = resolved.location to path.render()
                        if (active.add(key)) {
                            try {
                                run(resolved.schema, resolved.location, value, path, depth + 1)
                            } finally {
                                active.remove(key)
                            }
                        }
                    }
                }
            }

            (obj["properties"] as? JsonObject)?.let { declared ->
                val container = value as? JsonObject ?: return@let
                for ((name, subschema) in declared) {
                    val child = container[name] ?: continue
                    run(subschema, location.child("properties", name), child, path.child(name), depth + 1)
                }
            }

            obj["items"]?.let { subschema ->
                val array = value as? JsonArray ?: return@let
                array.forEachIndexed { index, element ->
                    run(subschema, location.child("items"), element, path.index(index), depth + 1)
                }
            }

            // A component's own properties sit under `allOf`, and `Icon.name` shows that a child
            // may sit under a branch of a `oneOf` as well. Every branch is followed rather than
            // the matching one, because which branch matched is the checker's answer and this is
            // not the checker — a value that fits neither has no children under either.
            for (keyword in COMPOSITION) {
                (obj[keyword] as? JsonArray)?.forEachIndexed { index, branch ->
                    run(branch, location.child(keyword, index.toString()), value, path, depth + 1)
                }
            }
            obj["then"]?.let { run(it, location.child("then"), value, path, depth + 1) }
        }

        private fun emitSingle(value: JsonElement, path: Path) {
            val id = (value as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: return
            out += ChildReference.Single(path.render(), id)
        }

        private fun emitList(value: JsonElement, path: Path) {
            when (value) {
                is JsonArray -> {
                    val ids = value.mapNotNull {
                        (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                    }
                    // A malformed entry is dropped rather than shifting the rest: the list is
                    // positional to whatever the agent sent, and the checker is what reports it.
                    if (ids.size == value.size) out += ChildReference.Fixed(path.render(), ids)
                }

                is JsonObject -> {
                    val componentId = value["componentId"].asId() ?: return
                    val pointer = value["path"].asId() ?: return
                    val parsed = runCatching { JsonPointer.parse(pointer) }.getOrNull() ?: return
                    out += ChildReference.Template(path.render(), componentId, parsed)
                }

                else -> Unit
            }
        }

        private fun JsonElement?.asId(): ComponentId? =
            (this as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
    }

    public companion object {
        /**
         * How many references one component may carry before [childrenOf] refuses it.
         *
         * A component's own schema bounds this in any real catalog; the number is here because a
         * catalog may be inlined by an agent, and a schema that nests `items` inside `items`
         * expands over a value the same agent sent. Reaching it raises
         * [A2uiStateException] rather than returning the first [MAX_REFERENCES] — see [Walk.run].
         */
        public const val MAX_REFERENCES: Int = 4_096

        private val COMPOSITION = listOf("allOf", "anyOf", "oneOf")

        private val ROOT_OF = SchemaLocation("", "")

        private val CHILD = SchemaLocation(ProtocolSchemas.COMMON_TYPES_URI, "/\$defs/Child")

        private val CHILD_LIST = SchemaLocation(ProtocolSchemas.COMMON_TYPES_URI, "/\$defs/ChildList")

        /**
         * A resolver over [catalogs], with [surfaceDefault] as the catalog a component that names
         * none belongs to.
         *
         * A component that names a catalog this resolver does not hold contributes no children
         * rather than raising: the walk this feeds is what draws a partially arrived surface, and
         * the specification requires it to render placeholders rather than to stop. Reporting the
         * unknown catalog is [CatalogValidator]'s job.
         */
        public fun of(
            catalogs: List<CatalogDefinition>,
            surfaceDefault: String? = null,
            limits: ValidationLimits = ValidationLimits.DEFAULT,
        ): CatalogChildResolver {
            val documents = catalogs.map { catalog ->
                A2uiJson.strict.encodeToJsonElement(CatalogDefinition.serializer(), catalog)
                    as JsonObject
            }
            val active = surfaceDefault?.takeIf { id -> catalogs.any { it.catalogId == id } }
            return CatalogChildResolver(
                registry = SchemaRegistry.of(ProtocolSchemas.documents + documents),
                catalogUri = active,
                surfaceDefault = surfaceDefault,
                limits = limits,
            )
        }
    }
}

/** Where a value sits inside one component, as a JSON Pointer without its leading slash. */
private class Path private constructor(private val parent: Path?, private val token: String) {
    fun child(name: String): Path = Path(this, name.escapePointer())

    fun index(at: Int): Path = Path(this, at.toString())

    fun render(): String {
        if (parent == null) return ""
        val steps = ArrayDeque<String>()
        var node: Path? = this
        while (node?.parent != null) {
            steps.addFirst(node.token)
            node = node.parent
        }
        return steps.joinToString("/")
    }

    companion object {
        val ROOT: Path = Path(null, "")
    }
}

private fun String.escapePointer(): String = replace("~", "~0").replace("/", "~1")

private fun SchemaLocation.child(vararg steps: String): SchemaLocation =
    copy(pointer = steps.fold(pointer) { acc, step -> "$acc/${step.escapePointer()}" })
