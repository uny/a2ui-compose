package dev.ynagai.a2ui.core.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Thrown when a payload is well-formed JSON but does not describe a valid A2UI structure.
 *
 * This is a [SerializationException], so a caller that already catches serialization failures
 * catches these too.
 *
 * Note that catching [SerializationException] is not sufficient on its own for untrusted input:
 * neither this library nor `kotlinx.serialization` bounds nesting depth, so a deeply nested
 * payload fails with a [StackOverflowError] — an `Error`, not an exception. Bound the size and
 * nesting of a payload at the transport before handing it to [A2uiJson].
 */
public class A2uiFormatException(
    message: String,
    cause: Throwable? = null,
) : SerializationException(message, cause)

/** The [Json] configurations this library parses and emits A2UI payloads with. */
public object A2uiJson {
    /**
     * The default, and the only configuration the conformance suite runs against.
     *
     * Unknown keys are rejected rather than dropped, which is what makes a typed model able to
     * refuse a malformed payload at all: the v1.0 schemas close almost every object with
     * `additionalProperties: false` or `unevaluatedProperties: false`, and silently ignoring the
     * extra key would turn a payload the specification rejects into one this library accepts.
     */
    public val strict: Json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = false
        encodeDefaults = true
        coerceInputValues = false
        allowStructuredMapKeys = false
        allowSpecialFloatingPointValues = false
    }

    /**
     * An opt-in configuration that tolerates unknown keys.
     *
     * Useful against an agent that emits properties this library does not model yet — but note
     * what it costs. Most of what [strict] refuses, it refuses for carrying a key the schema does
     * not define; under [lenient] those payloads are accepted instead. The conformance suite is
     * only ever run against [strict], so nothing exercises that path. Treat a surface parsed
     * leniently as unvalidated input.
     */
    public val lenient: Json = Json(strict) { ignoreUnknownKeys = true }
}
