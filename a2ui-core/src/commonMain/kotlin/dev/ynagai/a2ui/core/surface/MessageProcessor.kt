package dev.ynagai.a2ui.core.surface

import dev.ynagai.a2ui.core.protocol.AgentFunctionResponseMessage
import dev.ynagai.a2ui.core.protocol.AgentToRendererMessage
import dev.ynagai.a2ui.core.protocol.CallId
import dev.ynagai.a2ui.core.protocol.CallRendererFunctionMessage
import dev.ynagai.a2ui.core.protocol.CreateSurfaceMessage
import dev.ynagai.a2ui.core.protocol.DeleteSurfaceMessage
import dev.ynagai.a2ui.core.protocol.FunctionCall
import dev.ynagai.a2ui.core.protocol.FunctionResponse
import dev.ynagai.a2ui.core.protocol.UpdateComponentsMessage
import dev.ynagai.a2ui.core.protocol.UpdateDataModelMessage
import kotlinx.serialization.json.JsonObject

/**
 * Every surface the renderer currently holds.
 *
 * This is the whole of the renderer's agent-facing state, and it is immutable: applying a message
 * returns a new [RendererState] rather than mutating this one, so a UI layer can hold a snapshot
 * and diff against the next one.
 */
public data class RendererState(
    public val surfaces: Map<String, SurfaceModel> = emptyMap(),
) {
    /** The surface [surfaceId] names, or null when it does not exist. */
    public fun surface(surfaceId: String): SurfaceModel? = surfaces[surfaceId]

    /** The surface [surfaceId] names, or a failure naming the message that needs it. */
    internal fun require(surfaceId: String, what: String): SurfaceModel =
        surfaces[surfaceId] ?: throw A2uiStateException(
            "$what: surface `$surfaceId` does not exist; `createSurface` must be sent first.",
            surfaceId,
        )
}

/**
 * Something the renderer must act on that applying a message did not, and could not, do itself.
 *
 * Two of the six agent-to-renderer messages do not change surface state at all — they ask the
 * renderer to run a function, or hand back the result of one the renderer asked for. Folding them
 * into state would lose them; dropping them would lose the response the specification says the
 * renderer MUST send. So the fold returns them alongside the new state.
 *
 * The state-changing messages report an effect too, so that a UI layer can tell what moved without
 * diffing two whole [RendererState]s.
 */
public sealed interface RendererEffect {
    /** The surface this effect concerns, or null for effects that name no surface. */
    public val surfaceId: String? get() = null

    /** A surface was created. It is not renderable until its `root` component arrives. */
    public data class SurfaceCreated(override val surfaceId: String) : RendererEffect

    /** Components were added to or replaced in a surface. */
    public data class ComponentsUpdated(
        override val surfaceId: String,
        public val ids: List<String>,
    ) : RendererEffect

    /** A surface's data model changed at [path]. */
    public data class DataModelUpdated(
        override val surfaceId: String,
        public val path: JsonPointer,
    ) : RendererEffect

    /** A surface was deleted along with its components and data. */
    public data class SurfaceDeleted(override val surfaceId: String) : RendererEffect

    /**
     * The agent asked the renderer to run [call].
     *
     * The renderer MUST answer with a `rendererFunctionResponse` or an `error` carrying
     * [functionCallId], even when the function returns void.
     */
    public data class RendererFunctionRequested(
        public val functionCallId: CallId,
        public val call: FunctionCall,
    ) : RendererEffect

    /** The agent answered a `callAgentFunction` the renderer had sent. */
    public data class AgentFunctionResponded(
        public val response: FunctionResponse,
    ) : RendererEffect
}

/** A new [state], plus what the caller has to do about the message that produced it. */
public data class ProcessResult(
    public val state: RendererState,
    public val effects: List<RendererEffect>,
)

/**
 * Folds agent-to-renderer messages into [RendererState].
 *
 * This is the incremental half of the protocol: a surface is built up by a stream of messages that
 * each carry only what changed, and convergence depends on applying them in order. The order MUSTs
 * are enforced here — a surface cannot be created twice, and cannot be updated or deleted before
 * it is created — and raise [A2uiStateException].
 *
 * Everything that needs a catalog is out of scope. A component whose properties the catalog does
 * not define, a child reference that names nothing, a function call with the wrong argument types:
 * all of those are accepted into state here and rejected by the validator, which is also what lets
 * progressive rendering work at all.
 */
public object MessageProcessor {

