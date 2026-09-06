package dev.ynagai.a2ui.core.protocol

import dev.ynagai.a2ui.core.validation.CatalogFixtures
import dev.ynagai.a2ui.core.validation.pointer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun the_closed_set_of_name_maps_is_pinned_and_not_merely_iterated() {
        // [NAME_MAPS] is derived from [SCHEMA_MAPS], so a keyword added to the set is walked by
        // the tests above without anyone remembering to widen a list. Derivation alone, though,
        // makes those tests agree with whatever the set happens to say: delete `dependencies`
        // from it and they shrink to four and stay green, which is the bug this PR fixed. This
        // is the half that notices a deletion. The set is closed, so changing it is a decision
        // and should have to be made twice.
        assertEquals(
            setOf(
                "properties",
                "patternProperties",
                "${'$'}defs",
                "definitions",
                "dependentSchemas",
                "dependencies",
            ),
            SCHEMA_MAPS,
            "the name maps are a closed set; adding or dropping one is a decision, not an edit",
        )
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
    fun a_carried_keyword_that_is_not_the_shape_it_must_be_is_refused() {
        // `CatalogDefinitionSerializer` selects the carried keywords by key name and never by
        // shape -- `rejectUnknownKeys` checks names alone -- so `$id` and `$schema`, which
        // `catalog_definition.json` types as strings, may arrive holding an object or an array,
        // and `$defs` may arrive holding an array.
        //
        // Was: only `$defs` was read, and only where it had been an object, so all three regions
        // went unwalked and `$ref`-reachable. Then they were walked whatever shape they held,
        // which reached the names but left the shapes standing. Now the shape itself is refused:
        // a region that is not the thing it claims to be has no business in the catalog, and
        // saying so once beats reporting whatever happened to be found inside it.
        listOf(
            """"${'$'}id": {"properties": {"bad-name": {}}}""",
            """"${'$'}id": [{"properties": {"bad-name": {}}}]""",
            """"${'$'}schema": {"properties": {"bad-name": {}}}""",
            """"${'$'}schema": [{"properties": {"bad-name": {}}}]""",
        ).forEach { carried ->
            val failure = assertFailsWith<A2uiFormatException>("`$carried` was accepted") {
                json.decodeFromString<CatalogDefinition>(catalogCarrying(carried))
            }
            assertTrue(
                failure.message.orEmpty().contains("must be a string"),
                "`$carried` was refused for the wrong reason: ${failure.message}",
            )
        }
        val failure = assertFailsWith<A2uiFormatException> {
            json.decodeFromString<CatalogDefinition>(
                catalogCarrying(""""${'$'}defs": [{"properties": {"bad-name": {}}}]"""),
            )
        }
        assertTrue(
            failure.message.orEmpty().contains("must be an object"),
            "an array-valued `${'$'}defs` was refused for the wrong reason: ${failure.message}",
        )
    }

    @Test
    fun a_reference_may_name_a_top_level_definition_and_nothing_inside_one() {
        // The restriction that lets the walk decline to enter a region: what is not a schema
        // position cannot be turned into one by a pointer. `#/components/Text` names a schema
        // this walk checked; `#/components/Text/metadata/...` names a region it did not.
        listOf(
            "#/components/Text",
            "#/functions/openUrl",
            "#/${'$'}defs/anyComponent",
            "catalog.json#/${'$'}defs/anyComponent",
            "common_types.json#/${'$'}defs/DynamicString",
            "https://a2ui.org/specification/v1_0/common_types.json#/${'$'}defs/Action",
        ).forEach { reference ->
            val body = """{"type":"object","allOf":[{"${'$'}ref":"$reference"}]}"""
            val decoded = json.decodeFromString<CatalogDefinition>(catalogWithComponentBody(body))
            assertEquals(setOf("Text"), decoded.components.keys, "`$reference` should be permitted")
        }
        listOf(
            "#/components/Text/metadata/extensions/vendor",
            "#/components/Text/properties/ok",
            "#/${'$'}defs/anyComponent/properties/ok",
            "#/x",
            "https://evil.example/schema.json#/${'$'}defs/A",
            "common_types.json#/${'$'}defs/A/B",
        ).forEach { reference ->
            val body = """{"type":"object","allOf":[{"${'$'}ref":"$reference"}]}"""
            assertFailsWith<A2uiFormatException>("`$reference` should have been refused") {
                json.decodeFromString<CatalogDefinition>(catalogWithComponentBody(body))
            }
        }
        // A `$ref` that is not a string at all, which is a separate branch from a target that
        // names the wrong thing: the `keyword == REF` arm consumes the value, so a shape that
        // slipped past would be neither walked nor refused -- `bad-name` below would be a live
        // property of a catalog that loaded. Every other `$ref` in this suite is a string, so
        // without these three the branch is exercised by nothing and `?: return` ships green.
        val shapes = listOf("""{"properties":{"bad-name":{}}}""", "1", """["#/components/Text"]""")
        shapes.forEach { ref ->
            val body = """{"type":"object","allOf":[{"${'$'}ref":$ref}]}"""
            val failure = assertFailsWith<A2uiFormatException>("`$ref` should have been refused") {
                json.decodeFromString<CatalogDefinition>(catalogWithComponentBody(body))
            }
            assertTrue(
                failure.message.orEmpty().contains("must be a string"),
                "`$ref` was refused for the wrong reason: ${failure.message}",
            )
        }
    }

    @Test
    fun a_region_the_walk_does_not_enter_cannot_be_reached_by_a_pointer() {
        // The two halves are only correct together. Declining to walk vendor data is what stops a
        // catalog being refused for the JSON a vendor put in its own extension block; the
        // reference restriction is what stops that same region being aimed at and evaluated as a
        // schema. Was, with a blind walk and an unrestricted pointer: an entry named `default`
        // under an unknown keyword hid its subtree from the walk, and a `$ref` then handed the
        // subtree to the evaluator, so `bad-name` was a live property of a catalog that loaded.
        val body = """{"x-shared":{"default":{"properties":{"bad-name":{}}}},""" +
            """"allOf":[{"${'$'}ref":"#/components/Text/x-shared/default"}]}"""
        val failure = assertFailsWith<A2uiFormatException>(
            "a pointer into an unwalked region was permitted",
        ) {
            json.decodeFromString<CatalogDefinition>(catalogWithComponentBody(body))
        }
        // Which of the two halves refused it matters. Asserting only the exception type would
        // keep this test green if the walk were re-blinded: `bad-name` would then be reached
        // again and refused by the identifier check, while the reference restriction this test
        // exists to guard had gone.
        assertTrue(
            failure.message.orEmpty().contains("not a permitted"),
            "refused by the walk rather than by the reference rule: ${failure.message}",
        )
    }

    @Test
    fun a_reference_may_name_this_catalogs_own_document_and_a_definitions_own_defs() {
        // Two spellings that name exactly what `#/…` names, and were refused for their address
        // rather than their target. Both are permitted because the walk entered the region:
        // a definition-local `$defs` is one of `SCHEMA_MAPS`, and a catalog's own `$id` or
        // `catalogId` is the name `SchemaRegistry` registers it under, so the pointer resolves to
        // the same subschema either way.
        val absolute = "https://example.com/c.json#/components/Text"
        val ownDocument = """
            {
              "catalogId": "example.com:testing",
              "${'$'}id": "https://example.com/c.json",
              "components": {
                "Text": {"type": "object"},
                "Box": {"type":"object","allOf":[{"${'$'}ref":"$absolute"}]}
              }
            }
        """.trimIndent()
        assertEquals(
            setOf("Text", "Box"),
            json.decodeFromString<CatalogDefinition>(ownDocument).components.keys,
        )
        val byCatalogId = ownDocument.replace(absolute, "example.com:testing#/components/Text")
        assertEquals(
            setOf("Text", "Box"),
            json.decodeFromString<CatalogDefinition>(byCatalogId).components.keys,
        )
        // Rule 2 bars the catalog-level `$defs` from holding shared helpers, which leaves a
        // definition-local one the only place for them.
        val localDefs = """{"type":"object","${'$'}defs":{"Pad":{"type":"string"}},""" +
            """"properties":{"padding":{"${'$'}ref":"#/components/Text/${'$'}defs/Pad"}}}"""
        val withLocalDefs = json.decodeFromString<CatalogDefinition>(
            catalogWithComponentBody(localDefs),
        )
        assertEquals(setOf("Text"), withLocalDefs.components.keys)
        // The widening is one step and only under `$defs`. Naming another document's definition,
        // or any other second step, stays refused -- otherwise the depth rule buys nothing.
        listOf(
            "#/components/Text/properties/ok",
            "#/components/Text/${'$'}defs/Pad/properties/ok",
            "https://other.example/c.json#/components/Text",
        ).forEach { reference ->
            val body = """{"type":"object","allOf":[{"${'$'}ref":"$reference"}]}"""
            assertFailsWith<A2uiFormatException>("`$reference` should have been refused") {
                json.decodeFromString<CatalogDefinition>(catalogWithComponentBody(body))
            }
        }
    }

    @Test
    fun an_escaped_pointer_segment_does_not_buy_a_step() {
        // The depth restriction counts `/`-separated segments in the reference text, while
        // `SchemaRegistry` resolves the fragment as a JSON Pointer -- so the two only agree
        // while `pointer` splits on `/` BEFORE decoding `~1`. In that order `~1` produces a
        // literal `/` inside one already-tokenised step, and `#/components/Text~1metadata` is a
        // lookup for a component *named* `Text/metadata`, which `requireIdentifier` can never
        // admit. Decode-then-split would make the same string five steps and hand a `$ref` the
        // vendor region the walk deliberately skips.
        //
        // The reference is permitted here on purpose: it is `[^/]+` and names nothing, and that
        // is the whole point. What this pins is the other half -- that it resolves to null --
        // which lives in `SchemaRegistry.kt`, where nothing else would fail if the order changed.
        val escaped = "#/components/Text~1metadata~1extensions~1vendor"
        val vendor = """"metadata":{"extensions":{"vendor":{"properties":{"bad-name":{}}}}}"""
        val body = """{"type":"object",$vendor,"allOf":[{"${'$'}ref":"$escaped"}]}"""
        val source = catalogWithComponentBody(body)
        val decoded = json.decodeFromString<CatalogDefinition>(source)
        val document = json.parseToJsonElement(source) as JsonObject
        assertEquals(setOf("Text"), decoded.components.keys)
        assertNull(
            document.pointer(escaped.removePrefix("#")),
            "an escaped segment reached a region the walk does not enter",
        )
        // The control, without which the assertion above passes for a pointer that simply names
        // nothing: the same region IS reachable when the steps are written unescaped -- and that
        // spelling is the one `requirePermittedReference` refuses.
        assertNotNull(document.pointer("/components/Text/metadata/extensions/vendor"))
    }

    @Test
    fun the_vendor_json_a_component_carries_is_data_and_is_not_read_as_a_schema() {
        // `ComponentDefinitionSerializer` sets `schema` to the whole component object, so
        // `metadata` -- whose `extensions` hold arbitrary vendor JSON -- used to be walked as
        // though it were a schema. A vendor payload that happened to contain the word
        // `properties` and a hyphenated key then refused a catalog that breaks no rule.
        listOf(
            """{"type":"object","metadata":{"extensions":{"v":{"properties":{"bad-name":1}}}}}""",
            """{"type":"object","x-vendor":{"properties":{"bad-name":1}}}""",
            """{"type":"object","x-vendor":{"default":{"properties":{"bad-name":1}}}}""",
        ).forEach { body ->
            val decoded = json.decodeFromString<CatalogDefinition>(catalogWithComponentBody(body))
            assertEquals(setOf("Text"), decoded.components.keys, "`$body` should have been kept")
        }
    }

    @Test
    fun a_property_name_is_checked_in_every_position_a_subschema_lives() {
        // The walk descends by an enumeration now, so a keyword missing from it is a position no
        // rule is applied to. The enumeration is iterated rather than sampled, and pinned below,
        // for the reason the name maps are: a member no test exercises is a member that can be
        // dropped without the suite noticing.
        SUBSCHEMA.forEach { keyword ->
            val body = """{"type":"object","$keyword":{"properties":{"bad-name":{}}}}"""
            val failure = assertFailsWith<A2uiFormatException>("`$keyword` was not walked") {
                json.decodeFromString<CatalogDefinition>(catalogWithComponentBody(body))
            }
            assertTrue(
                failure.message.orEmpty().contains("bad-name"),
                "`$keyword` was refused for the wrong reason: ${failure.message}",
            )
        }
        (SUBSCHEMA_LIST + ITEMS).forEach { keyword ->
            val body = """{"type":"object","$keyword":[{"properties":{"bad-name":{}}}]}"""
            val failure = assertFailsWith<A2uiFormatException>("`$keyword` was not walked") {
                json.decodeFromString<CatalogDefinition>(catalogWithComponentBody(body))
            }
            assertTrue(
                failure.message.orEmpty().contains("bad-name"),
                "`$keyword` was refused for the wrong reason: ${failure.message}",
            )
        }
        // `items` carries both forms: 2020-12's single schema, and draft-07's tuple array above.
        assertFailsWith<A2uiFormatException>("the 2020-12 form of `items` was not walked") {
            json.decodeFromString<CatalogDefinition>(
                catalogWithComponentBody("""{"items":{"properties":{"bad-name":{}}}}"""),
            )
        }
    }

    @Test
    fun the_subschema_positions_are_pinned_and_not_merely_iterated() {
        // The other half of the pair above, for the reason
        // [the_closed_set_of_name_maps_is_pinned_and_not_merely_iterated] gives: a derived list
        // shrinks with the set it derives from, so a position dropped from the walk drops out of
        // the test that guards it. Unlike the name maps this set is *not* closed -- a later draft
        // may add a position -- so widening it is expected; doing so silently is not.
        assertEquals(
            setOf(
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
            ),
            SUBSCHEMA,
            "a position added or dropped here changes what the naming rule reaches",
        )
        assertEquals(setOf("allOf", "anyOf", "oneOf", "prefixItems"), SUBSCHEMA_LIST)
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
     * Derived from [SCHEMA_MAPS] rather than retyped, so a keyword added to the set is covered
     * here without anyone remembering to widen a list. That is one direction only: the derived
     * list shrinks with the set, so a keyword *dropped* from it silently drops out of these tests
     * too. [the_closed_set_of_name_maps_is_pinned_and_not_merely_iterated] is the other direction,
     * and the two are only a guard together.
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

    private fun catalogCarrying(carried: String): String = """
        {
          "catalogId": "example.com:testing",
          $carried,
          "components": {"Text": {"type": "object"}}
        }
    """.trimIndent()

    private fun catalogWithComponentBody(body: String): String = """
        {
          "catalogId": "example.com:testing",
          "components": {"Text": $body}
        }
    """.trimIndent()
}
