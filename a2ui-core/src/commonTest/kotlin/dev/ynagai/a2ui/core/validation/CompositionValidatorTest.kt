package dev.ynagai.a2ui.core.validation

import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.CatalogDefinition
import dev.ynagai.a2ui.core.protocol.Component
import dev.ynagai.a2ui.core.protocol.Surface
import dev.ynagai.a2ui.core.surface.SurfaceModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A catalog stating the same rule from each of the three sides the specification's examples use:
 * from the child (`allowedParents`), from the container (`allowedChildren`), and from both.
 *
 * Hand-written rather than the basic catalog, which declares neither keyword — so unlike the other
 * checkers here, there is no published catalog to measure this one against.
 */
private const val CATALOG_SOURCE = """
{
  "${'$'}id": "urn:test:composition",
  "catalogId": "urn:test:composition",
  "protocolVersion": "1.0",
  "components": {
    "AppLayout": {
      "type": "object",
      "allowedParents": ["Surface"],
      "properties": {
        "component": {"const": "AppLayout"},
        "children": {
          "${'$'}ref": "https://a2ui.org/specification/v1_0/common_types.json#/${'$'}defs/ChildList"
        }
      }
    },
    "Menu": {
      "type": "object",
      "allowedChildren": ["MenuItem"],
      "properties": {
        "component": {"const": "Menu"},
        "children": {
          "${'$'}ref": "https://a2ui.org/specification/v1_0/common_types.json#/${'$'}defs/ChildList"
        }
      }
    },
    "MenuItem": {
      "type": "object",
      "allowedParents": ["Menu"],
      "properties": {"component": {"const": "MenuItem"}}
    },
    "Label": {
      "type": "object",
      "properties": {"component": {"const": "Label"}}
    },
    "Nowhere": {
      "type": "object",
      "allowedParents": [],
      "properties": {"component": {"const": "Nowhere"}}
    }
  }
}
"""

private val CATALOG: CatalogDefinition =
    A2uiJson.strict.decodeFromString(CatalogDefinition.serializer(), CATALOG_SOURCE)

private val VALIDATOR = CompositionValidator(listOf(CATALOG), surfaceDefault = CATALOG.catalogId)

private val RESOLVER = CatalogChildResolver.of(listOf(CATALOG), surfaceDefault = CATALOG.catalogId)

private fun component(json: String): Component =
    A2uiJson.strict.decodeFromString(Component.serializer(), json)

private fun surface(vararg components: String): SurfaceModel = SurfaceModel(
    surfaceId = "s",
    catalogId = CATALOG.catalogId,
).withComponents(components.map(::component))

private fun check(vararg components: String) = VALIDATOR.validate(surface(*components), RESOLVER)

class CompositionValidatorTest {
    @Test
    fun accepts_a_tree_every_constraint_allows() {
        val found = check(
            """{"id": "root", "component": "AppLayout", "children": ["m"]}""",
            """{"id": "m", "component": "Menu", "children": ["i1"]}""",
            """{"id": "i1", "component": "MenuItem"}""",
        )
        assertEquals(emptyList(), found)
    }

    @Test
    fun treats_the_reserved_container_as_the_parent_of_the_root() {
        // This is what makes `"allowedParents": ["Surface"]` mean "only at the top level", which
        // is the specification's first worked example -- without it the rule never holds.
        assertEquals(
            emptyList(),
            check("""{"id": "root", "component": "AppLayout", "children": []}"""),
        )
    }

    @Test
    fun reports_a_component_restricted_to_the_top_level_that_is_not_there() {
        val found = check(
            """{"id": "root", "component": "Menu", "children": ["a"]}""",
            """{"id": "a", "component": "AppLayout", "children": []}""",
        )
        val parent = found.single { it.code == CompositionViolation.UNALLOWED_PARENT }
        assertEquals("AppLayout", parent.child)
        assertEquals("Menu", parent.parent)
        assertEquals("a", parent.childId)
    }

    @Test
    fun reports_a_container_carrying_a_child_it_does_not_allow() {
        val found = check(
            """{"id": "root", "component": "AppLayout", "children": ["m"]}""",
            """{"id": "m", "component": "Menu", "children": ["l"]}""",
            """{"id": "l", "component": "Label"}""",
        )
        val child = found.single()
        assertEquals(CompositionViolation.UNALLOWED_CHILD, child.code)
        assertEquals("Menu", child.parent)
        assertEquals("Label", child.child)
    }

    @Test
    fun reports_both_codes_when_the_catalog_states_the_rule_from_both_sides() {
        // A `MenuItem` under an `AppLayout` breaks the child's `allowedParents` and the parent has
        // no `allowedChildren`, so one code. Under a `Menu` holding a `Label` it is the other. A
        // tree that breaks both rules at once must say both -- an agent correcting only the one it
        // was told about would resend a payload that still fails.
        val found = check(
            """{"id": "root", "component": "Menu", "children": ["a"]}""",
            """{"id": "a", "component": "AppLayout", "children": []}""",
        )
        assertEquals(
            setOf(CompositionViolation.UNALLOWED_PARENT, CompositionViolation.UNALLOWED_CHILD),
            found.map { it.code }.toSet(),
        )
    }

