package dev.ynagai.a2ui.core.protocol

import dev.ynagai.a2ui.core.validation.isUnicodeIdentifier
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Checks a catalog against the structural rules the specification states only in prose.
 *
 * `a2ui_protocol.md`'s "Catalog Entity Naming Rules" makes component names, function names, and
 * argument/property names MUST-conform to UAX #31, and gives the canonical regex
 * `^[\p{XID_Start}_][\p{XID_Continue}]*$`. **`catalog_definition.json` does not encode this**:
 * its `components.propertyNames` forbids the reserved `Surface` and nothing else, and `functions`
 * carries no `propertyNames` at all. So the schema evaluator cannot reach the rule, and the
 * specification's own harness does not try -- `test/run_tests.py`'s `validate_catalogs_identifiers`
 * is a separate pass, outside the JSON Schema validation. This is that pass.
 *
 * It also enforces rule 3 of "Catalog Schema Rules and Conventions", which restricts `$ref`, not
 * because that is a naming rule but because it is what makes the name rule *checkable*: without
 * it a name cannot be checked where it stands, only where it is reachable from -- see
 * [checkSchema].
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
    checkCarriedKeywords(schemaKeywords)
    components.forEach { (name, definition) ->
        // Rule 4 of the naming section, and enforced here for the same reason as rules 1-3: a
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
        checkSchema(definition.schema, "component `${name.take(ERROR_EXCERPT)}`")
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
        checkSchema(definition.schema.raw, "function `${name.take(ERROR_EXCERPT)}`")
    }
}

/**
 * The three keywords `CatalogDefinitionSerializer` carries through unread.
 *
 * `$schema` and `$id` are strings in `catalog_definition.json`, and `$defs` is an object whose
 * only permitted keys are `anyComponent` and `anyFunction` -- rule 2 of "Catalog Schema Rules and
 * Conventions" prohibits custom definitions there outright. None of that is checked on the way in:
 * the serializer selects these by key *name* and `rejectUnknownKeys` checks names alone, so each
 * may arrive holding anything at all.
 *
 * Shapes are refused rather than skipped. An `as?` that yields null is a check that does not run,
 * and a region no check ran over is exactly what a `$ref` used to be aimed at -- which is also
 * why the keys of `$defs` are held to rule 2 here rather than left to the schema evaluator.
 */
private fun checkCarriedKeywords(schemaKeywords: Map<String, JsonElement>) {
    schemaKeywords.forEach { (keyword, value) ->
        val quoted = keyword.take(ERROR_EXCERPT)
        if (keyword != DEFS) {
            if (value !is JsonPrimitive || !value.isString) {
                throw A2uiFormatException(
                    "CatalogDefinition: `$quoted` must be a string; `catalog_definition.json` " +
                        "types it so, and an object or an array here is a region a `\$ref` can " +
                        "reach but no rule has been applied to.",
                )
            }
            return@forEach
        }
        val defs = value as? JsonObject ?: throw A2uiFormatException(
            "CatalogDefinition: `$DEFS` must be an object mapping names to subschemas.",
        )
        // Every entry is walked, whatever it is called. The specification's "No Custom `$defs` or
        // Helpers" rule permits only `anyComponent` and `anyFunction` here, and refusing the rest
        // outright was tried and reverted: the security property comes from the reference
        // restriction, not from this rule -- an entry nothing may point at is inert -- while
        // refusing them decides a compatibility question about third-party inline catalogs that
        // is not this check's to decide.
        defs.forEach { (name, subschema) ->
            checkSchema(subschema, "the catalog's `$DEFS/${name.take(ERROR_EXCERPT)}`")
        }
    }
}

