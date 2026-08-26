package dev.ynagai.a2ui.core.validation

import dev.ynagai.a2ui.core.protocol.ProtocolSchemaSources
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

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

    /** `common_types.json`. */
    public val commonTypes: JsonObject by lazy { parse(ProtocolSchemaSources.COMMON_TYPES) }

    /** `agent_to_renderer.json`. */
    public val agentToRenderer: JsonObject by lazy { parse(ProtocolSchemaSources.AGENT_TO_RENDERER) }

    /** Both documents, in the shape [SchemaRegistry.of] takes. */
    public val documents: List<JsonObject> get() = listOf(commonTypes, agentToRenderer)

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

    private fun parse(source: String): JsonObject = Json.parseToJsonElement(source) as JsonObject
}
