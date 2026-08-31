package dev.ynagai.a2ui.material3

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
 * So the tap's *release* is taken in the pointer input's [PointerEventPass.Initial] pass, before
 * the child sees it; see [interceptTaps], which says why it is the release and nothing earlier.
 * The cost is real and worth naming: a trigger carrying an `action` does not dispatch it, so an
 * agent that wanted to be told the modal opened does not find out. Opening the dialog *and*
 * dispatching is not available as a third option -- the two would have to be the same gesture, and
 * a `Button` that received it would take it.
 *
 * **What the interception does not reach is a pointer, and that is a real gap rather than a
 * rounding error.** It is a `pointerInput`, so it sits on the touch and mouse path only: a
 * keyboard `Enter` on the focused trigger, and an accessibility service activating it, both go
 * straight to the `Button`'s own `clickable` -- which dispatches the trigger's `action` and leaves
 * the dialog shut, the exact inverse of what a tap does. The `semantics` block below was written
 * to close the accessibility half and does not, because a merging node does not absorb a child
 * that merges too, so the `Button` stays a separately activatable node of its own. A `Modal` whose
 * trigger is disabled by a failing `check` is open to a tap for the same reason: this node cannot
 * see the child's enabled state. Closing all three means the trigger opening the modal *itself*
 * rather than having its input stolen, which is a change to how a renderer reaches its children.
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
            // A click on the merged node, so that an accessibility service has something to
            // activate that opens the dialog rather than firing the `Button` underneath -- which
            // the pointer interception above cannot reach, semantics actions not being gestures.
            // **It does not currently achieve that**, and the note on this renderer says why: a
            // merging node does not absorb a child that merges as well, so this node is an
            // unlabelled button and the trigger remains one of its own, still carrying its own
            // click. Kept because it is half of the eventual answer and costs nothing standing.
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
 * Takes the *release* of a tap on this node's subtree before the subtree sees it, calling [onTap].
 *
 * [PointerEventPass.Initial] runs from the root down, so a release consumed here reaches the
 * child's own gesture detector already spent -- and `detectTapGestures`, which is what
 * `Modifier.clickable` runs, reads a consumed release as a cancelled press rather than as a click.
 * That is how the modal opens instead of the `Button` underneath firing. `Modifier.clickable` on
 * this node could not do it: it works in the `Main` pass, which runs from the leaf up and arrives
 * only after the button has taken the event.
 *
 * **Only the release is taken, and nothing before it.** Consuming the press and every move as well
 * would be simpler and is what this did first, but a consumed move is invisible to an ancestor
 * that wanted to scroll: a `Modal` inside a `List` -- itself a `verticalScroll` -- made the whole
 * area of its trigger unscrollable, and then opened the dialog when the finger that had tried to
 * scroll lifted. Leaving the press and the moves alone costs nothing, because the child cannot
 * complete a click without the release this node takes at the end.
 *
 * [waitForUpOrCancellation] is what decides the gesture stayed a tap, and it is stricter than the
 * loop it replaced in two ways that are both wanted. It yields null the moment anything else
 * consumes a change -- which is precisely the ancestor scrollable claiming the drag -- and again
 * when the pointer leaves this node's bounds. So a press that slides off the trigger before
 * lifting no longer opens the dialog, where the old loop counted any release anywhere. That is
 * the behaviour every other control on the surface already has, the trigger `Button` included.
 *
 * Keyed on nothing and reading [onTap] through a cell, rather than keyed on the lambda: a
 * `pointerInput` restarts its block whenever a key changes, so keying on a lambda the caller
 * rebuilds every recomposition would tear down the gesture mid-press.
 */
@Composable
private fun Modifier.interceptTaps(onTap: () -> Unit): Modifier {
    val latest by rememberUpdatedState(onTap)
    return pointerInput(Unit) {
        awaitEachGesture {
            // Observed rather than claimed. `requireUnconsumed = false` because this runs ahead of
            // everyone, so there is nothing that could have consumed it yet -- the flag is about
            // not caring, not about expecting.
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val up = waitForUpOrCancellation(PointerEventPass.Initial) ?: return@awaitEachGesture
            up.consume()
            latest()
        }
    }
}

private const val CLOSE_GLYPH = "close"

/** Material's own dialog padding, so the content is not against the surface's rounded corner. */
private val DIALOG_PADDING = 16.dp

/** Enough tonal lift to read as a surface above the one behind it. */
private val DIALOG_ELEVATION = 6.dp
