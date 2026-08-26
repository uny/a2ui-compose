package dev.ynagai.a2ui.core.surface

import dev.ynagai.a2ui.core.protocol.A2uiFormatException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

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
 * model." This type follows A2UI, so `""` and `/` both parse to [ROOT] and there is no way to
 * write a pointer to a top-level `""` key. The alternative — following the RFC and reading `/` as
 * a key — would silently write agent payloads into the wrong place.
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
         * It resolves to nothing, and is only meaningful as the last token of a write, where it
         * appends. RFC 6901 gives it no meaning at all for evaluation.
         */
        public const val APPEND: String = "-"

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
         * Parses [raw], rejecting a malformed escape rather than reading it literally and a
         * pointer with more than [MAX_TOKENS] tokens rather than recursing on it.
         */
        public fun parse(raw: String): JsonPointer {
            if (raw.isEmpty() || raw == "/") return ROOT
            val absolute = raw.startsWith('/')
            val body = if (absolute) raw.substring(1) else raw
            val split = body.split('/')
            if (split.size > MAX_TOKENS) {
                // Counted before unescaping, so an oversized pointer costs one split rather
                // than a pass over every token, and truncated in the message so a megabyte of
                // agent-chosen path does not travel inside the exception.
                throw A2uiFormatException(
                    "JSON Pointer `${raw.take(64)}...`: ${split.size} tokens exceeds the " +
                        "maximum of $MAX_TOKENS.",
                )
            }
            return JsonPointer(split.map { unescape(it, raw) }, absolute)
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
 * The semantics are the specification's, not JSON Patch's:
 *
 * - **Upsert.** A pointer that already exists is replaced; one that does not is created, along
 *   with any missing containers on the way to it.
 * - **Null deletes.** An explicit `null` removes what [pointer] addresses — a member from an
 *   object, an element from an array (shifting the rest down). There is therefore no way for an
 *   `updateDataModel` to *store* a JSON null; the specification spends `null` on the delete verb.
 *   A delete of something [pointer] does not address changes nothing at all: it neither creates
 *   the containers on the way to it nor disturbs whatever stands in the path.
 * - **Root.** A [JsonPointer.ROOT] write replaces the whole data model, and a root delete empties
 *   it. The replacement must be an object, because that is what the schema types a data model as.
 *
 * Missing containers are created as objects — on a write that *stores* a value; see the delete
 * rule above — even when the next token looks like an array index.
 * `/items/0` against an absent `items` is genuinely ambiguous, and choosing an object keeps the
 * write reversible: a later write of an actual array at `/items` replaces the object outright,
 * whereas guessing an array would make `{"0": ...}` unrepresentable.
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
    if (pointer.isRoot) {
        if (value is JsonNull) return JsonObject(emptyMap())
        return value as? JsonObject
            ?: throw A2uiStateException(
                "updateDataModel: replacing the whole data model requires an object.",
            )
    }
    // A delete removes what [pointer] addresses and nothing else, so a delete of something
    // that is not there is a no-op. Without this the recursion below would materialize the
    // containers on the way to the absent member — `{}` would gain `{"user":{}}` from a
    // delete of `/user/name` — replace a scalar standing in the path with an empty object,
    // and append `{}` to an array addressed through `-`. It also makes an out-of-range array
    // delete uniformly do nothing, rather than being ignored at `size` and raising a
    // "would leave a gap" error just past it.
    if (value is JsonNull && resolve(pointer) == null) return this
    return writeIn(this, pointer.tokens, 0, value, pointer) as JsonObject
}

private fun writeIn(
    node: JsonElement?,
    tokens: List<String>,
    depth: Int,
    value: JsonElement,
    pointer: JsonPointer,
): JsonElement {
    val token = tokens[depth]
    val last = depth == tokens.lastIndex
    return when (node) {
        is JsonArray -> {
            val appending = token == JsonPointer.APPEND || arrayIndex(token) == node.size
            val index = if (appending) node.size else arrayIndex(token)
                ?: throw A2uiStateException(
                    "updateDataModel `$pointer`: `$token` is not an array index.",
                )
            if (index > node.size) {
                throw A2uiStateException(
                    "updateDataModel `$pointer`: index $index would leave a gap in an array of " +
                        "size ${node.size}.",
                )
            }
            when {
                last && value is JsonNull -> {
                    if (appending) node else JsonArray(node.filterIndexed { i, _ -> i != index })
                }
                last -> JsonArray(
                    if (appending) node + value else node.mapIndexed { i, e -> if (i == index) value else e },
                )
                else -> {
                    val child = writeIn(node.getOrNull(index), tokens, depth + 1, value, pointer)
                    JsonArray(
                        if (appending) node + child else node.mapIndexed { i, e -> if (i == index) child else e },
                    )
                }
            }
        }
        else -> {
            // Anything that is not an object — a primitive, a null, or an absent node — is
            // replaced by the object this write needs, rather than failing. `updateDataModel` is
            // specified as an upsert, so the agent overwriting a scalar with a subtree is a
            // legitimate update and not a type error.
            val obj = node as? JsonObject ?: JsonObject(emptyMap())
            when {
                last && value is JsonNull -> JsonObject(obj - token)
                last -> JsonObject(obj + (token to value))
                else -> JsonObject(
                    obj + (token to writeIn(obj[token], tokens, depth + 1, value, pointer)),
                )
            }
        }
    }
}

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
