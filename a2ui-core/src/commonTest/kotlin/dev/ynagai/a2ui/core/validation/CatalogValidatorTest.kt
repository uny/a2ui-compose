package dev.ynagai.a2ui.core.validation

import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.CatalogDefinition
import dev.ynagai.a2ui.core.protocol.Component
import dev.ynagai.a2ui.core.protocol.FunctionCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The two checkers against the published basic catalog.
 *
 * Every case below is one of the conformance assertions T0 classified as needing catalog-driven
 * validation, named by the file and index `run_tests.py` reports. They are exercised here against
 * the same catalog the conformance harness will use, ahead of that harness existing, because a
 * checker measured only against a catalog written to suit it has not been measured.
 */
private val BASIC: CatalogDefinition =
    A2uiJson.strict.decodeFromString(CatalogDefinition.serializer(), CatalogFixtures.BASIC)

private val VALIDATOR = CatalogValidator.of(listOf(BASIC))

private val BASIC_ID = BASIC.catalogId

private fun call(json: String): FunctionCall =
    A2uiJson.strict.decodeFromString(FunctionCall.serializer(), json)

private fun component(json: String): Component =
    A2uiJson.strict.decodeFromString(Component.serializer(), json)

private fun checkCall(json: String): SchemaValidation = VALIDATOR.validate(call(json), BASIC_ID)

private fun checkComponent(json: String): SchemaValidation =
    VALIDATOR.validate(component(json), BASIC_ID)

private fun parseObject(source: String): JsonObject = Json.parseToJsonElement(source) as JsonObject

/** A schema exercising the keywords only `catalog_definition.json` reaches. */
private val KEYWORD_PROBE_SCHEMA = """
{
  "${'$'}id": "urn:probe",
  "type": "object",
  "properties": {
    "tags": {"type": "array", "uniqueItems": true, "contains": {"type": "string"}},
    "names": {"type": "object", "propertyNames": {"not": {"const": "Surface"}}},
    "version": {"type": "string", "pattern": "^[0-9]+\\.[0-9]+\\.[0-9]+${'$'}"}
  }
}
"""

class CatalogValidatorTest {
    // --- catalog resolution order -------------------------------------------------------------

    @Test
    fun a_component_names_its_own_catalog_before_the_surface_default() {
        val resolution = VALIDATOR.resolve(explicit = BASIC_ID, surfaceDefault = "urn:other")
        assertEquals(CatalogResolution.Found(BASIC_ID, BASIC), resolution)
    }

    @Test
    fun the_surface_default_applies_when_the_component_omits_one() {
        val resolution = VALIDATOR.resolve(explicit = null, surfaceDefault = BASIC_ID)
        assertEquals(CatalogResolution.Found(BASIC_ID, BASIC), resolution)
    }

    @Test
    fun naming_no_catalog_at_all_is_an_error_not_a_guess() {
        // Falling back to "the only catalog loaded" would make a payload mean different things in
        // two renderers that hold different sets.
        assertIs<CatalogResolution.Unspecified>(VALIDATOR.resolve(null, null))
        val result = VALIDATOR.validate(call("""{"call": "email", "args": {"value": "a@b.c"}}"""))
        assertFalse(result.isValid)
    }

    @Test
    fun a_catalog_this_renderer_does_not_hold_is_reported_as_such() {
        val resolution = VALIDATOR.resolve("urn:nope", null)
        assertEquals(CatalogResolution.Unknown("urn:nope"), resolution)
        val result = VALIDATOR.validate(
            call("""{"call": "email", "catalogId": "urn:nope", "args": {"value": "a@b.c"}}"""),
        )
        assertFalse(result.isValid)
        assertTrue(
            result.violations.any { "does not hold" in it.message },
            result.violations.toString(),
        )
    }

    // --- FunctionCall checking, against the basic catalog --------------------------------------

