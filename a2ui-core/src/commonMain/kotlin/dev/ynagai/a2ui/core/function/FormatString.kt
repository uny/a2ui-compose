package dev.ynagai.a2ui.core.function

import dev.ynagai.a2ui.core.protocol.FunctionCall
import dev.ynagai.a2ui.core.protocol.encodeNumber
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * `formatString`: [template] with every `${…}` expression replaced by its value.
 *
 * This is the only function in the catalog whose arguments cannot be resolved before it runs. Every
 * other call carries its dynamic parts as `Dynamic*` unions that the evaluator resolves on the way
 * in; here they are written inside a string literal, so the function has to parse them itself.
 *
 * The grammar implemented is the one the protocol document specifies:
 *
 * ```text
 * template   := ( text | "\${" | "${" expression "}" )*
 * expression := literal | path | call | "${" expression "}"
 * literal    := "'" … "'" | '"' … '"' | number | "true" | "false" | "null"
 * path       := "/" absolute | relative
 * call       := name "(" [ argument ( "," argument )* ] ")"
 * argument   := name ":" expression | expression
 * ```
 *
 * Parsing and evaluation are one pass rather than two. A parse tree would have to be re-walked to
 * evaluate, and the tree is never reused: `formatString` is re-run from the top whenever the data
 * it reads changes, so caching the parse would mean caching it against the template string.
 */
internal fun interpolateTemplate(evaluator: Evaluator, template: String, depth: Int): String {
    val limits = evaluator.context.limits
    val out = StringBuilder()
    var i = 0
    while (i < template.length) {
        val marker = template.indexOf(OPEN, i)
        if (marker < 0) {
            out.appendBounded(template, i, template.length, limits)
            break
        }
        // `\${` is the escape the specification defines, and it is the only one: a backslash
        // anywhere else is an ordinary character, so `C:\${x}` interpolates and `C:\\${x}` does not
        // become a literal backslash followed by an interpolation.
        val escaped = marker > i && template[marker - 1] == '\\'
        out.appendBounded(template, i, if (escaped) marker - 1 else marker, limits)
        if (escaped) {
            out.appendBounded(OPEN, 0, OPEN.length, limits)
            i = marker + OPEN.length
            continue
        }
        val close = matchingClose(template, marker)
        if (close < 0) {
            throw A2uiFunctionException(
                "formatString: `${template.take(ERROR_EXCERPT)}` has an unterminated `\${`.",
                FunctionNames.FORMAT_STRING,
            )
        }
        val value = evaluateExpression(
            evaluator,
            template.substring(marker + OPEN.length, close),
            depth + 1,
        )
        val text = stringify(value)
        out.appendBounded(text, 0, text.length, limits)
        i = close + 1
    }
    return out.toString()
}

private const val OPEN: String = "\${"
private const val ERROR_EXCERPT: Int = 64

/**
 * Appends `[from, to)` of [text], having first checked that it fits.
 *
 * Before, not after. A template that interpolates a megabyte-long binding a thousand times would
 * otherwise allocate the whole result and then report that it was too long, which is the cheapest
 * way for an agent to exhaust a renderer and the case a bound checked afterwards does not close.
 */
private fun StringBuilder.appendBounded(
    text: CharSequence,
    from: Int,
    to: Int,
    limits: EvaluationLimits,
) {
    if (to <= from) return
    if (length + (to - from) > limits.maxResultLength) {
        throw A2uiFunctionException(
            "formatString: result exceeds ${limits.maxResultLength} characters.",
            FunctionNames.FORMAT_STRING,
        )
    }
    append(text, from, to)
}

/**
 * [value] as it appears inside an interpolated string.
 *
 * The rules are the protocol's: numbers and booleans in their standard form, null and absent as the
 * empty string, and objects and arrays as JSON "to ensure consistency across different renderer
 * implementations". [JsonPrimitive.content] carries a number's text exactly as the data model holds
 * it, so `1.50` does not come back as `1.5`.
 */
internal fun stringify(value: JsonElement): String = when (value) {
    is JsonNull -> ""
    is JsonPrimitive -> value.content
    else -> value.toString()
}

/**
 * One `${…}` expression.
 *
 * The forms are told apart in a fixed order, so that the classification of a given expression never
 * depends on what the data model happens to contain. `true` is the boolean, not a relative path to
 * a property named `true`; an agent that means the property writes `/true`.
 */
