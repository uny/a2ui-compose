package dev.ynagai.a2ui.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ynagai.a2ui.compose.A2uiRenderer
import dev.ynagai.a2ui.compose.A2uiRendererConfig
import dev.ynagai.a2ui.compose.A2uiSurface
import dev.ynagai.a2ui.material3.Material3Components
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The specification's examples at a phone's width: every piece of text is on screen and has room.
 *
 * [ExampleRenderTest] proves the corpus draws whole; this proves it draws *legibly* where the
 * width is short. A `Row` that measures its children in order hands a wrapping text the whole
 * width and its sibling nothing, so a price, a date or a label measures at zero -- on screen in
 * the semantics tree, invisible on the surface, and reported by nothing. The corpus has that
 * shape in most of its files (a `label`/`value` pair in a row is the common case), which is why
 * the assertion walks every example rather than the one the bug was found in.
 *
 * Two allowances. A text inside a horizontally scrolling container is allowed past the right
 * edge -- that is what scrolling is for -- and a text the agent left empty has no width to
 * assert.
 */
@OptIn(ExperimentalTestApi::class)
class PhoneWidthLayoutTest {
    @Test
    fun every_text_in_the_corpus_has_room_at_a_phones_width() = runComposeUiTest {
        val drawable = EXAMPLES.filter { it.isDrawableBy(Material3Components.Basic.types) }
        assertTrue(drawable.isNotEmpty(), "the corpus should hold examples this registry covers")
        val complaints = mutableListOf<String>()
        for (example in drawable) {
            val renderer = A2uiRenderer(A2uiRendererConfig.Default.withClock { CLOCK })
            renderer.applyAll(example.decoded)
            val surface = renderer.state.surfaces.filterValues { it.isRenderable }.keys.single()
            val width = WIDER[example.file] ?: PHONE_WIDTH
            setContent {
                Box(Modifier.size(width, SURFACE_HEIGHT)) {
                    MaterialTheme {
                        A2uiSurface(renderer = renderer, surfaceId = surface, registry = Material3Components.Basic)
                    }
                }
            }
            val root = onRoot().fetchSemanticsNode()
            val right = root.boundsInRoot.right
            root.walk(scrolling = false) { node, scrolling ->
                val text = node.config.getOrNull(SemanticsProperties.Text)
                    ?.joinToString("") { it.text }
                    ?.takeIf { it.isNotBlank() }
                    ?: return@walk
                // The unclipped geometry. `boundsInRoot` is cut to every ancestor's clip, so a
                // text below the fold of a scrolling `List`, or past the edge of a `Card`, reads
                // as an empty rectangle -- which is the symptom under test, seen from the wrong
                // side. Size and position are the layout's own numbers.
                val width = node.size.width
                val end = node.positionInRoot.x + width
                if (width <= 0) {
                    complaints += "${example.file}: \"${text.take(40)}\" has no width"
                } else if (!scrolling && end > right + TOLERANCE) {
                    complaints += "${example.file}: \"${text.take(40)}\" runs off the right edge (ends at $end, edge at $right)"
                }
            }
        }
        assertTrue(complaints.isEmpty(), complaints.joinToString("\n", prefix = "\n"))
    }

    private fun SemanticsNode.walk(scrolling: Boolean, visit: (SemanticsNode, Boolean) -> Unit) {
        val scrolls = scrolling || config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null
        visit(this, scrolls)
        children.forEach { it.walk(scrolls, visit) }
    }

    private companion object {
        const val CLOCK = "2026-08-28T00:00:00Z"

        /** Every phone, and the width at which a 300dp image cap stops covering for a starved sibling. */
        val PHONE_WIDTH = 320.dp

        /**
         * The examples whose *minimum* content is wider than a phone, drawn at the narrowest width
         * that holds them. A two-pane editor beside a preview puts "Celebrating" and a guest's name
         * side by side in half of 320dp, and no fair share fits two words into a slot narrower than
         * the words: the row overflows, as a flex row on the web would. What the test still holds
         * such an example to is the same claim at a width its author evidently meant it for.
         */
        val WIDER = mapOf("30_live-invitation-builder.json" to 480.dp)

        /** Tall enough that nothing is height-constrained. */
        val SURFACE_HEIGHT = 2000.dp

        /** A pixel of rounding, not a tolerance for overflow. */
        const val TOLERANCE = 1f
    }
}
