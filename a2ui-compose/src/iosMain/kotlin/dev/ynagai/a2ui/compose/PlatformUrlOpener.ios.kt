package dev.ynagai.a2ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.ynagai.a2ui.core.function.A2uiFunctionException
import dev.ynagai.a2ui.core.function.UrlOpener
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/** Hands the URL to whichever app iOS has registered for it. @see rememberPlatformUrlOpener */
@Composable
public actual fun rememberPlatformUrlOpener(): UrlOpener = remember {
    UrlOpener { url ->
        val target = NSURL.URLWithString(url)
            ?: throw A2uiFunctionException("openUrl: `$url` is not a URL iOS can parse.")
        // The completion handler is where iOS reports refusal, and it runs after this call has
        // returned. There is nothing to report it to -- `UrlOpener` is synchronous and the action
        // that triggered it has already been dispatched -- so the outcome is dropped rather than
        // pretended about. What a caller can rely on is that the request was made.
        UIApplication.sharedApplication.openURL(target, options = emptyMap<Any?, Any>(), completionHandler = null)
    }
}