private fun evaluateExpression(evaluator: Evaluator, source: String, depth: Int): JsonElement {
    if (depth > evaluator.context.limits.maxDepth) {
        throw A2uiFunctionException(
            "formatString: expression nests more than ${evaluator.context.limits.maxDepth} deep.",
            FunctionNames.FORMAT_STRING,
        )
    }
    val text = source.trim()
    if (text.isEmpty()) {
        throw A2uiFunctionException("formatString: `\${}` has no expression.", FunctionNames.FORMAT_STRING)
    }

    quotedLiteral(text)?.let { return JsonPrimitive(it) }
    when (text) {
        "true" -> return JsonPrimitive(true)
        "false" -> return JsonPrimitive(false)
        "null" -> return JsonNull
    }
    numberLiteral(text)?.let { return it }

    if (text.startsWith(OPEN) && matchingClose(text, 0) == text.lastIndex) {
        return evaluateExpression(evaluator, text.substring(OPEN.length, text.lastIndex), depth + 1)
    }

    val open = indexOfTop(text, '(')
    if (open >= 0) {
        if (!text.endsWith(')')) {
            throw A2uiFunctionException(
                "formatString: `${text.take(ERROR_EXCERPT)}` opens a call it does not close.",
                FunctionNames.FORMAT_STRING,
            )
        }
        val name = text.substring(0, open).trim()
        if (!isFunctionName(name)) {
            throw A2uiFunctionException(
                "formatString: `$name` is not a function name.",
                FunctionNames.FORMAT_STRING,
            )
        }
        val arguments = parseArguments(evaluator, text.substring(open + 1, text.lastIndex), name, depth)
        return callFunction(evaluator, name, arguments, depth)
    }

    if (!isDataPath(text)) {
        throw A2uiFunctionException(
            "formatString: `${text.take(ERROR_EXCERPT)}` is not a literal, a path, or a call.",
            FunctionNames.FORMAT_STRING,
        )
    }
    return evaluator.resolve(text)
}

/**
 * Invokes [name] with arguments the parser has already evaluated.
 *
 * `@index` goes back through the ordinary path rather than being special-cased here, because the
 * scope check and the `catalogId` check belong to it wherever it is called from; its only argument
 * is a number, which survives the round trip through the wire-shaped form unchanged.
 */
private fun callFunction(
    evaluator: Evaluator,
    name: String,
    arguments: Map<String, JsonElement>,
    depth: Int,
): JsonElement {
    if (name == FunctionCall.INDEX) {
        return evaluator.evaluate(FunctionCall(call = name, args = arguments), depth)
    }
    evaluator.step(name)
    return evaluator.invoke(
        name,
        CallArguments(evaluator, name, arguments, preEvaluated = true, depth = depth),
    )
}

/**
 * The named arguments of a call, each evaluated.
 *
 * A single unnamed argument is accepted and bound to `value`. The protocol says arguments "must be
 * named", but its own example of a nested call — `${upper(${now()})}` — does not name one, and every
 * one-argument function in the basic catalog calls that argument `value`. Accepting the form the
 * specification demonstrates is worth more than holding it to the sentence next to it; more than one
 * unnamed argument has no such reading and is refused.
 */
private fun parseArguments(
    evaluator: Evaluator,
    body: String,
    call: String,
    depth: Int,
): Map<String, JsonElement> {
    if (body.isBlank()) return emptyMap()
    val parts = splitTop(body, ',')
    val out = LinkedHashMap<String, JsonElement>(parts.size)
    for (part in parts) {
        val text = part.trim()
        if (text.isEmpty()) {
            throw A2uiFunctionException(
                "formatString: `$call` has an empty argument.",
                FunctionNames.FORMAT_STRING,
            )
        }
        val colon = indexOfTop(text, ':')
        val name = if (colon < 0) null else text.substring(0, colon).trim()
        if (colon >= 0 && !isArgumentName(name!!)) {
            throw A2uiFunctionException(
                "formatString: `$name` is not an argument name in `$call`.",
                FunctionNames.FORMAT_STRING,
            )
        }
        if (name == null && parts.size > 1) {
            throw A2uiFunctionException(
                "formatString: arguments of `$call` must be named.",
                FunctionNames.FORMAT_STRING,
            )
        }
        val key = name ?: "value"
        if (out.containsKey(key)) {
            throw A2uiFunctionException(
                "formatString: `$call` names the argument `$key` twice.",
                FunctionNames.FORMAT_STRING,
            )
        }
        val valueText = if (colon < 0) text else text.substring(colon + 1)
        out[key] = evaluateExpression(evaluator, valueText, depth + 1)
    }
    return out
}

// ---- lexical helpers ----------------------------------------------------------------------

/**
 * The index of the `}` closing the `${` whose `$` is at [start], or -1 when there is none.
 *
 * Quoted spans are skipped so that a `}` inside a string argument — `${formatDate(format:'}')}` —
 * does not close the expression, and nested `${` raise the depth so that an expression wrapping
 * another one ends at its own brace.
 */
