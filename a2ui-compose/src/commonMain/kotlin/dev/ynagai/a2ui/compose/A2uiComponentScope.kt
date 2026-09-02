package dev.ynagai.a2ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.ynagai.a2ui.core.function.EvaluationContext
import dev.ynagai.a2ui.core.function.InvocationContext
import dev.ynagai.a2ui.core.function.evaluate
import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.Action
import dev.ynagai.a2ui.core.protocol.ActionMessage
import dev.ynagai.a2ui.core.protocol.BoundValue
import dev.ynagai.a2ui.core.protocol.Component
import dev.ynagai.a2ui.core.protocol.DataBinding
import dev.ynagai.a2ui.core.protocol.DynamicString
import dev.ynagai.a2ui.core.protocol.DynamicValue
import dev.ynagai.a2ui.core.protocol.RendererToAgentMessage
import dev.ynagai.a2ui.core.surface.ChildReference
import dev.ynagai.a2ui.core.surface.EvaluationScope
import dev.ynagai.a2ui.core.surface.JsonPointer
import dev.ynagai.a2ui.core.surface.SurfaceModel
import dev.ynagai.a2ui.core.surface.iterate
import dev.ynagai.a2ui.core.surface.rebase
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * One component instance being drawn, and everything a renderer needs to draw it.
 *
 * A component is not the same thing as a component instance: a `ChildList` template instantiates
 * its subtree once per item of a bound array, so the same [component] appears here many times, each
 * with a different [evaluationScope]. That scope is what makes a relative path like `name` resolve
 * against `/items/3` rather than the root, and losing it is the usual cause of a nested component
 * rendering empty.
 *
 * **What keeps the derived states below alive is this object's identity, not its equality.**
 * [A2uiComponent] builds exactly one of these per component instance and holds it in a `remember`,
 * so `remember(this, name)` in [rememberString] and its siblings matches on the same instance
 * every time. Do not read the `data` modifier as a promise that an equal scope built elsewhere
 * would hit those caches: [onMessage] is one of the compared components and it holds an
 * indirection allocated inside that `remember`, so two independently constructed scopes never
 * compare equal. The `data` modifier is here for `toString` in test failures and for destructuring;
 * the caching contract is [A2uiComponent]'s.
 *
 * **[budget] is read rather than held, so that the same identity survives a share that moves.**
 * The share a child is handed is the remainder divided by how many children there are, so
 * appending one item to a bound array changes it for every sibling. Held as a value, it would have
 * to be one of the `remember` keys that build this -- and then a one-item append would rebuild
 * every scope in the subtree and discard every derived state under it, which is precisely the
 * granularity this class exists to keep. Read through a function, the scope outlives the move and
 * the reads that care see the new share: [children] runs inside a `derivedStateOf`, which
 * subscribes to whatever this reads and recomputes when it changes.
 */
