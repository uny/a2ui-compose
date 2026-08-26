package dev.ynagai.a2ui.core.protocol

import dev.ynagai.a2ui.core.A2ui
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * One component of a surface: the envelope properties every component has, plus the properties
 * its catalog defines.
 *
 * The catalog-defined half is kept as an unparsed [properties] bag on purpose. Which properties a
 * component may carry, and what each of them means, is decided by the catalog the surface names —
 * so a protocol model that closed this set could only ever be right for one catalog. Resolving
 * [properties] against the catalog, and rejecting a component that carries one the catalog does
 * not define, is the validator's job.
 */
@Serializable(with = ComponentSerializer::class)
public data class Component(
    public val id: ComponentId,
    public val component: String,
    public val catalogId: String? = null,
    public val accessibility: AccessibilityAttributes? = null,
    public val metadata: Metadata? = null,
    public val properties: Map<String, JsonElement> = emptyMap(),
)

/** Surface-level metadata on a `createSurface` message. */
@Serializable
public data class SurfaceMetadata(public val extensions: Extensions? = null)

/**
 * A message from the agent to the renderer.
 *
 * Every variant travels in a two-key envelope — `version`, plus the one key naming the message —
 * which is what the wire format uses to tell them apart.
 */
@Serializable(with = AgentToRendererMessageSerializer::class)
public sealed interface AgentToRendererMessage

/**
 * Creates a surface and begins rendering it.
 *
 * [surfaceId] must be unique for the renderer's lifetime; re-creating a live surface is an error.
 */
@Serializable
public data class CreateSurfaceMessage(
    public val surfaceId: String,
    public val catalogId: String? = null,
    public val sendDataModel: Boolean? = null,
    public val components: List<Component>? = null,
    public val dataModel: JsonObject? = null,
    public val metadata: SurfaceMetadata? = null,
) : AgentToRendererMessage

/**
 * Replaces or extends the component tree of a live surface.
 *
 * One component across the messages a surface has received must have the id
 * [Surface.ROOT_ID] to serve as the tree's root.
 */
@Serializable
public data class UpdateComponentsMessage(
    public val surfaceId: String,
    public val components: List<Component>,
) : AgentToRendererMessage

/**
 * Writes [value] into a live surface's data model.
 *
 * [path] is a JSON Pointer; omitting it, or passing `/`, addresses the whole data model. It is
 * carried verbatim and is NOT checked for pointer syntax here — it arrives from the agent and is
 * the write address into the data model, so validate it before resolving it.
 *
 * A [value] of [kotlinx.serialization.json.JsonNull] deletes what [path] addresses, which is why
 * this is a non-null [JsonElement] — an absent `value` is malformed, an explicit null is a delete.
 */
@Serializable
public data class UpdateDataModelMessage(
    public val surfaceId: String,
    public val path: String? = null,
    public val value: JsonElement,
) : AgentToRendererMessage

/** Deletes a live surface. */
@Serializable
public data class DeleteSurfaceMessage(
    public val surfaceId: String,
) : AgentToRendererMessage

/**
 * Asks the renderer to run a function locally on the agent's behalf.
 *
 * Unlike a [FunctionCall] embedded in a component, [callFunction] must name its `catalogId`
 * explicitly: there is no surface in scope to inherit a default from.
 */
@Serializable
public data class CallRendererFunctionMessage(
    public val functionCallId: CallId,
    public val callFunction: FunctionCall,
) : AgentToRendererMessage {
    init {
        if (callFunction.catalogId == null) {
            throw A2uiFormatException(
                "callRendererFunction: `callFunction.catalogId` is required.",
            )
        }
    }
}

/** The agent's response to a `callAgentFunction` the renderer sent. */
@Serializable(with = AgentFunctionResponseMessageSerializer::class)
public data class AgentFunctionResponseMessage(
    public val response: FunctionResponse,
) : AgentToRendererMessage

/** Carries a [FunctionResponse] as the whole envelope body, with no wrapper object of its own. */
internal object AgentFunctionResponseMessageSerializer : KSerializer<AgentFunctionResponseMessage> {
    override val descriptor: SerialDescriptor = FunctionResponseSerializer.descriptor

    override fun deserialize(decoder: Decoder): AgentFunctionResponseMessage =
        AgentFunctionResponseMessage(FunctionResponseSerializer.deserialize(decoder))

    override fun serialize(encoder: Encoder, value: AgentFunctionResponseMessage) {
        FunctionResponseSerializer.serialize(encoder, value.response)
    }
}

// --- serializers ---------------------------------------------------------------------------

/**
 * Splits a component's wire object into the envelope properties and the catalog-defined bag.
 *
 * Nothing is rejected for being unrecognised here — an unrecognised key is exactly what a
 * catalog-defined property looks like from the protocol's side.
 */
internal object ComponentSerializer : KSerializer<Component> {
    private val ENVELOPE = setOf("id", "component", "catalogId", "accessibility", "metadata")

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor =
        SerialDescriptor("dev.ynagai.a2ui.core.protocol.Component", JsonElement.serializer().descriptor)

