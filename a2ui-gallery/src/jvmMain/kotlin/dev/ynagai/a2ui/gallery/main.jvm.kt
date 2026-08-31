@file:JvmName("GalleryMain")

package dev.ynagai.a2ui.gallery

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/**
 * The Gallery as a desktop window. `./gradlew :a2ui-gallery:run`.
 *
 * The initial size is wide enough for the three columns rather than the toolkit's default, which
 * is not: the Gallery would open in its narrow single-pane layout on a machine whose screen has
 * room for the layout the blueprint actually specifies.
 */
public fun main(): Unit = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "A2UI Gallery",
        state = rememberWindowState(width = 1280.dp, height = 860.dp),
    ) {
        GalleryApp()
    }
}
