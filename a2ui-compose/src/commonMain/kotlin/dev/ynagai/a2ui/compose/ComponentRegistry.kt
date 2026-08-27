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
public class ComponentRegistry(private val renderers: Map<String, ComponentRenderer>) {
    public operator fun get(component: String): ComponentRenderer? = renderers[component]

    /** The component types this registry can draw. */
    public val types: Set<String> get() = renderers.keys

    /** This registry with [renderers] added, overriding any of the same name. */
    public fun with(renderers: Map<String, ComponentRenderer>): ComponentRegistry =
        ComponentRegistry(this.renderers + renderers)

    public companion object {
        /** A registry that draws nothing. Every component renders as an unknown-type placeholder. */
        public val Empty: ComponentRegistry = ComponentRegistry(emptyMap())
    }
}
