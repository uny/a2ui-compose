package dev.ynagai.a2ui.core.validation

import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.CatalogDefinition
import dev.ynagai.a2ui.core.protocol.Component
import dev.ynagai.a2ui.core.surface.A2uiStateException
import dev.ynagai.a2ui.core.surface.ChildReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val CATALOG: CatalogDefinition =
    A2uiJson.strict.decodeFromString(CatalogDefinition.serializer(), CatalogFixtures.BASIC)

private val RESOLVER = CatalogChildResolver.of(listOf(CATALOG), surfaceDefault = CATALOG.catalogId)

private fun children(json: String): List<ChildReference> =
    RESOLVER.childrenOf(A2uiJson.strict.decodeFromString(Component.serializer(), json))

/**
 * The resolver against the published basic catalog.
 *
 * Every property name below is one the catalog chose, not one the protocol reserves — which is the
 * whole reason the resolver reads the catalog. A hard-coded `child`/`children` pair passes the
 * first two cases here and silently drops the rest.
 */
class CatalogChildResolverTest {
    @Test
    fun finds_a_single_child_under_the_name_the_catalog_chose() {
        assertEquals(
            listOf(ChildReference.Single("child", "t1")),
            children("""{"id": "c", "component": "Card", "child": "t1"}"""),
        )
    }

    @Test
    fun finds_a_fixed_list_of_children() {
        assertEquals(
            listOf(ChildReference.Fixed("children", listOf("a", "b"))),
            children("""{"id": "col", "component": "Column", "children": ["a", "b"]}"""),
        )
    }

    @Test
    fun finds_a_template_child_list() {
        val found = children(
            """{"id": "l", "component": "List", "children": {"componentId": "row", "path": "/items"}}""",
        )
        val template = found.single() as ChildReference.Template
        assertEquals("children", template.property)
        assertEquals("row", template.componentId)
    }

    @Test
    fun finds_both_children_of_a_modal() {
        // `Modal` is the case a hard-coded resolver gets wrong without noticing: neither of its
        // two child properties is called `child`.
        val found = children(
            """{"id": "m", "component": "Modal", "entryPoint": "b1", "contentId": "c1"}""",
        ).ifEmpty {
            children("""{"id": "m", "component": "Modal", "trigger": "b1", "content": "c1"}""")
        }
        assertEquals(2, found.size, found.toString())
        assertTrue(found.all { it is ChildReference.Single }, found.toString())
    }