    override fun deserialize(decoder: Decoder): Component {
        val json = (decoder as JsonDecoder).json
        val obj = decoder.jsonObjectOrFail("Component")
        val id = obj.requiredString("id", "Component")
        val type = obj.requiredString("component", "Component")
        return Component(
            id = id,
            component = type,
            catalogId = obj.optionalString("catalogId", "Component"),
            accessibility = obj["accessibility"]?.let {
                json.decodeFromJsonElement(AccessibilityAttributes.serializer(), it)
            },
            metadata = obj["metadata"]?.let {
                json.decodeFromJsonElement(Metadata.serializer(), it)
            },
            properties = obj.filterKeys { it !in ENVELOPE },
        )
    }

    override fun serialize(encoder: Encoder, value: Component) {
        val json = encoder as JsonEncoder
        json.encodeJsonElement(
            buildJsonObject {
                put("id", JsonPrimitive(value.id))
                put("component", JsonPrimitive(value.component))
                value.catalogId?.let { put("catalogId", JsonPrimitive(it)) }
                value.accessibility?.let {
                    put("accessibility", json.json.encodeToJsonElement(AccessibilityAttributes.serializer(), it))
                }
                value.metadata?.let {
                    put("metadata", json.json.encodeToJsonElement(Metadata.serializer(), it))
                }
                value.properties.carryThrough(ENVELOPE).forEach { (key, element) ->
                    put(key, element)
                }
            },
        )
    }
}

/**
 * Reads and writes the two-key envelope every message travels in.
 *
 * A message object must carry `version` and exactly one message key. The schemas express this as
 * a `oneOf` over six variants that each close their own object, which comes to the same thing and
 * is cheaper to check directly than to try each branch in turn.
 */
internal object AgentToRendererMessageSerializer :
    EnvelopeSerializer<AgentToRendererMessage>("agent-to-renderer message") {

    override val variants: Map<String, KSerializer<out AgentToRendererMessage>> = mapOf(
        "createSurface" to CreateSurfaceMessage.serializer(),
        "updateComponents" to UpdateComponentsMessage.serializer(),
        "updateDataModel" to UpdateDataModelMessage.serializer(),
        "deleteSurface" to DeleteSurfaceMessage.serializer(),
        "callRendererFunction" to CallRendererFunctionMessage.serializer(),
        "agentFunctionResponse" to AgentFunctionResponseMessage.serializer(),
    )

    override fun keyOf(value: AgentToRendererMessage): String = when (value) {
        is CreateSurfaceMessage -> "createSurface"
        is UpdateComponentsMessage -> "updateComponents"
        is UpdateDataModelMessage -> "updateDataModel"
        is DeleteSurfaceMessage -> "deleteSurface"
        is CallRendererFunctionMessage -> "callRendererFunction"
        is AgentFunctionResponseMessage -> "agentFunctionResponse"
    }
}

/**
 * The shared shape of both message envelopes: `{"version": "v1.0", "<messageKey>": {...}}`.
 */
internal abstract class EnvelopeSerializer<T : Any>(private val what: String) : KSerializer<T> {
    protected abstract val variants: Map<String, KSerializer<out T>>

    protected abstract fun keyOf(value: T): String

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor =
        SerialDescriptor("dev.ynagai.a2ui.core.protocol.Envelope", JsonElement.serializer().descriptor)

    override fun deserialize(decoder: Decoder): T {
        val json = (decoder as JsonDecoder).json
        val obj = decoder.jsonObjectOrFail(what)
        val version = obj.optionalString("version", what)
            ?: throw A2uiFormatException("$what: `version` is required.")
        if (version != A2ui.PROTOCOL_VERSION) {
            throw A2uiFormatException(
                "$what: unsupported protocol version `$version`; this library implements " +
                    "${A2ui.PROTOCOL_VERSION} only.",
            )
        }
        val lenient = json.configuration.ignoreUnknownKeys
        val candidates = obj.keys.filter { it != "version" }
        // Under `lenient` an unmodelled envelope key is exactly what the caller opted in to
        // tolerate, so narrow to the keys this envelope knows before demanding there be one.
        val present = if (lenient) candidates.filter { it in variants } else candidates
        if (present.size != 1) {
            throw A2uiFormatException(
                if (present.isEmpty()) {
                    "$what: carries no message; expected one of ${variants.keys.joinToString()}."
                } else {
                    "$what: carries ${present.joinToString()}; expected exactly one message."
                },
            )
        }
        val key = present.single()
        val serializer = variants[key]
            ?: throw A2uiFormatException(
                "$what: `$key` is not a $what; expected one of ${variants.keys.joinToString()}.",
            )
        return json.decodeFromJsonElement(serializer, obj.getValue(key))
    }

    override fun serialize(encoder: Encoder, value: T) {
        val json = encoder as JsonEncoder
        val key = keyOf(value)
        @Suppress("UNCHECKED_CAST")
        val serializer = variants.getValue(key) as KSerializer<T>
        json.encodeJsonElement(
            buildJsonObject {
                put("version", JsonPrimitive(A2ui.PROTOCOL_VERSION))
                put(key, json.json.encodeToJsonElement(serializer, value))
            },
        )
    }
}
