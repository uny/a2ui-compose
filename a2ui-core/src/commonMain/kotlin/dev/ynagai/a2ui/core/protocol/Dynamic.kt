package dev.ynagai.a2ui.core.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * A value resolved against the surface at render time rather than carried literally on the wire:
 * either a [DataBinding] into the data model or a [FunctionCall].
 *
 * Every `Dynamic*` union in `common_types.json` is "a literal, or one of these two", so this is
 * the half they share.
 */
@Serializable(with = BoundValueSerializer::class)
public sealed interface BoundValue :
    DynamicValue,
    DynamicString,
    DynamicNumber,
    DynamicBoolean,
    DynamicStringList

/** A JSON Pointer into the surface's data model. */
@Serializable
public data class DataBinding(public val path: String) : BoundValue

/**
 * An invocation of a catalog function, or of the `@index` system function.
 *
 * This is the one shape for both: `common_types.json` gives `IndexSystemFunction` its own
 * definition, but it differs from a catalog call only in that `call` is the constant `@index`
 * and that it takes no `catalogId` — see [isSystemFunction]. `FunctionCommon` likewise collapses
 * into the [catalogId] property here.
 *
 * [args] is null when the wire object carried no `args` key at all, which is distinct from an
 * empty `args` object — the catalog schemas reject the latter for functions that require
 * arguments. Argument arity and types are checked against the catalog, not here.
 */
@Serializable
public data class FunctionCall(
    public val call: String,
    public val catalogId: String? = null,
    public val args: Map<String, JsonElement>? = null,
) : BoundValue {
    /**
     * True when this call names a system function, which no catalog defines.
     *
     * The `@` namespace is reserved -- `a2ui_protocol.md`'s System Namespace Rule gives it to
     * system context evaluations and bars catalogs from defining into it, which is why
     * [checkEntityNames] refuses such a definition. But v1.0 *populates* it with exactly one
     * name: `FunctionCall` is a `oneOf` over a catalog function and `IndexSystemFunction`, whose
     * `call` is the constant `@index`.
     *
     * So a reserved prefix is not a system function. Any other `@`-prefixed name is a call on
     * nothing — no catalog may define it and no system function answers to it — and it must fail
     * catalog resolution rather than skip it, because that is where the message saying so comes
     * from.
     */
    public val isSystemFunction: Boolean get() = call == INDEX

    public companion object {
        /** The `@index` system function, available only inside a list template's item scope. */
        public const val INDEX: String = "@index"
    }
}

/**
 * A value that may be a literal of any JSON type, a [DataBinding], or a [FunctionCall].
 *
 * Note that a literal here may be an arbitrary object. That is what forces the discrimination
 * rule in [decodeBoundValue]: an object is only ever read as a literal when it carries neither a
 * `path` nor a `call` key, so a malformed binding cannot slip through as "just some object".
 */
@Serializable(with = DynamicValueSerializer::class)
public sealed interface DynamicValue {
    /** A literal JSON value. Never [JsonNull] — the union does not admit null. */
    public data class Literal(public val value: JsonElement) : DynamicValue {
        init {
            // Enforced here rather than only while decoding: the encoder would otherwise emit a
            // bare `null` that this same serializer refuses to read back.
            if (value is JsonNull) {
                throw A2uiFormatException(
                    "DynamicValue: null is not one of the permitted value types.",
                )
            }
        }
    }
}

/** A string, a [DataBinding], or a [FunctionCall]. */
@Serializable(with = DynamicStringSerializer::class)
public sealed interface DynamicString {
    public data class Literal(public val value: String) : DynamicString
}

/** A number, a [DataBinding], or a [FunctionCall]. */
@Serializable(with = DynamicNumberSerializer::class)
public sealed interface DynamicNumber {
    /** A literal number. Never NaN or infinite — JSON has no way to write either. */
    public data class Literal(public val value: Double) : DynamicNumber {
        init {
            // Mirrors [DynamicValue.Literal]: enforced on the type rather than only in
            // `encodeNumber`, so a value that cannot be written cannot be built either.
            if (value.isNaN() || value.isInfinite()) {
                throw A2uiFormatException("DynamicNumber: `$value` is not a JSON number.")
            }
        }
    }
}

/** A boolean, a [DataBinding], or a [FunctionCall]. */
@Serializable(with = DynamicBooleanSerializer::class)
public sealed interface DynamicBoolean {
    public data class Literal(public val value: Boolean) : DynamicBoolean
}

