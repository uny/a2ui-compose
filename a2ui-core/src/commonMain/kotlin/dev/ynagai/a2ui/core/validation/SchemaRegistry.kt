package dev.ynagai.a2ui.core.validation

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Where one subschema lives: the document that carries it, and a JSON Pointer into that document.
 *
 * This pairs with an instance location to make a cycle detectable. The reference graph a catalog
 * describes is genuinely cyclic — a function's arguments are dynamic values, a dynamic value may
 * be a function call, and a function call resolves back to the catalog's own functions — so a
 * traversal that tracked only the instance would revisit the same subschema forever on a value
 * that never shrinks.
 */
public data class SchemaLocation(
    public val documentUri: String,
    public val pointer: String,
) {
    override fun toString(): String = if (pointer.isEmpty()) documentUri else "$documentUri#$pointer"
}

/**
 * The filename the specification uses to mean "whichever catalog is in play".
 *
 * `common_types.json` refers to the catalog's `$defs` as `catalog.json#/$defs/anyFunction`, and
 * `agent_to_renderer.json` does the same for `anyComponent`. Resolved as a URI against the
 * document that carries it, that names `https://a2ui.org/specification/v1_0/catalog.json`, which
 * is not a document that exists — no catalog is published under that name and the basic catalog's
 * own `$id` is `.../catalogs/basic/catalog.json`. The prose says so outright: it is "a placeholder
 * filename" (`docs/a2ui_protocol.md:146`).
 *
 * So a resolver cannot treat it as a URI. [SchemaRegistry] binds the bare name to the active
 * catalog before the registered documents are consulted, and any other spelling whose path ends in
 * this name once no registered document claims it.
 */
internal const val CATALOG_PLACEHOLDER: String = "catalog.json"

/**
 * The documents `$ref` may reach, keyed by the URI each one publishes as its `$id`.
 *
 * A catalog is not self-contained: nearly every property in one refers to `common_types.json`, and
 * that document refers back into the catalog. The registry is what closes that loop, and holding
 * the documents explicitly — rather than fetching them — is deliberate. A renderer resolving a
 * `$ref` over the network would let an agent choose what its own payload is validated against.
 *
 * @property activeCatalog the document [CATALOG_PLACEHOLDER] binds to, or null when validating
 *   something that never reaches the placeholder.
 */
