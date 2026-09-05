package dev.ynagai.a2ui.core.protocol

import dev.ynagai.a2ui.core.validation.CatalogFixtures
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The specification's "Catalog Entity Naming Rules", which live in prose and in no schema.
 *
 * Each entity kind is fixed separately. The four -- component name, function name, component
 * property name, function argument name -- are one rule but four sites, and a wiring that reaches
 * three of them passes any test that only asks "is the rule enforced?".
 */
class CatalogEntityNamesTest {
    private val json = A2uiJson.strict

    // --- component names -----------------------------------------------------------------

    @Test
    fun a_component_name_that_is_not_an_identifier_is_refused() {
        listOf("my-component", "1stItem", "My Component", "user#name", "", "calc\$val").forEach {
            assertFailsWith<A2uiFormatException>("`$it` should have been refused") {
                json.decodeFromString<CatalogDefinition>(catalog(component = it))
            }
        }
    }

    @Test
    fun a_component_name_the_tables_admit_is_accepted_whatever_script_it_is_in() {
        listOf("UserProfileCard", "_internal", "item_id_1", "über", "τάξις", "𐐀Card")
            .forEach {
                val decoded = json.decodeFromString<CatalogDefinition>(catalog(component = it))
                assertEquals(setOf(it), decoded.components.keys, "`$it` should have been accepted")
            }
    }

    @Test
    fun the_answer_comes_from_the_derived_tables_rather_than_from_letters_and_digits() {
        // U+037A GREEK YPOGEGRAMMENI is `ID_Start` and a letter by general category, but is
        // excluded from `XID_Start` because NFKC folds it away. Anything built on `Char.isLetter`
        // accepts it. This is the direction that ships non-conformance behind a green run, so it
        // is fixed here and not only in `Uax31Test`.
        assertFailsWith<A2uiFormatException> {
            json.decodeFromString<CatalogDefinition>(catalog(component = "ͺ"))
        }
        // U+0301 COMBINING ACUTE ACCENT is `XID_Continue` and neither a letter nor a digit, so
        // the same approximation refuses it -- in exactly the scripts that need it.
        val decoded = json.decodeFromString<CatalogDefinition>(catalog(component = "áb"))
        assertEquals(setOf("áb"), decoded.components.keys)
    }

    // --- function names and the `@` namespace --------------------------------------------

    @Test
    fun a_function_name_that_is_not_an_identifier_is_refused() {
        listOf("submit-form", "2ndCall", "open url", "").forEach {
            assertFailsWith<A2uiFormatException>("`$it` should have been refused") {
                json.decodeFromString<CatalogDefinition>(catalog(function = it))
            }
        }
    }

    @Test
    fun a_catalog_may_not_define_into_the_system_namespace() {
        // The System Namespace Rule bars all of these, `@index` included. The specification's own
        // harness strips a leading `@` before checking and so accepts `@ping`; that contradicts
        // the canonical regex, the v1.0 changes list, and the rule itself, and no bundled catalog
        // exercises it.
        listOf("@ping", "@index", "@", "@@ping", "@_internal").forEach {
            val failure = assertFailsWith<A2uiFormatException>("`$it` should have been refused") {
                json.decodeFromString<CatalogDefinition>(catalog(function = it))
            }
            assertTrue(
                failure.message.orEmpty().contains("reserved for system functions"),
                "`$it` was refused for the wrong reason: ${failure.message}",
            )
        }
    }

    @Test
    fun the_reserved_prefix_does_not_stop_the_one_system_function_from_being_called() {
        // Barring catalogs from the namespace must not bar the namespace's single inhabitant from
        // being invoked: `@index` is composed in by `common_types.json`, not by a catalog.
        assertTrue(FunctionCall(call = FunctionCall.INDEX).isSystemFunction)
        assertTrue(!FunctionCall(call = "@ping").isSystemFunction)
    }

    // --- property and argument names ------------------------------------------------------

