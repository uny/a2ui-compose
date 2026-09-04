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
    private val registries: Map<String?, SchemaRegistry>,
    private val surfaceDefault: String?,
    private val limits: ValidationLimits,
) : ChildResolver {
    /**
     * @throws A2uiStateException when the component's schema or value outgrows [limits], or when
     *   it carries more than [MAX_REFERENCES] references. The bounds refuse rather than truncate;
     *   a shortened list is a container drawn with children missing and nothing said about it.
     */
    override fun childrenOf(component: Component): List<ChildReference> {
        val named = component.catalogId ?: surfaceDefault ?: return emptyList()
        val registry = registryFor(named)
        val definition = definitionFor(component, named, registry) ?: return emptyList()
        val encoded = A2uiJson.strict.encodeToJsonElement(Component.serializer(), component)
        val found = mutableListOf<ChildReference>()
        Walk(found, registry).run(definition.schema, definition.location, encoded, Path.ROOT, depth = 0)
        return found
    }

    /**
     * The registry with [named] bound to [CATALOG_PLACEHOLDER], as [CatalogValidator.registryFor]
     * binds it -- one per catalog rather than one for the surface's.
     *
     * A component may override the surface's catalog, and the catalog it names is then the one *in
     * play* for it: `catalog.json` inside that catalog's own schema means that catalog, and the
     * name it is reachable by is the name it published. Resolving an override against the surface
     * default's registry got both wrong, and the second one silently: a held catalog whose
     * `catalogId` is a name [ProtocolSchemas.catalogPlaceholderUris] reserves is refused by
     * [SchemaRegistry.document] unless it is the catalog bound, so [definitionFor] found nothing
     * and the component rendered with its children dropped -- while [CatalogValidator], which does
     * bind the catalog a component names, reported that same component valid.
     *
     * A name no held catalog published as its `catalogId` falls back to the unbound registry,
     * where nothing answers for it and [definitionFor] refuses. With one exception, which predates
     * the per-catalog registries and is not fixed by them: a name that is some held catalog's
     * JSON Schema `$id` *is* registered -- [SchemaRegistry.of]'s first pass keys on `$id` -- so
     * that catalog answers, and its children are read with `catalog.json` bound to nothing. The
     * checker calls the same name [CatalogResolution.Unknown], because it keys on `catalogId`
     * alone. Reporting an unknown catalog is [CatalogValidator]'s job rather than this one's, so
     * the disagreement costs a component nothing it was entitled to -- but the two do not agree
     * about what is held, and that is worth knowing before either is changed.
     */
    private fun registryFor(named: String): SchemaRegistry =
        registries[named] ?: registries.getValue(null)

    private fun definitionFor(
        component: Component,
        named: String,
        registry: SchemaRegistry,
    ): SchemaRegistry.Resolved? {
        // A component may override the surface's catalog, and then its children are that catalog's
        // business rather than this one's. Naming a catalog nothing holds does *not* fall back to
        // the surface default: the fallback would read one catalog's property names off another's
        // component of the same name, which is a wrong tree rather than a missing one.
        val uri = when {
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
    private inner class Walk(
        private val out: MutableList<ChildReference>,
        private val registry: SchemaRegistry,
    ) {
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
            // `else` for the same reason every `oneOf` branch is followed: which one applies is
            // the checker's answer, and a child sitting only under the `else` was invisible here.
            obj["else"]?.let { run(it, location.child("else"), value, path, depth + 1) }

            // A component may carry its children in a map rather than a fixed property --
            // `"slots": {"additionalProperties": {"$ref": ".../Child"}}` with a value of
            // `{"header": "c1"}`. Without this the payload validates and the children vanish.
            // `patternProperties` is deliberately NOT walked: matching it needs the catalog's own
            // regex, which this library refuses to compile for the reason [matcher] gives.
            obj["additionalProperties"]?.let { subschema ->
                val container = value as? JsonObject ?: return@let
                val named = (obj["properties"] as? JsonObject)?.keys.orEmpty()
                val patterns = (obj["patternProperties"] as? JsonObject)?.keys.orEmpty()
                // `additionalProperties` applies only to what neither `properties` nor
                // `patternProperties` covers. Emitting a reference for a name a pattern claims
                // would invent a child the catalog never declared -- and an invented edge is worse
                // than a missing one here, because `CompositionValidator` then reports
                // `UNALLOWED_CHILD` for a pairing that does not exist. So when a pattern cannot be
                // matched -- this library will not compile a catalog's regex, see [matcher] -- the
                // walk stops rather than guessing.
                if (patterns.any { matcher(it) == null }) return@let
                for ((name, child) in container) {
                    if (name in named) continue
                    if (patterns.any { matcher(it)?.invoke(name) == true }) continue
                    run(
                        subschema,
                        location.child("additionalProperties"),
                        child,
                        path.child(name),
                        depth + 1,
                    )
                }
            }
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
         *
         * **Two catalogs sharing a `catalogId` resolve to the LAST one given**, which is what
         * [CatalogValidator.of] documents for the same input. It used to be the first here, and
         * the disagreement was the quiet kind: the checker read one catalog's schema and this read
         * the other's property names off the same component, so a child could be checked against a
         * definition it was never found under. A renderer should still refuse the duplicate before
         * either sees it -- agreeing is not the same as being right.
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
            val byCatalogId = catalogs.withIndex()
                .associate { (index, catalog) -> catalog.catalogId to documents[index] }
            // One registry per catalog a component may name, plus the unbound one, exactly as
            // [CatalogValidator] holds them and for the same two reasons. Bound, because without
            // it the placeholder falls through to the map and an inlined catalog publishing an
            // `$id` of `.../v1_0/catalog.json` answers `catalog.json#/$defs/anyFunction` for a
            // surface bound to another catalog -- the hole `SchemaRegistry` closes only when it is
            // told which catalog is live. Per catalog rather than per surface, because a component
            // may override the surface's default and is then that catalog's business; see
            // [registryFor]. Built once: a registry is a map over already-parsed documents, and
            // [childrenOf] runs per component.
            return CatalogChildResolver(
                registries = (byCatalogId.keys + null).associateWith { catalogId ->
                    SchemaRegistry.of(
                        documents = ProtocolSchemas.documents + documents,
                        activeCatalog = catalogId?.let(byCatalogId::get),
                    )
                },
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
