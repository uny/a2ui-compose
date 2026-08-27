package dev.ynagai.a2ui.core.conformance

import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.CatalogDefinition
import dev.ynagai.a2ui.core.validation.CatalogValidator
import dev.ynagai.a2ui.core.validation.MessageDirection
import dev.ynagai.a2ui.core.validation.ProtocolSchemas
import dev.ynagai.a2ui.core.validation.SchemaEvaluator
import dev.ynagai.a2ui.core.validation.SchemaLocation
import dev.ynagai.a2ui.core.validation.SchemaRegistry
import dev.ynagai.a2ui.core.validation.SchemaValidation
import dev.ynagai.a2ui.core.validation.ValidationLimits
import dev.ynagai.a2ui.core.validation.CatalogFixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * One assertion from the specification's own conformance suite.
 *
 * @property file the suite it came from, and [index] its position in that suite's `tests` array —
 *   together they are how `run_tests.py` names a case, so a failure here can be looked up there.
 */
internal data class ConformanceCase(
    val file: String,
    val index: Int,
    val description: String,
    val expectedValid: Boolean,
    val data: JsonElement,
    val suite: ConformanceSuite,
) {
    val name: String get() = "$file #$index"

    override fun toString(): String = "$name: $description"
}

/**
 * A suite file: a list of cases, plus the schema and the catalog they are checked against.
 *
 * Both are read from the file rather than assumed. Three of the fourteen suites bind the
 * `catalog.json` placeholder to the specification's testing catalog rather than to the basic one,
 * and running those against the basic catalog gets ten assertions wrong — in both directions, so
 * a harness that assumed would not simply look stricter.
 */
internal data class ConformanceSuite(
    val file: String,
    val schema: String,
    val catalog: String,
) {
    /** Which document the cases are validated against. */
    val target: SchemaTarget = when (schema) {
        "agent_to_renderer.json" -> SchemaTarget.AgentToRenderer
        "renderer_to_agent.json" -> SchemaTarget.RendererToAgent
        "catalog_definition.json" -> SchemaTarget.CatalogDefinitionDocument
        else -> error("$file names a schema this harness does not hold: $schema")
    }

    /** The catalog the `catalog.json` placeholder binds to for every case in this suite. */
    val catalogId: String = when (catalog) {
        "catalogs/basic/catalog.json" -> BASIC.catalogId
        "testing_catalog.json" -> TESTING.catalogId
        else -> error("$file names a catalog this harness does not hold: $catalog")
    }
}

/** Which of the specification's documents a suite validates against. */
internal sealed interface SchemaTarget {
    /** A message from the agent. */
    data object AgentToRenderer : SchemaTarget

    /** A message from the renderer. */
    data object RendererToAgent : SchemaTarget

    /**
     * A catalog document, rather than a message.
     *
     * `composition_constraints` checks catalogs themselves — that one may not define the reserved
     * `Surface` component, that `allowedParents` is an array of distinct strings. Those are
     * constraints on `catalog_definition.json`, so the case data is a catalog and there is no
     * placeholder to bind.
     */
    data object CatalogDefinitionDocument : SchemaTarget
}

internal val BASIC: CatalogDefinition =
    A2uiJson.strict.decodeFromString(CatalogDefinition.serializer(), CatalogFixtures.BASIC)

internal val TESTING: CatalogDefinition =
    A2uiJson.strict.decodeFromString(CatalogDefinition.serializer(), CatalogFixtures.TESTING)

private val VALIDATOR = CatalogValidator.of(listOf(BASIC, TESTING))

/** A validator at [limits], for the tests that measure how much of a bound the suite needs. */
internal fun validatorAt(limits: ValidationLimits): CatalogValidator =
    CatalogValidator.of(listOf(BASIC, TESTING), limits)

/** Every case in every suite, in the order `run_tests.py` reports them. */
internal val CONFORMANCE_CASES: List<ConformanceCase> = ConformanceSources.ALL.entries
    // Sorted by file so the order is the same on every target, and so a failure list reads the
    // way `run_tests.py` prints one. `toSortedMap` would do it, and is JVM-only.
    .sortedBy { it.key }
    .flatMap { (file, source) ->
        val document = Json.parseToJsonElement(source) as JsonObject
        val suite = ConformanceSuite(
            file = file,
            schema = document.string("schema") ?: "agent_to_renderer.json",
            catalog = document.string("catalog") ?: "catalogs/basic/catalog.json",
        )
        (document["tests"] as JsonArray).mapIndexed { index, element ->
            val case = element as JsonObject
            ConformanceCase(
                file = file,
                index = index,
                description = case.string("description") ?: "#$index",
                // A case that omits `valid` is asserting acceptance, as `run_tests.py` reads it.
                expectedValid = (case["valid"] as? JsonPrimitive)?.booleanOrNull ?: true,
                data = case["data"] ?: error("$file #$index carries no `data`."),
                suite = suite,
            )
        }
    }

/**
 * This implementation's verdict on [case], under [limits].
 *
 * [validator] is a parameter so that a caller running the whole suite at one set of limits builds
 * one -- as a default it would be rebuilt per case, and the cost tests run the suite a dozen times
 * over.
 */
internal fun verdict(
    case: ConformanceCase,
    limits: ValidationLimits = ValidationLimits.DEFAULT,
    validator: CatalogValidator = VALIDATOR,
): SchemaValidation = when (case.suite.target) {
    SchemaTarget.AgentToRenderer -> validator.validateMessage(
        message = case.data,
        direction = MessageDirection.AGENT_TO_RENDERER,
        catalogId = case.suite.catalogId,
    )

    SchemaTarget.RendererToAgent -> validator.validateMessage(
        message = case.data,
        direction = MessageDirection.RENDERER_TO_AGENT,
        catalogId = case.suite.catalogId,
    )

    SchemaTarget.CatalogDefinitionDocument -> {
        val registry = SchemaRegistry.of(ProtocolSchemas.documents)
        val location = SchemaLocation(CATALOG_DEFINITION_URI, "")
        SchemaEvaluator(registry, limits)
            .validate(ProtocolSchemas.catalogDefinition, location, case.data)
    }
}

private const val CATALOG_DEFINITION_URI =
    "https://a2ui.org/specification/v1_0/catalog_definition.json"

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