    @Test
    fun a_component_property_name_that_is_not_an_identifier_is_refused() {
        val failure = assertFailsWith<A2uiFormatException> {
            json.decodeFromString<CatalogDefinition>(catalog(componentProperty = "text-value"))
        }
        assertTrue(
            failure.message.orEmpty().contains("property name in component `Text`"),
            "the message does not say where it was: ${failure.message}",
        )
    }

    @Test
    fun a_function_argument_name_that_is_not_an_identifier_is_refused() {
        val failure = assertFailsWith<A2uiFormatException> {
            json.decodeFromString<CatalogDefinition>(catalog(functionArgument = "target-url"))
        }
        assertTrue(
            failure.message.orEmpty().contains("property name in function `openUrl`"),
            "the message does not say where it was: ${failure.message}",
        )
    }

    @Test
    fun a_property_name_is_found_however_deeply_the_definition_composes_it() {
        // A definition may reach its properties through any keyword that holds a subschema, and
        // enumerating them would have to be revised for every keyword the specification adds. So
        // the walk is blind to the path -- these four are the shapes the bundled catalogs use.
        val nested = listOf(
            """{"allOf":[{"properties":{"bad-name":{"type":"string"}}}]}""",
            """{"items":{"properties":{"bad-name":{"type":"string"}}}}""",
            """{"if":{"type":"object"},"then":{"properties":{"bad-name":{"type":"string"}}}}""",
            """{"${'$'}defs":{"Inner":{"properties":{"bad-name":{"type":"string"}}}}}""",
        )
        nested.forEach { body ->
            assertFailsWith<A2uiFormatException>("`$body` should have been refused") {
                json.decodeFromString<CatalogDefinition>(catalogWithComponentBody(body))
            }
        }
    }

    @Test
    fun keys_that_are_not_property_names_are_left_alone() {
        // `$defs` entry names, `patternProperties` regexes and `required` entries are not entity
        // names. Refusing them would reject catalogs that break no rule -- and the ones refused
        // would be third-party catalogs, since nothing bundled here is shaped that way.
        val untouched = listOf(
            """{"${'$'}defs":{"not-an-identifier":{"type":"string"}}}""",
            """{"patternProperties":{"^x-[a-z]+${'$'}":{"type":"string"}}}""",
            """{"properties":{"ok":{"type":"string"}},"required":["ok"],"title":"a-b"}""",
        )
        untouched.forEach { body ->
            val decoded = json.decodeFromString<CatalogDefinition>(catalogWithComponentBody(body))
            assertEquals(setOf("Text"), decoded.components.keys, "`$body` should have been kept")
        }
    }

    @Test
    fun a_literal_value_that_happens_to_hold_a_properties_key_is_not_a_property_name() {
        // `const`, `default`, `enum` and `examples` hold instances, not subschemas. A default
        // value of `{"properties": {"x-y": 1}}` is data that happens to use those two words, and
        // carries no property name at all. The upstream harness recurses into them blindly and
        // would refuse this catalog; this deliberately does not, and no violation can hide there
        // because no subschema is reachable through those keywords.
        listOf("const", "default").forEach { keyword ->
            val body = """{"properties":{"ok":{"$keyword":{"properties":{"x-y":1}}}}}"""
            val decoded = json.decodeFromString<CatalogDefinition>(catalogWithComponentBody(body))
            assertEquals(setOf("Text"), decoded.components.keys, "`$keyword` should be skipped")
        }
        listOf("enum", "examples").forEach { keyword ->
            val body = """{"properties":{"ok":{"$keyword":[{"properties":{"x-y":1}}]}}}"""
            val decoded = json.decodeFromString<CatalogDefinition>(catalogWithComponentBody(body))
            assertEquals(setOf("Text"), decoded.components.keys, "`$keyword` should be skipped")
        }
    }

