plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.maven.publish) apply false
    // Applied here as well as in each module: the root is where the four modules' HTML is
    // aggregated into one site with cross-module links, which `docs.yml` publishes to GitHub Pages.
    alias(libs.plugins.dokka)
}

allprojects {
    group = "dev.ynagai.a2ui"
    version = findProperty("VERSION_NAME")?.toString() ?: "0.1.0-SNAPSHOT"
}

// The published modules only -- `a2ui-gallery` is an app, not an API anyone depends on.
dependencies {
    dokka(projects.a2uiCore)
    dokka(projects.a2uiCompose)
    dokka(projects.a2uiMaterial3)
    dokka(projects.a2uiMaterial3Markdown)
}
