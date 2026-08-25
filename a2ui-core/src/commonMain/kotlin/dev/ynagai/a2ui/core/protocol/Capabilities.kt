package dev.ynagai.a2ui.core.protocol

import dev.ynagai.a2ui.core.A2ui
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * What an agent can generate, advertised to renderers.
 *
 * Keyed by protocol version so one agent card can carry several. This library reads and writes
 * the [A2ui.PROTOCOL_VERSION] entry.
 */
@Serializable
public data class AgentCapabilities(
    @SerialName("v1.0") public val v1: AgentCapabilitiesV1,
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

/** What a renderer can render, sent to the agent alongside its messages. */
@Serializable
public data class RendererCapabilities(
    @SerialName("v1.0") public val v1: RendererCapabilitiesV1,
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
@Serializable
public data class RendererDataModel(
    public val version: String = A2ui.PROTOCOL_VERSION,
    public val surfaces: Map<String, JsonObject>,
)
