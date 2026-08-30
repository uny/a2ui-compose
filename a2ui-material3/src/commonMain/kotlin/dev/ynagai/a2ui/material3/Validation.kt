package dev.ynagai.a2ui.material3

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.ynagai.a2ui.compose.A2uiCheckFailure
import dev.ynagai.a2ui.core.protocol.Severity

/**
 * The message a failing check puts under an input.
 *
 * Drawn by every input in this module and by none of the containers, because `checks` is the
 * catalog's `Checkable` mixin and that is exactly who carries it. A `Button` is the one checkable
 * that shows nothing here: the protocol gives it a different consequence -- it is disabled -- and a
 * button that was both greyed out and captioned with the reason would say it twice.
 *
 * **Severity chooses the colour and nothing else.** An `error` is the theme's error colour, a
 * `warning` or an `info` the ordinary supporting-text colour, so a check that is not fatal reads
 * as a remark rather than as a fault. Which failure is shown is the first that carries a message;
 * a rule whose result and whose `message` are both absent has nothing to say and is skipped rather
 * than drawn as an empty line.
 */
@Composable
internal fun CheckMessage(failure: A2uiCheckFailure?, modifier: Modifier = Modifier) {
    val message = failure?.message ?: return
    Text(
        text = message,
        modifier = modifier,
        color = if (failure.severity == Severity.ERROR) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        style = MaterialTheme.typography.bodySmall,
    )
}