    @Test
    fun accepts_every_function_the_basic_catalog_defines() {
        val calls = listOf(
            """{"call": "required", "args": {"value": "x"}}""",
            """{"call": "regex", "args": {"value": "x", "pattern": "^x"}}""",
            """{"call": "length", "args": {"value": "x", "min": 1, "max": 3}}""",
            """{"call": "numeric", "args": {"value": 2, "min": 1}}""",
            """{"call": "email", "args": {"value": "a@b.c"}}""",
            """{"call": "formatString", "args": {"value": "hi ${'$'}{/name}"}}""",
            """{"call": "formatNumber", "args": {"value": 1, "decimals": 2}}""",
            """{"call": "formatCurrency", "args": {"value": 1, "currency": "USD"}}""",
            """{"call": "formatDate", "args": {"value": "2026-01-01", "format": "yyyy"}}""",
            """{"call": "pluralize", "args": {"value": 1, "one": "x", "other": "y"}}""",
            """{"call": "openUrl", "args": {"url": "https://example.dev"}}""",
            """{"call": "not", "args": {"value": true}}""",
        )
        for (payload in calls) {
            val result = checkCall(payload)
            assertTrue(result.isValid, "$payload -> ${result.violations}")
            assertEquals(emptySet(), result.unsupportedKeywords, payload)
        }
    }

    @Test
    fun accepts_a_call_composed_of_other_calls() {
        // `and` takes at least two boolean-valued arguments, each of which is itself a call that
        // resolves back through the catalog.
        val result = checkCall(
            """
            {"call": "and", "args": {"values": [
              {"call": "required", "args": {"value": "a"}},
              {"call": "regex", "args": {"value": "a", "pattern": "^a"}}
            ]}}
            """.trimIndent(),
        )
        assertTrue(result.isValid, result.violations.toString())
    }

    @Test
    fun accepts_the_index_system_function_without_a_catalog() {
        // `@index` is defined by `common_types.json`, not by any catalog, so it resolves with no
        // surface default in play.
        assertTrue(VALIDATOR.validate(call("""{"call": "@index"}""")).isValid)
        assertTrue(VALIDATOR.validate(call("""{"call": "@index", "args": {"offset": 1}}""")).isValid)
    }

    @Test
    fun rejects_a_system_function_carrying_a_catalog_id() {
        // `function_catalog_validation` #46. No catalog defines `@index`, so naming one is not a
        // redundancy to tolerate -- it asks for something that cannot exist.
        assertFalse(
            VALIDATOR.validate(call("""{"call": "@index", "catalogId": "$BASIC_ID"}""")).isValid,
        )
    }

    @Test
    fun rejects_an_at_prefixed_name_that_is_not_the_one_system_function() {
        // v1.0 reserves no `@` namespace: `@nope` is a catalog function no catalog defines.
        assertFalse(checkCall("""{"call": "@nope", "args": {}}""").isValid)
    }

