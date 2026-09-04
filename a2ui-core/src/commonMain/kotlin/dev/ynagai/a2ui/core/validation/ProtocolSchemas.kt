package dev.ynagai.a2ui.core.validation

import dev.ynagai.a2ui.core.protocol.ProtocolSchemaSources
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The specification's own schema documents, embedded in the library.
 *
 * A catalog is not self-contained. Nearly every property it defines is a `$ref` into
 * `common_types.json` — thirty-two of them to `DynamicString` alone — and the definition that
 * combines a component's envelope with its catalog-defined half lives in `agent_to_renderer.json`.
 * Resolving those over the network would let whoever sent the payload choose what the payload is
 * validated against, so the documents ship with the library instead.
 *
 * The text is generated from `a2ui-core/spec/` at build time and is byte-for-byte the
 * specification's own; see `a2ui-core/spec/README.md` for the revision each was taken from.
 */
public object ProtocolSchemas {
    /** The URI `common_types.json` publishes as its `$id`. */
    public const val COMMON_TYPES_URI: String = "https://a2ui.org/specification/v1_0/common_types.json"

    /** The URI `agent_to_renderer.json` publishes as its `$id`. */
    public const val AGENT_TO_RENDERER_URI: String =
        "https://a2ui.org/specification/v1_0/agent_to_renderer.json"

    /** The URI `renderer_to_agent.json` publishes as its `$id`. */
    public const val RENDERER_TO_AGENT_URI: String =
        "https://a2ui.org/specification/v1_0/renderer_to_agent.json"

    /** The URI of the JSON Schema 2020-12 meta-schema. See [metaSchemaStandIn]. */
    public const val META_SCHEMA_URI: String = "https://json-schema.org/draft/2020-12/schema"

    /** `common_types.json`. */
    public val commonTypes: JsonObject by lazy { parse(ProtocolSchemaSources.COMMON_TYPES) }

    /** `agent_to_renderer.json`. */
    public val agentToRenderer: JsonObject by lazy { parse(ProtocolSchemaSources.AGENT_TO_RENDERER) }

    /** `renderer_to_agent.json`. */
    public val rendererToAgent: JsonObject by lazy { parse(ProtocolSchemaSources.RENDERER_TO_AGENT) }

    /** `catalog_definition.json`, which describes a catalog rather than a message. */
    public val catalogDefinition: JsonObject by lazy {
        parse(ProtocolSchemaSources.CATALOG_DEFINITION)
    }

    /**
     * A stand-in for the JSON Schema 2020-12 meta-schema, which `catalog_definition.json` refers
     * to in four places to say "this member must itself be a schema".
     *
     * **It is not the meta-schema and does not claim to be.** The real one is defined through
     * `$vocabulary` and `$dynamicRef`, neither of which this evaluator implements, and applying it
     * would mean implementing the whole of JSON Schema's self-description to check a catalog's
     * `anyComponent`. What this asserts is what a JSON Schema *is*: an object or a boolean. A
     * catalog whose `anyComponent` is a string or a number is refused here; one whose `type` is
     * `7` is not, and fails later when something is actually validated against it.
     *
     * The alternative is worse in both directions. Leaving the reference unresolvable refuses
     * every conformant catalog — `composition_constraints` #0 and #1 are valid catalogs and say
     * so. Treating it as vacuously true accepts a `anyComponent` that is not a schema at all.
     */
    private val metaSchemaStandIn: JsonObject by lazy {
        parse(
            """{"${'$'}id": "$META_SCHEMA_URI", "type": ["object", "boolean"]}""",
        )
    }

    /** Every document, in the shape [SchemaRegistry.of] takes. */
    public val documents: List<JsonObject>
        get() = listOf(
            commonTypes,
            agentToRenderer,
            rendererToAgent,
            catalogDefinition,
            metaSchemaStandIn,
        )

