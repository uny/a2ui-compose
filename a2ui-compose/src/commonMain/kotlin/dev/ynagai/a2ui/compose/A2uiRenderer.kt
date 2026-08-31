package dev.ynagai.a2ui.compose

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ynagai.a2ui.core.function.EvaluationLimits
import dev.ynagai.a2ui.core.function.FallbackLocaleFormatter
import dev.ynagai.a2ui.core.function.LocaleFormatter
import dev.ynagai.a2ui.core.function.UrlOpener
import dev.ynagai.a2ui.core.protocol.AgentToRendererMessage
import dev.ynagai.a2ui.core.protocol.CatalogDefinition
import dev.ynagai.a2ui.core.surface.A2uiStateException
import dev.ynagai.a2ui.core.surface.ChildResolver
import dev.ynagai.a2ui.core.surface.JsonPointer
import dev.ynagai.a2ui.core.surface.MessageProcessor
import dev.ynagai.a2ui.core.surface.RendererEffect
import dev.ynagai.a2ui.core.surface.RendererState
import dev.ynagai.a2ui.core.surface.SurfaceModel
import dev.ynagai.a2ui.core.validation.CatalogChildResolver
import dev.ynagai.a2ui.core.surface.RenderLimits
import dev.ynagai.a2ui.core.validation.ValidationLimits
import kotlinx.serialization.json.JsonElement

/** A source of ISO 8601 timestamps, for the `timestamp` every action message carries. */
public fun interface A2uiClock {
    public fun nowIso8601(): String

    public companion object {
        /**
         * The system clock.
         *
         * Wrapped rather than exposed: `kotlin.time.Clock` is still experimental, and a library
         * that names it in a public signature makes every consumer opt in to that. A test that
         * wants a fixed timestamp passes its own [A2uiClock] instead.
         */
        public val System: A2uiClock = A2uiClock {
            @OptIn(kotlin.time.ExperimentalTime::class)
            kotlin.time.Clock.System.now().toString()
        }
    }
}

/**
 * Holds the renderer's state and applies messages to it.
 *
 * The state is one immutable [RendererState] in a snapshot-backed property, rather than a graph of
 * observables. The framework adapter blueprint describes a binder layer that turns each bound
 * property into a stream, with rules about subscribing lazily, re-subscribing when a path changes,
 * and disposing on unmount. Those rules exist to keep manual subscriptions from leaking; Compose's
 * snapshot system has no subscriptions to leak, so what they protect against cannot happen here.
 * The blueprint allows exactly this -- it is the "Direct / Binderless" strategy it names first.
 *
 * The cost that swaps in is recomposition granularity: every component reads from the same state
 * object, so a single data model write invalidates all of them. That is paid where it arises, in
 * [A2uiComponentScope]'s property accessors, which wrap each resolved value in a `derivedStateOf`
 * so a component recomposes only when the value it actually asked for changes.
 *
 * @param catalogs the catalogs this renderer can resolve. A component naming one that is absent
 *   renders as a placeholder rather than an error, because the specification requires missing
 *   references to degrade rather than fail.
 * @param locale how the four locale-sensitive functions format. The default is locale-independent
 *   and English-shaped; `systemLocaleFormatter()` reads the device's locale and
 *   `localeFormatter(tag)` takes a fixed one. Opt-in on purpose -- a renderer that reads the
 *   device by default makes the same payload render differently in CI than on a desk.
 * @param urlOpener where `openUrl` sends a URL. The default does nothing, since a library should
 *   not navigate its host's window uninvited; `rememberPlatformUrlOpener()` is the platform's own.
 */
@Stable
public class A2uiRenderer(
    public val catalogs: List<CatalogDefinition> = listOf(BasicCatalog.definition),
    public val locale: LocaleFormatter = FallbackLocaleFormatter,
    public val urlOpener: UrlOpener = UrlOpener { },
    public val clock: A2uiClock = A2uiClock.System,
    public val evaluationLimits: EvaluationLimits = EvaluationLimits.DEFAULT,
    public val validationLimits: ValidationLimits = ValidationLimits.DEFAULT,
    public val renderLimits: RenderLimits = RenderLimits.DEFAULT,
    initialState: RendererState = RendererState(),
) {
    /** Every surface this renderer holds. Reading it in a composable subscribes to changes. */
    public var state: RendererState by mutableStateOf(initialState)
        private set

    // Keyed by the surface's catalog rather than built per component: the resolver parses the
    // catalog's schemas, and a tree of a thousand components would otherwise parse them a thousand
    // times. There is one entry per catalog a surface has named, which is bounded by `catalogs`.
    private val resolvers = mutableMapOf<String?, ChildResolver>()

    /** Applies [message], returning what the renderer should act on beyond redrawing. */
    public fun apply(message: AgentToRendererMessage): List<RendererEffect> {
        val result = MessageProcessor.apply(state, message)
        state = result.state
        return result.effects
    }

    /** Applies [messages] in order, as one state transition. */
    public fun applyAll(messages: Iterable<AgentToRendererMessage>): List<RendererEffect> {
        val result = MessageProcessor.applyAll(state, messages)
        state = result.state
        return result.effects
    }

    /**
     * Writes [value] at [pointer] in [surfaceId]'s data model -- the write half of two-way binding.
     *
     * Local, and deliberately not sent to the agent. The specification has the renderer own the
     * data model between actions; the agent sees what the user typed when an action carries it.
     */
    public fun write(surfaceId: String, pointer: JsonPointer, value: JsonElement) {
        val surface = state.surfaces[surfaceId] ?: return
        // [pointer] is the agent's, not the host's: it arrives as a component's `path` and reaches
        // here through [A2uiComponentScope.binding]. `JsonObject.write` refuses some of those --
        // an array index past the end, a root write of a non-object -- by raising, and this is
        // called from an input callback, so an uncaught raise turns one malformed `path` into a
        // crash on the first keystroke. Left unwritten instead, which is what every other
        // agent-driven path in this library does with a payload it cannot honour.
        //
        // `A2uiStateException` only: the `require` that a write address be absolute is a caller
        // error rather than an agent one, and swallowing it would hide a bug in a host's own
        // pointer arithmetic.
        val updated = try {
            surface.withDataModel(pointer, value)
        } catch (_: A2uiStateException) {
            return
        }
        state = state.copy(surfaces = state.surfaces + (surfaceId to updated))
    }

    /**
     * The resolver that finds [surface]'s components' children.
     *
     * There is deliberately no fallback to a resolver that reads `child` and `children`: those
     * names are the basic catalog's, and a renderer that assumed them would silently drop
     * `Modal.trigger`, `Modal.content` and the child inside each `Tabs.tabs` entry.
     *
     * Built even when [SurfaceModel.catalogId] names nothing this renderer holds -- including when
     * it names nothing at all, which `createSurface` permits. A component may carry its own
     * `catalogId`, and [CatalogChildResolver] already resolves that override against every catalog
     * passed here; short-circuiting on the surface default would drop the children of a component
     * that named a catalog this renderer does have. The degradation the previous
     * [ChildResolver.NONE] provided is not lost: a component whose catalog is genuinely absent
     * still contributes no children, because that is what `CatalogChildResolver` does with one.
     */
    internal fun childResolver(surface: SurfaceModel): ChildResolver =
        resolvers.getOrPut(surface.catalogId) {
            CatalogChildResolver.of(catalogs, surface.catalogId, validationLimits)
        }
}