    @Test
    fun a_property_named_after_an_instance_keyword_still_has_its_subschema_walked() {
        // The carve-out above is about those four words in *keyword* position. A property may be
        // named `default`, and its value is then an ordinary subschema -- so the exemption must
        // not follow the word into a `properties` map. Was: the walk read the map as a schema, so
        // the key `default` matched the exemption and the whole subtree under it went unchecked,
        // which is the one thing the carve-out's rationale claims cannot happen.
        listOf("const", "default", "enum", "examples").forEach { name ->
            val body = """{"properties":{"$name":{"type":"object","properties":{"bad-name":{"type":"string"}}}}}"""
            val failure = assertFailsWith<A2uiFormatException>("a property named `$name` hid its subschema") {
                json.decodeFromString<CatalogDefinition>(catalogWithComponentBody(body))
            }
            assertTrue(
                failure.message.orEmpty().contains("bad-name"),
                "`$name` was refused for the wrong reason: ${failure.message}",
            )
        }
    }

    @Test
    fun a_property_name_reached_only_through_the_catalogs_own_defs_is_checked() {
        // A definition may reach its properties by `$ref` into the catalog's `$defs` instead of
        // declaring them inline, and `schemaKeywords` carries that `$defs` so the reference still
        // resolves. Was: only the definitions were walked, so moving the name one level out was
        // enough to get it past the rule. The upstream harness still accepts this, deliberately.
        val source = """
            {
              "catalogId": "example.com:testing",
              "${'$'}defs": {"Base": {"properties": {"bad-name": {"type": "string"}}}},
              "components": {"Text": {"${'$'}ref": "#/${'$'}defs/Base"}}
            }
        """.trimIndent()
        val failure = assertFailsWith<A2uiFormatException> {
            json.decodeFromString<CatalogDefinition>(source)
        }
        assertTrue(
            failure.message.orEmpty().contains("bad-name"),
            "refused for the wrong reason: ${failure.message}",
        )
        // The `$defs` entry names are not themselves entity names, exactly as inside a definition.
        val named = """
            {
              "catalogId": "example.com:testing",
              "${'$'}defs": {"not-an-identifier": {"properties": {"ok": {"type": "string"}}}},
              "components": {"Text": {"type": "object"}}
            }
        """.trimIndent()
        assertEquals(
            setOf("Text"),
            json.decodeFromString<CatalogDefinition>(named).components.keys,
        )
    }

    @Test
    fun an_entry_named_after_an_instance_keyword_is_walked_in_every_name_map() {
        // `properties` is not the only map whose keys are names their author chose. A `$defs`
        // entry, a `patternProperties` branch or a `dependencies` trigger may be named `default`
        // too, and the carve-out must not follow the word into any of them. Was: fixing this for
        // `properties` alone left the same false negative one keyword to the side.
        //
        // Every map is listed, not a sample of them. The test that stood here walked two of the
        // five and stayed green while `dependencies` -- absent from the set entirely -- hid a
        // name; a list that samples cannot report the member that is missing.
        NAME_MAPS.forEach { keyword ->
            listOf("const", "default", "enum", "examples").forEach { name ->
                val body =
                    """{"$keyword":{"$name":{"properties":{"bad-name":{"type":"string"}}}}}"""
                val failure = assertFailsWith<A2uiFormatException>(
                    "a `$keyword` entry named `$name` hid its subschema",
                ) {
                    json.decodeFromString<CatalogDefinition>(catalogWithComponentBody(body))
                }
                // Which name it refused, not merely that it refused: a future change that ran a
                // `patternProperties` regex through the identifier check would throw here too,
                // and this test would pass while the false negative it guards was back.
                assertTrue(
                    failure.message.orEmpty().contains("bad-name"),
                    "`$keyword`/`$name` was refused for the wrong reason: ${failure.message}",
                )
            }
        }
    }

    @Test
    fun an_entry_named_after_a_schema_keyword_is_a_name_in_every_name_map() {
        // The opposite direction, and the same list. An author may call a `$defs` entry or a
        // `dependencies` trigger `properties`; what sits under it is that entry's subschema, and
        // its keywords are keywords. Was: `dependencies` was read as a schema, so the entry name
        // `properties` was taken for the keyword and `$ref` beneath it run through the identifier
        // check -- refusing a catalog that breaks no rule.
        NAME_MAPS.forEach { keyword ->
            val body = """{"$keyword":{"properties":{"${'$'}ref":"#/${'$'}defs/S"}}}"""
            val decoded = json.decodeFromString<CatalogDefinition>(catalogWithComponentBody(body))
            assertEquals(setOf("Text"), decoded.components.keys, "`$keyword` should have been kept")
        }
    }

