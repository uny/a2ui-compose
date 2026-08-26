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
    val out = StringBuilder()
    var i = 0
    while (i < template.length) {
        val marker = template.indexOf(OPEN, i)
        if (marker < 0) {
            out.appendBounded(evaluator, template, i, template.length)
            break
        }
        // `\${` is the escape the specification defines, and it is the only one: a backslash
        // anywhere else is an ordinary character. Only the character immediately before the marker
        // is examined, and the backslash is consumed by the escape, so `C:\${x}` renders the
        // literal `C:${x}` rather than interpolating, and `C:\\${x}` renders `C:\${x}` — doubling
        // the backslash does not restore the interpolation.
        val escaped = marker > i && template[marker - 1] == '\\'
        out.appendBounded(evaluator, template, i, if (escaped) marker - 1 else marker)
        if (escaped) {
            out.appendBounded(evaluator, OPEN, 0, OPEN.length)
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
        out.appendBounded(evaluator, text, 0, text.length)
        i = close + 1
    }
    return out.toString()
}

private const val OPEN: String = "\${"
private const val ERROR_EXCERPT: Int = 64

/**
 * Appends `[from, to)` of [text], having first charged it to the evaluation's result budget.
 *
 * Before, not after. A template that interpolates a megabyte-long binding a thousand times would
 * otherwise allocate the whole result and then report that it was too long, which is the cheapest
 * way for an agent to exhaust a renderer and the case a bound checked afterwards does not close.
 *
 * The charge goes to [Evaluator.produce] rather than to this builder's own length, so that the
 * budget spans the whole evaluation. Measuring each builder separately bounded no total: one call
 * may carry thousands of arguments, each interpolating its own near-limit string, and all of them
 * are held at once while the argument map is assembled.
 */
private fun StringBuilder.appendBounded(
    evaluator: Evaluator,
    text: CharSequence,
    from: Int,
    to: Int,
) {
    if (to <= from) return
    evaluator.produce(to - from, FunctionNames.FORMAT_STRING)
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
                "formatString: `${name.take(ERROR_EXCERPT)}` is not a function name.",
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
 * `@index` is dispatched here like every other function rather than being sent back through the
 * wire-shaped path. Sending it back looked safe — its only argument is a number — but the argument
 * is only a number *after* evaluation: `${@index(offset:/o)}` where `/o` holds `{"path":"/n"}`
 * resolves to that object here and would then be read as a binding and followed a second time,
 * which is exactly what [CallArguments] documents `preEvaluated` as preventing. There is no
 * `catalogId` to check on this path, because the template grammar has no way to write one.
 */
private fun callFunction(
    evaluator: Evaluator,
    name: String,
    arguments: Map<String, JsonElement>,
    depth: Int,
): JsonElement {
    evaluator.step(name)
    if (name == FunctionCall.INDEX) {
        return evaluator.index(
            CallArguments(evaluator, name, arguments, preEvaluated = true, depth = depth),
        )
    }
    return evaluator.invoke(
        name,
        CallArguments(evaluator, name, arguments, preEvaluated = true, depth = depth),
    )
}

/**
 * The named arguments of a call, each evaluated.
 *
 * Every argument must be named. The basic catalog says so of `formatString` in as many words —
 * "Function arguments must be named" — and the one place the specification appears to show
 * otherwise, `${upper(${now()})}`, calls a function no catalog defines and is illustrating nesting
 * rather than argument syntax. Accepting an unnamed argument here would only ever produce payloads
 * that render on this renderer and fail on a conformant one, which is the divergence this module
 * spends its effort avoiding; nothing legal is refused by requiring the name.
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
        // One step per argument. Parsing was the one kind of work the budget did not cover, so an
        // argument list was bounded only by the payload's own size — and every argument's value is
        // retained in `out` until the call returns.
        evaluator.step(call)
        val text = part.trim()
        if (text.isEmpty()) {
            throw A2uiFunctionException(
                "formatString: `${call.take(ERROR_EXCERPT)}` has an empty argument.",
                FunctionNames.FORMAT_STRING,
            )
        }
        val colon = indexOfTop(text, ':')
        val name = if (colon < 0) null else text.substring(0, colon).trim()
        if (colon >= 0 && !isArgumentName(name!!)) {
            throw A2uiFunctionException(
                "formatString: `${name.take(ERROR_EXCERPT)}` is not an argument name in `${call.take(ERROR_EXCERPT)}`.",
                FunctionNames.FORMAT_STRING,
            )
        }
        if (name == null) {
            throw A2uiFunctionException(
                "formatString: arguments of `${call.take(ERROR_EXCERPT)}` must be named, but " +
                    "`${text.take(ERROR_EXCERPT)}` is not.",
                FunctionNames.FORMAT_STRING,
            )
        }
        val key = name
        if (out.containsKey(key)) {
            throw A2uiFunctionException(
                "formatString: `${call.take(ERROR_EXCERPT)}` names the argument `${key.take(ERROR_EXCERPT)}` twice.",
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

/**
 * [text] split on every [separator] that [indexOfTop] would find, in one pass.
 *
 * One pass, and one substring per part, because the obvious version — find the separator, keep the
 * tail, repeat — copies the whole remainder on every separator and re-scans it from the start. That
 * is quadratic in the number of arguments, and nothing bounded that number: the split runs to
 * completion before the first argument is inspected, so `${f(,,,,…)}` with 500 000 commas spent
 * about four seconds in here before reporting that argument one was empty.
 */
private fun splitTop(text: String, separator: Char): List<String> {
    val out = mutableListOf<String>()
    var start = 0
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
            // Before the parenthesis counter, for the reason given in [indexOfTop].
            c == separator && parens == 0 -> {
                out += text.substring(start, i)
                start = i + 1
            }
            c == '(' -> parens++
            c == ')' -> parens--
        }
        i++
    }
    out += text.substring(start)
    return out
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
