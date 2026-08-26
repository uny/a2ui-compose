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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject

/** What a catalog function hands back to its caller. */
@Serializable
public enum class ReturnType(public val wireName: String) {
    @SerialName("string")
    STRING("string"),

    @SerialName("number")
    NUMBER("number"),

    @SerialName("boolean")
    BOOLEAN("boolean"),

    @SerialName("array")
    ARRAY("array"),

    @SerialName("object")
    OBJECT("object"),

    @SerialName("validationResult")
    VALIDATION_RESULT("validationResult"),

    @SerialName("any")
    ANY("any"),

    @SerialName("void")
    VOID("void"),
    ;

    public companion object {
        internal val byWireName: Map<String, ReturnType> = entries.associateBy { it.wireName }
    }
}

/** Which side of the connection may invoke a catalog function. */
@Serializable
public enum class AllowedCallers(public val wireName: String) {
    @SerialName("rendererOnly")
    RENDERER_ONLY("rendererOnly"),

    @SerialName("agentOnly")
    AGENT_ONLY("agentOnly"),

    @SerialName("rendererOrAgent")
    RENDERER_OR_AGENT("rendererOrAgent"),
    ;

    public companion object {
        /** The value a definition that omits `allowedCallers` is read with. */
        public val DEFAULT: AllowedCallers = RENDERER_ONLY

        internal val byWireName: Map<String, AllowedCallers> = entries.associateBy { it.wireName }
    }
}

/** How severely a failed check should be reported. */
@Serializable
public enum class Severity(public val wireName: String) {
    @SerialName("error")
    ERROR("error"),

    @SerialName("warning")
    WARNING("warning"),

    @SerialName("info")
    INFO("info"),
    ;

    public companion object {
        /** The value a result that omits `severity` is read with. */
        public val DEFAULT: Severity = ERROR
    }
}

/**
 * What a check's condition evaluates to.
 *
 * [additional] is carried rather than rejected because the schema leaves this object open.
 */
@Serializable(with = ValidationResultSerializer::class)
public data class ValidationResult(
    public val valid: Boolean,
    public val code: String? = null,
    public val message: String? = null,
    public val severity: Severity? = null,
    public val additional: Map<String, JsonElement> = emptyMap(),
)

/**
 * The JSON Schema a catalog gives for one function's wire-level [FunctionCall].
 *
 * This is kept as [raw] rather than decoded into a Kotlin model of JSON Schema. Writing a schema
 * validator for Kotlin Multiplatform was ruled out as being the size of the whole renderer; what
 * the function-call checker actually needs from a definition is the handful of keywords surfaced
 * below, read off the raw object.
 */
public data class FunctionCallValidationSchema(public val raw: JsonObject) {
    /** The function's name, from `properties.call.const`. Null for an `allOf`-shaped definition. */
    public val callName: String?
        get() = (raw["properties"] as? JsonObject)
            ?.get("call")
            ?.let { it as? JsonObject }
            ?.get("const")
            ?.let { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }

    /** The JSON Schema for this function's `args`, if it declares any. */
    public val argsSchema: JsonObject?
        get() = (raw["properties"] as? JsonObject)?.get("args") as? JsonObject