    @Test
    fun an_entry_name_in_a_name_map_is_not_an_entity_name_in_any_of_them() {
        // The negative half of the pair, and it has to walk the same whole list. Only `properties`
        // holds names the rule governs; a `$defs` entry name, a `patternProperties` regex and a
        // `dependencies` trigger are none of them, and refusing one would reject a catalog that
        // breaks no rule. Was: `keys_that_are_not_property_names_are_left_alone` sampled two of
        // the maps, so the mutation `if (key == PROPERTIES || key == "dependencies")` left the
        // suite green while `{"dependencies": {"x-legacy": …}}` was newly refused.
        NAME_MAPS.forEach { keyword ->
            val body = """{"$keyword":{"x-legacy":{"type":"string"}}}"""
            val decoded = json.decodeFromString<CatalogDefinition>(catalogWithComponentBody(body))
            assertEquals(
                setOf("Text"),
                decoded.components.keys,
                "a `$keyword` entry name is not an entity name and should have been kept",
            )
        }
    }

    @Test
    fun a_draft_07_dependencies_entry_may_hold_required_names_rather_than_a_subschema() {
        // `dependencies` is the one name map whose entry is not always a schema: draft-07 lets it
        // hold an array of property names instead. The walk bottoms out on the strings in it, as
        // on any other array, so admitting the keyword must not start refusing that form.
        val body = """{"type":"object","dependencies":{"a":["b","c"]}}"""
        val decoded = json.decodeFromString<CatalogDefinition>(catalogWithComponentBody(body))
        assertEquals(setOf("Text"), decoded.components.keys)
    }

    @Test
    fun a_schema_keyword_the_catalog_carries_is_walked_whatever_shape_it_arrived_in() {
        // `CatalogDefinitionSerializer` selects the carried keywords by key name and never by
        // shape -- `rejectUnknownKeys` checks names alone -- so `$id` and `$schema`, which JSON
        // Schema says are strings, may in fact arrive holding an object, and `$defs` may arrive
        // holding an array. A `$ref` is a JSON pointer and reaches any of them.
        //
        // Was: only `$defs` was read, and only where it had been an object, so all three of those
        // regions were unwalked and `$ref`-reachable. Moving the name from `$defs` to `$id`, or
        // wrapping it in a one-element array, was enough to get it past the rule.
        //
        // Both shapes are crossed against all three keywords rather than tested on the diagonal:
        // with only `$id`-as-object and `$defs`-as-array listed, a walk that read objects
        // everywhere but arrays under `$defs` alone would pass, and `{"$id": [ … ]}` would still
        // slip through.
        listOf(
            """"${'$'}id": {"properties": {"bad-name": {"type": "string"}}}""" to "#/${'$'}id",
            """"${'$'}schema": {"properties": {"bad-name": {"type": "string"}}}""" to "#/${'$'}schema",
            """"${'$'}defs": {"B": {"properties": {"bad-name": {"type": "string"}}}}""" to "#/${'$'}defs/B",
            """"${'$'}id": [{"properties": {"bad-name": {"type": "string"}}}]""" to "#/${'$'}id/0",
            """"${'$'}schema": [{"properties": {"bad-name": {"type": "string"}}}]""" to "#/${'$'}schema/0",
            """"${'$'}defs": [{"properties": {"bad-name": {"type": "string"}}}]""" to "#/${'$'}defs/0",
        ).forEach { (carried, reference) ->
            val source = """
                {
                  "catalogId": "example.com:testing",
                  $carried,
                  "components": {"Text": {"${'$'}ref": "$reference"}}
                }
            """.trimIndent()
            val failure = assertFailsWith<A2uiFormatException>("`$carried` went unwalked") {
                json.decodeFromString<CatalogDefinition>(source)
            }
            assertTrue(
                failure.message.orEmpty().contains("bad-name"),
                "`$carried` was refused for the wrong reason: ${failure.message}",
            )
        }
    }

