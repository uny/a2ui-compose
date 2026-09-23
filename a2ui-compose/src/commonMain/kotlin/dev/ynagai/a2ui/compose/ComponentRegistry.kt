package dev.ynagai.a2ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import dev.ynagai.a2ui.core.protocol.Component

/**
 * Draws one kind of component.
 *
 * The [Modifier] is passed in rather than built inside, because the parent decides how a child sits
 * in its layout -- a `Row` giving a child a weight cannot do so through the catalog.
 *
 * **Hand over the same instance every time.** [A2uiComponent] keys its call to [Render] on the
 * renderer instance (#31), so a renderer rebuilt on every recomposition -- `ComponentRenderer { }`
 * written inline in a composable without `remember` -- starts from nothing each time: whatever it
 * and everything under it `remember`ed, focus and scroll position included, is dropped. A top-level
 * `val`, or one built once and held, keeps it.
 */
public fun interface ComponentRenderer {
    @Composable
    public fun Render(scope: A2uiComponentScope, modifier: Modifier)

    /**
     * How a component of this kind sits along the main axis of the `Row` or `Column` holding it.
     *
     * [LayoutTraits.Content] unless overridden, which is the reading a lambda registered by a host
     * gets. See [LayoutTraits] for what the container does with the answer, and the
     * `ComponentRenderer(traits, render)` constructors for declaring one without spelling out an
     * object.
     */
    public fun layoutTraits(component: Component, axis: LayoutAxis): LayoutTraits = LayoutTraits.Content
}

/**
 * Which [ComponentRenderer] draws each component type.
 *
 * Keyed by the component's name alone, not by catalog. Two catalogs defining a `Button` would
 * collide, and the specification's own catalogs already share `Text`. That is a real limit rather
 * than an oversight, and it is recorded here so the next reader does not have to rediscover it:
 * multi-catalog surfaces are not yet a case this library handles, and widening the key is the fix
 * when they become one.
 */
@Immutable
public class ComponentRegistry(renderers: Map<String, ComponentRenderer>) {
    // Copied rather than retained. `Map` is a read-only view, not an immutable type, so a caller
    // may hand over a `MutableMap` and keep mutating it. `@Immutable` promises Compose the
    // opposite, and `LocalA2uiRegistry` is a *static* composition local, so a mutation would
    // change lookups with nothing invalidated -- a subtree left drawing a stale renderer, or a
    // placeholder for a type the registry now knows, and no error either way.
    private val renderers: Map<String, ComponentRenderer> = renderers.toMap()

    public operator fun get(component: String): ComponentRenderer? = renderers[component]

    /** The component types this registry can draw. */
    public val types: Set<String> get() = renderers.keys

    /**
     * This registry with [renderers] added, overriding any of the same name.
     *
     * **A shape that used to crash, and no longer should.** On Kotlin/Native -- macOS and iOS,
     * never JVM or either web target -- a renderer that handed a capturing content lambda to a
     * composable (Material 3's `Surface` drawing its children through [RenderChild] is the shape
     * it was met in) could segfault when it *arrived* in an update, replacing a renderer of another
     * type that had `remember`ed something. Nothing raised and nothing was reported; the process
     * was simply gone. What it needed was the `fun interface` implementation behind one call site
     * changing, not anything the renderer itself does wrong, and [A2uiComponent] now keys that
     * call on the renderer, which `RendererSwapTest` pins (#31). `a2ui-material3`'s own
     * `CardRenderer` still draws a bordered `Box` from before that fix.
     */
    public fun with(renderers: Map<String, ComponentRenderer>): ComponentRegistry =
        ComponentRegistry(this.renderers + renderers)

    public companion object {
        /** A registry that draws nothing. Every component renders as an unknown-type placeholder. */
        public val Empty: ComponentRegistry = ComponentRegistry(emptyMap())
    }
}
