package dev.ynagai.a2ui.core.validation

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.floor

/**
 * One reason a value did not satisfy its schema.
 *
 * **[message] never carries a value read from the instance.** A renderer turns a validation
 * failure into the `error` it sends back to the agent, so anything quoted here travels back over
 * the wire — and the values being checked are form fields. `not(value: /form/cardNumber)` putting
 * a card number in an error message is not a hypothetical; it is a defect that reached review
 * once already. What may be quoted is what the *catalog* said: an `enum`'s options, a `const`, a
 * `minimum`. Those are the renderer's own configuration, not the user's data.
 *
 * @property location a JSON Pointer to the value inside the instance that was checked.
 */
public data class SchemaViolation(
    public val location: String,
    public val message: String,
) {
    override fun toString(): String = if (location.isEmpty()) message else "$location: $message"
}

/** What [SchemaEvaluator] concluded about one value. */
public data class SchemaValidation(
    public val violations: List<SchemaViolation>,
    /**
     * Keywords the schema used that this evaluator does not apply, if any.
     *
     * Empty for every schema A2UI v1.0 publishes. A non-empty set means part of the schema was not
     * checked at all — the value may be valid, or it may violate a constraint nothing tested. A
     * renderer accepting inlined catalogs from an agent should treat this as a reason to refuse
     * the catalog rather than as a warning to log, because the alternative is enforcing a subset
     * of what the catalog asked for without saying so.
     */
    public val unsupportedKeywords: Set<String> = emptySet(),
    /** Whether the evaluator stopped before saying everything it had to say. */
    public val truncated: Boolean = false,
) {
    public val isValid: Boolean get() = violations.isEmpty()
}

/**
 * The bounds one validation runs under.
 *
 * Both the instance and — once a catalog may be inlined in a capabilities message — the schema
 * come from the agent, so neither one's size may decide how much work a renderer does.
 */
public data class ValidationLimits(
    /**
     * How deeply subschema application may nest.
     *
     * The reference graph a catalog describes is cyclic: a function's arguments are dynamic
     * values, a dynamic value may be a function call, and a function call resolves through
     * `anyFunction` back to the catalog's own functions. The cycle guard stops a traversal that
     * revisits the same subschema at the same place in the instance; this stops one that descends
     * forever through an instance that keeps nesting. It is a stack-depth bound, and Kotlin/Native
     * aborts the process on overflow rather than raising something catchable.
     */
    public val maxDepth: Int = 64,
    /**
     * How many subschema applications one validation may perform in total.
     *
     * Charged across the whole run, not per subschema. A per-node budget is not a budget here: a
     * component's schema applies `oneOf` over every component the catalog defines, each of which
     * reaches back into the catalog through its properties, so the cost is the product of those
     * fan-outs and only a total bounds it.
     */
    public val maxSteps: Int = 100_000,
    /** How many violations one validation reports before it stops describing them. */
    public val maxViolations: Int = 32,
    /**
     * The longest `pattern` this will hand to [Regex].
     *
     * Mirrors the function evaluator's bound of the same name and exists for the same reason: a
     * pattern that arrives with an inlined catalog is agent-controlled.
     */
    public val maxPatternLength: Int = 1_024,
    /**
     * The longest string a `pattern` will be matched against.
     *
     * A stack-depth bound rather than a length one. A backtracking engine recurses roughly once
     * per character of a quantified group, and Kotlin/Native aborts the process on overflow
     * instead of raising.
     */
    public val maxSubjectLength: Int = 2_048,
) {
    public companion object {
        /** The bounds used when a caller does not choose. */
        public val DEFAULT: ValidationLimits = ValidationLimits()
    }
}

/**
 * Where a value sits inside the instance, kept as a linked path rather than a built string.
 *
 * The evaluator visits every value under every alternative of every `oneOf`, and the overwhelming
 * majority of those visits never produce a message. Building the pointer eagerly would allocate a
 * string per visit, and building it by appending to a parent's string would copy the prefix at
 * every level — the quadratic shape that a previous parser here had to be rewritten out of.
 */
internal class InstancePath private constructor(
    private val parent: InstancePath?,
    private val token: String,
) {
    fun child(name: String): InstancePath = InstancePath(this, name.escaped())

    fun index(at: Int): InstancePath = InstancePath(this, at.toString())

    /** This path as a JSON Pointer. Called only when a message is actually being written. */
    fun render(): String {
        if (parent == null) return ""
        val steps = ArrayDeque<String>()
        var node: InstancePath? = this
        while (node?.parent != null) {
            steps.addFirst(node.token)
            node = node.parent
        }
        return steps.joinToString(separator = "/", prefix = "/")
    }

    private fun String.escaped(): String = replace("~", "~0").replace("/", "~1")

    companion object {
        val ROOT: InstancePath = InstancePath(null, "")
    }
}