/** An array of strings, a [DataBinding], or a [FunctionCall]. */
@Serializable(with = DynamicStringListSerializer::class)
public sealed interface DynamicStringList {
    public data class Literal(public val value: List<String>) : DynamicStringList
}

/**
 * Reads the [BoundValue] half of a `Dynamic*` union, or returns null when [element] should be
 * read as a literal instead.
 *
 * The discrimination is by key presence, not by "whichever branch happens to parse". An object
 * with a `path` key is a [DataBinding] and is held to that schema even when it is malformed;
 * likewise `call` and [FunctionCall]. Falling back to "try each branch" would let
 * `{"path": "/x", "extra": 1}` — which the specification rejects — be accepted as a literal
 * object, since [DynamicValue] admits arbitrary object literals.
 *
 * An object carrying both keys is ambiguous and is rejected rather than resolved by precedence.
 */
internal fun decodeBoundValue(element: JsonElement, json: Json, owner: String): BoundValue? {
    if (element !is JsonObject) return null
    val hasPath = "path" in element
    val hasCall = "call" in element
    return when {
        hasPath && hasCall -> throw A2uiFormatException(
            "$owner: object carries both `path` and `call`, so it is neither a DataBinding nor " +
                "a FunctionCall.",
        )
        hasPath -> json.decodeFromJsonElement(DataBinding.serializer(), element)
        hasCall -> json.decodeFromJsonElement(FunctionCall.serializer(), element)
        else -> null
    }
}

internal fun encodeBoundValue(value: BoundValue, json: Json): JsonElement = when (value) {
    is DataBinding -> json.encodeToJsonElement(DataBinding.serializer(), value)
    is FunctionCall -> json.encodeToJsonElement(FunctionCall.serializer(), value)
}

/**
 * Encodes a number so an integral value survives a round trip as an integer.
 *
 * JSON draws no line between `42` and `42.0`, but re-emitting `42` as `42.0` would make a
 * round-tripped payload differ textually from its input for no reason.
 */
internal fun encodeNumber(value: Double): JsonPrimitive {
    if (value.isNaN() || value.isInfinite()) {
        throw A2uiFormatException("DynamicNumber: `$value` is not a JSON number.")
    }
    // `toLong()` saturates, and Long.MAX_VALUE.toDouble() == 2^63 exactly, so the round-trip
    // guard below would otherwise pass for 2^63 and emit 9223372036854775807 — a different
    // integer from the one that came in. Only take the integer path inside Long's real range.
    if (value < -LONG_RANGE || value >= LONG_RANGE) return JsonPrimitive(value)
    val asLong = value.toLong()
    return if (asLong.toDouble() == value) JsonPrimitive(asLong) else JsonPrimitive(value)
}

/** 2^63 as a [Double]: the first magnitude `Double.toLong()` can no longer represent. */
private const val LONG_RANGE: Double = 9223372036854775808.0

private fun Decoder.asJson(): JsonElement {
    val jsonDecoder = this as? kotlinx.serialization.json.JsonDecoder
        ?: throw A2uiFormatException("A2UI payloads can only be read from JSON.")
    return jsonDecoder.decodeJsonElement()
}

private fun Decoder.json(): Json =
    (this as? kotlinx.serialization.json.JsonDecoder)?.json
        ?: throw A2uiFormatException("A2UI payloads can only be read from JSON.")

private fun Encoder.asJsonEncoder(): kotlinx.serialization.json.JsonEncoder =
    this as? kotlinx.serialization.json.JsonEncoder
        ?: throw A2uiFormatException("A2UI payloads can only be written as JSON.")

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private fun unionDescriptor(name: String): SerialDescriptor =
    SerialDescriptor(name, JsonElement.serializer().descriptor)

internal object DynamicValueSerializer : KSerializer<DynamicValue> {
    override val descriptor: SerialDescriptor =
        unionDescriptor("dev.ynagai.a2ui.core.protocol.DynamicValue")

    override fun deserialize(decoder: Decoder): DynamicValue {
        val element = decoder.asJson()
        decodeBoundValue(element, decoder.json(), "DynamicValue")?.let { return it }
        if (element is JsonNull) {
            throw A2uiFormatException("DynamicValue: null is not one of the permitted value types.")
        }
        return DynamicValue.Literal(element)
    }

    override fun serialize(encoder: Encoder, value: DynamicValue) {
        val json = encoder.asJsonEncoder()
        json.encodeJsonElement(
            when (value) {
                is BoundValue -> encodeBoundValue(value, json.json)
                is DynamicValue.Literal -> value.value
            },
        )
    }
}

