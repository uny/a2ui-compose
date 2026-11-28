package dev.ynagai.a2ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.BoundValue
import dev.ynagai.a2ui.core.protocol.CheckRule
import dev.ynagai.a2ui.core.protocol.DataBinding
import dev.ynagai.a2ui.core.protocol.Severity
import dev.ynagai.a2ui.core.protocol.ValidationResult
import dev.ynagai.a2ui.core.surface.JsonPointer
import dev.ynagai.a2ui.core.surface.resolve
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * One failed `checks` rule, in the terms a widget needs to show it.
 *
 * The three fields are the ones the protocol's `ValidationResult` carries that a renderer acts on.
 * [message] is what to display, already resolved through the fallback the schema describes -- the
 * result's own `message` when the condition produced one, otherwise the rule's -- so a widget
 * shows text without knowing which half of the payload supplied it.
 */
@ConsistentCopyVisibility
@Immutable
public data class A2uiCheckFailure internal constructor(
    public val message: String?,
    public val code: String?,
    public val severity: Severity,
)

/**
 * The `checks` on this component that are currently failing, in the order the agent wrote them.
 *
 * Evaluated on every read rather than cached, like every other bound property here: a check reads
 * the data model, so its answer changes as the user types.
 *
 * **A rule that cannot be *read* is left out; a condition that does not answer "valid" fails.** A
 * rule that will not decode as a `CheckRule` is left out of this list rather than reported as
 * invalid: that is the agent describing a rule this version does not understand, and it should
 * cost its own rule -- the same degradation [A2uiComponentScope.value] gives a malformed property.
 * Once the rule is read, though, its condition is what holds the gate, and it is never dropped. A
 * condition whose evaluation *raises* fails, because the user decides whether it raises (`regex`
 * refuses a subject past the evaluator's limits, so skipping would let anyone re-open a gated
 * `Button` by pasting enough characters into the field the rule is about). A result that is
 * neither a boolean nor an object with a boolean `valid` is read by the protocol's coercion table
 * -- `"true"` in any case and a non-zero number pass, every other string, `0`, an array and any
 * other object fail -- because dropping it opens the same gate for a value the agent or a host
 * function produced.
 *
 * A condition that is a bare `@path` with nothing behind it -- a path whose `updateDataModel` has
 * not arrived -- is neither of those. It is skipped, because progressive rendering is not a
 * validation failure. That is the only `null` a check skips: a path that holds an explicit JSON
 * `null`, and a function call that returns `null`, fail with the rule's message, because the
 * agent sent something and it was not "valid".
 *
 * A failure's severity comes from the result; a result that omits it is an error, per
 * [Severity.DEFAULT]. Callers that gate an interaction should gate on
 * [Severity.ERROR] alone, so that a `warning` says something without taking the action away.
 */
public fun A2uiComponentScope.checkFailures(): List<A2uiCheckFailure> {
    val raw = property("checks") as? JsonArray ?: return emptyList()
    // Decoded one rule at a time, never as a whole `ListSerializer`. The list form is all-or-
    // nothing: `A2uiJson.strict` refuses an unknown key, so a single rule carrying one -- or a
    // single `condition` that is not a `BoundValue` -- would throw for the array and take every
    // well-formed rule beside it down, leaving a `Button` the agent gated enabled and its field
    // uncaptioned. That is a validation bypass rather than a degradation, and it is the opposite
    // of what the paragraph above promises.
    return raw.mapNotNull { element ->
        val rule = runCatching {
            A2uiJson.strict.decodeFromJsonElement(CheckRule.serializer(), element)
        }.getOrNull() ?: return@mapNotNull null
        val result = evaluateCondition(rule) ?: return@mapNotNull null
        if (result.valid) {
            null
        } else {
            A2uiCheckFailure(
                message = result.message ?: rule.message,
                code = result.code,
                severity = result.severity ?: Severity.DEFAULT,
            )
        }
    }
}

