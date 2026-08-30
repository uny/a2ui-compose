package dev.ynagai.a2ui.material3

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.ynagai.a2ui.compose.ComponentRenderer
import dev.ynagai.a2ui.compose.firstMessage
import dev.ynagai.a2ui.compose.rememberBoolean
import dev.ynagai.a2ui.compose.rememberCheckFailures
import dev.ynagai.a2ui.compose.rememberString
import kotlinx.serialization.json.JsonPrimitive

/**
 * `CheckBox` -- a box and its label, toggling one boolean in the data model.
 *
 * The whole row is the target, not the box: `toggleable` on the row with the `Checkbox` itself
 * given a null callback is Material's own recipe for a labelled checkbox, and it is what makes the
 * label a place to tap. It is also what gives the pair a single accessibility node reading "label,
 * checked" rather than an unlabelled box beside some text.
 *
 * **A checkbox whose `value` is not a data binding is disabled**, for [TextFieldRenderer]'s reason:
 * a literal or a function result gives it nowhere to write, and a box that visibly toggled and
 * then sprang back would read as a broken renderer. Disabled rather than read-only because a
 * checkbox has no way to look at rest and refuse -- the greyed state is the only "you cannot
 * change this" Material gives it.
 *
 * An absent `value` reads as unchecked. The catalog requires the property, so this is a payload
 * the schema already refuses; drawing the unchecked box is the same degradation an unreadable
 * property gets everywhere else here.
 */
public val CheckBoxRenderer: ComponentRenderer = ComponentRenderer { scope, modifier ->
    val label = scope.rememberString("label")
    val checked = scope.rememberBoolean("value") ?: false
    val target = remember(scope) { scope.binding("value") }
    val failure = scope.rememberCheckFailures().firstMessage()
    Column(modifier = modifier.leafMargin()) {
        Row(
            modifier = Modifier.toggleable(
                value = checked,
                enabled = target != null,
                role = Role.Checkbox,
                onValueChange = { now -> target?.let { scope.write(it, JsonPrimitive(now)) } },
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Null, so the box does not take the click for itself: the row above already carries
            // the toggle, and a `Checkbox` with its own handler would be a second target inside
            // the first and would split the accessibility node in two.
            Checkbox(checked = checked, onCheckedChange = null, enabled = target != null)
            if (label != null) {
                Text(text = label, modifier = Modifier.padding(start = LABEL_GAP))
            }
        }
        CheckMessage(failure)
    }
}

/**
 * Between the box and its label.
 *
 * Padding on the label rather than a `spacedBy` on the row: the gap belongs inside the toggleable
 * area, so that tapping the space between the two still toggles.
 */
private val LABEL_GAP = 8.dp