    @Test
    fun an_empty_allowed_parents_list_bars_every_parent() {
        // Not the same as omitting the keyword: `[]` is how a catalog withdraws a component.
        val found = check(
            """{"id": "root", "component": "AppLayout", "children": ["n"]}""",
            """{"id": "n", "component": "Nowhere"}""",
        )
        assertEquals(CompositionViolation.UNALLOWED_PARENT, found.single().code)
    }

    @Test
    fun an_omitted_keyword_constrains_nothing() {
        assertEquals(
            emptyList(),
            check(
                """{"id": "root", "component": "AppLayout", "children": ["l"]}""",
                """{"id": "l", "component": "Label"}""",
            ),
        )
    }

    @Test
    fun skips_a_reference_to_a_component_that_has_not_arrived() {
        // Progressive rendering: an id naming nothing is a component still in flight, not a
        // composition error.
        assertEquals(
            emptyList(),
            check("""{"id": "root", "component": "Menu", "children": ["not-yet"]}"""),
        )
    }

    @Test
    fun reports_nothing_for_a_surface_whose_root_has_not_arrived() {
        assertEquals(
            emptyList(),
            check("""{"id": "other", "component": "MenuItem"}"""),
        )
    }

    @Test
    fun checks_a_template_child_once_rather_than_once_per_row() {
        // The subtree is instantiated per item of a list the agent sends, and composition is a
        // property of the types -- reporting per row would repeat one pairing as many times as the
        // data model happens to be long.
        val model = SurfaceModel(surfaceId = "s", catalogId = CATALOG.catalogId)
            .withComponents(
                listOf(
                    component("""{"id": "root", "component": "Menu", "children": {"componentId": "l", "path": "/rows"}}"""),
                    component("""{"id": "l", "component": "Label"}"""),
                ),
            )
        val found = VALIDATOR.validate(model, RESOLVER)
        assertEquals(1, found.size, found.toString())
        assertEquals(CompositionViolation.UNALLOWED_CHILD, found.single().code)
    }

    @Test
    fun reports_one_forbidden_pairing_once_however_many_routes_reach_it() {
        // The adjacency list is a graph, and `walk` follows every path through it: n layers each
        // referencing the same two children produce 2^n instances from 2n components. A check that
        // ran over the expansion would report this one pairing four times and would cost the same
        // shape on a deeper payload.
        val found = check(
            """{"id": "root", "component": "AppLayout", "children": ["m1", "m2"]}""",
            """{"id": "m1", "component": "Menu", "children": ["l", "l"]}""",
            """{"id": "m2", "component": "Menu", "children": ["l"]}""",
            """{"id": "l", "component": "Label"}""",
        )
        // Three edges name the pairing: `m1` twice and `m2` once. Each edge is one placement the
        // catalog forbids, and they are reported once each -- not once per route to the parent.
        assertEquals(3, found.size, found.toString())
        assertTrue(found.all { it.code == CompositionViolation.UNALLOWED_CHILD })
    }

    @Test
    fun checks_a_component_no_route_reaches_yet() {
        // It is in the surface and the next `updateComponents` may mount it. Reporting now is what
        // lets an agent fix the pairing before it is drawn.
        val found = check(
            """{"id": "root", "component": "AppLayout", "children": []}""",
            """{"id": "m", "component": "Menu", "children": ["l"]}""",
            """{"id": "l", "component": "Label"}""",
        )
        assertEquals(CompositionViolation.UNALLOWED_CHILD, found.single().code)
    }

    @Test
    fun allows_a_component_under_one_parent_while_reporting_it_under_another() {
        // A component may have more than one parent, and both verdicts are true at once.
        val found = check(
            """{"id": "root", "component": "Menu", "children": ["i", "l"]}""",
            """{"id": "i", "component": "MenuItem"}""",
            """{"id": "l", "component": "Label"}""",
        )
        assertEquals("Label", found.single().child)
    }

    @Test
    fun names_the_property_the_reference_was_found_under() {
        val found = check(
            """{"id": "root", "component": "Menu", "children": ["l"]}""",
            """{"id": "l", "component": "Label"}""",
        )
        assertEquals("children", found.single().property)
        assertTrue(found.single().message.isNotEmpty())
    }
    @Test
    fun the_surface_supplies_the_default_when_the_validator_was_not_given_one() {
        // What a renderer holding several catalogs builds: one validator over the list, and the
        // surface says which catalog it belongs to. Reading only the constructor default returned
        // an empty list here -- composition checking silently off.
        val validator = CompositionValidator(listOf(CATALOG))
        val resolver = CatalogChildResolver.of(listOf(CATALOG), surfaceDefault = CATALOG.catalogId)
        val violations = validator.validate(
            surface(
                """{"id": "root", "component": "Menu", "children": ["l"]}""",
                """{"id": "l", "component": "Label"}""",
            ),
            resolver,
        )
        assertEquals(
            listOf(CompositionViolation.UNALLOWED_CHILD),
            violations.map { it.code },
            violations.toString(),
        )
    }

}