/**
 * [rule]'s condition as a [ValidationResult]: a failure when evaluating it raises, the protocol's
 * coercion of its result otherwise, and null only when it is a `@path` the data model does not
 * reach yet.
 *
 * The distinction is the whole point and it is not a detail of the implementation -- see the
 * comment on the `getOrElse` below before changing either branch. Collapsing the raising case back
 * into null reopens a gate the user can walk through by typing past the evaluator's limits.
 *
 * Both shapes the specification describes are accepted. `catalog.json` types the basic catalog's
 * five validation functions as `"returnType": "validationResult"`, and the implementation guide's
 * prose for the same five says they return `true` or `false`; the specification's own examples
 * nest one inside the other. Reading either here is the same leniency the core evaluator applies
 * to `and`/`or`/`not`, in the one other place a validity has to be recognised.
 */
private fun A2uiComponentScope.evaluateCondition(rule: CheckRule): ValidationResult? {
    val outcome = evaluateCatching(rule.condition) ?: return null
    // **A condition that raises has not passed.** The user chooses how long the string in the
    // bound field is, and `regex` raises on a subject past `maxSubjectLength` -- so under the
    // gentler reading, pasting 2049 characters made the rule vanish and re-enabled the very
    // `Button` that rule was gating. A gate an attacker can open by feeding it something it
    // cannot chew is not a gate. The cost is the other direction: an agent that writes a
    // condition this evaluator refuses -- a function that does not exist, an argument of the
    // wrong type -- now disables the action rather than being ignored. That is the safe way for
    // this particular mistake to fail, and it is loud enough for the agent to notice.
    val element = outcome.getOrElse { return ValidationResult(valid = false) }
    // **Only an absent path waits; every other `null` fails.** The evaluator answers JSON `null`
    // for three different things, and only the first is progressive rendering:
    //
    // 1. a `@path` whose `updateDataModel` has not arrived -- skipped, the rule has not run yet;
    // 2. a `@path` that holds an explicit `null` -- the agent sent a value, and it is not a
    //    validity;
    // 3. a function call that returns `null` -- `formatNumber` and friends over a missing value.
    //
    // Skipping all three, as this did before, made the rule vanish for (2) and (3) and left the
    // `Button` it gates enabled. The upstream SDKs fail both (TS `coerceDynamicBoolean`, Python
    // `_process_rule_val`, Swift `makePathBoundCheck` and `coercedDynamicBoolean`); they fail (1)
    // too, which is the one place this keeps its own reading. The evaluator cannot say which of
    // the three it saw, so (1) is told apart here by looking the path up again the way
    // `Evaluator.resolve` does: absent is Kotlin `null`, an explicit one is [JsonNull].
    if (element is JsonNull) {
        return if (isAbsentPath(rule.condition)) null else ValidationResult(valid = false)
    }
    (element as? JsonObject)?.let(::readValidationResult)?.let { return it }
    // **Any other value is read by the protocol's coercion table, never dropped.** Read as "no
    // rule" -- what this did before -- `{"valid": false, "message": null}` from a serializer that
    // writes its nulls, or a condition resolving to a string, re-opened the gate the agent wrote.
    // `a2ui_protocol.md` ("`DynamicBoolean` and `CheckRule` Coercion", rule 3) says what each
    // shape means; a `false` reading takes the rule's own message and `Severity.DEFAULT`, as rule
    // 2 asks. **This reading covers a bare condition only.** The core evaluator's `and`/`or`/`not`
    // accept a boolean or a validity and raise on anything else, so the same `"true"` or `1`
    // passes here and fails as `and(values: ["true"])` or `not(value: 1)` -- through the raising
    // branch above, which fails closed rather than opening the gate.
    return ValidationResult(valid = coercedBoolean(element))
}

/**
 * Whether [condition] is a `@path` that this scope's data model does not reach.
 *
 * The same lookup `Evaluator.resolve` makes -- the pointer parsed, rebased on [evaluationScope],
 * resolved against the surface's data model -- kept to the one question the evaluator's answer
 * loses. Anything this cannot look up counts as present, so that a doubt fails the rule rather
 * than skipping it: a function call, a pointer that will not parse (the evaluation that reached
 * here would already have raised on it), a surface that has gone.
 */
private fun A2uiComponentScope.isAbsentPath(condition: BoundValue): Boolean {
    val binding = condition as? DataBinding ?: return false
    val model = surface?.dataModel ?: return false
    val pointer = runCatching { JsonPointer.parse(binding.path) }.getOrNull() ?: return false
    return evaluationScope.resolve(model, pointer) == null
}

