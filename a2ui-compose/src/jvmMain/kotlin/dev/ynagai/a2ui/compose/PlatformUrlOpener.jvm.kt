package dev.ynagai.a2ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.ynagai.a2ui.core.function.A2uiFunctionException
import dev.ynagai.a2ui.core.function.UrlOpener
import java.awt.Desktop
import java.net.URI

/** Hands the URL to the desktop's browser through AWT. @see rememberPlatformUrlOpener */
@Composable
public actual fun rememberPlatformUrlOpener(): UrlOpener = remember {
    UrlOpener { url ->
        // `isDesktopSupported` is asked before `getDesktop`, not inside a `takeIf` after it:
        // `getDesktop` itself throws on a headless JVM, so testing the instance meant never
        // reaching the check that was supposed to produce the message below.
        val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
        // A headless JVM, or one on a desktop with no registered browser. Raising rather than
        // returning quietly: `openUrl` is an action the user just took, and a request that goes
        // nowhere with no trace is the failure mode `UrlOpener` was given no default to avoid.
        if (desktop == null || !desktop.isSupported(Desktop.Action.BROWSE)) {
            throw A2uiFunctionException("openUrl: this JVM cannot open a browser.")
        }
        desktop.browse(URI(url))
    }
}
