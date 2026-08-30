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
 * **A check that cannot be evaluated does not fail.** A rule that will not decode, a condition
 * whose function raises, or a result that is neither a boolean nor a `ValidationResult` is left
 * out of this list rather than reported as invalid. The alternative shows the user a validation
 * error the agent never expressed, and disables a `Button` for a reason nothing in the payload
 * says -- a malformed check should cost its own rule, which is the same degradation
 * [A2uiComponentScope.value] gives a malformed property.
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
 * [rule]'s condition as a [ValidationResult], or null when it cannot be read as one.
 *
 * Both shapes the specification describes are accepted. `catalog.json` types the basic catalog's
 * five validation functions as `"returnType": "validationResult"`, and the implementation guide's
 * prose for the same five says they return `true` or `false`; the specification's own examples
 * nest one inside the other. Reading either here is the same leniency the core evaluator applies
 * to `and`/`or`/`not`, in the one other place a validity has to be recognised.
 */
private fun A2uiComponentScope.evaluateCondition(rule: CheckRule): ValidationResult? {
    val element = evaluate(rule.condition) ?: return null
    (element as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull?.let {
        return ValidationResult(valid = it)
    }
    if (element !is JsonObject) return null
    return runCatching {
        A2uiJson.strict.decodeFromJsonElement(ValidationResult.serializer(), element)
    }.getOrNull()
}

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
