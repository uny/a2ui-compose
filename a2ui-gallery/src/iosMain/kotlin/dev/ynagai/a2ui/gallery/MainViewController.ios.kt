package dev.ynagai.a2ui.gallery

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * The Gallery as a `UIViewController`, for an Xcode project to set as its root.
 *
 * This is where an iOS entry point stops on the Kotlin side: an app needs an Xcode target, a
 * bundle identifier and a signing identity, none of which belong in a Gradle build. A
 * `UIApplicationDelegate` would set this function's result as its window's `rootViewController`.
 *
 * **Neither iOS target declares `binaries.framework {}`, so no framework is produced and nothing
 * can consume this yet** -- there is no `linkDebugFrameworkIosArm64` task to run. Adding one is a
 * separate piece of work, alongside the Xcode project that would link it; this function exists so
 * that work has an entry point to reach for. What the build proves today is that the Gallery
 * compiles and links for both iOS targets, and `iosSimulatorArm64Test` proves the corpus draws
 * there.
 */
@Suppress("FunctionName", "unused")
public fun MainViewController(): UIViewController = ComposeUIViewController { GalleryApp() }
