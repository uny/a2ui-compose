package dev.ynagai.a2ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.ynagai.a2ui.core.function.UrlOpener

/** Opens a new browsing context. @see rememberPlatformUrlOpener */
@Composable
public actual fun rememberPlatformUrlOpener(): UrlOpener = remember {
    UrlOpener { url -> openInNewContext(url) }
}

/**
 * `window.open` with the protections `UrlOpener` names.
 *
 * `noopener` is the one that matters: without it the page that opens gets a live `window.opener`
 * handle back to the renderer's own document and can navigate it, which is the tab-nabbing attack
 * `UrlOpener`'s documentation puts on the browser implementation. `noreferrer` withholds the
 * referrer as well, so an agent-supplied URL cannot learn where it was rendered.
 *
 * **The result is discarded, and it has to be.** Setting `noopener` makes the window open steps
 * return null on success as well as on failure — the specification's step is "if noopener is true
 * and target is not `_self`, `_parent` or `_top`, then return null" — so there is no popup-blocker
 * signal left to read here. Reading it as one raised on every successful open instead.
 */
private fun openInNewContext(url: String) {
    js("window.open(url, '_blank', 'noopener,noreferrer')")
}
