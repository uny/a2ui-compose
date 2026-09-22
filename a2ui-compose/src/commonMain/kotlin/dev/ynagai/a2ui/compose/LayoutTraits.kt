package dev.ynagai.a2ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import dev.ynagai.a2ui.core.protocol.Component
import dev.ynagai.a2ui.core.protocol.ComponentId
import dev.ynagai.a2ui.core.surface.ChildReference
import dev.ynagai.a2ui.core.surface.RenderLimits
import dev.ynagai.a2ui.core.surface.SurfaceModel

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

/**
 * What [child] and everything it wraps say, together, about how it fits [axis].
 *
 * A renderer speaks for its own component, and a wrapper -- a `Card`, a `Column` holding one thing
 * -- has no size of its own to declare. Its renderer says [MainAxisFit.Content] truthfully and the
 * container then asks it a question its content cannot answer: a card around an image that fills
 * whatever it is given reports the width of its own padding, sixty-four pixels of margin around
 * nothing, and is measured to it. The image inside draws at no width at all, which is the
 * starvation this all exists to end, one level down from where the container was looking.
 *
 * So a wrapper that declares nothing of its own, and whose children all fill, fills. A card of a
 * banner asks for a share; a card of a banner *and a caption* does not, because the caption has a
 * preferred width and the card can be asked for it. The walk stops at the first child that is
 * content-sized, at a child that declares [MainAxisFit.Fill] itself, and at the depth the renderer
 * itself stops drawing at ([RenderLimits.maxDepth]) -- the same wrapper nested deeper than the
 * surface will draw cannot matter, and a component graph that leads back to itself is caught by
 * the visited set rather than by the depth. A cap of its own would be a second, smaller limit: a
 * banner under nine cards would report the ninth card's padding and starve the text beside it,
 * which is this bug again at a depth nobody thought to look, levels
 * -- which also ends the cycle an agent can build, the same bound `A2uiSurface` draws with.
 *
 * Children are resolved through the surface's catalog, not by property name, so this holds for a
 * host's own wrapper as it does for the ones this library ships.
 */
public fun A2uiComponentScope.layoutTraitsOf(
    child: A2uiChild,
    registry: ComponentRegistry,
    axis: LayoutAxis,
): LayoutTraits {
    val surface = surface ?: return LayoutTraits.Content
    return traitsOf(child.componentId, registry, axis, surface, depth = 0, seen = HashSet())
}

private fun A2uiComponentScope.traitsOf(
    id: ComponentId,
    registry: ComponentRegistry,
    axis: LayoutAxis,
    surface: SurfaceModel,
    depth: Int,
    seen: MutableSet<ComponentId>,
): LayoutTraits {
    // A component the surface does not hold, or a type the registry cannot draw, is a placeholder:
    // plain layout, content-sized.
    val component = surface.components[id] ?: return LayoutTraits.Content
    val declared = registry[component.component]?.layoutTraits(component, axis) ?: return LayoutTraits.Content
    // A cycle, or deeper than the renderer will draw: the declaration is all there is to go on.
    if (declared.fit == MainAxisFit.Fill || depth >= renderer.renderLimits.maxDepth || !seen.add(id)) {
        return declared
    }
    val children = runCatching { renderer.childResolver(surface).childrenOf(component) }
        .getOrDefault(emptyList())
        .flatMap { reference ->
            when (reference) {
                is ChildReference.Single -> listOf(reference.id)
                is ChildReference.Fixed -> reference.ids
                is ChildReference.Template -> listOf(reference.componentId)
            }
        }
    if (children.isEmpty()) return declared
    val all = children.all { traitsOf(it, registry, axis, surface, depth + 1, seen).fit == MainAxisFit.Fill }
    return if (all) LayoutTraits.Fill else declared
}