    @Test
    fun the_shapes_those_keywords_normally_hold_are_left_alone() {
        // Walking the carried keywords uniformly must cost nothing on the strings they hold when
        // a catalog is written the way JSON Schema says to write it; the walk bottoms out on a
        // primitive. Without this, the test above would pass just as well for a check that
        // refused every catalog carrying an `$id`.
        val source = """
            {
              "catalogId": "example.com:testing",
              "${'$'}id": "https://example.com/catalog.json",
              "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
              "${'$'}defs": {"Base": {"properties": {"ok": {"type": "string"}}}},
              "components": {"Text": {"${'$'}ref": "#/${'$'}defs/Base"}}
            }
        """.trimIndent()
        assertEquals(
            setOf("Text"),
            json.decodeFromString<CatalogDefinition>(source).components.keys,
        )
    }

    @Test
    fun a_property_named_after_a_schema_keyword_is_a_name_and_not_that_keyword() {
        // The same confusion in the opposite direction. A component may declare a property called
        // `properties`; its subschema's keywords are keywords, not entity names. Was: the walk
        // re-read the map as a schema, saw `properties` a second time, and ran `$ref` and
        // `x-vendor` through the identifier check -- refusing a catalog that breaks no rule and
        // that the specification's own harness accepts.
        listOf(
            """{"type":"object","properties":{"properties":{"${'$'}ref":"#/${'$'}defs/S"}}}""",
            """{"type":"object","properties":{"properties":{"type":"object","x-vendor":1}}}""",
        ).forEach { body ->
            val decoded = json.decodeFromString<CatalogDefinition>(catalogWithComponentBody(body))
            assertEquals(setOf("Text"), decoded.components.keys, "`$body` should have been kept")
        }
    }

    @Test
    fun a_name_too_long_to_quote_is_truncated_in_the_message() {
        // The excerpt exists because an inlined catalog's keys are agent-chosen and reach a
        // renderer's log through this message. Nothing else in the suite is long enough to notice
        // if the truncation were dropped.
        val name = "x".repeat(200) + "-not-an-identifier"
        val failure = assertFailsWith<A2uiFormatException> {
            json.decodeFromString<CatalogDefinition>(catalog(component = name))
        }
        assertTrue(
            !failure.message.orEmpty().contains(name),
            "the whole name was quoted rather than an excerpt: ${failure.message}",
        )
    }

    // --- every way a catalog reaches a checker ---------------------------------------------

    @Test
    fun a_catalog_inlined_in_renderer_capabilities_is_checked_too() {
        val source = """
            {"v1.0":{"supportedCatalogIds":["example.com:testing"],"inlineCatalogs":[
              ${catalog(component = "my-component")}
            ]}}
        """.trimIndent()
        assertFailsWith<A2uiFormatException> {
            json.decodeFromString<RendererCapabilities>(source)
        }
    }

    @Test
    fun a_catalog_built_in_kotlin_is_checked_rather_than_only_a_decoded_one() {
        // `CatalogValidator.of` and `CompositionValidator` both take `List<CatalogDefinition>`
        // directly, so a check that lived in the serializer would leave both reachable with names
        // no wire catalog could carry. This is why the rule is an `init` invariant.
        assertFailsWith<A2uiFormatException> {
            CatalogDefinition(
                catalogId = "example.com:testing",
                components = mapOf("my-component" to ComponentDefinition(schema = buildJsonObject {})),
            )
        }
        assertFailsWith<A2uiFormatException> {
            CatalogDefinition(
                catalogId = "example.com:testing",
                components = mapOf(
                    "Text" to ComponentDefinition(
                        schema = buildJsonObject {
                            put("properties", buildJsonObject { put("bad-name", buildJsonObject {}) })
                        },
                    ),
                ),
            )
        }
    }

