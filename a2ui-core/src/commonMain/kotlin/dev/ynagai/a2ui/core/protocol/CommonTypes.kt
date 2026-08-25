package dev.ynagai.a2ui.core.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
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

/** The identifier of a component, unique within its surface. */
public typealias ComponentId = String

/** The identifier of a single function invocation, echoed back on its response. */
public typealias CallId = String

/** A reference to one child component, by id. */
public typealias Child = ComponentId

/**
 * Vendor extension metadata.
 *
 * Keys must be Unicode identifiers (UAX #31) and keys beginning with `a2ui_` are reserved for
 * official extensions. Neither rule is enforced here — key validation belongs to the validator.
 */
public typealias Extensions = Map<String, JsonElement>

/** The reserved canonical container component that every surface is implicitly rooted in. */
public object Surface {
    /** The component type name, which a catalog may not define and a message may not create. */
    public const val COMPONENT: String = "Surface"

    /** The id of the component the surface renders as its root. */
    public const val ROOT_ID: ComponentId = "root"
}

/** How assistive technology should announce updates to an element (WAI-ARIA `aria-live`). */
@Serializable
public enum class LiveRegion {
    @SerialName("off")
    OFF,

    @SerialName("polite")
    POLITE,

    @SerialName("assertive")
    ASSERTIVE,
}

/** Attributes that describe an element to assistive technology. */
@Serializable
public data class AccessibilityAttributes(
    public val label: DynamicString? = null,
    public val description: DynamicString? = null,
    public val live: LiveRegion? = null,
    public val hidden: DynamicBoolean? = null,
)

/** The `metadata` envelope carried by surfaces, components, catalogs, and renderer actions. */
@Serializable
public data class Metadata(public val extensions: Extensions? = null)

/**
 * The children of a container: either a fixed list of ids, or a template expanded once per item
 * of a list in the data model.
 */
@Serializable(with = ChildListSerializer::class)
public sealed interface ChildList {
    /** A fixed list of child component ids. */
    public data class Static(public val ids: List<ComponentId>) : ChildList

    /** A template component instantiated once per item at [path] in the data model. */
    @Serializable
    public data class Template(
        public val componentId: ComponentId,
        public val path: String,
    ) : ChildList
}

/** One validation check on an input component. [condition] evaluates to a [ValidationResult]. */
@Serializable
public data class CheckRule(
    public val condition: BoundValue,
    public val message: String? = null,
)

/**
 * The `checks` mixin that catalog schemas fold into input components.
 *
 * Components carry their catalog-defined properties as an unparsed bag (see `Component`), so this
 * type is not reached by decoding a component directly — it is the shape the validator reads
 * `checks` back out as once the catalog says the component is checkable.
 */
@Serializable
public data class Checkable(public val checks: List<CheckRule>? = null)

/** The event an [Action.Event] dispatches to the agent. */
@Serializable
public data class ActionEvent(
    public val name: String,
    public val userMessage: DynamicString? = null,
    public val context: Map<String, DynamicValue>? = null,
)

/** An interaction handler: either dispatch an event to the agent, or run a function locally. */
@Serializable(with = ActionSerializer::class)
public sealed interface Action {
    /** Dispatches [event] to the agent. */
    public data class Event(public val event: ActionEvent) : Action

    /** Executes [functionCall] on the renderer or the agent, per the catalog's `allowedCallers`. */
    public data class Invoke(public val functionCall: FunctionCall) : Action
}

/** The failure half of a [FunctionResponse]. */
@Serializable
public data class FunctionError(
    public val code: String,
    public val message: String,
)

/**
 * The response to a `callRendererFunction` or `callAgentFunction` invocation.
 *
 * The schema's "exactly one of `value` or `error`" is expressed as the two variants, so a
 * response carrying both or neither cannot be constructed, only rejected while decoding.
 */
@Serializable(with = FunctionResponseSerializer::class)
public sealed interface FunctionResponse {
    /** The id of the invocation this responds to. */
    public val functionCallId: CallId

    /**
     * The function returned [value].
     *
     * [value] is a [JsonElement] rather than a nullable Kotlin value because the schema treats an
     * explicit `"value": null` as a return of null, which is a success — distinct from omitting
     * `value`, which is malformed.
     */
    public data class Success(
        override val functionCallId: CallId,
        public val value: JsonElement,
    ) : FunctionResponse

    /** The function failed with [error]. */
    public data class Failure(
        override val functionCallId: CallId,
        public val error: FunctionError,
    ) : FunctionResponse
}

// --- serializers ---------------------------------------------------------------------------

internal fun Decoder.jsonObjectOrFail(owner: String): JsonObject {
    val element = (this as? JsonDecoder)?.decodeJsonElement()
        ?: throw A2uiFormatException("A2UI payloads can only be read from JSON.")
    return element as? JsonObject
        ?: throw A2uiFormatException("$owner: expected an object.")
}

/**
 * Rejects keys outside [allowed], unless the caller opted into [A2uiJson.lenient].
 *
 * The generated serializers get this from `ignoreUnknownKeys`; the hand-written ones below have
 * to apply the same rule themselves, reading the setting off the decoder so both honour the same
 * switch.
 */
internal fun JsonObject.rejectUnknownKeys(allowed: Set<String>, owner: String, decoder: Decoder) {
    if ((decoder as? JsonDecoder)?.json?.configuration?.ignoreUnknownKeys == true) return
    val unknown = keys - allowed
    if (unknown.isNotEmpty()) {
        throw A2uiFormatException("$owner: unexpected ${unknown.joinToString()}.")
    }
}

