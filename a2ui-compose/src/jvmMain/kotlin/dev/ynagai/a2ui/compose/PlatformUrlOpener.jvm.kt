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
        val desktop = Desktop.getDesktop().takeIf {
            Desktop.isDesktopSupported() && it.isSupported(Desktop.Action.BROWSE)
        }
        // A headless JVM, or one on a desktop with no registered browser. Raising rather than
        // returning quietly: `openUrl` is an action the user just took, and a request that goes
        // nowhere with no trace is the failure mode `UrlOpener` was given no default to avoid.
            ?: throw A2uiFunctionException("openUrl: this JVM cannot open a browser.")
        desktop.browse(URI(url))
    }
}
