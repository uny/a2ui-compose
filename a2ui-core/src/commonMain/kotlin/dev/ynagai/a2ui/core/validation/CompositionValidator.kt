package dev.ynagai.a2ui.core.validation

import dev.ynagai.a2ui.core.protocol.CatalogDefinition
import dev.ynagai.a2ui.core.protocol.Component
import dev.ynagai.a2ui.core.protocol.ComponentDefinition
import dev.ynagai.a2ui.core.protocol.ComponentId
import dev.ynagai.a2ui.core.protocol.Surface
import dev.ynagai.a2ui.core.surface.ChildReference
import dev.ynagai.a2ui.core.surface.ChildResolver
import dev.ynagai.a2ui.core.surface.SurfaceModel
import dev.ynagai.a2ui.core.surface.walk

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
 */
public class CompositionValidator(
    catalogs: List<CatalogDefinition>,
    private val surfaceDefault: String? = null,
) {
    private val byId: Map<String, CatalogDefinition> = catalogs.associateBy { it.catalogId }

    /**
     * Every forbidden pairing in [surface], in the order [resolver] lays the tree out.
     *
     * A reference to a component the surface has not received yet is skipped rather than reported:
     * the specification requires a renderer to keep drawing while a surface arrives, so an id that
     * names nothing is a component still in flight, not a composition error. A component whose
     * catalog this validator does not hold is skipped for the same reason it is skipped by the
     * resolver — [CatalogValidator] is what reports that.
     */
    public fun validate(
        surface: SurfaceModel,
        resolver: ChildResolver,
    ): List<CompositionViolation> {
        val root = surface.root ?: return emptyList()
        val out = mutableListOf<CompositionViolation>()
        check(
            parent = null,
            parentType = Surface.COMPONENT,
            child = root,
            property = Surface.ROOT_ID,
            into = out,
        )
        // The walk is what bounds this: it carries the depth and instance limits, refuses to
        // revisit a component already on the current path, and raises rather than truncating.
        for ((component, _) in surface.walk(resolver)) {
            for (reference in resolver.childrenOf(component)) {
                for (id in reference.ids()) {
                    val child = surface.component(id) ?: continue
                    check(component, component.component, child, reference.property, out)
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
    ) {
        val childDefinition = definitionOf(child)
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
        val parentDefinition = parent?.let(::definitionOf)
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

    private fun definitionOf(component: Component): ComponentDefinition? {
        val catalogId = component.catalogId ?: surfaceDefault ?: return null
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
