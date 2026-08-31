package dev.ynagai.a2ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.ynagai.a2ui.core.function.A2uiFunctionException
import dev.ynagai.a2ui.core.function.UrlOpener
import platform.AppKit.NSWorkspace
import platform.Foundation.NSURL

/** Hands the URL to the default browser through `NSWorkspace`. @see rememberPlatformUrlOpener */
@Composable
public actual fun rememberPlatformUrlOpener(): UrlOpener = remember {
    UrlOpener { url ->
        val target = NSURL.URLWithString(url)
            ?: throw A2uiFunctionException("openUrl: `$url` is not a URL macOS can parse.")
        NSWorkspace.sharedWorkspace.openURL(target)
    }
}
