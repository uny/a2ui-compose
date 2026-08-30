package dev.ynagai.a2ui.material3

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import dev.ynagai.a2ui.compose.ComponentRenderer
import dev.ynagai.a2ui.compose.firstMessage
import dev.ynagai.a2ui.compose.hasError
import dev.ynagai.a2ui.compose.rememberCheckFailures
import dev.ynagai.a2ui.compose.rememberString
import kotlinx.serialization.json.JsonPrimitive

/**
 * `TextField` -- two-way binding's write half.
 *
 * The value shown is read from the data model and the value typed is written straight back to it,
 * so the field holds no text of its own. That is the "immediately write the new string back to the
 * local data model path" the implementation guide asks for, and it is also what makes a field
 * agree with everything else bound to the same path: the `formatString` in the specification's
 * own `00_formatted-text` example updates as the field is typed into because both read the one
 * model.
 *
 * **A field whose `value` is not a data binding is read-only.** A literal, a function result, or
 * an absent `value` gives the field nowhere to write, and a writable-looking field that discards
 * every keystroke reads as a broken renderer rather than as a payload that asked for one.
 */
public val TextFieldRenderer: ComponentRenderer = ComponentRenderer { scope, modifier ->
    val label = scope.rememberString("label")
    val placeholder = scope.rememberString("placeholder")
    val variant = scope.rememberString("variant")
    val value = scope.rememberString("value")
    // Keyed on the scope alone: the pointer comes from the component's properties and this scope's
    // evaluation scope, and both of those are already what the scope's own identity is keyed on.
    val target = remember(scope) { scope.binding("value") }
    val failures = scope.rememberCheckFailures()
    val failure = failures.firstMessage()
    OutlinedTextField(
        value = value.orEmpty(),
        onValueChange = { typed -> target?.let { scope.write(it, JsonPrimitive(typed)) } },
        // The parent's modifier plus the margin every framed component carries -- see
        // [leafMargin] -- and nothing else. A `fillMaxWidth` here measured against whatever
        // width the *grandparent* offered rather than against this field's share of its container,
        // so a field beside a `Button` in a `Row` took the whole row and left the button at zero
        // width. A `Column` with the catalog's default `align` already stretches its children, so
        // the common case still fills; letting the container decide is what makes the uncommon
        // one survive.
        modifier = modifier.leafMargin(),
        readOnly = target == null,
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = variant != "longText",
        visualTransformation =
            if (variant == "obscured") PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType(variant)),
        // A failing `check` colours the field and captions it. Only an `error` colours it: a
        // `warning` still gets its message, in the ordinary supporting-text colour, because
        // painting the field red for one would leave the agent no way to remark without alarming.
        //
        // Read off the whole list rather than off the captioned failure, because the two questions
        // are different: an `error` whose rule and result both omit a message has nothing to
        // display, but it is still what disables the `Button` -- and a field drawn as valid beside
        // a greyed-out action is the state that leaves the user with no reason anywhere on screen.
        // This is the same list `hasError` gates the action on, so the two cannot disagree.
        isError = failures.hasError(),
        supportingText = failure?.let { { CheckMessage(it) } },
    )
}

/**
 * Which keyboard a variant asks for.
 *
 * `number` changes the keyboard and nothing else. The catalog types a `TextField`'s `value` as a
 * string whatever the variant, so a numeric field still writes back the text the user typed --
 * writing a JSON number instead would change the type of the agent's own data model out from
 * under it.
 */
private fun keyboardType(variant: String?): KeyboardType = when (variant) {
    "number" -> KeyboardType.Number
    "obscured" -> KeyboardType.Password
    else -> KeyboardType.Text
}
