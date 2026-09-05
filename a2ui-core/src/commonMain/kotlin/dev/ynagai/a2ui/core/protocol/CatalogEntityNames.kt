package dev.ynagai.a2ui.core.protocol

import dev.ynagai.a2ui.core.validation.isUnicodeIdentifier
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Checks a catalog's entity names against the rule the specification states in prose.
 *
 * `a2ui_protocol.md`'s "Catalog Entity Naming Rules" makes component names, function names, and
 * argument/property names MUST-conform to UAX #31, and gives the canonical regex
 * `^[\p{XID_Start}_][\p{XID_Continue}]*$`. **`catalog_definition.json` does not encode this**:
 * its `components.propertyNames` forbids the reserved `Surface` and nothing else, and `functions`
 * carries no `propertyNames` at all. So the schema evaluator cannot reach the rule, and the
 * specification's own harness does not try -- `test/run_tests.py`'s `validate_catalogs_identifiers`
 * is a separate pass, outside the JSON Schema validation. This is that pass.
 *
 * It runs from [CatalogDefinition]'s `init` rather than from its serializer, because a catalog
 * reaches a checker three ways and only one of them decodes: `CatalogValidator.of` and
 * `CompositionValidator` both take `List<CatalogDefinition>` directly, so a catalog built in
 * Kotlin -- or a `copy()` of a decoded one -- would otherwise carry names no wire catalog could.
 *
 * Refusing the whole definition, rather than reporting a `SchemaViolation`, is the strength the
 * neighbouring rules already have: an unparseable `protocolVersion` and a catalog that defines
 * `Surface` both throw from here, and the upstream harness likewise fails the catalog file rather
 * than the entity. A name is not a payload the agent can be asked to correct; it is the catalog
 * the renderer would then be checking everything else against.
 */
internal fun checkEntityNames(
    components: Map<String, ComponentDefinition>,
    functions: Map<String, FunctionDefinition>,
    schemaKeywords: Map<String, JsonElement> = emptyMap(),
) {
    // A definition may reach its properties through the catalog's own `$defs` rather than declare
    // them inline, and `schemaKeywords` carries that `$defs` precisely so those references still
    // resolve. Walking only the definitions would leave `{"$defs":{"Base":{"properties":
    // {"bad-name":…}}},"components":{"Text":{"$ref":"#/$defs/Base"}}}` accepted, with `bad-name`
    // a live component property. The upstream harness has this gap too -- it walks `components`
    // and `functions` alone -- so closing it is deliberately stricter than the reference
    // implementation, and stricter only about names the prose rule already forbids.
    (schemaKeywords[DEFS] as? JsonObject)?.let { defs ->
        checkPropertyNames(buildJsonObject { put(DEFS, defs) }, "the catalog's `$DEFS`")
    }
    components.forEach { (name, definition) ->
        // Rule 4 of the same section, and enforced here for the same reason as rules 1-3: a
        // catalog may not redefine the surface's implicit root. `catalog_definition.json` does
        // encode this one, as `components.propertyNames`, but the schema is not consulted on the
        // path a definition built in Kotlin takes.
        if (name == Surface.COMPONENT) {
            throw A2uiFormatException(
                "CatalogDefinition: `${Surface.COMPONENT}` is reserved and cannot be defined by " +
                    "a catalog.",
            )
        }
        requireIdentifier(name, "component name")
        checkPropertyNames(definition.schema, "component `${name.take(ERROR_EXCERPT)}`")
    }
    functions.forEach { (name, definition) ->
        // The `@` namespace is reserved before UAX #31 is consulted, because the reason differs
        // and so does the fix. `a2ui_protocol.md`'s System Namespace Rule gives `@`-prefixed names
        // to "universal system context evaluations available across all catalogs" -- of which
        // v1.0 has exactly one, `@index` -- and says custom catalogs MUST NOT define them. Saying
        // `@ping` is not an identifier is true but unhelpful: dropping the `@` would not make the
        // name available, since the namespace is not the catalog's to define in.
        //
        // Note that `run_tests.py` strips a leading `@` before checking and so accepts `@ping`.
        // That contradicts the prose in three places -- the canonical regex, the v1.0 changes
        // list, and the System Namespace Rule -- and its strip branch is exercised by no bundled
        // catalog, since neither `basic` nor `testing` names a function with an `@`.
        if (name.startsWith(SYSTEM_FUNCTION_PREFIX)) {
            throw A2uiFormatException(
                "CatalogDefinition: `${name.take(ERROR_EXCERPT)}` is in the `" +
                    "$SYSTEM_FUNCTION_PREFIX` namespace, which is reserved for system functions " +
                    "such as `${FunctionCall.INDEX}` and cannot be defined by a catalog.",
            )
        }
        requireIdentifier(name, "function name")
        checkPropertyNames(definition.schema.raw, "function `${name.take(ERROR_EXCERPT)}`")
    }
}