    /**
     * The `$id`s of the documents this library ships, which is what makes a schema trusted.
     *
     * A schema read from one of these was vendored from the specification and reviewed; a schema
     * read from anywhere else came from a catalog, and a catalog may arrive inlined in an agent's
     * capabilities message. [SchemaEvaluator] uses the distinction for `pattern`, which is the one
     * keyword whose cost is chosen by whoever wrote the schema.
     */
    internal val libraryDocuments: Map<String, JsonObject> by lazy {
        documents.mapNotNull { document ->
            (document["\$id"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeIf { it.isString }?.content?.let { id -> id to document }
        }.toMap()
    }

    /** The URIs [libraryDocuments] covers. */
    internal val libraryUris: Set<String> get() = libraryDocuments.keys

    /**
     * The URIs a bare `catalog.json` reference resolves to from a document this library ships.
     *
     * [CATALOG_PLACEHOLDER] is a filename, not a document: nobody publishes it, and the basic
     * catalog's own `$id` is `.../catalogs/basic/catalog.json`. But `$ref` resolution has to join
     * it against the document that carries it before it can tell, and the result -- for
     * `common_types.json` and `agent_to_renderer.json` alike,
     * `https://a2ui.org/specification/v1_0/catalog.json` -- is a name no shipped document claims
     * and the registry would otherwise let anyone have.
     *
     * So these names are reserved, and no registration may take one. Read out of the documents
     * rather than written down, because what has to be reserved is exactly what a reference in one
     * of them joins to -- no more. A document that never writes the placeholder reserves nothing:
     * [metaSchemaStandIn] does not, and reserving on its behalf would withhold
     * `https://json-schema.org/draft/2020-12/catalog.json` from a catalog entitled to it.
     *
     * Only a bare `catalog.json` is affected: a catalog whose `$id` ends in the same filename
     * under any other directory -- the published basic catalog does -- is registered and reachable
     * as itself.
     */
    internal val catalogPlaceholderUris: Set<String> by lazy {
        libraryDocuments.mapNotNullTo(mutableSetOf()) { (uri, document) ->
            if (document.refersToCatalogPlaceholder()) {
                "${uri.substringBeforeLast('/')}/$CATALOG_PLACEHOLDER"
            } else {
                null
            }
        }
    }

    /** Whether any `$ref` in this subtree names [CATALOG_PLACEHOLDER] with no path before it. */
    private fun JsonElement.refersToCatalogPlaceholder(): Boolean = when (this) {
        is JsonObject -> entries.any { (key, value) ->
            val names = key == "\$ref" &&
                (value as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)
                    ?.content?.substringBefore('#') == CATALOG_PLACEHOLDER
            names || value.refersToCatalogPlaceholder()
        }
        is JsonArray -> any { it.refersToCatalogPlaceholder() }
        else -> false
    }

    /** Where a `FunctionCall` is checked from: `common_types.json#/$defs/FunctionCall`. */
    public val functionCall: SchemaLocation =
        SchemaLocation(COMMON_TYPES_URI, "/\$defs/FunctionCall")

    /**
     * Where a component is checked from: `agent_to_renderer.json#/$defs/Component`.
     *
     * Not the catalog's own `anyComponent`. That one describes the catalog-defined half only,
     * while this composes it with `ComponentCommon` — which is what carries `id`, `accessibility`
     * and the extension-key rule — and with the constraint that no message may name a component
     * `Surface`, and closes the result with `unevaluatedProperties`.
     */
    public val component: SchemaLocation =
        SchemaLocation(AGENT_TO_RENDERER_URI, "/\$defs/Component")

    /**
     * Where a whole message is checked from.
     *
     * Each document's root is a `oneOf` over the message types that travel in that direction, so
     * validating against the root is what tells a `createSurface` from an `updateComponents`
     * without the caller having to. It also reaches constraints no single component or call
     * carries -- that a `createSurface` may not send an empty `components` array, say, which is
     * `minItems` on the list rather than anything about the components in it.
     */
    public fun message(direction: MessageDirection): SchemaLocation = when (direction) {
        MessageDirection.AGENT_TO_RENDERER -> SchemaLocation(AGENT_TO_RENDERER_URI, "")
        MessageDirection.RENDERER_TO_AGENT -> SchemaLocation(RENDERER_TO_AGENT_URI, "")
    }

    private fun parse(source: String): JsonObject = Json.parseToJsonElement(source) as JsonObject
}

/** Which way a message travels, which is what decides the schema it is checked against. */
public enum class MessageDirection {
    /** A message the agent sent, checked against `agent_to_renderer.json`. */
    AGENT_TO_RENDERER,

    /** A message the renderer sends, checked against `renderer_to_agent.json`. */
    RENDERER_TO_AGENT,
}
