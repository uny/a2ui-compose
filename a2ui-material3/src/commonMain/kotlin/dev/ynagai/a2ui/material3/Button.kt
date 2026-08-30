package dev.ynagai.a2ui.material3

import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.ynagai.a2ui.compose.A2uiComponentScope
import dev.ynagai.a2ui.compose.ComponentRenderer
import dev.ynagai.a2ui.compose.RenderChild
import dev.ynagai.a2ui.compose.hasError
import dev.ynagai.a2ui.compose.rememberAction
import dev.ynagai.a2ui.compose.rememberCheckFailures
import dev.ynagai.a2ui.compose.rememberChildren
import dev.ynagai.a2ui.compose.rememberString

/**
 * `Button` -- dispatches its action, and draws its child inside itself.
 *
 * The child is drawn rather than a label read off the component: the catalog gives a `Button` a
 * `child` reference and no text of its own, so an icon-only button is the same shape as a labelled
 * one and neither is a special case here.
 *
 * **The action is resolved at the tap, not here.** [A2uiComponentScope.dispatch] evaluates the
 * action's bindings when it runs, which is what the implementation guide means by "dynamically
 * resolving the context variables at the moment of the interaction" -- a button that captured them
 * at composition time would send the agent whatever the data model held when the screen was drawn.
 *
 * A button whose `action` is absent or will not decode still draws and still responds; it just has
 * nothing to dispatch. Disabling it instead would present an agent's malformed payload to the user
 * as a deliberately unavailable action.
 *
 * **A failing `check` does disable it**, because that is the one the protocol asks for by name:
 * "If any check fails, the button is automatically disabled. This allows the button's state to
 * depend on the validity of data in the model." Errors alone -- see
 * [hasError][dev.ynagai.a2ui.compose.hasError] -- so a `warning` result can say something without
 * taking the action away. The button shows no message of its own: the inputs the checks are about
 * carry theirs, and a greyed button captioned with the reason would say it twice.
 */
public val ButtonRenderer: ComponentRenderer = ComponentRenderer { scope, modifier ->
    val variant = scope.rememberString("variant")
    val action = scope.rememberAction("action")
    val enabled = !scope.rememberCheckFailures().hasError()
    val onClick = { if (action != null) scope.dispatch(action) }
    when (variant) {
        // The theme's primary colour as a background with contrasting text, which is exactly what
        // Material 3's filled `Button` is.
        "primary" -> Button(
            onClick = onClick,
            modifier = modifier.leafMargin(),
            enabled = enabled,
        ) { scope.Child() }
        // "No visual border or background, making its child content appear like a clickable link."
        "borderless" -> TextButton(
            onClick = onClick,
            modifier = modifier.leafMargin(),
            enabled = enabled,
        ) { scope.Child() }
        // "A subtle background and border." Material 3 has no button that is both: the outlined
        // one carries the border and leaves the container transparent, the tonal one carries a
        // container and no border. The border is the half that distinguishes a default button from
        // a borderless one, so it is the half kept.
        else -> OutlinedButton(
            onClick = onClick,
            modifier = modifier.leafMargin(),
            enabled = enabled,
        ) { scope.Child() }
    }
}

/**
 * The one child a button wraps.
 *
 * A list because that is what the adapter layer returns; the catalog types `child` as a single
 * reference, so it holds one entry, or none while the child is still in flight. Iterated rather
 * than indexed so a budget marker standing in for a dropped child is drawn like any other entry.
 */
@Composable
private fun A2uiComponentScope.Child() {
    rememberChildren("child").forEach { RenderChild(it, Modifier) }
}
