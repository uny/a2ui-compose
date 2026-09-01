package dev.ynagai.a2ui.gallery

import androidx.compose.ui.window.Window
import platform.AppKit.NSApp
import platform.AppKit.NSApplication

/**
 * The Gallery as a native macOS window. `./gradlew :a2ui-gallery:runDebugExecutableMacosArm64`.
 *
 * `sharedApplication()` before the window and `run()` after it, in that order: Compose's macOS
 * `Window` attaches to the shared application, so creating it first leaves nothing to attach to,
 * and the run loop has to be started last or the call blocks before the window is made.
 */
public fun main() {
    NSApplication.sharedApplication()
    Window(title = "A2UI Gallery") {
        GalleryApp()
    }
    NSApp?.run()
}
