package dev.ynagai.a2ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import dev.ynagai.a2ui.core.protocol.RendererToAgentMessage
import dev.ynagai.a2ui.core.protocol.Surface
import dev.ynagai.a2ui.core.surface.DEFAULT_MAX_DEPTH
import dev.ynagai.a2ui.core.surface.EvaluationScope

/**
 * How deep the tree may nest before rendering stops.
 *
 * Core's own depth bound rather than a second copy of the number: [A2uiComponent]'s guard and
 * [dev.ynagai.a2ui.core.surface.walk]'s have to agree, and two `const val`s holding 256 agree only
 * until someone changes one of them.
 *
 * **Depth is not the only bound core carries, and it is not the one that stops the worst payload.**
 * `walk` also refuses past [dev.ynagai.a2ui.core.surface.DEFAULT_WALK_LIMIT] *instances*, because
 * the adjacency list is a graph: n layers of components that each name the same two children
 * expand to 2^n instances from 2n components, and neither the depth guard nor the cycle guard
 * bounds that -- no path repeats an id and none of them is deep. This composition has no instance
 * budget, so that bound does not exist on the path that actually draws. See the review note on
 * this PR; closing it needs a budget carried down the descent, which is more than a constant.
 */
public const val MAX_RENDER_DEPTH: Int = DEFAULT_MAX_DEPTH

/**
 * The id the specification reserves for a surface's root component.
 *
 * Core's constant rather than a second literal: [dev.ynagai.a2ui.core.surface.SurfaceModel.isRenderable]
 * decides a surface is drawable by looking for [Surface.ROOT_ID], and this is where the drawing
 * starts. If the two ever named different ids, every surface would pass `isRenderable` and then
 * render as a `MissingComponent` placeholder -- a blank screen, with nothing reporting why.
 */
public const val ROOT_COMPONENT_ID: String = Surface.ROOT_ID

/**
 * What a component that cannot be drawn leaves behind.
 *
 * Every case here is one the specification says to survive rather than fail: a reference to a
 * component that has not arrived yet is progressive rendering working as intended, and a component
 * type this registry does not know is a catalog the host has not fully implemented. Making them
 * visible is the point -- a renderer that drew nothing would be indistinguishable from one that
 * drew correctly and had nothing to show.
 */
public fun interface A2uiPlaceholder {
    @Composable
    public fun Render(reason: A2uiPlaceholderReason, modifier: Modifier)
}

/** Why a component could not be drawn. */
@Immutable
public sealed interface A2uiPlaceholderReason {
    /** The surface has no component with this id -- it may still arrive. */
    public data class MissingComponent(public val componentId: String) : A2uiPlaceholderReason

    /** The registry has no renderer for this component type. */
    public data class UnknownType(public val componentId: String, public val component: String) :
        A2uiPlaceholderReason

    /** Drawing this would revisit a component already on the path from the root. */
    public data class Cycle(public val componentId: String) : A2uiPlaceholderReason

    /** The tree nests deeper than [MAX_RENDER_DEPTH]. */
    public data class TooDeep(public val componentId: String) : A2uiPlaceholderReason
}

/**
 * The registry in force for the subtree being drawn.
 *
 * Carried in the composition rather than passed down by hand. A container's renderer has to draw
 * its children, and threading the registry through every one of them makes forgetting it the
 * default -- a container that dropped it would render its subtree against whatever the caller
 * happened to pass, or against nothing.
 */
public val LocalA2uiRegistry: ProvidableCompositionLocal<ComponentRegistry> =
    staticCompositionLocalOf { ComponentRegistry.Empty }

/** The placeholder in force for the subtree being drawn. Carried alongside the registry, and for
 * the same reason: a container that failed to pass it on would silence exactly the reports the host
 * asked to see, in exactly the nested positions where they matter most. */
public val LocalA2uiPlaceholder: ProvidableCompositionLocal<A2uiPlaceholder> =
    staticCompositionLocalOf { NoPlaceholder }

/**
 * The ids on the path from the root to what is being drawn.
 *
 * The adjacency list an agent sends is not guaranteed acyclic, and this recursion is lazy, so
 * nothing else would stop `a -> b -> a`. The core walk keeps the same guard for the same reason;
 * it cannot be reused here because that walk is eager and this one has to follow composition.
 *
 * Not static: it changes at every level, and a static local would invalidate the whole subtree.
 */
private val LocalRenderPath = compositionLocalOf { emptyList<String>() }

