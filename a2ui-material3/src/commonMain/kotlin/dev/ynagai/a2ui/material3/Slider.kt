package dev.ynagai.a2ui.material3

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.ynagai.a2ui.compose.A2uiComponentScope
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
    val min = scope.finite("min") ?: 0f
    val max = scope.finite("max")
    val value = scope.finite("value") ?: min
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
                // See above: divisions, minus the two ends, never negative -- and bounded above,
                // because Compose's `steps` is an allocation. Material builds one tick fraction
                // per stop as a `FloatArray(steps + 2)`, so the agent's number is an array size:
                // `steps: 1e9` asks for four gigabytes, and `steps: 2147483647` saturates
                // `Double.toInt()` to `Int.MAX_VALUE`, whose `+ 2` wraps negative and raises
                // `NegativeArraySizeException` inside the composition. Clamped rather than
                // refused, like the value above -- see [MAX_DIVISIONS].
                steps = ((divisions ?: 1).coerceAtMost(MAX_DIVISIONS) - 1).coerceAtLeast(0),
            )
            CheckMessage(failure)
        }
    }
}

/**
 * Property [name] as a finite `Float`, or null when it is absent or is not one.
 *
 * **Checked after the narrowing, not before.** `A2uiComponentScope.number` reads the primitive's
 * text, so a data model holding the string `"NaN"` or `"Infinity"` -- which an agent can write and
 * a user can type into a `TextField` bound to the same pointer -- resolves to a non-finite
 * `Double`; and `1e300`, which is finite as a `Double`, becomes `Float.POSITIVE_INFINITY` on the
 * way down. Both have to be caught here, because neither survives contact with a slider: `NaN`
 * passes `coerceIn` untouched -- both of its comparisons are false -- and Material then raises
 * `IllegalArgumentException("current must not be NaN")` building the slider's own
 * `ProgressBarRangeInfo`, which takes the whole surface down.
 */
@Composable
private fun A2uiComponentScope.finite(name: String): Float? =
    rememberNumber(name)?.toFloat()?.takeIf { it.isFinite() }

/**
 * The most discrete divisions a `Slider` will draw.
 *
 * A bound on an agent-controlled magnitude, like `MAX_SVG_PATH` on a path and `maxInstances` on a
 * subtree. There is no readable slider past a few dozen stops, so this is far above any real
 * payload; what it refuses is the payload that is not a slider.
 */
private const val MAX_DIVISIONS = 1000