/**
 * Every property name declared anywhere under [root], and every `$ref` it takes.
 *
 * The walk descends only where JSON Schema puts a subschema. That is a reversal: it used to be
 * blind, treating every object it reached as a schema, on the reasoning that enumerating the
 * keywords which may hold a subschema would need revising for every keyword the specification
 * later admits. The reasoning was sound and the consequence was not, because a blind walk cannot
 * be right in both directions at once:
 *
 *  - It entered data. `ComponentDefinitionSerializer` sets `schema` to the *whole* component
 *    object, so `metadata.extensions.<vendor>` -- arbitrary vendor JSON -- was read as a schema,
 *    and a vendor payload that happened to contain `{"properties": {"bad-name": 1}}` refused a
 *    catalog that breaks no rule.
 *  - The carve-out that compensated for that -- skipping `const`, `default`, `enum` and
 *    `examples` by *name*, wherever they appeared -- then hid subtrees. Under any keyword the
 *    walk did not know, an entry named `default` swallowed everything below it, so
 *    `{"x-shared":{"default":{"properties":{"bad-name":…}}}}` was accepted.
 *
 * Position awareness settles both: data is never entered, and the carve-out becomes unnecessary
 * rather than merely narrower, because `const`, `default`, `enum` and `examples` are simply not
 * places a subschema lives.
 *
 * What makes that safe is the reference restriction below. A blind walk was, in one respect,
 * doing real work: this renderer's `SchemaRegistry` resolves a `$ref` by JSON Pointer without
 * asking whether the target stood in a schema position, and `SchemaEvaluator` then evaluates
 * whatever it finds. Ceasing to walk vendor data would therefore have traded a false positive
 * for a false negative -- `{"$ref": "#/components/Text/metadata/extensions/vendor"}` -- had the
 * pointer not been restricted in the same change. It is the pair that is correct, not either
 * half.
 *
 * Only names a catalog *declares* are checked, which is the distinction the suite already draws:
 * a `required` entry, a `dependentSchemas` trigger and a `$defs` entry name all *refer* to
 * something, and refusing them would reject catalogs that break no rule.
 *
 * Iterative rather than recursive. A definition is as deeply nested as whoever wrote it chose,
 * an inlined catalog is agent-controlled, and Kotlin/Native aborts the process on stack overflow
 * rather than raising something a caller could catch.
 */
private fun checkSchema(root: JsonElement, owner: String) {
    val pending = ArrayDeque<JsonElement>()
    pending.addLast(root)
    while (pending.isNotEmpty()) {
        // A schema is an object or a boolean. Anything else in a schema position is malformed,
        // and there is nothing under it to walk -- the evaluator ignores it too.
        val schema = pending.removeLast() as? JsonObject ?: continue
        schema.forEach { (keyword, value) ->
            when {
                keyword == REF -> requirePermittedReference(value, owner)
                keyword in SUBSCHEMA -> pending.addLast(value)
                keyword == ITEMS ->
                    // 2020-12 gives `items` a single schema; draft-07 also let it hold the
                    // tuple form, an array of them. Both are still written.
                    if (value is JsonArray) value.forEach { pending.addLast(it) }
                    else pending.addLast(value)
                keyword in SUBSCHEMA_LIST -> (value as? JsonArray)?.forEach { pending.addLast(it) }
                keyword in SCHEMA_MAPS -> (value as? JsonObject)?.forEach { (name, subschema) ->
                    // A key here is a name its author chose, not a keyword. Only under
                    // `properties` is it a name the rule governs -- a `$defs` entry name, a
                    // `patternProperties` regex and a `dependencies` trigger are none of them.
                    if (keyword == PROPERTIES) requireIdentifier(name, "property name in $owner")
                    // A draft-07 `dependencies` entry may hold an array of required property
                    // names rather than a subschema; it is not an object, so the pop discards it.
                    pending.addLast(subschema)
                }
                // Annotations, vendor extensions and instance values. Not schemas, so not walked,
                // and -- since a `$ref` may no longer be aimed into them -- not reachable either.
                else -> Unit
            }
        }
    }
}

/**
 * Rule 3, "Restricted `$ref` Targets".
 *
 * A local target must name a top-level component or function; an external one must name a
 * definition in `common_types.json`. This is the rule that lets [checkSchema] decline to walk a
 * region: what is not a schema position cannot be turned into one by a pointer.
 *
 * The prose narrows external targets further, to eleven named `common_types.json` schemas, and
 * that half is deliberately not enforced: **the specification's own `basic.json` violates it**,
 * referencing `Child`, `DataBinding` and `FunctionCall`, none of which are on the list, while
 * `testing.json` writes the relative `common_types.json#/$defs/…` rather than the absolute URL
 * the prose gives. Enforcing the list literally would refuse the catalogs the specification
 * ships. Restricting the *document* is what the security property needs; restricting which of
 * its definitions may be named is a conformance question for upstream.
 */
