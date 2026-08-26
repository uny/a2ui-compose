package dev.ynagai.a2ui.core.function

import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.BoundValue
import dev.ynagai.a2ui.core.protocol.DataBinding
import dev.ynagai.a2ui.core.protocol.FunctionCall
import dev.ynagai.a2ui.core.protocol.ValidationResult
import dev.ynagai.a2ui.core.protocol.decodeBoundValue
import dev.ynagai.a2ui.core.protocol.encodeNumber
import dev.ynagai.a2ui.core.surface.EvaluationScope
import dev.ynagai.a2ui.core.surface.JsonPointer
import dev.ynagai.a2ui.core.surface.currentIndex
import dev.ynagai.a2ui.core.surface.resolve
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull

/**
 * Thrown when a [FunctionCall] cannot be evaluated.
 *
 * This is about the *call*, not about the data it reads: a missing argument, an argument of the
 * wrong type, a function no catalog defines, `@index` outside a list template, an evaluation that
 * outgrows its budget. A binding that resolves to nothing is not one of these — the specification
 * requires a renderer to tolerate that and show a placeholder, so it produces [JsonNull] instead.
 *
 * It is a [RuntimeException] rather than a
 * [dev.ynagai.a2ui.core.protocol.A2uiFormatException] for the same reason
 * [dev.ynagai.a2ui.core.surface.A2uiStateException] is: the payload parsed. Note that neither of
 * those is a supertype of this one, so a renderer evaluating agent-sent expressions has to catch
 * all three — [JsonPointer.parse] still raises the format one for a malformed `path`.
 */
public class A2uiFunctionException(
    message: String,
    public val call: String? = null,
) : RuntimeException(message)

/**
 * How many nested [FunctionCall]s deep an evaluation may go.
 *
 * Arguments are themselves dynamic values, so the agent — not the renderer — chooses the nesting,
 * and the evaluator recurses once per level. The bound is what keeps that from becoming a
 * [StackOverflowError], which is an `Error` that no `catch` in the message loop is written for and
 * which aborts the process outright on Kotlin/Native. It is deliberately far tighter than
 * [dev.ynagai.a2ui.core.surface.DEFAULT_MAX_DEPTH]: component nesting is structural, whereas an
 * expression more than a few calls deep is not something an agent writes on purpose.
 */
public const val DEFAULT_CALL_DEPTH: Int = 32

/**
 * How many bindings and calls a single top-level evaluation may perform.
 *
 * Depth alone does not bound the work: one `formatString` may hold any number of `${...}`
 * expressions, each of which may hold a call whose arguments hold more. The step budget is what
 * makes the total cost of an expression a function of the budget rather than of the payload.
 */
public const val DEFAULT_CALL_STEPS: Int = 10_000

/**
 * The longest string an evaluation may build.
 *
 * Checked *before* each piece is appended rather than after, so that a `formatString` interpolating
 * a megabyte-long value a thousand times fails while allocating the first megabyte instead of the
 * thousandth — the same ordering [dev.ynagai.a2ui.core.surface.walk] needs for its instance budget.
 */
public const val DEFAULT_MAX_RESULT_LENGTH: Int = 1 shl 20

/**
 * The longest `regex` pattern, and the longest subject, the evaluator will hand to [Regex].
 *
 * Backtracking blow-up is not something this can prevent — `(a+)+$` against a long subject is
 * quadratic or worse on every platform's engine, and no bound on either length makes a hostile
 * pattern safe. What these do is cap how bad it gets, and they are the reason a renderer should
 * treat catalogue functions as running on agent-controlled input rather than as pure formatting.
 */
public const val DEFAULT_MAX_PATTERN_LENGTH: Int = 1024

/** @see DEFAULT_MAX_PATTERN_LENGTH */
public const val DEFAULT_MAX_SUBJECT_LENGTH: Int = 64 * 1024

