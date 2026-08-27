package dev.ynagai.a2ui.core.surface

import dev.ynagai.a2ui.core.protocol.Component
import dev.ynagai.a2ui.core.protocol.ComponentId
import dev.ynagai.a2ui.core.protocol.Surface
import kotlinx.serialization.json.JsonArray

/**
 * The bounds a renderer draws within.
 *
 * Separate from [dev.ynagai.a2ui.core.validation.ValidationLimits] and from [DEFAULT_MAX_DEPTH]
 * because they bound different quantities. Those are about what can be *checked*: how far a schema
 * evaluator may recurse, how deep [walk] may nest before its own stack and cycle check stop being
 * cheap. These are about what can be *drawn*, and drawing gives way far earlier than checking does
 * -- measured on Chrome, a chain of plain containers stops composing at all somewhere between 216
 * and 220 levels, and it does not raise when it does: `setContent` hangs. A bound that a renderer
 * could catch after the fact would therefore not be a bound at all, which is why the numbers here
 * are set to be consulted *before* descending.
 *
 * @param maxInstances how many component instances one surface may draw. The adjacency list is a
 *   graph rather than a tree, so this is not bounded by the number of components an agent sends:
 *   n layers that each name the same two children expand to 2^n instances from 2n components, and
 *   neither a depth bound nor a cycle check stops that -- no path repeats an id and none of them
 *   is deep. The default is the largest breadth measured to compose to completion (about 500ms on
 *   Chrome, 212ms on the JVM at 5,000 plain widgets). A surface that must show more than this
 *   wants the host's lazy layout, which a library cannot impose on a renderer it did not write.
 * @param maxDepth how deeply a surface may nest before drawing stops. The default is derived from
 *   two measurements: the official example corpus needs 7 levels at most (median 5), and the point
 *   where composition breaks moves with how many frames a widget nests per level -- 216 for a
 *   plain container, about 36 for a widget stacking six. 24 is 3.4x what real content needs and
 *   two thirds of the break for a heavy widget. Both sides have room because the break is neither
 *   catchable nor recoverable.
 * @param templateFanout how many instances a [ChildReference.Template] counts as when the cost of
 *   a surface is being estimated from its components alone. Counting a template as one is
 *   unsound: nested templates and templates reached along many structural paths multiply, and a
 *   count that summed them would report a surface as cheap and then draw the product. The default
 *   is the smallest value that still multiplies -- how many items a template actually yields is
 *   the agent's data model talking rather than its component graph, and that is bounded where it
 *   arises, when the reference is expanded.
 */
public data class RenderLimits(
    public val maxInstances: Int = 5_000,
    public val maxDepth: Int = 24,
    public val templateFanout: Int = 2,
) {
    public companion object {
        /** The bounds derived from the measurements described on [RenderLimits]. */
        public val DEFAULT: RenderLimits = RenderLimits()
    }
}

/** What drawing a surface would cost, as far as its components alone can say. */
public sealed interface RenderCost {
    /** The surface fits, and drawing it composes [instances] component instances. */
    public data class Fits(public val instances: Int) : RenderCost

    /** The surface expands past [RenderLimits.maxInstances] and must not be drawn. */
    public data class Exceeds(public val limit: Int) : RenderCost
}

/**
 * What drawing this surface from [from] would cost, without drawing any of it.
 *
 * The estimate a renderer consults before it descends. It counts what a composition would create
 * rather than what the agent sent: a reference to a component that has not arrived is a
 * placeholder the renderer composes, so it is charged, and so are the ones a cycle or the depth
 * bound stops -- a surface that is nothing but a million dangling references costs a million
 * placeholders, which is exactly the payload a count of *present* components would wave through.
 *
 * Templates are counted at [RenderLimits.templateFanout] rather than expanded against the data
 * model, which is what makes this an estimate. It is deliberate on both sides: the answer then
 * depends only on the surface's components, so it survives every data model write and can be
 * cached across them, and the count a template really has is bounded when the template is
 * expanded, where the array is in hand.
 *
 * Shares [walk]'s traversal, so the child order, the cycle rule, the depth rule and the resolver
 * handling here are not a second implementation of them.
 */
public fun SurfaceModel.renderCost(
    resolver: ChildResolver,
    limits: RenderLimits = RenderLimits.DEFAULT,
    from: ComponentId = Surface.ROOT_ID,
): RenderCost {
    val traversal = Traversal(
        model = this,
        resolver = resolver,
        maxInstances = limits.maxInstances,
        maxDepth = limits.maxDepth,
        templateFanout = limits.templateFanout,
        refuse = false,
        emit = null,
    )
    val instances = traversal.run(from)
    return if (instances < 0) RenderCost.Exceeds(limits.maxInstances) else RenderCost.Fits(instances)
}

/**
 * The one bounded traversal of a surface's adjacency list.
 *
 * [walk] materialises what it visits, [renderCost] counts it, and a renderer consults the count
 * before composing. All three have to agree about which children a component has, in what order,
 * what a cycle is, what is too deep, and what a reference that names nothing costs -- so they run
 * the same descent rather than three that were written to match.
 *
 * @param templateFanout how many instances a template yields, or null to read the data model.
 * @param refuse whether reaching a bound raises. [walk] refuses rather than truncating, because a
 *   silently shortened walk is a wrong answer given without complaint. A renderer asking what a
 *   surface would cost wants the answer rather than an exception, and reports it instead.
 * @param emit where visited components go, or null when only the count is wanted -- the count is
 *   what a renderer consults, and materialising up to [maxInstances] pairs to reach a number the
 *   traversal already has is the cost this exists to avoid.
 */
