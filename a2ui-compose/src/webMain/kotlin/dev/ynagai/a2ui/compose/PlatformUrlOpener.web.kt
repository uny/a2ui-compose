package dev.ynagai.a2ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.ynagai.a2ui.core.function.A2uiFunctionException
import dev.ynagai.a2ui.core.function.UrlOpener

/** Opens a new browsing context. @see rememberPlatformUrlOpener */
@Composable
public actual fun rememberPlatformUrlOpener(): UrlOpener = remember {
    UrlOpener { url ->
        if (!openInNewContext(url)) {
            throw A2uiFunctionException("openUrl: the browser blocked opening `$url`.")
        }
    }
}

/**
 * `window.open` with the protections `UrlOpener` names, returning whether a context was opened.
 *
 * `noopener` is the one that matters: without it the page that opens gets a live `window.opener`
 * handle back to the renderer's own document and can navigate it, which is the tab-nabbing attack
 * `UrlOpener`'s documentation puts on the browser implementation. `noreferrer` withholds the
 * referrer as well, so an agent-supplied URL cannot learn where it was rendered.
 *
 * A popup blocker returns `null` rather than throwing, and that is the false this reports.
 */
private fun openInNewContext(url: String): Boolean =
    js("!!window.open(url, '_blank', 'noopener,noreferrer')")