/** The bounds one evaluation runs under. See each default for what it is protecting against. */
public data class EvaluationLimits(
    public val maxDepth: Int = DEFAULT_CALL_DEPTH,
    public val maxSteps: Int = DEFAULT_CALL_STEPS,
    public val maxResultLength: Int = DEFAULT_MAX_RESULT_LENGTH,
    public val maxPatternLength: Int = DEFAULT_MAX_PATTERN_LENGTH,
    public val maxSubjectLength: Int = DEFAULT_MAX_SUBJECT_LENGTH,
) {
    public companion object {
        /** The bounds used when a caller does not choose. */
        public val DEFAULT: EvaluationLimits = EvaluationLimits()
    }
}

/**
 * Why a function is being evaluated, which is what `openUrl` is allowed to depend on.
 *
 * The specification requires `openUrl` to run only "in response to an active, physical user
 * interaction", and to reject "any uninitiated invocation (e.g. initial layout render auto-trigger
 * or dynamic data binding evaluation)". A renderer cannot enforce that from inside the function, so
 * it is the caller that declares which of the two it is doing.
 */
public enum class InvocationContext {
    /** Evaluating a property to draw it. Side-effecting functions are refused here. */
    RENDER,

    /** Running an action the user just triggered. */
    USER_ACTION,
}

/**
 * Opens a URL the evaluator has already validated.
 *
 * The evaluator performs the checks the specification makes mandatory — the `http`/`https` scheme
 * allowlist, and the user-activation requirement — and then delegates, because opening a URL is a
 * platform capability that `commonMain` does not have. Tab-nabbing protection (`noopener,noreferrer`)
 * belongs to the browser implementation of this interface.
 *
 * There is no default. `openUrl` fails with [A2uiFunctionException] until a renderer installs one,
 * which is a visible failure rather than a call that silently does nothing.
 */
public fun interface UrlOpener {
    /** Opens [url], which is guaranteed to be absolute and `http`- or `https`-schemed. */
    public fun open(url: String)
}

/**
 * Everything an evaluation reads besides the call itself.
 *
 * [scope] is what makes the same expression mean different things in different rows of a list: it
 * fixes where a relative binding measures from and what `@index` returns.
 */
public class EvaluationContext(
    public val dataModel: JsonElement,
    public val scope: EvaluationScope = EvaluationScope.Root,
    public val locale: LocaleFormatter = FallbackLocaleFormatter,
    public val invocation: InvocationContext = InvocationContext.RENDER,
    public val urlOpener: UrlOpener? = null,
    public val limits: EvaluationLimits = EvaluationLimits.DEFAULT,
    public val json: Json = A2uiJson.strict,
) {
    /** The same context evaluating in [scope] instead — one row of a list template, typically. */
    public fun inScope(scope: EvaluationScope): EvaluationContext = EvaluationContext(
        dataModel = dataModel,
        scope = scope,
        locale = locale,
        invocation = invocation,
        urlOpener = urlOpener,
        limits = limits,
        json = json,
    )
}

/**
 * [value] resolved against this context.
 *
 * A binding that resolves to nothing yields [JsonNull], and so does a function whose return type is
 * `void`. Every other failure raises — see [A2uiFunctionException].
 *
 * Each call gets a fresh budget, so the bounds in [EvaluationLimits] are per top-level evaluation
 * rather than per surface or per frame.
 */
public fun EvaluationContext.evaluate(value: BoundValue): JsonElement =
    Evaluator(this).evaluate(value, depth = 0)

/** [call] evaluated against this context. @see evaluate */
public fun EvaluationContext.evaluate(call: FunctionCall): JsonElement =
    Evaluator(this).evaluate(call, depth = 0)

/**
 * The [ValidationResult] a check's `condition` evaluates to.
 *
 * A condition may be a call to one of the five validation functions or a [DataBinding] into the
 * data model — the specification allows both, since an agent may compute a result server-side and
 * send it down. Either way the value has to be shaped like a `ValidationResult`, and one that is
 * not raises rather than being read as "invalid": a renderer cannot tell a failed check from a
 * malformed one by looking at a `false`.
 */
public fun EvaluationContext.evaluateCheck(condition: BoundValue): ValidationResult {
    val value = evaluate(condition)
    if (value !is JsonObject) {
        throw A2uiFunctionException(
            "a check condition must evaluate to a ValidationResult object, but produced " +
                describe(value) + ".",
        )
    }
    return json.decodeFromJsonElement(ValidationResult.serializer(), value)
}

