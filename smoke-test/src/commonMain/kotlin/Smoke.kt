package dev.ynagai.a2ui.smoketest

import dev.ynagai.a2ui.compose.ComponentRegistry
import dev.ynagai.a2ui.core.protocol.CatalogDefinition
import dev.ynagai.a2ui.core.protocol.ComponentDefinition
import dev.ynagai.a2ui.material3.Material3Components
import kotlinx.serialization.json.buildJsonObject

/**
 * Touches one public symbol from each published artifact.
 *
 * Resolution alone is not the whole property. An artifact can resolve and still be missing the
 * class -- a `.module` file can point a variant at a jar that does not carry it, and the metadata
 * module can resolve while a platform one does not. Compiling against a symbol from each of the
 * three, on every target, is what proves the published metadata leads somewhere real.
 */
@Suppress("unused")
internal fun catalog(): CatalogDefinition = CatalogDefinition(
    catalogId = "example.com:smoke",
    components = mapOf("Text" to ComponentDefinition(schema = buildJsonObject {})),
)

@Suppress("unused")
internal fun registry(): ComponentRegistry = Material3Components.Basic
