package dev.ynagai.a2ui.core.surface

import dev.ynagai.a2ui.core.protocol.A2uiFormatException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A JSON Pointer ([RFC 6901](https://www.rfc-editor.org/rfc/rfc6901)) into a surface's data model.
 *
 * A2UI uses pointers for two things that RFC 6901 does not distinguish, so this type carries the
 * distinction as [isAbsolute]:
 *
 * - An **absolute** pointer (`/user/name`, or the empty string) resolves from the root of the data
 *   model no matter where the component using it sits in the UI tree.
 * - A **relative** pointer (`name`) resolves from the item currently being iterated by the
 *   enclosing list template — see [EvaluationScope]. RFC 6901 has no such form: every pointer it
 *   defines either is empty or begins with `/`.
 *
 * **Deviation from RFC 6901, required by the specification.** Under the RFC, `/` is a pointer to
 * the member whose name is the empty string, and only `""` addresses the whole document. A2UI
 * instead says of `updateDataModel.path`: "If omitted, or set to `/`, refers to the entire data
 * model." This type follows A2UI, so `""` and `/` both parse to [ROOT]. The alternative —
 * following the RFC and reading `/` as a key — would silently write agent payloads into the wrong
 * place.
 *
 * **Empty tokens are dropped.** `//a///b//` is `/a/b`, and a trailing slash is not a distinct
 * path. That is what every upstream data model does (`web_core`, Python `a2ui_core`, Swift
 * `A2UICore`) and what `data_model.yaml`'s `test_data_model_normalizes_empty_path_segments` and
 * `..._trailing_slash` pin, so an agent writing `/user/` reaches the same member on every
 * renderer. The cost is the RFC's empty-string key, which no pointer can address; the root rule
 * above already gave up the top-level one.
 */
public class JsonPointer private constructor(
    public val tokens: List<String>,
    public val isAbsolute: Boolean,
) {
    /** True when this addresses the whole data model rather than a member of it. */
    public val isRoot: Boolean get() = isAbsolute && tokens.isEmpty()

    /** This pointer extended by one more unescaped [token]. */
    public fun child(token: String): JsonPointer = JsonPointer(tokens + token, isAbsolute)

    /**
     * [other] resolved against this pointer as its base.
     *
     * An absolute [other] ignores the base entirely, which is what lets a component inside a list
     * template reach back out to the root scope by writing a leading `/`.
     */
    public fun resolve(other: JsonPointer): JsonPointer =
        if (other.isAbsolute) other else JsonPointer(tokens + other.tokens, isAbsolute)

    override fun toString(): String = buildString {
        if (!isAbsolute && tokens.isEmpty()) return ""
        if (!isAbsolute) {
            append(escape(tokens.first()))
            tokens.drop(1).forEach { append('/').append(escape(it)) }
            return@buildString
        }
        tokens.forEach { append('/').append(escape(it)) }
    }

    override fun equals(other: Any?): Boolean =
        other is JsonPointer && other.tokens == tokens && other.isAbsolute == isAbsolute

    override fun hashCode(): Int = tokens.hashCode() * 31 + isAbsolute.hashCode()

    public companion object {
        /** The pointer to the whole data model. */
        public val ROOT: JsonPointer = JsonPointer(emptyList(), isAbsolute = true)

        /**
         * The token JSON Patch uses for "one past the last element" of an array.
         *
         * It has no meaning in A2UI. Every upstream data model (`web_core`, `a2ui_core` in
         * Python, `A2UICore` in Swift) treats it as what it is to them -- a non-numeric segment
         * on an array -- and refuses the write, so [write] does too, and on a read it resolves to
         * nothing. Kept so that code naming it still compiles; append by writing the index equal
         * to the array's size.
         */
        @Deprecated(
            "A2UI v1.0 data models refuse `-` on an array. Write the index equal to the array's " +
                "size to append.",
        )
        public const val APPEND: String = "-"

        /**
         * The largest array index a write may name, the bound every upstream data model applies.
         *
         * A write past an array's end pads the gap with nulls, so without a bound one
         * `updateDataModel` of `/items/2000000000` would ask the renderer for two billion of them.
         */
        public const val MAX_ARRAY_INDEX: Int = 10_000

        /**
         * Segments refused anywhere in a pointer, on reads and writes alike.
         *
         * They only mean something to a runtime with prototype chains, but the blueprint makes the
         * rule normative in every language "so that a payload behaves the same everywhere": an
         * agent whose writes to `/constructor` land on Kotlin renderers and fail on JavaScript
         * ones would be relying on which renderer it happened to reach.
         */
        internal val FORBIDDEN_TOKENS: Set<String> = setOf("__proto__", "constructor", "prototype")

        /**
         * The most tokens a pointer may carry.
         *
         * [write] recurses once per token and the path arrives from the agent, so without a
         * bound the agent picks the renderer's recursion depth: a `path` of `/a` repeated ten
         * thousand times overflows the stack, and a `StackOverflowError` is an [Error] that
         * nothing in the message loop is written to catch — on Kotlin/Native it aborts the
         * process outright rather than raising at all. The bound is far past any data model a
         * UI binds against, and matches [DEFAULT_MAX_DEPTH] so the two nesting limits agree.
         */
        public const val MAX_TOKENS: Int = 256

        /**
         * Parses [raw], rejecting a malformed escape rather than reading it literally, a pointer
         * with more than [MAX_TOKENS] tokens rather than recursing on it, and one that names any
         * of [FORBIDDEN_TOKENS].
         */
        public fun parse(raw: String): JsonPointer {
            if (raw.isEmpty() || raw == "/") return ROOT
            val absolute = raw.startsWith('/')
            val body = if (absolute) raw.substring(1) else raw
            val split = body.split('/').filter { it.isNotEmpty() }
            if (split.isEmpty()) return if (absolute) ROOT else JsonPointer(emptyList(), false)
            if (split.size > MAX_TOKENS) {
                // Counted before unescaping, so an oversized pointer costs one split rather
                // than a pass over every token, and truncated in the message so a megabyte of
                // agent-chosen path does not travel inside the exception.
                throw A2uiFormatException(
                    "JSON Pointer `${raw.take(64)}...`: ${split.size} tokens exceeds the " +
                        "maximum of $MAX_TOKENS.",
                )
            }
            val tokens = split.map { unescape(it, raw) }
            tokens.firstOrNull { it in FORBIDDEN_TOKENS }?.let { token ->
                throw A2uiFormatException(
                    "JSON Pointer `${raw.take(64)}`: `$token` is a forbidden path segment.",
                )
            }
            return JsonPointer(tokens, absolute)
        }

        /** Builds an absolute pointer from already-unescaped [tokens]. */
        public fun of(vararg tokens: String): JsonPointer =
            JsonPointer(tokens.toList(), isAbsolute = true)

        private fun escape(token: String): String =
            token.replace("~", "~0").replace("/", "~1")

        /**
         * Decodes `~1` to `/` and `~0` to `~`, in that order.
         *
         * The order matters and is fixed by RFC 6901 §4: decoding `~0` first would turn the
         * encoded form of `~1` into an unintended `/`.
         */
        private fun unescape(token: String, raw: String): String {
            if ('~' !in token) return token
            val out = StringBuilder(token.length)
            var i = 0
            while (i < token.length) {
                val c = token[i]
                if (c != '~') {
                    out.append(c)
                    i++
                    continue
                }
                when (token.getOrNull(i + 1)) {
                    '0' -> out.append('~')
                    '1' -> out.append('/')
                    else -> throw A2uiFormatException(
                        "JSON Pointer `$raw`: `~` must be followed by `0` or `1`.",
                    )
                }
                i += 2
            }
            return out.toString()
        }
    }
}

/**
 * The value [pointer] addresses, or null when nothing is there.
 *
 * Missing is not an error: the specification requires renderers to tolerate a binding whose
 * `updateDataModel` has not arrived yet ("data paths may resolve to `undefined` during the
 * initial streaming phase") and to render a placeholder instead. A caller that needs to tell
 * "absent" from "present and JSON null" can compare the result against [JsonNull].
 *
 * A relative [pointer] is resolved as if from this element, so callers holding an
 * [EvaluationScope] should rebase it first — see [EvaluationScope.resolve].
 */
public fun JsonElement.resolve(pointer: JsonPointer): JsonElement? {
    // `parse` refuses these, but a pointer assembled with `of` or `child` has not been through it.
    if (pointer.tokens.any { it in JsonPointer.FORBIDDEN_TOKENS }) return null
    var current: JsonElement = this
    for (token in pointer.tokens) {
        current = when (current) {
            is JsonObject -> current[token] ?: return null
            is JsonArray -> current.getOrNull(arrayIndex(token) ?: return null) ?: return null
            else -> return null
        }
    }
    return current
}

/**
 * [value] written at [pointer], returning a new data model.
 *
 * The semantics are the A2UI data model's -- the `a2ui_core` blueprint's JSON Pointer rules and
 * the conformance suite's `data_model.yaml` and `data_deletion.yaml` -- not JSON Patch's:
 *
 * - **Upsert.** A pointer that already exists is replaced; one that does not is created, along
 *   with any missing containers on the way to it. A missing container is an array when the token
 *   that indexes it is numeric and an object otherwise, so `/items/0` against an empty model
 *   writes `{"items": [...]}` (rule 2, "Auto-typing").
 * - **Arrays take indices only.** A non-numeric token on an array is an error, and that includes
 *   JSON Patch's `-`. An index past the end pads the gap with nulls; one past
 *   [JsonPointer.MAX_ARRAY_INDEX] is an error.
 * - **A primitive in the way is an error** (rule 3). Writing `/a/b` where `/a` holds a string
 *   would otherwise replace committed data with a fresh container, silently. A null in the way
 *   counts as absent and is replaced.
 * - **Null deletes.** An explicit `null` removes a member from an object. On an array it empties
 *   the element and keeps the array's length (rule 6), so `[1,2,3]` with `/1` deleted is
 *   `[1,null,3]` and the indices after it do not move. A delete of something [pointer] does not
 *   address changes nothing at all (rule 7).
 * - **Root.** A [JsonPointer.ROOT] write replaces the whole data model, and a root delete empties
 *   it (rule 8). The replacement must be an object, because that is what the schema types a data
 *   model as.
 *
 * Every refusal raises [A2uiStateException] naming the pointer and leaves the model unchanged.
 */
public fun JsonObject.write(pointer: JsonPointer, value: JsonElement): JsonObject {
    require(pointer.isAbsolute) {
        "A relative pointer has no meaning as a write address; rebase it on its scope first."
    }
    if (pointer.tokens.size > JsonPointer.MAX_TOKENS) {
        // `parse` already refuses these, so this only catches a pointer assembled through
        // `of`/`child`/`resolve`. The recursion below is what makes it worth refusing twice.
        throw A2uiStateException(
            "updateDataModel: a write address of ${pointer.tokens.size} tokens exceeds the " +
                "maximum of ${JsonPointer.MAX_TOKENS}.",
        )
    }
    pointer.tokens.firstOrNull { it in JsonPointer.FORBIDDEN_TOKENS }?.let { token ->
        throw A2uiStateException("updateDataModel `$pointer`: `$token` is a forbidden path segment.")
    }
    if (pointer.isRoot) {
        if (value is JsonNull) return JsonObject(emptyMap())
        return value as? JsonObject
            ?: throw A2uiStateException(
                "updateDataModel: replacing the whole data model requires an object.",
            )
    }
    // Rule 7: a delete of something that is not there is a no-op. Without this the recursion
    // below would materialize the containers on the way to the absent member -- `{}` would gain
    // `{"user":{}}` from a delete of `/user/name` -- and could raise over a primitive standing in
    // a path the delete was never going to reach.
    if (value is JsonNull && resolve(pointer) == null) return this
    return writeIn(this, pointer.tokens, 0, value, pointer) as JsonObject
}

/**
 * [node] with [value] written at `tokens[depth..]`.
 *
 * [node] is null when nothing stands at this depth yet (or a JSON null does), and the container
 * created for it then follows rule 2: an array when this depth's token is numeric.
 */
private fun writeIn(
    node: JsonElement?,
    tokens: List<String>,
    depth: Int,
    value: JsonElement,
    pointer: JsonPointer,
): JsonElement {
    val token = tokens[depth]
    val last = depth == tokens.lastIndex
    val container: JsonElement = when (node) {
        null, JsonNull -> if (isNumericToken(token)) JsonArray(emptyList()) else JsonObject(emptyMap())
        is JsonArray, is JsonObject -> node
        is JsonPrimitive -> throw A2uiStateException(
            "updateDataModel `$pointer`: `${pointer.prefix(depth)}` holds a primitive, so the " +
                "write cannot pass through it.",
        )
    }
    return when (container) {
        is JsonArray -> {
            // A digit string too long for an Int is still an index, just one past the bound;
            // reading it as a non-index would vivify `{"99999999999": …}` where every upstream
            // data model raises.
            val index = arrayIndex(token) ?: throw A2uiStateException(
                if (isNumericToken(token)) {
                    "updateDataModel `$pointer`: index $token exceeds the maximum of " +
                        "${JsonPointer.MAX_ARRAY_INDEX}."
                } else {
                    "updateDataModel `$pointer`: `$token` is not an array index."
                },
            )
            if (index > JsonPointer.MAX_ARRAY_INDEX) {
                throw A2uiStateException(
                    "updateDataModel `$pointer`: index $index exceeds the maximum of " +
                        "${JsonPointer.MAX_ARRAY_INDEX}.",
                )
            }
            val written = when {
                // Only reached for an index that exists: the no-op check in `write` already
                // returned for one past the end.
                last && value is JsonNull -> JsonNull
                last -> value
                else -> writeIn(container.getOrNull(index), tokens, depth + 1, value, pointer)
            }
            val padded = if (index < container.size) {
                container.toMutableList()
            } else {
                (container + List(index - container.size + 1) { JsonNull }).toMutableList()
            }
            padded[index] = written
            JsonArray(padded)
        }
        is JsonObject -> when {
            last && value is JsonNull -> JsonObject(container - token)
            last -> JsonObject(container + (token to value))
            else -> JsonObject(
                container + (token to writeIn(container[token], tokens, depth + 1, value, pointer)),
            )
        }
        else -> error("unreachable: the container is always an array or an object")
    }
}

/** The first [count] tokens of this pointer, as an absolute pointer string. */
private fun JsonPointer.prefix(count: Int): String =
    JsonPointer.of(*tokens.subList(0, count).toTypedArray()).toString()

/**
 * Whether [token] is spelled as an array index (`0`, or digits with no leading zero), whatever
 * its magnitude. Rule 2's "the next segment is numeric" is this test, not [arrayIndex], which
 * also refuses a value too large for an [Int].
 */
private fun isNumericToken(token: String): Boolean =
    token == "0" || (token.isNotEmpty() && token[0] in '1'..'9' && token.all { it in '0'..'9' })

/**
 * [token] as an array index, or null when it is not one.
 *
 * RFC 6901 §4 admits only `0` and digit strings with no leading zero, so `01` and `+1` are not
 * indices — reading them as one would let two different pointers address the same element.
 */
private fun arrayIndex(token: String): Int? {
    if (token.isEmpty()) return null
    if (token == "0") return 0
    if (token[0] !in '1'..'9') return null
    if (!token.all { it in '0'..'9' }) return null
    return token.toIntOrNull()
}
