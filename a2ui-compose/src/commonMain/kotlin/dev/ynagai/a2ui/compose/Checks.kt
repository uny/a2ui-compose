package dev.ynagai.a2ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.CheckRule
import dev.ynagai.a2ui.core.protocol.Severity
import dev.ynagai.a2ui.core.protocol.ValidationResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

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
 * Once the rule is read, though, its condition is what holds the gate, and anything other than a
 * passing answer counts as a failure: a condition whose evaluation *raises*, because the user
 * decides whether it raises (`regex` refuses a subject past the evaluator's limits, so skipping
 * would let anyone re-open a gated `Button` by pasting enough characters into the field the rule
 * is about), and a result that is neither a boolean nor an object with a boolean `valid`, because
 * dropping it opens the same gate for a value the agent or a host function produced.
 *
 * A condition that resolves to nothing -- a path whose `updateDataModel` has not arrived -- is
 * neither of those. It is skipped, because progressive rendering is not a validation failure. The
 * evaluator answers JSON `null` for it, so an explicit `null` and a function returning `null` are
 * skipped the same way.
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
 * [rule]'s condition as a [ValidationResult]: a failure when evaluating it raises or when its result
 * is not a validity, and null only when it resolves to nothing yet.
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
    // Nothing behind the condition yet -- a path whose `updateDataModel` has not arrived. That is
    // progressive rendering, not an answer, so the rule waits rather than failing. The evaluator
    // answers JSON `null` for that, and for an explicit `null` in the data model and a function
    // that returns `null` too; this cannot tell them apart, so all three are skipped (upstream
    // fails the last two).
    if (element is JsonNull) return null
    (element as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull?.let {
        return ValidationResult(valid = it)
    }
    // **A value that is not a validity has not passed.** Read as "no rule" -- what this did
    // before -- `{"valid": false, "message": null}` from a serializer that writes its nulls, or a
    // condition resolving to a string, re-opened the gate the agent wrote. The rule's own message
    // and `Severity.DEFAULT` stand in for the fields such a value does not supply.
    return (element as? JsonObject)?.let(::readValidationResult) ?: ValidationResult(valid = false)
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
