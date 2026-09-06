package dev.ynagai.a2ui.smoketest

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.ynagai.a2ui.compose.A2uiComponentScope
import dev.ynagai.a2ui.compose.ComponentRegistry
import dev.ynagai.a2ui.compose.ComponentRenderer
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

/**
 * Writes a renderer, which is the thing a host actually does with this library.
 *
 * This build declares no Compose dependency of its own, so `Modifier` and `@Composable` reach
 * this file only across the published metadata -- which is the property being checked: that a
 * host can name the types in `ComponentRenderer`'s signature having resolved these coordinates
 * and nothing else.
 *
 * It does **not** pin `a2ui-compose`'s `api(compose.runtime)` / `api(compose.ui)` specifically,
 * and the first draft of this comment claimed it did. Measured: downgrade both to
 * `implementation`, publish, and the gate stays green, because `a2ui-material3`'s
 * `api(compose.material3)` puts the same two on the compile classpath transitively. Guarding
 * those two scopes on their own would take a consumer that resolves `a2ui-compose` without
 * `a2ui-material3`, which this build is not.
 */
@Suppress("unused")
internal fun renderer(): ComponentRenderer = object : ComponentRenderer {
    @Composable
    override fun Render(scope: A2uiComponentScope, modifier: Modifier): Unit = Unit
}