    @Test
    fun rejects_the_argument_defects_the_conformance_cases_name() {
        val cases = mapOf(
            "#1  required: empty args" to """{"call": "required", "args": {}}""",
            "#4  regex: missing pattern" to """{"call": "regex", "args": {"value": "a"}}""",
            "#7  length: empty constraint" to """{"call": "length", "args": {"value": "a"}}""",
            // The case descriptions call this argument `precision` and `formatDate`'s
            // `pattern`; the catalog names them `decimals` and `format`. The payloads are what
            // count -- a test written from the prose would be rejected for the wrong reason.
            "#13 formatNumber: decimals type" to
                """{"call": "formatNumber", "args": {"value": 1, "decimals": "2"}}""",
            "#29 formatNumber: decimals is a boolean" to
                """{"call": "formatNumber", "args": {"value": 1, "decimals": true}}""",
            "#31 formatDate: format is null" to
                """{"call": "formatDate", "args": {"value": "2026-01-01", "format": null}}""",
            "#30 formatCurrency: currency code type" to
                """{"call": "formatCurrency", "args": {"value": 1, "currency": 7}}""",
            "#15 formatCurrency: missing currency" to
                """{"call": "formatCurrency", "args": {"value": 1}}""",
            "#18 pluralize: missing other" to
                """{"call": "pluralize", "args": {"value": 1, "one": "x"}}""",
            "#22 length: min type" to """{"call": "length", "args": {"value": "a", "min": "1"}}""",
            "#23 length: negative max" to """{"call": "length", "args": {"value": "a", "max": -1}}""",
            "#24 numeric: min type" to """{"call": "numeric", "args": {"value": 1, "min": "1"}}""",
            "#26 regex: pattern type" to """{"call": "regex", "args": {"value": "a", "pattern": 7}}""",
            "#27 email: too many args" to
                """{"call": "email", "args": {"value": "a@b.c", "extra": 1}}""",
            "#28 formatString: format string type" to """{"call": "formatString", "args": {"value": 7}}""",
            "#32 openUrl: not a URI" to """{"call": "openUrl", "args": {"url": "not a uri"}}""",
            "#34 and: single value" to
                """{"call": "and", "args": {"values": [{"call": "required", "args": {"value": "a"}}]}}""",
            "#38 not: string argument" to """{"call": "not", "args": {"value": "x"}}""",
            "#40 required: too many args" to
                """{"call": "required", "args": {"value": "a", "extra": 1}}""",
            "#44 @index: string offset" to """{"call": "@index", "args": {"offset": "1"}}""",
        )
        for ((name, payload) in cases) {
            val result = checkCall(payload)
            assertFalse(result.isValid, "$name should have been rejected")
            assertEquals(emptySet(), result.unsupportedKeywords, name)
        }
    }

    @Test
    fun accepts_a_call_that_names_its_catalog_explicitly() {
        // Named for what it asserts. The extra-key case this used to claim -- but did not carry a
        // key for, and asserted acceptance of -- cannot be expressed here at all: `FunctionCall`
        // models `call`, `catalogId` and `args` and nothing else, so a key outside those never
        // survives decoding to reach this entry point. It is checked through `validateMessage`,
        // which takes the wire form, in `rejects_an_extra_key_on_a_call_in_a_message`.
        val result = VALIDATOR.validate(
            call("""{"call": "email", "args": {"value": "a@b.c"}}""").copy(catalogId = BASIC_ID),
        )
        assertTrue(result.isValid, result.violations.toString())
    }

    // --- component checking --------------------------------------------------------------------

    @Test
    fun accepts_components_the_basic_catalog_defines() {
        val components = listOf(
            """{"id": "t", "component": "Text", "text": "hi"}""",
            """{"id": "d", "component": "Divider"}""",
            """{"id": "c", "component": "Column", "children": ["t"]}""",
            """{"id": "b", "component": "Button", "child": "t",
                "action": {"event": {"name": "go"}}}""",
        )
        for (payload in components) {
            val result = checkComponent(payload)
            assertTrue(result.isValid, "$payload -> ${result.violations}")
            assertEquals(emptySet(), result.unsupportedKeywords, payload)
        }
    }

    @Test
    fun rejects_a_component_property_the_catalog_does_not_define() {
        // `initial_state_validation` #19, and the deprecated-property cases that are the same
        // shape: `button_checks` #1 (`enabled`) and #6 (`primary`).
        val cases = listOf(
            """{"id": "b", "component": "Button", "child": "t",
               "action": {"event": {"name": "g"}}, "enabled": true}""",
            """{"id": "b", "component": "Button", "child": "t",
               "action": {"event": {"name": "g"}}, "primary": true}""",
            """{"id": "t", "component": "Text", "text": "hi", "unexpected": 1}""",
        )
        for (payload in cases) {
            assertFalse(checkComponent(payload).isValid, payload)
        }
    }

    @Test
    fun rejects_a_component_named_after_the_reserved_container() {
        // `initial_state_validation` #9 and #10 -- neither `createSurface` nor `updateComponents`
        // may create one, and the reason is the same for both.
        val result = checkComponent("""{"id": "x", "component": "Surface"}""")
        assertFalse(result.isValid)
        assertTrue(
            result.violations.any { "reserved" in it.message },
            result.violations.toString(),
        )
    }

