package dev.ynagai.a2ui.gallery

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ynagai.a2ui.compose.A2uiPlaceholder
import dev.ynagai.a2ui.compose.A2uiPlaceholderReason
import dev.ynagai.a2ui.compose.A2uiRenderer
import dev.ynagai.a2ui.compose.A2uiSurface
import dev.ynagai.a2ui.material3.Material3Components
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The specification's own examples, drawn by the shipped registry.
 *
 * The corpus is the acceptance test this library is aiming at, and this is the part of it that has
 * come true: every example built only from the components the registry knows draws with no
 * placeholder at all. That is a stronger claim than "it did not crash" -- a renderer that silently
 * dropped half a surface would pass the second and fail this.
 *
 * The examples are selected by comparing component types, so nothing here has to be edited when
 * the registry grows. What has to be edited is [DrawableExamplesTest], which pins which examples
 * that selection currently yields.
 */
@OptIn(ExperimentalTestApi::class)
class ExampleRenderTest {
    @Test
    fun every_example_the_registry_covers_draws_without_a_single_placeholder() = runComposeUiTest {
        val drawable = EXAMPLES.filter { it.isDrawableBy(Material3Components.Basic.types) }
        assertTrue(drawable.isNotEmpty(), "the corpus should hold examples this registry covers")
        for (example in drawable) {
            val renderer = A2uiRenderer(clock = { CLOCK })
            renderer.applyAll(example.decoded)
            val surfaces = renderer.state.surfaces.filterValues { it.isRenderable }.keys.toList()
            assertEquals(
                1,
                surfaces.size,
                "${example.file}: an example creates exactly one renderable surface",
            )
            val reasons = mutableListOf<A2uiPlaceholderReason>()
            setContent {
                MaterialTheme {
                    A2uiSurface(
                        renderer = renderer,
                        surfaceId = surfaces.single(),
                        registry = Material3Components.Basic,
                        placeholder = A2uiPlaceholder { reason, _ -> reasons += reason },
                    )
                }
            }
            assertEquals(
                emptyList(),
                reasons,
                "${example.file}: an example the registry covers should draw whole",
            )
            assertTrue(
                onRoot().fetchSemanticsNode().children.isNotEmpty(),
                "${example.file}: something should be on screen",
            )
        }
    }

    private companion object {
        const val CLOCK = "2026-08-28T00:00:00Z"
    }
}
