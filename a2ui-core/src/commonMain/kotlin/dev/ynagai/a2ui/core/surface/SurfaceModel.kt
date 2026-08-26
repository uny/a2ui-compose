package dev.ynagai.a2ui.core.surface

import dev.ynagai.a2ui.core.protocol.ChildList
import dev.ynagai.a2ui.core.protocol.Component
import dev.ynagai.a2ui.core.protocol.ComponentId
import dev.ynagai.a2ui.core.protocol.Surface
import dev.ynagai.a2ui.core.protocol.SurfaceMetadata
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Thrown when a message is well-formed but cannot be applied to the renderer's current state.
 *
 * These are the specification's MUSTs about message *order* rather than about payload shape —
 * creating a surface that already exists, updating one that was never created. They are separate
 * from [dev.ynagai.a2ui.core.protocol.A2uiFormatException] because the payload is fine; it is the
 * conversation that is out of order.
 *
 * Turning one of these into a renderer-to-agent `error` message is the validator's job, which is
 * why [surfaceId] is carried rather than only mentioned in [message].
 */
public class A2uiStateException(
    message: String,
    public val surfaceId: String? = null,
) : RuntimeException(message)

/**
 * The renderer's state for one surface: its components, its data model, and the settings the
 * `createSurface` message fixed.
 *
 * Components are held as the flat map the specification's adjacency-list model calls for, not as
 * a tree. The tree is rebuilt at render time from id references, which is what lets the agent
 * send definitions in any order and lets rendering start as soon as [root] arrives — see
 * [ChildResolver] for why the rebuild needs the catalog.
 */
public data class SurfaceModel(
    public val surfaceId: String,
    public val catalogId: String? = null,
    public val sendDataModel: Boolean = false,
    public val metadata: SurfaceMetadata? = null,
    public val components: Map<ComponentId, Component> = emptyMap(),
    public val dataModel: JsonObject = JsonObject(emptyMap()),
) {
    /**
     * The component mounted as the child of the surface's canonical `Surface` container, or null
     * until it arrives.
     *
     * Until this is present the specification says other component updates "will have no visible
     * effect, and they will be buffered" — which the flat map already does, so nothing here needs
     * to hold them back.
     */
    public val root: Component? get() = components[Surface.ROOT_ID]

    /** True once [root] has arrived and the surface can be drawn. */
    public val isRenderable: Boolean get() = root != null

    /** The component [id] names, or null when it has not been defined (yet, or at all). */
    public fun component(id: ComponentId): Component? = components[id]

    /**
     * This surface with [incoming] merged in, keyed by id.
     *
     * `updateComponents` is an upsert over the map rather than a replacement of it: the message is
     * documented as one that "can be sent multiple times to update the component tree", and each
     * carries only the components it changes.
     */
    public fun withComponents(incoming: List<Component>): SurfaceModel {
        if (incoming.isEmpty()) return this
        return copy(components = components + incoming.associateBy { it.id })
    }

    /** This surface with [value] written into its data model at [pointer]. */
    public fun withDataModel(pointer: JsonPointer, value: JsonElement): SurfaceModel =
        copy(dataModel = dataModel.write(pointer, value))

    /** The value [pointer] addresses in this surface's data model, resolved in [scope]. */
    public fun read(
        pointer: JsonPointer,
        scope: EvaluationScope = EvaluationScope.Root,
    ): JsonElement? = scope.resolve(dataModel, pointer)
}

/**
 * A reference from one component to another, as the catalog declares it.
 *
 * The protocol reserves the *shapes* — `common_types.json` defines `Child` and `ChildList` — but
 * not the property names carrying them. `Card` puts its child under `child`, `Column` puts its
 * children under `children`, `Modal` has two (`trigger` and `content`), and `Tabs` nests one
 * inside each element of a `tabs` array. Only the catalog knows which of a component's properties
 * are child references, so resolving them is a [ChildResolver]'s job, not this model's.
 */
public sealed interface ChildReference {
    /** The property this reference was found under, for error reporting. */
    public val property: String

    /** A reference to exactly one component. */
    public data class Single(
        override val property: String,
        public val id: ComponentId,
    ) : ChildReference

    /** A fixed list of children. */
    public data class Fixed(
        override val property: String,
        public val ids: List<ComponentId>,
    ) : ChildReference

    /**
     * A template instantiated once per item of the array at [path].
     *
     * The renderer expands this by iterating the bound array and rendering [componentId] once per
     * item in the matching [EvaluationScope.Collection].
     */
    public data class Template(
        override val property: String,
        public val componentId: ComponentId,
        public val path: JsonPointer,
    ) : ChildReference
}

/**
 * Finds the child references a component carries.
 *
 * There is deliberately no default implementation here that reads `child` and `children`. A
 * renderer that hard-coded those names would be correct for the basic catalog and wrong for every
 * other one — it would miss `Modal.trigger`, `Modal.content` and `Tabs.tabs[].child` even within
 * the basic catalog. The implementation that reads the catalog's schema belongs with the rest of
 * catalog resolution; this interface is the seam it plugs into.
 */
public fun interface ChildResolver {
    /** The child references [component] carries, in the order the renderer should lay them out. */
    public fun childrenOf(component: Component): List<ChildReference>

    public companion object {
        /** A resolver for catalogs whose components have no children at all. */
        public val NONE: ChildResolver = ChildResolver { emptyList() }
    }
}

/**
 * Every component reachable from [SurfaceModel.root], depth first, each paired with the scope it
 * renders in.
 *
 * References that name a component the surface has not received are skipped rather than raised:
 * the specification requires renderers to "handle missing references gracefully by rendering
 * placeholders (progressive rendering)". A reference that revisits a component already on the
 * current path is also skipped, so a cycle in the adjacency list ends the walk instead of hanging
 * it — reporting that cycle is the validator's job, not the renderer's.
 */
public fun SurfaceModel.walk(
    resolver: ChildResolver,
): List<Pair<Component, EvaluationScope>> {
    val out = mutableListOf<Pair<Component, EvaluationScope>>()

    fun visit(component: Component, scope: EvaluationScope, path: Set<ComponentId>) {
        out += component to scope
        val nextPath = path + component.id
        fun descend(id: ComponentId, childScope: EvaluationScope) {
            val child = components[id] ?: return
            if (child.id in nextPath) return
            visit(child, childScope, nextPath)
        }
        for (reference in resolver.childrenOf(component)) {
            when (reference) {
                is ChildReference.Single -> descend(reference.id, scope)
                is ChildReference.Fixed -> reference.ids.forEach { descend(it, scope) }
                is ChildReference.Template -> {
                    val items = read(reference.path, scope) as? JsonArray ?: continue
                    // The template component is re-entered once per item: each instance is a
                    // distinct rendering in a distinct scope, so only a reference back up the
                    // *current* path counts as a cycle.
                    items.indices.forEach { index ->
                        descend(reference.componentId, scope.iterate(reference.path, index))
                    }
                }
            }
        }
    }

    root?.let { visit(it, EvaluationScope.Root, emptySet()) }
    return out
}

/** Reads [ChildList] out of an already-decoded property value. */
public fun ChildList.asReference(property: String): ChildReference = when (this) {
    is ChildList.Static -> ChildReference.Fixed(property, ids)
    is ChildList.Template -> ChildReference.Template(
        property,
        componentId,
        JsonPointer.parse(path),
    )
}