@ConsistentCopyVisibility
@Stable
public data class A2uiComponentScope internal constructor(
    public val renderer: A2uiRenderer,
    public val surfaceId: String,
    public val component: Component,
    public val evaluationScope: EvaluationScope,
    internal val budget: () -> Int,
    internal val onMessage: (RendererToAgentMessage) -> Unit,
) {
    /**
     * The surface as it stands now.
     *
     * Looked up on every read rather than captured, so that reading it inside a composable or a
     * `derivedStateOf` subscribes to the renderer's state. A captured [SurfaceModel] would be a
     * snapshot that never changes, and every bound property would freeze at its first value.
     */
    public val surface: SurfaceModel? get() = renderer.state.surfaces[surfaceId]

    /** The raw property [name] as the agent sent it, uninterpreted. */
    public fun property(name: String): JsonElement? = component.properties[name]

    /**
     * [bound] evaluated against this scope for rendering, or null when it cannot be read.
     *
     * The rendering half of [dispatch]'s split: [InvocationContext.RENDER], because nothing here
     * was caused by a gesture. Used by the property accessors below and by
     * [checkFailures], which needs the evaluated result rather than a typed property.
     */
    internal fun evaluate(bound: BoundValue): JsonElement? = evaluateCatching(bound)?.getOrNull()

    /**
     * [bound] evaluated for rendering, keeping *how* it failed, or null when there is no surface.
     *
     * The three outcomes are three different things and [evaluate] flattens two of them together.
     * A null return is "there is nothing to evaluate against yet", which is progressive rendering
     * and not a fault. A `Result` that failed is the evaluator raising -- a function that does not
     * exist, an argument of the wrong type, a subject past the limits. Only [checkFailures] needs
     * to tell those apart, because for a validation gate the difference decides whether the gate
     * opens: a rule that raises has not passed, and treating it as absent lets the user re-open a
     * check by feeding it something it cannot evaluate.
     */
    internal fun evaluateCatching(bound: BoundValue): Result<JsonElement>? {
        val context = context(InvocationContext.RENDER) ?: return null
        return runCatching { context.evaluate(bound) }
    }

    private fun context(invocation: InvocationContext): EvaluationContext? {
        val model = surface?.dataModel ?: return null
        // A chain rather than named arguments: `EvaluationContext`'s constructor is deliberately
        // not its compatibility surface, so everything past `dataModel` arrives this way. `json`
        // is not set -- its default is already `A2uiJson.strict`, which is what this passed.
        return EvaluationContext(model)
            .inScope(evaluationScope)
            .withLocale(renderer.locale)
            .withInvocation(invocation)
            .withUrlOpener(renderer.urlOpener)
            .withLimits(renderer.evaluationLimits)
    }

    /**
     * Property [name] resolved to a value, or null when it is absent or cannot be read.
     *
     * A property that will not decode resolves to null rather than raising. The agent chooses these
     * payloads, and one malformed property should cost its own widget rather than the surface --
     * the specification asks renderers to degrade, and [CatalogValidator][dev.ynagai.a2ui.core.validation.CatalogValidator]
     * is where a caller that wants to know says so.
     */
    public fun value(name: String): JsonElement? {
        val raw = property(name) ?: return null
        val decoded = runCatching {
            A2uiJson.strict.decodeFromJsonElement(DynamicValue.serializer(), raw)
        }.getOrNull() ?: return null
        return when (decoded) {
            is DynamicValue.Literal -> decoded.value
            is BoundValue -> {
                val context = context(InvocationContext.RENDER) ?: return null
                runCatching { context.evaluate(decoded) }.getOrNull()
            }
            else -> null
        }
    }

    /** Property [name] as a string. A bound value is resolved and then read as text. */
    public fun string(name: String): String? = property(name)?.let(::dynamicString)

    /**
     * [element] read as a `DynamicString` and resolved against this scope.
     *
     * The same reading [string] gives a property, for a `DynamicString` that is *not* one. The
     * catalog nests them: a `ChoicePicker`'s `options[].label` is a `DynamicString` inside an array
     * inside a property, so a renderer that walks into a structured property still needs a way to
     * resolve what it finds there.
     */
    public fun dynamicString(element: JsonElement): String? {
        val decoded = runCatching {
            A2uiJson.strict.decodeFromJsonElement(DynamicString.serializer(), element)
        }.getOrNull()
        return when (decoded) {
            is DynamicString.Literal -> decoded.value
            is BoundValue -> evaluate(decoded)?.asText()
            else -> null
        }
    }

    /**
     * Property [name] as a number.
     *
     * `doubleOrNull` reads the primitive's text, so a data model holding `"42"` where the catalog
     * types a number still resolves -- the leniency the specification asks for when the agent's
     * payload and its catalog disagree about a scalar's type.
     */
    public fun number(name: String): Double? = (value(name) as? JsonPrimitive)?.doubleOrNull

    /** Property [name] as a boolean. */
    public fun boolean(name: String): Boolean? = value(name)?.let {
        (it as? JsonPrimitive)?.booleanOrNull
    }

    /** Property [name] as a list of strings. */
    public fun stringList(name: String): List<String>? =
        (value(name) as? JsonArray)?.mapNotNull { it.asText() }

    /**
     * Property [name] as an action, ready to hand back to [dispatch].
     *
     * Structure rather than a value, so it is read straight off the component instead of through
     * [value]: an `Action` is a `functionCall` or an `event` the agent wrote out, not something a
     * data binding resolves to. An action that will not decode reads as null, and the widget
     * carrying it draws without a handler -- the same degradation an unreadable property gets,
     * for the same reason.
     */
    public fun action(name: String): Action? {
        val raw = property(name) ?: return null
        return runCatching {
            A2uiJson.strict.decodeFromJsonElement(Action.serializer(), raw)
        }.getOrNull()
    }

    /**
     * The children [component] carries under [name], with templates already expanded.
     *
     * A [ChildReference.Template] becomes one entry per item of the array it is bound to, each in
     * its own collection scope, which is what a nested component's relative paths resolve against.
     *
     * Bounded by this instance's share of the surface's instance budget -- see [expansion]. An
     * entry standing for children the budget did not reach draws as a
     * [A2uiPlaceholderReason.TooManyChildren] placeholder when a container passes it to
     * [RenderChild], so a shortened list is visible rather than silent.
     */
    public fun children(name: String): List<A2uiChild> =
        expansion().filter { it.property == name }

    /** Every child, in the order the catalog's schema puts them. */
    public fun allChildren(): List<A2uiChild> = expansion()

    /**
     * Every child this instance may draw, with what is left of its budget divided among them.
     *
     * **The budget is divided rather than counted down.** How many instances a template yields is
     * the agent's data model talking, so the estimate a renderer takes before it descends cannot
     * know it -- a row template over a hundred items, each holding a cell template over another
     * hundred, is four instances of structure and ten thousand of composition. A counter that
     * noticed would notice too late: composition is not a traversal this library drives, and the
     * instances would already exist by the time any total was read. Giving each child a share of
     * what is left means no subtree can spend more than it was handed, whatever the data says.
     *
     * The division is even, and deliberately so: which sibling deserves more is a question about
     * the content, and answering it wrong for an adversarial payload is how the bound gets lost.
     * A container of two whose second child is a longer list than its share loses that list's
     * tail, and says so.
     *
     * Every reference is expanded together rather than one property at a time, because the share
     * is a fraction of the whole -- a container reading `children("a")` and `children("b")`
     * separately must not be handed the budget twice.
     */
    private fun expansion(): List<A2uiChild> {
        val surface = surface ?: return emptyList()
        val references = runCatching { renderer.childResolver(surface).childrenOf(component) }
            .getOrDefault(emptyList())
        if (references.isEmpty()) return emptyList()
        val wanted = references.map { it.size(surface) }
        val total = wanted.sumOf { it.toLong() }
        // One instance for this component, and what is left goes to the children. Clamped before
        // the subtraction rather than after it: `budget` arrives from a public parameter, and
        // `Int.MIN_VALUE - 1` wraps to `Int.MAX_VALUE`, which coercing afterwards would read as
        // room for everything -- the one input that turns the bound into its opposite.
        val handed = budget()
        val room = if (handed <= 1) 0 else handed - 1
        // The entries reporting what was cut are themselves instances, and a bound that did not
        // pay for them would be a bound that overspends by however many references a component
        // carries. Reserved before the rest is divided; when even the reserve does not fit, some
        // truncation goes unreported rather than unpaid, because the count is what has to hold.
        var markers = if (total > room) minOf(references.size, room) else 0
        val spendable = room - markers
        var left = minOf(total, spendable.toLong()).toInt()
        // `left` cannot exceed `spendable`, so every child that is kept gets a share of at least
        // one -- an instance with nothing to spend draws itself and stops, which is the point.
        val share = if (left > 0) spendable / left else 0
        val out = mutableListOf<A2uiChild>()
        references.forEachIndexed { index, reference ->
            val want = wanted[index]
            val take = minOf(want, left)
            left -= take
            out += reference.expand(take, share)
            if (take < want && markers > 0) {
                markers--
                out += A2uiChild("", EvaluationScope.Root, reference.property, 0, want - take)
            }
        }
        return out
    }

    /** How many instances this reference asks for, before any budget is applied. */
    private fun ChildReference.size(surface: SurfaceModel): Int = when (this) {
        is ChildReference.Single -> 1
        is ChildReference.Fixed -> ids.size
        is ChildReference.Template -> {
            val bound = runCatching { surface.read(path, evaluationScope) }.getOrNull()
            (bound as? JsonArray)?.size ?: 0
        }

        else -> 0
    }

    /** The first [take] instances of this reference, each carrying a budget of [share]. */
    private fun ChildReference.expand(take: Int, share: Int): List<A2uiChild> = when (this) {
        is ChildReference.Single ->
            if (take > 0) listOf(A2uiChild(id, evaluationScope, property, share)) else emptyList()

        is ChildReference.Fixed ->
            ids.take(take).map { A2uiChild(it, evaluationScope, property, share) }

        is ChildReference.Template -> (0 until take).map { index ->
            A2uiChild(componentId, evaluationScope.iterate(path, index), property, share)
        }

        else -> emptyList()
    }

    /**
     * Runs [action], which is what a `Button` does when tapped.
     *
     * The gesture's authority stops at the call the gesture makes. An [Action.Invoke]'s
     * `functionCall` runs with [InvocationContext.USER_ACTION], because `openUrl` is required to
     * refuse an invocation that no user gesture caused and that distinction is carried by this
     * parameter rather than inferred. An [Action.Event]'s `context` and `userMessage` do not: they
     * are bindings read to describe the event, which is the "dynamic data binding evaluation" the
     * specification puts on the far side of that line.
     */
    public fun dispatch(action: Action) {
        when (action) {
            is Action.Invoke -> {
                val context = context(InvocationContext.USER_ACTION) ?: return
                runCatching { context.evaluate(action.functionCall) }
            }

            is Action.Event -> {
                val event = action.event
                // RENDER, not USER_ACTION. These are values being *read* to describe the event, and
                // the specification puts "dynamic data binding evaluation" on the side of the line
                // `openUrl` must refuse. Evaluating them with user-action authority also handed
                // each field its own evaluator, and so its own "one open per expression" budget:
                // an event with three `openUrl` calls in its context opened three windows from one
                // tap, which is the popup flood that budget exists to stop.
                val resolved = event.context.orEmpty().mapValues { (_, bound) ->
                    val context = context(InvocationContext.RENDER)
                    when {
                        context == null -> JsonNull
                        bound is DynamicValue.Literal -> bound.value
                        bound is BoundValue -> runCatching { context.evaluate(bound) }.getOrDefault(JsonNull)
                        else -> JsonNull
                    }
                }
                onMessage(
                    ActionMessage(
                        name = event.name,
                        surfaceId = surfaceId,
                        sourceComponentId = component.id,
                        timestamp = renderer.clock.nowIso8601(),
                        context = JsonObject(resolved),
                        userMessage = event.userMessage?.let { resolveDynamicString(it) },
                    ),
                )
            }

            else -> Unit
        }
    }

    /** A bound `userMessage`, read the same way and for the same reason as an event's context. */
    private fun resolveDynamicString(value: DynamicString): String? = when (value) {
        is DynamicString.Literal -> value.value
        is BoundValue -> context(InvocationContext.RENDER)
            ?.let { runCatching { it.evaluate(value) }.getOrNull() }
            ?.asText()

        else -> null
    }

    /** Writes [value] at [pointer], resolved against this scope -- two-way binding's write half. */
    public fun write(pointer: JsonPointer, value: JsonElement) {
        renderer.write(surfaceId, evaluationScope.rebase(pointer), value)
    }

    /**
     * The absolute pointer property [name] binds to, or null when it is not a data binding.
     *
     * An input needs this rather than the resolved value: to write back it has to know where, and
     * a component whose `value` is a literal or a function result has nowhere to write.
     */
    public fun binding(name: String): JsonPointer? {
        val raw = property(name) ?: return null
        val decoded = runCatching {
            A2uiJson.strict.decodeFromJsonElement(DynamicValue.serializer(), raw)
        }.getOrNull()
        val path = (decoded as? DataBinding)?.path ?: return null
        val pointer = runCatching { JsonPointer.parse(path) }.getOrNull() ?: return null
        return evaluationScope.rebase(pointer)
    }
}

