package dev.ynagai.a2ui.core.validation

import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.CatalogDefinition
import dev.ynagai.a2ui.core.protocol.Component
import dev.ynagai.a2ui.core.protocol.FunctionCall
import dev.ynagai.a2ui.core.protocol.Surface
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Why a catalog could not be found for a component or a call.
 *
 * Carried rather than folded into a [SchemaViolation] because a renderer answers the two
 * differently: a property the catalog rejects is the agent's mistake to correct, while a catalog
 * the renderer does not hold is a capability mismatch — the renderer said which catalogs it
 * supports, and the surface named another one.
 */
public sealed interface CatalogResolution {
    /** The catalog to check against, and the id it was found under. */
    public data class Found(
        public val catalogId: String,
        public val catalog: CatalogDefinition,
    ) : CatalogResolution

    /** Neither the component nor its surface named a catalog. */
    public data object Unspecified : CatalogResolution

    /** A catalog was named, but this renderer does not hold it. */
    public data class Unknown(public val catalogId: String) : CatalogResolution
}

/**
 * Checks components and function calls against the catalogs a renderer holds.
 *
 * The two checks are the same [SchemaEvaluator] applied at two places, which is why they live
 * together: a component's properties and a call's arguments are both described by JSON Schema in
 * the same catalog, and both reach the same `common_types.json` definitions on the way.
 *
 * A validator is built once per set of catalogs and is safe to keep: it re-encodes each catalog to
 * the document form `$ref` resolution needs, and doing that per message would re-serialize every
 * definition on every component.
 */
