package dev.ynagai.a2ui.gallery

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * The Gallery as a `UIViewController`, for an Xcode project to set as its root.
 *
 * This is where an iOS entry point stops on the Kotlin side: an app needs an Xcode target, a
 * bundle identifier and a signing identity, none of which belong in a Gradle build. The framework
 * this compiles into exports this function, and a `UIApplicationDelegate` sets its result as the
 * window's `rootViewController`.
 *
 * No Xcode project is checked in, so **nothing in this repository launches this** -- what the build
 * proves is that the Gallery compiles and links for both iOS targets, and `iosSimulatorArm64Test`
 * proves the corpus draws there.
 */
@Suppress("FunctionName", "unused")
public fun MainViewController(): UIViewController = ComposeUIViewController { GalleryApp() }
