package dev.ynagai.a2ui.core.validation

import dev.ynagai.a2ui.core.protocol.A2uiJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two catalogs this library vendors, checked against the `catalog_definition.json` it vendors
 * beside them.
 *
 * These files are copied from the specification and bumped by hand -- see `spec/README.md`, which
 * records a revision per document. Nothing made the pair self-consistent: the catalogs were only
 * ever read *through* [CatalogValidator], which asks what a catalog says about a payload, not
 * whether the catalog itself is a catalog. A bump that took a newer `catalog_definition.json` and
 * an older `catalog.json` would leave the two disagreeing with the whole suite green.
 *
 * That is not hypothetical. Upstream `676a8999` removed the `allOf` branch from
 * `FunctionCallValidationSchema` and rewrote every function in the basic catalog to the flat
 * `properties` shape, and the protocol document gained a MUST NOT saying function definitions may
 * no longer wrap `FunctionCommon` themselves. Taking the new definition with the old catalog is a
 * one-file mistake, and before this test nothing in five targets would have reported it.
 */
class VendoredCatalogsAreValidTest {
    @Test
    fun the_basic_catalog_validates_against_the_catalog_definition_shipped_with_it() {
        assertValidCatalog("basic", CatalogFixtures.BASIC)
    }

    @Test
    fun the_testing_catalog_validates_against_the_catalog_definition_shipped_with_it() {
        // The testing catalog is the conformance suite's own, and it moves with the basic one --
        // `676a8999` rewrote both. It is vendored here for the harness, so it is bumped here too.
        assertValidCatalog("testing", CatalogFixtures.TESTING)
    }

    private fun assertValidCatalog(name: String, source: String) {
        val definition = ProtocolSchemas.catalogDefinition
        val uri = (definition["\$id"] as JsonPrimitive).content
        val result = SchemaEvaluator(SchemaRegistry.of(ProtocolSchemas.documents)).validate(
            definition,
            SchemaLocation(uri, ""),
            A2uiJson.strict.parseToJsonElement(source) as JsonObject,
        )
        assertTrue(
            result.isValid,
            "the vendored $name catalog does not satisfy the vendored catalog_definition.json. " +
                "The two revisions in `spec/README.md` have drifted apart. " +
                result.violations.joinToString("\n") { it.toString() },
        )
        // A validator that quietly skipped the keywords doing the work would report `isValid` for
        // any document at all, which is the one way this test could pass while saying nothing.
        assertEquals(emptySet(), result.unsupportedKeywords, "$name: keywords went unapplied")
    }
}