/**
 * [element] by rule 3 of the protocol's coercion table, for a value that is neither a boolean nor a
 * `ValidationResult`: a string is `true` only when it spells `true` in any letter case, a number is
 * `true` unless it is zero (`0`, `0.0` and `-0` alike), and an object or an array is `false`.
 * JSON `null` never reaches this: [evaluateCondition] skips or fails it first.
 */
private fun coercedBoolean(element: JsonElement): Boolean {
    val primitive = element as? JsonPrimitive ?: return false
    if (primitive.isString) return primitive.content.equals("true", ignoreCase = true)
    return primitive.booleanOrNull ?: primitive.doubleOrNull?.let { it != 0.0 } ?: false
}

/**
 * [element] read as a `ValidationResult`, or null when it is not one: an object is one exactly when
 * its `valid` is a boolean.
 *
 * Shared with [A2uiComponentScope.boolean], so that a value bound to a check's condition and the
 * same value bound to a boolean property are recognised by one rule -- the rule `a2ui_protocol.md`
 * gives ("an object with a boolean `valid` property") and the three upstream SDKs apply (TS
 * `validityOf`, Python `validity_of`, Swift `coercedDynamicBoolean`), and the one this library's
 * own evaluator applies to `and`/`or`/`not`.
 *
 * **Lenient on purpose, unlike the wire serializer.** The other fields are read the way upstream's
 * `checks` binders read them: a `code` or `message` that is not a string -- an explicit `null`
 * included -- is absent, and a `severity` that is present but not one the protocol defines is
 * [Severity.ERROR], so an unrecognised severity can never soften a failure into a warning. Decoding
 * with [ValidationResult]'s strict serializer instead made all of those shapes unreadable, and an
 * unreadable result was the one a check then dropped.
 */
internal fun readValidationResult(element: JsonObject): ValidationResult? {
    val valid = (element["valid"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        ?: return null
    return ValidationResult(
        valid = valid,
        code = element.stringOrNull("code"),
        message = element.stringOrNull("message"),
        severity = when (val raw = element["severity"]) {
            null, JsonNull -> null
            else -> Severity.entries.firstOrNull {
                raw is JsonPrimitive && raw.isString && it.wireName == raw.content
            } ?: Severity.ERROR
        },
        additional = element.filterKeys { it !in VALIDATION_RESULT_KEYS },
    )
}

private val VALIDATION_RESULT_KEYS = setOf("valid", "code", "message", "severity")

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * The failing checks, recomposing the caller only when they change.
 *
 * Wrapped in `derivedStateOf` for the reason [rememberString] is: every scope reads the one
 * renderer state, so a `Button` whose checks watch `/email` would otherwise recompose on every
 * keystroke anywhere on the surface.
 */
@Composable
public fun A2uiComponentScope.rememberCheckFailures(): List<A2uiCheckFailure> {
    val value by remember(this) { derivedStateOf { checkFailures() } }
    return value
}

/**
 * Whether any failing check is severe enough to take an interaction away.
 *
 * The protocol's rule for a `Button`: "If any check fails, the button is automatically disabled."
 * Read as the errors alone, because a `warning` or an `info` result is a thing to say rather than
 * a reason to refuse -- a severity the agent could otherwise not use without losing the action.
 */
public fun List<A2uiCheckFailure>.hasError(): Boolean = any { it.severity == Severity.ERROR }

/**
 * The first failure worth showing next to an input, or null when there is nothing to say.
 *
 * **An error outranks the agent's order; nothing else does.** Within one severity the order the
 * agent wrote the rules in is the only ranking there is, so it is kept -- but a `warning` listed
 * first must not bury an `error` listed second, because the error is the one that also disables
 * the `Button` this input feeds. A renderer that showed the warning would leave the user with a
 * greyed-out action, a field drawn as valid, and the actual reason nowhere on screen.
 *
 * A failure whose result and whose rule both omit a message has nothing to display and is skipped;
 * it still counts for [hasError], which is what gates the action.
 */
public fun List<A2uiCheckFailure>.firstMessage(): A2uiCheckFailure? =
    firstOrNull { it.message != null && it.severity == Severity.ERROR }
        ?: firstOrNull { it.message != null }