    @Test
    fun the_reserved_container_is_refused_on_the_path_the_schema_does_not_watch() {
        // Rule 4 of the same section. `catalog_definition.json` encodes this one, so the decode
        // path was already covered by `SchemaConformanceTest`; a definition built in Kotlin never
        // meets the schema, and until the rules moved together it was allowed to redefine the
        // surface's implicit root.
        assertFailsWith<A2uiFormatException> {
            CatalogDefinition(
                catalogId = "example.com:testing",
                components = mapOf(
                    Surface.COMPONENT to ComponentDefinition(schema = buildJsonObject {}),
                ),
            )
        }
    }

    @Test
    fun copying_a_decoded_catalog_into_an_invalid_one_is_refused() {
        val decoded = json.decodeFromString<CatalogDefinition>(catalog())
        assertFailsWith<A2uiFormatException> {
            decoded.copy(components = decoded.components.mapKeys { "1st" })
        }
    }

    // --- shape of the walk ------------------------------------------------------------------

    @Test
    fun a_deeply_nested_definition_is_walked_without_growing_the_call_stack() {
        // Kotlin/Native aborts the process on stack overflow rather than raising something a
        // caller could catch, and an inlined catalog's definitions are as deep as their author
        // chose. Depth that a recursive walk would not survive, with the violation at the bottom
        // so that reaching it is what the assertion proves.
        var body: JsonObject = buildJsonObject {
            put("properties", buildJsonObject { put("bad-name", buildJsonObject {}) })
        }
        repeat(10_000) {
            val inner = body
            body = buildJsonObject { put("allOf", buildJsonArray { add(inner) }) }
        }
        assertFailsWith<A2uiFormatException> {
            CatalogDefinition(
                catalogId = "example.com:testing",
                components = mapOf("Text" to ComponentDefinition(schema = body)),
            )
        }
    }

    @Test
    fun the_catalogs_the_specification_ships_satisfy_the_rule_it_states() {
        // The rule is stated in prose and enforced by no schema, so nothing else in this suite
        // would report it if a bump brought in a catalog the specification's own harness refuses.
        listOf("basic" to CatalogFixtures.BASIC, "testing" to CatalogFixtures.TESTING)
            .forEach { (name, source) ->
                val decoded = json.decodeFromString<CatalogDefinition>(source)
                assertTrue(decoded.components.isNotEmpty(), "$name defines no components")
                assertTrue(decoded.functions.isNotEmpty(), "$name defines no functions")
            }
    }

    // --- fixtures ---------------------------------------------------------------------------

    /**
     * The keywords whose keys are names their author chose, minus `properties` itself.
     *
     * `properties` has its own pair of tests above, because its keys are the only ones the naming
     * rule governs. The rest are here to be walked as the schemas they are.
     *
     * Derived from [SCHEMA_MAPS] rather than retyped, so the list cannot drift from the set it is
     * meant to cover. The bug that shipped was a *missing* member; a hand-written list reports a
     * member deleted from the set and stays green on one added to it, which is the same blind spot
     * one keyword to the side.
     */
    private val NAME_MAPS = (SCHEMA_MAPS - PROPERTIES).toList()

    private fun catalog(
        component: String = "Text",
        componentProperty: String = "text",
        function: String = "openUrl",
        functionArgument: String = "url",
    ): String = """
        {
          "catalogId": "example.com:testing",
          "protocolVersion": "1.0",
          "components": {
            "$component": {
              "type": "object",
              "properties": {"$componentProperty": {"type": "string"}}
            }
          },
          "functions": {
            "$function": {
              "type": "object",
              "properties": {
                "call": {"const": "$function"},
                "args": {"type": "object", "properties": {"$functionArgument": {"type": "string"}}}
              },
              "returnType": "void"
            }
          }
        }
    """.trimIndent()

    private fun catalogWithComponentBody(body: String): String = """
        {
          "catalogId": "example.com:testing",
          "components": {"Text": $body}
        }
    """.trimIndent()
}