    @Test
    fun finds_a_child_nested_inside_an_array_of_objects() {
        // `Tabs.tabs[].child` -- the reference is two levels below the component, one of them an
        // array index, and the schema that says so is reached through `items`.
        val found = children(
            """
            {"id": "t", "component": "Tabs", "tabs": [
              {"title": "one", "child": "c1"},
              {"title": "two", "child": "c2"}
            ]}
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                ChildReference.Single("tabs/0/child", "c1"),
                ChildReference.Single("tabs/1/child", "c2"),
            ),
            found,
        )
    }

    @Test
    fun reports_nothing_for_a_component_with_no_children() {
        assertEquals(emptyList(), children("""{"id": "t", "component": "Text", "text": "hi"}"""))
        assertEquals(emptyList(), children("""{"id": "d", "component": "Divider"}"""))
    }

    @Test
    fun reports_nothing_for_a_component_type_the_catalog_does_not_define() {
        assertEquals(emptyList(), children("""{"id": "x", "component": "Nope", "child": "a"}"""))
    }

    @Test
    fun reports_nothing_when_the_component_names_a_catalog_this_resolver_does_not_hold() {
        // The walk this feeds draws a surface that is still arriving, and the specification
        // requires it to render placeholders rather than to stop. Saying so is the checker's job.
        assertEquals(
            emptyList(),
            children("""{"id": "c", "component": "Card", "catalogId": "urn:nope", "child": "t1"}"""),
        )
    }

    @Test
    fun ignores_a_child_list_whose_entries_are_not_all_ids() {
        // Dropping the malformed entry alone would shift every child after it into the wrong slot,
        // which draws a wrong UI without complaining. The checker reports the list; this skips it.
        assertEquals(
            emptyList(),
            children("""{"id": "col", "component": "Column", "children": ["a", 7]}"""),
        )
    }

    @Test
    fun a_long_child_list_is_one_reference_however_long_the_agent_made_it() {
        val ids = (0 until CatalogChildResolver.MAX_REFERENCES + 10).joinToString(",") { "\"c$it\"" }
        val found = children("""{"id": "col", "component": "Column", "children": [$ids]}""")
        // The bound counts references, and a list is one of them whatever its length -- so this
        // is not what the bound is for. `Fixed` carries the ids.
        assertEquals(1, found.size)
        assertEquals(CatalogChildResolver.MAX_REFERENCES + 10, (found.single() as ChildReference.Fixed).ids.size)
    }

    @Test
    fun refuses_a_component_past_the_reference_bound_rather_than_shortening_it() {
        // `Tabs` yields one reference per tab, so the count is the agent's to choose. Returning
        // the first few thousand would draw a tab strip with tabs missing and say nothing, which
        // is the failure `walk` already refuses for the same reason.
        val tabs = (0 until CatalogChildResolver.MAX_REFERENCES + 50)
            .joinToString(",") { """{"title": "t$it", "child": "c$it"}""" }
        assertFailsWith<A2uiStateException> {
            children("""{"id": "t", "component": "Tabs", "tabs": [$tabs]}""")
        }
    }

    @Test
    fun refuses_a_component_whose_schema_outgrows_the_step_budget() {
        val tabs = (0 until 200).joinToString(",") { """{"title": "t$it", "child": "c$it"}""" }
        val tight = CatalogChildResolver.of(
            listOf(CATALOG),
            surfaceDefault = CATALOG.catalogId,
            limits = ValidationLimits(maxSteps = 50),
        )
        assertFailsWith<A2uiStateException> {
            tight.childrenOf(
                A2uiJson.strict.decodeFromString(
                    Component.serializer(),
                    """{"id": "t", "component": "Tabs", "tabs": [$tabs]}""",
                ),
            )
        }
    }
    @Test
    fun finds_children_under_an_else_branch_and_in_a_map_shaped_slot() {
        // Neither shape appears in the published basic catalog, and both are legal. A child the
        // resolver does not find is a container drawn without it and nothing said -- and
        // `CompositionValidator` never sees the edge either, so its rules go unchecked too.
        val source = """
        {
          "${'$'}id": "urn:test:shapes",
          "catalogId": "urn:test:shapes",
          "components": {
            "Panel": {
              "type": "object",
              "properties": {
                "component": {"const": "Panel"},
                "slots": {
                  "type": "object",
                  "additionalProperties": {
                    "${'$'}ref": "https://a2ui.org/specification/v1_0/common_types.json#/${'$'}defs/Child"
                  }
                }
              },
              "if": {"properties": {"mode": {"const": "a"}}},
              "then": {
                "properties": {
                  "primary": {
                    "${'$'}ref": "https://a2ui.org/specification/v1_0/common_types.json#/${'$'}defs/Child"
                  }
                }
              },
              "else": {
                "properties": {
                  "fallback": {
                    "${'$'}ref": "https://a2ui.org/specification/v1_0/common_types.json#/${'$'}defs/Child"
                  }
                }
              }
            }
          }
        }
        """.trimIndent()
        val catalog = A2uiJson.strict.decodeFromString(CatalogDefinition.serializer(), source)
        val resolver = CatalogChildResolver.of(listOf(catalog), surfaceDefault = catalog.catalogId)
        val found = resolver.childrenOf(
            A2uiJson.strict.decodeFromString(
                Component.serializer(),
                """{"id": "p", "component": "Panel", "mode": "b", "fallback": "c1",
                    "slots": {"header": "c2"}}""",
            ),
        )
        assertEquals(
            setOf(
                ChildReference.Single("fallback", "c1"),
                ChildReference.Single("slots/header", "c2"),
            ),
            found.toSet(),
            found.toString(),
        )
    }

    @Test
    fun does_not_invent_a_child_for_a_name_pattern_properties_covers() {
        // `additionalProperties` applies only to what neither `properties` nor `patternProperties`
        // covers. An invented edge is worse than a missing one: `CompositionValidator` would then
        // report UNALLOWED_CHILD for a pairing the catalog never declared.
        val source = """
        {
          "${'$'}id": "urn:test:patterned",
          "catalogId": "urn:test:patterned",
          "components": {
            "Panel": {
              "type": "object",
              "properties": {"component": {"const": "Panel"}},
              "patternProperties": {"^data_": {"type": "string"}},
              "additionalProperties": {
                "${'$'}ref": "https://a2ui.org/specification/v1_0/common_types.json#/${'$'}defs/Child"
              }
            }
          }
        }
        """.trimIndent()
        val catalog = A2uiJson.strict.decodeFromString(CatalogDefinition.serializer(), source)
        val found = CatalogChildResolver.of(listOf(catalog), surfaceDefault = catalog.catalogId)
            .childrenOf(
                A2uiJson.strict.decodeFromString(
                    Component.serializer(),
                    """{"id": "p", "component": "Panel", "data_note": "c1"}""",
                ),
            )
        assertEquals(emptyList(), found, "a pattern-covered property was read as a child")
    }

    @Test
    fun finds_the_children_of_a_component_that_overrides_to_a_catalog_named_after_the_placeholder() {
        // The reservation withholds a *name*, not the catalog -- `CatalogIdentityTest` pins that
        // for the checker. This is the same rule for the resolver, and it is the case the two
        // disagreed on: a held catalog whose `catalogId` is the name `catalog.json` resolves to is
        // refused by `SchemaRegistry.document` unless it is the catalog *bound*, so an override to
        // it found no definition while the surface's own default was bound instead. The component
        // checked out valid and rendered with its children dropped, silently -- which is the
        // failure this whole class exists to prevent.
        val source = """
        {
          "catalogId": "https://a2ui.org/specification/v1_0/catalog.json",
          "components": {
            "Column": {
              "type": "object",
              "properties": {
                "component": {"const": "Column"},
                "children": {
                  "${'$'}ref": "https://a2ui.org/specification/v1_0/common_types.json#/${'$'}defs/ChildList"
                }
              }
            }
          }
        }
        """.trimIndent()
        val named = A2uiJson.strict.decodeFromString(CatalogDefinition.serializer(), source)
        val resolver = CatalogChildResolver.of(
            listOf(named, CATALOG),
            surfaceDefault = CATALOG.catalogId,
        )
        val found = resolver.childrenOf(
            A2uiJson.strict.decodeFromString(
                Component.serializer(),
                """{"id": "col", "component": "Column", "children": ["a", "b"],
                    "catalogId": "${named.catalogId}"}""",
            ),
        )
        assertEquals(listOf(ChildReference.Fixed("children", listOf("a", "b"))), found)
    }

    @Test
    fun reads_an_overriding_catalogs_placeholder_ref_off_that_catalog_rather_than_the_surfaces() {
        // The same binding, in the spelling that has nothing to do with the reservation. Both
        // catalogs reach their child property through `catalog.json` -- the placeholder meaning
        // "whichever catalog is in play" -- but declare a *different* property behind it. For a
        // component that overrides the surface's default, the catalog in play is the one it named;
        // resolving against the surface default's registry read the surface catalog's property
        // name off the overriding catalog's component, which is a wrong tree rather than a missing
        // one, and it is `body` that would be reported here.
        fun catalog(id: String, property: String) = A2uiJson.strict.decodeFromString(
            CatalogDefinition.serializer(),
            """
            {
              "catalogId": "$id",
              "components": {
                "Panel": {"${'$'}ref": "catalog.json#/${'$'}defs/panelShape"}
              },
              "${'$'}defs": {
                "panelShape": {
                  "type": "object",
                  "properties": {
                    "component": {"const": "Panel"},
                    "$property": {
                      "${'$'}ref": "https://a2ui.org/specification/v1_0/common_types.json#/${'$'}defs/Child"
                    }
                  }
                }
              }
            }
            """.trimIndent(),
        )
        val surface = catalog("urn:test:surface", "body")
        val override = catalog("urn:test:override", "content")
        val found = CatalogChildResolver.of(
            listOf(surface, override),
            surfaceDefault = surface.catalogId,
        ).childrenOf(
            A2uiJson.strict.decodeFromString(
                Component.serializer(),
                """{"id": "p", "component": "Panel", "content": "c1", "body": "c2",
                    "catalogId": "urn:test:override"}""",
            ),
        )
        assertEquals(listOf(ChildReference.Single("content", "c1")), found, found.toString())
    }
}
