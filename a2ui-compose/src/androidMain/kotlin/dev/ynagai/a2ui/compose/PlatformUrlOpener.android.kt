package dev.ynagai.a2ui.compose

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.ynagai.a2ui.core.function.A2uiFunctionException
import dev.ynagai.a2ui.core.function.UrlOpener

/**
 * Sends an `ACTION_VIEW` intent to whatever the device handles URLs with.
 *
 * **Android's background-activity-launch restrictions are not handled, and cannot be from here.**
 * A `startActivity` made while the app is not in the foreground is dropped by the system with a log
 * line rather than an exception, so an `openUrl` in that state goes nowhere and this reports
 * nothing. What keeps it from mattering is the layer above: the evaluator refuses `openUrl` outside
 * `InvocationContext.USER_ACTION`, so a call reaching here followed a user's own tap.
 *
 * @see rememberPlatformUrlOpener
 */
@Composable
public actual fun rememberPlatformUrlOpener(): UrlOpener {
    val context = LocalContext.current
    return remember(context) {
        UrlOpener { url ->
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                // The `Context` behind `LocalContext` is not always an `Activity` -- a composable
                // hosted in a `ComposeView` inside a service or a widget has none -- and starting
                // an activity from a non-activity `Context` without this flag throws.
                //
                // Added only in that case. Setting it unconditionally also changes what happens
                // from an `Activity`: the browser lands in a task of its own rather than on top of
                // the host's, so returning from it does not come back to the surface the user was
                // looking at. That is a navigation decision, and not one to make for every host.
                if (context.findActivity() == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                throw A2uiFunctionException("openUrl: no activity on this device can open `$url` ($e).")
            }
        }
    }
}

/**
 * The [Activity] this context is or wraps, or null when there is none.
 *
 * `LocalContext` is frequently a `ContextThemeWrapper` around the activity rather than the activity
 * itself, so the wrappers are unwrapped rather than the type tested once.
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
