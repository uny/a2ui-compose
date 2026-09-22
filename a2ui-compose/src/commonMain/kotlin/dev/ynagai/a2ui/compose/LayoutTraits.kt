package dev.ynagai.a2ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import dev.ynagai.a2ui.core.protocol.Component
import dev.ynagai.a2ui.core.surface.ChildReference

/** The main axis of the `Row` or `Column` a component is being laid out in. */
public enum class LayoutAxis { Horizontal, Vertical }

/** How a component takes space along its container's main axis. */
public enum class MainAxisFit {
    /**
     * As much as its content wants, shrinking towards its minimum when the container is short of
     * room -- CSS's `flex: 0 1 auto`. Text and containers of text are this.
     */
    Content,

    /**
     * A share of whatever is left once the [Content] children are measured, as though it carried
     * `weight: 1` -- CSS's `flex: 1`. A slider's track, a divider drawn along the axis, an image
     * that fills its container: leaves with no size of their own along that axis, which starve a
     * sibling if measured as content.
     */
    Fill,
}

/**
 * What a `Row` or `Column` has to know about a child to lay it out beside its siblings.
 *
 * Compose measures a child once. A container that wants to share its main axis fairly therefore
 * has to know each child's preferred size *before* measuring it, and the only way to ask is an
 * intrinsic measurement query -- which a `SubcomposeLayout` (a `LazyColumn`, a `BoxWithConstraints`)
 * cannot answer and raises on. [answersIntrinsics] is the renderer's promise that its own layout
 * can; a container trusts it only when every descendant makes the same promise, and otherwise
 * measures that child without asking.
 *
 * A renderer that says nothing gets [Host]: content-sized and not to be asked. That is the safe
 * reading for a renderer this library has not seen, at the cost of the fair share -- a host that
 * knows its renderer is plain Compose layout can say so and get it back.
 */
@Immutable
public class LayoutTraits(
    public val fit: MainAxisFit,
    public val answersIntrinsics: Boolean,
) {
    // A value, compared by its fields. A container remembers what it knows about each child and
    // recomputes it inside a `derivedStateOf`, which discards an equal result without invalidating
    // anyone; a renderer that builds its traits fresh on every call -- `LayoutTraits(fit, false)`
    // in a lambda -- would otherwise never compare equal, and the container would be rebuilt on
    // every write to the surface.
    override fun equals(other: Any?): Boolean =
        other is LayoutTraits && other.fit == fit && other.answersIntrinsics == answersIntrinsics

    override fun hashCode(): Int = fit.hashCode() * 31 + answersIntrinsics.hashCode()

    override fun toString(): String = "LayoutTraits(fit=$fit, answersIntrinsics=$answersIntrinsics)"

    public companion object {
        /** Content-sized, not to be asked for intrinsics: the default for a renderer that says nothing. */
        public val Host: LayoutTraits = LayoutTraits(MainAxisFit.Content, answersIntrinsics = false)

        /** Content-sized and made of plain Compose layout, so a container may ask its size. */
        public val Content: LayoutTraits = LayoutTraits(MainAxisFit.Content, answersIntrinsics = true)

        /** Fills its share of the axis, and may be asked for intrinsics. */
        public val Fill: LayoutTraits = LayoutTraits(MainAxisFit.Fill, answersIntrinsics = true)
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
 * Whether every component in the subtree under [child] answers intrinsic measurement queries.
 *
 * The question a container asks before it queries a child's intrinsic size: the query recurses
 * through every layout beneath, so one `SubcomposeLayout` anywhere in the subtree raises from the
 * container's own measure pass. Answered from the surface's component graph and [registry], not
 * from the composition -- which is why a template child counts once, and why a type the registry
 * cannot draw counts as safe: it will be a placeholder, and the placeholder is plain layout.
 *
 * Bounded by [maxDepth] and a visited set, because the component graph is agent-controlled and
 * may be cyclic; a subtree too deep to walk is reported as not answering.
 */
public fun A2uiComponentScope.answersIntrinsics(
    child: A2uiChild,
    registry: ComponentRegistry,
    maxDepth: Int = 64,
): Boolean {
    val surface = surface ?: return false
    val resolver = renderer.childResolver(surface)
    val visited = HashSet<String>()
    fun walk(id: String, depth: Int): Boolean {
        if (depth > maxDepth || !visited.add(id)) return false
        val component = surface.components[id] ?: return true
        val renderer = registry[component.component] ?: return true
        if (!renderer.layoutTraits(component, LayoutAxis.Horizontal).answersIntrinsics) return false
        val references = runCatching { resolver.childrenOf(component) }.getOrElse { return false }
        return references.all { reference ->
            when (reference) {
                is ChildReference.Single -> walk(reference.id, depth + 1)
                is ChildReference.Fixed -> reference.ids.all { walk(it, depth + 1) }
                is ChildReference.Template -> walk(reference.componentId, depth + 1)
            }
        }
    }
    return walk(child.componentId, 0)
}