private fun matchingClose(text: String, start: Int): Int {
    var depth = 1
    var i = start + OPEN.length
    while (i < text.length) {
        val c = text[i]
        when {
            c == '\'' || c == '"' -> i = skipQuoted(text, i)
            c == '$' && i + 1 < text.length && text[i + 1] == '{' -> {
                depth++
                i += OPEN.length
            }
            c == '}' -> {
                if (--depth == 0) return i
                i++
            }
            else -> i++
        }
    }
    return -1
}

/** The index just past the quoted span opening at [start], or the end of [text] if unterminated. */
private fun skipQuoted(text: String, start: Int): Int {
    val quote = text[start]
    var i = start + 1
    while (i < text.length) {
        when (text[i]) {
            '\\' -> i += 2
            quote -> return i + 1
            else -> i++
        }
    }
    return text.length
}

/** The index of the first [char] outside quotes, parentheses and nested `${…}`, or -1. */
private fun indexOfTop(text: String, char: Char): Int {
    var parens = 0
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '\'' || c == '"' -> {
                i = skipQuoted(text, i)
                continue
            }
            c == '$' && i + 1 < text.length && text[i + 1] == '{' -> {
                val close = matchingClose(text, i)
                i = if (close < 0) text.length else close + 1
                continue
            }
            // Tested before the parenthesis counter so that `indexOfTop(text, '(')` — which is
            // how a call is recognised at all — returns the opening parenthesis rather than
            // counting it and walking past.
            c == char && parens == 0 -> return i
            c == '(' -> parens++
            c == ')' -> parens--
        }
        i++
    }
    return -1
}

/** [text] split on every [separator] that [indexOfTop] would find. */
private fun splitTop(text: String, separator: Char): List<String> {
    val out = mutableListOf<String>()
    var rest = text
    while (true) {
        val at = indexOfTop(rest, separator)
        if (at < 0) {
            out += rest
            return out
        }
        out += rest.substring(0, at)
        rest = rest.substring(at + 1)
    }
}

/** The contents of [text] when it is one whole quoted literal, or null when it is not. */
private fun quotedLiteral(text: String): String? {
    val quote = text.firstOrNull() ?: return null
    if (quote != '\'' && quote != '"') return null
    if (skipQuoted(text, 0) != text.length) return null
    val body = text.substring(1, text.lastIndex)
    if ('\\' !in body) return body
    val out = StringBuilder(body.length)
    var i = 0
    while (i < body.length) {
        // Only the escape itself is interpreted: `\n` is the letter n, not a newline. The
        // specification defines quoting for literals but no escape sequences inside them, and
        // inventing a table here would make a template mean different things on different renderers.
        if (body[i] == '\\' && i + 1 < body.length) {
            out.append(body[i + 1])
            i += 2
        } else {
            out.append(body[i])
            i++
        }
    }
    return out.toString()
}

/** [text] as a JSON number literal, or null when it is not one. */
private fun numberLiteral(text: String): JsonPrimitive? {
    val first = text.firstOrNull() ?: return null
    // Guards `toDoubleOrNull`, which also accepts `NaN`, `Infinity`, `0x1p3` and a trailing `d` —
    // none of which JSON has, and all of which would otherwise shadow a relative path of that name.
    if (first != '-' && first != '+' && !first.isDigit()) return null
    if (!text.all { it.isDigit() || it in "+-.eE" }) return null
    val value = text.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    // `encodeNumber` rather than `JsonPrimitive(Double)`: an integral literal has to interpolate
    // as `7`, not as `7.0`, since JSON draws no line between the two but a rendered string does.
    return encodeNumber(value)
}

private fun isFunctionName(text: String): Boolean {
    if (text == FunctionCall.INDEX) return true
    return text.isNotEmpty() && text.first().isLetter() && text.all { it.isLetterOrDigit() || it == '_' }
}

private fun isArgumentName(text: String): Boolean =
    text.isNotEmpty() && (text.first().isLetter() || text.first() == '_') &&
        text.all { it.isLetterOrDigit() || it == '_' }

/**
 * Whether [text] can be read as a JSON Pointer, absolute or relative.
 *
 * Deliberately loose — [JsonPointer.parse] is what actually validates the escapes — but not empty,
 * and free of the characters that would mean the expression was meant as something else and is
 * malformed. Whitespace is one of those: `${first name}` is a mistake, not a path with a space.
 */
private fun isDataPath(text: String): Boolean =
    text.isNotEmpty() && text.none { it.isWhitespace() || it in "(){}$,:'\"" }
