package dev.ynagai.a2ui.compose

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.ynagai.a2ui.core.function.A2uiFunctionException
import dev.ynagai.a2ui.core.function.UrlOpener

/** Sends an `ACTION_VIEW` intent to whatever the device handles URLs with. @see rememberPlatformUrlOpener */
@Composable
public actual fun rememberPlatformUrlOpener(): UrlOpener {
    val context = LocalContext.current
    return remember(context) {
        UrlOpener { url ->
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                // The `Context` behind `LocalContext` is not always an `Activity` -- a composable
                // hosted in a `ComposeView` inside a service or a widget has none -- and starting
                // an activity from a non-activity `Context` without this flag throws.
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                throw A2uiFunctionException("openUrl: no activity on this device can open `$url` ($e).")
            }
        }
    }
}