internal object BoundValueSerializer : KSerializer<BoundValue> {
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor =
        SerialDescriptor("dev.ynagai.a2ui.core.protocol.BoundValue", JsonElement.serializer().descriptor)

    override fun deserialize(decoder: Decoder): BoundValue {
        val json = (decoder as? JsonDecoder)?.json
            ?: throw A2uiFormatException("A2UI payloads can only be read from JSON.")
        val element = decoder.decodeJsonElement()
        return decodeBoundValue(element, json, "BoundValue")
            ?: throw A2uiFormatException("BoundValue: expected a DataBinding or a FunctionCall.")
    }

    override fun serialize(encoder: Encoder, value: BoundValue) {
        val json = encoder as? JsonEncoder
            ?: throw A2uiFormatException("A2UI payloads can only be written as JSON.")
        json.encodeJsonElement(encodeBoundValue(value, json.json))
    }
}

internal object ChildListSerializer : KSerializer<ChildList> {
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor =
        SerialDescriptor("dev.ynagai.a2ui.core.protocol.ChildList", JsonElement.serializer().descriptor)

    override fun deserialize(decoder: Decoder): ChildList {
        val json = (decoder as? JsonDecoder)?.json
            ?: throw A2uiFormatException("A2UI payloads can only be read from JSON.")
        return when (val element = decoder.decodeJsonElement()) {
            is kotlinx.serialization.json.JsonArray -> ChildList.Static(
                element.map { item ->
                    (item as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: throw A2uiFormatException("ChildList: every child must be a component id.")
                },
            )
            is JsonObject -> json.decodeFromJsonElement(ChildList.Template.serializer(), element)
            else -> throw A2uiFormatException(
                "ChildList: expected an array of component ids or a list template.",
            )
        }
    }

    override fun serialize(encoder: Encoder, value: ChildList) {
        val json = encoder as? JsonEncoder
            ?: throw A2uiFormatException("A2UI payloads can only be written as JSON.")
        json.encodeJsonElement(
            when (value) {
                is ChildList.Static -> kotlinx.serialization.json.JsonArray(
                    value.ids.map(::JsonPrimitive),
                )
                is ChildList.Template ->
                    json.json.encodeToJsonElement(ChildList.Template.serializer(), value)
            },
        )
    }
}

internal object ActionSerializer : KSerializer<Action> {
    private val KEYS = setOf("event", "functionCall")

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor =
        SerialDescriptor("dev.ynagai.a2ui.core.protocol.Action", JsonElement.serializer().descriptor)

    override fun deserialize(decoder: Decoder): Action {
        val json = (decoder as JsonDecoder).json
        val obj = decoder.jsonObjectOrFail("Action")
        obj.rejectUnknownKeys(KEYS, "Action", decoder)
        val hasEvent = "event" in obj
        val hasCall = "functionCall" in obj
        return when {
            hasEvent && hasCall -> throw A2uiFormatException(
                "Action: `event` and `functionCall` are mutually exclusive.",
            )
            hasEvent -> Action.Event(
                json.decodeFromJsonElement(ActionEvent.serializer(), obj.getValue("event")),
            )
            hasCall -> Action.Invoke(
                json.decodeFromJsonElement(FunctionCall.serializer(), obj.getValue("functionCall")),
            )
            else -> throw A2uiFormatException("Action: requires `event` or `functionCall`.")
        }
    }

    override fun serialize(encoder: Encoder, value: Action) {
        val json = encoder as JsonEncoder
        json.encodeJsonElement(
            buildJsonObject {
                when (value) {
                    is Action.Event ->
                        put("event", json.json.encodeToJsonElement(ActionEvent.serializer(), value.event))
                    is Action.Invoke -> put(
                        "functionCall",
                        json.json.encodeToJsonElement(FunctionCall.serializer(), value.functionCall),
                    )
                }
            },
        )
    }
}

internal object FunctionResponseSerializer : KSerializer<FunctionResponse> {
    private val KEYS = setOf("functionCallId", "value", "error")

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = SerialDescriptor(
        "dev.ynagai.a2ui.core.protocol.FunctionResponse",
        JsonElement.serializer().descriptor,
    )

    override fun deserialize(decoder: Decoder): FunctionResponse {
        val json = (decoder as JsonDecoder).json
        val obj = decoder.jsonObjectOrFail("FunctionResponse")
        obj.rejectUnknownKeys(KEYS, "FunctionResponse", decoder)
        val callId = (obj["functionCallId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw A2uiFormatException("FunctionResponse: `functionCallId` is required.")
        val hasValue = "value" in obj
        val hasError = "error" in obj
        return when {
            hasValue && hasError -> throw A2uiFormatException(
                "FunctionResponse: `value` and `error` are mutually exclusive.",
            )
            hasValue -> FunctionResponse.Success(callId, obj.getValue("value"))
            hasError -> FunctionResponse.Failure(
                callId,
                json.decodeFromJsonElement(FunctionError.serializer(), obj.getValue("error")),
            )
            else -> throw A2uiFormatException("FunctionResponse: requires `value` or `error`.")
        }
    }

    override fun serialize(encoder: Encoder, value: FunctionResponse) {
        val json = encoder as JsonEncoder
        json.encodeJsonElement(
            buildJsonObject {
                put("functionCallId", JsonPrimitive(value.functionCallId))
                when (value) {
                    is FunctionResponse.Success -> put("value", value.value)
                    is FunctionResponse.Failure -> put(
                        "error",
                        json.json.encodeToJsonElement(FunctionError.serializer(), value.error),
                    )
                }
            },
        )
    }
}