/** Whether a `format` was checked, and what it said. */
internal enum class FormatVerdict { VALID, INVALID, UNKNOWN }

/**
 * Checks the `format` values A2UI v1.0 asserts with.
 *
 * `format` is an annotation by default in JSON Schema 2020-12, but v1.0 relies on it as an
 * assertion: `function_catalog_validation` requires `openUrl` with a non-URI `url` to be rejected,
 * and the alternative that would otherwise catch it — a plain string — is exactly what it is.
 * Treating it as an annotation there would accept the payload the specification's own test says to
 * refuse. Anything not listed returns [FormatVerdict.UNKNOWN] so the caller can say the schema was
 * only partly applied, rather than passing an unchecked value off as valid.
 */
internal fun checkFormat(name: String, value: String): FormatVerdict = when (name) {
    "uri" -> if (isUri(value)) FormatVerdict.VALID else FormatVerdict.INVALID
    "date" -> if (DATE.matches(value)) FormatVerdict.VALID else FormatVerdict.INVALID
    "time" -> if (TIME.matches(value)) FormatVerdict.VALID else FormatVerdict.INVALID
    "date-time" -> if (DATE_TIME.matches(value)) FormatVerdict.VALID else FormatVerdict.INVALID
    else -> FormatVerdict.UNKNOWN
}

/**
 * Whether [value] is an absolute URI: a scheme, then anything.
 *
 * This is the RFC 3986 rule for the scheme and nothing more. Validating the rest would reject
 * URIs that every platform's own opener accepts, and the constraint that actually matters — which
 * schemes a renderer will open — belongs to `openUrl` rather than to a string format.
 */
private fun isUri(value: String): Boolean {
    val colon = value.indexOf(':')
    if (colon <= 0) return false
    if (!value[0].isAsciiLetter()) return false
    // Written out rather than using a character class, because `\w` and friends cover different
    // characters on JS than they do on the JVM and Native, and the same payload must not be a URI
    // on Android and not one on the web.
    return (1 until colon).all { index ->
        val character = value[index]
        character.isAsciiLetter() || character in '0'..'9' || character == '+' || character == '-' ||
            character == '.'
    }
}

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

private val DATE = Regex("""^\d{4}-\d{2}-\d{2}$""")
private val TIME = Regex("""^\d{2}:\d{2}:\d{2}(\.\d+)?(?:[Zz]|[+-]\d{2}:\d{2})?$""")
private val DATE_TIME = Regex(
    """^\d{4}-\d{2}-\d{2}[Tt]\d{2}:\d{2}:\d{2}(\.\d+)?(?:[Zz]|[+-]\d{2}:\d{2})$""",
)

// --- describing a value without quoting it ---------------------------------------------------

/** The JSON type name for [this], for a message that must not carry the value itself. */
internal fun JsonElement.typeName(): String = when (this) {
    is JsonArray -> "an array"
    is JsonObject -> "an object"
    JsonNull -> "null"
    is JsonPrimitive -> when {
        isString -> "a string"
        content == "true" || content == "false" -> "a boolean"
        else -> "a number"
    }
}

/** Whether [this] is one of the types [expected] names, which may be a string or an array of them. */
internal fun JsonElement.matchesType(expected: JsonElement): Boolean = when (expected) {
    is JsonArray -> expected.any { matchesType(it) }
    is JsonPrimitive -> matchesTypeName(expected.content)
    else -> true
}

private fun JsonElement.matchesTypeName(name: String): Boolean = when (name) {
    "object" -> this is JsonObject
    "array" -> this is JsonArray
    "null" -> this is JsonPrimitive && this == JsonNull
    "string" -> this is JsonPrimitive && isString
    "boolean" -> this is JsonPrimitive && !isString && booleanOrNull != null
    "number" -> this is JsonPrimitive && !isString && this != JsonNull && doubleOrNull != null
    // A JSON number with a zero fractional part is an integer, per JSON Schema, so `1.0` counts.
    // `longOrNull` is asked first because a value past 2^53 is still an integer, and routing it
    // through a `Double` would round it to one that is merely nearby.
    "integer" -> this is JsonPrimitive && !isString && this != JsonNull &&
        (longOrNull != null || doubleOrNull?.let { it == floor(it) && !it.isInfinite() } == true)
    else -> true
}