/**
 * Every key of every `properties` object anywhere under [schema].
 *
 * The walk is blind to where in JSON Schema it is, as the upstream harness's is: a property name
 * is a property name whether it sits under `allOf`, inside `items`, in a `then` branch, or in a
 * `$defs` entry the definition composes, and enumerating the keywords that may hold a subschema
 * would have to be revised for every keyword the specification later admits.
 *
 * With one exception, which is not a matter of taste. `const`, `default`, `enum` and `examples`
 * hold *instances*, not subschemas -- a component whose `default` is `{"properties": {"x-y": 1}}`
 * carries a JSON object that happens to use those two words, and no property name at all. The
 * upstream harness descends into them anyway and would refuse such a catalog; this does not.
 * Skipping them cannot hide a real violation, because no subschema is reachable through them --
 * but that is only true of those words in *keyword* position. An entry may be *named* `default`,
 * and its value is then an ordinary subschema.
 *
 * So the walk distinguishes the two objects JSON Schema is built from. In a *schema*, a key is a
 * keyword. In one of [SCHEMA_MAPS], a key is a **name** its author chose and the value beneath it
 * is a schema; such a map is therefore never popped as a schema, and its entries are enqueued one
 * by one. Reading a name map as though it were a schema is what let an entry named `default`
 * swallow its own subtree, and an entry named `properties` have its subschema's keywords mistaken
 * for names. Note that this enumerates only the keywords whose value is a *map of names* -- a
 * closed set of five -- and not the far larger, open set of keywords that may hold a subschema,
 * which is what the blindness above exists to avoid having to track.
 *
 * Iterative rather than recursive. A definition is as deeply nested as whoever wrote it chose,
 * an inlined catalog is agent-controlled, and Kotlin/Native aborts the process on stack overflow
 * rather than raising something a caller could catch.
 */
private fun checkPropertyNames(schema: JsonObject, owner: String) {
    val pending = ArrayDeque<JsonElement>()
    pending.addLast(schema)
    while (pending.isNotEmpty()) {
        when (val element = pending.removeLast()) {
            is JsonObject -> element.forEach { (key, value) ->
                if (key in INSTANCE_KEYWORDS) return@forEach
                if (key in SCHEMA_MAPS && value is JsonObject) {
                    // Enqueue the entries, never the map: a key here is a name its author chose,
                    // so popping the map as a schema would read an entry named `default` as the
                    // keyword and one named `properties` as another name map. Only under
                    // `properties` is that name an entity name the rule governs -- a `$defs`
                    // entry name and a `patternProperties` regex are neither.
                    value.forEach { (name, subschema) ->
                        if (key == PROPERTIES) requireIdentifier(name, "property name in $owner")
                        pending.addLast(subschema)
                    }
                    return@forEach
                }
                pending.addLast(value)
            }
            is JsonArray -> element.forEach { pending.addLast(it) }
            else -> Unit
        }
    }
}

private fun requireIdentifier(name: String, what: String) {
    if (!isUnicodeIdentifier(name)) {
        throw A2uiFormatException(
            "CatalogDefinition: `${name.take(ERROR_EXCERPT)}` is not a valid $what; a catalog " +
                "entity name must be a UAX #31 identifier, which the specification writes as " +
                "`^[\\p{XID_Start}_][\\p{XID_Continue}]*\$`.",
        )
    }
}

/** The keyword whose keys are the names the rule is about. */
private const val PROPERTIES: String = "properties"

/**
 * The keywords whose value is a map from *names* to subschemas rather than a schema.
 *
 * JSON Schema 2020-12 has exactly these five, so unlike the set of keywords that may hold a
 * subschema this one does not grow with the specification. Only [PROPERTIES] holds names the
 * naming rule governs; the rest are here so that their entries are walked as the schemas they
 * are, without their author-chosen keys being read as keywords or as entity names.
 */
private val SCHEMA_MAPS: Set<String> = setOf(
    PROPERTIES,
    "patternProperties",
    DEFS,
    "definitions",
    "dependentSchemas",
)

/** Where a catalog keeps the subschemas its definitions reference rather than inline. */
private const val DEFS: String = "\$defs"

/** The prefix `a2ui_protocol.md`'s System Namespace Rule reserves. */
private const val SYSTEM_FUNCTION_PREFIX: String = "@"

/** JSON Schema keywords whose values are instances, and so hold no property names. */
private val INSTANCE_KEYWORDS: Set<String> = setOf("const", "default", "enum", "examples")

/** How much of a name an error message quotes; a catalog chooses its own key lengths. */
private const val ERROR_EXCERPT: Int = 64
