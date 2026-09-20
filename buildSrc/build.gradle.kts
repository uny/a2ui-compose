plugins {
    `kotlin-dsl`
}

dependencies {
    // Unversioned: the Kotlin Gradle plugin that `kotlin-dsl` applies pins `kotlin-test` to its own
    // Kotlin, and picks the JUnit 5 variant from `useJUnitPlatform()` below.
    testImplementation(kotlin("test"))
}

// Not run by the root build. Since Gradle 8, `buildSrc` is assembled with `jar`, not `build`, so
// its tests neither run nor compile unless `:buildSrc:test` is named -- which `build.yml` does.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