    /**
     * The keys the call object must carry, or empty when the definition omits `required`.
     *
     * A conformant v1.0 definition lists `call`, but an `allOf`-shaped one carries its `required`
     * inside a branch — so a caller must treat an empty list as "this definition does not say",
     * not as "nothing is required".
     */
    public val required: List<String>
        get() = (raw["required"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
            .orEmpty()

    /** The composed branches of an `allOf`-shaped definition, if this is one. */
    public val allOf: List<JsonObject>?
        get() = (raw["allOf"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it as? JsonObject }
}

/** A catalog's definition of one function. */
@Serializable(with = FunctionDefinitionSerializer::class)
public data class FunctionDefinition(
    public val schema: FunctionCallValidationSchema,
    public val returnType: ReturnType,
    public val allowedCallers: AllowedCallers? = null,
    public val requiresUserActivation: Boolean? = null,
) {
    /** [allowedCallers] with the schema default applied. */
    public val effectiveAllowedCallers: AllowedCallers
        get() = allowedCallers ?: AllowedCallers.DEFAULT
}

/**
 * A catalog's definition of one component: a JSON Schema for its properties, plus the two
 * keywords that constrain where it may sit in the tree.
 *
 * [schema] is the definition object verbatim, [allowedParents] and [allowedChildren] are lifted
 * out of it for the composition check. On the way out [schema] wins for any key it already
 * carries — it is the unparsed original, so re-deriving a key from the typed field could only
 * lose what the model does not name — and the typed fields supply the keys it omits, which is
 * what lets a definition built in Kotlin round trip at all. A null list means "unconstrained", which is not the same
 * as an empty one — `"allowedParents": []` is how a component is barred from every parent.
 */
@Serializable(with = ComponentDefinitionSerializer::class)
public data class ComponentDefinition(
    public val schema: JsonObject,
    public val allowedParents: List<String>? = null,
    public val allowedChildren: List<String>? = null,
    public val metadata: Metadata? = null,
)

/**
 * A collection of component and function definitions that a surface renders against.
 *
 * [components] and [functions] hold their definitions keyed by type name.
 *
 * [schemaKeywords] carries `$schema`, `$id`, and `$defs` through unread. A definition may
 * reference `#/$defs/...`, so dropping them would leave a re-encoded catalog — an inline one
 * carried in [RendererCapabilitiesV1.inlineCatalogs], say — with unresolvable references.
 */
@Serializable(with = CatalogDefinitionSerializer::class)
public data class CatalogDefinition(
    public val catalogId: String,
    public val protocolVersion: String? = null,
    public val title: String? = null,
    public val description: String? = null,
    public val instructions: String? = null,
    public val components: Map<String, ComponentDefinition> = emptyMap(),
    public val functions: Map<String, FunctionDefinition> = emptyMap(),
    public val schemaKeywords: Map<String, JsonElement> = emptyMap(),
) {
    /** [protocolVersion] with the schema default applied. */
    public val effectiveProtocolVersion: String
        get() = protocolVersion ?: DEFAULT_PROTOCOL_VERSION

    public companion object {
        /** The version a catalog that omits `protocolVersion` is read as declaring. */
        public const val DEFAULT_PROTOCOL_VERSION: String = "0.9"

        /**
         * The value [protocolVersion] carries on a v1.0 catalog.
         *
         * Note the spelling: a catalog says `"1.0"`, while the message envelope and the
         * capabilities key say `"v1.0"` ([dev.ynagai.a2ui.core.A2ui.PROTOCOL_VERSION]). They are
         * not interchangeable, so comparing [effectiveProtocolVersion] against the envelope
         * constant is always false — compare against this one.
         */
        public const val PROTOCOL_VERSION: String = "1.0"
    }
}

// --- serializers ---------------------------------------------------------------------------

internal object ValidationResultSerializer : KSerializer<ValidationResult> {
    private val MODELLED = setOf("valid", "code", "message", "severity")

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = SerialDescriptor(
        "dev.ynagai.a2ui.core.protocol.ValidationResult",
        JsonElement.serializer().descriptor,
    )

    override fun deserialize(decoder: Decoder): ValidationResult {
        val obj = decoder.jsonObjectOrFail("ValidationResult")
        val valid = (obj["valid"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            ?: throw A2uiFormatException("ValidationResult: `valid` is required and must be a boolean.")
        val severity = obj.optionalString("severity", "ValidationResult")?.let { name ->
            Severity.entries.firstOrNull { it.wireName == name }
                ?: throw A2uiFormatException("ValidationResult: `$name` is not a severity.")
        }
        return ValidationResult(
            valid = valid,
            code = obj.optionalString("code", "ValidationResult"),
            message = obj.optionalString("message", "ValidationResult"),
            severity = severity,
            additional = obj.filterKeys { it !in MODELLED },
        )
    }

    override fun serialize(encoder: Encoder, value: ValidationResult) {
        (encoder as JsonEncoder).encodeJsonElement(
            buildJsonObject {
                put("valid", JsonPrimitive(value.valid))
                value.code?.let { put("code", JsonPrimitive(it)) }
                value.message?.let { put("message", JsonPrimitive(it)) }
                value.severity?.let { put("severity", JsonPrimitive(it.wireName)) }
                value.additional.carryThrough(MODELLED).forEach { (key, element) ->
                    put(key, element)
                }
            },
        )
    }
}

internal object ComponentDefinitionSerializer : KSerializer<ComponentDefinition> {
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = SerialDescriptor(
        "dev.ynagai.a2ui.core.protocol.ComponentDefinition",
        JsonElement.serializer().descriptor,
    )

    override fun deserialize(decoder: Decoder): ComponentDefinition {
        val json = (decoder as JsonDecoder).json
        val obj = decoder.jsonObjectOrFail("ComponentDefinition")
        return ComponentDefinition(
            schema = obj,
            allowedParents = obj.stringList("allowedParents", "ComponentDefinition"),
            allowedChildren = obj.stringList("allowedChildren", "ComponentDefinition"),
            metadata = obj["metadata"]?.let {
                json.decodeFromJsonElement(Metadata.serializer(), it)
            },
        )
    }

    override fun serialize(encoder: Encoder, value: ComponentDefinition) {
        val json = encoder as JsonEncoder
        json.encodeJsonElement(
            buildJsonObject {
                value.schema.forEach { (key, element) -> put(key, element) }
                if ("allowedParents" !in value.schema) {
                    value.allowedParents?.let { put("allowedParents", stringArray(it)) }
                }
                if ("allowedChildren" !in value.schema) {
                    value.allowedChildren?.let { put("allowedChildren", stringArray(it)) }
                }
                if ("metadata" !in value.schema) {
                    value.metadata?.let {
                        put("metadata", json.json.encodeToJsonElement(Metadata.serializer(), it))
                    }
                }
            },
        )
    }
}

internal object FunctionDefinitionSerializer : KSerializer<FunctionDefinition> {
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = SerialDescriptor(
        "dev.ynagai.a2ui.core.protocol.FunctionDefinition",
        JsonElement.serializer().descriptor,
    )

    override fun deserialize(decoder: Decoder): FunctionDefinition {
        val obj = decoder.jsonObjectOrFail("FunctionDefinition")
        val returnTypeName = obj.optionalString("returnType", "FunctionDefinition")
            ?: throw A2uiFormatException("FunctionDefinition: `returnType` is required.")
        val returnType = ReturnType.byWireName[returnTypeName]
            ?: throw A2uiFormatException("FunctionDefinition: `$returnTypeName` is not a return type.")
        val callersName = obj.optionalString("allowedCallers", "FunctionDefinition")
        val callers = callersName?.let {
            AllowedCallers.byWireName[it]
                ?: throw A2uiFormatException("FunctionDefinition: `$it` is not an allowedCallers value.")
        }
        return FunctionDefinition(
            schema = FunctionCallValidationSchema(obj),
            returnType = returnType,
            allowedCallers = callers,
            requiresUserActivation = obj.optionalBoolean("requiresUserActivation", "FunctionDefinition"),
        )
    }

    override fun serialize(encoder: Encoder, value: FunctionDefinition) {
        (encoder as JsonEncoder).encodeJsonElement(
            buildJsonObject {
                value.schema.raw.forEach { (key, element) -> put(key, element) }
                if ("returnType" !in value.schema.raw) {
                    put("returnType", JsonPrimitive(value.returnType.wireName))
                }
                if ("allowedCallers" !in value.schema.raw) {
                    value.allowedCallers?.let { put("allowedCallers", JsonPrimitive(it.wireName)) }
                }
                if ("requiresUserActivation" !in value.schema.raw) {
                    value.requiresUserActivation?.let {
                        put("requiresUserActivation", JsonPrimitive(it))
                    }
                }
            },
        )
    }
}

internal object CatalogDefinitionSerializer : KSerializer<CatalogDefinition> {
    private val SCHEMA_KEYWORDS = setOf("\$schema", "\$id", "\$defs")
    private val KEYS = SCHEMA_KEYWORDS + setOf(
        "protocolVersion", "title", "description", "catalogId", "instructions", "components",
        "functions",
    )

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = SerialDescriptor(
        "dev.ynagai.a2ui.core.protocol.CatalogDefinition",
        JsonElement.serializer().descriptor,
    )

    override fun deserialize(decoder: Decoder): CatalogDefinition {
        val json = (decoder as JsonDecoder).json
        val obj = decoder.jsonObjectOrFail("CatalogDefinition")
        obj.rejectUnknownKeys(KEYS, "CatalogDefinition", decoder)
        val catalogId = obj.requiredString("catalogId", "CatalogDefinition")
        return CatalogDefinition(
            catalogId = catalogId,
            protocolVersion = obj.optionalString("protocolVersion", "CatalogDefinition"),
            title = obj.optionalString("title", "CatalogDefinition"),
            description = obj.optionalString("description", "CatalogDefinition"),
            instructions = obj.optionalString("instructions", "CatalogDefinition"),
            components = obj.optionalObject("components", "CatalogDefinition").orEmpty()
                .mapValues { (_, element) ->
                    json.decodeFromJsonElement(ComponentDefinitionSerializer, element)
                },
            functions = obj.optionalObject("functions", "CatalogDefinition").orEmpty()
                .mapValues { (_, element) ->
                    json.decodeFromJsonElement(FunctionDefinitionSerializer, element)
                },
            schemaKeywords = obj.filterKeys { it in SCHEMA_KEYWORDS },
        )
    }

    override fun serialize(encoder: Encoder, value: CatalogDefinition) {
        val json = encoder as JsonEncoder
        json.encodeJsonElement(
            buildJsonObject {
                value.schemaKeywords.forEach { (key, element) -> put(key, element) }
                put("catalogId", JsonPrimitive(value.catalogId))
                value.protocolVersion?.let { put("protocolVersion", JsonPrimitive(it)) }
                value.title?.let { put("title", JsonPrimitive(it)) }
                value.description?.let { put("description", JsonPrimitive(it)) }
                value.instructions?.let { put("instructions", JsonPrimitive(it)) }
                if (value.components.isNotEmpty()) {
                    put(
                        "components",
                        buildJsonObject {
                            value.components.forEach { (name, definition) ->
                                put(
                                    name,
                                    json.json.encodeToJsonElement(
                                        ComponentDefinitionSerializer,
                                        definition,
                                    ),
                                )
                            }
                        },
                    )
                }
                if (value.functions.isNotEmpty()) {
                    put(
                        "functions",
                        buildJsonObject {
                            value.functions.forEach { (name, definition) ->
                                put(
                                    name,
                                    json.json.encodeToJsonElement(
                                        FunctionDefinitionSerializer,
                                        definition,
                                    ),
                                )
                            }
                        },
                    )
                }
            },
        )
    }
}

private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()

private fun stringArray(values: List<String>): kotlinx.serialization.json.JsonArray =
    kotlinx.serialization.json.JsonArray(values.map(::JsonPrimitive))

/**
 * Reads an optional array-of-strings keyword.
 *
 * A key that is present but not an array of strings is rejected rather than read as absent: the
 * difference between "unconstrained" and "constrained to nothing" is exactly what these keywords
 * carry, so quietly dropping a malformed one would widen the constraint instead of failing.
 */
private fun JsonObject.stringList(key: String, owner: String): List<String>? {
    val value = this[key] ?: return null
    val array = value as? kotlinx.serialization.json.JsonArray
        ?: throw A2uiFormatException("$owner: `$key` must be an array of component type names.")
    return array.map { item ->
        (item as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw A2uiFormatException("$owner: `$key` must be an array of component type names.")
    }
}
