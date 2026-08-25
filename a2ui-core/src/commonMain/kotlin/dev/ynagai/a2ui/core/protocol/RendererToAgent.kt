package dev.ynagai.a2ui.core.protocol

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
 * A message from the renderer to the agent.
 *
 * Travels in the same two-key envelope as [AgentToRendererMessage].
 */
@Serializable(with = RendererToAgentMessageSerializer::class)
public sealed interface RendererToAgentMessage

/**
 * Reports a user-initiated action back to the agent.
 *
 * [context] holds the component's `action.event.context` with every binding already resolved, so
 * its values are plain JSON rather than [DynamicValue]s.
 *
 * [additional] exists because `renderer_to_agent.json` does not close this object — unlike every
 * sibling message, `action` omits `additionalProperties: false`. Keys outside the ones modelled
 * here are therefore carried through rather than rejected.
 */
@Serializable(with = ActionMessageSerializer::class)
public data class ActionMessage(
    public val name: String,
    public val surfaceId: String,
    public val sourceComponentId: ComponentId,
    public val timestamp: String,
    public val context: JsonObject,
    public val userMessage: String? = null,
    public val metadata: Metadata? = null,
    public val additional: Map<String, JsonElement> = emptyMap(),
) : RendererToAgentMessage

/** Asks the agent to run a function remotely on the renderer's behalf. */
@Serializable
public data class CallAgentFunctionMessage(
    public val surfaceId: String,
    public val functionCallId: CallId,
    public val callFunction: FunctionCall,
) : RendererToAgentMessage

/** The renderer's response to a `callRendererFunction` the agent sent. */
@Serializable(with = RendererFunctionResponseMessageSerializer::class)
public data class RendererFunctionResponseMessage(
    public val response: FunctionResponse,
) : RendererToAgentMessage

/** The three reserved codes that select the structured error shape. */
@Serializable
public enum class ValidationErrorCode(public val wireName: String) {
    VALIDATION_FAILED("VALIDATION_FAILED"),
    UNALLOWED_PARENT("UNALLOWED_PARENT"),
    UNALLOWED_CHILD("UNALLOWED_CHILD"),
    ;

    public companion object {
        internal val byWireName: Map<String, ValidationErrorCode> =
            entries.associateBy { it.wireName }
    }
}

/**
 * A renderer-side error.
 *
 * The schema splits this by `code`: the three reserved codes take a shape that must locate the
 * failure in the message ([Validation]), and any other code takes a shape that must name either
 * the surface or the call it belongs to, but not both ([Generic]).
 */
@Serializable(with = RendererErrorMessageSerializer::class)
public sealed interface RendererErrorMessage : RendererToAgentMessage {
    /** The wire value of `code`. */
    public val code: String

    /** A sentence or two on what went wrong. */
    public val message: String

    /** A message the renderer refused, located by [path] within it. */
    public data class Validation(
        public val validationCode: ValidationErrorCode,
        public val surfaceId: String,
        public val path: String,
        override val message: String,
    ) : RendererErrorMessage {
        override val code: String get() = validationCode.wireName
    }

    /**
     * Any other failure, attributed to exactly one of a surface or a function call.
     *
     * [additional] carries keys outside the modelled ones: this is the one object in the v1.0
     * schemas that sets `additionalProperties: true` outright.
     */
    public data class Generic(
        override val code: String,
        override val message: String,
        public val scope: Scope,
        public val additional: Map<String, JsonElement> = emptyMap(),
    ) : RendererErrorMessage {
        init {
            if (code in ValidationErrorCode.byWireName) {
                throw A2uiFormatException(
                    "error: `$code` is reserved for the structured validation error shape.",
                )
            }
        }
    }

    /** What a [Generic] error is about. Exactly one of the two, never both and never neither. */
    public sealed interface Scope {
        /** The error concerns a surface. */
        public data class OnSurface(public val surfaceId: String) : Scope

        /** The error concerns an in-flight function call. */
        public data class OnCall(public val functionCallId: CallId) : Scope
    }
}

// --- serializers ---------------------------------------------------------------------------

internal object ActionMessageSerializer : KSerializer<ActionMessage> {
    private val MODELLED =
        setOf("name", "surfaceId", "sourceComponentId", "timestamp", "context", "userMessage", "metadata")

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = SerialDescriptor(
        "dev.ynagai.a2ui.core.protocol.ActionMessage",
        JsonElement.serializer().descriptor,
    )

