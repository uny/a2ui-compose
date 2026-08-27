package dev.ynagai.a2ui.core.validation

import dev.ynagai.a2ui.core.protocol.CatalogDefinition
import dev.ynagai.a2ui.core.protocol.Component
import dev.ynagai.a2ui.core.protocol.ComponentDefinition
import dev.ynagai.a2ui.core.protocol.ComponentId
import dev.ynagai.a2ui.core.protocol.Surface
import dev.ynagai.a2ui.core.surface.ChildReference
import dev.ynagai.a2ui.core.surface.ChildResolver
import dev.ynagai.a2ui.core.surface.SurfaceModel

/**
 * A parent-child pairing a catalog forbids.
 *
 * [code] is one of the two the specification names, and it is part of the contract rather than a
 * label: a renderer reports these to the agent in a validation `error` message, and the agent is
 * expected to tell the two apart.
 */
public data class CompositionViolation(
    public val code: String,
    public val parent: String,
    public val parentId: ComponentId?,
    public val child: String,
    public val childId: ComponentId,
    public val property: String,
) {
    /** A description of the pairing. It names component *types* and ids, never property values. */
    public val message: String
        get() = when (code) {
            UNALLOWED_PARENT -> "a `$child` may not be placed inside a `$parent`."
            else -> "a `$parent` may not contain a `$child`."
        }

    public companion object {
        /** A component placed under a parent its own `allowedParents` does not list. */
        public const val UNALLOWED_PARENT: String = "UNALLOWED_PARENT"

        /** A component placed inside a container whose `allowedChildren` does not list it. */
        public const val UNALLOWED_CHILD: String = "UNALLOWED_CHILD"
    }
}

/**
 * Checks a surface's component tree against the composition constraints its catalog declares.
 *
 * This is a separate pass from the property checker because JSON Schema cannot express it. A
 * surface is a flat adjacency list of id references, so the schema for a `Menu` can say that its
 * `children` are component ids but not that each of those ids names a `MenuItem` — the evolution
 * guide gives that as the reason the two keywords exist at all. The constraint is only decidable
 * once the tree is assembled, which is here.
 *
 * The two keywords are checked independently and both must hold: a catalog may state the rule from
 * the container's side, from the child's side, or from both, and the examples in the specification
 * do each of the three.
 *
 * The reserved [Surface.COMPONENT] container is the parent of the component at
 * [Surface.ROOT_ID]. That is what makes `"allowedParents": ["Surface"]` mean "only as the top
 * level of a surface", which is the specification's first worked example.
 *
 * Note that a component may have more than one parent — the adjacency list is a graph — so a
 * component can be reported under one parent and allowed under another. Both are true at once.
 */
public class CompositionValidator(
    catalogs: List<CatalogDefinition>,
    private val surfaceDefault: String? = null,
) {
    private val byId: Map<String, CatalogDefinition> = catalogs.associateBy { it.catalogId }

    /**
     * Every forbidden pairing in [surface].
     *
     * This reads the adjacency list rather than the rendered tree, and the difference is not a
     * shortcut. [dev.ynagai.a2ui.core.surface.walk] follows every path, so it emits a component
     * once per route that reaches it — n layers each referencing the same two children produce
     * 2^n instances from 2n components, and a template's subtree is instantiated once per row of
     * a list the agent sends. Checking there would report one forbidden pairing as many times as
     * the payload happens to reach it, and would cost the same. Composition is a property of the
     * edges: a `Menu` may not contain a `Label` once, whoever renders it and however often.
     *
     * A component the surface has not received yet is skipped rather than reported: the
     * specification requires a renderer to keep drawing while a surface arrives, so an id naming
     * nothing is a component still in flight, not a composition error. A component whose catalog
     * this validator does not hold is skipped for the same reason the resolver skips it —
     * [CatalogValidator] is what reports that.
     *
     * Components no route reaches are checked too. They are in the surface, the next
     * `updateComponents` may mount them, and reporting the pairing now is what lets an agent fix
     * it before it is drawn.
     *
     * @throws dev.ynagai.a2ui.core.surface.A2uiStateException if [resolver] refuses a component —
     *   [CatalogChildResolver] does that rather than return a shortened list of children, and a
     *   composition verdict over children that were quietly dropped would be worth nothing.
     */
    public fun validate(
        surface: SurfaceModel,
        resolver: ChildResolver,
    ): List<CompositionViolation> {
        val out = mutableListOf<CompositionViolation>()
        // The surface names the catalog its components belong to, and it is right here -- so it
        // wins over the constructor's default, which cannot know which surface it is being asked
        // about. Reading only that default meant a validator built from a catalog list alone --
        // what a renderer holding several catalogs would build -- found no definition for any
        // component that did not name a catalog itself, and returned an empty list. No violations
        // and "I could not check" are not the same answer.
        val default = surface.catalogId ?: surfaceDefault
        // The reserved container is the implicit parent of `root`, which is what makes
        // `"allowedParents": ["Surface"]` mean "only at the top level of a surface".
        surface.root?.let { root ->
            check(
                parent = null,
                parentType = Surface.COMPONENT,
                child = root,
                property = Surface.ROOT_ID,
                into = out,
                default = default,
            )
        }
        for (component in surface.components.values) {
            for (reference in resolver.childrenOf(component)) {
                for (id in reference.ids()) {
                    val child = surface.component(id) ?: continue
                    check(component, component.component, child, reference.property, out, default)
                }
            }
        }
        return out
    }

    private fun check(
        parent: Component?,
        parentType: String,
        child: Component,
        property: String,
        into: MutableList<CompositionViolation>,
        default: String?,
    ) {
        val childDefinition = definitionOf(child, default)
        // A null list means "unconstrained", which is not the same as an empty one: a catalog bars
        // a component from every parent by writing `"allowedParents": []`.
        childDefinition?.allowedParents?.let { allowed ->
            if (parentType !in allowed) {
                into += CompositionViolation(
                    code = CompositionViolation.UNALLOWED_PARENT,
                    parent = parentType,
                    parentId = parent?.id,
                    child = child.component,
                    childId = child.id,
                    property = property,
                )
            }
        }
        val parentDefinition = parent?.let { definitionOf(it, default) }
        parentDefinition?.allowedChildren?.let { allowed ->
            if (child.component !in allowed) {
                into += CompositionViolation(
                    code = CompositionViolation.UNALLOWED_CHILD,
                    parent = parentType,
                    parentId = parent.id,
                    child = child.component,
                    childId = child.id,
                    property = property,
                )
            }
        }
    }

    private fun definitionOf(component: Component, default: String?): ComponentDefinition? {
        val catalogId = component.catalogId ?: default ?: return null
        return byId[catalogId]?.components?.get(component.component)
    }
}

/** The component ids one reference names, however the catalog shaped it. */
private fun ChildReference.ids(): List<ComponentId> = when (this) {
    is ChildReference.Single -> listOf(id)
    is ChildReference.Fixed -> ids
    // A template's subtree is the template component, instantiated once per item of a list the
    // agent sends. Composition is a property of the types, so the template is checked once rather
    // than once per row -- checking per row would report the same pairing as many times as the
    // data model happens to be long.
    is ChildReference.Template -> listOf(componentId)
}
