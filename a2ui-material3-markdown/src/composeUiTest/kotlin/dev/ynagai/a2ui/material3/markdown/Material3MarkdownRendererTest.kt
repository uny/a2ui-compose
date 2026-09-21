package dev.ynagai.a2ui.material3.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ynagai.a2ui.compose.A2uiPlaceholder
import dev.ynagai.a2ui.compose.A2uiRenderer
import dev.ynagai.a2ui.compose.A2uiRendererConfig
import dev.ynagai.a2ui.compose.A2uiSurface
import dev.ynagai.a2ui.compose.BasicCatalog
import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.AgentToRendererMessage
import dev.ynagai.a2ui.material3.LocalA2uiMarkdownRenderer
import dev.ynagai.a2ui.material3.Material3Components
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The renderer drawn through a `Text`, which is the only way a host ever reaches it.
 *
 * The block conversion is pinned in `commonTest`; what only a composition can settle is that the
 * blocks reach the screen, that the modifier `Text` hands over lands on the outermost node and
 * so keeps the leaf margin, that a caption's blocks shrink with the caption, and that nothing
 * drawn is clickable.
 */
@OptIn(ExperimentalTestApi::class)
class Material3MarkdownRendererTest {
    @Test
    fun the_blocks_the_default_passes_through_are_drawn_as_blocks() = runComposeUiTest {
        setContent { Surface(BLOCKS) }
        // The marker is gone from the item and drawn beside it, which is what "a list" means.
        onNodeWithText("one").assertIsDisplayed()
        onNodeWithText("two").assertIsDisplayed()
        onAllNodesWithText("•").assertCountEquals(2)
        onNodeWithText("quoted").assertIsDisplayed()
        onNodeWithText("val x = 1").assertIsDisplayed()
        onNodeWithText("h1").assertIsDisplayed()
        onNodeWithText("cell").assertIsDisplayed()
        onNodeWithText("Heading").assertIsDisplayed()
    }

    @Test
    fun the_leaf_margin_reaches_the_outermost_block() = runComposeUiTest {
        // `Text` hands over a modifier that already carries the margin; putting it on the column
        // rather than on a block inside it is what keeps a list spaced like every other leaf.
        setContent { Surface(BLOCKS) }
        val heading = onNodeWithText("Heading").fetchSemanticsNode().boundsInRoot
        assertTrue(heading.top > 0f && heading.left > 0f, "the first block should be inset: $heading")
    }

    @Test
    fun a_caption_shrinks_its_blocks_with_it() = runComposeUiTest {
        // The same list drawn as body text and as a caption: the caption's `bodySmall` has to
        // reach the items, not only the prose, or a captioned list is body-sized.
        setContent { Surface(CAPTIONED) }
        val body = onNodeWithText("body item").fetchSemanticsNode().boundsInRoot
        val caption = onNodeWithText("caption item").fetchSemanticsNode().boundsInRoot
        assertTrue(caption.height < body.height, "a caption's item should be shorter: $caption vs $body")
    }

    @Test
    fun nothing_drawn_is_clickable() = runComposeUiTest {
        // The link's label is on screen and the link is not: no node under the surface takes a
        // click, so an agent's `[text](url)` cannot open anything.
        setContent { Surface(LINKS) }
        onNodeWithText("label here", substring = true).assertIsDisplayed()
        onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Composable
    private fun Surface(components: String) {
        MaterialTheme {
            CompositionLocalProvider(LocalA2uiMarkdownRenderer provides Material3MarkdownRenderer) {
                A2uiSurface(
                    renderer = rendererFor(components),
                    surfaceId = SURFACE,
                    registry = Material3Components.Basic,
                    placeholder = A2uiPlaceholder { _, _ -> },
                )
            }
        }
    }

    private fun rendererFor(components: String): A2uiRenderer =
        A2uiRenderer(A2uiRendererConfig.Default).also { renderer ->
            renderer.applyAll(
                listOf(
                    """{"version":"v1.0","createSurface":{"surfaceId":"$SURFACE","catalogId":"CATALOG_ID"}}""",
                    """{"version":"v1.0","updateComponents":{"surfaceId":"$SURFACE","components":$components}}""",
                ).map {
                    A2uiJson.strict.decodeFromString(
                        AgentToRendererMessage.serializer(),
                        it.replace("CATALOG_ID", BasicCatalog.id),
                    )
                },
            )
        }

    private companion object {
        const val SURFACE = "s"

        val BLOCKS = """[
            {"id":"root","component":"Column","children":["md"]},
            {"id":"md","component":"Text","text":"# Heading\n\n- one\n- two\n\n> quoted\n\n```\nval x = 1\n```\n\n| h1 |\n|---|\n| cell |"}
        ]"""

        val CAPTIONED = """[
            {"id":"root","component":"Column","children":["body","caption"]},
            {"id":"body","component":"Text","text":"- body item"},
            {"id":"caption","component":"Text","text":"- caption item","variant":"caption"}
        ]"""

        val LINKS = """[
            {"id":"root","component":"Column","children":["md"]},
            {"id":"md","component":"Text","text":"a [label here](https://example.com) and <https://example.org>"}
        ]"""
    }
}
