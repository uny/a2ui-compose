package dev.ynagai.a2ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import dev.ynagai.a2ui.core.protocol.Component

/** The main axis of the `Row` or `Column` a component is being laid out in. */
public enum class LayoutAxis { Horizontal, Vertical }

/** How a component takes space along its container's main axis. */
public enum class MainAxisFit {
    /**
     * As much as its content wants, shrinking towards its minimum when the container is short of
     * room -- CSS's `flex: 0 1 auto`. Text, inputs, and containers of them are this: anything with
     * a preferred size of its own.
     */
    Content,

    /**
     * A share of whatever is left once the [Content] children are measured, up to that share --
     * CSS's `flex: 0 1 auto` for something whose preferred size *is* the room it is given. A
     * slider's track, a divider drawn along the axis, an image that fills its container: leaves
     * with no size of their own along that axis, which starve a sibling if measured as content.
     */
    Fill,
}

/**
 * What a `Row` or `Column` has to know about a child to lay it out beside its siblings.
 *
 * Compose measures a child once. A container that wants to share its main axis fairly therefore
 * has to know each child's preferred size *before* measuring it, which it learns by asking --
 * intrinsic measurement -- and a component with no preferred size of its own has nothing to say.
 * [fit] is the renderer's word on which it is. Whether a child *can* be asked is not declared: a
 * `SubcomposeLayout` (a `LazyColumn`, a `BoxWithConstraints`) raises on the question, the
 * container catches that, and measures the child without asking from then on.
 *
 * A renderer that says nothing is [Content]: content-sized, asked its size, and measured as it
 * comes if it cannot answer. That is right for most of what a host draws.
 */
@Immutable
public class LayoutTraits(public val fit: MainAxisFit) {
    // A value, compared by its fields. A container remembers what it knows about each child and
    // recomputes it inside a `derivedStateOf`, which discards an equal result without invalidating
    // anyone; a renderer that builds its traits fresh on every call would otherwise never compare
    // equal, and the container would be rebuilt on every write to the surface.
    override fun equals(other: Any?): Boolean = other is LayoutTraits && other.fit == fit

    override fun hashCode(): Int = fit.hashCode()

    override fun toString(): String = "LayoutTraits(fit=$fit)"

    public companion object {
        /** Content-sized: the default for a renderer that says nothing. */
        public val Content: LayoutTraits = LayoutTraits(MainAxisFit.Content)

        /** Fills its share of the axis. */
        public val Fill: LayoutTraits = LayoutTraits(MainAxisFit.Fill)
    }
}

/**
 * A [ComponentRenderer] that also declares its [LayoutTraits].
 *
 * `ComponentRenderer` is a `fun interface` so that a host can register a lambda; this is the
 * constructor for the case where the renderer has something to say about layout as well. The
 * trailing lambda is still the `fun interface` -- `ComponentRenderer(traits) { scope, modifier ->
 * ... }` -- rather than a composable function type, so that the compiler makes it an anonymous
 * renderer as it does the plain form, and not a `ComposableSingletons` entry whose hashed name
 * would put every edit to a renderer's body into the module's ABI dump.
 */
public fun ComponentRenderer(
    traits: (component: Component, axis: LayoutAxis) -> LayoutTraits,
    render: ComponentRenderer,
): ComponentRenderer = object : ComponentRenderer {
    @Composable
    override fun Render(scope: A2uiComponentScope, modifier: Modifier) = render.Render(scope, modifier)
    override fun layoutTraits(component: Component, axis: LayoutAxis): LayoutTraits = traits(component, axis)
}

/** A [ComponentRenderer] with the same [LayoutTraits] whatever the component or the axis. */
public fun ComponentRenderer(traits: LayoutTraits, render: ComponentRenderer): ComponentRenderer =
    ComponentRenderer({ _, _ -> traits }, render)
