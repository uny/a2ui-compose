rootProject.name = "a2ui-consumer-smoke-test"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // The producer's catalog, read across the build boundary rather than copied. This build pins
    // the same Kotlin, AGP and Compose versions the library is built with, and the same
    // `compileSdk`/`minSdk`; hand-copied, those drift, and the drift surfaces on a tag -- the one
    // run where a failure costs a version number. `android-compileSdk` in particular becomes
    // `minCompileSdk` in the AAR metadata, and this gate would *not* catch a mismatch: the task
    // graph of `compileAndroidMain` contains no `checkAarMetadata` (verified with `--dry-run`),
    // so a consumer floor raised past this build's `compileSdk` would ship green.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }

    repositories {
        // First, and the point of the whole build: the artifacts under test are resolved from a
        // repository, exactly as a consumer resolves them, rather than by a project dependency
        // that never exercises the published metadata.
        //
        // Restricted to the group under test. Unfiltered it would also shadow every third-party
        // dependency from `~/.m2`, so a stale or hand-installed Kotlin/AndroidX/Compose jar on a
        // developer's machine would silently win over Maven Central and the gate would pass
        // against a dependency set no consumer will ever resolve.
        mavenLocal {
            mavenContent {
                includeGroup("dev.ynagai.a2ui")
            }
        }
        google {
            mavenContent {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}
