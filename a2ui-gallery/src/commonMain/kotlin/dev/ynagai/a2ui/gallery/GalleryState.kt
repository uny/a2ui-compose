package dev.ynagai.a2ui.gallery

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import dev.ynagai.a2ui.compose.A2uiClock
import dev.ynagai.a2ui.compose.A2uiRenderer
import dev.ynagai.a2ui.core.function.LocaleFormatter
import dev.ynagai.a2ui.core.function.FallbackLocaleFormatter
import dev.ynagai.a2ui.core.function.UrlOpener
import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.ActionMessage
import dev.ynagai.a2ui.core.protocol.RendererToAgentMessage
import dev.ynagai.a2ui.core.surface.SurfaceModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * How the Gallery renders JSON for reading rather than for the wire.
 *
 * Derived from [A2uiJson.strict] rather than configured from scratch, so the panes show payloads
 * under the same rules the renderer decoded them by -- a key this library drops on encode would
 * otherwise be visible here and nowhere else.
 */
internal val GalleryJson: Json = Json(A2uiJson.strict) { prettyPrint = true }

/**
 * One entry of the action log -- a message the surface sent back towards the agent.
 *
 * Kept as the decoded message plus its rendered JSON. The blueprint asks the pane to show "actions
 * and their context", and context is a `JsonObject` whose interesting property is its *shape*
 * after scope resolution; a `toString` of the data class would bury it.
 */
@Immutable
public data class ActionLogEntry(
    public val message: RendererToAgentMessage,
    public val json: String,
) {
    /** A one-line label for the collapsed view: the action's name, or the message's kind. */
    public val label: String get() = when (message) {
        is ActionMessage -> message.name
        else -> message::class.simpleName ?: "message"
    }

    /** The component that originated this, when the message names one. */
    public val sourceComponentId: String? get() = (message as? ActionMessage)?.sourceComponentId
}

/**
 * The Gallery's state for one example: how much of it has been applied, and what came back.
 *
 * A plain class rather than a pile of `remember`s inside the composable, because the blueprint
 * requires the integration tests to "utilize the Gallery App's logic" -- stepping, two-way binding
 * and action scoping are all assertions about *this*, and a test that had to stand up a
 * composition to make one would be testing Compose as much as the renderer. The composable reads
 * it; nothing here needs a composition to run.
 *
 * Snapshot state is used outside composition on purpose: writes are visible to a composition that
 * reads them, and to a test that does not have one, with no second code path.
 *
 * @param clock the source of the `timestamp` on every action message. The system clock by default;
 *   a test that asserts on a formatted timestamp passes a fixed one, as `ExampleRenderTest` does.
 * @param locale how the four locale-sensitive functions format. The locale-independent default
 *   here, so a formatted string asserted in a test does not change with the machine; [GalleryApp]
 *   passes `systemLocaleFormatter()` instead, which is what the running Gallery uses and the only
 *   thing that exercises `platformLocaleData` on Kotlin/JS.
 * @param urlOpener where an `openUrl` action sends a URL. No-op, and **not** wired to
 *   `rememberPlatformUrlOpener()` anywhere: opening a URL is a capability handed to an agent's
 *   payload rather than a formatter reading the machine, so an `openUrl` action does nothing in
 *   the Gallery. A host that wants otherwise passes its own.
 */
@Stable
public class GalleryState(
    public val examples: List<Example> = EXAMPLES,
    private val clock: A2uiClock = A2uiClock.System,
    private val locale: LocaleFormatter = FallbackLocaleFormatter,
    private val urlOpener: UrlOpener = UrlOpener { },
) {
    init {
        require(examples.isNotEmpty()) { "the Gallery needs at least one example to show." }
    }

    /** The example on screen. Setting it through [select] restarts the stepper. */
    public var example: Example by mutableStateOf(examples.first())
        private set

    /**
     * The renderer the preview draws from.
     *
     * Replaced rather than reset when the example changes: [A2uiRenderer]'s state is private, and
     * a fresh instance is also the only way to be sure nothing of the previous example -- a
     * surface id it happened to share, a data model write -- survives into the next.
     */
    public var renderer: A2uiRenderer by mutableStateOf(newRenderer())
        private set

    /** How many of [Example.raw] have been applied. Starts at zero: nothing is drawn until the
     * first advance, which is what makes the first message's effect visible at all. */
    public var cursor: Int by mutableStateOf(0)
        private set

    /** Messages the surface sent back, newest last. */
    public val actionLog: SnapshotStateList<ActionLogEntry> = mutableStateListOf()

    /** Whether [advance] would do anything. */
    public val canAdvance: Boolean get() = cursor < example.raw.size

    /** The surface the preview draws, or null while none is renderable yet. */
    public val surfaceId: String?
        get() = renderer.state.surfaces.entries.firstOrNull { it.value.isRenderable }?.key

    /** The model of [surfaceId], or null. */
    public val surface: SurfaceModel? get() = surfaceId?.let { renderer.state.surfaces[it] }

    /**
     * The surface the inspection panes read: the renderable one, or any surface that exists.
     *
     * Deliberately not [surface], which is the *preview's* and is null until the root component
     * arrives. `00_incremental.json` sends its data model one message before that root, so a pane
     * keyed to renderability shows `{}` over a surface that already holds the data -- hiding
     * exactly the progressive-rendering step the stepper exists to make visible.
     */
    public val inspectedSurface: SurfaceModel?
        get() = renderer.state.surfaces.values.firstOrNull { it.isRenderable }
            ?: renderer.state.surfaces.values.firstOrNull()

    /** The live data model, pretty-printed. Empty object while no surface exists. */
    public val dataModelJson: String
        get() = GalleryJson.encodeToString<JsonElement>(
            inspectedSurface?.dataModel ?: JsonObject(emptyMap()),
        )

    /** Shows [example] from the start. A no-op for the one already showing, so that re-tapping the
     * selected row does not silently discard a stepped-through state. */
    public fun select(example: Example) {
        if (example == this.example) return
        this.example = example
        reset()
    }

    /** Applies the next message, if there is one. */
    public fun advance() {
        if (!canAdvance) return
        renderer.apply(example.decoded[cursor])
        cursor += 1
    }

    /** Applies every remaining message. */
    public fun advanceAll() {
        if (!canAdvance) return
        renderer.applyAll(example.decoded.subList(cursor, example.decoded.size))
        cursor = example.raw.size
    }

    /** Returns to nothing-applied, on a renderer that has never seen this example. */
    public fun reset() {
        renderer = newRenderer()
        cursor = 0
        actionLog.clear()
    }

    /** Records a message the surface sent. Passed to `A2uiSurface` as its `onMessage`. */
    public fun record(message: RendererToAgentMessage) {
        actionLog += ActionLogEntry(
            message = message,
            json = GalleryJson.encodeToString<RendererToAgentMessage>(message),
        )
    }

    /** The message at [index] of the current example, pretty-printed. */
    public fun messageJson(index: Int): String =
        GalleryJson.encodeToString<JsonElement>(example.raw[index])

    private fun newRenderer() = A2uiRenderer(
        locale = locale,
        urlOpener = urlOpener,
        clock = clock,
    )
}
