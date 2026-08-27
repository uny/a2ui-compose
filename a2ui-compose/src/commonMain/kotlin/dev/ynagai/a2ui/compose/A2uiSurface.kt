package dev.ynagai.a2ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import dev.ynagai.a2ui.core.protocol.RendererToAgentMessage
import dev.ynagai.a2ui.core.protocol.Surface
import dev.ynagai.a2ui.core.surface.EvaluationScope
import dev.ynagai.a2ui.core.surface.RenderCost
import dev.ynagai.a2ui.core.surface.RenderLimits
import dev.ynagai.a2ui.core.surface.renderCost


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

    /** The tree nests deeper than [RenderLimits.maxDepth]. */
    public data class TooDeep(public val componentId: String) : A2uiPlaceholderReason

    /**
     * Drawing this component would compose more than [limit] instances, so none of it was drawn.
     *
     * The one reason here that is not a component degrading. The adjacency list is a graph: n
     * layers naming the same two children expand to 2^n instances from 2n components, which no
     * depth or cycle guard bounds. Composition does not survive that and does not raise when it
     * gives way, so the estimate is taken before descending and the whole subtree is refused --
     * drawing the first [limit] of it would be a wrong UI shown without complaint.
     */
    public data class BudgetExceeded(
        public val componentId: String,
        public val limit: Int,
    ) : A2uiPlaceholderReason

    /**
     * [dropped] children of this container were not drawn, because its share of the surface's
     * instance budget ran out.
     *
     * What [BudgetExceeded] cannot catch: how many instances a `ChildList` template yields is the
     * agent's *data model* talking, and an estimate made from the components alone cannot know it.
     * The budget is therefore also carried down the descent and spent as it is expanded. Reported
     * rather than silently shortened, for the reason core's walk refuses rather than truncating.
     */
    public data class TooManyChildren(
        public val componentId: String,
        public val dropped: Int,
    ) : A2uiPlaceholderReason
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
 * nothing else would stop `a -> b -> a`. Core's traversal keeps the same guard for the same
 * reason; it cannot be reused here because that traversal is eager and this one has to follow
 * composition, which is why the *bounds* are shared rather than the descent.
 *
 * Not static: it changes at every level, and a static local would invalidate the whole subtree.
 */
private val LocalRenderPath = compositionLocalOf<RenderPath?> { null }

/**
 * One link of the path from the root to what is being drawn.
 *
 * A linked chain rather than a `List<String>`, which is what the two operations this local exists
 * for cost. `path + componentId` copies the whole path at every level and `componentId in path`
 * scans it, so a surface nested d deep did O(d^2) work per frame and allocated a list per node.
 * Core's traversal shares its ancestors by reference for the same reason.
 */
private class RenderPath(val id: String, val parent: RenderPath?) {
    val depth: Int = (parent?.depth ?: 0) + 1

    fun contains(id: String): Boolean {
        var link: RenderPath? = this
        while (link != null) {
            if (link.id == id) return true
            link = link.parent
        }
        return false
    }
}


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
        // A surface starts its own path. Component ids are scoped to the surface that defined
        // them, so a path carried in from an enclosing surface is a path of somebody else's ids:
        // it makes this surface's `root` look like a cycle against the outer one's, and it leaves
        // [A2uiComponent]'s budget gate -- which opens on a null path -- shut. A host drawing a
        // second surface from inside a component renderer would then compose the one payload the
        // gate exists to refuse.
        LocalRenderPath provides null,
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
 *
 * @param budget how many component instances this subtree may compose, itself included. Defaults
 *   to the whole surface's, which is what a top-level entry gets; [RenderChild] passes down the
 *   share [A2uiComponentScope] divided out. A parameter rather than a composition local because
 *   providing one wraps every child in another composable, and that layer was enough to stop a
 *   leaf from being skipped on two of the targets -- the recomposition granularity this design
 *   exists to buy.
 */