/**
 * A child to draw: which component, and the scope its bound paths resolve against.
 *
 * Or, when [dropped] is positive, a stand-in for children the budget did not reach. It rides in
 * the list rather than being reported beside it so that a container built the ordinary way --
 * `rememberChildren` into [RenderChild] -- shows the shortfall without its author doing anything.
 */
@ConsistentCopyVisibility
@Stable
public data class A2uiChild internal constructor(
    public val componentId: String,
    public val evaluationScope: EvaluationScope,
    // After the two public properties on purpose: `component1`/`component2` are part of the
    // published surface, so putting a new one first would silently re-point every destructuring a
    // host had written.
    internal val property: String,
    internal val budget: Int,
    internal val dropped: Int = 0,
)

/**
 * Property [name] as a string, recomposing the caller only when the resolved text changes.
 *
 * The `derivedStateOf` is the whole point. Every scope reads the same [A2uiRenderer.state], so any
 * write to the data model invalidates every component that touches it. Wrapping the resolved value
 * means the read below only re-runs the caller when *this* property's value is different, which is
 * what the binder layer would otherwise have bought.
 */
@Composable
public fun A2uiComponentScope.rememberString(name: String): String? {
    val value by remember(this, name) { derivedStateOf { string(name) } }
    return value
}

/** Property [name] as a number, recomposing the caller only when it changes. */
@Composable
public fun A2uiComponentScope.rememberNumber(name: String): Double? {
    val value by remember(this, name) { derivedStateOf { number(name) } }
    return value
}