    /** [state] with [message] applied. */
    public fun apply(state: RendererState, message: AgentToRendererMessage): ProcessResult =
        when (message) {
            is CreateSurfaceMessage -> createSurface(state, message)
            is UpdateComponentsMessage -> updateComponents(state, message)
            is UpdateDataModelMessage -> updateDataModel(state, message)
            is DeleteSurfaceMessage -> deleteSurface(state, message)
            is CallRendererFunctionMessage -> ProcessResult(
                state,
                listOf(
                    RendererEffect.RendererFunctionRequested(
                        message.functionCallId,
                        message.callFunction,
                    ),
                ),
            )
            is AgentFunctionResponseMessage -> ProcessResult(
                state,
                listOf(RendererEffect.AgentFunctionResponded(message.response)),
            )
        }

    /**
     * [state] with every message in [messages] applied in order.
     *
     * The effects accumulate across the whole batch, so a caller draining a stream sees them in
     * the order they happened. A message that violates an order MUST aborts the batch: the
     * messages before it have already been folded into the state that the exception leaves
     * unreturned, which is why callers that must survive a bad message should apply one at a time.
     */
    public fun applyAll(
        state: RendererState,
        messages: Iterable<AgentToRendererMessage>,
    ): ProcessResult {
        var current = state
        val effects = mutableListOf<RendererEffect>()
        for (message in messages) {
            val result = apply(current, message)
            current = result.state
            effects += result.effects
        }
        return ProcessResult(current, effects)
    }

    private fun createSurface(
        state: RendererState,
        message: CreateSurfaceMessage,
    ): ProcessResult {
        if (message.surfaceId in state.surfaces) {
            throw A2uiStateException(
                "createSurface: surface `${message.surfaceId}` already exists; delete it first.",
                message.surfaceId,
            )
        }
        val surface = SurfaceModel(
            surfaceId = message.surfaceId,
            catalogId = message.catalogId,
            // The schema leaves `sendDataModel` optional and documents the absent case as false,
            // so the model stores the resolved value and does not make every reader re-apply it.
            sendDataModel = message.sendDataModel ?: false,
            metadata = message.metadata,
            dataModel = message.dataModel ?: JsonObject(emptyMap()),
        ).withComponents(message.components.orEmpty())

        val effects = buildList {
            add(RendererEffect.SurfaceCreated(message.surfaceId))
            // `createSurface` may carry the opening components and data model inline. Reporting
            // them as their own effects means a caller can treat the inline and the streamed form
            // identically rather than special-casing the first message.
            message.components?.takeIf { it.isNotEmpty() }?.let {
                add(RendererEffect.ComponentsUpdated(message.surfaceId, it.map { c -> c.id }))
            }
            message.dataModel?.let {
                add(RendererEffect.DataModelUpdated(message.surfaceId, JsonPointer.ROOT))
            }
        }
        return ProcessResult(
            state.copy(surfaces = state.surfaces + (message.surfaceId to surface)),
            effects,
        )
    }

    private fun updateComponents(
        state: RendererState,
        message: UpdateComponentsMessage,
    ): ProcessResult {
        val surface = state.require(message.surfaceId, "updateComponents")
        val updated = surface.withComponents(message.components)
        return ProcessResult(
            state.copy(surfaces = state.surfaces + (message.surfaceId to updated)),
            listOf(
                RendererEffect.ComponentsUpdated(
                    message.surfaceId,
                    message.components.map { it.id },
                ),
            ),
        )
    }

    private fun updateDataModel(
        state: RendererState,
        message: UpdateDataModelMessage,
    ): ProcessResult {
        val surface = state.require(message.surfaceId, "updateDataModel")
        // The path arrives from the agent unparsed (see `UpdateDataModelMessage`), so this is
        // where a malformed pointer is first rejected rather than resolved to somewhere else.
        val pointer = JsonPointer.parse(message.path ?: "")
        if (!pointer.isAbsolute) {
            throw A2uiStateException(
                "updateDataModel: `${message.path}` must be an absolute JSON Pointer; the " +
                    "relative form is only defined inside a list template.",
                message.surfaceId,
            )
        }
        val updated = try {
            surface.withDataModel(pointer, message.value)
        } catch (e: A2uiStateException) {
            // Rethrown so the failure names the surface it happened on; `write` has no surface.
            throw A2uiStateException(e.message ?: "updateDataModel failed.", message.surfaceId)
        }
        return ProcessResult(
            state.copy(surfaces = state.surfaces + (message.surfaceId to updated)),
            listOf(RendererEffect.DataModelUpdated(message.surfaceId, pointer)),
        )
    }

    private fun deleteSurface(
        state: RendererState,
        message: DeleteSurfaceMessage,
    ): ProcessResult {
        state.require(message.surfaceId, "deleteSurface")
        return ProcessResult(
            state.copy(surfaces = state.surfaces - message.surfaceId),
            listOf(RendererEffect.SurfaceDeleted(message.surfaceId)),
        )
    }
}