public class CatalogValidator private constructor(
    private val catalogs: Map<String, CatalogDefinition>,
    private val documents: Map<String, JsonObject>,
    private val limits: ValidationLimits,
) {
    /** The catalog ids this validator can check against. */
    public val catalogIds: Set<String> get() = catalogs.keys

    /**
     * The catalog a component or call belongs to.
     *
     * The order is the one the specification fixes: the element's own `catalogId` wins, the
     * surface's default applies when the element omits it, and naming neither is an error rather
     * than a silent choice among the catalogs the renderer happens to hold. Guessing when only one
     * catalog is loaded would make a payload's meaning depend on the renderer's configuration.
     */
    public fun resolve(explicit: String?, surfaceDefault: String?): CatalogResolution {
        val named = explicit ?: surfaceDefault ?: return CatalogResolution.Unspecified
        val catalog = catalogs[named] ?: return CatalogResolution.Unknown(named)
        return CatalogResolution.Found(named, catalog)
    }

    /**
     * Whether [call]'s arguments match what its catalog declares for it.
     *
     * `@index` is checked without resolving a catalog, because no catalog defines it: it is the
     * one system function v1.0 has, and `common_types.json` composes it into `FunctionCall`
     * alongside the catalog's own. A call that names any other `@`-prefixed function is *not*
     * treated as a system function — the specification reserves no namespace around `@index` — so
     * it goes through catalog resolution and fails there, which is where the message the agent
     * needs comes from.
     */
    public fun validate(
        call: FunctionCall,
        surfaceDefault: String? = null,
    ): SchemaValidation {
        val encoded = A2uiJson.strict.encodeToJsonElement(FunctionCall.serializer(), call)
        if (call.isSystemFunction && call.catalogId == null) {
            return evaluate(registryFor(null), ProtocolSchemas.functionCall, encoded)
        }
        return when (val resolution = resolve(call.catalogId, surfaceDefault)) {
            is CatalogResolution.Found ->
                evaluate(registryFor(resolution.catalogId), ProtocolSchemas.functionCall, encoded)
            CatalogResolution.Unspecified -> refuse(
                "`${call.call}` names no catalog and the surface sets no default, so there is " +
                    "nothing that says what it takes.",
            )
            is CatalogResolution.Unknown -> refuse(
                "`${call.call}` is declared by `${resolution.catalogId}`, which this renderer " +
                    "does not hold.",
            )
        }
    }

    /**
     * Whether [component] carries the properties its catalog declares for its type.
     *
     * The reserved root container is refused before the catalog is consulted. A catalog may not
     * define `Surface` — the model rejects one that tries — so checking a component that names it
     * against the catalog would report the type as unknown, which is true but misses the point:
     * the name is reserved, and it is reserved whichever catalog is in play.
     */
    public fun validate(
        component: Component,
        surfaceDefault: String? = null,
    ): SchemaValidation {
        if (component.component == Surface.COMPONENT) {
            return refuse(
                "`${Surface.COMPONENT}` is the reserved root container and cannot be created by a " +
                    "message.",
            )
        }
        val encoded = A2uiJson.strict.encodeToJsonElement(Component.serializer(), component)
        return when (val resolution = resolve(component.catalogId, surfaceDefault)) {
            is CatalogResolution.Found ->
                evaluate(registryFor(resolution.catalogId), ProtocolSchemas.component, encoded)
            CatalogResolution.Unspecified -> refuse(
                "`${component.id}` names no catalog and the surface sets no default, so there is " +
                    "nothing that says what a `${component.component}` may carry.",
            )
            is CatalogResolution.Unknown -> refuse(
                "`${component.id}` is a `${component.component}` from `${resolution.catalogId}`, " +
                    "which this renderer does not hold.",
            )
        }
    }

    /**
     * Whether a whole message, still in its wire form, matches the schema for its direction.
     *
     * This is the entry the conformance harness uses, and it reaches what the per-element checks
     * cannot: an envelope key no message defines, a `createSurface` whose `components` array is
     * empty, a `callRendererFunction` that omits the `catalogId` its own definition requires. Each
     * of those is a constraint on the message, not on anything inside it.
     *
     * [catalogId] binds the `catalog.json` placeholder for the whole message. It is passed rather
     * than read from the payload because a message may carry a `catalogId` per component, while
     * the placeholder resolves once — the specification's own harness binds it per test suite for
     * exactly this reason. For a live surface, pass the id the surface was created with; the
     * per-element checks are what apply a component's own override.
     *
     * Takes the raw element rather than a decoded message on purpose. A decoded one has already
     * been through the model's own strict parse, which refuses much of what this is meant to
     * report on — a payload that fails to decode never reaches a checker at all.
     */
    public fun validateMessage(
        message: JsonElement,
        direction: MessageDirection = MessageDirection.AGENT_TO_RENDERER,
        catalogId: String? = null,
    ): SchemaValidation = evaluate(
        registry = registryFor(catalogId),
        location = ProtocolSchemas.message(direction),
        instance = message,
    )

    private fun evaluate(
        registry: SchemaRegistry,
        location: SchemaLocation,
        instance: JsonElement,
    ): SchemaValidation {
        val document = registry.document(location.documentUri)
            ?: return refuse("this renderer is missing `${location.documentUri}`.")
        val schema = document.pointer(location.pointer)
            ?: return refuse("this renderer is missing `$location`.")
        return SchemaEvaluator(registry, limits).validate(schema, location, instance)
    }

    /**
     * The registry to resolve `$ref`s against with [catalogId] as the active catalog.
     *
     * Rebuilt per call rather than cached per catalog because a registry is a map of already
     * parsed documents — the cost is the map, not the parse, and caching one per catalog would
     * hold every catalog's registry for the lifetime of the validator.
     */
    private fun registryFor(catalogId: String?): SchemaRegistry = SchemaRegistry.of(
        documents = ProtocolSchemas.documents + documents.values,
        activeCatalog = catalogId?.let(documents::get),
    )

    private fun refuse(message: String): SchemaValidation =
        SchemaValidation(violations = listOf(SchemaViolation("", message)))

    public companion object {
        /**
         * A validator over [catalogs], each keyed by its own `catalogId`.
         *
         * Catalogs are re-encoded here rather than being taken as raw documents so that a caller
         * can pass what it already holds: a catalog arrives decoded, in a renderer's capabilities
         * or inlined in an agent's, and re-parsing the wire text to check against it would mean
         * keeping both forms. The encoding is faithful — a decoded definition carries its original
         * object and emits it verbatim — so the document `$ref` resolution sees is the one that
         * arrived.
         */
        public fun of(
            catalogs: List<CatalogDefinition>,
            limits: ValidationLimits = ValidationLimits.DEFAULT,
        ): CatalogValidator = CatalogValidator(
            catalogs = catalogs.associateBy { it.catalogId },
            documents = catalogs.associate { catalog ->
                catalog.catalogId to
                    A2uiJson.strict.encodeToJsonElement(CatalogDefinition.serializer(), catalog)
                        as JsonObject
            },
            limits = limits,
        )
    }
}