    override fun deserialize(decoder: Decoder): ActionMessage {
        val json = (decoder as JsonDecoder).json
        val obj = decoder.jsonObjectOrFail("action")
        fun required(key: String): String =
            (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw A2uiFormatException("action: `$key` is required and must be a string.")
        return ActionMessage(
            name = required("name"),
            surfaceId = required("surfaceId"),
            sourceComponentId = required("sourceComponentId"),
            timestamp = required("timestamp"),
            context = obj["context"] as? JsonObject
                ?: throw A2uiFormatException("action: `context` is required and must be an object."),
            userMessage = (obj["userMessage"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            metadata = obj["metadata"]?.let {
                json.decodeFromJsonElement(Metadata.serializer(), it)
            },
            additional = obj.filterKeys { it !in MODELLED },
        )
    }

    override fun serialize(encoder: Encoder, value: ActionMessage) {
        val json = encoder as JsonEncoder
        json.encodeJsonElement(
            buildJsonObject {
                put("name", JsonPrimitive(value.name))
                value.userMessage?.let { put("userMessage", JsonPrimitive(it)) }
                put("surfaceId", JsonPrimitive(value.surfaceId))
                put("sourceComponentId", JsonPrimitive(value.sourceComponentId))
                put("timestamp", JsonPrimitive(value.timestamp))
                put("context", value.context)
                value.metadata?.let {
                    put("metadata", json.json.encodeToJsonElement(Metadata.serializer(), it))
                }
                value.additional.forEach { (key, element) -> put(key, element) }
            },
        )
    }
}

/** Carries a [FunctionResponse] as the whole envelope body, with no wrapper object of its own. */
internal object RendererFunctionResponseMessageSerializer :
    KSerializer<RendererFunctionResponseMessage> {
    override val descriptor: SerialDescriptor = FunctionResponseSerializer.descriptor

    override fun deserialize(decoder: Decoder): RendererFunctionResponseMessage =
        RendererFunctionResponseMessage(FunctionResponseSerializer.deserialize(decoder))

    override fun serialize(encoder: Encoder, value: RendererFunctionResponseMessage) {
        FunctionResponseSerializer.serialize(encoder, value.response)
    }
}

internal object RendererErrorMessageSerializer : KSerializer<RendererErrorMessage> {
    private val VALIDATION_KEYS = setOf("code", "surfaceId", "path", "message")
    private val GENERIC_MODELLED = setOf("code", "message", "surfaceId", "functionCallId")

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = SerialDescriptor(
        "dev.ynagai.a2ui.core.protocol.RendererErrorMessage",
        JsonElement.serializer().descriptor,
    )

    override fun deserialize(decoder: Decoder): RendererErrorMessage {
        val obj = decoder.jsonObjectOrFail("error")
        val code = (obj["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw A2uiFormatException("error: `code` is required and must be a string.")
        val message = (obj["message"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw A2uiFormatException("error: `message` is required and must be a string.")
        val reserved = ValidationErrorCode.byWireName[code]
        if (reserved != null) {
            obj.rejectUnknownKeys(VALIDATION_KEYS, "error", decoder)
            val surfaceId = (obj["surfaceId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw A2uiFormatException("error: `$code` requires `surfaceId`.")
            val path = (obj["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw A2uiFormatException("error: `$code` requires `path`.")
            return RendererErrorMessage.Validation(reserved, surfaceId, path, message)
        }
        val surfaceId = (obj["surfaceId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val callId = (obj["functionCallId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val scope = when {
            surfaceId != null && callId != null -> throw A2uiFormatException(
                "error: `surfaceId` and `functionCallId` are mutually exclusive.",
            )
            surfaceId != null -> RendererErrorMessage.Scope.OnSurface(surfaceId)
            callId != null -> RendererErrorMessage.Scope.OnCall(callId)
            else -> throw A2uiFormatException(
                "error: requires either `surfaceId` or `functionCallId`.",
            )
        }
        return RendererErrorMessage.Generic(
            code = code,
            message = message,
            scope = scope,
            additional = obj.filterKeys { it !in GENERIC_MODELLED },
        )
    }

    override fun serialize(encoder: Encoder, value: RendererErrorMessage) {
        val json = encoder as JsonEncoder
        json.encodeJsonElement(
            buildJsonObject {
                put("code", JsonPrimitive(value.code))
                when (value) {
                    is RendererErrorMessage.Validation -> {
                        put("surfaceId", JsonPrimitive(value.surfaceId))
                        put("path", JsonPrimitive(value.path))
                        put("message", JsonPrimitive(value.message))
                    }
                    is RendererErrorMessage.Generic -> {
                        put("message", JsonPrimitive(value.message))
                        when (val scope = value.scope) {
                            is RendererErrorMessage.Scope.OnSurface ->
                                put("surfaceId", JsonPrimitive(scope.surfaceId))
                            is RendererErrorMessage.Scope.OnCall ->
                                put("functionCallId", JsonPrimitive(scope.functionCallId))
                        }
                        value.additional.forEach { (key, element) -> put(key, element) }
                    }
                }
            },
        )
    }
}

internal object RendererToAgentMessageSerializer :
    EnvelopeSerializer<RendererToAgentMessage>("renderer-to-agent message") {

    override val variants: Map<String, KSerializer<out RendererToAgentMessage>> = mapOf(
        "action" to ActionMessageSerializer,
        "callAgentFunction" to CallAgentFunctionMessage.serializer(),
        "rendererFunctionResponse" to RendererFunctionResponseMessageSerializer,
        "error" to RendererErrorMessageSerializer,
    )

    override fun keyOf(value: RendererToAgentMessage): String = when (value) {
        is ActionMessage -> "action"
        is CallAgentFunctionMessage -> "callAgentFunction"
        is RendererFunctionResponseMessage -> "rendererFunctionResponse"
        is RendererErrorMessage -> "error"
    }
}