private class Traversal(
    private val model: SurfaceModel,
    private val resolver: ChildResolver,
    private val maxInstances: Int,
    private val maxDepth: Int,
    private val templateFanout: Int?,
    private val refuse: Boolean,
    private val emit: ((Component, EvaluationScope) -> Unit)?,
) {
    private val pending = ArrayDeque<Frame>()

    /** The frames one component's references produced, held to be pushed in reverse. */
    private val children = mutableListOf<Frame>()

    private var instances = 0

    private var overflowed = false

    /** The instances reachable from [from], or -1 when the surface outgrew [maxInstances]. */
    fun run(from: ComponentId): Int {
        descend(from, EvaluationScope.Root, ancestors = null, depth = 0)
        flush()
        while (!overflowed && pending.isNotEmpty()) {
            val frame = pending.removeLast()
            emit?.invoke(frame.component, frame.scope)
            // Ancestors are shared by reference rather than copied per node: copying the path into
            // a set at every step is what makes a deep surface quadratic, which a depth bound
            // alone would not fix cheaply enough for the js and wasmJs targets.
            val ancestors = Ancestor(frame.component.id, frame.ancestors)
            val depth = frame.depth + 1
            for (reference in resolver.childrenOf(frame.component)) {
                when (reference) {
                    is ChildReference.Single -> descend(reference.id, frame.scope, ancestors, depth)

                    is ChildReference.Fixed ->
                        for (id in reference.ids) {
                            if (!descend(id, frame.scope, ancestors, depth)) break
                        }

                    is ChildReference.Template -> {
                        val count = templateFanout
                            ?: ((model.read(reference.path, frame.scope) as? JsonArray)?.size ?: 0)
                        // The template component is re-entered once per item: each instance is a
                        // distinct rendering in a distinct scope, so only a reference back up the
                        // *current* path counts as a cycle.
                        for (index in 0 until count) {
                            val scope = frame.scope.iterate(reference.path, index)
                            if (!descend(reference.componentId, scope, ancestors, depth)) break
                        }
                    }
                }
                if (overflowed) break
            }
            flush()
        }
        return if (overflowed) -1 else instances
    }

    /**
     * Moves the frames one component produced onto the stack, reversed so that popping from the
     * end visits the children in the order the resolver reported them.
     */
    private fun flush() {
        for (index in children.indices.reversed()) pending.addLast(children[index])
        children.clear()
    }

    /**
     * Charges one instance for [id] and queues it if it can be descended into, returning whether
     * the traversal may continue.
     *
     * The budget is read *before* the component is looked up, and a reference that names nothing
     * is charged all the same. Both matter. A `ChildList` naming a hundred thousand ids that were
     * never sent would otherwise cost nothing to a bound that only counted components it found,
     * while a renderer composes a placeholder for every one of them; and a template bound to an
     * array of a million items would allocate a million frames, each holding a scope whose pointer
     * is built eagerly, before a bound checked afterwards had been read at all.
     */
    private fun descend(
        id: ComponentId,
        scope: EvaluationScope,
        ancestors: Ancestor?,
        depth: Int,
    ): Boolean {
        if (instances >= maxInstances) {
            if (refuse) {
                throw A2uiStateException(
                    "surface `${model.surfaceId}` expands to more than $maxInstances component instances.",
                    model.surfaceId,
                )
            }
            overflowed = true
            return false
        }
        instances++
        // Charged and not descended into, in the order a renderer decides what to draw in place of
        // this component: a cycle first, since revisiting an id says nothing about how deep the
        // path is, then the depth bound, then a reference that named nothing.
        if (ancestors?.contains(id) == true) return true
        if (depth >= maxDepth) {
            if (refuse) {
                throw A2uiStateException(
                    "surface `${model.surfaceId}` nests components more than $maxDepth deep.",
                    model.surfaceId,
                )
            }
            return true
        }
        val component = model.components[id] ?: return true
        children += Frame(component, scope, ancestors, depth)
        return true
    }
}

private class Frame(
    val component: Component,
    val scope: EvaluationScope,
    val ancestors: Ancestor?,
    val depth: Int,
)

/** One link of the chain of component ids on the path from the root to the current node. */
private class Ancestor(val id: ComponentId, val parent: Ancestor?) {
    fun contains(id: ComponentId): Boolean {
        var link: Ancestor? = this
        while (link != null) {
            if (link.id == id) return true
            link = link.parent
        }
        return false
    }
}

/** [walk], over the shared traversal. */
internal fun SurfaceModel.walkVia(
    resolver: ChildResolver,
    limit: Int,
    maxDepth: Int,
): List<Pair<Component, EvaluationScope>> {
    val out = mutableListOf<Pair<Component, EvaluationScope>>()
    Traversal(
        model = this,
        resolver = resolver,
        maxInstances = limit,
        maxDepth = maxDepth,
        templateFanout = null,
        refuse = true,
        emit = { component, scope -> out += component to scope },
    ).run(Surface.ROOT_ID)
    return out
}
