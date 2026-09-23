package dev.ynagai.a2ui.material3

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ynagai.a2ui.compose.A2uiRenderer
import dev.ynagai.a2ui.compose.A2uiSurface
import dev.ynagai.a2ui.compose.BasicCatalog
import dev.ynagai.a2ui.compose.ComponentRenderer
import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.AgentToRendererMessage
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * What a `Card` draws, read back as pixels (#28).
 *
 * `.border` and `.clip` take no layout space, so no bounds assertion can see either of them: each
 * could be deleted from [CardRenderer] with every other test in this module still green. Before
 * #26 they were `OutlinedCard`'s to keep; since then only these two tests keep them.
 *
 * The card's child is a host renderer that paints far past its own bounds, flooding everything
 * behind the card with [FLOOD] unless something clips it. Nothing a catalog payload can put in a
 * card reaches past the 16dp padding, so this is the only way to give the clip something to do.
 *
 * `captureToImage()` is common API and was measured to read back correctly on every target this
 * source set runs on -- jvm, macosArm64, iosSimulatorArm64 and wasmJs in a headless Chrome -- so
 * these live here rather than in a JVM-only set. iosArm64 is compiled, never run.
 */
@OptIn(ExperimentalTestApi::class)
class CardOutlineTest {
    @Test
    fun a_card_draws_its_outline_over_what_it_holds() = runComposeUiTest {
        setContent { FloodedCard() }
        val card = cardOrigin()
        val pixels = onRoot().captureToImage().toPixelMap()
        val midY = card.y + CARD_PADDING_PX
        // The outermost column of the card's own box: `Modifier.border` insets a rounded stroke by
        // half its width, so a 1dp hairline covers exactly this pixel along a straight side.
        assertEquals(OUTLINE, pixels.at(card.x, midY), "the card's left edge should be its outline")
        // One column in is the content again, or an outline grown to fill the card would pass.
        assertEquals(FLOOD, pixels.at(card.x + 1, midY), "the outline should be one pixel wide")
    }

    @Test
    fun a_card_clips_what_it_holds_to_its_rounded_shape() = runComposeUiTest {
        setContent { FloodedCard() }
        val card = cardOrigin()
        val pixels = onRoot().captureToImage().toPixelMap()
        // The flood is drawn, or a child that stopped drawing would keep this pin green.
        assertEquals(FLOOD, pixels.at(card.x + CARD_PADDING_PX, card.y + CARD_PADDING_PX))
        // The corner of the card's bounding box lies outside `shapes.medium`'s 12dp arc, so only a
        // *rounded* clip keeps it clear -- a rectangular one anywhere above the card would not.
        assertNotEquals(FLOOD, pixels.at(card.x, card.y), "the card's corner should be clipped")
    }

    private fun ComposeUiTest.cardOrigin(): Offset {
        // The card draws no semantics node, so it is found from its child: the child sits inside
        // the card's padding, and the border and clip add nothing to its offset.
        val child = onNodeWithTag(CHILD_TAG).fetchSemanticsNode().boundsInRoot
        return Offset(child.left - CARD_PADDING_PX, child.top - CARD_PADDING_PX)
    }

    @Composable
    private fun FloodedCard() {
        MaterialTheme(colorScheme = lightColorScheme(outlineVariant = OUTLINE)) {
            A2uiSurface(surfaceWith(CARD_ROOT), SURFACE_ID, REGISTRY)
        }
    }

    private fun PixelMap.at(x: Float, y: Float): Color = this[x.roundToInt(), y.roundToInt()]

    private fun surfaceWith(components: String) = A2uiRenderer().also { renderer ->
        renderer.applyAll(
            listOf(
                """{"version":"v1.0","createSurface":{"surfaceId":"$SURFACE_ID","catalogId":"${BasicCatalog.id}"}}""",
                """{"version":"v1.0","updateComponents":{"surfaceId":"$SURFACE_ID","components":[$components]}}""",
            ).map { A2uiJson.strict.decodeFromString<AgentToRendererMessage>(it) }
        )
    }

    private companion object {
        const val SURFACE_ID = "s"
        const val CHILD_TAG = "flood"

        /** The composeUiTest harness draws at a density of 1, so a dp is a pixel here. */
        const val CARD_PADDING_PX = 16f

        /** Saturated and unlike each other, so neither reads as the other or as the background. */
        val OUTLINE = Color.Magenta
        val FLOOD = Color.Red

        const val CARD_ROOT =
            """{"id":"root","component":"Card","child":"inner"},""" +
                """{"id":"inner","component":"Text","text":"unused"}"""

        /** `Text` overridden rather than a type invented, so the payload stays catalog-valid. */
        val REGISTRY = Material3Components.Basic.with(
            mapOf(
                "Text" to ComponentRenderer { _, modifier ->
                    Box(
                        modifier.testTag(CHILD_TAG).size(40.dp).drawBehind {
                            drawRect(FLOOD, topLeft = Offset(-1000f, -1000f), size = Size(2000f, 2000f))
                        },
                    )
                },
            ),
        )
    }
}
