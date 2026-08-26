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
 * What an agent can generate, advertised to renderers.
 *
 * The object is keyed by protocol version so one agent card can advertise several at once. This
 * library reads the [A2ui.PROTOCOL_VERSION] entry into [v1] and carries the rest through in
 * [otherVersions] — refusing a card for also advertising v0.9 would refuse most real agents.
 */
@Serializable(with = AgentCapabilitiesSerializer::class)
public data class AgentCapabilities(
    public val v1: AgentCapabilitiesV1,
    public val otherVersions: Map<String, JsonElement> = emptyMap(),
)

/** The v1.0 half of [AgentCapabilities]. */
@Serializable
public data class AgentCapabilitiesV1(
    public val supportedCatalogIds: List<String>? = null,
    public val acceptsInlineCatalogs: Boolean? = null,
) {
    /** [acceptsInlineCatalogs] with the schema default applied. */
    public val acceptsInlineCatalogsOrDefault: Boolean get() = acceptsInlineCatalogs ?: false
}

/**
 * What a renderer can render, sent to the agent alongside its messages.
 *
 * Keyed by protocol version, and carried through, the same way [AgentCapabilities] is.
 */
@Serializable(with = RendererCapabilitiesSerializer::class)
public data class RendererCapabilities(
    public val v1: RendererCapabilitiesV1,
    public val otherVersions: Map<String, JsonElement> = emptyMap(),
)

/**
 * The v1.0 half of [RendererCapabilities].
 *
 * [inlineCatalogs] should only be sent to an agent that advertised
 * [AgentCapabilitiesV1.acceptsInlineCatalogs].
 */
@Serializable
public data class RendererCapabilitiesV1(
    public val supportedCatalogIds: List<String>,
    public val inlineCatalogs: List<CatalogDefinition>? = null,
)

/**
 * The data models of every live surface, for synchronising them back to the agent.
 *
 * Sent when a surface was created with `sendDataModel`.
 */
@Serializable(with = RendererDataModelSerializer::class)
public data class RendererDataModel(
    public val version: String = A2ui.PROTOCOL_VERSION,
    public val surfaces: Map<String, JsonObject>,
) {
    init {
        if (version != A2ui.PROTOCOL_VERSION) {
            throw A2uiFormatException(
                "renderer data model: unsupported protocol version `$version`; this library " +
                    "implements ${A2ui.PROTOCOL_VERSION} only.",
            )
        }
    }
}

// --- serializers ---------------------------------------------------------------------------

/** Reads the [A2ui.PROTOCOL_VERSION] entry of a version-keyed object, keeping its siblings. */
internal abstract class VersionKeyedSerializer<T : Any, V : Any>(
    private val what: String,
    private val version: KSerializer<V>,
) : KSerializer<T> {
    protected abstract fun create(v1: V, otherVersions: Map<String, JsonElement>): T

    protected abstract fun v1Of(value: T): V

    protected abstract fun otherVersionsOf(value: T): Map<String, JsonElement>

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor =
        SerialDescriptor("dev.ynagai.a2ui.core.protocol.VersionKeyed", JsonElement.serializer().descriptor)

    override fun deserialize(decoder: Decoder): T {
        val json = (decoder as JsonDecoder).json
        val obj = decoder.jsonObjectOrFail(what)
        val current = obj[A2ui.PROTOCOL_VERSION]
            ?: throw A2uiFormatException("$what: `${A2ui.PROTOCOL_VERSION}` is required.")
        return create(
            json.decodeFromJsonElement(version, current),
            obj.filterKeys { it != A2ui.PROTOCOL_VERSION },
        )
    }

    override fun serialize(encoder: Encoder, value: T) {
        val json = encoder as JsonEncoder
        json.encodeJsonElement(
            buildJsonObject {
                put(A2ui.PROTOCOL_VERSION, json.json.encodeToJsonElement(version, v1Of(value)))
                otherVersionsOf(value).carryThrough(setOf(A2ui.PROTOCOL_VERSION))
                    .forEach { (key, element) -> put(key, element) }
            },
        )
    }
}

/**
 * Reads [RendererDataModel], rejecting a payload that declares another protocol version.
 *
 * The generated serializer would substitute the default for an absent `version` and accept any
 * string for a present one, which is the opposite of what every message envelope does.
 */
internal object RendererDataModelSerializer : KSerializer<RendererDataModel> {
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = SerialDescriptor(
        "dev.ynagai.a2ui.core.protocol.RendererDataModel",
        JsonElement.serializer().descriptor,
    )

    override fun deserialize(decoder: Decoder): RendererDataModel {
        val json = (decoder as JsonDecoder).json
        val obj = decoder.jsonObjectOrFail("renderer data model")
        obj.rejectUnknownKeys(setOf("version", "surfaces"), "renderer data model", decoder)
        val version = obj.requiredString("version", "renderer data model")
        val surfaces = obj.optionalObject("surfaces", "renderer data model")
            ?: throw A2uiFormatException("renderer data model: `surfaces` is required.")
        return RendererDataModel(
            version = version,
            surfaces = surfaces.mapValues { (key, element) ->
                element as? JsonObject
                    ?: throw A2uiFormatException(
                        "renderer data model: surface `$key` must be an object.",
                    )
            },
        )
    }

    override fun serialize(encoder: Encoder, value: RendererDataModel) {
        (encoder as JsonEncoder).encodeJsonElement(
            buildJsonObject {
                put("version", JsonPrimitive(value.version))
                put("surfaces", JsonObject(value.surfaces))
            },
        )
    }
}

internal object AgentCapabilitiesSerializer : VersionKeyedSerializer<AgentCapabilities, AgentCapabilitiesV1>(
    what = "agent capabilities",
    version = AgentCapabilitiesV1.serializer(),
) {
    override fun create(v1: AgentCapabilitiesV1, otherVersions: Map<String, JsonElement>) =
        AgentCapabilities(v1, otherVersions)

    override fun v1Of(value: AgentCapabilities) = value.v1

    override fun otherVersionsOf(value: AgentCapabilities) = value.otherVersions
}

internal object RendererCapabilitiesSerializer :
    VersionKeyedSerializer<RendererCapabilities, RendererCapabilitiesV1>(
        what = "renderer capabilities",
        version = RendererCapabilitiesV1.serializer(),
    ) {
    override fun create(v1: RendererCapabilitiesV1, otherVersions: Map<String, JsonElement>) =
        RendererCapabilities(v1, otherVersions)

    override fun v1Of(value: RendererCapabilities) = value.v1

    override fun otherVersionsOf(value: RendererCapabilities) = value.otherVersions
}