/** Property [name] as a boolean, recomposing the caller only when it changes. */
@Composable
public fun A2uiComponentScope.rememberBoolean(name: String): Boolean? {
    val value by remember(this, name) { derivedStateOf { boolean(name) } }
    return value
}

/** Property [name] as a list of strings, recomposing the caller only when it changes. */
@Composable
public fun A2uiComponentScope.rememberStringList(name: String): List<String>? {
    val value by remember(this, name) { derivedStateOf { stringList(name) } }
    return value
}

/**
 * Property [name] as an action, recomposing the caller only when the action changes.
 *
 * Wrapped like the value accessors even though an action is structure rather than a binding: the
 * read still goes through [A2uiComponentScope.property], and a widget that read it bare would
 * recompose on every write to the surface it sits in.
 */
@Composable
public fun A2uiComponentScope.rememberAction(name: String): Action? {
    val value by remember(this, name) { derivedStateOf { action(name) } }
    return value
}

/** The children under [name], recomposing the caller only when the expansion changes. */
@Composable
public fun A2uiComponentScope.rememberChildren(name: String): List<A2uiChild> {
    val value by remember(this, name) { derivedStateOf { children(name) } }
    return value
}

/** Every child, recomposing the caller only when the expansion changes. */
@Composable
public fun A2uiComponentScope.rememberAllChildren(): List<A2uiChild> {
    val value by remember(this) { derivedStateOf { allChildren() } }
    return value
}

/** A JSON value as the text a widget would show, without quoting a string that already is one. */
internal fun JsonElement.asText(): String? = when (this) {
    is JsonPrimitive -> if (this is JsonNull) null else contentOrNull
    else -> null
}