    @Test
    fun rejects_the_component_defects_the_conformance_cases_name() {
        val cases = mapOf(
            "text_variants #1: h1" to """{"id": "t", "component": "Text", "text": "x", "variant": "h1"}""",
            "text_variants #2: unknown" to
                """{"id": "t", "component": "Text", "text": "x", "variant": "nope"}""",
            "tabs_checks #0: empty tabs" to """{"id": "t", "component": "Tabs", "tabs": []}""",
            "checkable #13: steps is a string" to
                """{"id": "s", "component": "Slider", "value": 1, "min": 0, "max": 9, "steps": "3"}""",
            "checkable #14: steps below one" to
                """{"id": "s", "component": "Slider", "value": 1, "min": 0, "max": 9, "steps": 0}""",
            "icon_checks #4: svgPath type" to
                """{"id": "i", "component": "Icon", "name": {"svgPath": 7}}""",
            "icon_checks #5: svgPath binding with extras" to
                """{"id": "i", "component": "Icon", "name": {"svgPath": {"path": "/p", "extra": 1}}}""",
        )
        for ((name, payload) in cases) {
            val result = checkComponent(payload)
            assertFalse(result.isValid, "$name should have been rejected")
            assertEquals(emptySet(), result.unsupportedKeywords, name)
        }
    }

    @Test
    fun accepts_a_tabs_component_with_at_least_one_tab() {
        val result = checkComponent(
            """{"id": "t", "component": "Tabs", "tabs": [{"title": "a", "child": "c"}]}""",
        )
        assertTrue(result.isValid, result.violations.toString())
    }

    // --- extension keys (UAX #31, approximated) --------------------------------------------------

    @Test
    fun accepts_extension_keys_that_are_unicode_identifiers() {
        // `initial_state_validation` #12 must not be rejected: these are the accepting side of the
        // specification's own fixture.
        for (key in listOf("wellsky_über", "wellsky_τάξις", "_leading", "a2ui_official")) {
            val result = checkComponent(
                """{"id": "t", "component": "Text", "text": "x",
                    "metadata": {"extensions": {"$key": 1}}}""",
            )
            assertTrue(result.isValid, "$key -> ${result.violations}")
        }
    }

    @Test
    fun rejects_extension_keys_that_are_not_identifiers() {
        // `initial_state_validation` #16, and #13 for the surface-level form.
        for (key in listOf("invalid-key-with-dashes", "123start_with_number", "has space", "")) {
            val result = checkComponent(
                """{"id": "t", "component": "Text", "text": "x",
                    "metadata": {"extensions": {"$key": 1}}}""",
            )
            assertFalse(result.isValid, "$key should have been rejected")
        }
    }

    @Test
    fun applies_the_uax31_pattern_rather_than_reporting_it_unsupported() {
        // The pattern `common_types.json` writes is `\p{XID_Start}`, which `Regex` cannot compile
        // on two of this library's five targets. Reaching a verdict at all is the assertion here;
        // that the verdict is an approximation is documented on `isUnicodeIdentifier`.
        val result = checkComponent(
            """{"id": "t", "component": "Text", "text": "x",
                "metadata": {"extensions": {"ok_key": 1}}}""",
        )
        assertEquals(emptySet(), result.unsupportedKeywords)
    }

    // --- a catalog may not choose what it is checked against ---------------------------------

    @Test
    fun a_catalog_cannot_shadow_a_document_the_specification_publishes() {
        // `$id` is a free string on a catalog -- `catalog_definition.json` requires only
        // `catalogId` -- and a catalog may arrive inlined in an agent's capabilities message. A
        // catalog that claims `agent_to_renderer.json` and defines `Component` as `true` would
        // otherwise become the schema every component is checked against, which is the whole of
        // the check.
        val hostile = BASIC.copy(
            catalogId = "urn:agent:inlined",
            schemaKeywords = BASIC.schemaKeywords + mapOf(
                "\$id" to JsonPrimitive(ProtocolSchemas.AGENT_TO_RENDERER_URI),
                "\$defs" to parseObject("""{"Component": true}"""),
            ),
        )
        val result = CatalogValidator.of(listOf(BASIC, hostile)).validate(
            component("""{"id": "c", "component": "Text", "nope": true}"""),
            surfaceDefault = BASIC_ID,
        )
        assertFalse(result.isValid, "the inlined catalog replaced `agent_to_renderer.json`")
    }

