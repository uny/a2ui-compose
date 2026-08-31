package dev.ynagai.a2ui.material3

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.ynagai.a2ui.compose.ComponentRenderer
import dev.ynagai.a2ui.compose.RenderChild
import dev.ynagai.a2ui.compose.rememberChildren

/**
 * `Modal` -- a trigger on the surface, and a dialog behind it.
 *
 * Not a container. The guide calls this a "Modal Entry Point": what the surface shows is the
 * `trigger` child, and the `content` child exists only while the dialog is open. So this renderer
 * draws one of its two children and holds the other in reserve, which is why a closed `Modal`
 * composes nothing of its content -- and why an unopened modal's subtree costs no instances.
 *
 * **The trigger's taps are intercepted, and its own action does not run.** "The modal logic
 * intercepts interactions (taps/clicks) on the `trigger`" is the guide's own sentence, and it has
 * to be read that strongly to work at all: every trigger in the corpus is a `Button`, and a
 * `Button` consumes its own clicks -- a wrapper waiting for one that got through would never open.
 * So the gesture is taken in the pointer input's [PointerEventPass.Initial] pass, before the child
 * sees it. The cost is real and worth naming: a trigger carrying an `action` does not dispatch it,
 * so an agent that wanted to be told the modal opened does not find out. Opening the dialog *and*
 * dispatching is not available as a third option -- the two would have to be the same gesture, and
 * a `Button` that received it would take it.
 *
 * **Closing is a button, a tap outside, and the platform's back gesture.** The guide asks for a
 * mechanism and offers three; Compose's `Dialog` already carries the second and third, and the
 * explicit control is here because the other two are invisible. Its name comes from
 * [LocalA2uiStrings] -- a `Modal` has no text in the catalog to borrow, which is the same bind a
 * picker's confirm and cancel are in.
 *
 * The dialog is a centred surface rather than a bottom sheet. The guide splits the two by platform
 * -- popup on desktop, sheet on mobile -- and this module draws one surface for every target it
 * publishes to; a host that wants the sheet on Android registers its own `Modal` renderer, which
 * is the same escape hatch `Icon` offers.
 *
 * **Open-ness is the renderer's state, like `Tabs`'s selected index.** The catalog gives a `Modal`
 * nothing to bind it to, so an agent can neither open one nor learn that it is open.
 */
public val ModalRenderer: ComponentRenderer = ComponentRenderer { scope, modifier ->
    var open by remember(scope) { mutableStateOf(false) }
    val trigger = scope.rememberChildren("trigger")
    val strings = LocalA2uiStrings.current
    Box(
        modifier
            .interceptTaps { open = true }
            // The merged node's own click, so that a screen reader activating the trigger opens
            // the dialog rather than firing the `Button` underneath -- which the pointer
            // interception above cannot reach, semantics actions not being gestures. Merging keeps
            // the trigger's label: the child's semantics fold into this node, and a key this node
            // has already set is the one that survives, which is exactly the click.
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick { open = true; true }
            },
    ) {
        trigger.forEach { scope.RenderChild(it) }
    }
    if (!open) return@ComponentRenderer
    Dialog(onDismissRequest = { open = false }) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = DIALOG_ELEVATION) {
            Column(Modifier.padding(DIALOG_PADDING)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = { open = false }) {
                        val vector = remember { ICON_PATHS[CLOSE_GLYPH]?.let { iconVector(it) } }
                        if (vector != null) {
                            Icon(imageVector = vector, contentDescription = strings.close)
                        }
                    }
                }
                // No scrolling wrapper. A `List` inside the content scrolls on its own, and a
                // second scrollable of the same axis around it measures the inner one against an
                // infinite height -- which raises rather than degrades. The dialog's own window
                // bounds the content instead.
                scope.rememberChildren("content").forEach { scope.RenderChild(it) }
            }
        }
    }
}

/**
 * Takes every tap on this node's subtree before the subtree sees it, calling [onTap] on release.
 *
 * [PointerEventPass.Initial] runs from the root down, so consuming here leaves nothing for the
 * child's own gesture detector -- which is the whole point, the child being a `Button` that would
 * otherwise swallow the tap. `Modifier.clickable` cannot do this: it works in the `Main` pass,
 * which runs from the leaf up and reaches this node only after the button has taken the event.
 *
 * A release anywhere counts, including one that wandered off the trigger first. Distinguishing a
 * cancelled press would mean tracking bounds through consumed events for a control whose whole
 * hit area is the child; opening on a slipped tap is the friendlier half of that trade.
 *
 * Keyed on nothing and reading [onTap] through a cell, rather than keyed on the lambda: a
 * `pointerInput` restarts its block whenever a key changes, so keying on a lambda the caller
 * rebuilds every recomposition would tear down the gesture mid-press.
 */
@Composable
private fun Modifier.interceptTaps(onTap: () -> Unit): Modifier {
    val latest by rememberUpdatedState(onTap)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                // Down, consumed so the child never starts a press of its own.
                var event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.none { it.pressed }) continue
                event.changes.forEach { it.consume() }
                // Everything until the last finger lifts, consumed for the same reason: a child
                // that saw the move would scroll or drag under a gesture this node has claimed.
                while (event.changes.any { it.pressed }) {
                    event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                }
                latest()
            }
        }
    }
}

private const val CLOSE_GLYPH = "close"

/** Material's own dialog padding, so the content is not against the surface's rounded corner. */
private val DIALOG_PADDING = 16.dp

/** Enough tonal lift to read as a surface above the one behind it. */
private val DIALOG_ELEVATION = 6.dp