/** The machine-readable `code` each built-in validation function reports a failure with. */
public object ValidationCode {
    public const val REQUIRED: String = "REQUIRED"
    public const val PATTERN_MISMATCH: String = "PATTERN_MISMATCH"
    public const val TOO_SHORT: String = "TOO_SHORT"
    public const val TOO_LONG: String = "TOO_LONG"
    public const val OUT_OF_RANGE: String = "OUT_OF_RANGE"
    public const val NOT_A_NUMBER: String = "NOT_A_NUMBER"
    public const val INVALID_EMAIL: String = "INVALID_EMAIL"
}

/** The names of the basic catalog's functions, plus the one system function. */
internal object FunctionNames {
    const val REQUIRED = "required"
    const val REGEX = "regex"
    const val LENGTH = "length"
    const val NUMERIC = "numeric"
    const val EMAIL = "email"
    const val FORMAT_STRING = "formatString"
    const val FORMAT_NUMBER = "formatNumber"
    const val FORMAT_CURRENCY = "formatCurrency"
    const val FORMAT_DATE = "formatDate"
    const val PLURALIZE = "pluralize"
    const val OPEN_URL = "openUrl"
    const val AND = "and"
    const val OR = "or"
    const val NOT = "not"
}

/**
 * `^[^\s@]+@[^\s@]+\.[^\s@]+$`, the pattern the implementation guide names for `email`.
 *
 * It is not RFC 5322 and is not meant to be. Matching the guide matters more than being right,
 * because an agent that sends an address this rejects and another renderer accepts has produced a
 * form the user cannot submit on one platform only.
 */
private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

/** The schemes `openUrl` may hand to a [UrlOpener]. */
private val ALLOWED_URL_SCHEMES = setOf("http", "https")

/**
 * One top-level evaluation, holding the step budget that spans it.
 *
 * Depth travels as a parameter rather than as state because it has to fall back on the way out of
 * a nested call, which a field would have to remember to do.
 */
internal class Evaluator(val context: EvaluationContext) {
    private var steps: Int = 0

    fun evaluate(value: BoundValue, depth: Int): JsonElement = when (value) {
        is DataBinding -> resolve(value.path)
        is FunctionCall -> evaluate(value, depth)
    }

    fun evaluate(call: FunctionCall, depth: Int): JsonElement {
        // Read on the way in, before any argument is touched: an expression nested past the bound
        // must cost one comparison rather than a full evaluation of everything underneath it.
        if (depth > context.limits.maxDepth) {
            throw A2uiFunctionException(
                "expression nests function calls more than ${context.limits.maxDepth} deep.",
                call.call,
            )
        }
        step(call.call)
        if (call.isSystemFunction) return index(call, depth)
        val args = CallArguments(this, call.call, call.args, preEvaluated = false, depth = depth)
        return invoke(call.call, args)
    }

    /**
     * Dispatches by name.
     *
     * The dispatch is by name alone; [FunctionCall.catalogId] is not consulted. Deciding which
     * catalog a name belongs to needs the surface's default catalog and the catalog registry, and
     * refusing a call whose function no catalog defines is the catalog-driven checker's job. What
     * this refuses is a name it has no implementation for, which is a different thing and is
     * reported as such.
     */
    fun invoke(name: String, args: CallArguments): JsonElement = when (name) {
        FunctionNames.REQUIRED -> required(args)
        FunctionNames.REGEX -> regex(args)
        FunctionNames.LENGTH -> length(args)
        FunctionNames.NUMERIC -> numeric(args)
        FunctionNames.EMAIL -> email(args)
        FunctionNames.FORMAT_STRING -> JsonPrimitive(formatString(args))
        FunctionNames.FORMAT_NUMBER -> JsonPrimitive(formatNumber(args))
        FunctionNames.FORMAT_CURRENCY -> JsonPrimitive(formatCurrency(args))
        FunctionNames.FORMAT_DATE -> JsonPrimitive(formatDate(args))
        FunctionNames.PLURALIZE -> JsonPrimitive(pluralize(args))
        FunctionNames.OPEN_URL -> openUrl(args)
        FunctionNames.AND -> JsonPrimitive(all(args, expected = true))
        FunctionNames.OR -> JsonPrimitive(!all(args, expected = false))
        FunctionNames.NOT -> JsonPrimitive(!args.boolean("value"))
        else -> throw A2uiFunctionException("no function named `$name` is implemented.", name)
    }

