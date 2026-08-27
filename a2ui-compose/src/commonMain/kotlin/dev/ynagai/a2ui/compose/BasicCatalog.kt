package dev.ynagai.a2ui.compose

import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.CatalogDefinition

/**
 * The A2UI v1.0 basic catalog, as this library ships it.
 *
 * An agent names a catalog with a bare string. Nothing resolves that string over the network, and
 * nothing should: a catalog fetched from a URL the agent chose is a catalog the agent chose to be
 * validated against. So the document a renderer checks against has to be one it already holds, and
 * this is it.
 */
public object BasicCatalog {
    /**
     * The `catalogId` the specification's own examples name, and the one this document registers
     * under.
     *
     * Read from the document rather than written out here. The two being the same string is the
     * whole point, and a second copy of it is a second thing to get wrong when the specification
     * moves.
     */
    public val id: String get() = definition.catalogId

    /** The parsed catalog. */
    public val definition: CatalogDefinition by lazy {
        A2uiJson.strict.decodeFromString(CatalogDefinition.serializer(), BasicCatalogSource.BASIC)
    }

    /** The catalog document verbatim, for a caller that needs the text rather than the model. */
    public val source: String get() = BasicCatalogSource.BASIC
}
