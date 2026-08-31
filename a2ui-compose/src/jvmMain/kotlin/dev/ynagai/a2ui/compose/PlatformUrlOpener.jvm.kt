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
        // Both steps are converted rather than left to escape. The evaluator admits a URL on its
        // scheme alone, so `https://example.com/a b` reaches here and `URI` rejects it; `browse`
        // reports a launch failure with `IOException`. Neither is `A2uiFunctionException`, which
        // is the one type this module's error channel names and what the other five actuals raise
        // -- so a JVM host caught a `URISyntaxException` where every other target caught nothing.
        val target = runCatching { URI(url) }.getOrElse {
            throw A2uiFunctionException("openUrl: `$url` is not a URI this JVM can parse ($it).")
        }
        runCatching { desktop.browse(target) }.getOrElse {
            throw A2uiFunctionException("openUrl: this JVM could not open `$url` ($it).")
        }
    }
}
