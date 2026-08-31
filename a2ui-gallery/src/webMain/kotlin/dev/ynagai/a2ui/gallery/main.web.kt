package dev.ynagai.a2ui.gallery

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport

/**
 * The Gallery in a browser, for both web targets.
 *
 * Kotlin/JS and Kotlin/Wasm share this file. The two differ in how they reach the DOM -- which is
 * why `rememberPlatformUrlOpener` is written against `js(...)` rather than `kotlinx.browser` -- but
 * neither is touched here: the container is named by id, so nothing in this file is DOM-typed at
 * all. The element it names lives in `webMain/resources/index.html`, which both distributions copy.
 *
 * This is the only place a browser runs this renderer. Kotlin/JS has no working Compose UI test
 * harness (see `a2ui-compose`'s build script), so the JS target's rendering is covered here rather
 * than by a test.
 */
@OptIn(ExperimentalComposeUiApi::class)
public fun main() {
    ComposeViewport(viewportContainerId = VIEWPORT_ID) {
        GalleryApp()
    }
}

/** The id of the element the composition is mounted into. Shared with `index.html`. */
private const val VIEWPORT_ID = "a2ui-gallery"
