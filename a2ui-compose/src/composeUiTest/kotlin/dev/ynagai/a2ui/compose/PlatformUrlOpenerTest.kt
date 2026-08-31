package dev.ynagai.a2ui.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ynagai.a2ui.core.function.UrlOpener
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * That each target has a [rememberPlatformUrlOpener] and that composing it is safe.
 *
 * **A smoke test, and named as one.** What the opener does — an `Intent`, an `NSWorkspace` call, a
 * `window.open` — leaves the process, so a test that asserted it worked would either open a browser
 * on the machine running it or assert against a stub that is not the code that ships. What is
 * checkable is the part that actually broke during the work: the `expect` has an `actual` on every
 * target, and composing and recomposing it raises nothing.
 *
 * **What this does not check, having tried: that the opener is `remember`ed rather than rebuilt.**
 * Identity looked like the probe for it and is not one — every implementation but Android's builds
 * a `UrlOpener` lambda that captures nothing, which Kotlin compiles to a singleton, so the same
 * instance comes back with or without the `remember`. Deleting the `remember` from the JVM `actual`
 * left this file green, which is how that was found. Android's is the one that captures — its
 * `Context` — and Android is the target with no host test task in this module, so the claim is
 * untested on the only target where it could fail. It is a real gap and not a covered one.
 *
 * Everything before the platform call — the `http`/`https` allowlist and the user-activation
 * requirement the specification makes mandatory — is enforced by the evaluator and covered by its
 * own tests against an injected [UrlOpener].
 */
@OptIn(ExperimentalTestApi::class)
class PlatformUrlOpenerTest {

    @Test
    fun every_target_composes_an_opener_and_recomposes_without_raising() = runComposeUiTest {
        val seen = mutableListOf<UrlOpener>()
        var tick by mutableStateOf(0)
        setContent {
            // Read first, so the composable subscribes and the write below actually recomposes it.
            @Suppress("UNUSED_EXPRESSION")
            tick
            seen += rememberPlatformUrlOpener()
        }
        tick = 1
        waitForIdle()

        assertTrue(seen.size >= 2, "the composable did not recompose, so nothing was exercised")
    }
}
