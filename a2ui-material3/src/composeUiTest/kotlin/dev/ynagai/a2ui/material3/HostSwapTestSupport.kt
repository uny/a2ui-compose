package dev.ynagai.a2ui.material3

import androidx.compose.runtime.Composable
import dev.ynagai.a2ui.compose.A2uiRenderer
import dev.ynagai.a2ui.compose.BasicCatalog
import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.AgentToRendererMessage

/** A surface drawing [components], for [MarkdownRendererSwapTest] and [ImageLoaderSwapTest]. */
internal fun hostSwapSurfaceWith(components: String) = A2uiRenderer().also { renderer ->
    renderer.applyAll(
        listOf(
            """{"version":"v1.0","createSurface":{"surfaceId":"s","catalogId":"${BasicCatalog.id}"}}""",
            """{"version":"v1.0","updateComponents":{"surfaceId":"s","components":[$components]}}""",
        ).map { A2uiJson.strict.decodeFromString<AgentToRendererMessage>(it) }
    )
}

/** Non-inline, as `Surface` is: the lambda handed to it is a remembered object, not inlined code. */
@Composable
internal fun HostSwapWrapper(content: @Composable () -> Unit) {
    content()
}
