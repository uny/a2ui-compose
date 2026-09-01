package dev.ynagai.a2ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier

/**
 * Draws one kind of component.
 *
 * The [Modifier] is passed in rather than built inside, because the parent decides how a child sits
 * in its layout -- a `Row` giving a child a weight cannot do so through the catalog.
 */
public fun interface ComponentRenderer {
    @Composable
    public fun Render(scope: A2uiComponentScope, modifier: Modifier)
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
     * **One shape to avoid in a renderer you write here: Material 3's non-interactive `Surface`
     * drawing its children through [RenderChild].** On Kotlin/Native -- macOS and iOS, never JVM
     * or either web target -- a component of that shape *arriving* in an update, replacing whatever
     * held its id before, segfaults inside `AtomicInt.compareAndSet` with no unwindable stack:
     * nothing raises, nothing is reported, the process is simply gone. `a2ui-material3`'s own `CardRenderer` was rebuilt off `OutlinedCard`
     * onto a bordered `Box` for exactly this, and its note carries the conditions and the
     * reproductions. A host renderer is now the only way back into it, and a `Modal`'s content is
     * where it would land least visibly, so it is written down here rather than only there.
     */
    public fun with(renderers: Map<String, ComponentRenderer>): ComponentRegistry =
        ComponentRegistry(this.renderers + renderers)

    public companion object {
        /** A registry that draws nothing. Every component renders as an unknown-type placeholder. */
        public val Empty: ComponentRegistry = ComponentRegistry(emptyMap())
    }
}
