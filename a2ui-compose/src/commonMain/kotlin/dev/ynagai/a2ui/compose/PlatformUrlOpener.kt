package dev.ynagai.a2ui.compose

import androidx.compose.runtime.Composable
import dev.ynagai.a2ui.core.function.UrlOpener

/**
 * The platform's own way of opening a URL, for [A2uiRenderer]'s `urlOpener`.
 *
 * ```kotlin
 * val opener = rememberPlatformUrlOpener()
 * val renderer = remember { A2uiRenderer(urlOpener = opener) }
 * ```
 *
 * A composable rather than a plain function because of Android: an `Intent` needs a `Context`, and
 * the only `Context` a library can reach without asking the host for one is `LocalContext`. The
 * other six targets have nothing to remember and return the same opener every time.
 *
 * The checks the specification makes mandatory are not repeated here. The evaluator has already
 * refused anything outside `http` and `https` and anything that was not a user action by the time
 * a URL reaches an [UrlOpener] — see `UrlOpener` — so this opens what it is given.
 *
 * **Opt-in, like `systemLocaleFormatter`.** `A2uiRenderer`'s default `urlOpener` does nothing,
 * because a renderer that navigates the host's window by default is not something a library should
 * decide for it.
 */
@Composable
public expect fun rememberPlatformUrlOpener(): UrlOpener