    /** One unit of the step budget, read before the work it pays for. */
    fun step(call: String?) {
        if (++steps > context.limits.maxSteps) {
            throw A2uiFunctionException(
                "expression performs more than ${context.limits.maxSteps} evaluations.",
                call,
            )
        }
    }

    /** The value at [path], or [JsonNull] when the data model does not reach it. */
    fun resolve(path: String): JsonElement {
        step(null)
        val pointer = JsonPointer.parse(path)
        return context.scope.resolve(context.dataModel, pointer) ?: JsonNull
    }

    /**
     * Evaluates one wire-level argument: a binding, a nested call, or a literal.
     *
     * The three are told apart by [decodeBoundValue] rather than by trying each in turn, which is
     * what keeps an object literal that happens to carry a `path` key from being read as a
     * binding — see that function for why the distinction is by key presence.
     */
    fun argument(element: JsonElement, depth: Int): JsonElement {
        val bound = decodeBoundValue(element, context.json, "function argument")
            ?: return element
        return evaluate(bound, depth + 1)
    }

    /**
     * `@index`, the one system function.
     *
     * Outside a list template [EvaluationScope.currentIndex] is null, and the specification says a
     * call there "MUST" be an error or evaluate as invalid. Raising is the readable half of that
     * choice: returning 0 would render a plausible list of ones and twos that nothing reports.
     */
    private fun index(call: FunctionCall, depth: Int): JsonElement {
        if (call.catalogId != null) {
            throw A2uiFunctionException(
                "`${FunctionCall.INDEX}` is a system function and takes no `catalogId`.",
                call.call,
            )
        }
        val current = context.scope.currentIndex() ?: throw A2uiFunctionException(
            "`${FunctionCall.INDEX}` is only available inside a list template's item scope.",
            call.call,
        )
        val args = CallArguments(this, call.call, call.args, preEvaluated = false, depth = depth)
        val offset = args.optionalNumber("offset") ?: 0.0
        return encodeNumber(current + offset)
    }

    // ---- validation functions -------------------------------------------------------------
    // All five are declared `"returnType": "validationResult"` by the catalog, so they build the
    // object rather than returning the bare boolean the implementation guide's prose describes.

    /** Present and non-empty: not null, not `""`, not `[]`. */
    private fun required(args: CallArguments): JsonElement {
        val value = args.require("value")
        val present = when (value) {
            is JsonNull -> false
            is JsonArray -> value.isNotEmpty()
            is JsonObject -> true
            is JsonPrimitive -> !(value.isString && value.content.isEmpty())
        }
        return validation(present, ValidationCode.REQUIRED)
    }

    private fun regex(args: CallArguments): JsonElement {
        val pattern = args.string("pattern")
        if (pattern.length > context.limits.maxPatternLength) {
            throw A2uiFunctionException(
                "`regex`: pattern of ${pattern.length} characters exceeds the maximum of " +
                    "${context.limits.maxPatternLength}.",
                FunctionNames.REGEX,
            )
        }
        val element = args.require("value")
        if (element is JsonNull) return validation(false, ValidationCode.PATTERN_MISMATCH)
        val subject = args.asString(element, "value")
        if (subject.length > context.limits.maxSubjectLength) {
            throw A2uiFunctionException(
                "`regex`: subject of ${subject.length} characters exceeds the maximum of " +
                    "${context.limits.maxSubjectLength}.",
                FunctionNames.REGEX,
            )
        }
        val compiled = try {
            Regex(pattern)
        } catch (e: Throwable) {
            // Deliberately broad, and the breadth is the point. Regex syntax differs between the
            // engines, so a pattern that compiles on one target may not on another — and they do
            // not agree on how to say so either: the JVM and Native raise
            // `IllegalArgumentException`, Kotlin/Wasm maps its engine's failure onto that, and
            // Kotlin/JS lets the raw `SyntaxError` through as a `Throwable` of no Kotlin type at
            // all. Catching only the declared type would make `regex` the one function whose
            // failure mode depends on which target the renderer was built for.
            throw A2uiFunctionException(
                "`regex`: `$pattern` is not a valid pattern on this platform (${e.message}).",
                FunctionNames.REGEX,
            )
        }
        // `containsMatchIn`, not `matches`: the guide specifies JavaScript `RegExp.test`
        // semantics, which are a search. An agent that wants the whole string anchors it.
        return validation(compiled.containsMatchIn(subject), ValidationCode.PATTERN_MISMATCH)
    }

