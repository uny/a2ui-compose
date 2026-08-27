// Set explicitly because the root build enables `TYPESAFE_PROJECT_ACCESSORS`. Left unset, Gradle
// derives this name from the checkout directory and warns on every invocation: the buildscript
// classpath then differs between a CI checkout and a developer's, so with `org.gradle.caching=true`
// no build-cache entry produced by one is a hit for the other.
rootProject.name = "buildSrc"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