internal object DynamicStringSerializer : KSerializer<DynamicString> {
    override val descriptor: SerialDescriptor =
        unionDescriptor("dev.ynagai.a2ui.core.protocol.DynamicString")

    override fun deserialize(decoder: Decoder): DynamicString {
        val element = decoder.asJson()
        decodeBoundValue(element, decoder.json(), "DynamicString")?.let { return it }
        val primitive = element as? JsonPrimitive
        if (primitive == null || !primitive.isString) {
            throw A2uiFormatException(
                "DynamicString: expected a string, a DataBinding, or a FunctionCall.",
            )
        }
        return DynamicString.Literal(primitive.content)
    }

    override fun serialize(encoder: Encoder, value: DynamicString) {
        val json = encoder.asJsonEncoder()
        json.encodeJsonElement(
            when (value) {
                is BoundValue -> encodeBoundValue(value, json.json)
                is DynamicString.Literal -> JsonPrimitive(value.value)
            },
        )
    }
}

internal object DynamicNumberSerializer : KSerializer<DynamicNumber> {
    override val descriptor: SerialDescriptor =
        unionDescriptor("dev.ynagai.a2ui.core.protocol.DynamicNumber")

    override fun deserialize(decoder: Decoder): DynamicNumber {
        val element = decoder.asJson()
        decodeBoundValue(element, decoder.json(), "DynamicNumber")?.let { return it }
        val primitive = element as? JsonPrimitive
        val number = primitive?.takeIf { !it.isString && it !is JsonNull }?.doubleOrNull
            ?: throw A2uiFormatException(
                "DynamicNumber: expected a number, a DataBinding, or a FunctionCall.",
            )
        if (number.isNaN() || number.isInfinite()) {
            // `"1e999"` parses to Double.POSITIVE_INFINITY rather than failing. Accepting it here
            // would defer the failure to encode time, where it surfaces while serialising the
            // renderer's own state long after the payload was taken.
            throw A2uiFormatException(
                "DynamicNumber: `${primitive.content}` is out of range for a JSON number.",
            )
        }
        return DynamicNumber.Literal(number)
    }

    override fun serialize(encoder: Encoder, value: DynamicNumber) {
        val json = encoder.asJsonEncoder()
        json.encodeJsonElement(
            when (value) {
                is BoundValue -> encodeBoundValue(value, json.json)
                is DynamicNumber.Literal -> encodeNumber(value.value)
            },
        )
    }
}

internal object DynamicBooleanSerializer : KSerializer<DynamicBoolean> {
    override val descriptor: SerialDescriptor =
        unionDescriptor("dev.ynagai.a2ui.core.protocol.DynamicBoolean")

    override fun deserialize(decoder: Decoder): DynamicBoolean {
        val element = decoder.asJson()
        decodeBoundValue(element, decoder.json(), "DynamicBoolean")?.let { return it }
        val primitive = element as? JsonPrimitive
        val flag = primitive?.takeIf { !it.isString && it !is JsonNull }?.booleanOrNull
            ?: throw A2uiFormatException(
                "DynamicBoolean: expected a boolean, a DataBinding, or a FunctionCall.",
            )
        return DynamicBoolean.Literal(flag)
    }

    override fun serialize(encoder: Encoder, value: DynamicBoolean) {
        val json = encoder.asJsonEncoder()
        json.encodeJsonElement(
            when (value) {
                is BoundValue -> encodeBoundValue(value, json.json)
                is DynamicBoolean.Literal -> JsonPrimitive(value.value)
            },
        )
    }
}

internal object DynamicStringListSerializer : KSerializer<DynamicStringList> {
    override val descriptor: SerialDescriptor =
        unionDescriptor("dev.ynagai.a2ui.core.protocol.DynamicStringList")

    override fun deserialize(decoder: Decoder): DynamicStringList {
        val element = decoder.asJson()
        decodeBoundValue(element, decoder.json(), "DynamicStringList")?.let { return it }
        val array = element as? JsonArray
            ?: throw A2uiFormatException(
                "DynamicStringList: expected an array of strings, a DataBinding, or a FunctionCall.",
            )
        val items = array.map { item ->
            (item as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw A2uiFormatException("DynamicStringList: every item must be a string.")
        }
        return DynamicStringList.Literal(items)
    }

    override fun serialize(encoder: Encoder, value: DynamicStringList) {
        val json = encoder.asJsonEncoder()
        json.encodeJsonElement(
            when (value) {
                is BoundValue -> encodeBoundValue(value, json.json)
                is DynamicStringList.Literal -> JsonArray(value.value.map(::JsonPrimitive))
            },
        )
    }
}