    @Test
    fun a_catalog_cannot_take_the_placeholder_from_the_catalog_in_play() {
        // The placeholder is a filename, not a URI. A catalog registering itself under the URI it
        // resolves to would otherwise answer `catalog.json#/$defs/anyComponent` for a surface that
        // named a different catalog entirely.
        val hostile = BASIC.copy(
            catalogId = "urn:agent:inlined",
            schemaKeywords = BASIC.schemaKeywords + mapOf(
                "\$id" to JsonPrimitive("https://a2ui.org/specification/v1_0/catalog.json"),
                "\$defs" to parseObject(
                    """{"anyComponent": {"type": "object", "additionalProperties": true}}""",
                ),
            ),
        )
        val result = CatalogValidator.of(listOf(BASIC, hostile)).validate(
            component("""{"id": "c", "component": "Text", "nope": true}"""),
            surfaceDefault = BASIC_ID,
        )
        assertFalse(result.isValid, "the inlined catalog took the placeholder binding")
    }

    // --- coverage --------------------------------------------------------------------------------

    @Test
    fun nothing_this_library_ships_uses_a_keyword_the_evaluator_skips() {
        // The one assertion that keeps the subset honest, over every document that ships: the four
        // protocol schemas, the published basic catalog, and the specification's own testing
        // catalog. If the specification adds a keyword, or this evaluator loses one, a green
        // conformance run would otherwise still mean nothing -- an unapplied keyword shows up as
        // acceptance, not as failure.
        val seen = mutableSetOf<String>()
        // The keywords whose *values* are keyed by property name rather than by keyword. Their
        // keys are names and must not be collected; the subschema under each name is a schema
        // again, so the walk resumes there. Deciding that from the name rather than from the
        // keyword above it -- as this did -- makes a property literally called `properties` open a
        // second name-space, and `catalog_definition.json` has two of those.
        val nameSpaces = setOf("properties", "\$defs", "patternProperties", "dependentSchemas")
        fun walk(node: JsonElement, inNames: Boolean) {
            when (node) {
                is JsonObject -> node.forEach { (key, value) ->
                    if (inNames) {
                        walk(value, inNames = false)
                    } else {
                        seen += key
                        walk(value, inNames = key in nameSpaces)
                    }
                }
                is kotlinx.serialization.json.JsonArray -> node.forEach { walk(it, inNames = false) }
                else -> Unit
            }
        }
        for (document in ProtocolSchemas.documents) walk(document, inNames = false)
        for (source in listOf(CatalogFixtures.BASIC, CatalogFixtures.TESTING)) {
            walk(Json.parseToJsonElement(source), inNames = false)
        }
        val unhandled = seen.filter { it in JSON_SCHEMA_KEYWORDS }
            .filterNot { it in SUPPORTED_KEYWORDS }
        assertEquals(emptyList(), unhandled)
    }

    @Test
    fun applies_the_keywords_only_the_protocol_schemas_use() {
        // `uniqueItems`, `contains`, `propertyNames` and `pattern` appear in
        // `catalog_definition.json` and nowhere in a catalog, so they have no coverage from the
        // cases above -- and an unexercised keyword in a partial validator reads as acceptance.
        val registry = SchemaRegistry.of(listOf(parseObject(KEYWORD_PROBE_SCHEMA)))
        val evaluator = SchemaEvaluator(registry)
        val at = SchemaLocation("urn:probe", "")
        fun verdict(payload: String): Boolean = evaluator
            .validate(parseObject(KEYWORD_PROBE_SCHEMA), at, Json.parseToJsonElement(payload))
            .isValid

        assertTrue(verdict("""{"tags": ["a", "b"], "names": {"ok": 1}, "version": "1.0.0"}"""))
        assertFalse(verdict("""{"tags": ["a", "a"], "names": {}, "version": "1.0.0"}"""), "uniqueItems")
        assertFalse(verdict("""{"tags": [], "names": {}, "version": "1.0.0"}"""), "contains")
        assertFalse(
            verdict("""{"tags": ["a"], "names": {"Surface": 1}, "version": "1.0.0"}"""),
            "propertyNames",
        )
        // `pattern` is deliberately absent here: a pattern read from a document this library
        // does not ship is agent-supplied, and the evaluator now reports it unapplied rather than
        // handing an unbounded backtracking cost to a phone. It is covered by the two tests below
        // -- applied from the shipped document, refused and reported from a catalog.
    }