/**
 * Draws the surface [surfaceId], starting at its `root` component.
 *
 * @param onMessage where messages bound for the agent go -- actions a `Button` dispatches, and
 *   anything else the renderer originates. Nothing is sent anywhere by this library.
 */
@Composable
public fun A2uiSurface(
    renderer: A2uiRenderer,
    surfaceId: String,
    registry: ComponentRegistry,
    modifier: Modifier = Modifier,
    placeholder: A2uiPlaceholder = NoPlaceholder,
    onMessage: (RendererToAgentMessage) -> Unit = {},
) {
    // Read through the renderer's snapshot state, so a `createSurface` that has not arrived yet
    // resolves to null now and to a surface later without anything here re-subscribing.
    val surface = renderer.state.surfaces[surfaceId]
    if (surface == null || !surface.isRenderable) return
    CompositionLocalProvider(
        LocalA2uiRegistry provides registry,
        LocalA2uiPlaceholder provides placeholder,
    ) {
        A2uiComponent(
            renderer = renderer,
            surfaceId = surfaceId,
            componentId = ROOT_COMPONENT_ID,
            evaluationScope = EvaluationScope.Root,
            modifier = modifier,
            onMessage = onMessage,
        )
    }
}

/**
 * Draws one component by id, recursing into its children through [A2uiComponentScope].
 *
 * Public because a host embedding a single component without a surface around it is a reasonable
 * thing to want. A container drawing its own children calls [RenderChild] instead, which carries
 * the scope's renderer, surface and message sink for it.
 */
@Composable
public fun A2uiComponent(
    renderer: A2uiRenderer,
    surfaceId: String,
    componentId: String,
    evaluationScope: EvaluationScope,
    modifier: Modifier = Modifier,
    onMessage: (RendererToAgentMessage) -> Unit = {},
) {
    val registry = LocalA2uiRegistry.current
    val placeholder = LocalA2uiPlaceholder.current
    val path = LocalRenderPath.current
    when {
        componentId in path -> {
            placeholder.Render(A2uiPlaceholderReason.Cycle(componentId), modifier)
            return
        }

        path.size >= MAX_RENDER_DEPTH -> {
            placeholder.Render(A2uiPlaceholderReason.TooDeep(componentId), modifier)
            return
        }
    }

    val component = renderer.state.surfaces[surfaceId]?.components?.get(componentId)
    if (component == null) {
        placeholder.Render(A2uiPlaceholderReason.MissingComponent(componentId), modifier)
        return
    }

    val componentRenderer = registry[component.component]
    if (componentRenderer == null) {
        placeholder.Render(A2uiPlaceholderReason.UnknownType(componentId, component.component), modifier)
        return
    }

    // `onMessage` is deliberately not a key. A host writing `onMessage = { viewModel.send(it) }`
    // hands over a fresh lambda whenever the compiler cannot memoise it, and keying on it would
    // rebuild every scope in the tree on every recomposition of the host. Rebuilding the scope is
    // cheap; what it takes with it is not -- the `derivedStateOf` caches in `rememberString` and
    // its siblings are keyed on the scope, so they would all be discarded, and the recomposition
    // granularity this design exists to buy would quietly stop working.
    val latest = rememberUpdatedState(onMessage)
    val scope = remember(renderer, surfaceId, component, evaluationScope) {
        A2uiComponentScope(renderer, surfaceId, component, evaluationScope) { latest.value(it) }
    }
    CompositionLocalProvider(LocalRenderPath provides path + componentId) {
        componentRenderer.Render(scope, modifier)
    }
}

/**
 * Draws [child] under this scope.
 *
 * A container's renderer calls this for each entry of [rememberChildren]. The child's evaluation
 * scope comes from the child rather than from here, because a template instance resolves its
 * relative paths against its own item -- and the registry, placeholder and message sink come from
 * the composition, so there is nothing for a container author to forget to pass on.
 */
@Composable
public fun A2uiComponentScope.RenderChild(child: A2uiChild, modifier: Modifier = Modifier) {
    A2uiComponent(
        renderer = renderer,
        surfaceId = surfaceId,
        componentId = child.componentId,
        evaluationScope = child.evaluationScope,
        modifier = modifier,
        onMessage = onMessage,
    )
}

/**
 * Draws nothing.
 *
 * The default because a placeholder's right shape is the host's decision -- a debugging Gallery
 * wants a labelled box, a production surface wants empty space rather than internal vocabulary in
 * front of a user. Hosts that want to see these pass their own.
 */
public val NoPlaceholder: A2uiPlaceholder = A2uiPlaceholder { _, _ -> }
