package dev.ynagai.a2ui.gallery

import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.AgentToRendererMessage
import dev.ynagai.a2ui.core.protocol.CatalogDefinition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One of the specification's example files.
 *
 * [messages] is kept in both forms on purpose. The Gallery's stepper feeds [decoded] to the
 * `MessageProcessor` one at a time, while its JSON pane shows [raw] -- and the raw element is also
 * what the validator takes, since it checks the payload the agent sent rather than what this
 * implementation's decoder made of it.
 */
public data class Example(
    /** The file this came from, e.g. `00_simple-text.json`. */
    public val file: String,
    /** The example's own title, e.g. `Simple Text`. */
    public val name: String,
    public val description: String,
    public val raw: List<JsonElement>,
    public val decoded: List<AgentToRendererMessage>,
) {
    /**
     * Whether this is one of the five the SDK implementation skill names as the foundational
     * verification set, to be rendered before the rest of the catalog is attempted.
     */
    public val isFoundational: Boolean get() = file.removeSuffix(".json") in FOUNDATIONAL

    public companion object {
        internal val FOUNDATIONAL = setOf(
            "00_simple-text",
            "00_row-layout",
            "00_complex-layout",
            "00_interactive-button",
            "00_simple-login-form",
        )
    }
}

/**
 * The specification's example corpus, in filename order.
 *
 * Decoded eagerly: an example that does not parse is a fact about this implementation, and the
 * corpus is the one place that can say so about all of them at once rather than one failing
 * Gallery screen at a time.
 */
public val EXAMPLES: List<Example> = ExampleSources.ALL.entries
    // Sorted by file so the order is the same on every target. `toSortedMap` would do it, and is
    // JVM-only.
    .sortedBy { it.key }
    .map { (file, source) ->
        val document = A2uiJson.strict.parseToJsonElement(source) as JsonObject
        val raw = (document["messages"] as JsonArray).toList()
        Example(
            file = file,
            name = document.text("name") ?: file.removeSuffix(".json"),
            description = document.text("description").orEmpty(),
            raw = raw,
            decoded = raw.map {
                A2uiJson.strict.decodeFromJsonElement(AgentToRendererMessage.serializer(), it)
            },
        )
    }

/** The basic catalog, which every example's `createSurface` names. */
public val BASIC_CATALOG: CatalogDefinition =
    A2uiJson.strict.decodeFromString(CatalogDefinition.serializer(), GalleryCatalog.BASIC)

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
