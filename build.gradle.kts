plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.maven.publish) apply false
}

allprojects {
    group = "dev.ynagai.a2ui"
    version = findProperty("VERSION_NAME")?.toString() ?: "0.1.0-SNAPSHOT"
}