private fun requirePermittedReference(target: JsonElement, owner: String) {
    val reference = (target as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw A2uiFormatException(
            "CatalogDefinition: a `$REF` in $owner must be a string.",
        )
    // Both patterns are tried rather than dispatched on a leading `#`: `catalog.json#/$defs/X`
    // is a local target wearing a document name, and testing it as an external one refused it.
    val permitted =
        LOCAL_REFERENCE.matches(reference) || COMMON_TYPES_REFERENCE.matches(reference)
    if (!permitted) {
        throw A2uiFormatException(
            "CatalogDefinition: `${reference.take(ERROR_EXCERPT)}` in $owner is not a permitted " +
                "`$REF` target; the specification restricts a local one to the catalog's " +
                "top-level components and functions (`#/components/Text`, `#/functions/required`)" +
                " and an external one to `common_types.json#/\$defs/…`.",
        )
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
internal const val PROPERTIES: String = "properties"

/**
 * The keywords whose value is a map from *names* to subschemas rather than a schema.
 *
 * Unlike the set of keywords that may hold a subschema, this one is closed. JSON Schema 2020-12
 * has four -- [PROPERTIES], `patternProperties`, [DEFS] and `dependentSchemas` -- and the earlier
 * drafts a catalog may still be written against spell the last two `definitions` and
 * `dependencies`. Only [PROPERTIES] holds names the naming rule governs; the rest are here so
 * that their entries are walked as the schemas they are, without their author-chosen keys being
 * read as keywords or as entity names.
 *
 * `internal` rather than private so `CatalogEntityNamesTest` can iterate this set rather than
 * retype it -- a retyped copy stays green on a member added here, and so would cover a seventh
 * keyword with nothing. Iterating alone is only half of it: a derived list also shrinks when a
 * member is *dropped*, which is how the bug this set was widened for would reopen unnoticed. The
 * other half is `the_closed_set_of_name_maps_is_pinned_and_not_merely_iterated`, which pins the
 * membership. Change either and that test must be changed too, on purpose.
 */
internal val SCHEMA_MAPS: Set<String> = setOf(
    PROPERTIES,
    "patternProperties",
    DEFS,
    "definitions",
    "dependentSchemas",
    "dependencies",
)

/**
 * The keywords whose value is a single subschema.
 *
 * `additionalItems` is draft-07's tail-of-tuple schema, kept for the same reason `definitions`
 * and `dependencies` are in [SCHEMA_MAPS]: catalogs written against the earlier drafts still use
 * it, and a position the walk does not know is a position it does not check. `contentSchema` is
 * an annotation the evaluator does not apply, but its value is still a schema and may still
 * declare property names.
 */
internal val SUBSCHEMA: Set<String> = setOf(
    "additionalItems",
    "additionalProperties",
    "contains",
    "contentSchema",
    "else",
    "if",
    "not",
    "propertyNames",
    "then",
    "unevaluatedItems",
    "unevaluatedProperties",
)

/** The keywords whose value is an array of subschemas. */
internal val SUBSCHEMA_LIST: Set<String> = setOf("allOf", "anyOf", "oneOf", "prefixItems")

/** A schema in 2020-12, an array of them in draft-07's tuple form. */
internal const val ITEMS: String = "items"

/** Where a catalog keeps the subschemas its definitions reference rather than inline. */
private const val DEFS: String = "\$defs"

private const val REF: String = "\$ref"

/**
 * A top-level definition of this catalog, and nothing inside one.
 *
 * `catalog.json` is how a document names the *active* catalog -- `common_types.json` itself
 * refers to `catalog.json#/$defs/anyFunction` -- so a catalog may write either spelling for its
 * own definitions.
 *
 * The depth is the whole point, and is what the prose's rule 3 is protecting even though it
 * enumerates only `components` and `functions`: `#/components/Text` names a schema this walk
 * checked, while `#/components/Text/metadata/extensions/vendor` names a region it deliberately
 * did not. `$defs` is admitted alongside them because every entry under it is walked as a schema
 * whatever it is called, so naming one is no different from naming a component.
 */
private val LOCAL_REFERENCE: Regex =
    Regex("^(?:catalog\\.json)?#/(?:components|functions|\\\$defs)/[^/]+$")

/** `common_types.json#/$defs/<name>`, however the catalog spells the document's location. */
private val COMMON_TYPES_REFERENCE: Regex =
    Regex("^(?:[^#]*/)?common_types\\.json#/\\\$defs/[^/]+$")

/** The prefix `a2ui_protocol.md`'s System Namespace Rule reserves. */
private const val SYSTEM_FUNCTION_PREFIX: String = "@"

/** How much of a name an error message quotes; a catalog chooses its own key lengths. */
private const val ERROR_EXCERPT: Int = 64
