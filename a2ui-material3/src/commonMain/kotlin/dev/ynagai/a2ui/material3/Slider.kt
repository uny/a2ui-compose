package dev.ynagai.a2ui.material3

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import dev.ynagai.a2ui.compose.ComponentRenderer
import dev.ynagai.a2ui.compose.firstMessage
import dev.ynagai.a2ui.compose.rememberCheckFailures
import dev.ynagai.a2ui.compose.rememberNumber
import dev.ynagai.a2ui.compose.rememberString
import kotlinx.serialization.json.JsonPrimitive

/**
 * `Slider` -- a number in a range, written back as the thumb moves.
 *
 * **`steps` is not Compose's `steps`.** The catalog's is "the number of discrete divisions in the
 * slider range"; Compose's is the number of stops *between* the two ends. Four divisions of a
 * 0..100 range are the stops at 25, 50 and 75, so the two counts differ by one and passing the
 * catalog's number straight through would put the snapping in the wrong places. A `steps` of 1 --
 * the catalog's minimum, one division, meaning no interior stop -- becomes Compose's 0, which is
 * the continuous slider.
 *
 * A range whose `max` is not above its `min` is refused rather than drawn: Compose requires a
 * non-empty range and would raise inside the composition, taking the surface with it. The catalog
 * requires `max`, so an absent or inverted one is a payload the schema already refuses -- and this
 * is a leaf, so drawing nothing costs only its own space.
 *
 * The value is clamped into the range before it is shown. A data model holding a number outside
 * `min..max` is the agent's to fix, but a thumb drawn off the end of its own track is not a way of
 * saying so.
 */
public val SliderRenderer: ComponentRenderer = ComponentRenderer { scope, modifier ->
    val label = scope.rememberString("label")
    val min = (scope.rememberNumber("min") ?: 0.0).toFloat()
    val max = scope.rememberNumber("max")?.toFloat()
    val value = scope.rememberNumber("value")?.toFloat() ?: min
    val divisions = scope.rememberNumber("steps")?.toInt()
    val target = remember(scope) { scope.binding("value") }
    val failure = scope.rememberCheckFailures().firstMessage()
    if (max != null && max > min) {
        Column(modifier = modifier.leafMargin()) {
            if (label != null) {
                Text(text = label, style = MaterialTheme.typography.labelLarge)
            }
            Slider(
                value = value.coerceIn(min, max),
                // The `Float` is written as a `Float`, not widened first: `0.3f.toDouble()` is
                // 0.30000001192092896, and that number -- not the 0.3 the user chose -- is what
                // would land in the agent's data model and come back in the next event.
                onValueChange = { now -> target?.let { scope.write(it, JsonPrimitive(now)) } },
                enabled = target != null,
                valueRange = min..max,
                // See above: divisions, minus the two ends, and never negative.
                steps = ((divisions ?: 1) - 1).coerceAtLeast(0),
            )
            CheckMessage(failure)
        }
    }
}