    /**
     * String length against `min` and `max`.
     *
     * The length is UTF-16 code units, matching `String.length` in JavaScript and on the JVM. It
     * is not a count of characters a reader would recognise — an emoji counts as two — but a
     * renderer that counted grapheme clusters would reject inputs another renderer accepts, and
     * the constraint is being checked against a bound the agent wrote for one of those.
     */
    private fun length(args: CallArguments): JsonElement {
        val min = args.optionalNumber("min")?.toInt()
        val max = args.optionalNumber("max")?.toInt()
        if (min == null && max == null) {
            throw A2uiFunctionException(
                "`length` needs at least one of `min` and `max`.",
                FunctionNames.LENGTH,
            )
        }
        val element = args.require("value")
        if (element is JsonNull) return validation(false, ValidationCode.TOO_SHORT)
        val value = args.asString(element, "value")
        if (min != null && value.length < min) return validation(false, ValidationCode.TOO_SHORT)
        if (max != null && value.length > max) return validation(false, ValidationCode.TOO_LONG)
        return validation(true, null)
    }

    /** Numeric range. A value that cannot be read as a number is invalid rather than an error. */
    private fun numeric(args: CallArguments): JsonElement {
        val min = args.optionalNumber("min")
        val max = args.optionalNumber("max")
        if (min == null && max == null) {
            throw A2uiFunctionException(
                "`numeric` needs at least one of `min` and `max`.",
                FunctionNames.NUMERIC,
            )
        }
        val value = args.asNumberOrNull(args.require("value"))
            ?: return validation(false, ValidationCode.NOT_A_NUMBER)
        val inRange = (min == null || value >= min) && (max == null || value <= max)
        return validation(inRange, ValidationCode.OUT_OF_RANGE)
    }

    private fun email(args: CallArguments): JsonElement {
        val element = args.require("value")
        if (element is JsonNull) return validation(false, ValidationCode.INVALID_EMAIL)
        val value = args.asString(element, "value")
        return validation(EMAIL_PATTERN.matches(value), ValidationCode.INVALID_EMAIL)
    }

    /** `{"valid": …}`, carrying [code] only on failure — a passing check has nothing to report. */
    private fun validation(valid: Boolean, code: String?): JsonObject = buildJsonObject {
        put("valid", JsonPrimitive(valid))
        if (!valid && code != null) put("code", JsonPrimitive(code))
    }

    // ---- formatting functions -------------------------------------------------------------

    private fun formatString(args: CallArguments): String =
        interpolate(args.string("value"), args.depth)

    private fun formatNumber(args: CallArguments): String = context.locale.formatNumber(
        value = args.number("value"),
        decimals = args.optionalNumber("decimals")?.toInt(),
        grouping = args.optionalBoolean("grouping") ?: true,
    )

    private fun formatCurrency(args: CallArguments): String = context.locale.formatCurrency(
        value = args.number("value"),
        currency = args.string("currency"),
        decimals = args.optionalNumber("decimals")?.toInt(),
        grouping = args.optionalBoolean("grouping") ?: true,
    )

    /**
     * A date, from either an epoch-millisecond number or an ISO 8601 string.
     *
     * The catalog types `value` as an unconstrained `DynamicValue` and the guide only says "parse
     * into a native Date/Time object", so both forms are accepted: an agent has no way to know
     * which one a given renderer wants, and rejecting one of them would make working payloads
     * renderer-specific.
     */
    private fun formatDate(args: CallArguments): String {
        val value = args.require("value")
        val instant = epochMillisOf(value) ?: throw A2uiFunctionException(
            "`formatDate`: ${describe(value)} is not an epoch-millisecond number or an " +
                "ISO 8601 date.",
            FunctionNames.FORMAT_DATE,
        )
        return context.locale.formatDate(instant, args.string("format"))
    }