@Composable
public fun A2uiComponent(
    renderer: A2uiRenderer,
    surfaceId: String,
    componentId: String,
    evaluationScope: EvaluationScope,
    modifier: Modifier = Modifier,
    budget: Int = renderer.renderLimits.maxInstances,
    onMessage: (RendererToAgentMessage) -> Unit = {},
) {
    val registry = LocalA2uiRegistry.current
    val placeholder = LocalA2uiPlaceholder.current
    val path = LocalRenderPath.current
    val limits = renderer.renderLimits

    // Nothing above this call is drawing A2UI, so this is where the surface's budget is opened.
    // The check belongs here rather than in `A2uiSurface` because this function is public: a host
    // embedding one component without a surface around it reaches the same descent, and a gate
    // only `A2uiSurface` passed through would be a gate with a documented way around it.
    if (path == null) {
        val model = renderer.state.surfaces[surfaceId]
        // Keyed on the surface's components rather than the surface, so an estimate survives every
        // data model write -- which is also why it is an estimate. `renderer` and `limits` are
        // keys of their own: the same components resolve to different children under a different
        // catalog, and to a different verdict under different bounds.
        // `runCatching`, and for the reason [A2uiComponentScope.children] resolves its own children
        // inside one. `ChildResolver.childrenOf` raises on agent-controlled input -- a component
        // carrying more than `MAX_REFERENCES` references, or a schema that outgrows
        // [A2uiRenderer.validationLimits] -- and a `Tabs` of five thousand tabs is enough to reach
        // it through the shipped catalog. Estimating is not the place that answer arrives: the
        // descent this gates already degrades to a component with no children when the resolver
        // refuses it, so an estimate that threw instead would turn a payload the renderer survives
        // into a composition that does not.
        val cost = remember(renderer, surfaceId, componentId, model?.components, limits) {
            model?.let {
                runCatching { it.renderCost(renderer.childResolver(it), limits, componentId) }
                    .getOrNull()
            }
        }
        if (cost is RenderCost.Exceeds) {
            placeholder.Render(A2uiPlaceholderReason.BudgetExceeded(componentId, cost.limit), modifier)
            return
        }
    }
    when {
        path?.contains(componentId) == true -> {
            placeholder.Render(A2uiPlaceholderReason.Cycle(componentId), modifier)
            return
        }

        (path?.depth ?: 0) >= limits.maxDepth -> {
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
    // Keyed exactly as the scope is, and deliberately not `rememberUpdatedState`, which is keyless.
    // A keyless cell outlives the scope that captured it: this call position renders surface `a`,
    // a renderer keeps hold of that scope, the position is then re-keyed to surface `b`, and the
    // one cell now holds `b`'s callback -- so a dispatch through the retained `a` scope builds a
    // message stamped with `a` and hands it to the host's handler for `b`. Sharing the keys means
    // a rebuilt scope gets a rebuilt cell, and a retained scope keeps reaching the callback that
    // belonged to it.
    val latest = remember(renderer, surfaceId, component, evaluationScope, budget) {
        mutableStateOf(onMessage)
    }
    latest.value = onMessage
    val scope = remember(renderer, surfaceId, component, evaluationScope, budget) {
        A2uiComponentScope(renderer, surfaceId, component, evaluationScope, budget) { latest.value(it) }
    }
    // Remembered, and that is load-bearing rather than an optimisation. A composition local
    // invalidates its readers when the value *provided* changes, and `RenderPath` is a chain of
    // links compared by identity, so a fresh one per recomposition would invalidate the entire
    // subtree below every component -- which the `List<String>` this replaced did not, being
    // compared structurally. The previous list cost O(depth) per node to copy and scan; this
    // costs O(1) and is built once per component instance.
    val childPath = remember(componentId, path) { RenderPath(componentId, path) }
    CompositionLocalProvider(LocalRenderPath provides childPath) {
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
    // The one entry that is not a component. A container gets these back from `rememberChildren`
    // in place of the children its budget did not reach, so every container reports a shortened
    // list without its author having to know the budget exists.
    if (child.dropped > 0) {
        LocalA2uiPlaceholder.current.Render(
            A2uiPlaceholderReason.TooManyChildren(component.id, child.dropped),
            modifier,
        )
        return
    }
    A2uiComponent(
        renderer = renderer,
        surfaceId = surfaceId,
        componentId = child.componentId,
        evaluationScope = child.evaluationScope,
        modifier = modifier,
        budget = child.budget,
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