public class SchemaRegistry private constructor(
    private val documents: Map<String, JsonObject>,
    private val activeCatalogUri: String?,
    private val activeCatalog: JsonObject?,
) {
    /**
     * The document [uri] publishes, or null when nothing registered it.
     *
     * Two rules, and the order between them is the point.
     *
     * A document this library ships is never displaceable, by anyone -- not by another catalog and
     * not by the catalog in play. `$id` is a free string on a catalog, so a catalog declaring the
     * `$id` of `agent_to_renderer.json` is making a claim it has no right to; honouring it would
     * hand an agent the definition of `Component`, which is the whole of the check.
     *
     * Below that, the catalog in play answers for its own URI whatever else claimed it. Two
     * catalogs may publish the same `$id` and the map can only keep one, but the placeholder
     * resolves through here and must reach the catalog the caller *named* rather than whichever
     * namesake happened to register first.
     *
     * A catalog that claims a library URI therefore reaches neither branch as itself: the
     * placeholder binds to that URI, the library document answers, and the pointer into it fails
     * to resolve. That is reported as an unresolvable reference, which is the truth. It also means
     * no schema text a catalog wrote is ever read at a library [SchemaLocation.documentUri], which
     * is what [SchemaEvaluator] keys its `pattern` trust decision on.
     */
    public fun document(uri: String): JsonObject? = when {
        // Read from the library itself rather than from the map, so the guarantee does not depend
        // on the caller having passed the specification's documents first. `of` is public.
        uri in ProtocolSchemas.libraryUris -> ProtocolSchemas.libraryDocuments[uri]
        uri == activeCatalogUri -> activeCatalog ?: documents[uri]
        else -> documents[uri]
    }

    /**
     * The subschema [reference] names, read relative to [base].
     *
     * Returns null when the document is unknown or the pointer does not land on a schema, which
     * the evaluator reports rather than treats as vacuous success: an unresolvable `$ref` means
     * the payload was never checked against what the catalog said, and silently accepting it is
     * the failure mode this whole checker exists to prevent.
     */
    public fun resolve(reference: String, base: SchemaLocation): Resolved? {
        val hash = reference.indexOf('#')
        val uriPart = if (hash < 0) reference else reference.substring(0, hash)
        val fragment = if (hash < 0) "" else reference.substring(hash + 1)
        if (!fragment.isEmpty() && !fragment.startsWith("/")) {
            // Only pointer fragments are supported. A plain-name `$anchor` fragment is valid JSON
            // Schema but appears nowhere in v1.0, and guessing at one would resolve to the wrong
            // subschema rather than to nothing.
            return null
        }
        val documentUri = when {
            uriPart.isEmpty() -> base.documentUri
            else -> resolveDocumentUri(uriPart, base.documentUri)
        }
        val document = document(documentUri) ?: return null
        val target = document.pointer(fragment) ?: return null
        return Resolved(target, SchemaLocation(documentUri, fragment))
    }

    private fun resolveDocumentUri(uriPart: String, baseUri: String): String {
        // The placeholder written bare is answered before the map is consulted. It is a filename
        // rather than a URI, so a document that registered itself under the URI it would resolve to
        // -- which an inlined catalog may do, `$id` being a free string on a catalog -- must not be
        // able to take the binding away from the catalog that is actually in play.
        if (uriPart == CATALOG_PLACEHOLDER && activeCatalogUri != null) return activeCatalogUri
        val absolute = if (uriPart.contains("://")) uriPart else joinRelative(uriPart, baseUri)
        if (absolute in documents) return absolute
        // The placeholder: a reference to `catalog.json` that no registered document claims means
        // the catalog in play, whatever its own `$id` says.
        return if (absolute.substringAfterLast('/') == CATALOG_PLACEHOLDER && activeCatalogUri != null) {
            activeCatalogUri
        } else {
            absolute
        }
    }

    /**
     * [relative] resolved against [baseUri] the way RFC 3986 resolves a relative reference: the
     * base's last path segment is replaced. Only the relative-path form appears in v1.0, so an
     * absolute path or a scheme-relative reference is left to fail resolution rather than being
     * mis-joined.
     */
    private fun joinRelative(relative: String, baseUri: String): String {
        if (relative.startsWith("/")) return baseUri.substringBefore("://") + "://" +
            baseUri.substringAfter("://").substringBefore('/') + relative
        val directory = baseUri.substringBeforeLast('/', missingDelimiterValue = "")
        return if (directory.isEmpty()) relative else "$directory/$relative"
    }

    /** A subschema and where it was found. */
    public data class Resolved(
        public val schema: JsonElement,
        public val location: SchemaLocation,
    )

    public companion object {
        /**
         * A registry over [documents], each keyed by its own `$id`, with the FIRST to claim a
         * URI keeping it.
         *
         * [activeCatalog] is bound to [CATALOG_PLACEHOLDER] and answers for its own URI directly,
         * so it does not depend on winning that race -- it is appended last, and under first-wins
         * it would otherwise lose every collision. Passing it separately rather than inferring
         * "the one that has a `catalogId`" keeps a catalog that inlines another one from silently
         * taking over.
         */
        public fun of(
            documents: List<JsonObject>,
            activeCatalog: JsonObject? = null,
        ): SchemaRegistry {
            // The FIRST document to claim a URI keeps it. `$id` is a free string on a catalog --
            // `catalog_definition.json` requires only `catalogId` -- and a catalog may arrive
            // inlined in an agent's capabilities message, so a document claiming a URI an earlier
            // one already published is choosing what a payload is checked against rather than
            // registering itself. Callers pass the specification's own documents first.
            val all = mutableMapOf<String, JsonObject>()
            for (document in documents + listOfNotNull(activeCatalog)) {
                val id = document.declaredId() ?: continue
                if (id !in all) all[id] = document
            }
            return SchemaRegistry(all, activeCatalog?.declaredId(), activeCatalog)
        }

        private fun JsonObject.declaredId(): String? =
            (this["\$id"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
    }
}

/**
 * The element [pointer] names, or null when the path does not exist.
 *
 * This is JSON Pointer as `$ref` fragments use it, which is narrower than
 * [dev.ynagai.a2ui.core.surface.JsonPointer]: it addresses a *schema*, never an array by index
 * past its end, and a missing step is an unresolvable reference rather than a value not yet sent.
 * The two escapes are the same, and `~1` must be undone before `~0` or `~01` decodes to `/`.
 */
internal fun JsonObject.pointer(pointer: String): JsonElement? {
    if (pointer.isEmpty()) return this
    var current: JsonElement = this
    // Dropping the leading empty token rather than splitting and skipping it: `"/a"` splits to
    // ["", "a"], and treating that first "" as a step would look for a key named "".
    for (raw in pointer.removePrefix("/").split('/')) {
        val token = raw.replace("~1", "/").replace("~0", "~")
        current = when (val node = current) {
            is JsonObject -> node[token] ?: return null
            is kotlinx.serialization.json.JsonArray ->
                token.toIntOrNull()?.let(node::getOrNull) ?: return null
            else -> return null
        }
    }
    return current
}