    /**
     * The string for the value's plural category, falling back to `other`.
     *
     * The fallback is what the guide specifies, and it is also why `other` is the one required
     * argument: a locale whose rules name a category the agent did not supply still renders.
     */
    private fun pluralize(args: CallArguments): String {
        val value = args.number("value")
        val category = context.locale.pluralCategory(value)
        return args.optionalString(category.argumentName)
            ?: args.optionalString(PluralCategory.OTHER.argumentName)
            ?: throw A2uiFunctionException(
                "`pluralize` requires an argument `other`.",
                FunctionNames.PLURALIZE,
            )
    }

    // ---- side effects ---------------------------------------------------------------------

    /**
     * Opens a URL, after the two checks the specification makes mandatory.
     *
     * Relative URLs are refused rather than resolved. The guide says to resolve them against "the
     * current environment context (e.g. `window.location.href`)", which is exactly the thing this
     * module does not have — and a relative URL that reached the scheme allowlist unresolved would
     * pass it vacuously, which is the hole the allowlist exists to close.
     */
    private fun openUrl(args: CallArguments): JsonElement {
        if (context.invocation != InvocationContext.USER_ACTION) {
            throw A2uiFunctionException(
                "`openUrl` may only run in response to a user interaction.",
                FunctionNames.OPEN_URL,
            )
        }
        val url = args.string("url")
        val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
        if (scheme !in ALLOWED_URL_SCHEMES) {
            throw A2uiFunctionException(
                "`openUrl`: `${url.take(64)}` is not an absolute http or https URL.",
                FunctionNames.OPEN_URL,
            )
        }
        val opener = context.urlOpener ?: throw A2uiFunctionException(
            "`openUrl` needs a UrlOpener, which this EvaluationContext does not carry.",
            FunctionNames.OPEN_URL,
        )
        opener.open(url)
        // `"returnType": "void"`. Null rather than an absent result so that a `void` function
        // interpolated into a `formatString` produces "" like any other absent value.
        return JsonNull
    }

    // ---- logic ----------------------------------------------------------------------------

    /**
     * Whether every item of `values` equals [expected], short-circuiting at the first that does not.
     *
     * `and` and `or` are the same fold: `or` is `and` over the negation, so one implementation
     * covers both and neither can drift from the other's evaluation order.
     */
    private fun all(args: CallArguments, expected: Boolean): Boolean {
        for (item in args.list("values")) {
            if (args.asBoolean(item(), "values") != expected) return false
        }
        return true
    }

    // ---- formatString ---------------------------------------------------------------------

    /** @see interpolateTemplate */
    fun interpolate(template: String, depth: Int): String = interpolateTemplate(this, template, depth)

    // ---- coercion -------------------------------------------------------------------------

    /** [element] as an instant, or null when it is neither form. */
    private fun epochMillisOf(element: JsonElement): Long? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        if (!primitive.isString) return primitive.doubleOrNull?.toLong()
        return parseIso8601(primitive.content)
    }
}

/**
 * The arguments of one call, evaluated on demand.
 *
 * On demand rather than up front so that `and` and `or` can short-circuit, and so that a call with
 * an argument the function does not read never pays for it.
 *
 * [preEvaluated] distinguishes the two ways a call reaches [Evaluator.invoke]. From the wire, each
 * argument is a `Dynamic*` union that still has to be resolved. From inside a `formatString`
 * expression, the parser has already resolved each argument to a value — and re-resolving it would
 * be wrong, not merely wasteful: a data path that resolved to `{"path": "/x"}` would be read as a
 * binding and followed a second time.
 *
 * Arguments the function does not name are ignored rather than refused. What a call may carry is
 * fixed by the catalog's schema for it (`unevaluatedProperties: false`), and checking a call
 * against that schema is the catalog-driven checker's job; duplicating the argument lists here
 * would put the same rule in two places and let them disagree.
 */
