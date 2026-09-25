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

/**
 * How a component takes space along one axis.
 *
 * A container asks it of each child twice: along its own main axis, to share that axis out, and
 * across it, to decide what `align: stretch` may do to the child.
 */
public enum class AxisFit {
    /**
     * As much as its content wants. Along a main axis it shrinks towards its minimum when the
     * container is short of room -- CSS's `flex: 0 1 auto`; across one it is stretched to the
     * line, as CSS stretches an item whose cross size is `auto`. Text, inputs, and containers of
     * them are this: anything with a preferred size of its own.
     */
    Content,

    /**
     * Whatever room it is given. Along a main axis, a share of what is left once the [Content]
     * children are measured, up to that share -- CSS's `flex: 0 1 auto` for something whose
     * preferred size *is* the room it is given; across one, up to the line and never beyond it,
     * with no say in how tall or wide the line is -- unless it is weighted in a row, where its
     * width is its share before it is drawn and what it needs at that width counts towards the
     * row's height, or a column spreading its children would spill. A slider's track, a divider
     * drawn along the axis, an image that fills its container: leaves with no size of their own
     * along that axis, which starve a sibling if measured as content.
     */
    Fill,

    /**
     * A size of its own that nothing around it changes -- an icon's square, a banner's height, a
     * divider's thickness. Along a main axis it is [Content]; across one it counts towards the
     * line and is not stretched to it, as CSS leaves an item whose cross size is definite. A
     * stretched avatar is a pill, and a stretched glyph sits in the middle of a box it does not
     * fill.
     */
    Fixed,
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
 * A renderer that says nothing is [Content]: content-sized, asked its size, measured as it comes
 * if it cannot answer, and stretched across a container that stretches. That is right for most of
 * what a host draws; a host component of a fixed size says [Fixed] across the axis it is fixed on.
 */
@Immutable
public class LayoutTraits(public val fit: AxisFit) {
    // A value, compared by its fields. A container remembers what it knows about each child and
    // recomputes it inside a `derivedStateOf`, which discards an equal result without invalidating
    // anyone; a renderer that builds its traits fresh on every call would otherwise never compare
    // equal, and the container would be rebuilt on every write to the surface.
    override fun equals(other: Any?): Boolean = other is LayoutTraits && other.fit == fit

    override fun hashCode(): Int = fit.hashCode()

    override fun toString(): String = "LayoutTraits(fit=$fit)"

    public companion object {
        /** Content-sized: the default for a renderer that says nothing. */
        public val Content: LayoutTraits = LayoutTraits(AxisFit.Content)

        /** Fills its share of the axis. */
        public val Fill: LayoutTraits = LayoutTraits(AxisFit.Fill)

        /** A size of its own along the axis, which stretching leaves alone. */
        public val Fixed: LayoutTraits = LayoutTraits(AxisFit.Fixed)
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
 * -- has no size of its own to declare. Its renderer says [AxisFit.Content] truthfully and the
 * container then asks it a question its content cannot answer: a card around an image that fills
 * whatever it is given reports the width of its own padding, sixty-four pixels of margin around
 * nothing, and is measured to it. The image inside draws at no width at all, which is the
 * starvation this all exists to end, one level down from where the container was looking.
 *
 * So a wrapper whose children all fill, fills. A card of a banner asks for a share; a card of a
 * banner *and a caption* does not, because the caption has a preferred width and the card can be
 * asked for it. That holds for a renderer that declared [AxisFit.Content] as much as for one
 * that said nothing -- the two are the same value -- so a host wrapper of a fixed size around
 * only filling children is laid out as a filler too: measured to a share of what its
 * content-sized siblings leave, which beside a long text can be less than its own size. One that
 * declares [AxisFit.Fixed] is taken at its word.
 *
 * The walk stops at the first child that is content-sized, at a child that declares
 * [AxisFit.Fill] or [AxisFit.Fixed] itself -- a wrapper that says it is fixed is, whatever it
 * holds -- and [RenderLimits.maxDepth] levels below [child], the renderer's own
 * bound -- a cap smaller than the surface draws would be this bug again, at a depth nobody thought
 * to look. A component reached twice at the same depth, under two parents, is walked once and
 * answers the same both times -- at another depth it is walked again, since the bound may cut it
 * there; one that leads back to one of its own ancestors is a cycle, and stops the walk there
 * with its declaration.
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
    return traitsOf(child.componentId, registry, axis, surface, depth = 0, walking = HashSet(), walked = HashMap())
}

private fun A2uiComponentScope.traitsOf(
    id: ComponentId,
    registry: ComponentRegistry,
    axis: LayoutAxis,
    surface: SurfaceModel,
    depth: Int,
    walking: MutableSet<ComponentId>,
    walked: MutableMap<Pair<ComponentId, Int>, LayoutTraits>,
): LayoutTraits {
    walked[id to depth]?.let { return it }
    // A component the surface does not hold, or a type the registry cannot draw, is a placeholder:
    // plain layout, content-sized.
    val component = surface.components[id] ?: return LayoutTraits.Content
    val declared = registry[component.component]?.layoutTraits(component, axis) ?: return LayoutTraits.Content
    // One of its own ancestors, or deeper than the renderer will draw: the declaration is all
    // there is to go on. `walking` is the path down to here, not everything seen so far: a
    // component shared by two parents is not a cycle, and `walked` answers it the second time --
    // keyed by depth too, because the same component nearer the bound may be cut short of the
    // filler it reaches from higher up.
    if (declared.fit != AxisFit.Content || depth >= renderer.renderLimits.maxDepth || !walking.add(id)) {
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
    val all = children.isNotEmpty() &&
        children.all { traitsOf(it, registry, axis, surface, depth + 1, walking, walked).fit == AxisFit.Fill }
    walking.remove(id)
    return (if (all) LayoutTraits.Fill else declared).also { walked[id to depth] = it }
}
