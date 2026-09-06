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
 * Here to exercise the `api` scopes rather than for what it draws. `Modifier` and `@Composable`
 * are named by nothing else in this file, and this build declares no Compose dependency of its
 * own -- they can only arrive through `a2ui-compose`'s `api(compose.runtime)` and
 * `api(compose.ui)`. Scoped `implementation` those would reach a consumer's runtime classpath but
 * not its compile classpath, this file would stop compiling, and the gate would say so; without
 * it, that downgrade published green and broke on the first host to write a renderer.
 */
@Suppress("unused")
internal fun renderer(): ComponentRenderer = object : ComponentRenderer {
    @Composable
    override fun Render(scope: A2uiComponentScope, modifier: Modifier): Unit = Unit
}