/**
 * The names in a `type` keyword that are not one of the seven JSON types.
 *
 * Every one of the seven is implemented, so anything else is a constraint written wrongly rather
 * than one this evaluator has not got to -- and a `type` nobody can satisfy must not read as a
 * `type` everybody satisfies.
 */
internal fun JsonElement.unknownTypeNames(): List<String> = when (this) {
    is JsonArray -> flatMap { it.unknownTypeNames() }
    is JsonPrimitive -> if (isString && content in JSON_TYPE_NAMES) emptyList() else listOf(content)
    else -> listOf("a value that is not a type name")
}

private val JSON_TYPE_NAMES: Set<String> =
    setOf("object", "array", "string", "number", "integer", "boolean", "null")

/**
 * [a] against [b] as JSON Schema compares numbers, which is by mathematical value.
 *
 * Integers are compared as integers. Routing them through `Double` makes `9007199254740992` and
 * `9007199254740993` the same number, so a `minimum` just above the range a `Double` can name
 * would accept a value below it.
 */
internal fun compareNumbers(a: JsonPrimitive, b: JsonPrimitive): Int {
    val left = a.longOrNull
    val right = b.longOrNull
    if (left != null && right != null) return left.compareTo(right)
    val leftDouble = a.doubleOrNull ?: return 0
    val rightDouble = b.doubleOrNull ?: return 0
    return leftDouble.compareTo(rightDouble)
}

/** How [this] `type` keyword reads in a message. */
internal fun JsonElement.describeType(): String = when (this) {
    is JsonArray -> joinToString(" or ") { it.describeType() }
    is JsonPrimitive -> when (content) {
        "object" -> "an object"
        "array" -> "an array"
        "string" -> "a string"
        "number" -> "a number"
        "integer" -> "an integer"
        "boolean" -> "a boolean"
        "null" -> "null"
        else -> "a $content"
    }
    else -> "a value of the declared type"
}

/**
 * A catalog-authored literal, quoted.
 *
 * Only ever called on a value taken from the schema — an `enum` option, a `const`, a `minimum`.
 * Calling it on anything read from the instance would put user data in a message that goes back
 * to the agent; see [SchemaViolation].
 */
internal fun JsonElement.describeLiteral(): String = when (this) {
    is JsonPrimitive -> if (isString) "`$content`" else content
    is JsonArray -> "an array of ${size} entries"
    is JsonObject -> "an object"
}

/**
 * Whether [text] matches [source], under the same bounds the function evaluator's `regex` runs
 * with, or [FormatVerdict.UNKNOWN] when it cannot be judged.
 *
 * A pattern reaches this from a catalog, and once a catalog may be inlined in a capabilities
 * message that means it reaches this from an agent. Backtracking blow-up is not something a bound
 * prevents — `(a+)+$` is quadratic or worse on every platform's engine — so what these do is cap
 * how bad it gets. The subject bound is a stack-depth bound wearing a length's clothing: an
 * ordinary quantified group recurses once per character, and Kotlin/Native answers an overflow by
 * aborting the process rather than by raising something a `catch` can see.
 *
 * A pattern that will not compile is [FormatVerdict.UNKNOWN] rather than a failure, because a
 * catalog this evaluator cannot read is not evidence about the payload. The failure is caught as
 * [Throwable]: Kotlin/JS lets the engine's own `SyntaxError` through where every other target
 * raises [IllegalArgumentException].
 *
 * **The verdict is not identical across targets.** `\s` covers six ASCII characters on the JVM and
 * on Native and the whole Unicode space separator category on JS, and a handful of other escapes
 * differ the same way. A catalog whose pattern uses one will accept a payload on Android and
 * refuse it on the web. Nothing here can fix that — it is a property of the engines — so it is
 * written down instead.
 */
internal fun matchesPattern(source: String, text: String, limits: ValidationLimits): FormatVerdict {
    if (source.length > limits.maxPatternLength) return FormatVerdict.UNKNOWN
    if (text.length > limits.maxSubjectLength) return FormatVerdict.UNKNOWN
    val regex = try {
        Regex(source)
    } catch (failure: Throwable) {
        return FormatVerdict.UNKNOWN
    }
    // `containsMatchIn`, not `matches`: JSON Schema's `pattern` is a search, and every pattern in
    // v1.0 anchors itself.
    return if (regex.containsMatchIn(text)) FormatVerdict.VALID else FormatVerdict.INVALID
}
