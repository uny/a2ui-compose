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
 * A data class so that `remember` keys built from it compare by value; without that, every
 * recomposition would rebuild the derived states below.
 */
@Stable
public data class A2uiComponentScope internal constructor(
    public val renderer: A2uiRenderer,
    public val surfaceId: String,
    public val component: Component,
    public val evaluationScope: EvaluationScope,
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

    private fun context(invocation: InvocationContext): EvaluationContext? {
        val model = surface?.dataModel ?: return null
        return EvaluationContext(
            dataModel = model,
            scope = evaluationScope,
            locale = renderer.locale,
            invocation = invocation,
            urlOpener = renderer.urlOpener,
            limits = renderer.evaluationLimits,
            json = A2uiJson.strict,
        )
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
    public fun string(name: String): String? {
        val raw = property(name) ?: return null
        val decoded = runCatching {
            A2uiJson.strict.decodeFromJsonElement(DynamicString.serializer(), raw)
        }.getOrNull()
        return when (decoded) {
            is DynamicString.Literal -> decoded.value
            is BoundValue -> {
                val context = context(InvocationContext.RENDER) ?: return null
                runCatching { context.evaluate(decoded) }.getOrNull()?.asText()
            }
            else -> null
        }
    }

    /** Property [name] as a number. */
    public fun number(name: String): Double? = value(name)?.let {
        (it as? JsonPrimitive)?.doubleOrNull ?: it.contentOrNullSafe()?.toDoubleOrNull()
    }

    /** Property [name] as a boolean. */
    public fun boolean(name: String): Boolean? = value(name)?.let {
        (it as? JsonPrimitive)?.booleanOrNull
    }

    /** Property [name] as a list of strings. */
    public fun stringList(name: String): List<String>? =
        (value(name) as? JsonArray)?.mapNotNull { it.asText() }

    /**
     * The children [component] carries under [name], with templates already expanded.
     *
     * A [ChildReference.Template] becomes one entry per item of the array it is bound to, each in
     * its own collection scope, which is what a nested component's relative paths resolve against.
     */
    public fun children(name: String): List<A2uiChild> {
        val surface = surface ?: return emptyList()
        val references = runCatching { renderer.childResolver(surface).childrenOf(component) }
            .getOrDefault(emptyList())
        return references.filter { it.property == name }.flatMap { it.expand(surface) }
    }

    /** Every child, in the order the catalog's schema puts them. */
    public fun allChildren(): List<A2uiChild> {
        val surface = surface ?: return emptyList()
        val references = runCatching { renderer.childResolver(surface).childrenOf(component) }
            .getOrDefault(emptyList())
        return references.flatMap { it.expand(surface) }
    }

    private fun ChildReference.expand(surface: SurfaceModel): List<A2uiChild> = when (this) {
        is ChildReference.Single -> listOf(A2uiChild(id, evaluationScope))
        is ChildReference.Fixed -> ids.map { A2uiChild(it, evaluationScope) }
        is ChildReference.Template -> {
            val bound = runCatching { surface.read(path, evaluationScope) }.getOrNull()
            (bound as? JsonArray).orEmpty().indices.map { index ->
                A2uiChild(componentId, evaluationScope.iterate(path, index))
            }
        }

        else -> emptyList()
    }

    /**
     * Runs [action], which is what a `Button` does when tapped.
     *
     * [InvocationContext.USER_ACTION] rather than `RENDER`: `openUrl` is required to refuse an
     * invocation that no user gesture caused, and that distinction is carried by this parameter
     * rather than inferred.
     */
    public fun dispatch(action: Action) {
        when (action) {
            is Action.Invoke -> {
                val context = context(InvocationContext.USER_ACTION) ?: return
                runCatching { context.evaluate(action.functionCall) }
            }

            is Action.Event -> {
                val event = action.event
                val resolved = event.context.orEmpty().mapValues { (_, bound) ->
                    val context = context(InvocationContext.USER_ACTION)
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

    private fun resolveDynamicString(value: DynamicString): String? = when (value) {
        is DynamicString.Literal -> value.value
        is BoundValue -> context(InvocationContext.USER_ACTION)
            ?.let { runCatching { it.evaluate(value) }.getOrNull() }
            ?.asText()

        else -> null
    }

    /** Writes [value] at [pointer], resolved against this scope -- two-way binding's write half. */
    public fun write(pointer: JsonPointer, value: JsonElement) {
        renderer.write(surfaceId, evaluationScope.rebasedFrom(pointer), value)
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
        return evaluationScope.rebasedFrom(pointer)
    }
}

/** A child to draw: which component, and the scope its bound paths resolve against. */
@Stable
public data class A2uiChild internal constructor(
    public val componentId: String,
    public val evaluationScope: EvaluationScope,
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

/**
 * [pointer] made absolute against this scope.
 *
 * A relative pointer inside a template instance means "within the item being rendered". An absolute
 * one means what it says, wherever it appears.
 */
internal fun EvaluationScope.rebasedFrom(pointer: JsonPointer): JsonPointer =
    if (pointer.isAbsolute) pointer else base.resolve(pointer)

/** A JSON value as the text a widget would show, without quoting a string that already is one. */
internal fun JsonElement.asText(): String? = when (this) {
    is JsonPrimitive -> if (this is JsonNull) null else contentOrNull
    else -> null
}

private fun JsonElement.contentOrNullSafe(): String? = (this as? JsonPrimitive)?.contentOrNull