    @Test
    fun applies_a_pattern_read_from_a_document_the_library_ships() {
        val document = ProtocolSchemas.catalogDefinition
        val at = SchemaLocation(
            "https://a2ui.org/specification/v1_0/catalog_definition.json",
            "/properties/protocolVersion",
        )
        val schema = document.pointer(at.pointer)!!
        val evaluator = SchemaEvaluator(SchemaRegistry.of(ProtocolSchemas.documents))
        fun verdict(payload: String): SchemaValidation =
            evaluator.validate(schema, at, Json.parseToJsonElement(payload))

        val good = verdict(""""1.0"""")
        assertTrue(good.isValid, good.violations.toString())
        assertEquals(emptySet(), good.unsupportedKeywords)

        // `v1.0` is the envelope's spelling; a catalog says `1.0`, and the pattern is what says so.
        val bad = verdict(""""v1.0"""")
        assertFalse(bad.isValid, "the semver pattern was not applied")
        assertEquals(emptySet(), bad.unsupportedKeywords)
    }

    @Test
    fun does_not_apply_a_pattern_a_catalog_supplied_and_says_so() {
        // Neither length bound constrains backtracking, and the cost lands on Kotlin/Native and JS
        // rather than the JVM -- so the target that hangs is a phone. Refusing is reported through
        // `unsupportedKeywords`, never silently.
        val registry = SchemaRegistry.of(listOf(parseObject(KEYWORD_PROBE_SCHEMA)))
        val result = SchemaEvaluator(registry).validate(
            parseObject(KEYWORD_PROBE_SCHEMA),
            SchemaLocation("urn:probe", ""),
            Json.parseToJsonElement("""{"tags": ["a"], "names": {}, "version": "not-a-semver"}"""),
        )
        assertTrue(result.isValid, "an agent's pattern was compiled")
        assertContains(result.unsupportedKeywords, "pattern")
    }

    @Test
    fun reports_a_pattern_it_will_not_compile_rather_than_judging_it() {
        // A catalog may be inlined by an agent, and a pattern is the one place a schema hands work
        // to a backtracking engine. One that will not compile is not evidence about the payload.
        val schema = """{"type": "string", "pattern": "("}"""
        val registry = SchemaRegistry.of(listOf(parseObject("""{"${'$'}id": "urn:p"}""")))
        val result = SchemaEvaluator(registry).validate(
            parseObject(schema),
            SchemaLocation("urn:p", ""),
            Json.parseToJsonElement("\"anything\""),
        )
        assertTrue(result.isValid)
        assertContains(result.unsupportedKeywords, "pattern")
    }
}

/** Every assertion keyword of JSON Schema 2020-12, to tell one apart from a catalog's own key. */
private val JSON_SCHEMA_KEYWORDS = setOf(
    "type", "enum", "const", "multipleOf", "maximum", "exclusiveMaximum", "minimum",
    "exclusiveMinimum", "maxLength", "minLength", "pattern", "maxItems", "minItems", "uniqueItems",
    "maxContains", "minContains", "maxProperties", "minProperties", "required",
    "dependentRequired", "prefixItems", "items", "contains", "properties", "patternProperties",
    "additionalProperties", "propertyNames", "allOf", "anyOf", "oneOf", "not", "if", "then",
    "else", "dependentSchemas", "unevaluatedItems", "unevaluatedProperties", "format",
    "contentEncoding", "contentMediaType", "contentSchema",
)
