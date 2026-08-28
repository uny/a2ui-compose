package dev.ynagai.a2ui.gallery

import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.AgentToRendererMessage
import dev.ynagai.a2ui.compose.BasicCatalog
import dev.ynagai.a2ui.core.protocol.CatalogDefinition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One of the specification's example files.
 *
 * The messages are kept in both forms on purpose. The Gallery's stepper feeds [decoded] to the
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
    /**
     * Every component type this example names.
     *
     * What makes "which examples can a registry draw?" a question with a mechanical answer rather
     * than a hand-kept list -- see [isDrawableBy]. Read off the raw messages rather than the
     * decoded ones because a component keeps its catalog-defined properties as an unparsed bag,
     * and `component` is the one field the decoder does not have to interpret.
     */
    public val componentTypes: Set<String>,
) {
    /**
     * Whether [types] covers every component this example names.
     *
     * An empty example is not drawable by anything: it names no components, so the answer would
     * otherwise be "yes" for every registry including the empty one.
     */
    public fun isDrawableBy(types: Set<String>): Boolean =
        componentTypes.isNotEmpty() && types.containsAll(componentTypes)

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
        // Both failures name the file. Unchecked casts here would report neither it nor the key,
        // and because this is a top-level initializer the first one to throw takes the whole file
        // down: on JVM every later touch -- `BASIC_CATALOG` included -- then raises
        // `NoClassDefFoundError`, which no longer carries the original cause at all.
        val document = A2uiJson.strict.parseToJsonElement(source) as? JsonObject
            ?: error("$file: an example is a JSON object; this one is not.")
        val raw = (document["messages"] as? JsonArray)?.toList()
            ?: error("$file: an example carries a `messages` array; this one does not.")
        Example(
            file = file,
            name = document.text("name") ?: file.removeSuffix(".json"),
            description = document.text("description").orEmpty(),
            raw = raw,
            decoded = raw.map {
                A2uiJson.strict.decodeFromJsonElement(AgentToRendererMessage.serializer(), it)
            },
            componentTypes = raw.componentTypes(),
        )
    }

/**
 * The `component` of every component in every `updateComponents` message.
 *
 * A flat scan rather than a walk of the whole document, and that is exact rather than approximate:
 * the specification does not allow a component to be defined inline, so every component in a
 * surface appears as an entry of some `updateComponents.components` array. A nested reference --
 * a `Tabs` entry's `child`, a `Card`'s child -- is an id, and the component it names is in that
 * same flat list.
 */
private fun List<JsonElement>.componentTypes(): Set<String> = buildSet {
    for (message in this@componentTypes) {
        val update = (message as? JsonObject)?.get("updateComponents") as? JsonObject ?: continue
        val components = update["components"] as? JsonArray ?: continue
        for (component in components) {
            ((component as? JsonObject)?.get("component") as? JsonPrimitive)?.contentOrNull
                ?.let { add(it) }
        }
    }
}

/**
 * The basic catalog, which every example's `createSurface` names.
 *
 * The renderer's copy, not a second one vendored here. A renderer needs the catalog document at
 * runtime to resolve children and check components, so `a2ui-compose` publishes it -- and a corpus
 * validated against a different copy would be checking something other than what will draw it.
 */
public val BASIC_CATALOG: CatalogDefinition get() = BasicCatalog.definition

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