internal class CallArguments(
    private val evaluator: Evaluator,
    private val call: String,
    private val raw: Map<String, JsonElement>?,
    private val preEvaluated: Boolean,
    val depth: Int,
) {
    private fun evaluated(element: JsonElement): JsonElement =
        if (preEvaluated) element else evaluator.argument(element, depth)

    /**
     * The value of [name], or null when the call does not carry that argument at all.
     *
     * "Not carried" and "carried but resolving to nothing" are different failures and are kept
     * apart here: the first is a malformed call, while the second is the ordinary case of a
     * binding whose `updateDataModel` has not arrived — which a validation function reports as
     * invalid rather than raising on. [optional] collapses the two for the arguments where the
     * distinction does not matter.
     */
    fun value(name: String): JsonElement? = raw?.get(name)?.let(::evaluated)

    fun optional(name: String): JsonElement? = value(name)?.takeIf { it !is JsonNull }

    fun require(name: String): JsonElement = value(name)
        ?: throw A2uiFunctionException("`$call` requires an argument `$name`.", call)

    /**
     * A string, converting a number or boolean rather than refusing it.
     *
     * The conversion is the one `formatString` specifies for interpolation, applied here for the
     * same reason: a data model that holds `42` where the catalog types a `DynamicString` is a
     * mistake the agent made, but rendering "42" is a better answer than refusing to draw.
     */
    fun optionalString(name: String): String? = optional(name)?.let { asString(it, name) }

    fun string(name: String): String = asString(require(name), name)

    fun optionalNumber(name: String): Double? = optional(name)?.let { asNumber(it, name) }

    fun number(name: String): Double = asNumber(require(name), name)

    fun optionalBoolean(name: String): Boolean? = optional(name)?.let { asBoolean(it, name) }

    fun boolean(name: String): Boolean = asBoolean(require(name), name)

    /**
     * The items of an array argument, each still to be evaluated when the caller asks for it.
     *
     * The array itself may arrive two ways: written out in the call, in which case each item is a
     * `Dynamic*` union of its own, or produced by a binding, in which case the items are already
     * values. Both are accepted, and the thunks close over which.
     */
    fun list(name: String): List<() -> JsonElement> {
        val element = raw?.get(name)
            ?: throw A2uiFunctionException("`$call` requires an argument `$name`.", call)
        if (element is JsonArray && !preEvaluated) return element.map { { evaluated(it) } }
        val resolved = if (element is JsonArray) element else evaluated(element)
        val array = resolved as? JsonArray ?: throw A2uiFunctionException(
            "`$call`: `$name` must be an array, but was ${describe(resolved)}.",
            call,
        )
        return array.map { { it } }
    }

    fun asString(element: JsonElement, name: String): String {
        val primitive = (element as? JsonPrimitive)?.takeIf { it !is JsonNull }
            ?: throw A2uiFunctionException(
                "`$call`: `$name` must be a string, but was ${describe(element)}.",
                call,
            )
        return primitive.content
    }

    /** [element] as a number, or null when it cannot be read as one. */
    fun asNumberOrNull(element: JsonElement): Double? =
        (element as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { primitive ->
            // A quoted number is read as one. The data model behind a `DynamicNumber` binding is
            // whatever the agent last wrote there, and a text field writes back a string.
            if (primitive.isString) primitive.content.trim().toDoubleOrNull() else primitive.doubleOrNull
        }?.takeIf { it.isFinite() }

    fun asNumber(element: JsonElement, name: String): Double = asNumberOrNull(element)
        ?: throw A2uiFunctionException(
            "`$call`: `$name` must be a number, but was ${describe(element)}.",
            call,
        )

    fun asBoolean(element: JsonElement, name: String): Boolean =
        (element as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            ?: throw A2uiFunctionException(
                "`$call`: `$name` must be a boolean, but was ${describe(element)}.",
                call,
            )
}

/** A short, non-quoting description of [element] for an error message. */
internal fun describe(element: JsonElement): String = when (element) {
    is JsonNull -> "null"
    is JsonObject -> "an object"
    is JsonArray -> "an array"
    is JsonPrimitive -> if (element.isString) "the string `${element.content.take(32)}`"
    else "`${element.content}`"
}
